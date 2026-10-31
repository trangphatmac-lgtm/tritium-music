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

    @Test void recoveredFixedShelfAndHighpassTypesHaveTheExpectedFrequencyResponse() {
        JsonObject shelf = peq(5, 1000, .1, -12);
        assertEquals(1, response(shelf, 50), .001);
        assertEquals(Math.pow(10, -.6), response(shelf, 12_000), .001);
        // Native type 5 ignores q; types 7/8 are the variable-Q shelf variants.
        assertArrayEquals(configured(shelf, tones(997, .1, 0, RATE))[0],
                configured(peq(5, 1000, 10, -12), tones(997, .1, 0, RATE))[0], 0);
        JsonObject fixedHighpass = peq(1, 1000, 10, 0);
        assertTrue(response(fixedHighpass, 100) < .011);
        assertEquals(1 / Math.sqrt(2), response(fixedHighpass, 1000), .001);
        assertEquals(1, response(fixedHighpass, 10_000), .001);
        // The native highpass type 10 uses q; resonance at cutoff equals q.
        assertEquals(2, response(peq(10, 1000, 2, 0), 1000), .001);
    }

    @Test void feedbackDelayHasExactSpacingIndependentChannelsAndClearsOnSeek() {
        JsonObject config = json("{dly:{on:true,delay:100,feedback:0.5,dry:-96.3,wet:0}}");
        double[][] impulse = new double[2][RATE / 2]; impulse[0][0] = .25;
        double[][] output = configured(config, impulse);
        assertEquals(.25 * Math.pow(10, -96.3 / 20), output[0][0], 1e-16);
        for (int i = 1; i < output[0].length; i++) {
            double expected = i % 4800 == 0 ? .25 * Math.pow(.5, i / 4800 - 1) : 0;
            assertEquals(expected, output[0][i], 1e-16, "Frame " + i);
        }
        assertEquals(0, energy(output[1], 0, output[1].length), 0);
        AudioEffectDsp dsp = new AudioEffectDsp(RATE); dsp.setConfiguration(config); dsp.reset();
        render(dsp, impulse); dsp.reset();
        assertEquals(0, energy(render(dsp, new double[2][RATE])[0], 0, RATE), 0);
        // Instantaneous feedback has no causal delay line; zero delay is a finite dry/wet mix.
        assertEquals(.2, configured(json("{dly:{on:true,delay:0,feedback:1,dry:0,wet:0}}"),
                new double[][]{{.1}, {0}})[0][0], 1e-15);
    }

    @Test void chorusDelaysTheWetSignalAndItsModulationIsAudibleWithoutChannelLeakage() {
        double[][] impulse = new double[2][RATE / 10]; impulse[0][0] = .2;
        JsonObject staticDelay = json("{chorus:{on:true,delay:15,depth:0,rate:0,mix:1,voices:2}}");
        double[][] wet = configured(staticDelay, impulse);
        assertEquals(0, energy(wet[0], 0, 720), 0);
        assertEquals(.2, wet[0][720], 0);
        assertEquals(.04, energy(wet[0], 0, wet[0].length), 1e-15);
        double[][] input = tones(997, .1, 0, RATE * 2);
        JsonObject chorus = json("{chorus:{on:true,delay:15,depth:0.4,rate:0.3,mix:0.25,voices:2}}");
        double[][] modulated = configured(chorus, input);
        chorus.getAsJsonObject("chorus").addProperty("depth", 0);
        double[] unmodulated = configured(chorus, input)[0];
        double difference = 0;
        for (int i = RATE; i < input[0].length; i++) difference += Math.pow(modulated[0][i] - unmodulated[i], 2);
        assertTrue(difference > 1, "Modulated delay must alter the signal");
        assertEquals(0, rms(modulated[1], 0), 0);
    }

    @Test void adaptiveLevelCurveRaisesQuietMusicAndPreservesChannelBalance() {
        JsonObject alc = json("{alc:{on:true,attack:0.005,release:0.1,hold:0.05,st:0.02,lt:0.5,minisize:64,"
                + "xypoints:[0,0,-10,-8,-20,-16,-30,-25,-40,-36,-50,-46,-70,-70,-200,-200]}}");
        double[][] input = tones(997, .05, .025, RATE * 2);
        double[][] output = configured(alc, input);
        double boost = rms(output[0], RATE) / rms(input[0], RATE);
        assertTrue(boost > 1.6 && boost < 1.85, "Quiet programme should gain roughly 4–5 dB, got " + boost);
        assertEquals(2, rms(output[0], RATE) / rms(output[1], RATE), 1e-12);
        assertEquals(0, rms(configured(alc, new double[2][RATE])[0], 0), 0);
        // A constant +6 dB transfer curve provides a reference independent of detector choice.
        alc.getAsJsonObject("alc").add("xypoints", json("{p:[-200,-194,0,6]}").getAsJsonArray("p"));
        assertEquals(Math.pow(10, .3), rms(configured(alc, input)[0], RATE) / rms(input[0], RATE), .0001);
    }

    @Test void speakerFoldDownSeparatelyControlsCoherentCentreAndStereoFronts() {
        JsonObject spatial = json("{spspatial:{on:true,upmix:1,center:6,fl:-6,fr:-6,global:0}}");
        double[][] mono = tones(997, .1, .1, RATE), side = tones(997, .1, -.1, RATE);
        assertEquals(Math.pow(10, .3), rms(configured(spatial, mono)[0], RATE / 2) / rms(mono[0], RATE / 2), 1e-12);
        assertEquals(Math.pow(10, -.3), rms(configured(spatial, side)[0], RATE / 2) / rms(side[0], RATE / 2), 1e-12);
        double[][] input = tones(997, .1, .03, RATE);
        double[][] unity = configured(json("{spspatial:{on:true}}"), input);
        assertArrayEquals(input[0], unity[0], 1e-16); assertArrayEquals(input[1], unity[1], 1e-16);
    }

    @Test void explicitModuleOrderChangesDynamicsAndDoesNotDuplicateStages() {
        JsonObject before = json("{peq:{on:true,gain:12},cmp:{on:true,threshold:-18,ratio:8,attack:0.001,release:0.01},order:[peq,cmp]}");
        JsonObject after = json(before.toString()); after.add("order", json("{o:[cmp,peq]}").getAsJsonArray("o"));
        double[][] input = tones(997, .15, .075, RATE);
        double first = rms(configured(before, input)[0], RATE / 2), last = rms(configured(after, input)[0], RATE / 2);
        assertTrue(last > first * 2, "Pre-compressor gain and post-compressor gain must not be interchangeable");
        JsonObject duplicate = json(before.toString()); duplicate.add("order", json("{o:[peq,peq,cmp]}").getAsJsonArray("o"));
        assertArrayEquals(configured(before, input)[0], configured(duplicate, input)[0], 0);
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

    @Test void zeroDecayStopsFeedbackAndNegative963LevelIsRetained() {
        double[][] impulse = new double[2][RATE]; impulse[0][0] = .25;
        JsonObject zero = reverb(0);
        zero.getAsJsonObject("rvb").getAsJsonObject("rvb").addProperty("pdelay", 0);
        zero.getAsJsonObject("rvb").getAsJsonObject("ol").addProperty("dry", -96.3);
        double[][] output = configured(zero, impulse);
        assertEquals(.25 * Math.pow(10, -96.3 / 20), output[0][0], 1e-16);
        assertTrue(energy(output[0], 1, RATE / 4) > 1e-7, "Zero feedback retains the initial reflection");
        assertTrue(energy(output[0], RATE / 2, RATE) < 1e-20, "Zero decay must not silently fall back to 1 second");
        assertTrue(energy(configured(reverb(1), impulse)[0], RATE / 2, RATE) > 1e-9);
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

    @Test void allEmotionTemplatesHaveImplementedModulesAndStableResettableStereoState() throws Exception {
        try (var stream = getClass().getResourceAsStream("/tritium/audioeffects/emotion/templates.json")) {
            assertNotNull(stream);
            JsonObject templates = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(64, templates.entrySet().size());
            double[][] input = tones(523.25, .2, .13, RATE);
            for (var entry : templates.entrySet()) {
                JsonObject template = entry.getValue().getAsJsonObject();
                for (var module : template.entrySet()) {
                    if (!module.getValue().isJsonObject()) continue;
                    JsonObject settings = module.getValue().getAsJsonObject();
                    if (settings.has("on") && settings.get("on").getAsBoolean())
                        assertTrue(AudioEffectDsp.supportsModule(module.getKey()), entry.getKey() + ":" + module.getKey());
                }
                AudioEffectDsp dsp = new AudioEffectDsp(RATE); dsp.setConfiguration(template); dsp.reset();
                double[][] out = render(dsp, input);
                for (double[] channel : out) for (double sample : channel) {
                    assertTrue(Double.isFinite(sample), entry.getKey());
                    assertTrue(Math.abs(sample) <= 1, entry.getKey());
                }
                dsp.reset();
                double[][] silence = render(dsp, new double[2][RATE / 2]);
                assertEquals(0, energy(silence[0], 0, silence[0].length), 0, entry.getKey());
                assertEquals(0, energy(silence[1], 0, silence[1].length), 0, entry.getKey());
            }
        }
    }

    @Test void statefulModulesAreIndependentOfRenderBlockSizeAndSupportInPlacePcm() {
        JsonObject config = json("{chorus:{on:true,delay:15,depth:0.4,rate:0.3,mix:0.25,voices:2},"
                + "dly:{on:true,delay:37.5,feedback:0.7,wet:-12},alc:{on:true,xypoints:[-200,-200,-20,-16,0,0]}}");
        double[][] input = tones(523.25, .2, .13, RATE);
        double[][] expected = configured(config, input);
        AudioEffectDsp dsp = new AudioEffectDsp(RATE); dsp.setConfiguration(config); dsp.reset();
        for (int start = 0; start < RATE; start += 127)
            dsp.process(input[0], input[1], input[0], input[1], start, Math.min(RATE, start + 127));
        assertArrayEquals(expected[0], input[0], 0); assertArrayEquals(expected[1], input[1], 0);
        // A second deck with its own Processor must start with empty delay/envelope history.
        double[][] silentDeck = configured(config, new double[2][RATE]);
        assertEquals(0, energy(silentDeck[0], 0, RATE), 0);
    }

    @Test void extremeNewModuleParametersAndUnityFeedbackStayBounded() {
        JsonObject extremes = json("{dly:{on:true,delay:0.02,feedback:99,dry:99,wet:99},"
                + "chorus:{on:true,delay:999,depth:999,rate:999,mix:999,voices:999},"
                + "spspatial:{on:true,global:999,center:999,fl:999,fr:999,lronoff:1,lrangle:999},"
                + "alc:{on:true,st:0,lt:0,attack:0,release:0,minisize:0,lookahead:0.01,xyknee:99,"
                + "xypoints:[0,99,-20,99,-20,-999,-200,-200]}}");
        double[][] input = tones(997, .8, -.4, RATE * 2);
        double[][] output = configured(extremes, input);
        for (double[] channel : output) for (double sample : channel) {
            assertTrue(Double.isFinite(sample)); assertTrue(Math.abs(sample) <= 1);
        }
        assertTrue(energy(output[0], RATE, RATE * 2) > 0, "Protection must not permanently mute the processor");
    }

    private static JsonObject peq(int type, double hz, double q, double gain) {
        return json("{peq:{on:true,f:[{on:true,band:0,type:" + type + ",freq:" + hz + ",q:" + q + ",gain:" + gain + "}]}}");
    }
    private static double response(JsonObject config, double hz) {
        double[][] input = tones(hz, .1, 0, RATE);
        return rms(configured(config, input)[0], RATE / 2) / rms(input[0], RATE / 2);
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
