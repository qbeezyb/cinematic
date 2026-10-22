package me.cinematic.media;

import java.nio.file.Path;

public record MediaEntry(String id, String name, Path path, long size, long modifiedMs,
        long durationMs, int width, int height, boolean audio) {
    public MediaEntry {
        if (!id.matches("[a-f0-9]{32}") || name.length() > 256 || size < 0
                || durationMs <= 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid media metadata");
        }
    }
}
