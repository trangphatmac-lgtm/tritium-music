// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import tritium.screens.ncm.LyricLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Immutable Folia-style seconds/grapheme model. Never mutates the Normal page or HUD's lyric state. */
public final class LyricTimeline {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    public record Glyph(String text, double start, double end) {
        public double progress(double time) { return LyricMath.clamp((time - start) / Math.max(.001, end - start)); }
    }
    public record Line(String text, String translation, String romanization, double start, double end,
                       List<Glyph> glyphs, boolean timedWords, int sourceIndex) {
        public double progress(double time) { return LyricMath.clamp((time - start) / Math.max(.001, end - start)); }
        public double characterProgress(double time) {
            for (int i = 0; i < glyphs.size(); i++) {
                Glyph g = glyphs.get(i);
                if (time < g.end) return i + g.progress(time);
            }
            return glyphs.size();
        }
    }
    private final List<Line> lines;
    public LyricTimeline(List<LyricLine> source, double duration) {
        var result = new ArrayList<Line>();
        for (int i = 0; i < source.size(); i++) {
            LyricLine line = source.get(i);
            // Tritium's break/placeholder records are timing markers, not sung text.
            if (line.isBreakLine || line.lyric == null || line.lyric.isBlank()
                    || line.lyric.codePoints().noneMatch(Character::isLetterOrDigit)) continue;
            double start = Math.max(0, line.timestamp / 1000.0);
            double next = i + 1 < source.size() ? source.get(i + 1).timestamp / 1000.0 : duration;
            double wordEnd = line.words.stream().mapToDouble(w -> (w.timestamp + w.duration) / 1000.0).max().orElse(start);
            double end = line.duration > 0 ? start + line.duration / 1000.0
                    : wordEnd > start ? wordEnd : next > start ? next : start + 5;
            end = Math.max(start + .05, end);
            var glyphs = new ArrayList<Glyph>();
            boolean timed = !line.words.isEmpty();
            if (timed) {
                int cursor = 0;
                double previousEnd = start;
                for (LyricLine.Word word : line.words) {
                    if (word.word == null || word.word.isEmpty()) continue;
                    int found = line.lyric.indexOf(word.word, cursor);
                    if (found < 0) { timed = false; break; }
                    double wordStart = Math.max(start, word.timestamp / 1000.0);
                    if (found > cursor) append(glyphs, line.lyric.substring(cursor, found), previousEnd, Math.max(previousEnd, wordStart));
                    previousEnd = Math.max(wordStart + .001, (word.timestamp + Math.max(1, word.duration)) / 1000.0);
                    append(glyphs, word.word, wordStart, previousEnd);
                    cursor = found + word.word.length();
                }
                if (timed && cursor < line.lyric.length()) append(glyphs, line.lyric.substring(cursor), previousEnd, Math.max(previousEnd, end));
                if (!timed) { glyphs.clear(); append(glyphs, line.lyric, start, end); }
            } else append(glyphs, line.lyric, start, end);
            result.add(new Line(line.lyric, line.translationText == null ? "" : line.translationText,
                    line.romanizationText == null ? "" : line.romanizationText, start, end,
                    List.copyOf(glyphs), timed, i));
        }
        result.sort(Comparator.comparingDouble(Line::start));
        lines = List.copyOf(result);
    }
    private static void append(List<Glyph> into, String text, double start, double end) {
        var parts = split(text);
        for (int i = 0; i < parts.size(); i++) into.add(new Glyph(parts.get(i),
                LyricMath.mix(start, end, (double) i / parts.size()),
                LyricMath.mix(start, end, (double) (i + 1) / parts.size())));
    }
    public static List<String> split(String text) {
        return GRAPHEME.matcher(text == null ? "" : text).results().map(java.util.regex.MatchResult::group).toList();
    }
    public List<Line> lines() { return lines; }
    /** Most recent started line, including gaps (scenes independently fade completed text). */
    public int indexAt(double seconds) {
        int low = 0, high = lines.size();
        while (low < high) { int middle = (low + high) >>> 1; if (lines.get(middle).start <= seconds) low = middle + 1; else high = middle; }
        return low - 1;
    }
    public Line line(int index) { return index < 0 || index >= lines.size() ? null : lines.get(index); }
}
