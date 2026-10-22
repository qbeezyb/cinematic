package me.cinematic.core;

/** Immutable server timeline. All time values use milliseconds on one monotonic clock. */
public record Timeline(long durationMs, long positionMs, long anchorMs, boolean playing, boolean looping) {
    public Timeline {
        if (durationMs < 0 || positionMs < 0 || positionMs > durationMs) {
            throw new IllegalArgumentException("Invalid media duration or position");
        }
    }

    public long positionAt(long nowMs) {
        long elapsed = playing ? Math.max(0, nowMs - anchorMs) : 0;
        long position = elapsed > Long.MAX_VALUE - positionMs ? Long.MAX_VALUE : positionMs + elapsed;
        return looping && durationMs > 0 ? position % durationMs : Math.min(durationMs, position);
    }

    public boolean playingAt(long nowMs) {
        return playing && durationMs > 0 && (looping || positionAt(nowMs) < durationMs);
    }

    public Timeline play(long nowMs) {
        long position = positionAt(nowMs);
        return new Timeline(durationMs, position == durationMs ? 0 : position, nowMs, durationMs > 0, looping);
    }

    public Timeline pause(long nowMs) {
        return new Timeline(durationMs, positionAt(nowMs), nowMs, false, looping);
    }

    public Timeline stop(long nowMs) {
        return new Timeline(durationMs, 0, nowMs, false, looping);
    }

    public Timeline seek(long targetMs, long nowMs) {
        return new Timeline(durationMs, Math.clamp(targetMs, 0, durationMs), nowMs, playingAt(nowMs), looping);
    }

    public Timeline loop(boolean enabled, long nowMs) {
        return new Timeline(durationMs, positionAt(nowMs), nowMs, playingAt(nowMs), enabled);
    }
}
