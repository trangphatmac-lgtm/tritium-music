package tritium.ncm.music.lyrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricMatchScoreTest {
    private LyricSong song(String title, String artist, String album, long duration) { return new LyricSong("1", title, artist, album, duration, "", ""); }
    @Test void weightsDurationAndIdentityMatchFolia() {
        var target = song("Song", "Artist", "Album", 200000);
        assertEquals(100, LyricMatchScore.score(target, target).value());
        assertEquals(95, LyricMatchScore.score(target, song("Song", "Artist", "Album", 203000)).value());
        assertEquals(75, LyricMatchScore.score(target, song("Song", "Artist", "Album", 205000)).value());
        assertFalse(LyricMatchScore.score(target, song("Song", "Artist", "Album", 206000)).reliable());
        assertFalse(LyricMatchScore.score(target, song("Song", "Stranger", "Another", 200000)).reliable());
        assertTrue(LyricMatchScore.score(target, song("Song", "Stranger", "Album", 200000)).reliable());
        assertFalse(LyricMatchScore.score(target, song("Other", "Artist", "Album", 200000)).reliable());
        assertEquals(90, LyricMatchScore.score(target, song("Song", "Artist", "Album", 0)).value());
        assertFalse(LyricMatchScore.score(target, song("Song", "Artist", "", 200000)).reliable());
    }
    @Test void scriptPunctuationAliasesAndArtistGroups() {
        assertEquals(LyricMatchScore.normalize("愛與夢"), LyricMatchScore.normalize("爱与梦"));
        assertEquals(LyricMatchScore.normalize("ひかり"), LyricMatchScore.normalize("ヒカリ"));
        assertEquals(100, LyricMatchScore.score(song("Song (歌曲)", "Artist", "", 200000), song("song", "Artist", "", 200000)).value());
        assertTrue(LyricMatchScore.score(song("Song", "Artist", "", 200000), song("Song", "Artist, Guest", "", 200000)).reliable());
        assertTrue(LyricMatchScore.score(song("Song", "Artist", "Album", 200000), song("Song (Live)", "Artist", "Album", 200000)).value() < 100);
        assertEquals("Song - Artist - Album", LyricMatchScore.query(song("Artist - Song", "Artist", "Album", 200000)));
    }
    @Test void preservesEachSourceOnce() {
        assertEquals(java.util.List.of(LyricSource.NETEASE, LyricSource.AMLL, LyricSource.QQ, LyricSource.KUGOU), LyricSource.order(null));
        for (var source : LyricSource.values()) assertEquals(4, new java.util.HashSet<>(LyricSource.order(source)).size());
    }
}
