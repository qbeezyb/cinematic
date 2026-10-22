package me.cinematic.core;

public record ScreenKey(String dimension, int x, int y, int z) {
    public ScreenKey {
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("Invalid dimension");
        }
    }

    public double distanceSquared(double px, double py, double pz) {
        double dx = px - (x + 0.5);
        double dy = py - (y + 0.5);
        double dz = pz - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
