package tritium.ncm.music;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.unitgen.ChannelOut;
import repackage.com.jsyn.unitgen.Multiply;
import repackage.com.jsyn.unitgen.UnitGenerator;
import repackage.processing.sound.Engine;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.automix.AutoMixAutomation;
import tritium.ncm.music.automix.AutoMixAutomation.FilterSetting;
import repackage.com.jsyn.data.FloatSample;
import tritium.ncm.music.stream.StreamingAudioSample;
import com.sun.net.httpserver.HttpServer;
import tritium.ncm.music.automix.AutoMixAutomation.FilterType;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual decoded PCM and JSyn graph, rendered offline without opening an output device. */
class AudioPlayerMixTest {
    @TempDir Path directory;
    private Engine previous;
    private SynthesisEngine synth;
    private final Capture[] outputs = {new Capture(), new Capture()};
    private final List<AudioPlayer> decks = new ArrayList<>();
    private File file;

    @BeforeEach void setup() throws Exception {
        previous = (Engine) field(Engine.class, "singleton").get(null);
        AudioDeviceManager device = (AudioDeviceManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AudioDeviceManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getMaxOutputChannels")) return 2;
                    throw new AssertionError("Offline test accessed a device: " + method.getName());
                });
        synth = new SynthesisEngine(device);
        synth.setRealTime(false);
        synth.start(44_100, -1, 0, 0, 2);
        Engine engine = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Engine.class);
        field(Engine.class, "synth").set(engine, synth);
        field(Engine.class, "addedUnits").set(engine, ConcurrentHashMap.<UnitGenerator>newKeySet());
        Multiply[] volume = {new Multiply(), new Multiply()};
        for (int i = 0; i < 2; i++) {
            outputs[i].setChannelIndex(i);
            synth.add(outputs[i]);
            synth.add(volume[i]);
            volume[i].inputB.set(1);
            volume[i].output.connect(outputs[i].input);
            outputs[i].start();
        }
        field(Engine.class, "volume").set(engine, volume);
        field(Engine.class, "singleton").set(null, engine);
        byte[] pcm = new byte[44_100 * 2 * 4];
        for (int i = 0; i < pcm.length; i += 4) {
            pcm[i + 1] = 64; // constant 0.5 left, 0.25 right, signed 16-bit LE
            pcm[i + 3] = 32;
        }
        file = directory.resolve("stereo.wav").toFile();
        try (var input = new AudioInputStream(new ByteArrayInputStream(pcm),
                new AudioFormat(44_100, 16, 2, true, false), pcm.length / 4)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, file);
        }
    }

    @AfterEach void cleanup() throws Exception {
        for (AudioPlayer deck : decks) deck.close();
        synth.generateNextBuffer();
        synth.stop();
        field(Engine.class, "singleton").set(null, previous);
    }

    @Test void mutedIncomingIsSilentFromItsFirstAudioBlockThenUsesMasterTimesMixGain() {
        AudioPlayer deck = deck(false);
        deck.setVolume(.4f);
        deck.setMixGain(0);
        deck.startAt(0);
        render(1);
        assertEquals(0, outputs[0].peak, 0);
        assertEquals(0, outputs[1].peak, 0);
        deck.setMixGain(.5);
        assertEquals(.5, deck.getMixGain());
        render(1);
        assertEquals(.1, outputs[0].peak, 1e-5);
        assertEquals(.05, outputs[1].peak, 1e-5);
    }

    @Test void pausingAndSeekingPreserveSilenceAndResumeFromTheSelectedPosition() {
        AudioPlayer deck = deck(false);
        deck.startAt(0);
        render(2);
        deck.pause();
        render(1);
        deck.setPlaybackTime(1_000);
        render(2);
        assertTrue(deck.isPausing());
        assertEquals(0, outputs[0].peak, 0);
        assertEquals(1_000, deck.getCurrentTimeMillis(), 1);
        deck.unpause();
        render(2);
        assertTrue(deck.getCurrentTimeMillis() > 1_000);
        assertTrue(outputs[0].peak > 0);
    }

    @Test void streamingDeckPreservesVolumePauseSeekAndOtherVoices() throws Exception {
        byte[] audio = Files.readAllBytes(file.toPath());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            exchange.sendResponseHeaders(200, audio.length);
            try (var output = exchange.getResponseBody()) { output.write(audio); }
        });
        server.start();
        StreamingAudioSample stream = null;
        try {
            stream = StreamingAudioSample.open("http://127.0.0.1:" + server.getAddress().getPort() + "/audio",
                    directory.resolve("stream.wav"), 2_000, () -> false);
            AudioPlayer a = new AudioPlayer(stream);
            decks.add(a);
            a.setVolume(.4f);
            a.setMixGain(.5);
            a.play(); render(2);
            assertEquals(.1, outputs[0].peak, 1e-5);
            assertEquals(.05, outputs[1].peak, 1e-5);
            a.pause(); render(2);
            float paused = a.getCurrentTimeMillis();
            render(10);
            assertEquals(paused, a.getCurrentTimeMillis());
            assertEquals(0, outputs[0].peak);
            a.setPlaybackTime(1_000);
            assertTrue(a.isPausing());
            assertEquals(1_000, a.getCurrentTimeMillis(), 1);
            a.unpause();
            for (int i = 0; i < 100 && a.getCurrentTimeMillis() <= 1_000; i++) { render(1); Thread.sleep(10); }
            assertTrue(a.getCurrentTimeMillis() > 1_000);
            assertEquals(.1, outputs[0].peak, 1e-5);
            AudioPlayer b = deck(false);
            b.play(); render(2);
            a.close(); render(3);
            assertEquals(.125, outputs[0].peak, 1e-5);
            assertFalse(b.isFinished());
        } finally {
            if (stream != null) stream.cleanUp();
            server.stop(0);
            for (int i = 0; i < 1_000; i++) {
                try (var paths = Files.list(directory)) {
                    if (paths.noneMatch(path -> path.toString().endsWith(".part"))) break;
                }
                Thread.sleep(10);
            }
        }
    }

    @Test void closingOneVoiceLeavesTheOtherAudibleAndDetachesItsFft() throws Exception {
        AudioPlayer a = deck(true), b = deck(false);
        a.startAt(0); b.startAt(0);
        render(3);
        var fft = a.getFft().getFft();
        a.close();
        render(3);
        assertEquals(.125, outputs[0].peak, 1e-5);
        assertEquals(.0625, outputs[1].peak, 1e-5);
        assertTrue(a.isFinished());
        assertFalse(b.isFinished());
        var running = (List<?>) field(SynthesisEngine.class, "runningUnitList").get(synth);
        assertFalse(running.contains(fft), "Closed deck's FFT must no longer execute");
        var added = (Set<?>) field(Engine.class, "addedUnits").get(Engine.getEngine());
        assertFalse(added.contains(a.player.getUnitGenerator()));
    }

    @Test void stereoHighPassAttenuatesDcAndCancellationRestoresBothChannels() {
        AudioPlayer deck = deck(false);
        deck.startAt(0);
        deck.setMixFilter(0, 1_000);
        render(100);
        assertTrue(outputs[0].peak < 1e-5);
        assertTrue(outputs[1].peak < 1e-5);
        deck.setMixFilter(0, 0);
        render(2);
        assertEquals(.125, outputs[0].peak, 1e-5);
        assertEquals(.0625, outputs[1].peak, 1e-5);
    }

    @Test void preinstalledEntryEqualizerSurvivesCueAndIsRemovedOnClose() throws Exception {
        AudioPlayer deck = deck(false);
        var stages = List.of(new FilterSetting(FilterType.LOW_PASS, 4_000),
                new FilterSetting(FilterType.HIGH_PASS, 400));
        deck.setMixGain(0);
        deck.setMixEqualizer(stages);
        Object effect = field(AudioPlayer.class, "advancedFilter").get(deck);
        Object left = field(repackage.processing.sound.Effect.class, "left").get(effect);
        Object right = field(repackage.processing.sound.Effect.class, "right").get(effect);

        // AutoMix installs B's entry EQ before cueing and starting the muted incoming voice.
        deck.startAt(250);
        render(1);
        assertEquals(0, outputs[0].peak, 0);
        assertEquals(0, outputs[1].peak, 0);
        deck.setMixGain(1);
        render(100);
        assertEquals(stages, deck.getMixEqualizer());
        assertSame(effect, field(AudioPlayer.class, "advancedFilter").get(deck));
        assertTrue(outputs[0].peak < 1e-5, "Cue must reconnect the installed left entry EQ");
        assertTrue(outputs[1].peak < 1e-5, "Cue must reconnect the installed right entry EQ");

        deck.close();
        render(2);
        var added = (Set<?>) field(Engine.class, "addedUnits").get(Engine.getEngine());
        assertFalse(added.contains(left));
        assertFalse(added.contains(right));
        assertTrue(deck.getMixEqualizer().isEmpty());
        assertEquals(0, outputs[0].peak, 0);
        assertEquals(0, outputs[1].peak, 0);
    }

    @Test void preinstalledLegacyFilterSurvivesCueAndStart() {
        AudioPlayer deck = deck(false);
        deck.setMixFilter(0, 1_000);
        deck.startAt(250);
        render(100);
        assertTrue(outputs[0].peak < 1e-5);
        assertTrue(outputs[1].peak < 1e-5);
        deck.setMixFilter(0, 0);
        render(2);
        assertEquals(.125, outputs[0].peak, 1e-5);
        assertEquals(.0625, outputs[1].peak, 1e-5);
    }

    @Test void diagnosticFilterValuesFollowPauseSeekAndClose() {
        AudioPlayer deck = deck(false);
        deck.startAt(0);
        deck.setMixFilter(12_000, 300);
        assertEquals(12_000, deck.getMixLowPassHz());
        assertEquals(300, deck.getMixHighPassHz());
        deck.pause();
        assertEquals(0, deck.getMixLowPassHz());
        assertEquals(0, deck.getMixHighPassHz());
        deck.unpause();
        deck.setMixFilter(0, 500);
        deck.setPlaybackTime(500);
        assertEquals(0, deck.getMixHighPassHz());
        deck.setMixFilter(5_000, 0);
        deck.close();
        assertEquals(0, deck.getMixLowPassHz());
    }

    @Test void advancedEqualizerProcessesStereoPcmAndReleasesItsGraphAcrossPlaybackControls() throws Exception {
        AudioPlayer deck = deck(false);
        deck.startAt(0);
        render(3);
        var stages = List.of(new FilterSetting(FilterType.LOW_PASS, 4_000),
                new FilterSetting(FilterType.HIGH_PASS, 400));
        deck.setMixEqualizer(stages);
        Object effect = field(AudioPlayer.class, "advancedFilter").get(deck);
        Object left = field(repackage.processing.sound.Effect.class, "left").get(effect);
        Object right = field(repackage.processing.sound.Effect.class, "right").get(effect);
        render(100);
        assertEquals(stages, deck.getMixEqualizer());
        assertThrows(UnsupportedOperationException.class, () -> deck.getMixEqualizer().clear());
        assertTrue(outputs[0].peak < 1e-5, "Cascaded high-pass must reject left DC");
        assertTrue(outputs[1].peak < 1e-5, "Cascaded high-pass must reject right DC");

        // 0 Hz retains the same slots and smoothly bypasses them, without replacing the graph.
        deck.setMixEqualizer(List.of(new FilterSetting(FilterType.LOW_PASS, 0),
                new FilterSetting(FilterType.HIGH_PASS, 0)));
        assertSame(effect, field(AudioPlayer.class, "advancedFilter").get(deck));
        render(100);
        assertEquals(.125, outputs[0].peak, 1e-5);
        assertEquals(.0625, outputs[1].peak, 1e-5);

        deck.setMixEqualizer(stages);
        deck.pause();
        render(2);
        assertTrue(deck.getMixEqualizer().isEmpty());
        var added = (Set<?>) field(Engine.class, "addedUnits").get(Engine.getEngine());
        assertFalse(added.contains(left));
        assertFalse(added.contains(right));
        assertEquals(0, outputs[0].peak, 0);
        deck.unpause();
        render(2);
        assertEquals(.125, outputs[0].peak, 1e-5);
        assertEquals(.0625, outputs[1].peak, 1e-5);

        deck.setMixEqualizer(stages);
        deck.setPlaybackTime(1_000);
        assertTrue(deck.getMixEqualizer().isEmpty());
        render(3);
        assertEquals(.125, outputs[0].peak, 1e-5);
        deck.setMixEqualizer(stages);
        deck.setMixFilter(0, 0);
        assertTrue(deck.getMixEqualizer().isEmpty());
        assertNull(field(AudioPlayer.class, "advancedFilter").get(deck));
        render(3);
        assertEquals(.125, outputs[0].peak, 1e-5);
        deck.setMixEqualizer(stages);
        deck.close();
        assertTrue(deck.getMixEqualizer().isEmpty());
        render(2);
        assertEquals(0, outputs[0].peak, 0);
    }

    @Test void slowIncomingPcmPreparationNeverBlocksOutgoingAndStartReadsOnlyThePreparedWindow() throws Exception {
        AudioPlayer a = deck(false), b = deck(false);
        a.startAt(0); render(3);
        var source = new BlockingSample();
        var sampleField = field(repackage.processing.sound.AudioSample.class, "sample");
        ((FloatSample) sampleField.get(b.player)).cleanUp();
        sampleField.set(b.player, source);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var preparation = worker.submit(() -> b.prepareAutoMix(new AutoMixPlan(AutoMixPlan.Strategy.CROSSFADE, 0, 500, 500, 250,
                    AutoMixPlan.Curve.LINEAR, AutoMixPlan.Curve.LINEAR, false), () -> false));
            try {
                assertTrue(source.entered.await(5, TimeUnit.SECONDS));
                assertFalse(preparation.isDone());
                for (int i = 0; i < 20; i++) {
                    render(1);
                    assertEquals(.125, outputs[0].minimum, 1e-5, "A stays continuous while B's decoder is blocked");
                }
            } finally { source.release.countDown(); }
            preparation.get(5, TimeUnit.SECONDS);
            assertTrue(b.isPausing());
            assertEquals(500, b.getCurrentTimeMillis(), 1);
            assertNotNull(b.getFft(), "Analyzer allocation belongs to preparation, not the handoff");
            int reads = source.reads.get();
            b.startAt(500); b.setMixGain(1);
            render(3);
            for (int i = 0; i < 100; i++) {
                render(1);
                assertEquals(.25, outputs[0].minimum, 1e-5, "Both voices remain continuous after entry");
                assertEquals(.125, outputs[1].minimum, 1e-5);
            }
            assertEquals(reads, source.reads.get(), "No disk/decode read is allowed at the cue or through the fade");
            assertTrue(b.getCurrentTimeMillis() > 500);
            b.close(); render(2);
            assertEquals(1, source.closes);
            assertEquals(.125, outputs[0].minimum, 1e-5);
        }
    }

    @Test void backgroundPreparedEntryPreservesEqualizerAndCanBeCancelledWithoutPlaying() throws Exception {
        for (boolean full : new boolean[]{false, true}) {
            AudioPlayer b = deck(false);
            b.prepareAutoMix(entryPlan(full), () -> false);
            Object effect = field(AudioPlayer.class, full ? "advancedFilter" : "filter").get(b);
            assertNotNull(effect);
            render(2);
            assertEquals(0, outputs[0].peak);
            assertEquals(500, b.getCurrentTimeMillis(), 1);
            b.startAt(500); b.setMixGain(1); render(100);
            assertSame(effect, field(AudioPlayer.class, full ? "advancedFilter" : "filter").get(b));
            assertTrue(outputs[0].peak < 1e-5);
            b.close(); render(2);
        }
        AudioPlayer cancelled = deck(false);
        cancelled.prepareAutoMix(entryPlan(true), () -> false);
        Object unit = cancelled.player.getUnitGenerator();
        cancelled.close(); render(2);
        var added = (Set<?>) field(Engine.class, "addedUnits").get(Engine.getEngine());
        assertFalse(added.contains(unit));
        assertTrue(cancelled.getMixEqualizer().isEmpty());
        assertEquals(0, outputs[0].peak);
    }

    private AutoMixPlan entryPlan(boolean full) {
        if (!full) return new AutoMixPlan(AutoMixPlan.Strategy.VOCAL, 0, 500, 500, 250,
                AutoMixPlan.Curve.LINEAR, AutoMixPlan.Curve.LINEAR, true);
        var ga = new AutoMixAutomation.Gain(0, 500, AutoMixPlan.Curve.LINEAR, false, true);
        var gb = new AutoMixAutomation.Gain(500, 1000, AutoMixPlan.Curve.LINEAR, true, true);
        return new AutoMixPlan(AutoMixPlan.Strategy.VOCAL, 0, 500, 500, 250,
                ga.curve(), gb.curve(), true, new AutoMixAutomation(0,
                new AutoMixAutomation.Deck(ga, List.of()),
                new AutoMixAutomation.Deck(gb, List.of(new AutoMixAutomation.EqSweep(500, 1000,
                        FilterType.HIGH_PASS, 1000, 20))), "test"));
    }

    @Test void realFlacEntryIsPrefetchedAndStaysContinuousAcrossTheCacheBoundary() throws Exception {
        File flac = Path.of(getClass().getResource("/audio/automix-entry-stereo.flac").toURI()).toFile();
        AudioPlayer b = new AudioPlayer(flac, false);
        decks.add(b);
        var sampleField = field(repackage.processing.sound.AudioSample.class, "sample");
        assertInstanceOf(repackage.com.jsyn.util.soundfile.streamed.buffered.BufferedFloatSample.class,
                sampleField.get(b.player));
        b.prepareAutoMix(new AutoMixPlan(AutoMixPlan.Strategy.CROSSFADE, 0, 500, 500, 250,
                AutoMixPlan.Curve.LINEAR, AutoMixPlan.Curve.LINEAR, false), () -> false);
        assertInstanceOf(repackage.com.jsyn.data.PrefetchedFloatSample.class, sampleField.get(b.player));
        assertEquals(500, b.getCurrentTimeMillis(), 1);
        b.startAt(500); b.setMixGain(1); render(3);
        // The prepared range ends at 2.5 s; keep rendering beyond it into the original decoder.
        while (b.getCurrentTimeMillis() < 2_650) {
            render(1);
            assertEquals(.125, outputs[0].minimum, 1e-5);
            assertEquals(.0625, outputs[1].minimum, 1e-5);
        }
    }

    private static final class BlockingSample extends FloatSample {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        int closes;
        BlockingSample() { numFrames = 44_100 * 2; channelsPerFrame = 2; setFrameRate(44_100); }
        @Override public double readDouble(int index) {
            if (reads.getAndIncrement() == 0) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Decoder was not released"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return index % 2 == 0 ? .5 : .25;
        }
        @Override public void cleanUp() { closes++; }
    }

    private AudioPlayer deck(boolean analysis) {
        AudioPlayer result = new AudioPlayer(file, analysis);
        decks.add(result);
        return result;
    }
    private void render(int buffers) {
        for (int i = 0; i < buffers; i++) {
            outputs[0].peak = outputs[1].peak = 0;
            outputs[0].minimum = outputs[1].minimum = Double.POSITIVE_INFINITY;
            synth.generateNextBuffer();
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static final class Capture extends ChannelOut {
        double peak, minimum;
        @Override public void generate(int start, int limit) {
            super.generate(start, limit);
            double[] samples = input.getValues();
            for (int i = start; i < limit; i++) {
                peak = Math.max(peak, Math.abs(samples[i]));
                minimum = Math.min(minimum, Math.abs(samples[i]));
            }
        }
    }
}
