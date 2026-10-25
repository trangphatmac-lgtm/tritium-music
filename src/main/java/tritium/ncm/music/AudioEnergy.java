// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia's playback visualizer bridge; see THIRD_PARTY_NOTICES.md.
package tritium.ncm.music;

/** Immutable music-analysis snapshot, independent of HUD gain and spectrum layout preferences. */
public record AudioEnergy(double power, double bass, double lowMid, double mid, double vocal, double treble, long sampledAt) {
    public static final AudioEnergy SILENT = new AudioEnergy(0, 0, 0, 0, 0, 0, 0);

    /** Folia's five frequency bands, byte-analyser dB range and perceptual response curves. */
    public static final class Analyzer {
        private final double[] smoothed;
        private long lastSample;

        public Analyzer(int fftSize) { smoothed = new double[fftSize / 2]; }

        public synchronized void reset() {
            java.util.Arrays.fill(smoothed, 0);
            lastSample = 0;
        }

        public synchronized AudioEnergy analyze(float[] magnitudes, double sampleRate, long now) {
            double seconds = lastSample == 0 ? 1 / 60.0 : Math.max(0, (now - lastSample) / 1e9);
            lastSample = now;
            double retention = Math.pow(.6, seconds * 60);
            for (int i = 0; i < smoothed.length; i++) {
                double value = i < magnitudes.length && Float.isFinite(magnitudes[i]) ? Math.max(0, magnitudes[i]) : 0;
                smoothed[i] = smoothed[i] * retention + value * (1 - retention);
            }
            if (!(sampleRate > 0) || !Double.isFinite(sampleRate)) return SILENT;
            double bass = band(20, 150, sampleRate), low = band(150, 400, sampleRate);
            return new AudioEnergy(Math.pow((bass + low) / 2, 3), Math.pow(bass, 1.8), low * low,
                    Math.pow(band(400, 1200, sampleRate), 2), Math.pow(band(1000, 3500, sampleRate), 1.5),
                    Math.pow(band(3500, 12000, sampleRate), 2), now);
        }

        private double band(int low, int high, double sampleRate) {
            double binHz = sampleRate / (smoothed.length * 2);
            int start = Math.min(smoothed.length - 1, (int) (low / binHz));
            int end = Math.min(smoothed.length - 1, (int) (high / binHz));
            double sum = 0;
            for (int i = start; i <= end; i++) {
                double db = 20 * Math.log10(Math.max(1e-10, smoothed[i]));
                sum += Math.max(0, Math.min(1, (db + 100) / 70));
            }
            return sum / Math.max(1, end - start + 1);
        }
    }
}
