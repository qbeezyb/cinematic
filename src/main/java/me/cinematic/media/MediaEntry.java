package me.cinematic.media;

import java.nio.file.Path;
import java.util.List;

public record MediaEntry(String id, String name, Path path, long size, long modifiedMs,
        long durationMs, int width, int height, boolean audio, List<AudioTrack> audioTracks) {
    public MediaEntry(String id, String name, Path path, long size, long modifiedMs, long durationMs, int width, int height, boolean audio) {
        this(id, name, path, size, modifiedMs, durationMs, width, height, audio, List.of());
    }
    public int resolvedAudioStream(int selected) {
        return audioTracks.stream().anyMatch(t -> t.streamIndex() == selected) ? selected : AudioTrack.defaultStream(audioTracks);
    }

    public MediaEntry {
        audioTracks = AudioTrack.boundedTracks(audioTracks);
        if (!id.matches("[a-f0-9]{32}") || name.length() > 256 || size < 0
                || durationMs <= 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid media metadata");
        }
    }
}
