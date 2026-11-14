package tritium.desktop.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import tritium.desktop.DesktopAppState;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.Quality;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import tritium.utils.Tuple;

import static org.junit.jupiter.api.Assertions.*;

class McpToolsTest {
    @TempDir Path directory;
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final Thread oldThread = CloudMusic.playThread;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final User oldUser = CloudMusic.profile;
    private final List<Long> oldLikes = CloudMusic.likeList;
    private final List<PlayList> oldPlaylists = CloudMusic.playLists;
    private final PlayList oldSource = CloudMusic.playedFrom;
    private final List<Music> oldQueue = CloudMusic.playList;
    private final int oldIndex = CloudMusic.curIdx;
    private final CloudMusic.PlayMode oldMode = CloudMusic.playMode;
    private final double oldVolume = DesktopAppState.preferences().volume().getValue();
    private final boolean oldTranslation = DesktopAppState.preferences().showTranslation().getValue();
    private final boolean oldRunning = DesktopAppState.isRunning();
    private final String oldDownloadDirectory = DesktopAppState.preferences().downloadDirectory().getValue();
    private final String oldDownloadQuality = DesktopAppState.preferences().downloadQuality().getValue();
    private McpTools tools;

    @BeforeEach void setup() {
        DesktopAppState.markMainThread();
        DesktopAppState.setRunning(true);
        CloudMusic.player = null;
        CloudMusic.playThread = null;
        CloudMusic.currentlyPlaying = new Gson().fromJson("{\"id\":123,\"name\":\"测试歌曲\",\"ar\":[],\"dt\":60000}", Music.class);
        CloudMusic.profile = new User(1, "用户", "", 0, "");
        CloudMusic.likeList = List.of(123L);
        CloudMusic.playedFrom = new PlayList(42, "测试歌单", "", 1, 0, CloudMusic.profile, "", false, 0, 0);
        CloudMusic.playedFrom.setSearchMode(true);
        CloudMusic.playedFrom.musics = List.of(CloudMusic.currentlyPlaying);
        CloudMusic.playList = List.of(CloudMusic.currentlyPlaying);
        CloudMusic.curIdx = 0;
        CloudMusic.playLists = List.of(CloudMusic.playedFrom, new PlayList(43, "收藏歌单", "", 0, 0, null, "", true, 0, 0));
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        tools = new McpTools();
    }

    @AfterEach void restore() {
        CloudMusic.player = oldPlayer;
        CloudMusic.playThread = oldThread;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.profile = oldUser;
        CloudMusic.likeList = oldLikes;
        CloudMusic.playLists = oldPlaylists;
        CloudMusic.playedFrom = oldSource;
        CloudMusic.playList = oldQueue;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.setPlayMode(oldMode);
        DesktopAppState.preferences().volume().setValue(oldVolume);
        DesktopAppState.preferences().showTranslation().setValue(oldTranslation);
        DesktopAppState.preferences().downloadDirectory().setValue(oldDownloadDirectory);
        DesktopAppState.preferences().downloadQuality().setValue(oldDownloadQuality);
        DesktopAppState.setRunning(oldRunning);
    }

    @Test void exposesRequestedToolsAndPlaybackMetadata() {
        assertEquals(17, tools.list().size());
        JsonObject state = call("get_playback_state", "{}");
        assertEquals("Sequential", state.get("playMode").getAsString());
        assertEquals(42, state.getAsJsonObject("sourcePlaylist").get("id").getAsLong());
        assertTrue(state.getAsJsonObject("song").get("liked").getAsBoolean());
        assertEquals(1, call("get_queue", "{}").get("total").getAsInt());
        assertEquals(0, call("get_queue", "{}").get("currentIndex").getAsInt());
        assertEquals(1, call("get_playlist", "{\"playlistId\":42}").get("total").getAsInt());
        assertEquals(42, call("list_playlists", "{\"source\":\"mine\"}").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsLong());
        assertEquals(43, call("list_playlists", "{\"source\":\"subscribed\"}").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsLong());
    }

