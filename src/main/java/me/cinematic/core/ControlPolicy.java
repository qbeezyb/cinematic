package me.cinematic.core;

public final class ControlPolicy {
    public static final double MAX_DISTANCE = 16;

    private ControlPolicy() {}

    public static void check(ScreenSession screen, String dimension, double x, double y, double z,
            boolean remoteInEitherHand, long expectedRevision) {
        if (!remoteInEitherHand) {
            throw new IllegalArgumentException("Hold a Cinematic Remote in either hand");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !screen.key().dimension().equals(dimension)
                || screen.key().distanceSquared(x, y, z) > MAX_DISTANCE * MAX_DISTANCE) {
            throw new IllegalArgumentException("Move within 16 blocks of this screen in the same dimension");
        }
        if (expectedRevision != screen.revision()) {
            throw new IllegalArgumentException("Screen changed; wait for the updated controls");
        }
    }
}
