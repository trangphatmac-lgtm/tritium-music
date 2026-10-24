package tritium.screens.ncm.lyrics;

import org.junit.jupiter.api.Test;
import tritium.screens.ncm.LyricLine;
import java.util.*;
import java.util.function.ToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;

class LyricLayoutTest {
    private static LyricTimeline.Line line(String text) {
        var source = new LyricLine(1000, text); source.duration = 6000;
        return new LyricTimeline(List.of(source), 10).line(0);
    }

    @Test void englishWrapKeepsWordsPunctuationAndOriginalGlyphOffsets() {
        var line = line("I'll stay, and we'll find our way.");
        var glyphs = line.glyphs().stream().map(LyricTimeline.Glyph::text).toList();
        var rows = LyricTextFlow.wrap(glyphs, ch -> 1, 10);
        var text = rows.stream().map(row -> String.join("", glyphs.subList(row.from(), row.to()))).toList();
        assertEquals(List.of("I'll stay,", "and we'll", "find our", "way."), text);
        assertEquals('a', line.text().charAt(rows.get(1).from()));
        assertEquals(11, rows.get(1).from(), "Skipped wrap spaces must not shift the timed glyph index");
    }

    @Test void punctuationStaysAttachedToTheWordInKineticLayouts() {
        var source = line("I'll stay, and we'll go!");
        var segments = LyricWords.split(source);
        assertEquals(List.of("I'll", " ", "stay,", " ", "and", " ", "we'll", " ", "go!"),
                segments.stream().map(LyricWords.Segment::text).toList());
        assertEquals(source.glyphs(), segments.stream().flatMap(segment -> segment.glyphs().stream()).toList());
    }

    @Test void longWordsGraphemesAndExplicitLineBreaksStillFit() {
        var glyphs = LyricTimeline.split("Á👩‍🚀\nextraordinary\r\n世界欢迎你");
        var rows = LyricTextFlow.wrap(glyphs, ch -> 1, 5);
        assertEquals(List.of("Á👩‍🚀", "extra", "ordin", "ary", "世界欢迎你"),
                rows.stream().map(row -> String.join("", glyphs.subList(row.from(), row.to()))).toList());
        assertTrue(rows.stream().allMatch(row -> row.to() - row.from() <= 5));
    }

    @Test void narrowLatinLettersReserveTheirCircularBackingsAndSpacesStayEmpty() {
        var source = line("All I know");
        var type = FoliaData.data("hanabi.json").getAsJsonArray().get(7).getAsJsonObject().getAsJsonObject("text");
        ToDoubleFunction<String> measure = ch -> "Il ".contains(ch) ? .23 : .65;
        var layout = HanabiLayout.place(source, type, 70, 1100, 350, 1, measure);
        assertEquals(8, layout.letters().size());
        for (int i = 1; i < layout.letters().size(); i++) {
            double distance = layout.letters().get(i).x() - layout.letters().get(i - 1).x();
            assertTrue(distance >= layout.size() * 1.4, "Circle diameters need room even around I and l");
        }
    }

    @Test void everyHanabiProfileFitsShortAndLongEnglishInsideItsRegion() {
        for (var item : FoliaData.data("hanabi.json").getAsJsonArray()) {
            var p = item.getAsJsonObject(); var type = p.getAsJsonObject("text");
            for (String text : List.of("All I know", "When the city lights are fading, we will follow every echo through the rain")) {
                for (double width : new double[]{900, 300}) {
                    var layout = HanabiLayout.place(line(text), type, 90, width, 270, 17,
                            ch -> ch.isBlank() ? .3 : "Ilit".contains(ch) ? .25 : .65);
                    assertTrue(layout.width() <= width + .001);
                    assertTrue(layout.height() <= 270.001);
                    assertTrue(Double.isFinite(layout.size()) && layout.size() > 0);
                    assertEquals(text.chars().filter(ch -> ch != ' ').count(), layout.letters().size());
                }
            }
        }
    }

    @Test void englishChunkAndBalancedLayoutsBreakBetweenWords() {
        var line = line("All I know");
        assertEquals(List.of("All", "I", "know"), PartitaScene.layout(line, 650).stream()
                .map(chunk -> chunk.glyphs().stream().map(LyricTimeline.Glyph::text).reduce("", String::concat)).toList());
        var glyphs = LyricTimeline.split("Keep dreaming");
        double[] sizes = new double[glyphs.size()]; Arrays.fill(sizes, 1);
        assertEquals(5, LyricTextFlow.balancedBreak(glyphs, sizes));
        var word = LyricTimeline.split("dreaming");
        assertEquals(word.size(), LyricTextFlow.balancedBreak(word, new double[word.size()]));
    }
}
