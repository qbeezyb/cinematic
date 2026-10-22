package me.cinematic.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

/** Local preferences keyed by server identity and the persistent screen UUID. */
public final class PersonalAudio {
    private final Path path;
    private final Properties values = new Properties();
    private String server = "";
    private boolean dirty;

    public PersonalAudio(Path path) { this.path = path; }

    public void load() throws IOException {
        if (Files.exists(path) && Files.size(path) <= 1024 * 1024) {
            try (var in = Files.newInputStream(path)) { values.load(in); }
        }
    }

    public void server(String identity) {
        try {
            server = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private String key(UUID screen) { return server + "." + screen; }
    public double level(UUID screen) {
        try {
            double value = Double.parseDouble(values.getProperty(key(screen) + ".volume", "1"));
            return Double.isFinite(value) ? Math.clamp(value, 0, 1) : 1;
        } catch (NumberFormatException e) { return 1; }
    }
    public boolean muted(UUID screen) { return Boolean.parseBoolean(values.getProperty(key(screen) + ".muted", "false")); }
    public double volume(UUID screen) { return muted(screen) ? 0 : level(screen); }
    public void level(UUID screen, double value) {
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("Volume must be finite"); }
        values.setProperty(key(screen) + ".volume", Double.toString(Math.clamp(value, 0, 1))); dirty = true;
    }
    public void muted(UUID screen, boolean value) { values.setProperty(key(screen) + ".muted", Boolean.toString(value)); dirty = true; }
    public void flush() throws IOException {
        if (!dirty) { return; }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "cinematic-audio-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temporary)) { values.store(out, "Cinematic personal screen audio"); }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } finally { Files.deleteIfExists(temporary); }
    }
}
