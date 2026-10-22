package me.cinematic.core;

import java.util.UUID;

/** Owned by the server thread, independent of the controller's chunk lifecycle. */
public final class ScreenSession {
    private final UUID id;
    private ScreenKey key;
    private final int facing;
    private int width;
    private int height;
    private String mediaId;
    private Timeline timeline;
    private int audioStream = -1;
    private long revision;

    public ScreenSession(UUID id, ScreenKey key, int facing) {
        this(id, key, facing, 1, 1, "", new Timeline(0, 0, 0, false, false), 0);
    }

    public ScreenSession(UUID id, ScreenKey key, int facing, int width, int height,
            String mediaId, Timeline timeline, long revision) {
        this(id, key, facing, width, height, mediaId, timeline, revision, -1);
    }

    public ScreenSession(UUID id, ScreenKey key, int facing, int width, int height,
            String mediaId, Timeline timeline, long revision, int audioStream) {
        validateDimensions(width, height);
        if (audioStream < -1) { throw new IllegalArgumentException("Invalid audio stream"); }
        this.audioStream = audioStream;
        if (facing < 0 || facing > 3 || revision < 0 || mediaId == null || mediaId.length() > 64) {
            throw new IllegalArgumentException("Invalid screen state");
        }
        this.id = id;
        this.key = key;
        this.facing = facing;
        this.width = width;
        this.height = height;
        this.mediaId = mediaId;
        this.timeline = timeline;
        this.revision = revision;
    }

    public static void validateDimensions(int width, int height) {
        if (width < 1 || width > 40 || height < 1 || height > 40) {
            throw new IllegalArgumentException("Screen width and height must each be 1–40 blocks");
        }
    }

    public void resize(int width, int height) {
        validateDimensions(width, height);
        this.width = width;
        this.height = height;
        revision++;
    }

    public void reshape(ScreenKey origin, int width, int height) {
        validateDimensions(width, height);
        if (!key.equals(origin) || this.width != width || this.height != height) {
            key = origin;
            resize(width, height);
        }
    }

    public double distanceSquared(double x, double y, double z) {
        return PanelGeometry.distanceSquared(key, facing, width, height, x, y, z);
    }

    public void select(String mediaId, long durationMs, long nowMs) {
        if (mediaId == null || !mediaId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Unknown media ID");
        }
        this.mediaId = mediaId;
        audioStream = -1;
        timeline = new Timeline(durationMs, 0, nowMs, false, timeline.looping());
        revision++;
    }

    public void selectAudio(int stream, long nowMs) {
        if (stream < 0) { throw new IllegalArgumentException("Invalid audio stream"); }
        audioStream = stream;
        timeline = timeline.prepare(nowMs);
        revision++;
    }

    public int audioStream() { return audioStream; }

    public void updateTimeline(Timeline value) {
        timeline = value;
        revision++;
    }

    public UUID id() { return id; }
    public ScreenKey key() { return key; }
    public int facing() { return facing; }
    public int width() { return width; }
    public int height() { return height; }
    public String mediaId() { return mediaId; }
    public Timeline timeline() { return timeline; }
    public long revision() { return revision; }
}
