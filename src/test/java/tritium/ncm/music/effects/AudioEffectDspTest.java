package tritium.ncm.music.effects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Frequency, dynamics, impulse and channel tests of the PCM implementation, without devices. */
class AudioEffectDspTest {
    private static final int RATE = 48_000;

    @Test void bypassIsExactAndConfigurationDoesNotRetainMutableJson() {
        double[][] input = tones(997, .2, .1, RATE);
        AudioEffectDsp dsp = new AudioEffectDsp(RATE);
        double[][] out = render(dsp, input);
        assertArrayEquals(input[0], out[0], 0);
        assertArrayEquals(input[1], out[1], 0);
        JsonObject config = json("{cmp:{on:true,ratio:1,pregain:-6}}");
        dsp.setConfiguration(config);
        config.getAsJsonObject("cmp").addProperty("pregain", 20);
        dsp.reset();
        out = render(dsp, input);
        assertEquals(Math.pow(10, -.3), rms(out[0], 0) / rms(input[0], 0), 1e-10);
        dsp.setConfiguration(null);
        out = render(dsp, input);
        for (int i = RATE / 10; i < RATE; i++) assertEquals(input[0][i], out[0][i], 0);
    }

    @Test void equalizerChangesItsBandWithoutLeakingLeftIntoRight() {
        JsonObject config = json("{eq:{on:true,eqs:[0,0,0,0,0,6,0,0,0,0]}}");
        double[][] out = configured(config, tones(1000, .1, 0, RATE));
        assertEquals(Math.pow(10, .3), rms(out[0], RATE / 2) / (.1 / Math.sqrt(2)), .015);
        assertEquals(0, rms(out[1], 0), 0);
        double[][] far = configured(config, tones(80, .1, 0, RATE));
        assertTrue(rms(far[0], RATE / 2) / (.1 / Math.sqrt(2)) < 1.02);
    }

    @Test void shelfAndPeqParametersHaveMeasurableFrequencyResponse() {
        JsonObject bass = json("{bt:{on:true,bass:6,bassFreq:120,bassQ:0.707,treble:0}}");
        assertTrue(rms(configured(bass, tones(30, .1, 0, RATE))[0], RATE / 2) > .13);
        assertTrue(rms(configured(bass, tones(6000, .1, 0, RATE))[0], RATE / 2) < .072);
        JsonObject peq = json("{peq:{on:true,gain:0,f:[{band:0,type:6,on:true,freq:2000,q:4,gain:-12}]}}");
        double[][] cut = configured(peq, tones(2000, .1, .05, RATE));
        assertEquals(Math.pow(10, -.6), rms(cut[0], RATE / 2) / (.1 / Math.sqrt(2)), .005);
        assertEquals(2, rms(cut[0], RATE / 2) / rms(cut[1], RATE / 2), 1e-10);
    }

    @Test void linkedCompressorReducesLoudSignalsAndPreservesStereoBalance() {
        JsonObject cmp = json("{cmp:{on:true,threshold:-18,ratio:4,attack:0.005,release:0.12,range:0}}");
        double[][] loud = configured(cmp, tones(500, .8, .4, RATE));
        double[][] quiet = configured(cmp, tones(500, .04, .02, RATE));
        assertTrue(rms(loud[0], RATE / 2) < .25);
        assertEquals(2, rms(loud[0], RATE / 2) / rms(loud[1], RATE / 2), 1e-8);
        assertEquals(.04 / Math.sqrt(2), rms(quiet[0], RATE / 2), .0001);
    }

    @Test void configurationChangesCrossfadeAndLimiterBoundsBothChannels() {
        AudioEffectDsp dsp = new AudioEffectDsp(RATE);
        double[][] constant = new double[2][RATE / 10];
        Arrays.fill(constant[0], .25); Arrays.fill(constant[1], .125);
        dsp.setConfiguration(json("{cmp:{on:true,ratio:1,pregain:-12}}"));
        double[][] fade = render(dsp, constant);
        assertEquals(.25, fade[0][0], 0);
        assertEquals(.25 * Math.pow(10, -.6), fade[0][fade[0].length - 1], 1e-12);
        for (int i = 1; i < fade[0].length; i++) assertTrue(Math.abs(fade[0][i] - fade[0][i - 1]) < .001);
        JsonObject limiter = json("{limiter:{on:true,threshold:-12,attack:0.002,release:0.04,outputgain:0,infiniteRatio:true}}" );
        double[][] limited = configured(limiter, tones(200, .9, .8, RATE));
        for (double[] channel : limited) for (double sample : channel)
            assertTrue(Math.abs(sample) <= Math.pow(10, -.6));
        assertTrue(rms(limited[0], RATE / 2) > .1);
    }

