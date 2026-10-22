package me.cinematic.core;

/** Plane basis: facing is SOUTH=0, WEST=1, NORTH=2, EAST=3. */
public final class PanelGeometry {
    private PanelGeometry() {}

    public record Point(double x, double y, double z) {}
    public record Fit(double x, double y, double width, double height) {}

    public static Point point(ScreenKey key, int facing, double right, double up, double outward) {
        return switch (facing) {
            case 0 -> new Point(key.x() + right, key.y() + up, key.z() + 1 + outward);
            case 1 -> new Point(key.x() - outward, key.y() + up, key.z() + right);
            case 2 -> new Point(key.x() + 1 - right, key.y() + up, key.z() - outward);
            case 3 -> new Point(key.x() + 1 + outward, key.y() + up, key.z() + 1 - right);
            default -> throw new IllegalArgumentException("Facing must be horizontal");
        };
    }

    public static Fit fit(double panelWidth, double panelHeight, int videoWidth, int videoHeight) {
        if (panelWidth <= 0 || panelHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return new Fit(0, 0, panelWidth, panelHeight);
        }
        double scale = Math.min(panelWidth / videoWidth, panelHeight / videoHeight);
        double w = videoWidth * scale;
        double h = videoHeight * scale;
        return new Fit((panelWidth - w) / 2, (panelHeight - h) / 2, w, h);
    }
}
