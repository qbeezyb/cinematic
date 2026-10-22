package me.cinematic.core;

/** Shared controls are server-wide; possession and optimistic revisions remain mandatory. */
public final class ControlPolicy {
    private ControlPolicy() {}

    public static void check(ScreenSession screen, boolean remoteInEitherHand, long expectedRevision) {
        if (!remoteInEitherHand) {
            throw new IllegalArgumentException("Hold a Cinematic Remote in either hand");
        }
        if (expectedRevision != screen.revision()) {
            throw new IllegalArgumentException("Screen changed; wait for the updated controls");
        }
    }
}
