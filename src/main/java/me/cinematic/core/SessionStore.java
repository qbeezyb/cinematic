package me.cinematic.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Versioned, world-local atomic persistence. Loaded timelines always start paused. */
public final class SessionStore {
    private static final int MAGIC = 0x43494E45;
    private static final int VERSION = 1;
    private static final int MAX_SCREENS = 100_000;
    private final Path file;

    public SessionStore(Path file) { this.file = file; }

    public List<ScreenSession> load(long nowMs) throws IOException {
        if (!Files.exists(file)) { return List.of(); }
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported Cinematic sessions file: " + file);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_SCREENS) { throw new IOException("Invalid screen count"); }
            var result = new ArrayList<ScreenSession>(count);
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                ScreenKey key = new ScreenKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                int facing = in.readInt();
                int width = in.readInt();
                int height = in.readInt();
                String media = in.readUTF();
                long duration = in.readLong();
                long position = in.readLong();
                boolean loop = in.readBoolean();
                long revision = in.readLong();
                result.add(new ScreenSession(id, key, facing, width, height, media,
                        new Timeline(duration, position, nowMs, false, loop), revision));
            }
            if (in.read() != -1) { throw new IOException("Trailing session data"); }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw new IOException("Corrupt Cinematic sessions file: " + file, e);
        }
    }

    public void save(Collection<ScreenSession> screens, long nowMs) throws IOException {
        if (screens.size() > MAX_SCREENS) { throw new IOException("Too many cinema screens"); }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "cinematic-", ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(screens.size());
                for (ScreenSession s : screens) {
                    out.writeLong(s.id().getMostSignificantBits());
                    out.writeLong(s.id().getLeastSignificantBits());
                    out.writeUTF(s.key().dimension());
                    out.writeInt(s.key().x());
                    out.writeInt(s.key().y());
                    out.writeInt(s.key().z());
                    out.writeInt(s.facing());
                    out.writeInt(s.width());
                    out.writeInt(s.height());
                    out.writeUTF(s.mediaId());
                    out.writeLong(s.timeline().durationMs());
                    out.writeLong(s.timeline().positionAt(nowMs));
                    out.writeBoolean(s.timeline().looping());
                    out.writeLong(s.revision());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
