package tritium.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudMusicPlaybackSessionTest {
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final Thread oldThread = CloudMusic.playThread;
    private final List<Music> oldQueue = CloudMusic.playList;
    private final int oldIndex = CloudMusic.curIdx;
    private final CloudMusic.PlayMode oldMode = CloudMusic.playMode;
    private final PlayList oldSource = CloudMusic.playedFrom;
    private final boolean oldBreak = CloudMusic.doBreak, oldDontAdd = CloudMusic.dontAdd;
    private final boolean oldPlaying = CloudMusic.playing.get();

    @BeforeEach void setup() {
        CloudMusic.player = null;
        CloudMusic.currentlyPlaying = null;
        CloudMusic.playThread = null;
        CloudMusic.playList = new ArrayList<>();
        CloudMusic.curIdx = 0;
        CloudMusic.doBreak = CloudMusic.dontAdd = false;
    }

    @AfterEach void restore() {
        CloudMusic.player = oldPlayer;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playThread = oldThread;
        CloudMusic.playList = oldQueue;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.playMode = oldMode;
        CloudMusic.playedFrom = oldSource;
        CloudMusic.doBreak = oldBreak;
        CloudMusic.dontAdd = oldDontAdd;
        CloudMusic.playing.set(oldPlaying);
    }

    @Test void restoresExactShuffleOrderAndDuplicateIndexAndRetainsPendingCueUntilAudioIsReady() {
        PlaybackSession saved = session(true);
        CloudMusic.PlayThread thread = restoreSession(saved);
        assertEquals(saved, CloudMusic.capturePlaybackSession(), "Closing while restoring must retain the cue");
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
        assertEquals(saved.songs(), CloudMusic.playList);
        assertEquals(2, CloudMusic.curIdx);
        assertTrue(thread.hasQueuedNext());

        Probe audio = probe();
        CloudMusic.player = audio;
        thread.startPlayback();
        assertEquals(42_123, audio.position);
        assertTrue(audio.isPausing());
        assertEquals(0, audio.plays, "Paused restoration must never briefly start audio");
        assertTrue(CloudMusic.playing.get(), "Paused playback must keep the queue worker alive");
        assertEquals(saved, CloudMusic.capturePlaybackSession());
        CloudMusic.setPaused(false);
        assertEquals(1, audio.plays);
        thread.advanceAfterTrack(CloudMusic.curIdx);
        assertEquals(8, CloudMusic.playList.get(CloudMusic.curIdx).getId());
        assertEquals(List.of(song(9)), CloudMusic.capturePlaybackSession().nextSongs());
    }

    @Test void resumesPlayingOnlyAfterSeekingAndDoesNotReuseCueForNextSong() {
        CloudMusic.PlayThread thread = restoreSession(session(false));
        Probe audio = probe();
        CloudMusic.player = audio;
        thread.startPlayback();
        assertEquals(42_123, audio.playedAt);
        assertEquals(1, audio.plays);
        assertFalse(CloudMusic.capturePlaybackSession().paused());
        Probe next = probe();
        CloudMusic.player = next;
        thread.startPlayback();
        assertEquals(0, next.playedAt);
    }

    @Test void startupCannotOverwriteUserSelectionOrRestartAnExistingThread() {
        assertFalse(CloudMusic.restorePlayback(null, ignored -> fail("No saved state")));
        assertNull(CloudMusic.capturePlaybackSession());
        CloudMusic.currentlyPlaying = song(88);
        assertFalse(CloudMusic.restorePlayback(session(false), ignored -> fail("User selection wins")));
        assertEquals(88, CloudMusic.currentlyPlaying.getId());
        CloudMusic.currentlyPlaying = null;
        restoreSession(session(false));
        assertFalse(CloudMusic.restorePlayback(session(false), ignored -> fail("Restore only once")));
    }

    @Test void stoppedQueueUsesLastActualSongAndNeverReadsDisposedAudio() {
        CloudMusic.currentlyPlaying = song(2);
        CloudMusic.playList = new ArrayList<>(List.of(song(1), song(2)));
        CloudMusic.curIdx = 2;
        Probe audio = probe();
        audio.finished = true;
        CloudMusic.player = audio;
        PlaybackSession saved = CloudMusic.capturePlaybackSession();
        assertEquals(1, saved.index());
        assertEquals(2, saved.song().getId());
        assertTrue(saved.paused());
    }

    private CloudMusic.PlayThread restoreSession(PlaybackSession session) {
        AtomicReference<CloudMusic.PlayThread> started = new AtomicReference<>();
        assertTrue(CloudMusic.restorePlayback(session, started::set));
        assertSame(started.get(), CloudMusic.playThread);
        return started.get();
    }

    private static PlaybackSession session(boolean paused) {
        return new PlaybackSession(List.of(song(1), song(2), song(1)), 2, 42_123, paused,
                CloudMusic.PlayMode.Random, List.of(song(8), song(9)));
    }

    private static Music song(long id) {
        return new Gson().fromJson("{\"id\":" + id + ",\"dt\":60000,\"name\":\"song\",\"ar\":[]}", Music.class);
    }

    private static Probe probe() {
        return new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Probe.class);
    }

    private static final class Probe extends AudioPlayer {
        float position, playedAt;
        int plays;
        boolean playing, finished;
        private Probe(File file) { super(file); }
        @Override public boolean isPausing() { return !playing; }
        @Override public boolean isFinished() { return finished; }
        @Override public float getTotalTimeMillis() { assertFalse(finished); return 60_000; }
        @Override public float getCurrentTimeMillis() { assertFalse(finished); return position; }
        @Override public void setPlaybackTime(float millis) { assertFalse(playing); position = millis; }
        @Override public void play() { playing = true; plays++; playedAt = position; }
    }
}
