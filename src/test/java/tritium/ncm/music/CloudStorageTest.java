package tritium.ncm.music;

import org.junit.jupiter.api.Test;
import tritium.ncm.RequestUtil;
import tritium.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudStorageTest {
    @Test
    void loadsEveryPageInOrderAndDeduplicatesOverlappingEntries() {
        List<Integer> offsets = new ArrayList<>();
        var songs = CloudStorage.loadAll((limit, offset) -> {
            assertEquals(200, limit);
            offsets.add(offset);
            return response(offset == 0
                    ? "{\"code\":200,\"hasMore\":true,\"data\":[{\"songId\":1},{\"songId\":2}]}"
                    : "{\"code\":200,\"hasMore\":false,\"data\":[{\"songId\":2},{\"songId\":3}]}");
        });
        assertEquals(List.of(0, 2), offsets);
        assertEquals(List.of(1L, 2L, 3L), songs.stream().map(m -> m.getId()).toList());
    }

    @Test
    void supportsTotalCountWhenHasMoreIsMissing() {
        var songs = CloudStorage.loadAll((limit, offset) -> response(
                "{\"code\":200,\"count\":2,\"data\":[{\"songId\":" + (offset + 1) + "}]}"));
        assertEquals(2, songs.size());
    }

    @Test
    void privateUploadsWithoutCatalogMetadataRemainRenderableAndPlayable() {
        var song = CloudStorage.toMusic(JsonUtils.toJsonObject("""
                {"songId":1234567890123,"fileName":"Flux.m4a","artist":"Flux","album":"Uploads","simpleSong":null}
                """));
        assertEquals(1234567890123L, song.getId());
        assertEquals("Flux.m4a", song.getName());
        assertEquals("Flux", song.getArtistsName());
        assertEquals("Uploads", song.getAlbum().getName());
        assertEquals("", song.getCoverUrl(64));
        assertEquals("", song.getTranslatedNames());
    }

    @Test
    void keepsCloudIdAndCatalogMetadataIncludingDurationAndCover() {
        var song = CloudStorage.toMusic(JsonUtils.toJsonObject("""
                {"songId":42,"simpleSong":{"id":99,"name":"Title","dt":245000,
                "ar":[null,{"name":null},{"id":1,"name":"Artist"}],
                "al":{"id":2,"name":"Album","picUrl":"https://example.com/cover.jpg"},"tns":["标题"]}}
                """));
        assertEquals(42, song.getId());
        assertEquals("Title", song.getName());
        assertEquals("Artist", song.getArtistsName());
        assertEquals(245000, song.getDuration());
        assertEquals("标题", song.getTranslatedNames());
        assertEquals("https://example.com/cover.jpg?param=64y64", song.getCoverUrl(64));
    }

    @Test
    void blankTagsHaveSafeFallbacks() {
        var song = CloudStorage.toMusic(JsonUtils.toJsonObject("""
                {"simpleSong":{"id":10,"name":"  ","ar":[],"al":null},"artist":null,"album":""}
                """));
        assertEquals("未知歌曲", song.getName());
        assertEquals("未知歌手", song.getArtistsName());
        assertEquals("未知专辑", song.getAlbum().getName());
    }

    @Test
    void emptyLibraryIsDistinctFromLoginAndRequestFailures() {
        assertTrue(CloudStorage.loadAll((l, o) -> response("{\"code\":200,\"data\":[],\"hasMore\":false}")).isEmpty());
        assertTrue(assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) ->
                response("{\"code\":301}"))).getMessage().contains("登录"));
        assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) -> response("{\"code\":500}")));
        assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) -> response("{\"code\":200}")));
    }

    @Test
    void brokenPaginationFailsInsteadOfLoopingOrPublishingPartialLibrary() {
        assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) ->
                response("{\"code\":200,\"hasMore\":true,\"data\":[]}")));
        assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) ->
                response("{\"code\":200,\"hasMore\":true,\"data\":[{\"songId\":1}]}")));
        assertThrows(IllegalStateException.class, () -> CloudStorage.loadAll((l, o) ->
                response(o == 0 ? "{\"code\":200,\"hasMore\":true,\"data\":[{\"songId\":1}]}" : "{\"code\":500}")));
    }

    private RequestUtil.RequestAnswer response(String json) {
        return RequestUtil.RequestAnswer.of(JsonUtils.toJsonObject(json), 200, null);
    }
}