    @Test void validatesArgumentsAndReturnsActionFailuresAsToolErrors() {
        assertThrows(IllegalArgumentException.class, () -> tools.call("set_volume", json("{\"volume\":2}")));
        assertThrows(IllegalArgumentException.class, () -> tools.call("get_queue", json("{\"offset\":1.1}")));
        assertThrows(IllegalArgumentException.class, () -> tools.call("play_song", json("{\"songId\":9223372036854775808}")));
        assertThrows(IllegalArgumentException.class, () -> tools.call("play_song", json("{\"songId\":\"123\"}")));
        assertThrows(IllegalArgumentException.class, () -> tools.call("get_settings", json("{\"unknown\":true}")));
        CloudMusic.likeList = null;
        assertTrue(tools.call("set_favorite", json("{\"liked\":false}")).get("isError").getAsBoolean());
        CloudMusic.profile = null;
        assertTrue(tools.call("get_cloud_storage", json("{}")).get("isError").getAsBoolean());
        assertTrue(call("get_playback_state", "{}").getAsJsonObject("song").get("liked").isJsonNull());
    }

    @Test void appliesVolumeModeTranslationAndIdempotentFavorites() {
        assertEquals(.37, call("set_volume", "{\"volume\":0.37}").get("volume").getAsDouble());
        call("set_translation", "{\"enabled\":false}");
        assertFalse(DesktopAppState.preferences().showTranslation().getValue());
        call("set_play_mode", "{\"mode\":\"LoopSingle\"}");
        assertEquals(CloudMusic.PlayMode.LoopSingle, CloudMusic.playMode);
        assertTrue(call("set_favorite", "{\"liked\":true}").get("liked").getAsBoolean());
        assertEquals(List.of(123L), CloudMusic.likeList);
    }

    @Test void dispatchesHttpToolCallsOntoDesktopThread() throws Exception {
        try (var server = new McpHttpServer(0, tools); var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(server.url())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"set_volume","arguments":{"volume":0.42}}}
                            """)).build();
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!response.isDone() && System.nanoTime() < deadline) {
                DesktopAppState.pumpMainThreadTasks();
                LockSupport.parkNanos(1_000_000);
            }
            assertTrue(response.isDone());
            assertEquals(200, response.join().statusCode());
            assertFalse(json(response.join().body()).getAsJsonObject("result").get("isError").getAsBoolean());
            assertEquals(.42, DesktopAppState.preferences().volume().getValue());
        }
    }

    @Test void pausesAndResumesTheExistingPlayer() {
        var player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", ProbePlayer.class);
        CloudMusic.player = player;
        assertEquals("PAUSED", call("control_playback", "{\"action\":\"pause\"}").get("status").getAsString());
        assertTrue(player.paused);
        assertEquals("PLAYING", call("control_playback", "{\"action\":\"play\"}").get("status").getAsString());
        assertFalse(player.paused);
    }

    @Test void downloadsUsingConfiguredDirectoryAndQualityWithoutStartingPlayback() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = "fLaC-mcp-download".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/audio", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            AtomicReference<Quality> requested = new AtomicReference<>();
            Music music = new Music("下载测试", null, null, 998877, List.of(), List.of(), null, 60000, 0, 0, List.of()) {
                @Override public Tuple<String, String> getDownloadUrl(Quality quality) {
                    requested.set(quality);
                    return Tuple.of("http://127.0.0.1:" + server.getAddress().getPort() + "/audio", "flac");
                }
            };
            CloudMusic.currentlyPlaying = music;
            DesktopAppState.preferences().downloadDirectory().setValue(directory.toString());
            DesktopAppState.preferences().downloadQuality().setValue("HiRes");
            JsonObject result = call("download_song", "{}");
            assertEquals("COMPLETE", result.get("state").getAsString());
            Path file = Path.of(result.get("file").getAsString());
            assertEquals(directory, file.getParent());
            assertArrayEquals(bytes, Files.readAllBytes(file));
            assertEquals(Quality.HIRES, requested.get());
            assertSame(music, CloudMusic.currentlyPlaying);
            assertNull(CloudMusic.player);
        } finally { server.stop(0); }
    }

    private static final class ProbePlayer extends AudioPlayer {
        private boolean paused;
        private ProbePlayer(java.io.File file) { super(file); }
        @Override public boolean isFinished() { return false; }
        @Override public boolean isPausing() { return paused; }
        @Override public float getTotalTimeMillis() { return 60000; }
        @Override public float getCurrentTimeMillis() { return 1000; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public void setVolume(float volume) {}
    }

    private JsonObject call(String name, String arguments) {
        JsonObject result = tools.call(name, json(arguments));
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        return result.getAsJsonObject("structuredContent");
    }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
}
