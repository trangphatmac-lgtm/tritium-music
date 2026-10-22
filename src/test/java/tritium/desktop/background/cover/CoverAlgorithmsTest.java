package tritium.desktop.background.cover;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;

import static org.junit.jupiter.api.Assertions.*;

class CoverAlgorithmsTest {
    @Test void pageArtworkCannotOverrideThePlayingSongInLyrics() {
        var gson = new Gson();
        PlayList playlist = gson.fromJson("{\"id\":81}", PlayList.class);
        Music song = gson.fromJson("{\"id\":27}", Music.class);
        for (String mode : new String[]{"solid", "blur", "nomand", "monet"}) {
            assertEquals(playlist.getCoverLocation(), CoverArtwork.source(playlist, song, false, mode));
            assertEquals(song.getCoverLocation(), CoverArtwork.source(playlist, song, true, mode));
            assertNull(CoverArtwork.source(playlist, null, true, mode));
        }
        assertNull(CoverArtwork.source(null, song, false, "solid"));
        assertEquals(song.getCoverLocation(), CoverArtwork.source(null, song, false, "monet"));
        playlist.setSearchMode(true);
        assertNull(CoverArtwork.source(playlist, song, false, "solid"));
    }

    @Test void driftLoopsSeamlesslyAndNeverUncoversAnEdge() {
        assertArrayEquals(MonetDrift.at(0), MonetDrift.at(240), 1e-12);
        assertArrayEquals(MonetDrift.at(.001), MonetDrift.at(240.001), 1e-12);
        for (int i = 0; i <= 1000; i++) {
            double[] frame = MonetDrift.at(i * .24);
            assertTrue(Math.abs(frame[0]) <= .01700001 && Math.abs(frame[1]) <= .01700001);
            assertTrue(frame[2] >= 1 + 2 * Math.max(Math.abs(frame[0]), Math.abs(frame[1])) + .00999);
        }
    }

    @Test void monetWashKeepsShadowsAndHighlightsOrderedInBothThemes() {
        for (int background : new int[]{0xFF1E1E1E, 0xFFFFFFFF}) {
            int primary = background == 0xFFFFFFFF ? 0xFF000000 : 0xFFFFFFFF;
            int black = MonetPaint.treat(0xFF000000, background, 0xFFB0C080, primary);
            int white = MonetPaint.treat(0xFFFFFFFF, background, 0xFFB0C080, primary);
            for (int shift : new int[]{0,8,16}) assertTrue(((black >> shift) & 255) < ((white >> shift) & 255));
        }
        // Folia's default 34% wash keeps 66% of an uncolored midtone.
        assertEquals(0xFF545454, MonetPaint.treat(0xFF808080, 0xFF000000, 0xFF000000, 0xFFFFFFFF));
    }
}
