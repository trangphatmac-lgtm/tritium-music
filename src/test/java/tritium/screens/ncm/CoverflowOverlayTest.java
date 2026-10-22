package tritium.screens.ncm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.screens.ncm.navigation.NavigationContent;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoverflowOverlayTest {
    private final Gson gson = new Gson();
    @Test void tracksStayInDefaultOrderIncludingDuplicateSongsAndSharedAlbums() {
        PlayList playlist = gson.fromJson("{\"id\":1}", PlayList.class);
        Music first = gson.fromJson("{\"id\":3,\"name\":\"Hello\",\"al\":{\"id\":1},\"tns\":[\"你好\"]}", Music.class);
        Music second = gson.fromJson("{\"id\":2,\"name\":\"World\",\"al\":{\"id\":1}}", Music.class);
        playlist.musics = List.of(first,second,first);
        var content = NavigationContent.tracks(playlist);
        var entries = content.entries();
        assertEquals(List.of("Hello","World","Hello"),entries.stream().map(NavigationContent.Entry::title).toList());
        assertNotEquals(entries.get(0).key(),entries.get(2).key());
        assertTrue(entries.get(0).matches("HELLO"));
        assertTrue(entries.get(2).matches("你好"));
        assertSame(entries,content.entries(),"Unchanged data must not rebuild the scene each frame");
        playlist.musics = List.of(second,first);
        assertEquals(List.of("World","Hello"),content.entries().stream().map(NavigationContent.Entry::title).toList());
    }
    @Test void missingMetadataStillProducesBrowsableEntries() {
        PlayList playlist = gson.fromJson("{\"id\":1}", PlayList.class);
        playlist.musics=List.of(gson.fromJson("{\"id\":2}",Music.class));
        var entry=NavigationContent.tracks(playlist).entries().getFirst();
        assertFalse(entry.title().isBlank());
        assertTrue(entry.coverUrl().isEmpty());
        assertNotNull(entry.cover());
        assertTrue(entry.matches(""));
        assertFalse(entry.matches("missing"));
    }
    @Test void playlistCoversPreserveSourceOrderInsteadOfAlbumAggregation() {
        var a=gson.fromJson("{\"id\":9,\"name\":\"Last\"}",PlayList.class);
        var b=gson.fromJson("{\"id\":1,\"name\":\"First\"}",PlayList.class);
        var entries=NavigationContent.playlists("test",()->"test",()->List.of(a,b),()->false).entries();
        assertEquals(List.of("Last","First"),entries.stream().map(NavigationContent.Entry::title).toList());
        assertTrue(entries.stream().allMatch(NavigationContent.Entry::collection));
    }
}
