package me.cinematic.media;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Immutable published indexes; scanning and native probing never run on the Minecraft thread. */
public final class MediaLibrary implements AutoCloseable {
    public record ProbeResult(long durationMs, int width, int height, boolean audio, List<AudioTrack> audioTracks) {
        public ProbeResult(long durationMs, int width, int height, boolean audio) { this(durationMs, width, height, audio, List.of()); }
        public ProbeResult { audioTracks = AudioTrack.boundedTracks(audioTracks); }
    }
    @FunctionalInterface
    public interface Probe { ProbeResult read(Path path) throws Exception; }

    private static final Set<String> EXTENSIONS = Set.of("mp4", "mkv", "webm", "avi", "mp3", "wav", "ogg", "m4a", "mov", "flac");
    private final Path root;
    private final Probe probe;
    private final Consumer<String> report;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Cinematic library").factory());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Map<String, MediaEntry> entries = Map.of();
    private volatile String status = "Media library has not been scanned";

    public MediaLibrary(Path root, Probe probe, Consumer<String> report) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) { throw new IOException("Media directory must not be a symbolic link: " + root); }
        this.root = root.toRealPath();
        this.probe = probe;
        this.report = report;
    }

    public CompletableFuture<Integer> reload() {
        if (!refreshing.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A media scan is already running"));
        }
        status = "Scanning media…";
        return CompletableFuture.supplyAsync(() -> {
            try {
                var next = new LinkedHashMap<String, MediaEntry>();
                var failures = new ArrayList<String>();
                List<Path> paths;
                try (var walk = Files.walk(root, 8)) {
                    paths = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .filter(MediaLibrary::supported).sorted().limit(4097).toList();
                }
                if (paths.size() > 4096) { throw new IOException("Media library exceeds 4096 files; remove files and reload"); }
                for (Path path : paths) {
                    if (Thread.currentThread().isInterrupted()) { throw new IOException("Media scan cancelled"); }
                    try {
                        if (!safePath(path)) { continue; }
                        long size = Files.size(path);
                        long modified = Files.getLastModifiedTime(path).toMillis();
                        String id = opaqueId(root.relativize(path).toString(), size, modified);
                        MediaEntry entry = entries.get(id);
                        if (entry == null) {
                            ProbeResult info = probe.read(path);
                            entry = new MediaEntry(id, path.getFileName().toString(), path, size,
                                    modified, info.durationMs(), info.width(), info.height(), info.audio(), info.audioTracks());
                        }
                        next.put(id, entry);
                    } catch (Exception | LinkageError e) {
                        failures.add(path.getFileName() + ": " + describe(e));
                    }
                }
                entries = Map.copyOf(next);
                status = next.size() + " media files" + (failures.isEmpty() ? "" : "; " + failures.size() + " invalid (see server log)");
                failures.forEach(report);
                report.accept(status);
                return next.size();
            } catch (IOException e) {
                status = "Cannot scan media: " + e.getMessage();
                report.accept(status);
                throw new java.util.concurrent.CompletionException(e);
            } finally { refreshing.set(false); }
        }, worker);
    }

    private boolean safePath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) { return false; }
        Path current = root;
        for (Path part : root.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) { return false; }
        }
        return path.toRealPath().startsWith(root);
    }

    public SeekableByteChannel open(MediaEntry entry) throws IOException {
        if (entries.get(entry.id()) != entry || !safePath(entry.path())
                || !Files.isRegularFile(entry.path(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(entry.path()) != entry.size()
                || Files.getLastModifiedTime(entry.path()).toMillis() != entry.modifiedMs()) {
            throw new IOException("Media changed or is missing; run /cinematic reload");
        }
        return Files.newByteChannel(entry.path(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    public static String opaqueId(String relative, long size, long modified) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (relative + "\0" + size + "\0" + modified).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString();
        return EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }

    public static String describe(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    public MediaEntry get(String id) { return entries.get(id); }
    public List<MediaEntry> entries() {
        return entries.values().stream().sorted(Comparator.comparing(MediaEntry::name).thenComparing(MediaEntry::id)).toList();
    }
    public String status() { return status; }
    public boolean refreshing() { return refreshing.get(); }
    @Override public void close() { worker.shutdownNow(); entries = Map.of(); }
}
