// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.ToDoubleFunction;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Block-aware em layout. Like Folia, blanks only advance and Latin words remain intact. */
final class HanabiLayout {
    record Letter(int index, double x, double y, double width, double rotation) { }
    record Band(double x, double y, double length, double angle) { }
    record Placement(double size, double width, double height, List<Letter> letters, List<Band> bands) { }

    static Placement place(LyricTimeline.Line line, JsonObject type, double font,
                           double width, double height, long seed, ToDoubleFunction<String> measure) {
        // Reflow before shrinking: fitting a tall pile of oversized rows makes English illegible.
        var widths = new HashMap<String, Double>();
        for (var glyph : line.glyphs()) widths.computeIfAbsent(glyph.text(), measure::applyAsDouble);
        Placement best = null;
        for (double trial = font; trial >= font * .1; trial *= .86) {
            var candidate = layout(line, type, trial, width, height, seed, widths::get);
            if (best == null || candidate.size() > best.size()) best = candidate;
            if (candidate.size() >= trial * .999) break;
        }
        return best;
    }

    private static Placement layout(LyricTimeline.Line line, JsonObject type, double font,
                                    double width, double height, long seed, ToDoubleFunction<String> measure) {
        String layout = s(type, "layout", "center"), block = s(type, "block", "none");
        boolean vertical = layout.startsWith("vertical");
        var glyphs = line.glyphs().stream().map(LyricTimeline.Glyph::text).toList();
        double tracking = switch (block) { case "circle" -> .24; case "tile" -> .14; default -> .06; };
        double minimum = switch (block) { case "circle" -> 1.21; case "tile", "stair" -> .9; default -> 0; };
        double padding = switch (block) {
            case "pill", "ticket", "split", "outline" -> .25;
            default -> .06;
        };
        ToDoubleFunction<String> advance = ch -> {
            if (ch.isBlank()) return Math.max(.4, measure.applyAsDouble(ch)) + .2;
            double natural = vertical && upright(ch) ? 1 : measure.applyAsDouble(ch);
            return Math.max(minimum, natural + padding * 2) + tracking;
        };
        double limit = (vertical ? height : width) / font;
        var rows = LyricTextFlow.wrap(glyphs, advance, Math.max(1, limit - .5));
        var letters = new ArrayList<Letter>();
        var bands = new ArrayList<Band>();
        double[] wordY = new double[glyphs.size()];
        int cursor = 0, word = 0;
        for (var segment : LyricWords.split(line)) {
            double offset = (random(seed + word++ * 37) - .5) * 1.05;
            for (var ignored : segment.glyphs()) wordY[cursor++] = offset;
        }
        double pitch = layout.equals("scatter") ? 2.5 : block.equals("circle") ? 1.8 : 1.65;
        double longest = 0;
        for (var row : rows) {
            double length = 0;
            for (int i = row.from(); i < row.to(); i++) length += advance.applyAsDouble(glyphs.get(i));
            longest = Math.max(longest, length);
        }
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r); double length = 0;
            for (int i = row.from(); i < row.to(); i++) length += advance.applyAsDouble(glyphs.get(i));
            double along = -length / 2, across = (r - (rows.size() - 1) / 2.0) * pitch;
            double shift = layout.equals("left") ? -(longest - length) / 2
                    : layout.equals("right") ? (longest - length) / 2
                    : layout.equals("stagger") && rows.size() > 1 ? (r % 2 == 0 ? -.55 : .55) : 0;
            bands.add(new Band(vertical ? -across : shift, vertical ? 0 : across, length, vertical ? 90 : 0));
            for (int i = row.from(); i < row.to(); i++) {
                String ch = glyphs.get(i); double step = advance.applyAsDouble(ch), center = along + step / 2;
                along += step;
                if (ch.isBlank()) continue;
                double x = center + shift, y = across, angle = 0;
                if (layout.equals("diagonal")) y -= center * .2;
                if (layout.equals("scatter")) y += wordY[i];
                if (layout.equals("arc")) { y += .65 * (1 - Math.cos(center / Math.max(1, length) * Math.PI)); angle = center / Math.max(1, length) * 35; }
                if (block.equals("stair")) y -= (i - row.from()) % 3 * .16;
                if (vertical) { x = -across; y = center; angle = upright(ch) ? 0 : 90; }
                letters.add(new Letter(i, x, y, measure.applyAsDouble(ch), angle));
            }
        }
        if (letters.isEmpty()) return new Placement(font, 0, 0, List.of(), List.of());
        double left = Double.POSITIVE_INFINITY, top = left, right = -left, bottom = -left;
        for (var letter : letters) {
            double halfW = block.equals("circle") ? .75 : Math.max(.5, letter.width / 2 + padding + .12);
            double halfH = block.equals("circle") ? .75 : .85;
            if (letter.rotation == 90) { double swap = halfW; halfW = halfH; halfH = swap; }
            left = Math.min(left, letter.x - halfW); right = Math.max(right, letter.x + halfW);
            top = Math.min(top, letter.y - halfH); bottom = Math.max(bottom, letter.y + halfH);
        }
        double bw = right - left, bh = bottom - top, tilt = n(type, "tilt", 0);
        double rotatedW = bw * Math.abs(Math.cos(tilt)) + bh * Math.abs(Math.sin(tilt));
        double rotatedH = bh * Math.abs(Math.cos(tilt)) + bw * Math.abs(Math.sin(tilt));
        double size = Math.min(font, Math.min(width / rotatedW, height / rotatedH));
        double cx = (left + right) / 2, cy = (top + bottom) / 2;
        var fitted = new ArrayList<Letter>();
        for (var letter : letters) fitted.add(new Letter(letter.index, (letter.x - cx) * size,
                (letter.y - cy) * size, letter.width * size, letter.rotation));
        var fittedBands = new ArrayList<Band>();
        for (var band : bands) fittedBands.add(new Band((band.x - cx) * size, (band.y - cy) * size, band.length * size, band.angle));
        return new Placement(size, rotatedW * size, rotatedH * size, List.copyOf(fitted), List.copyOf(fittedBands));
    }

    private static boolean upright(String ch) {
        int code = ch.codePointAt(0);
        return Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(code) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(code) == Character.UnicodeScript.KATAKANA
                || code >= 0x1f000;
    }
}
