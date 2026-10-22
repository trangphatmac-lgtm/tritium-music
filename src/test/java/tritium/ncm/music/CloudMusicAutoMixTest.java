package tritium.ncm.music;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.desktop.DesktopAppState;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.dto.Music;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudMusicAutoMixTest {
    private final Thread oldThread = CloudMusic.playThread;
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final List<Music> oldList = CloudMusic.playList;
    private final CloudMusic.PlayMode oldMode = CloudMusic.playMode;
    private final int oldIndex = CloudMusic.curIdx;
    private final boolean oldBreak = CloudMusic.doBreak, oldDontAdd = CloudMusic.dontAdd;
    private final boolean oldPlaying = CloudMusic.playing.get();
    private final boolean oldEnabled = DesktopAppState.preferences().autoMix().getValue();
    private CloudMusic.PlayThread thread;
    private Probe a, b;

    @BeforeEach void setup() {
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        CloudMusic.curIdx = 0;
        CloudMusic.doBreak = CloudMusic.dontAdd = false;
        CloudMusic.playing.set(true);
        CloudMusic.playList = new ArrayList<>(List.of(song(1), song(2), song(3)));
        CloudMusic.currentlyPlaying = CloudMusic.playList.getFirst();
        thread = new CloudMusic.PlayThread(CloudMusic.playList, 0);
        CloudMusic.playThread = thread;
        a = probe(); b = probe();
        CloudMusic.player = a;
        DesktopAppState.preferences().autoMix().setValue(true);
    }

    @AfterEach void restore() {
        thread.cancelAutoMix();
        CloudMusic.playThread = oldThread;
        CloudMusic.player = oldPlayer;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playList = oldList;
        CloudMusic.playMode = oldMode;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.doBreak = oldBreak;
        CloudMusic.dontAdd = oldDontAdd;
        CloudMusic.playing.set(oldPlaying);
        DesktopAppState.preferences().autoMix().setValue(oldEnabled);
    }

    @Test void peekingHonorsNextQueueWithoutConsumingIt() {
        assertEquals(2, thread.autoMixNextSong().getId());
        thread.queueNext(song(8));
        assertEquals(8, thread.autoMixNextSong().getId());
        assertTrue(thread.hasQueuedNext());
        assertEquals(List.of(1L, 2L, 3L), CloudMusic.playList.stream().map(Music::getId).toList());
        thread.advanceAfterTrack(0);
        assertEquals(8, CloudMusic.playList.get(CloudMusic.curIdx).getId());
    }

    @Test void sequentialEndAndSingleRepeatHaveNoMixCandidateButListLoopWraps() {
        CloudMusic.curIdx = 2;
        assertNull(thread.autoMixNextSong());
        CloudMusic.playMode = thread.lastMode = CloudMusic.PlayMode.LoopInList;
        // Recreate at the final song so activeSong has the correct identity.
        thread = new CloudMusic.PlayThread(CloudMusic.playList, 2);
        CloudMusic.playThread = thread;
        assertEquals(1, thread.autoMixNextSong().getId());
        CloudMusic.playMode = thread.lastMode = CloudMusic.PlayMode.LoopSingle;
        assertNull(thread.autoMixNextSong());
    }

    @Test void pendingModeChangeDoesNotSpeculateBeforeShuffle() {
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        assertNull(thread.autoMixNextSong());
    }

    @Test void seekCancelsBothPreparedAndStartedOldVoices() throws Exception {
        CloudMusic.PlayThread.Preparation request = prepare();
        a.position = 55_000;
        thread.tickAutoMix();
        assertEquals(1, b.starts);
        CloudMusic.seek(12_000);
        assertTrue(b.closed);
        assertEquals(12_000, a.position);
        assertEquals(1, a.gain);
        assertSame(a, CloudMusic.player);
        assertTrue(CloudMusic.playing.get());
        assertFalse(request.cancelled); // ownership had already transferred into the transition
    }

    @Test void pauseResumeAndVolumeReachBothVoices() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        CloudMusic.setPaused(true);
        assertTrue(a.paused); assertTrue(b.paused);
        CloudMusic.setVolume(.31f);
        assertEquals(.31f, a.volume); assertEquals(.31f, b.volume);
        CloudMusic.setPaused(false);
        assertFalse(a.paused); assertFalse(b.paused);
    }

    @Test void aNewQueueRequestBeforeLogicalSwitchCancelsTheOldCandidate() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        thread.queueNext(song(9));
        assertTrue(b.closed);
        assertEquals(1, a.gain);
        assertEquals(9, thread.autoMixNextSong().getId());
        assertSame(a, CloudMusic.player);
    }

    @Test void disablingBeforeOverlapClosesPreparedVoiceAndLateResultsAreDisposed() throws Exception {
        CloudMusic.PlayThread.Preparation request = prepare();
        DesktopAppState.preferences().autoMix().setValue(false);
        assertTrue(request.cancelled);
        assertTrue(b.closed);
        Probe late = probe();
        request.finish(late, plan());
        assertTrue(late.closed);
        assertEquals(0, late.starts);
    }

    @Test void disablingDuringOverlapLetsCurrentFadeFinish() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        DesktopAppState.preferences().autoMix().setValue(false);
        assertFalse(b.closed);
        assertNotNull(field("transition").get(thread));
        // Cancelling on a manual seek remains immediate, even after the setting is disabled.
        CloudMusic.seek(5_000);
        assertTrue(b.closed);
    }

    @Test void tooLatePreparationDoesNotStartOrSkipTheNextSong() throws Exception {
        prepare(); a.position = 56_000; thread.tickAutoMix();
        assertTrue(b.closed);
        assertEquals(0, b.starts);
        assertEquals(0, CloudMusic.curIdx);
        assertSame(a, CloudMusic.player);
        assertTrue(CloudMusic.playing.get());
    }

    @Test void cancellingAfterOutgoingEofCannotLeavePlaybackWaitingForever() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        // EOF happened on JSyn while the playback thread was about to process a UI queue request.
        a.finished = true;
        thread.queueNext(song(9));
        assertTrue(b.closed);
        assertFalse(CloudMusic.playing.get());
        thread.advanceAfterTrack(0);
        assertEquals(9, CloudMusic.playList.get(CloudMusic.curIdx).getId());
    }

    @Test void changingModeCancelsOverlapWithoutPrematurelyReshufflingTheQueue() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        CloudMusic.cyclePlayMode(() -> { throw new AssertionError("Unexpected heartbeat request"); });
        assertEquals(CloudMusic.PlayMode.LoopInList, CloudMusic.playMode);
        assertTrue(b.closed);
        assertSame(a, CloudMusic.player);
        assertEquals(0, CloudMusic.curIdx);
        assertEquals(List.of(1L, 2L, 3L), CloudMusic.playList.stream().map(Music::getId).toList());
    }

    @Test void preparationReplacedDuringNetworkWorkCannotPublishItsDeck() throws Exception {
        var request = new CloudMusic.PlayThread.Preparation(song(2), a, revision());
        field("preparation").set(thread, request);
        thread.queueNext(song(9));
        request.finish(b, plan());
        assertTrue(b.closed);
        assertNull(request.take());
        assertEquals(9, thread.autoMixNextSong().getId());
    }

    @Test void manualNextDuringOverlapStopsBothVoicesAndAdvancesOnce() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        CloudMusic.next();
        assertTrue(a.closed); assertTrue(b.closed);
        assertFalse(CloudMusic.playing.get());
        thread.advanceAfterTrack(0);
        assertEquals(1, CloudMusic.curIdx);
        assertEquals(2, CloudMusic.playList.get(CloudMusic.curIdx).getId());
    }

    private CloudMusic.PlayThread.Preparation prepare() throws Exception {
        var request = new CloudMusic.PlayThread.Preparation(song(2), a, revision());
        request.finish(b, plan());
        field("preparation").set(thread, request);
        return request;
    }
    private long revision() throws Exception { return field("mixRevision").getLong(thread); }
    private Field field(String name) throws Exception {
        Field result = CloudMusic.PlayThread.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private AutoMixPlan plan() {
        return new AutoMixPlan(AutoMixPlan.Strategy.CROSSFADE, 55_000, 0, 60_000, 57_500,
                AutoMixPlan.Curve.S, AutoMixPlan.Curve.S, false);
    }
    private Music song(long id) {
        return new Gson().fromJson("{\"id\":" + id + ",\"dt\":60000,\"name\":\"test\",\"ar\":[]}", Music.class);
    }
    private Probe probe() {
        Probe result = new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", Probe.class); // no device or SoundFile constructor
        result.gain = 1;
        return result;
    }
    private static final class Probe extends AudioPlayer {
        double gain;
        float position;
        boolean closed, paused, finished;
        int starts;
        private Probe(File file) { super(file); }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public float getTotalTimeMillis() { return 60_000; }
        @Override public boolean isFinished() { return closed || finished; }
        @Override public boolean isPausing() { return paused; }
        @Override public void startAt(float millis) { position = millis; starts++; }
        @Override public void setMixGain(double gain) { this.gain = gain; }
        @Override public void setMixFilter(double low, double high) { }
        @Override public void setAnalysisEnabled(boolean enabled) { }
        @Override public void setVolume(float volume) { this.volume = volume; }
        @Override public void setPlaybackTime(float millis) { position = millis; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public void close() { closed = true; }
    }
}
