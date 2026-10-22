package tritium.ncm.music;

import org.junit.jupiter.api.Test;
import tritium.ncm.music.automix.AutoMixAutomation.FilterSetting;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.automix.AutoMixAutomation.FilterType.*;

/** Runs the same per-sample DSP as JSyn over synthetic PCM, without opening an audio device. */
class AdvancedTransitionFilterTest {
    @Test void simultaneousHighAndLowPassLeaveTheMiddleAndAttenuateBothEnds() {
        List<FilterSetting> band = List.of(new FilterSetting(LOW_PASS, 1_500), new FilterSetting(HIGH_PASS, 300));
        assertTrue(response(band, 100) < .12, "The high-pass stage must remove low frequencies");
        assertTrue(response(band, 6_000) < .08, "The low-pass stage must remove high frequencies");
        assertTrue(response(band, 700) > .85, "The middle band must remain audible");
    }

    @Test void twoLowPassStagesAreCascadedInsteadOfCollapsedIntoOneCutoff() {
        double one = response(List.of(new FilterSetting(LOW_PASS, 1_500)), 6_000);
        double two = response(List.of(new FilterSetting(LOW_PASS, 1_500), new FilterSetting(LOW_PASS, 1_500)), 6_000);
        assertTrue(one > .02);
        assertTrue(two < one * .2, "A second identical stage must add attenuation");
    }

    @Test void sequentialLowPassSegmentsKeepTheirSlotsAndBypassRestoresTheSignal() {
        TestCascade filter = new TestCascade(48_000);
        filter.configure(List.of(new FilterSetting(LOW_PASS, 1_200), new FilterSetting(LOW_PASS, 0)));
        double first = sineRms(filter, 6_000);
        filter.configure(List.of(new FilterSetting(LOW_PASS, 0), new FilterSetting(LOW_PASS, 1_200)));
        double second = sineRms(filter, 6_000);
        assertEquals(first, second, 1e-6);
        filter.configure(List.of(new FilterSetting(LOW_PASS, 0), new FilterSetting(LOW_PASS, 0)));
        assertEquals(.5 / Math.sqrt(2), sineRms(filter, 6_000), 1e-6);
    }

    @Test void eachChannelHasIndependentIntegratorState() {
        TestCascade left = new TestCascade(48_000), right = new TestCascade(48_000);
        var setting = List.of(new FilterSetting(LOW_PASS, 300), new FilterSetting(HIGH_PASS, 50));
        left.configure(setting);
        right.configure(setting);
        sineRms(left, 200);
        for (int block = 0; block < 1_000; block++) {
            java.util.Arrays.fill(right.input.getValues(), 0);
            right.generate(0, right.input.getValues().length);
            for (double value : right.output.getValues()) assertEquals(0, value, 0);
        }
    }

    @Test void activationBypassAndSweepsAreSmoothFiniteAndBoundedAcrossSampleRates() {
        for (int rate : new int[]{44_100, 48_000, 96_000}) {
            TestCascade filter = new TestCascade(rate);
            double previous = .25;
            filter.configure(List.of(new FilterSetting(LOW_PASS, 20)));
            for (int block = 0; block < 2_000; block++) {
                java.util.Arrays.fill(filter.input.getValues(), .25);
                if (block == 500) filter.configure(List.of(new FilterSetting(LOW_PASS, 0)));
                filter.generate(0, filter.input.getValues().length);
                for (double value : filter.output.getValues()) {
                    assertTrue(Double.isFinite(value));
                    assertTrue(Math.abs(value - previous) < .01, "Activation and bypass must not jump");
                    previous = value;
                }
            }
            assertEquals(.25, previous, 1e-7);
            for (int block = 0; block < 4_000; block++) {
                if (block % 100 == 0) {
                    double low = block % 200 == 0 ? 10 : 20_000;
                    double high = block % 200 == 0 ? 20_000 : 10;
                    filter.configure(List.of(new FilterSetting(LOW_PASS, low), new FilterSetting(HIGH_PASS, high),
                            new FilterSetting(LOW_PASS, low), new FilterSetting(HIGH_PASS, high),
                            new FilterSetting(LOW_PASS, low), new FilterSetting(HIGH_PASS, high),
                            new FilterSetting(LOW_PASS, low), new FilterSetting(HIGH_PASS, high)));
                }
                double[] in = filter.input.getValues();
                for (int i = 0; i < in.length; i++) in[i] = .5 * Math.sin((block * in.length + i) * .317);
                filter.generate(0, in.length);
                for (double value : filter.output.getValues()) {
                    assertTrue(Double.isFinite(value));
                    assertTrue(Math.abs(value) < 8, "No unstable resonance during an extreme sweep");
                }
            }
        }
    }

    private static double response(List<FilterSetting> settings, double frequency) {
        TestCascade filter = new TestCascade(48_000);
        filter.configure(settings);
        return sineRms(filter, frequency) / (.5 / Math.sqrt(2));
    }

    private static double sineRms(TestCascade filter, double frequency) {
        double squares = 0;
        int counted = 0, frame = 0;
        double[] in = filter.input.getValues();
        // 1/2 s warm-up, then exactly 1/2 s measurement at the test sample rate.
        while (frame < filter.rate) {
            int length = Math.min(in.length, filter.rate - frame);
            for (int i = 0; i < length; i++) in[i] = .5 * Math.sin(2 * Math.PI * frequency * (frame + i) / filter.rate);
            filter.generate(0, length);
            double[] out = filter.output.getValues();
            for (int i = 0; i < length; i++) {
                if (frame + i >= filter.rate / 2) { squares += out[i] * out[i]; counted++; }
            }
            frame += length;
        }
        return Math.sqrt(squares / counted);
    }

    private static final class TestCascade extends AdvancedTransitionFilter.Cascade {
        final int rate;
        TestCascade(int rate) { this.rate = rate; }
        @Override public int getFrameRate() { return rate; }
    }
}
