package tritium.minecraft.runtime.lyric;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import tritium.minecraft.protocol.PlaybackState.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/** MusicLyricsWidget's spacing, per-line grace scroll and three word reveal modes. */
final class WidgetLyrics {
    private final WidgetText font;
    private double[] positions = new double[0], alphas = new double[0];
    private double scroll, previousTime;
    private long timeline = Long.MIN_VALUE, previousNow;
    WidgetLyrics(WidgetText font) { this.font = font; }

    void paint(Graphics2D g, HudConfig.Module m, Track track, PlaybackState state, double time,
               double width, double height, long now, boolean preview) {
        if (!preview && (!state.playing || track.lyrics == null || track.lyrics.isEmpty())) return;
        if (track.lyrics == null || track.lyrics.isEmpty()) {
            String text = "Tritium Music · 歌词预览";
            font.draw(g, text, x(text, width, 28, m.align), (height - font.height(28, true)) / 2, 28, true, -1, m.shadow);
            return;
        }
        int current = current(track, time), anchor = Math.max(0, current), rows = 0;
        for (Line line : track.lyrics) rows = Math.max(rows, secondaryCount(m, line));
        double big = (int)font.height(28, true), small = (int)font.height(18, true);
        double spacing = big + m.lyricHeight + (rows == 0 ? -small - 4 : (rows - 1) * (small + 2));
        double center = (height - big) / 2;
        double delta = previousNow == 0 ? 16.667 : Math.max(0, Math.min(100, now - previousNow));
        boolean reset = timeline != track.timeline || positions.length != track.lyrics.size()
                || time < previousTime - 250 || time - previousTime > Math.max(1500, now - previousNow + 500);
        if (reset) {
            positions = new double[track.lyrics.size()]; alphas = new double[positions.length];
            for (int i = 0; i < positions.length; i++) { positions[i] = center + (i - anchor) * spacing; alphas[i] = i == current ? 1 : .25; }
            scroll = anchor * spacing; timeline = track.timeline;
        }
        previousTime = time; previousNow = now;
        scroll = approach(scroll, anchor * spacing, .2, delta);
        for (int i = 0; i < positions.length; i++) {
            double dest = center + (i - anchor) * spacing;
            if (Math.abs(positions[i] - dest) > 100) positions[i] = dest;
            double previousLag = i == 0 ? 0 : positions[i - 1] - (dest - spacing);
            if (previousLag < spacing * .55) positions[i] = approach(positions[i], dest, .15, delta);
            alphas[i] = approach(alphas[i], i == current ? 1 : .25, .1, delta);
            if (m.singleLine && i != anchor) continue;
            double y = m.singleLine ? center : m.graceScroll ? positions[i] : center + i * spacing - scroll;
            if (y + spacing < 0 || y > height) continue;
            Line line = track.lyrics.get(i);
            String text = line.text == null ? "" : line.text;
            boolean active = i == current, words = line.words != null && !line.words.isEmpty();
            int alpha = active && words ? 80 : (int)(alphas[i] * 255);
            double left = x(text, width, 28, m.align);
            if (!active || !words || !"SlideIn".equals(m.scrollEffect) || "Left".equals(m.align))
                font.draw(g, text, left, y, 28, true, white(alpha), m.shadow);
            if (active && words) words(g, m, line, text, time, left, y, width);
            double secondaryY = y + big + 2;
            int secondaryAlpha = i <= current ? (int)(alphas[i] * 255) : 100;
            if (m.romanization && present(line.romanization)) {
                font.draw(g, line.romanization, x(line.romanization, width, 18, m.align), secondaryY, 18, true, white(secondaryAlpha), m.shadow);
                secondaryY += small + 2;
            }
            if (m.translation && present(line.translation))
                font.draw(g, line.translation, x(line.translation, width, 18, m.align), secondaryY, 18, true, white(secondaryAlpha), m.shadow);
        }
    }

    private void words(Graphics2D g, HudConfig.Module m, Line line, String text, double time, double left, double y, double width) {
        int index = 0;
        for (int i = 0; i < line.words.size(); i++) { if (line.words.get(i).timestamp > time) break; index = i; }
        Word word = line.words.get(index);
        double progress = HudConfig.clamp((time - word.timestamp) / Math.max(1, word.duration), 0, 1), before = 0;
        for (int i = 0; i < index; i++) before += font.width(line.words.get(i).text, 28, true);
        double wordWidth = font.width(word.text, 28, true);
        if ("Scroll".equals(m.scrollEffect)) {
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Double(left, y, before + wordWidth * progress + 1, font.height(28, true) + 4));
            font.draw(g, text, left, y, 28, true, -1, m.shadow); g.setClip(clip);
            return;
        }
        if ("SlideIn".equals(m.scrollEffect)) {
            double revealed = before + wordWidth * (1 - Math.pow(1 - progress, 3));
            left = "Right".equals(m.align) ? width - revealed : "Center".equals(m.align) ? (width - revealed) / 2 : 0;
        }
        for (int i = 0; i <= index; i++) {
            Word part = line.words.get(i);
            int alpha = i < index ? 255 : (int)(255 * Math.min(1, progress * 1.25));
            font.draw(g, part.text, left, y, 28, true, white(alpha), m.shadow);
            left += font.width(part.text, 28, true);
        }
    }
    private double x(String text, double width, float size, String align) {
        double length = font.width(text, size, true);
        return "Right".equals(align) ? width - length : "Center".equals(align) ? (width - length) / 2 : 0;
    }
    private static int secondaryCount(HudConfig.Module m, Line line) {
        return (m.translation && present(line.translation) ? 1 : 0) + (m.romanization && present(line.romanization) ? 1 : 0);
    }
    private static boolean present(String value) { return value != null && !value.isEmpty(); }
    static int current(Track track, double time) {
        int result = -1;
        if (track.lyrics != null) for (int i = 0; i < track.lyrics.size(); i++) { if (track.lyrics.get(i).timestamp > time) break; result = i; }
        return result;
    }
    static double approach(double start, double end, double speed, double delta) { return start + (end - start) * Math.min(1, delta * speed / 20); }
    private static int white(int alpha) { return Math.max(0, Math.min(255, alpha)) << 24 | 0xffffff; }
}
