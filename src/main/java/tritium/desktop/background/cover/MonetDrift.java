// Adapted from Folia's monetBackgroundDrift.ts (AGPL-3.0); see tritium/background/cover/NOTICE.txt.
package tritium.desktop.background.cover;

/** Folia's normalized, seamless 240-second drift track at its default strength of 0.5. */
public final class MonetDrift {
    private static final int SAMPLES = 156;
    private static final double[][] TRACK = build();
    private MonetDrift() { }

    public static double[] at(double seconds) {
        double index = ((seconds % 240 + 240) % 240) / 240 * SAMPLES;
        int start = (int) index;
        double fraction = index - start;
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) result[c] = TRACK[c][start] * (1 - fraction) + TRACK[c][start + 1] * fraction;
        return result;
    }

    private static double[][] build() {
        double[][] values = new double[3][SAMPLES + 1];
        double[] seeds = {12.9898, 78.233, 39.425};
        for (int c = 0; c < 3; c++) {
            double peak = 0;
            for (int i = 0; i <= SAMPLES; i++) {
                double t = 76.0 * i / SAMPLES;
                values[c][i] = (noise(t, 76, seeds[c]) * .68 + noise(t * 2, 152, seeds[c] + 17.31) * .32) * 2 - 1;
                peak = Math.max(peak, Math.abs(values[c][i]));
            }
            for (int i = 0; i <= SAMPLES; i++) {
                double normalized = peak > 0 ? values[c][i] / peak : 0;
                values[c][i] = c < 2 ? normalized * .017 : 1.044 + .0175 * (normalized + 1) / 2;
            }
        }
        return values;
    }

    private static double noise(double t, int points, double seed) {
        int cell = (int) Math.floor(t);
        double f = t - cell;
        double a = hash(Math.floorMod(cell, points), seed), b = hash(Math.floorMod(cell + 1, points), seed);
        return a + (b - a) * f * f * (3 - 2 * f);
    }

    private static double hash(int cell, double seed) {
        double v = Math.sin(cell * 127.1 + seed * 311.7) * 43758.5453;
        return v - Math.floor(v);
    }
}
