package tritium.ncm.music;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.desktop.DesktopAppState;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.automix.AutoMixAutomation;
import tritium.ncm.music.automix.AutoMixDebugSnapshot;
import tritium.ncm.music.automix.SongFeatures;
import static tritium.ncm.music.automix.AutoMixDebugSnapshot.Status.*;
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
    private final boolean oldFullMode = DesktopAppState.preferences().autoMixFullMode().getValue();
    private final boolean oldDebug = DesktopAppState.preferences().autoMixDebug().getValue();
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
        DesktopAppState.preferences().autoMixFullMode().setValue(false);
    }

    @AfterEach void restore() {
        DesktopAppState.preferences().autoMixDebug().setValue(oldDebug);
        thread.cancelAutoMix();
        DesktopAppState.preferences().autoMixFullMode().setValue(oldFullMode);
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

    @Test void switchingModesReconcilesNextSongBeforeCurrentTrackEndsEvenWhenPaused() throws Exception {
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        a.paused = true;
        a.position = 8_300;
        for (var mode : List.of(CloudMusic.PlayMode.LoopInList, CloudMusic.PlayMode.LoopSingle,
                CloudMusic.PlayMode.Random)) {
            CloudMusic.cyclePlayMode(() -> { throw new AssertionError("Unexpected heartbeat request"); });
            thread.tickAutoMix();
            assertEquals(mode, CloudMusic.playMode);
            assertEquals(mode == CloudMusic.PlayMode.LoopSingle ? SINGLE_REPEAT : PAUSED, snapshot().status());
            if (mode == CloudMusic.PlayMode.LoopSingle) assertNull(snapshot().b());
            else assertNotNull(snapshot().b());
            assertSame(a, CloudMusic.player);
            assertEquals(1, CloudMusic.playList.get(CloudMusic.curIdx).getId());
            assertEquals(8_300, a.position);
            assertTrue(a.paused);
        }
    }

    @Test void randomCandidateStaysStableAndMatchesTheActualNextSong() {
        CloudMusic.curIdx = 1;
        CloudMusic.currentlyPlaying = CloudMusic.playList.get(1);
        thread = new CloudMusic.PlayThread(CloudMusic.playList, 1);
        CloudMusic.playThread = thread;
        a.duration = 180_000; // No network preparation is needed to resolve the new order.
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        thread.tickAutoMix();
        Music next = thread.autoMixNextSong();
        assertNotNull(next);
        assertNotEquals(2, next.getId());
        var order = List.copyOf(CloudMusic.playList);
        for (int i = 0; i < 10; i++) {
            thread.tickAutoMix();
            assertSame(next, thread.autoMixNextSong());
            assertEquals(order, CloudMusic.playList);
        }
        assertEquals(1, CloudMusic.curIdx);
        assertEquals(2, CloudMusic.playList.get(1).getId());
        thread.advanceAfterTrack(1);
        assertSame(next, CloudMusic.playList.get(CloudMusic.curIdx));
        assertEquals(order, CloudMusic.playList);
    }

    @Test void changingModeDisposesOldPreparationAndAllowsANewTransition() throws Exception {
        var old = prepare();
        CloudMusic.cyclePlayMode(() -> { throw new AssertionError("Unexpected heartbeat request"); });
        assertTrue(old.cancelled);
        assertTrue(b.closed);
        a.duration = 180_000;
        thread.tickAutoMix();
        assertEquals(2, thread.autoMixNextSong().getId());
        Probe late = probe();
        old.finish(late, plan());
        assertTrue(late.closed);
        b = probe();
        prepare();
        a.duration = 60_000;
        a.position = 55_000;
        thread.tickAutoMix();
        assertEquals(1, b.starts);
        assertNotNull(field("transition").get(thread));
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

    @Test void fullModeChangeInvalidatesPreparedPlanAndDisposesLateResults() throws Exception {
        var request = prepare();
        assertFalse(request.fullMode);
        DesktopAppState.preferences().autoMixFullMode().setValue(true);
        assertTrue(request.cancelled);
        assertTrue(b.closed);
        assertNull(field("preparation").get(thread));
        var late = probe(); request.finish(late, plan()); assertTrue(late.closed);
        assertTrue(new CloudMusic.PlayThread.Preparation(song(2), a, revision()).fullMode);
    }

    @Test void fullModeChangeKeepsActivePlanUntilSeekOrCompletion() throws Exception {
        DesktopAppState.preferences().autoMixFullMode().setValue(true);
        var request = new CloudMusic.PlayThread.Preparation(song(2), a, revision());
        var ga = new AutoMixAutomation.Gain(55_000, 60_000, AutoMixPlan.Curve.LINEAR, false, true);
        var gb = new AutoMixAutomation.Gain(0, 8_000, AutoMixPlan.Curve.LINEAR, true, true);
        var full = new AutoMixPlan(AutoMixPlan.Strategy.VOCAL, 55_000, 0, 60_000, 57_500,
                ga.curve(), gb.curve(), false, new AutoMixAutomation(55_000,
                new AutoMixAutomation.Deck(ga, List.of()), new AutoMixAutomation.Deck(gb, List.of()), "test"));
        request.finish(b, full); field("preparation").set(thread, request);
        a.position = 55_000; thread.tickAutoMix();
        DesktopAppState.preferences().autoMixFullMode().setValue(false);
        var transition = (AutoMixTransition) field("transition").get(thread);
        assertSame(full, transition.plan);
        a.position = 60_000; b.position = 5_000; thread.tickAutoMix();
        assertTrue(a.closed); assertFalse(b.closed);
        assertSame(b, CloudMusic.player);
        assertEquals(2, CloudMusic.currentlyPlaying.getId());
        assertSame(transition, field("transition").get(thread));
        CloudMusic.setPaused(true); assertTrue(b.paused);
        CloudMusic.setPaused(false); assertFalse(b.paused);
        CloudMusic.seek(12_000);
        assertNull(field("transition").get(thread));
        assertSame(b, CloudMusic.player);
        assertEquals(1, b.gain);
        assertEquals(12_000, b.position);
        assertFalse(b.closed);
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

    @Test void changingModeCancelsOverlapAndResolvesANewCandidate() throws Exception {
        prepare(); a.position = 55_000; thread.tickAutoMix();
        CloudMusic.cyclePlayMode(() -> { throw new AssertionError("Unexpected heartbeat request"); });
        assertEquals(CloudMusic.PlayMode.LoopInList, CloudMusic.playMode);
        assertTrue(b.closed);
        assertSame(a, CloudMusic.player);
        assertEquals(0, CloudMusic.curIdx);
        assertEquals(List.of(1L, 2L, 3L), CloudMusic.playList.stream().map(Music::getId).toList());
        a.duration = 180_000;
        thread.tickAutoMix();
        assertEquals(2, thread.autoMixNextSong().getId());
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

    @Test void debugIsReadOnlyAndExplainsWaitingAndIneligibleModes() throws Exception {
        DesktopAppState.preferences().autoMix().setValue(false);
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        thread.tickAutoMix();
        assertEquals(DISABLED, snapshot().status());
        assertNull(field("preparation").get(thread));
        assertEquals(0, b.starts);
        DesktopAppState.preferences().autoMix().setValue(true);
        a.duration = 180_000;
        thread.tickAutoMix();
        assertEquals(WAITING_WINDOW, snapshot().status());
        a.duration = 30_000;
        thread.tickAutoMix();
        assertEquals(SHORT_TRACK, snapshot().status());
        CloudMusic.playMode = thread.lastMode = CloudMusic.PlayMode.LoopSingle;
        thread.tickAutoMix();
        assertEquals(SINGLE_REPEAT, snapshot().status());
        CloudMusic.playMode = thread.lastMode = CloudMusic.PlayMode.Sequential;
        CloudMusic.curIdx = 2;
        thread.tickAutoMix();
        assertEquals(NO_NEXT, snapshot().status());
        assertNull(field("preparation").get(thread));
        assertEquals(0, CloudMusic.player.getCurrentTimeMillis());
    }

    @Test void debugReportsPreparationFailureAndLateWindowWithoutRetrying() throws Exception {
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        var request = new CloudMusic.PlayThread.Preparation(song(2), a, revision());
        request.stage = DECODING;
        field("preparation").set(thread, request);
        thread.tickAutoMix();
        assertEquals(DECODING, snapshot().status());
        request.failureType = "IOException";
        request.stage = PREPARATION_FAILED;
        request.finish(null, null);
        thread.tickAutoMix();
        assertEquals(PREPARATION_FAILED, snapshot().status());
        assertEquals("IOException", snapshot().detail());
        var failed = snapshot();
        prepare(); a.position = 56_000; thread.tickAutoMix();
        assertEquals(LATE, snapshot().status());
        assertNull(snapshot().voiceB());
        assertEquals(PREPARATION_FAILED, failed.status(), "Published snapshots must not mutate");
        thread.tickAutoMix();
        assertEquals(LATE, snapshot().status());
        assertEquals(0, b.starts);
    }

    @Test void debugTracksActualGainPauseSeekAndPreservesTheOriginalPair() throws Exception {
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        var request = prepare();
        request.featuresA = SongFeatures.empty(1, 60_000).summary();
        request.featuresB = SongFeatures.empty(2, 60_000).summary();
        thread.tickAutoMix();
        assertEquals(READY, snapshot().status());
        assertEquals(55_000, snapshot().plan().startAMillis());
        assertFalse(snapshot().featuresA().available());
        a.position = 55_000; thread.tickAutoMix();
        a.position = 57_000; b.position = 2_000;
        CloudMusic.setVolume(.4f);
        thread.tickAutoMix();
        var mixed = snapshot();
        assertEquals(MIXING, mixed.status());
        assertEquals(a.gain, mixed.voiceA().gain());
        assertEquals(.4 * b.gain, mixed.voiceB().outputLevel(), 1e-7);
        assertTrue(mixed.voiceB().gain() > 0);
        CloudMusic.setPaused(true);
        thread.tickAutoMix();
        assertEquals(PAUSED, snapshot().status());
        assertEquals(0, snapshot().voiceB().outputLevel());
        assertEquals(mixed.voiceA().positionMillis(), snapshot().voiceA().positionMillis());
        CloudMusic.setPaused(false);
        // Simulate the logical handoff without initiating optional network/lyric notifications.
        a.position = 58_000; b.position = 3_000;
        ((AutoMixTransition) field("transition").get(thread)).tick(() -> { });
        thread.tickAutoMix();
        assertTrue(snapshot().switched());
        assertEquals(1, snapshot().a().id());
        assertEquals(2, snapshot().b().id());
        CloudMusic.seek(5_000);
        assertEquals(SEEK, snapshot().status());
        assertNull(snapshot().voiceB());
        assertFalse(snapshot().mixing());
        assertTrue(snapshot().events().stream().anyMatch(event -> event.status() == SEEK));
        assertTrue(snapshot().events().size() <= 5);
        assertThrows(UnsupportedOperationException.class, () -> snapshot().events().clear());
    }

    @Test void stoppedPlaybackPublishesSilentVoiceAndQueueChangesHaveTheirOwnReason() throws Exception {
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        prepare();
        thread.queueNext(song(9));
        assertEquals(QUEUE_CHANGED, snapshot().status());
        CloudMusic.next();
        var stopped = CloudMusic.autoMixDebugSnapshot();
        assertEquals(STOPPED, stopped.status());
        assertTrue(stopped.voiceA().finished());
        assertEquals(0, stopped.voiceA().outputLevel());
    }

    @Test void pausedReadyStateDoesNotFloodHistoryAndDisabledDebugClearsStaleSnapshots() throws Exception {
        DesktopAppState.preferences().autoMixDebug().setValue(true);
        prepare(); a.paused = true;
        thread.tickAutoMix();
        var paused = snapshot();
        for (int i = 0; i < 20; i++) thread.tickAutoMix();
        assertEquals(paused.events(), snapshot().events());
        DesktopAppState.preferences().autoMixDebug().setValue(false);
        thread.tickAutoMix();
        assertEquals(0, CloudMusic.autoMixDebugSnapshot().capturedAtMillis());
        assertEquals(0, b.starts);
        assertFalse(b.closed, "Hiding diagnostics must not dispose the prepared voice");
    }

    private AutoMixDebugSnapshot snapshot() throws Exception {
        field("lastDebugPublishNanos").setLong(thread, 0);
        thread.publishAutoMixDebug();
        return CloudMusic.autoMixDebugSnapshot();
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
        result.duration = 60_000;
        return result;
    }
    private static final class Probe extends AudioPlayer {
        double gain;
        float position, duration;
        boolean closed, paused, finished;
        int starts;
        private Probe(File file) { super(file); }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public float getTotalTimeMillis() { return duration; }
        @Override public double getMixGain() { return gain; }
        @Override public boolean isFinished() { return closed || finished; }
        @Override public boolean isPausing() { return paused; }
        @Override public void startAt(float millis) { position = millis; starts++; }
        @Override public void setMixGain(double gain) { this.gain = gain; }
        @Override public void setMixFilter(double low, double high) { }
        @Override public void setMixEqualizer(List<AutoMixAutomation.FilterSetting> settings) { }
        @Override public List<AutoMixAutomation.FilterSetting> getMixEqualizer() { return List.of(); }
        @Override public void setAnalysisEnabled(boolean enabled) { }
        @Override public void setVolume(float volume) { this.volume = volume; }
        @Override public void setPlaybackTime(float millis) { position = millis; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public void close() { closed = true; }
    }
}
