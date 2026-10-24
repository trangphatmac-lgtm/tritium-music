// SPDX-License-Identifier: AGPL-3.0-only
// Folia partitaLayout.ts / PartitaChunk.tsx: uneven chunks, alternate stagger and illuminated guides.
package tritium.screens.ncm.lyrics;

import java.util.ArrayList;
import java.util.List;
import static tritium.screens.ncm.lyrics.LyricMath.*;

final class PartitaScene implements LyricScene {
    record Chunk(List<LyricTimeline.Glyph> glyphs, double x, double scale, int order) { }
    private LyricTimeline.Line held;
    private double layoutHeight;
    private List<Chunk> chunks = List.of();
    @Override public void reset() { held = null; }
    @Override public void render(LyricCanvas c, LyricFrame f) {
        SceneBackdrop.ambient(c, f, 0xFF11121C);
        var line = f.current();
        if (line == null) return;
        if (held != line || layoutHeight != f.contentHeight()) { held = line; layoutHeight = f.contentHeight(); chunks = layout(line, layoutHeight); }
        double fade = 1 - smooth((f.time() - line.end() - .5) / .8);
        double floatY = Math.sin(f.time() * Math.PI * 2 / 7) * 14;
        double rowHeight = Math.min(92, (f.contentHeight() - 170) / Math.max(1, chunks.size()));
        for (Chunk chunk : chunks) {
            double start = chunk.glyphs.getFirst().start(), end = chunk.glyphs.getLast().end();
            double entry = out((f.time() - start + .12) / .4), exit = smooth((f.time() - end) / .6);
            if (entry <= 0) continue;
            String text = chunk.glyphs.stream().map(LyricTimeline.Glyph::text).reduce("", String::concat);
            double size = Math.min(64, (f.width() - 160) / Math.max(1, c.width(text, 1, true)));
            double rowWidth = c.width(text, size, true), stagger = chunk.x * Math.min(1, f.width() / 1000);
            double cx = f.width() / 2 + stagger + (chunk.order % 2 == 0 ? -40 : 40) * (1 - entry);
            double cy = f.contentHeight() * .43 + (chunk.order - (chunks.size() - 1) / 2.0) * rowHeight + floatY;
            c.push(cx, cy, mix(.85, 1, entry), 0);
            int guide = alpha(exit > .9 ? 0xFFCCCCDD : f.accent(), entry * fade * (1 - exit * .72));
            double gx = (chunk.order % 2 == 0 ? -1 : 1) * (rowWidth / 2 + 12);
            c.line(gx, 12, gx, 44, 1, guide);
            c.line(-rowWidth / 2 - 18, 36, -rowWidth / 2 - 18 + (rowWidth + 36) * entry, 36, 1, guide);
            double x = -rowWidth / 2;
            for (int i = 0; i < chunk.glyphs.size(); i++) {
                var glyph = chunk.glyphs.get(i); double p = glyph.progress(f.time());
                double pop = Math.sin(Math.PI * p), seed = start + i * 7.13;
                double dy = (random(seed + 2) - .5) * 12 * pop - pop * 8;
                c.push(x, -size * .55 + dy, 1 + pop * .06 * chunk.scale, (random(seed + 3) - .5) * 6 * pop);
                c.glowText(glyph.text(), 0, 0, size, alpha(color(0xFF6E7080, f.accent(), p), entry * fade), 20 * pop, true);
                c.pop(); x += c.width(glyph.text(), size, true);
            }
            c.pop();
        }
    }
    static List<Chunk> layout(LyricTimeline.Line line, double height) {
        var glyphs = line.glyphs();
        boolean[] breaks = LyricTextFlow.breaks(glyphs.stream().map(LyricTimeline.Glyph::text).toList());
        var ends = new ArrayList<Integer>();
        for (int i = 1; i <= glyphs.size(); i++) if (breaks[i]) ends.add(i);
        int count = Math.max(1, Math.min(ends.size(), (int) (height * .65 / 100)));
        var chunks = new ArrayList<Chunk>(); double seed = line.start(); int cursor = 0, unit = 0;
        for (int i = 0; i < count; i++) {
            int remaining = ends.size() - unit, groups = count - i; double avg = remaining / (double) groups;
            int length = i == count - 1 ? remaining : Math.max(1, Math.min(remaining - groups + 1,
                    (int) Math.round(avg + (random(seed++) - .5) * avg)));
            int end = ends.get(unit + length - 1), visibleEnd = end;
            while (cursor < visibleEnd && glyphs.get(cursor).text().isBlank()) cursor++;
            while (visibleEnd > cursor && glyphs.get(visibleEnd - 1).text().isBlank()) visibleEnd--;
            double stagger = (i % 2 == 0 ? -1 : 1) * (24 + random(seed++) * 56);
            double scale = .8 + random(seed++) * .9;
            if (visibleEnd > cursor) chunks.add(new Chunk(glyphs.subList(cursor, visibleEnd), stagger, scale, chunks.size()));
            cursor = end; unit += length;
        }
        return List.copyOf(chunks);
    }
}
