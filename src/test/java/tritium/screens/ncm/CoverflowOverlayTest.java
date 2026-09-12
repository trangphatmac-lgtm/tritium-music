package tritium.screens.ncm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Album;
import tritium.ncm.music.dto.Music;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoverflowOverlayTest {
    private final Gson gson = new Gson();

    @Test
    void missingAndBlankMetadataHaveRenderableNames() {
        for (String value : List.of("null", "\"\"", "\"  \"")) {
            Album album = gson.fromJson("{\"id\":1,\"name\":" + value + "}", Album.class);
            Music music = gson.fromJson("{\"id\":2,\"name\":" + value + "}", Music.class);
            assertEquals("未知专辑", CoverflowOverlay.albumName(album));
            assertEquals("未知歌曲", CoverflowOverlay.musicName(music));
        }
        assertEquals("Album", CoverflowOverlay.albumName(
                gson.fromJson("{\"name\":\"Album\"}", Album.class)));
        assertEquals("Song", CoverflowOverlay.musicName(
                gson.fromJson("{\"name\":\"Song\"}", Music.class)));
    }

    @Test
    void filteringIncompleteMetadataDeduplicatesAlbumsAndResetsSelection() {
        CoverflowOverlay screen = new CoverflowOverlay();
        Album album = gson.fromJson("{\"id\":1}", Album.class);
        Music unnamed = gson.fromJson("{\"id\":2}", Music.class);
        Music song = gson.fromJson("{\"id\":3,\"name\":\"Hello\",\"tns\":[\"你好\"]}", Music.class);
        screen.albumList.put(album, List.of(unnamed, song, song));
        screen.index = 12;
        screen.scrollOffset = 864;

        screen.filterAlbums("HELLO");
        assertEquals(List.of(album), screen.renderList);
        assertEquals(0, screen.index);
        assertEquals(0, screen.scrollOffset);
        screen.filterAlbums("你好");
        assertEquals(List.of(album), screen.renderList);
        screen.filterAlbums("未知专辑");
        assertEquals(List.of(album), screen.renderList);
        screen.filterAlbums("missing");
        assertTrue(screen.renderList.isEmpty());
        screen.filterAlbums("");
        assertEquals(List.of(album), screen.renderList);
    }
}
