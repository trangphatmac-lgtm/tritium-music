package tritium.minecraft.runtime;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import java.awt.*;
import java.awt.geom.*;

/** Original half-band selection, step=3, 16*10 gain and 200ms peak hold. No frame normalization. */
final class WidgetSpectrum {
    private double[] bars = new double[0], peaks = new double[0];
    private long[] peaksAt = new long[0];
    private long previousNow;
    void paint(Graphics2D g, HudConfig.Module m, PlaybackState s, double width, double height, long now, boolean preview) {
        int count = Math.max(1, s.spectrum.length / 2);
        boolean demo = preview && (!s.playing || s.paused || count <= 1);
        if (demo) count = 128;
        if (bars.length != count) { bars = new double[count]; peaks = new double[count]; peaksAt = new long[count]; }
        double delta = previousNow == 0 ? 16.667 : Math.max(0, Math.min(100, now - previousNow)); previousNow = now;
        double speed = 2 - (m.absoluteVolume ? 0 : .5 * (1 - s.volume));
        for (int i = 0; i < count; i++) {
            double target = demo ? .10 + .08 * Math.sin(i * .1 + now / 500.0)
                    : s.playing && !s.paused && i < s.spectrum.length ? s.spectrum[i] : 0;
            if (!Double.isFinite(target)) target = 0;
            bars[i] = WidgetLyrics.approach(bars[i], Math.max(0, target) * (m.compact ? 8 : 16), speed, delta);
        }
        if (m.compact) { g.setColor(new Color(0x66000000, true)); g.fill(new RoundRectangle2D.Double(0, 0, width, height, 12, 12)); }
        double padding = m.compact ? 4 : 0, base = height - padding;
        int step = "Line".equals(m.spectrumStyle) ? (m.compact ? 2 : 1) : (m.compact ? 8 : 3);
        double barWidth = (width - padding * 2) / (count / (double)step);
        Path2D path = new Path2D.Double(); path.moveTo(padding, base);
        if (!"Line".equals(m.spectrumStyle)) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(m.color, true));
        for (int i = 0; i < count; i += step) {
            double amplitude = Math.min(height - padding * 2, bars[i] * m.multiplier * 10);
            double x = padding + barWidth * i / step;
            if ("Line".equals(m.spectrumStyle)) path.lineTo(x, base - amplitude);
            else {
                if (amplitude > 0) g.fill(new Rectangle2D.Double(x, base - amplitude, barWidth, amplitude));
                if (m.indicator) {
                    if (amplitude > peaks[i]) { peaks[i] = amplitude; peaksAt[i] = now; }
                    else if (now - peaksAt[i] > 200) peaks[i] = Math.max(-6, peaks[i] - delta * .08);
                    g.fill(new Rectangle2D.Double(x, base - peaks[i] - 2, barWidth, 1));
                }
            }
        }
        if ("Line".equals(m.spectrumStyle)) {
            path.lineTo(width - padding, base); g.setStroke(new BasicStroke(m.compact ? .75f : 1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(path);
        }
    }
}