    @Test void stereoEnhancementCreatesSideSignalWhileKeepingMonoSum() {
        double[][] input = tones(997, .1, .1, RATE);
        double[][] output = configured(json("{se:{on:true,presence:3.6,stereoizer:2.8,sshaper:true,ambience:1}}"), input);
        double side = 0;
        for (int i = RATE / 2; i < RATE; i++) {
            assertEquals(input[0][i] * 2, output[0][i] + output[1][i], 1e-12);
            side += Math.pow(output[0][i] - output[1][i], 2);
        }
        assertTrue(side > .01, "Decorrelator must process both channels, not two independent mono effects");
    }

    @Test void reverbHasPredelayDiffuseStereoTailAndDecayControl() {
        JsonObject shortRoom = reverb(.3), longRoom = reverb(2);
        double[][] input = new double[2][RATE * 3]; input[0][0] = .25;
        double[][] shortOut = configured(shortRoom, input), longOut = configured(longRoom, input);
        for (int i = 1; i < RATE * .03; i++) assertEquals(0, longOut[0][i], 1e-20);
        assertTrue(energy(longOut[0], RATE / 10, RATE) > 1e-6);
        assertTrue(energy(longOut[1], RATE / 10, RATE) > 1e-6);
        assertTrue(energy(longOut[0], RATE, RATE * 2) > energy(shortOut[0], RATE, RATE * 2) * 100);
        assertFalse(Arrays.equals(longOut[0], longOut[1]), "Reverb must produce a stereo diffuse field");
    }

    @Test void earlyReflectionPatternRoomSizeAndToneActuallyChangePcm() {
        JsonObject a = reverb(1);
        JsonObject rvb = a.getAsJsonObject("rvb");
        rvb.getAsJsonObject("rvb").addProperty("on", false);
        rvb.add("er", json("{on:true,pattern:4,rsize:70,sdelay:40}"));
        rvb.getAsJsonObject("ol").addProperty("er", 0);
        double[][] input = new double[2][RATE]; input[0][0] = .25;
        double[][] early = configured(a, input);
        assertTrue(energy(early[0], 100, RATE) > .001);
        JsonObject b = json(a.toString());
        b.getAsJsonObject("rvb").getAsJsonObject("er").addProperty("rsize", 10);
        assertFalse(Arrays.equals(early[0], configured(b, input)[0]));
        b = json(a.toString());
        b.getAsJsonObject("rvb").add("tc", json("{on:true,f:[{curve:2,gain:-18,freq:1000,q:0.707,insert:3}]}"));
        double[][] filtered = configured(b, input);
        assertTrue(energy(filtered[0], 100, RATE) < energy(early[0], 100, RATE) * .6);
    }

    @Test void reverbOtherControlsChangeItsImpulseResponse() {
        JsonObject base = reverb(1);
        double[][] input = new double[2][RATE]; input[0][0] = .2;
        double[][] original = configured(base, input);
        for (String key : new String[]{"hfdamping", "density", "diffusion", "rshape", "swidth", "q"}) {
            JsonObject changed = json(base.toString());
            changed.getAsJsonObject("rvb").getAsJsonObject("rvb").addProperty(key, 1);
            assertFalse(Arrays.equals(original[0], configured(changed, input)[0]), key + " must affect rendering");
        }
    }

