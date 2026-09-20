package tritium.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.RequestUtil;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CloudMusicFavoritesTest {
    private final Gson gson = new Gson();
    private final Music first = gson.fromJson("{\"id\":101,\"name\":\"First\"}", Music.class);
    private final Music second = gson.fromJson("{\"id\":202,\"name\":\"Second\"}", Music.class);
    private User previousProfile;
    private List<Long> previousLikes;
    private Music previousSong;

    @BeforeEach
    void setUp() {
        previousProfile = CloudMusic.profile;
        previousLikes = CloudMusic.likeList;
        previousSong = CloudMusic.currentlyPlaying;
        CloudMusic.profile = gson.fromJson("{\"userId\":1}", User.class);
        CloudMusic.likeList = List.of();
        CloudMusic.currentlyPlaying = first;
    }

    @AfterEach
    void tearDown() {
        CloudMusic.profile = previousProfile;
        CloudMusic.likeList = previousLikes;
        CloudMusic.currentlyPlaying = previousSong;
    }

    @Test
    void togglesBothDirectionsOnlyAfterSuccessfulResponse() {
        Queue<Runnable> work = new ArrayDeque<>();
        var added = CloudMusic.toggleLike(first, (id, like) -> {
            assertEquals(101L, id);
            assertTrue(like);
            return response(200, 200);
        }, work::add);
        assertFalse(added.isDone());
        assertFalse(CloudMusic.isLiked(101));
        assertTrue(CloudMusic.isLikePending(101));
        work.remove().run();
        assertEquals("已收藏", added.join());
        assertTrue(CloudMusic.isLiked(101));
        assertFalse(CloudMusic.isLikePending(101));

        var removed = CloudMusic.toggleLike(first, (id, like) -> {
            assertFalse(like);
            return response(200, 200);
        }, Runnable::run);
        assertEquals("已取消收藏", removed.join());
        assertFalse(CloudMusic.isLiked(101));
    }

    @Test
    void duplicateClicksSharePendingStateAndSwitchingTracksKeepsOriginalTarget() {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger requests = new AtomicInteger();
        var firstClick = CloudMusic.toggleLike(first, (id, like) -> {
            requests.incrementAndGet();
            assertEquals(101L, id);
            return response(200, 200);
        }, work::add);
        var duplicate = CloudMusic.toggleLike(first, (id, like) -> {
            fail("Duplicate click must not send a request");
            return null;
        }, work::add);
        assertEquals("正在更新收藏…", duplicate.join());
        CloudMusic.currentlyPlaying = second;
        work.remove().run();
        assertEquals("已收藏", firstClick.join());
        assertEquals(1, requests.get());
        assertTrue(CloudMusic.isLiked(101));
        assertFalse(CloudMusic.isLiked(202));
    }

    @Test
    void independentRequestsDoNotOverwriteEachOthersFavorites() {
        Queue<Runnable> work = new ArrayDeque<>();
        CloudMusic.toggleLike(first, (id, like) -> response(200, 200), work::add);
        CloudMusic.toggleLike(second, (id, like) -> response(200, 200), work::add);
        work.remove().run();
        work.remove().run();
        assertEquals(List.of(101L, 202L), CloudMusic.likeList);
    }

    @Test
    void failuresPreserveStateAndAllowRetry() {
        CloudMusic.likeList = List.of(101L);
        for (RequestUtil.RequestAnswer failure : List.of(response(200, 301), response(500, 200))) {
            assertEquals("收藏更新失败，请重试",
                    CloudMusic.toggleLike(first, (id, like) -> failure, Runnable::run).join());
            assertTrue(CloudMusic.isLiked(101));
            assertFalse(CloudMusic.isLikePending(101));
        }
        assertEquals("收藏更新失败，请重试", CloudMusic.toggleLike(first, (id, like) -> {
            throw new IllegalStateException("Network unavailable");
        }, Runnable::run).join());
        assertTrue(CloudMusic.isLiked(101));
        assertFalse(CloudMusic.isLikePending(101));
        assertEquals("已取消收藏",
                CloudMusic.toggleLike(first, (id, like) -> response(200, 200), Runnable::run).join());
    }

    @Test
    void unavailableSessionOrSongNeverSchedulesNetworkWork() {
        java.util.concurrent.Executor noWork = task -> fail("Must not schedule a request");
        assertEquals("未在播放", CloudMusic.toggleLike(null, null, noWork).join());
        CloudMusic.profile = null;
        assertEquals("请先登录后收藏歌曲", CloudMusic.toggleLike(first, null, noWork).join());
        CloudMusic.profile = gson.fromJson("{\"userId\":1}", User.class);
        CloudMusic.likeList = null;
        assertEquals("收藏列表尚未加载，请稍后重试", CloudMusic.toggleLike(first, null, noWork).join());
    }

    @Test
    void sessionChangeDuringRequestDoesNotPolluteNewUsersFavorites() {
        var result = CloudMusic.toggleLike(first, (id, like) -> {
            CloudMusic.profile = gson.fromJson("{\"userId\":2}", User.class);
            CloudMusic.likeList = List.of(202L);
            return response(200, 200);
        }, Runnable::run);
        assertEquals("登录状态已变化，请重试", result.join());
        assertEquals(List.of(202L), CloudMusic.likeList);
        assertFalse(CloudMusic.isLikePending(101));
    }

    @Test
    void rejectedSchedulingReleasesPendingState() {
        var result = CloudMusic.toggleLike(first, (id, like) -> response(200, 200), task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        assertEquals("收藏更新失败，请重试", result.join());
        assertFalse(CloudMusic.isLikePending(101));
        assertFalse(CloudMusic.isLiked(101));
    }

    private static RequestUtil.RequestAnswer response(int status, int code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return RequestUtil.RequestAnswer.of(body, status, null);
    }
}
