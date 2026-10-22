package tritium.screens.ncm.navigation;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPlaybackTest {
    @Test void filteredTrackActivatesItsOriginalQueuePositionIncludingDuplicates() {
        var gson = new Gson();
        PlayList playlist = gson.fromJson("{\"id\":1}", PlayList.class);
        Music first = gson.fromJson("{\"id\":9,\"name\":\"First\",\"al\":{\"id\":1}}", Music.class);
        Music second = gson.fromJson("{\"id\":2,\"name\":\"Second\",\"al\":{\"id\":1}}", Music.class);
        playlist.musics = List.of(first,second,first);
        var queue = new AtomicReference<List<Music>>();
        var index = new AtomicInteger(-1);
        var content = NavigationContent.tracks(playlist, (songs,start) -> { queue.set(songs); index.set(start); });
        var filtered = content.entries().stream().filter(entry -> entry.matches("Second")).toList();
        assertEquals(1,filtered.size());
        filtered.getFirst().activate().run();
        assertEquals(List.of(first,second,first),queue.get());
        assertEquals(1,index.get());
        content.entries().get(2).activate().run();
        assertEquals(2,index.get());
        assertEquals(List.of(first,second,first),playlist.musics);
    }
}
