package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.PlaybackState;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class WorldLyricTimelineTest {
    @Test void lrcSplitsByEightyFivePercentSpanAndLeavesEnglishWordsTogether() {
        WorldLyricTimeline split = new WorldLyricTimeline(Arrays.asList(line(1000, "你好 世界"), line(5000, "hello world")), true);
        assertEquals(3, split.lines.size());
        assertEquals("你好", split.lines.get(0).text); assertEquals(1000, split.lines.get(0).startMs);
        assertEquals("世界", split.lines.get(1).text); assertEquals(2700, split.lines.get(1).startMs);
        assertEquals("hello world", split.lines.get(2).text);
        assertEquals(-1, split.indexAt(999)); assertEquals(0, split.indexAt(2699)); assertEquals(1, split.indexAt(2700));
    }

    @Test void yrcSplitsAtActualWordStartAndDoesNotInventWithinWordTimes() {
        PlaybackState.Line line = line(1000, "你好 世界");
        line.words.add(word(1000, 500, "你好 ")); line.words.add(word(3000, 800, "世界"));
        WorldLyricTimeline unsplit = new WorldLyricTimeline(Collections.singletonList(line), false);
        assertArrayEquals(new long[]{0, 0, 2000, 2000}, WorldLyricTimeline.characterTimes(unsplit.lines.get(0), 5000));
        WorldLyricTimeline split = new WorldLyricTimeline(Collections.singletonList(line), true);
        assertEquals(2, split.lines.size()); assertEquals(3000, split.lines.get(1).startMs);
        assertEquals(800, split.lines.get(1).words.get(0).durationMs);
        assertArrayEquals(new long[]{0, 0}, WorldLyricTimeline.characterTimes(split.lines.get(1), 5000));
    }

    @Test void shortOrRapidSplitFragmentsAreMerged() {
        WorldLyricTimeline timeline = new WorldLyricTimeline(Arrays.asList(line(1000, "你 好 世界"), line(1800, "下一句")), true);
        assertEquals(2, timeline.lines.size()); assertEquals("你 好 世界", timeline.lines.get(0).text);
    }

    @Test void estimatedTimesGroupCjkPairsAndWholeEnglishWords() {
        WorldLyricTimeline cjk = new WorldLyricTimeline(Collections.singletonList(line(1000, "你好世界")), false);
        assertArrayEquals(new long[]{0, 0, 330, 330}, WorldLyricTimeline.characterTimes(cjk.lines.get(0), 5000));
        WorldLyricTimeline english = new WorldLyricTimeline(Collections.singletonList(line(1000, "hello world")), false);
        assertArrayEquals(new long[]{0, 0, 0, 0, 0, 330, 330, 330, 330, 330}, WorldLyricTimeline.characterTimes(english.lines.get(0), 5000));
    }

    @Test void creditsAndPlaceholdersAreFilteredButBreakEventsClearTheLine() {
        PlaybackState.Line pause = line(3000, "•••"); pause.breakLine = true;
        PlaybackState.Line missing = line(0, "暂无歌词"); missing.placeholder = true;
        WorldLyricTimeline timeline = new WorldLyricTimeline(Arrays.asList(missing, line(0, "作词：Someone"), line(500, "纯音乐，请欣赏"), line(1000, "hello"), pause, line(4000, "last")), true);
        assertEquals(3, timeline.lines.size()); assertEquals("", timeline.lines.get(timeline.indexAt(3500)).text);
        assertEquals("last", timeline.lines.get(timeline.indexAt(999999)).text);
    }

    @Test void inputIsCopiedAndMissingOptionalFieldsAreSafe() {
        PlaybackState.Line line = line(0, "hello"); PlaybackState.Word word = word(0, 100, "hello"); line.words.add(word);
        WorldLyricTimeline timeline = new WorldLyricTimeline(Arrays.asList(null, line), false);
        word.text = "changed"; line.text = "changed"; line.words.clear();
        assertEquals("hello", timeline.lines.get(0).text); assertEquals("hello", timeline.lines.get(0).words.get(0).text);
        assertTrue(new WorldLyricTimeline(null, true).lines.isEmpty());
    }

    static PlaybackState.Line line(long timestamp, String text) {
        PlaybackState.Line line = new PlaybackState.Line(); line.timestamp = timestamp; line.text = text; return line;
    }
    static PlaybackState.Word word(long timestamp, long duration, String text) {
        PlaybackState.Word word = new PlaybackState.Word(); word.timestamp = timestamp; word.duration = duration; word.text = text; return word;
    }
}
