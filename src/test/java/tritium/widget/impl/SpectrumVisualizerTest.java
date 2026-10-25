package tritium.widget.impl;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumVisualizerTest {
    @Test void volumeModesComeFromTheSameFftAndRemainFiniteWhenMuted() {
        SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, 4096, 1024);
        float[] input = new float[2048]; Arrays.fill(input, .01f);
        var quiet = visualizer.processFrame(input, .2);
        var loud = visualizer.processFrame(input, .8);
        assertArrayEquals(quiet.relative, loud.relative);
        assertTrue(quiet.absolute[0] > loud.absolute[0]);
        var muted = visualizer.processFrame(new float[2048], 0);
        for (float value : muted.absolute) assertTrue(Float.isFinite(value));
    }

    @Test void publishedFrameDoesNotChangeWhenTheNextFftIsProcessed() {
        SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, 4096, 128);
        float[] magnitudes = new float[2048];
        Arrays.fill(magnitudes, .01f);
        float[] published = visualizer.processFFT(magnitudes);
        float[] expected = published.clone();
        assertTrue(expected[0] > 0);

        float[] next = visualizer.processFFT(new float[2048]);
        assertNotSame(published, next);
        assertArrayEquals(expected, published, "IPC may still be reading the previous frame");
        next[0] = Float.NaN;
        assertTrue(Float.isFinite(visualizer.processFFT(new float[0])[0]), "Consumers cannot corrupt the working buffer");
    }

    @Test void silenceAndInvalidFftBinsAlwaysProduceFiniteNonNegativeBands() {
        SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, 4096, 128);
        for (float value : new float[]{0, 1e-12f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE}) {
            float[] magnitudes = new float[2048];
            Arrays.fill(magnitudes, value);
            for (float band : visualizer.processFFT(magnitudes)) {
                assertTrue(Float.isFinite(band));
                assertTrue(band >= 0);
            }
        }
    }
}
