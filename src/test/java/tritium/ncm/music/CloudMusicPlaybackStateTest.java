package tritium.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Music;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.PlaybackState.Status.*;

class CloudMusicPlaybackStateTest {
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final Thread oldThread = CloudMusic.playThread;
    private final List<Music> oldList = CloudMusic.playList;
    private final int oldIndex = CloudMusic.curIdx;
    private final CloudMusic.PlayMode oldMode = CloudMusic.playMode;
    private final boolean oldBreak = CloudMusic.doBreak;

    @AfterEach void restore() {
        CloudMusic.player = oldPlayer;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playThread = oldThread;
        CloudMusic.playList = oldList;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.playMode = oldMode;
        CloudMusic.doBreak = oldBreak;
    }

    @Test void reportsActualDeckStatusAndQueueBoundariesWithoutAdvancingTheQueue() {
        Probe player = setup();
        assertEquals(PLAYING, CloudMusic.playbackState().status());
        assertEquals(10_000, CloudMusic.playbackState().positionMillis());
        assertFalse(CloudMusic.playbackState().canPrevious());
        assertTrue(CloudMusic.playbackState().canNext());
        player.paused = true;
        assertEquals(PAUSED, CloudMusic.playbackState().status());
        CloudMusic.curIdx = 1;
        assertTrue(CloudMusic.playbackState().canPrevious());
        assertFalse(CloudMusic.playbackState().canNext());
        CloudMusic.curIdx = 0;
        CloudMusic.playMode = CloudMusic.PlayMode.LoopInList;
        assertTrue(CloudMusic.playbackState().canPrevious());
        assertEquals(0, CloudMusic.curIdx, "Reading capabilities must not wrap the playback index");
    }

    @Test void queuedPlayNextEnablesTheNextButtonAtTheSequentialEnd() {
        setup();
        CloudMusic.curIdx = 1;
        var thread = new CloudMusic.PlayThread(CloudMusic.playList, 1);
        CloudMusic.playThread = thread;
        assertFalse(CloudMusic.playbackState().canNext());
        thread.queueNext(song(3));
        assertTrue(CloudMusic.playbackState().canNext());
        assertTrue(thread.hasQueuedNext());
    }

    @Test void stoppedAndEmptyStatesDoNotReadDisposedAudio() {
        Probe player = setup();
        player.finished = true;
        var state = CloudMusic.playbackState();
        assertEquals(STOPPED, state.status());
        assertEquals(60_000, state.durationMillis());
        assertFalse(state.canPlay());
        assertFalse(state.canNext());
        CloudMusic.currentlyPlaying = null;
        assertEquals(PlaybackState.empty(), CloudMusic.playbackState());
    }

    private Probe setup() {
        Probe player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Probe.class);
        CloudMusic.player = player;
        CloudMusic.currentlyPlaying = song(1);
        CloudMusic.playList = new ArrayList<>(List.of(song(1), song(2)));
        CloudMusic.curIdx = 0;
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        CloudMusic.playThread = null;
        CloudMusic.doBreak = false;
        return player;
    }

    private static Music song(long id) {
        return new Gson().fromJson("{\"id\":" + id + ",\"dt\":60000}", Music.class);
    }

    private static final class Probe extends AudioPlayer {
        boolean paused, finished;
        private Probe(File file) { super(file); }
        @Override public boolean isPausing() { return paused; }
        @Override public boolean isFinished() { return finished; }
        @Override public float getTotalTimeMillis() { assertFalse(finished); return 60_000; }
        @Override public float getCurrentTimeMillis() { assertFalse(finished); return 10_000; }
    }
}
