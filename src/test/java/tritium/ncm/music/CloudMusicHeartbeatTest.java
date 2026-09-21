package tritium.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.RequestUtil;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CloudMusicHeartbeatTest {
    private static final Gson GSON = new Gson();
    private static final Gson AUDIO_GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private final PlayList liked = playlist(10, 5, 1);
    private final Music first = song(101);
    private User oldProfile;
    private List<Long> oldLikes;
    private List<PlayList> oldPlaylists;
    private List<Music> oldQueue;
    private Music oldSong;
    private CloudMusic.PlayMode oldMode;
    private PlayList oldSource;
    private int oldIndex;
    private Thread oldThread;
    private AudioPlayer oldPlayer;
    private boolean oldPlaying, oldBreak, oldDontAdd;

    @BeforeEach
    void setUp() {
        oldProfile = CloudMusic.profile;
        oldLikes = CloudMusic.likeList;
        oldPlaylists = CloudMusic.playLists;
        oldQueue = CloudMusic.playList;
        oldSong = CloudMusic.currentlyPlaying;
        oldMode = CloudMusic.playMode;
        oldSource = CloudMusic.playedFrom;
        oldIndex = CloudMusic.curIdx;
        oldThread = CloudMusic.playThread;
        oldPlayer = CloudMusic.player;
        oldPlaying = CloudMusic.playing.get();
        oldBreak = CloudMusic.doBreak;
        oldDontAdd = CloudMusic.dontAdd;
        CloudMusic.playThread = null;
        CloudMusic.player = null;
        CloudMusic.doBreak = false;
        CloudMusic.dontAdd = false;
        CloudMusic.profile = GSON.fromJson("{\"userId\":1}", User.class);
        CloudMusic.likeList = List.of(101L);
        CloudMusic.playLists = List.of(playlist(9, 0, 1), liked);
        CloudMusic.playList = new ArrayList<>(List.of(first));
        CloudMusic.currentlyPlaying = first;
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        CloudMusic.curIdx = 0;
    }

    @AfterEach
    void tearDown() {
        CloudMusic.exitHeartbeat();
        CloudMusic.profile = oldProfile;
        CloudMusic.likeList = oldLikes;
        CloudMusic.playLists = oldPlaylists;
        CloudMusic.playList = oldQueue;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playMode = oldMode;
        CloudMusic.playedFrom = oldSource;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.playThread = oldThread;
        CloudMusic.player = oldPlayer;
        CloudMusic.playing.set(oldPlaying);
        CloudMusic.doBreak = oldBreak;
        CloudMusic.dontAdd = oldDontAdd;
    }

    @Test
    void enablingHeartbeatPreservesAudioPositionAndPauseStateUntilTheTrackEnds() {
        for (boolean paused : List.of(false, true)) {
            CloudMusic.exitHeartbeat();
            CloudMusic.playMode = CloudMusic.PlayMode.Random;
            CloudMusic.playList = new ArrayList<>(List.of(song(999), first, song(888)));
            CloudMusic.curIdx = 1;
            CloudMusic.currentlyPlaying = first;
            CloudMusic.playing.set(true);
            AudioProbe audio = AUDIO_GSON.fromJson("{\"position\":42000,\"paused\":" + paused + "}", AudioProbe.class);
            CloudMusic.player = audio;
            var thread = new CloudMusic.PlayThread(CloudMusic.playList, 1);
            CloudMusic.playThread = thread;
            var originalQueue = CloudMusic.playList;

            String message = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 202, 101, 303),
                    Runnable::run, CloudMusic::startHeartbeatPlayback).join();
            assertTrue(message.startsWith("已开启"));
            assertSame(thread, CloudMusic.playThread);
            assertSame(audio, CloudMusic.player);
            assertSame(first, CloudMusic.currentlyPlaying);
            assertSame(originalQueue, CloudMusic.playList);
            assertEquals(1, CloudMusic.curIdx);
            assertTrue(CloudMusic.playing.get());
            assertEquals(42000, audio.getCurrentTimeMillis());
            assertEquals(paused, audio.isPausing());

            // Simulate the natural end of the track, without starting native audio or network work.
            assertTrue(thread.adoptHeartbeat(1));
            assertEquals(List.of(999L, 101L, 202L, 303L), ids(CloudMusic.playList));
            assertEquals(2, CloudMusic.curIdx);
            assertSame(thread, CloudMusic.playThread);
        }
    }

    @Test
    void pendingRecommendationsUseTheTrackThatActuallyFinishesEvenIfPlaybackAdvancedDuringLoading() {
        Queue<Runnable> work = new ArrayDeque<>();
        CloudMusic.playList = new ArrayList<>(List.of(first, song(202), song(888)));
        var thread = new CloudMusic.PlayThread(CloudMusic.playList, 0);
        CloudMusic.playThread = thread;
        var result = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 202, 303, 101),
                work::add, CloudMusic::startHeartbeatPlayback);
        CloudMusic.curIdx = 1;
        Music advanced = CloudMusic.playList.get(1);
        CloudMusic.currentlyPlaying = advanced;
        work.remove().run();
        assertTrue(result.join().startsWith("已开启"));
        assertSame(advanced, CloudMusic.currentlyPlaying);
        assertTrue(thread.adoptHeartbeat(1));
        assertEquals(List.of(101L, 202L, 303L), ids(CloudMusic.playList));
        assertEquals(2, CloudMusic.curIdx);
    }

    @Test
    void exitingBeforeTheTrackEndsDiscardsThePendingQueue() {
        var thread = new CloudMusic.PlayThread(CloudMusic.playList, 0);
        CloudMusic.playThread = thread;
        var original = CloudMusic.playList;
        CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 202),
                Runnable::run, CloudMusic::startHeartbeatPlayback).join();
        CloudMusic.exitHeartbeat();
        assertFalse(thread.adoptHeartbeat(0));
        assertSame(original, CloudMusic.playList);
        assertSame(first, CloudMusic.currentlyPlaying);
    }

    @Test
    void manualNextOrPreviousIsPreservedWhenTheRecommendationQueueTakesOver() {
        for (int targetIndex : List.of(0, 2)) {
            CloudMusic.exitHeartbeat();
            CloudMusic.playList = new ArrayList<>(List.of(first, song(202), song(888)));
            CloudMusic.curIdx = 1;
            var thread = new CloudMusic.PlayThread(CloudMusic.playList, 1);
            CloudMusic.playThread = thread;
            CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 303),
                    Runnable::run, CloudMusic::startHeartbeatPlayback).join();
            CloudMusic.dontAdd = true;
            CloudMusic.curIdx = targetIndex;
            assertTrue(thread.adoptHeartbeat(1));
            assertEquals(List.of(101L, 202L, 303L), ids(CloudMusic.playList));
            assertEquals(targetIndex, CloudMusic.curIdx);
            assertFalse(CloudMusic.dontAdd);
        }
    }

    /** Gson allocates this test double without constructing a native SoundFile. */
    private static class AudioProbe extends AudioPlayer {
        @Expose private float position;
        @Expose private boolean paused;

        private AudioProbe(java.io.File file) { super(file); }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public boolean isPausing() { return paused; }
        @Override public void close() { fail("Switching mode must not close current audio"); }
        @Override public void play() { fail("Switching mode must not restart current audio"); }
        @Override public void pause() { fail("Switching mode must preserve pause state"); }
        @Override public void unpause() { fail("Switching mode must preserve pause state"); }
        @Override public void setPlaybackTime(float millis) { fail("Switching mode must not seek"); }
    }

    @Test
    void usesCurrentLikedSongAndRealLikedPlaylistWithoutInterruptingPlaybackWhileLoading() {
        Queue<Runnable> work = new ArrayDeque<>();
        var originalQueue = CloudMusic.playList;
        var result = CloudMusic.toggleHeartbeat((id, pid, sid) -> {
            assertEquals(101, id);
            assertEquals(10, pid);
            assertEquals(101, sid);
            return response(200, 200, 101, 202);
        }, work::add, session -> CloudMusic.playList = session.songs);
        assertTrue(CloudMusic.isHeartbeatLoading());
        assertSame(originalQueue, CloudMusic.playList);
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
        work.remove().run();
        assertTrue(result.join().startsWith("已开启"));
        assertFalse(CloudMusic.isHeartbeatLoading());
        assertEquals(CloudMusic.PlayMode.Heartbeat, CloudMusic.playMode);
        assertEquals(List.of(101L, 202L), ids(CloudMusic.playList));
        assertSame(liked, CloudMusic.playedFrom);
    }

    @Test
    void unlikedCurrentTrackDoesNotBecomeTheSeed() {
        CloudMusic.currentlyPlaying = song(999);
        CloudMusic.toggleHeartbeat((id, pid, sid) -> {
            assertEquals(101, id);
            return response(200, 200, 101);
        }, Runnable::run, session -> {});
    }

    @Test
    void secondClickCancelsPendingWorkAndLateResponseCannotStartPlayback() {
        Queue<Runnable> work = new ArrayDeque<>();
        var result = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 101),
                work::add, session -> fail("Cancelled request started playback"));
        assertEquals("已退出心动模式", CloudMusic.toggleHeartbeat(null, null, null).join());
        work.remove().run();
        assertEquals("心动模式请求已取消", result.join());
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
        assertFalse(CloudMusic.isHeartbeatLoading());
    }

    @Test
    void lateOldResponseCannotReplaceANewerSession() {
        Queue<Runnable> work = new ArrayDeque<>();
        CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 202), work::add,
                session -> fail("Old request started playback"));
        CloudMusic.exitHeartbeat();
        var latest = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 303), work::add,
                session -> CloudMusic.playList = session.songs);
        work.remove().run();
        assertTrue(CloudMusic.isHeartbeatLoading());
        work.remove().run();
        assertTrue(latest.join().startsWith("已开启"));
        assertEquals(List.of(303L), ids(CloudMusic.playList));
    }

    @Test
    void accountChangeDiscardsRecommendations() {
        Queue<Runnable> work = new ArrayDeque<>();
        var result = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 101),
                work::add, session -> fail("Wrong account"));
        CloudMusic.profile = GSON.fromJson("{\"userId\":2}", User.class);
        work.remove().run();
        assertEquals("登录状态已变化，请重试", result.join());
        assertFalse(CloudMusic.isHeartbeatLoading());
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
    }

    @Test
    void failuresAndEmptyResultsPreserveTheExistingQueueAndAllowRetry() {
        var original = CloudMusic.playList;
        for (var answer : List.of(response(500, 200, 101), response(200, 301, 101), response(200, 200))) {
            String message = CloudMusic.toggleHeartbeat((id, pid, sid) -> answer,
                    Runnable::run, session -> fail("Failed request started playback")).join();
            assertFalse(message.startsWith("已开启"));
            assertSame(original, CloudMusic.playList);
            assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
            assertFalse(CloudMusic.isHeartbeatLoading());
        }
        assertTrue(CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 101),
                Runnable::run, session -> {}).join().startsWith("已开启"));
    }

    @Test
    void schedulingFailureDoesNotLeaveTheControlLoading() {
        String message = CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 101),
                command -> { throw new java.util.concurrent.RejectedExecutionException(); },
                session -> fail()).join();
        assertTrue(message.contains("失败"));
        assertFalse(CloudMusic.isHeartbeatLoading());
    }

    @Test
    void unavailablePrerequisitesNeverScheduleARequest() {
        java.util.concurrent.Executor noWork = command -> fail("Unexpected network request");
        CloudMusic.profile = null;
        assertTrue(CloudMusic.toggleHeartbeat(null, noWork, null).join().contains("登录"));
        CloudMusic.profile = GSON.fromJson("{\"userId\":1}", User.class);
        CloudMusic.likeList = null;
        assertTrue(CloudMusic.toggleHeartbeat(null, noWork, null).join().contains("尚未加载"));
        CloudMusic.likeList = List.of();
        assertTrue(CloudMusic.toggleHeartbeat(null, noWork, null).join().contains("先收藏"));
        CloudMusic.likeList = List.of(101L);
        CloudMusic.playLists = List.of(playlist(9, 0, 1), playlist(10, 5, 2));
        assertTrue(CloudMusic.toggleHeartbeat(null, noWork, null).join().contains("未找到"));
    }

    @Test
    void exitRestoresPreviousModeWithoutChangingCurrentSongOrQueue() {
        CloudMusic.toggleHeartbeat((id, pid, sid) -> response(200, 200, 101), Runnable::run,
                session -> CloudMusic.playList = session.songs).join();
        var queue = CloudMusic.playList;
        CloudMusic.exitHeartbeat();
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
        assertSame(queue, CloudMusic.playList);
        assertSame(first, CloudMusic.currentlyPlaying);
    }

    @Test
    void modeButtonCyclesThroughAllFiveModesAndActuallyStartsHeartbeatPlayback() {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger starts = new AtomicInteger();
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        for (var expected : List.of(CloudMusic.PlayMode.LoopInList,
                CloudMusic.PlayMode.LoopSingle, CloudMusic.PlayMode.Random)) {
            CloudMusic.cyclePlayMode(() -> { fail("Heartbeat started too early"); return null; }).join();
            assertEquals(expected, CloudMusic.playMode);
        }
        var result = CloudMusic.cyclePlayMode(() -> CloudMusic.toggleHeartbeat(
                (id, pid, sid) -> response(200, 200, 101, 202), work::add, session -> {
                    starts.incrementAndGet();
                    CloudMusic.playList = session.songs;
                }));
        assertTrue(CloudMusic.isHeartbeatLoading());
        assertFalse(result.isDone());
        work.remove().run();
        assertEquals(CloudMusic.PlayMode.Heartbeat, CloudMusic.playMode);
        assertEquals(1, starts.get());
        assertEquals(List.of(101L, 202L), ids(CloudMusic.playList));
        var queue = CloudMusic.playList;
        CloudMusic.cyclePlayMode(() -> { fail("Exit must not request recommendations"); return null; }).join();
        assertEquals(CloudMusic.PlayMode.Sequential, CloudMusic.playMode);
        assertSame(queue, CloudMusic.playList);
        assertSame(first, CloudMusic.currentlyPlaying);
    }

    @Test
    void modeButtonAdvancesPastPendingHeartbeatWithoutALatePlaybackTakeover() {
        Queue<Runnable> work = new ArrayDeque<>();
        var result = CloudMusic.cyclePlayMode(() -> CloudMusic.toggleHeartbeat(
                (id, pid, sid) -> response(200, 200, 101), work::add, session -> fail("Cancelled")));
        assertTrue(CloudMusic.isHeartbeatLoading());
        CloudMusic.cyclePlayMode(() -> { fail("Duplicate request"); return null; }).join();
        assertFalse(CloudMusic.isHeartbeatLoading());
        assertEquals(CloudMusic.PlayMode.Sequential, CloudMusic.playMode);
        work.remove().run();
        assertEquals("心动模式请求已取消", result.join());
        assertEquals(CloudMusic.PlayMode.Sequential, CloudMusic.playMode);
    }

    @Test
    void modeButtonReportsUnavailableHeartbeatAndKeepsTheExistingModeAndQueue() {
        CloudMusic.profile = null;
        var queue = CloudMusic.playList;
        String message = CloudMusic.cyclePlayMode(() -> CloudMusic.toggleHeartbeat(null,
                command -> fail("Logged-out users must not send requests"), session -> fail())).join();
        assertTrue(message.contains("请先登录"));
        assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
        assertSame(queue, CloudMusic.playList);
    }

    @Test
    void prefetchIsSingleFlightAndContinuationUsesLastSongAndOriginalSeed() {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger count = new AtomicInteger();
        HeartbeatSession session = new HeartbeatSession(CloudMusic.profile, liked, 101, (id, pid, sid) -> {
            assertEquals(202, id);
            assertEquals(10, pid);
            assertEquals(101, sid);
            count.incrementAndGet();
            return response(200, 200, 202, 303, 303, 404);
        }, work::add);
        session.songs.addAll(List.of(song(101), song(202)));
        var future = session.requestMore(202);
        assertSame(future, session.requestMore(202));
        work.remove().run();
        assertEquals(1, count.get());
        assertEquals(List.of(303L, 404L), ids(session.freshSongs(future.join())));
        assertEquals(List.of(101L, 202L), ids(session.songs), "Background work must not mutate playback queue");
        session.consumed();
        assertNotSame(future, session.requestMore(202));
    }

    @Test
    void playbackBoundaryAppendsRecommendationsAndCanRetryAFailedBatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CloudMusic.toggleHeartbeat((id, pid, sid) -> switch (requests.incrementAndGet()) {
            case 1 -> response(200, 200, 101, 202);
            case 2 -> response(500, 500);
            default -> response(200, 200, 202, 303);
        }, Runnable::run, session -> CloudMusic.playList = session.songs).join();
        CloudMusic.curIdx = 2;
        var type = Class.forName("tritium.ncm.music.CloudMusic$PlayThread");
        var constructor = type.getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        Object thread = constructor.newInstance(CloudMusic.playList, 2);
        var ensureNext = type.getDeclaredMethod("ensureNextHeartbeatSong");
        ensureNext.setAccessible(true);
        assertEquals(false, ensureNext.invoke(thread));
        assertNotNull(CloudMusic.heartbeatError());
        assertEquals(List.of(101L, 202L), ids(CloudMusic.playList));
        assertEquals(true, ensureNext.invoke(thread));
        assertNull(CloudMusic.heartbeatError());
        assertEquals(List.of(101L, 202L, 303L), ids(CloudMusic.playList));
        assertEquals(2, CloudMusic.curIdx);
    }

    @Test
    void malformedEntriesAreIgnoredAndServerOrderIsPreserved() {
        var answer = response(200, 200, 303, 101, 303);
        JsonObject body = answer.toJsonObject();
        body.getAsJsonArray("data").add(new JsonObject());
        JsonObject invalid = new JsonObject();
        invalid.add("songInfo", GSON.fromJson("{\"id\":555}", JsonObject.class));
        body.getAsJsonArray("data").add(invalid);
        assertEquals(List.of(303L, 101L), ids(HeartbeatSession.parse(RequestUtil.RequestAnswer.of(body, 200, null))));
    }

    private static List<Long> ids(List<Music> songs) {
        return songs.stream().map(Music::getId).toList();
    }

    private static PlayList playlist(long id, int specialType, long userId) {
        return GSON.fromJson("{\"id\":" + id + ",\"specialType\":" + specialType
                + ",\"creator\":{\"userId\":" + userId + "}}", PlayList.class);
    }

    private static Music song(long id) {
        return GSON.fromJson(songJson(id), Music.class);
    }

    private static JsonObject songJson(long id) {
        return GSON.fromJson("{\"id\":" + id + ",\"name\":\"Song " + id
                + "\",\"ar\":[{\"id\":1,\"name\":\"Artist\"}],\"al\":{\"id\":1,\"name\":\"Album\"}}", JsonObject.class);
    }

    private static RequestUtil.RequestAnswer response(int status, int code, long... ids) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        JsonArray data = new JsonArray();
        for (long id : ids) {
            JsonObject item = new JsonObject();
            item.add("songInfo", songJson(id));
            data.add(item);
        }
        body.add("data", data);
        return RequestUtil.RequestAnswer.of(body, status, null);
    }
}
