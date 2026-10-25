package tritium.widget.impl;

import lombok.Getter;
import tritium.desktop.DesktopAppState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SpectrumVisualizer {

    @Getter
    private final int sampleRate;
    @Getter
    private final int fftSize;
    @Getter
    private final int numBands;
    private final float minDB = -90f;
    private final float maxDB = 0f;

    @Getter
    private final List<FrequencyBand> bands = new ArrayList<>();
    private final float[] bandMagnitudes;
    private final int maxBin;

    private final float lowFreqCutoff = 500.0f;

    public SpectrumVisualizer(int sampleRate, int fftSize, int numBands) {
        this.sampleRate = sampleRate;
        this.fftSize = fftSize;
        this.numBands = numBands;
        this.maxBin = fftSize / 2 - 1;

        generateFrequencyBands();
        this.bandMagnitudes = new float[bands.size()];
    }

    private void generateFrequencyBands() {
        float maxFreq = 14000;
        float minFreq = 20.0f;

        generateEnhancedBarkBands(minFreq, maxFreq);
    }

    private void generateEnhancedBarkBands(float minFreq, float maxFreq) {
        float lowFreqFactor = .3f;
        int lowFreqBands = (int) (numBands * lowFreqFactor);
        int highFreqBands = numBands - lowFreqBands;

        float lowFreqMax = Math.min(lowFreqCutoff, maxFreq);
        generateDenseBarkBands(minFreq, lowFreqMax, lowFreqBands);

        if (lowFreqMax < maxFreq) {
            float barkMin = freqToBark(lowFreqMax * .21f);
            float barkMax = freqToBark(maxFreq);
            float barkStep = (barkMax - barkMin) / highFreqBands;

            for (int i = 0; i < highFreqBands; i++) {
                float lowFreq = barkToFreq(barkMin + i * barkStep);
                float highFreq = barkToFreq(barkMin + (i + 1) * barkStep);
                float centerFreq = (lowFreq + highFreq) / 2;
                int lowBin = freqToBin(lowFreq);
                int highBin = freqToBin(highFreq);
                lowBin = Math.max(0, Math.min(maxBin, lowBin));
                highBin = Math.max(lowBin, Math.min(maxBin, highBin));
                bands.add(new FrequencyBand(centerFreq, lowFreq, highFreq, lowBin, highBin));
            }
        }
    }
    private void generateDenseBarkBands(float minFreq, float maxFreq, int bandCount) {

        int linearBands = bandCount / 5;
        int barkBands = bandCount - linearBands;

        float midFreq = minFreq + (maxFreq - minFreq) * .3f;

        float linearStep = (midFreq - minFreq) / linearBands;
        for (int i = 0; i < linearBands; i++) {
            float lowFreq = minFreq + i * linearStep;
            float highFreq = minFreq + (i + 1) * linearStep;
            float centerFreq = (lowFreq + highFreq) / 2;

            int lowBin = freqToBin(lowFreq);
            int highBin = freqToBin(highFreq);
            lowBin = Math.max(0, Math.min(maxBin, lowBin));
            highBin = Math.max(lowBin, Math.min(maxBin, highBin));

            bands.add(new FrequencyBand(centerFreq, lowFreq, highFreq, lowBin, highBin));
        }

    }

    private float freqToBark(float freq) {
        return 13.0f * (float) Math.atan(0.00076f * freq) + 3.5f * (float) Math.atan((freq / 7500.0f) * (freq / 7500.0f));
    }

    private float barkToFreq(float bark) {
        return 600.0f * (float) Math.sinh(bark / 4.0f);
    }

    private int freqToBin(float freq) {
        return Math.round(freq * fftSize / sampleRate);
    }

    public synchronized float[] processFFT(float[] magnitudes) {
        return processFFT(magnitudes, DesktopAppState.preferences().spectrumAbsoluteVolume().getValue(),
                DesktopAppState.preferences().volume().getValue());
    }

    /** Both normalizations share the same FFT, so Minecraft settings never depend on system HUD settings. */
    public synchronized Frame processFrame(float[] magnitudes, double volume) {
        return new Frame(processFFT(magnitudes, true, volume), processFFT(magnitudes, false, volume));
    }

    public static final class Frame {
        public final float[] absolute, relative;
        private Frame(float[] absolute, float[] relative) { this.absolute = absolute; this.relative = relative; }
    }

    private float[] processFFT(float[] magnitudes, boolean absoluteVolume, double volume) {
        if (magnitudes.length < fftSize / 2) {
            return Arrays.copyOf(bandMagnitudes, bandMagnitudes.length);
        }

        for (int i = 0; i < bands.size(); i++) {
            FrequencyBand band = bands.get(i);
            float energy = 0;
            int binCount = 0;

            for (int bin = band.lowBin; bin <= band.highBin && bin < magnitudes.length; bin++) {
                float magnitude = magnitudes[bin] * magnitudes[bin];

                if (absoluteVolume) {
                    magnitude *= (0.125f * 0.125f) / (float) Math.max(0.0001, volume * volume);
                } else {
                    magnitude *= 0.25f * 0.25f;
                }

                energy += magnitude;
                binCount++;
            }

            if (binCount > 0) {
                energy = (float) Math.sqrt(energy / binCount);
            }

            float db = energy < 1e-10f ? minDB : 20 * (float) Math.log10(energy);

            if (band.centerFreq < lowFreqCutoff) {
                db -= (lowFreqCutoff - band.centerFreq) / lowFreqCutoff * 18f;
            }

            double max = dBToLinear(-30);
            double min = dBToLinear(-100);
            double value = dBToLinear(db) * 2;

            float sensitivity = 1.0f;

            // Quiet bands can fall below the noise floor; a negative fractional power is NaN.
            float normalized = (float) Math.pow(Math.max(0, (value - min) / (max - min)), 1 / (1.8 * sensitivity));
            bandMagnitudes[i] = Float.isFinite(normalized) ? normalized : 0;
        }

        // Render/IPC threads must never observe this working buffer during the next FFT pass.
        return Arrays.copyOf(bandMagnitudes, bandMagnitudes.length);
    }

    private float dBToLinear(float input) {
        return (float) Math.pow(10, input / 20);
    }

    public static class FrequencyBand {
        final float centerFreq;
        final float lowFreq;
        final float highFreq;
        final int lowBin;
        final int highBin;

        public FrequencyBand(float center, float low, float high, int lowBin, int highBin) {
            this.centerFreq = center;
            this.lowFreq = low;
            this.highFreq = high;
            this.lowBin = lowBin;
            this.highBin = highBin;
        }
    }
}
