package tritium.ncm.music;

import repackage.com.jsyn.unitgen.UnitFilter;
import repackage.processing.sound.Effect;
import tritium.ncm.music.automix.AutoMixAutomation.FilterSetting;
import tritium.ncm.music.automix.AutoMixAutomation.FilterType;

import java.util.List;

/**
 * Desktop adaptation of the multi-EQ protocol, not a reconstruction of the unknown native DSP.
 * Each stable slot is a second-order Butterworth low/high-pass stage; both stereo channels have
 * their own integrator state. Cutoff and bypass changes settle smoothly instead of rewiring
 * the synthesis graph at every automation point.
 */
final class AdvancedTransitionFilter extends Effect<AdvancedTransitionFilter.Cascade> {
    static final int MAX_STAGES = 8;

    @Override protected Cascade newInstance() { return new Cascade(); }

    void configure(List<FilterSetting> settings) {
        List<FilterSetting> copy = List.copyOf(settings);
        if (copy.size() > MAX_STAGES) throw new IllegalArgumentException("Too many AutoMix EQ stages");
        left.configure(copy);
        right.configure(copy);
    }

    static class Cascade extends UnitFilter {
        private static final double DAMPING = Math.sqrt(2);
        private static final double SMOOTH_SECONDS = .005;
        private volatile List<FilterSetting> settings = List.of();
        private final Stage[] stages = new Stage[MAX_STAGES];
        private final double[] targetG = new double[MAX_STAGES];
        private final double[] targetWet = new double[MAX_STAGES];
        private final double[] targetHighPass = new double[MAX_STAGES];

        Cascade() {
            for (int i = 0; i < stages.length; i++) stages[i] = new Stage();
        }

        void configure(List<FilterSetting> settings) {
            if (settings.size() > MAX_STAGES) throw new IllegalArgumentException("Too many AutoMix EQ stages");
            this.settings = List.copyOf(settings);
        }

        @Override public void generate(int start, int limit) {
            List<FilterSetting> current = settings;
            double rate = getFrameRate();
            double smoothing = -Math.expm1(-1 / (rate * SMOOTH_SECONDS));
            // Frequency conversion occurs once per block. All arrays and stage states are reused.
            for (int slot = 0; slot < MAX_STAGES; slot++) {
                Stage stage = stages[slot];
                FilterSetting setting = slot < current.size() ? current.get(slot) : null;
                double frequency = setting == null ? 0 : setting.frequencyHz();
                boolean active = Double.isFinite(frequency) && frequency > 0;
                targetWet[slot] = active ? 1 : 0;
                targetG[slot] = active ? Math.tan(Math.PI * Math.min(frequency, rate * .45) / rate) : stage.g;
                targetHighPass[slot] = setting != null && setting.type() == FilterType.HIGH_PASS ? 1 : 0;
                if (!stage.initialized && active) {
                    stage.g = targetG[slot];
                    stage.highPass = targetHighPass[slot];
                    stage.initialized = true;
                }
            }
            double[] in = input.getValues(), out = output.getValues();
            for (int frame = start; frame < limit; frame++) {
                double sample = in[frame];
                for (int slot = 0; slot < MAX_STAGES; slot++) {
                    Stage stage = stages[slot];
                    if (!stage.initialized) continue;
                    stage.g += smoothing * (targetG[slot] - stage.g);
                    stage.wet += smoothing * (targetWet[slot] - stage.wet);
                    stage.highPass += smoothing * (targetHighPass[slot] - stage.highPass);
                    // Topology-preserving state-variable form remains stable during cutoff sweeps.
                    double a1 = 1 / (1 + stage.g * (stage.g + DAMPING));
                    double a2 = stage.g * a1;
                    double v3 = sample - stage.ic2;
                    double v1 = a1 * stage.ic1 + a2 * v3;
                    double low = stage.ic2 + a2 * stage.ic1 + stage.g * a2 * v3;
                    stage.ic1 = 2 * v1 - stage.ic1;
                    stage.ic2 = 2 * low - stage.ic2;
                    double high = sample - DAMPING * v1 - low;
                    double filtered = low + stage.highPass * (high - low);
                    sample += stage.wet * (filtered - sample);
                }
                out[frame] = sample;
            }
        }

        private static final class Stage {
            double g, wet, highPass, ic1, ic2;
            boolean initialized;
        }
    }
}
