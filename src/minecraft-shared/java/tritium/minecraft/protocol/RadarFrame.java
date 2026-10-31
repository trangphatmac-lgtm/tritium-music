package tritium.minecraft.protocol;

/** One complete game-authored Radar frame. Coordinates use Minecraft's scaled GUI units. */
public final class RadarFrame {
    public static final int MAX_POINTS = 4096;
    public boolean visible, tracers;
    public double viewportWidth, viewportHeight;
    public double x = 5, y = 70, width = 100, height = 100;
    public double centerX = 55.5, centerY = 122, selfRadius = 5, targetRadius = 3, borderWidth = 1;
    public float tracerWidth = .5f;
    public int background = 0x7d000000, border = 0xffffffff, self = 0xffffffff, target = 0xffff0000;
    /** Alternating x/y coordinates in the same coordinate system as the frame. */
    public double[] points = new double[0];

    public boolean valid() {
        if (!visible) return true;
        if (!positive(viewportWidth, 65536) || !positive(viewportHeight, 65536)
                || !coordinate(x) || !coordinate(y) || !positive(width, 16384) || !positive(height, 16384)
                || !coordinate(centerX) || !coordinate(centerY) || !positive(selfRadius, 1024)
                || !positive(targetRadius, 1024) || !positive(borderWidth, 128) || !positive(tracerWidth, 128)
                || points == null || points.length % 2 != 0 || points.length > MAX_POINTS * 2) return false;
        for (double value : points) if (!coordinate(value)) return false;
        return true;
    }
    private static boolean positive(double value, double max) { return value > 0 && value <= max; }
    private static boolean coordinate(double value) { return !Double.isNaN(value) && Math.abs(value) <= 1000000; }

    /** Fit the game viewport uniformly, preserving both shape and position within that viewport. */
    public Placement fit(double width, double height) {
        double scale = Math.min(width / viewportWidth, height / viewportHeight);
        return new Placement((width - viewportWidth * scale) / 2, (height - viewportHeight * scale) / 2, scale);
    }
    public static final class Placement {
        public final double x, y, scale;
        public Placement(double x, double y, double scale) { this.x = x; this.y = y; this.scale = scale; }
    }
}