    @Test void seekResetRemovesTailButKeepsConfiguration() {
        AudioEffectDsp dsp = new AudioEffectDsp(RATE);
        dsp.setConfiguration(reverb(3)); dsp.reset();
        double[][] input = tones(300, .1, .1, RATE);
        render(dsp, input);
        double[][] silence = new double[2][RATE / 2];
        assertTrue(energy(render(dsp, silence)[0], 0, silence[0].length) > 1e-7);
        dsp.reset();
        assertEquals(0, energy(render(dsp, silence)[0], 0, silence[0].length), 0);
        assertTrue(energy(render(dsp, input)[0], RATE / 2, RATE) > 0);
    }

    @Test void hostileParametersAndRepeatedSwitchesRemainFiniteAndBounded() {
        JsonObject bad = json("{eq:{on:true,eqs:[999,-999,999,{},null,999]},peq:{on:true,f:[{band:0,on:true,type:6,q:0,freq:999999,gain:999}]},bt:{on:true,bass:999,treble:999},cmp:{on:true,attack:0,release:0,ratio:0,pregain:999},rvb:{on:true,rvb:{on:true,dtime:999,pdelay:999},ol:{dry:999,rvb:999}}}");
        AudioEffectDsp dsp = new AudioEffectDsp(RATE);
        Random random = new Random(7);
        double[][] block = new double[2][1024];
        for (int i = 0; i < 80; i++) {
            dsp.setConfiguration(i % 3 == 0 ? null : bad);
            for (int frame = 0; frame < block[0].length; frame++) {
                block[0][frame] = random.nextDouble() - .5;
                block[1][frame] = random.nextDouble() - .5;
            }
            double[][] out = render(dsp, block);
            for (double[] channel : out) for (double value : channel) {
                assertTrue(Double.isFinite(value)); assertTrue(Math.abs(value) <= 1);
            }
        }
    }

    @Test void everyBundledSmartMasterAndDiyPresetProducesFiniteStereoPcm() throws Exception {
        double[][] input = tones(523.25, .2, .13, RATE / 3);
        int checked = 0;
        for (String name : new String[]{"smart-presets", "master-base", "master-diy"}) {
            try (var stream = getClass().getResourceAsStream("/tritium/audioeffects/" + name + ".json")) {
                assertNotNull(stream);
                JsonObject presets = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var entry : presets.entrySet()) {
                    double[][] out = configured(entry.getValue().getAsJsonObject(), input);
                    for (double[] channel : out) for (double sample : channel) {
                        assertTrue(Double.isFinite(sample), name + ":" + entry.getKey());
                        assertTrue(Math.abs(sample) <= 1, name + ":" + entry.getKey());
                    }
                    checked++;
                }
            }
        }
        assertEquals(55, checked, "18 smart + 32 master + 5 DIY parameter groups");
    }

    private static JsonObject reverb(double seconds) {
        JsonObject result = json("{rvb:{on:true,rvb:{on:true,pdelay:40,dtime:1,hfdamping:3.5,density:80,rshape:60,q:8,diffusion:70,swidth:150},er:{on:false},ol:{dry:-96,er:-20,rvb:-6}}}");
        result.getAsJsonObject("rvb").getAsJsonObject("rvb").addProperty("dtime", seconds);
        return result;
    }
    private static double[][] configured(JsonObject config, double[][] input) {
        AudioEffectDsp dsp = new AudioEffectDsp(RATE); dsp.setConfiguration(config); dsp.reset();
        return render(dsp, input);
    }
    private static double[][] render(AudioEffectDsp dsp, double[][] input) {
        double[][] out = new double[2][input[0].length];
        dsp.process(input[0], input[1], out[0], out[1], 0, input[0].length);
        return out;
    }
    private static double[][] tones(double hz, double l, double r, int frames) {
        double[][] input = new double[2][frames];
        for (int i = 0; i < frames; i++) {
            double sine = Math.sin(i * 2 * Math.PI * hz / RATE);
            input[0][i] = sine * l; input[1][i] = sine * r;
        }
        return input;
    }
    private static double energy(double[] samples, int start, int end) {
        double sum = 0; for (int i = start; i < end; i++) sum += samples[i] * samples[i]; return sum;
    }
    private static double rms(double[] samples, int start) { return Math.sqrt(energy(samples, start, samples.length) / (samples.length - start)); }
    private static JsonObject json(String text) { return new JsonParser().parse(text).getAsJsonObject(); }
}
