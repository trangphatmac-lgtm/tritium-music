package tritium.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.ncm.music.CloudMusic.PlayMode;
import tritium.ncm.music.PlaybackSession;
import tritium.ncm.music.dto.Music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopPlaybackStoreTest {
    @TempDir Path directory;

    @Test void roundTripPreservesMetadataDuplicateTracksQueueOrderAndResumePoint() throws Exception {
        Path file = directory.resolve("data/playback.json");
        DesktopPlaybackStore store = new DesktopPlaybackStore(file);
        var session = new PlaybackSession(List.of(song(1), song(2), song(1)), 2, 42_123, true,
                PlayMode.Random, List.of(song(8), song(9)));
        store.save(session);
        PlaybackSession loaded = store.load();
        assertEquals(session, loaded);
        assertEquals("测试歌曲", loaded.song().getName());
        assertEquals("歌手", loaded.song().getArtistsName());
        assertEquals("专辑", loaded.song().getAlbum().getName());
        assertEquals("https://example.com/cover?param=320y320", loaded.song().getCoverUrl(320));
        store.save(new PlaybackSession(List.of(song(3)), 0, 2_000, false, PlayMode.LoopSingle, List.of()));
        assertEquals(3, store.load().song().getId());
        assertFalse(store.load().paused());
        try (var files = Files.list(file.getParent())) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void missingOrCorruptStateDoesNotPreventStartup() throws Exception {
        Path file = directory.resolve("playback.json");
        DesktopPlaybackStore store = new DesktopPlaybackStore(file);
        assertNull(store.load());
        for (String invalid : List.of("", "{broken", "[]", "null", "{}", "{\"version\":99}")) {
            Files.writeString(file, invalid);
            assertNull(store.load(), invalid);
        }
        store.save(new PlaybackSession(List.of(song(1)), 0, 0, false, PlayMode.Sequential, List.of()));
        String valid = Files.readString(file);
        for (String property : List.of("index", "songs", "positionMillis", "paused")) {
            JsonObject json = new JsonParser().parse(valid).getAsJsonObject();
            json.remove(property);
            Files.writeString(file, json.toString());
            assertNull(store.load(), property);
        }
        JsonObject json = new JsonParser().parse(valid).getAsJsonObject();
        json.addProperty("index", 99);
        Files.writeString(file, json.toString());
        assertNull(store.load());
        json.addProperty("index", 0);
        json.getAsJsonArray("songs").get(0).getAsJsonObject().remove("ar");
        Files.writeString(file, json.toString());
        assertNull(store.load());
    }

    @Test void earlyExitPreservesPreviousSessionAndUnknownModeFallsBack() throws Exception {
        Path file = directory.resolve("playback.json");
        DesktopPlaybackStore store = new DesktopPlaybackStore(file);
        var session = new PlaybackSession(List.of(song(1)), 0, 10_000, false, PlayMode.Sequential, List.of());
        store.save(session);
        store.save(null);
        assertEquals(session, store.load());
        JsonObject json = new JsonParser().parse(Files.readString(file)).getAsJsonObject();
        json.addProperty("mode", "FutureMode");
        json.addProperty("positionMillis", -100);
        Files.writeString(file, json.toString());
        assertEquals(PlayMode.Sequential, store.load().mode());
        assertEquals(0, store.load().positionMillis());
        json.addProperty("mode", "Heartbeat");
        json.addProperty("positionMillis", Long.MAX_VALUE);
        Files.writeString(file, json.toString());
        assertEquals(PlayMode.Sequential, store.load().mode());
        assertEquals(59_999, store.load().positionMillis());
    }

    private static Music song(long id) {
        return new Gson().fromJson("""
                {"id":%d,"name":"测试歌曲","dt":60000,"ar":[{"id":10,"name":"歌手"}],
                 "al":{"id":20,"name":"专辑","picUrl":"https://example.com/cover"}}
                """.formatted(id), Music.class);
    }
}
