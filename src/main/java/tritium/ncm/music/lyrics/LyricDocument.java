package tritium.ncm.music.lyrics;

import tritium.screens.ncm.LyricLine;

import java.util.List;

/** Immutable cached lyrics; each publication receives fresh animation/rendering state. */
public record LyricDocument(List<Line> lines, boolean pureMusic) {
    public static final LyricDocument EMPTY = new LyricDocument(List.of(), false);

    public LyricDocument { lines = List.copyOf(lines); }
    public record Word(String text, long start, long duration) {}
    public record Line(long start, long duration, String text, String translation, String romanization, List<Word> words) {
        public Line { words = List.copyOf(words); }
    }

    public boolean usable() { return lines.stream().anyMatch(line -> !line.text().isBlank()); }
    public boolean wordTimed() { return lines.stream().anyMatch(line -> !line.words().isEmpty()); }

    public static LyricDocument fromLines(List<LyricLine> lines, boolean pureMusic) {
        return new LyricDocument(lines.stream().filter(line -> !line.lyric.isBlank()).map(line ->
                new Line(line.timestamp, line.duration, line.lyric, line.translationText, line.romanizationText,
                        line.words.stream().map(word -> new Word(word.word, word.timestamp, word.duration)).toList())).toList(), pureMusic);
    }

    public List<LyricLine> toLines() {
        return lines.stream().map(line -> {
            var result = new LyricLine(line.start(), line.text());
            result.duration = line.duration();
            result.translationText = line.translation();
            result.romanizationText = line.romanization();
            // The desktop word renderer divides by duration; zero-length punctuation must
            // remain visible without introducing NaN into its animation state.
            line.words().forEach(word -> result.words.add(new LyricLine.Word(word.text(), word.start(), Math.max(1, word.duration()))));
            return result;
        }).toList();
    }
}
