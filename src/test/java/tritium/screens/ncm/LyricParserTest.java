package tritium.screens.ncm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricParserTest {
    @Test
    void parsesPlainLyricsWithTranslationAndRomanization() {
        JsonObject input = lyricPayload(
                "[00:01.00]Hello\n[00:02.50]World",
                "[00:01.00]你好",
                "[00:01.00]ha ro"
        );

        List<LyricLine> lines = LyricParser.parse(input);

        assertEquals(2, lines.size());
        assertEquals(1000, lines.get(0).getTimestamp());
        assertEquals("Hello", lines.get(0).getLyric());
        assertEquals("你好", lines.get(0).getTranslationText());
        assertEquals("ha ro", lines.get(0).getRomanizationText());
        assertEquals(2500, lines.get(1).getTimestamp());
        assertEquals("World", lines.get(1).getLyric());
    }

    @Test
    void parsesYrcPerWordLyricsAndSecondaryLyrics() {
        JsonObject input = lyricPayload("[00:01.00]fallback", "", "");
        input.add("yrc", objectWithLyric("[1000,2000](1000,500,0)Hel(1500,500,0)lo"));
        input.add("ytlrc", objectWithLyric("[00:01.00]你好"));
        input.add("yromalrc", objectWithLyric("[00:01.00]ni hao"));

        List<LyricLine> lines = LyricParser.parse(input);

        assertEquals(1, lines.size());
        LyricLine line = lines.getFirst();
        assertEquals(1000, line.getTimestamp());
        assertEquals(2000, line.duration);
        assertEquals("Hello", line.getLyric());
        assertEquals(2, line.words.size());
        assertEquals("Hel", line.words.get(0).word);
        assertEquals(1000, line.words.get(0).timestamp);
        assertEquals(500, line.words.get(0).duration);
        assertEquals("你好", line.getTranslationText());
        assertEquals("ni hao", line.getRomanizationText());
    }

    @Test
    void returnsEmptyListForUnavailableLyrics() {
        JsonObject input = new JsonObject();
        input.addProperty("uncollected", true);

        assertTrue(LyricParser.parse(input).isEmpty());
        assertTrue(LyricParser.parse(new JsonObject()).isEmpty());
    }

    private static JsonObject lyricPayload(String lyric, String translation, String romanization) {
        JsonObject input = new JsonObject();
        input.add("lrc", objectWithLyric(lyric));
        input.add("tlyric", objectWithLyric(translation));
        input.add("romalrc", objectWithLyric(romanization));
        return input;
    }

    private static JsonObject objectWithLyric(String lyric) {
        JsonObject object = new JsonObject();
        object.addProperty("lyric", lyric);
        return object;
    }
}
