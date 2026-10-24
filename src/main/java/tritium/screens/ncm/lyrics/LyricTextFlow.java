package tritium.screens.ncm.lyrics;

import com.ibm.icu.text.BreakIterator;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Unicode line breaks shared by the native scenes. Ranges retain the original glyph timing. */
final class LyricTextFlow {
    record Row(int from, int to) { }

    static boolean[] breaks(List<String> glyphs) {
        var iterator = BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(String.join("", glyphs));
        boolean[] result = new boolean[glyphs.size() + 1];
        int offset = 0;
        result[0] = true;
        for (int i = 0; i < glyphs.size(); i++) {
            offset += glyphs.get(i).length();
            result[i + 1] = iterator.isBoundary(offset);
        }
        return result;
    }

    static List<Row> wrap(List<String> glyphs, ToDoubleFunction<String> measure, double width) {
        boolean[] breaks = breaks(glyphs);
        var rows = new ArrayList<Row>();
        for (int start = 0; start < glyphs.size();) {
            while (start < glyphs.size() && glyphs.get(start).isBlank()) start++;
            if (start == glyphs.size()) break;
            double used = 0;
            int end = start, lastBreak = start;
            while (end < glyphs.size()) {
                String glyph = glyphs.get(end);
                if (glyph.contains("\n") || glyph.contains("\r")) break;
                double next = measure.applyAsDouble(glyph);
                if (end > start && used + next > width) {
                    if (glyph.isBlank()) end++;
                    else if (lastBreak > start) end = lastBreak;
                    break;
                }
                used += next;
                end++;
                if (breaks[end]) lastBreak = end;
            }
            if (end == start) end++;
            int visibleEnd = end;
            while (visibleEnd > start && glyphs.get(visibleEnd - 1).isBlank()) visibleEnd--;
            if (visibleEnd > start) rows.add(new Row(start, visibleEnd));
            start = end;
        }
        return List.copyOf(rows);
    }

    static int balancedBreak(List<String> glyphs, double[] advances) {
        boolean[] breaks = breaks(glyphs);
        double target = Arrays.stream(advances).sum() / 2, used = 0, best = Double.MAX_VALUE;
        int cut = glyphs.size();
        for (int i = 1; i < glyphs.size(); i++) {
            used += advances[i - 1];
            if (breaks[i] && Math.abs(used - target) < best) { best = Math.abs(used - target); cut = i; }
        }
        // One long word stays intact; the scene can scale it to its available space.
        return cut;
    }
}
