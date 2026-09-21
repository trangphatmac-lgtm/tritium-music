package tritium.ncm.music.dto;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import tritium.ncm.RequestUtil;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayListCacheTest {
    private final PlayList playlist = new Gson().fromJson("{\"id\":10}", PlayList.class);

    @Test
    void reopeningAfterInvalidationLoadsFreshSongsAndPreservesPlaybackSnapshot() {
        List<Music> original = playlist.loadMusics(() -> songs(101), Runnable::run).join();
        playlist.invalidateMusics();
        List<Music> refreshed = playlist.loadMusics(() -> songs(202), Runnable::run).join();
        assertEquals(List.of(101L), ids(original));
        assertEquals(List.of(202L), ids(refreshed));
        assertSame(refreshed, playlist.getMusics());
    }

    @Test
    void invalidationDuringLoadRetriesAndDeliversFreshDataToEveryWaitingPanel() {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger requests = new AtomicInteger();
        var first = playlist.loadMusics(() -> {
            if (requests.incrementAndGet() == 1) {
                playlist.invalidateMusics();
                return songs(101);
            }
            return songs(202);
        }, work::add);
        var reopened = playlist.loadMusics(() -> {
            fail("Readers must share the in-flight request");
            return null;
        }, work::add);
        assertSame(first, reopened);
        work.remove().run();
        assertEquals(2, requests.get());
        assertEquals(List.of(202L), ids(first.join()));
        assertEquals(List.of(202L), ids(reopened.join()));
        assertTrue(playlist.musicsLoaded);
    }

    @Test
    void removingTheLastSongCachesAnEmptyResultAndStillCallsBackOnReentry() {
        playlist.loadMusics(() -> songs(101), Runnable::run).join();
        playlist.invalidateMusics();
        assertTrue(playlist.loadMusics(() -> songs(), Runnable::run).join().isEmpty());
        AtomicInteger callbacks = new AtomicInteger();
        playlist.loadMusicsWithCallback(musics -> {
            assertTrue(musics.isEmpty());
            callbacks.incrementAndGet();
        });
        assertEquals(1, callbacks.get());
    }

    @Test
    void rejectedLoadCanBeRetriedOnReentry() {
        var failed = playlist.loadMusics(() -> songs(), task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        assertTrue(failed.isCompletedExceptionally());
        assertFalse(playlist.musicsLoaded);
        assertFalse(playlist.musicsQueried);
        assertEquals(List.of(101L), ids(playlist.loadMusics(() -> songs(101), Runnable::run).join()));
    }

    private static List<Long> ids(List<Music> musics) {
        return musics.stream().map(Music::getId).toList();
    }

    private static RequestUtil.RequestAnswer songs(long... ids) {
        JsonArray songs = new JsonArray();
        for (long id : ids) {
            JsonObject song = new JsonObject();
            song.addProperty("id", id);
            songs.add(song);
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", 200);
        body.add("songs", songs);
        return RequestUtil.RequestAnswer.of(body, 200, null);
    }
}
