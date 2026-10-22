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

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private AudioPlayer deck(boolean analysis) {
        AudioPlayer result = new AudioPlayer(file, analysis);
        decks.add(result);
        return result;
    }
    private void render(int buffers) {
        for (int i = 0; i < buffers; i++) {
            outputs[0].peak = outputs[1].peak = 0;
            synth.generateNextBuffer();
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static final class Capture extends ChannelOut {
        double peak;
        @Override public void generate(int start, int limit) {
            super.generate(start, limit);
            double[] samples = input.getValues();
            for (int i = start; i < limit; i++) peak = Math.max(peak, Math.abs(samples[i]));
        }
    }
}
