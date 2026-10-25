package tritium.desktop.media;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.media.MediaCommand.Action.*;
import static tritium.desktop.media.MediaCommand.*;

class SystemMediaControlsTest {
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final Thread oldThread = CloudMusic.playThread;
    private Probe player;

    @BeforeEach void setup() {
        player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Probe.class);
        CloudMusic.player = player;
        CloudMusic.currentlyPlaying = new Gson().fromJson("{\"id\":1,\"dt\":60000}", Music.class);
        CloudMusic.playThread = null;
    }

    @AfterEach void restore() {
        CloudMusic.player = oldPlayer;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playThread = oldThread;
    }

    @Test void playAndPauseAreExplicitInsteadOfToggling() {
        SystemMediaControls.execute(button(PAUSE));
        assertTrue(player.paused);
        SystemMediaControls.execute(button(PAUSE));
        assertTrue(player.paused);
        SystemMediaControls.execute(button(PLAY));
        assertFalse(player.paused);
        SystemMediaControls.execute(button(PLAY));
        assertFalse(player.paused);
    }

    @Test void seekIsClampedAndCommandsIgnoreFinishedOrAbsentPlayback() {
        SystemMediaControls.execute(seek(-10));
        assertEquals(0, player.position);
        SystemMediaControls.execute(seek(Long.MAX_VALUE));
        assertEquals(60_000, player.position);
        player.finished = true;
        SystemMediaControls.execute(seek(10_000));
        SystemMediaControls.execute(button(PAUSE));
        assertEquals(60_000, player.position);
        assertFalse(player.paused);
        CloudMusic.currentlyPlaying = null;
        SystemMediaControls.execute(button(PAUSE));
        assertFalse(player.paused);
    }

    @Test void togglesAtExecutionTimeAndRejectsStaleTrackSeeks() {
        SystemMediaControls.execute(button(PLAY_PAUSE));
        assertTrue(player.paused);
        SystemMediaControls.execute(button(PLAY_PAUSE));
        assertFalse(player.paused);
        SystemMediaControls.execute(seek(20_000, 2));
        assertEquals(0, player.position);
        SystemMediaControls.execute(seek(20_000, 1));
        assertEquals(20_000, player.position);
        SystemMediaControls.execute(seek(60_001, 1));
        assertEquals(20_000, player.position);
    }

    @Test void relativeSeekUsesCurrentPositionAndAvoidsOverflow() {
        player.position = 20_000;
        SystemMediaControls.execute(relativeSeek(-5_000));
        assertEquals(15_000, player.position);
        SystemMediaControls.execute(relativeSeek(Long.MIN_VALUE));
        assertEquals(0, player.position);
        SystemMediaControls.execute(relativeSeek(1_234));
        assertEquals(1_234, player.position);
    }

    private static final class Probe extends AudioPlayer {
        boolean paused, finished;
        float position;
        private Probe(File file) { super(file); }
        @Override public boolean isPausing() { return paused; }
        @Override public boolean isFinished() { return finished; }
        @Override public float getTotalTimeMillis() { return 60_000; }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public void setPlaybackTime(float value) { position = value; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
    }
}
