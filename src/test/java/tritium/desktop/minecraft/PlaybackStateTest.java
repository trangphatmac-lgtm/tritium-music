package tritium.desktop.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import tritium.desktop.DesktopAppState;
import tritium.minecraft.protocol.PlaybackState;
import tritium.minecraft.protocol.Wire;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import java.io.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackStateTest {
    private final Gson json = new Gson();

    @Test void normalizesAllNumericChannelsAndOwnsItsSpectrumSnapshot() {
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            PlaybackState state = new PlaybackState();
            state.positionMillis = state.durationMillis = state.volume = state.downloadProgress = invalid;
            float[] producer = {.25f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1, 2.5f};
            state.spectrum = producer;
            state.normalize();
            assertDoesNotThrow(() -> json.toJson(state));
            assertEquals(0, state.positionMillis);
            assertEquals(0, state.durationMillis);
            assertEquals(0, state.volume);
            assertEquals(0, state.downloadProgress);
            assertArrayEquals(new float[]{.25f, 0, 0, 0, 0, 2.5f}, state.spectrum);
            assertTrue(Float.isNaN(producer[1]), "Never write back into the audio producer's buffer");
            producer[0] = Float.NaN;
            assertEquals(.25f, state.spectrum[0]);
        }
    }

    @Test void boundsRangesAndHandlesMissingArraysWithoutChangingValidPlayback() {
        PlaybackState state = new PlaybackState();
        state.trackId = 77; state.playing = true; state.positionMillis = 40_000; state.durationMillis = 90_000;
        state.volume = .5; state.downloadProgress = .3;
        state.spectrum = new float[PlaybackState.MAX_SPECTRUM_BANDS + 100]; Arrays.fill(state.spectrum, .125f);
        state.normalize();
        assertEquals(40_000, state.positionMillis); assertEquals(90_000, state.durationMillis);
        assertEquals(.5, state.volume); assertEquals(.3, state.downloadProgress);
        assertEquals(77, state.trackId); assertTrue(state.playing);
        assertEquals(PlaybackState.MAX_SPECTRUM_BANDS, state.spectrum.length); assertEquals(.125f, state.spectrum[state.spectrum.length - 1]);
        state.positionMillis = 100_000; state.volume = 4; state.downloadProgress = -1;
        state.spectrum = null; state.downloadSpeed = null; state.normalize();
        assertEquals(90_000, state.positionMillis); assertEquals(1, state.volume);
        assertEquals(0, state.downloadProgress); assertEquals(0, state.spectrum.length);
        assertEquals("", state.downloadSpeed);
    }

    @Test void captureCanSendAnInvalidAudioFrameThenRecoverOnTheSameWire() throws Exception {
        AudioPlayer oldPlayer = CloudMusic.player; Music oldMusic = CloudMusic.currentlyPlaying;
        float[] oldBands = AudioPlayer.bandValues;
        var oldFrame = AudioPlayer.spectrumFrame;
        double oldProgress = DesktopAppState.downloadStatus().downloadProgress;
        double oldVolume = DesktopAppState.preferences().volume().getValue();
        try {
            Probe player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Probe.class);
            CloudMusic.player = player; CloudMusic.currentlyPlaying = json.fromJson("{\"id\":77}", Music.class);
            player.position = Float.NaN; player.duration = Float.POSITIVE_INFINITY;
            AudioPlayer.bandValues = new float[]{.25f, Float.NaN, Float.NEGATIVE_INFINITY};
            AudioPlayer.spectrumFrame = null;
            DesktopAppState.downloadStatus().downloadProgress = Double.NaN;
            DesktopAppState.preferences().volume().setValue(Double.NaN);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Wire writer = new Wire(new ByteArrayInputStream(new byte[0]), bytes);
            writer.send(Wire.STATE, json.toJson(MinecraftConnectionManager.captureState()));
            writer.send(Wire.HEARTBEAT, "");

            player.position = 12_000; player.duration = 90_000;
            AudioPlayer.bandValues = new float[]{.75f};
            DesktopAppState.downloadStatus().downloadProgress = .6;
            DesktopAppState.preferences().volume().setValue(.5);
            writer.send(Wire.STATE, json.toJson(MinecraftConnectionManager.captureState()));

            Wire reader = new Wire(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream());
            PlaybackState bad = json.fromJson(reader.read().text(), PlaybackState.class);
            assertArrayEquals(new float[]{.25f, 0, 0}, bad.spectrum);
            assertEquals(0, bad.positionMillis); assertEquals(0, bad.durationMillis);
            assertEquals(0, bad.volume); assertEquals(0, bad.downloadProgress);
            assertEquals(Wire.HEARTBEAT, reader.read().type);
            PlaybackState recovered = json.fromJson(reader.read().text(), PlaybackState.class);
            assertEquals(12_000, recovered.positionMillis); assertEquals(90_000, recovered.durationMillis);
            assertEquals(.5, recovered.volume); assertEquals(.6, recovered.downloadProgress);
            assertArrayEquals(new float[]{.75f}, recovered.spectrum);
            assertEquals(77, recovered.trackId); assertTrue(recovered.playing);
        } finally {
            CloudMusic.player = oldPlayer; CloudMusic.currentlyPlaying = oldMusic; AudioPlayer.bandValues = oldBands;
            AudioPlayer.spectrumFrame = oldFrame;
            DesktopAppState.downloadStatus().downloadProgress = oldProgress;
            DesktopAppState.preferences().volume().setValue(oldVolume);
        }
    }

    @Test void keepsTheOriginalBandRangeAndSelectsTheGameVolumeModeIndependently() {
        var oldFrame = AudioPlayer.spectrumFrame;
        try {
            float[] fft = new float[2048]; Arrays.fill(fft, .01f);
            var frame = new tritium.widget.impl.SpectrumVisualizer(48000, 4096, 1024).processFrame(fft, .2);
            AudioPlayer.spectrumFrame = frame;
            assertTrue(frame.absolute.length > 512, "The original widget consumes half of the full band array");
            assertArrayEquals(frame.absolute, MinecraftConnectionManager.captureState(true).spectrum);
            assertArrayEquals(frame.relative, MinecraftConnectionManager.captureState(false).spectrum);
            assertNotEquals(frame.absolute[0], frame.relative[0]);
        } finally { AudioPlayer.spectrumFrame = oldFrame; }
    }

    private static final class Probe extends AudioPlayer {
        float position, duration;
        private Probe(File file) { super(file); }
        @Override public void setVolume(float value) { }
        @Override public boolean isFinished() { return false; }
        @Override public boolean isPausing() { return false; }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public float getTotalTimeMillis() { return duration; }
    }
}
