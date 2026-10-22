package tritium.ncm.music;

import repackage.com.jsyn.unitgen.UnitFilter;
import repackage.processing.sound.Effect;

/** Stereo, non-resonant one-pole filtering for the transition's gentle frequency handover. */
final class TransitionFilter extends Effect<TransitionFilter.Pole> {
    @Override protected Pole newInstance() { return new Pole(); }

    void configure(double lowPassHz, double highPassHz) {
        left.configure(lowPassHz, highPassHz);
        right.configure(lowPassHz, highPassHz);
    }

    static final class Pole extends UnitFilter {
        private volatile double lowPassHz;
        private volatile double highPassHz;
        private double lowState, highState;
        private double lowCoefficient = 1, highCoefficient;

        void configure(double lowPassHz, double highPassHz) {
            this.lowPassHz = Math.max(0, lowPassHz);
            this.highPassHz = Math.max(0, highPassHz);
        }

        @Override public void generate(int start, int limit) {
            double rate = getFrameRate();
            double lowTarget = lowPassHz == 0 ? 1 : coefficient(lowPassHz, rate);
            double highTarget = highPassHz == 0 ? 0 : coefficient(highPassHz, rate);
            double[] in = input.getValues(), out = output.getValues();
            for (int i = start; i < limit; i++) {
                // Smooth parameter steps over roughly 5 ms; no allocation in the audio thread.
                lowCoefficient += .004 * (lowTarget - lowCoefficient);
                highCoefficient += .004 * (highTarget - highCoefficient);
                lowState += lowCoefficient * (in[i] - lowState);
                highState += highCoefficient * (lowState - highState);
                out[i] = lowState - (highTarget == 0 && highCoefficient < 1e-8 ? 0 : highState);
            }
        }

        static double coefficient(double hz, double sampleRate) {
            return 1 - Math.exp(-2 * Math.PI * Math.min(hz, sampleRate * .45) / sampleRate);
        }
    }
}
