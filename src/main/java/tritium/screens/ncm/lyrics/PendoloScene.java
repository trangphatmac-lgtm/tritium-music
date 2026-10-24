// SPDX-License-Identifier: AGPL-3.0-only
// Folia pendoloGeometry.ts, pendoloClockworkScene.ts and pendoloClockworkMotion.ts.
package tritium.screens.ncm.lyrics;

import static tritium.screens.ncm.lyrics.LyricMath.*;

final class PendoloScene implements LyricScene {
    private final Spring wheel = new Spring(), seconds = new Spring();
    private double phase;
    @Override public boolean ownSubtitles() { return true; }
    @Override public void render(LyricCanvas c, LyricFrame f) {
        SceneBackdrop.ambient(c, f, 0xFF101515);
        double w = f.width(), h = f.contentHeight(), radius = Math.min(w, h) * .42;
        int index = Math.max(0, f.index());
        if (f.discontinuity()) { wheel.snap(index); seconds.snap(Math.floor(f.time()) * Math.PI * 2 / 15); phase = f.time() * 2.8; }
        double center = wheel.step(index, f.dt(), 190, 22);
        if (!f.paused()) phase += f.dt() * (2.8 + f.energy().bass() * 3.5);
        double secondsAngle = seconds.step(Math.floor(f.time()) * Math.PI * 2 / 15, f.dt(), 92, 13);
        double cy = h * .5;
        c.glow(0, cy, radius * 1.65, radius * 1.65, alpha(f.accent(), .14));
        for (double r : new double[]{.9, 1, 1.045, 1.09}) c.ring(0, cy, radius * r, 1, -Math.PI / 2, Math.PI, 0x24FFFFFF);
        for (int i = -45; i <= 45; i++) {
            double a = i * Math.PI / 90, outer = radius * 1.065, inner = outer - (i % 5 == 0 ? 15 : 6);
            c.line(Math.cos(a) * inner, cy + Math.sin(a) * inner, Math.cos(a) * outer, cy + Math.sin(a) * outer, 1, 0x55D6DEDA);
        }
        gear(c, 0, cy, radius * .82, 72, -center * .08, f.accent(), .6);
        gear(c, radius * .2, cy - radius * .75, radius * .28, 28, Math.sin(phase) * (.25 + f.energy().bass() * .6), f.accent(), .6);
        gear(c, radius * .32, cy + radius * .78, radius * .34, 24, center * .18 + phase * .03, f.accent(), .6);
        gear(c, radius * .68, cy + radius * .76, radius * .16, 15, -secondsAngle, f.accent(), .6);
        gear(c, radius * .54, cy + radius * .75, radius * .08, 12, secondsAngle * 1.25, f.accent(), .6);
        c.line(0, cy, radius - 16, cy, 1.5, alpha(f.accent(), .6));
        c.circle(0, cy, 6, alpha(f.accent(), .9));
        double size = Math.max(24, Math.min(44, w * .042)), maxWidth = Math.max(100, w - radius - 70);
        double subtitleRows = (f.translation() ? 1 : 0) + (f.romanization() ? 1 : 0);
        var currentLine = f.timeline().line(index);
        int activeRows = currentLine == null ? 1 : Math.min(3, c.wrapGlyphs(currentLine, size * 1.25, maxWidth, true).size());
        double step = Math.max(radians(100) / 8, (size * 1.3 * activeRows + subtitleRows * 22 + 24) / radius);
        for (int i = Math.max(0, index - 8); i <= Math.min(f.timeline().lines().size() - 1, index + 8); i++) {
            double angle = (i - center) * step;
            if (Math.abs(angle) >= Math.PI / 2) continue;
            var line = f.timeline().line(i);
            double d = Math.abs(i - center), a = Math.max(.12, Math.pow(Math.cos(angle * .75), 2.5) * (1 - d * .18));
            double s = i == index ? 1.25 : Math.max(.7, 1 - d * .08);
            double x = radius * Math.cos(angle) + 24, y = cy + radius * Math.sin(angle);
            c.push(x, y, 1, Math.toDegrees(angle));
            double font = size * s;
            var rows = c.wrapGlyphs(line, font, maxWidth, true);
            for(int attempt=0;attempt<16&&rows.size()>3;attempt++){font*=.88;rows=c.wrapGlyphs(line,font,maxWidth,true);}
            double rowY = -rows.size() * font * .6;
            for(var row:rows){
                double cursor=0;
                for(var glyph:row){
                    c.text(glyph.text(),cursor,rowY,font,alpha(color(0xFF909B99,0xFFF4F8F6,glyph.progress(f.time())),a),true);
                    cursor+=c.width(glyph.text(),font,true);
                }
                rowY+=font*1.2;
            }
            double subY = rowY + 8;
            if (f.romanization() && line.romanization() != null) { subtitle(c, line.romanization(), subY, maxWidth, a * .55); subY += 23; }
            if (f.translation() && line.translation() != null) subtitle(c, line.translation(), subY, maxWidth, a * .68);
            c.pop();
            if (Math.abs(angle) < .06) c.hit(x, y - size, maxWidth, size * 2.4, line.start());
        }
    }
    private void subtitle(LyricCanvas c, String text, double y, double maxWidth, double a) {
        c.text(text, 0, y, Math.min(20, maxWidth / Math.max(1, c.width(text, 1, false))), alpha(0xFFFFFFFF, a), false);
    }
    static void gear(LyricCanvas c, double x, double y, double radius, int teeth, double angle, int accent, double alpha) {
        c.push(x, y, 1, Math.toDegrees(angle));
        c.circle(0, 0, radius * .88, LyricMath.alpha(accent, .048 * alpha));
        c.ring(0, 0, radius * .8, 1.4, 0, Math.PI * 2, LyricMath.alpha(0xFFFFFFFF, .42 * alpha));
        c.ring(0, 0, radius * .67, 1, 0, Math.PI * 2, LyricMath.alpha(accent, .58 * alpha));
        c.ring(0, 0, radius * .22, 1.5, 0, Math.PI * 2, LyricMath.alpha(0xFFFFFFFF, .4 * alpha));
        for (int i = 0; i < teeth; i++) {
            double a = i * Math.PI * 2 / teeth;
            double b = (i + .45) * Math.PI * 2 / teeth;
            double r = radius * .89;
            c.line(Math.cos(a) * r, Math.sin(a) * r, Math.cos(a) * radius, Math.sin(a) * radius, 1.2, LyricMath.alpha(accent, .58 * alpha));
            c.line(Math.cos(a) * radius, Math.sin(a) * radius, Math.cos(b) * radius, Math.sin(b) * radius, 1.2, LyricMath.alpha(accent, .58 * alpha));
            c.line(Math.cos(b) * radius, Math.sin(b) * radius, Math.cos(b) * r, Math.sin(b) * r, 1.2, LyricMath.alpha(accent, .58 * alpha));
            if (i % Math.max(1, teeth / 6) == 0) c.line(Math.cos(a) * radius * .24, Math.sin(a) * radius * .24,
                    Math.cos(a) * radius * .65, Math.sin(a) * radius * .65, radius * .045, LyricMath.alpha(0xFFDBE5DF, .16 * alpha));
        }
        c.circle(0, 0, 3, LyricMath.alpha(accent, .65 * alpha)); c.pop();
    }
}
