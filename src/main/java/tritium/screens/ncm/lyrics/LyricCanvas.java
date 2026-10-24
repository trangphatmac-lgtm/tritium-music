package tritium.screens.ncm.lyrics;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedRenderingConstants;
import tritium.interfaces.SharedConstants;
import tritium.management.FontManager;
import tritium.rendering.Image;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.utils.Location;

import java.util.ArrayList;
import java.util.List;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Small drawing vocabulary backed exclusively by Tritium's font atlas and LWJGL. */
final class LyricCanvas implements SharedRenderingConstants, SharedConstants {
    double opacity = 1;
    private final List<Hit> hits = new ArrayList<>();
    record Hit(double x, double y, double width, double height, double seconds) { }
    void begin(double opacity) { this.opacity = opacity; hits.clear(); }
    void hit(double x, double y, double width, double height, double seconds) {
        hits.add(new Hit(x, y, width, height, seconds));
    }
    Double seekAt(double x, double y) {
        for (Hit hit : hits) if (x >= hit.x && x <= hit.x + hit.width && y >= hit.y && y <= hit.y + hit.height) return hit.seconds;
        return null;
    }
    private void color(int color) { RenderSystem.color(alpha(color, opacity)); }
    private void beginShape(int type) {
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().disableTexture2D();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(type);
    }
    private void endShape() { GL11.glEnd(); api.getGLStateManager().enableTexture2D(); }
    void rect(double x, double y, double width, double height, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        beginShape(GL11.GL_QUADS); color(color);
        GL11.glVertex2d(x, y); GL11.glVertex2d(x + width, y); GL11.glVertex2d(x + width, y + height); GL11.glVertex2d(x, y + height);
        endShape();
    }
    void gradient(double x, double y, double width, double height, int top, int bottom) {
        beginShape(GL11.GL_QUADS);
        color(top); GL11.glVertex2d(x, y); GL11.glVertex2d(x + width, y);
        color(bottom); GL11.glVertex2d(x + width, y + height); GL11.glVertex2d(x, y + height);
        endShape();
    }
    void polygon(int color, double... xy) {
        beginShape(GL11.GL_TRIANGLE_FAN); color(color);
        for (int i = 0; i + 1 < xy.length; i += 2) GL11.glVertex2d(xy[i], xy[i + 1]);
        endShape();
    }
    void triangles(int color, double[] xy) {
        beginShape(GL11.GL_TRIANGLES); color(color);
        for (int i = 0; i + 1 < xy.length; i += 2) GL11.glVertex2d(xy[i], xy[i + 1]);
        endShape();
    }
    void line(double x1, double y1, double x2, double y2, double thickness, int color) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len < .001) return;
        double nx = -(y2 - y1) / len * thickness / 2, ny = (x2 - x1) / len * thickness / 2;
        polygon(color, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny);
    }
    void circle(double x, double y, double radius, int color) {
        beginShape(GL11.GL_TRIANGLE_FAN); color(color); GL11.glVertex2d(x, y);
        int segments = Math.max(16, Math.min(96, (int) radius));
        for (int i = 0; i <= segments; i++) { double a = i * Math.PI * 2 / segments; GL11.glVertex2d(x + Math.cos(a) * radius, y + Math.sin(a) * radius); }
        endShape();
    }
    void ring(double x, double y, double radius, double thickness, double start, double extent, int color) {
        beginShape(GL11.GL_QUAD_STRIP); color(color);
        int segments = Math.max(12, Math.min(180, (int) Math.abs(extent * radius / 4)));
        for (int i = 0; i <= segments; i++) {
            double a = start + extent * i / segments, cs = Math.cos(a), sn = Math.sin(a);
            GL11.glVertex2d(x + cs * radius, y + sn * radius);
            GL11.glVertex2d(x + cs * (radius + thickness), y + sn * (radius + thickness));
        }
        endShape();
    }
    void glow(double x, double y, double rx, double ry, int color) {
        if (rx < .01 || ry < .01) return;
        beginShape(GL11.GL_TRIANGLE_FAN); color(color); GL11.glVertex2d(x, y);
        color(color & 0xFFFFFF);
        for (int i = 0; i <= 40; i++) { double a = i * Math.PI / 20; GL11.glVertex2d(x + Math.cos(a) * rx, y + Math.sin(a) * ry); }
        endShape();
    }
    void star(double x, double y, double radius, double angle, int color) {
        push(x, y, 1, angle);
        polygon(color, 0, 0, 0, -radius, radius * .16, -radius * .16, radius, 0, radius * .16, radius * .16,
                0, radius, -radius * .16, radius * .16, -radius, 0, -radius * .16, -radius * .16, 0, -radius);
        pop();
    }
    void frame(double x, double y, double w, double h, double thickness, int color) {
        rect(x, y, w, thickness, color); rect(x, y + h - thickness, w, thickness, color);
        rect(x, y, thickness, h, color); rect(x + w - thickness, y, thickness, h, color);
    }
    void push(double x, double y, double scale, double angle) {
        GL11.glPushMatrix(); GL11.glTranslated(x, y, 0); GL11.glRotated(angle, 0, 0, 1); GL11.glScaled(scale, scale, 1);
    }
    void pop() { GL11.glPopMatrix(); }
    CFontRenderer font(boolean bold) { return bold ? FontManager.lyricBold : FontManager.lyricRegular; }
    double width(String text, double size, boolean bold) {
        if (text == null || text.isEmpty()) return 0;
        CFontRenderer font = font(bold);
        font.prepareGlyphs(text);
        return font.getStringWidthD(text) * size / font.sizePx;
    }
    void text(String text, double x, double y, double size, int color, boolean bold) {
        if (text == null || text.isBlank() || size <= 0 || (color >>> 24) == 0) return;
        CFontRenderer font = font(bold);
        push(x, y, size / font.sizePx, 0);
        // Atlas metrics include ascent/descent padding; scene layouts use an em-sized box.
        font.prepareGlyphs(text);
        double padding = Math.max(0, (font.fontHeight * .5 - font.sizePx) * .5);
        font.drawString(text, 0, -padding, alpha(color, opacity));
        pop();
    }
    void centered(String text, double x, double y, double size, int color, boolean bold) {
        text(text, x - width(text, size, bold) / 2, y - size * .55, size, color, bold);
    }
    void glowText(String text, double x, double y, double size, int color, double glow, boolean bold) {
        if (glow > .2) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                text(text, x + Math.cos(a) * glow * .18, y + Math.sin(a) * glow * .18, size, alpha(color, .055), bold);
            }
        }
        text(text, x, y, size, color, bold);
    }
    /** Glyph timing is shared; each style still owns its layout and entry/exit choreography. */
    void lyric(LyricTimeline.Line line, double time, double x, double y, double size, double maxWidth,
               int ink, int highlight, double opacity, boolean centered, boolean lift) {
        if (line == null) return;
        double fit = Math.min(1, maxWidth / Math.max(1, width(line.text(), size, true)));
        size *= fit;
        if (centered) x -= width(line.text(), size, true) / 2;
        for (LyricTimeline.Glyph glyph : line.glyphs()) {
            double progress = glyph.progress(time), active = Math.sin(progress * Math.PI);
            int color = alpha(LyricMath.color(ink, highlight, progress), opacity);
            glowText(glyph.text(), x, y - (lift ? active * size * .08 : 0), size, color, active * size * .18, true);
            x += width(glyph.text(), size, true);
        }
    }
    List<String> wrap(String text, double size, double maxWidth, boolean bold) {
        if (text == null || text.isBlank()) return List.of();
        var glyphs = LyricTimeline.split(text);
        return LyricTextFlow.wrap(glyphs, ch -> width(ch, size, bold), maxWidth).stream()
                .map(row -> String.join("", glyphs.subList(row.from(), row.to()))).toList();
    }
    List<List<LyricTimeline.Glyph>> wrapGlyphs(LyricTimeline.Line line, double size, double maxWidth, boolean bold) {
        var glyphs = line.glyphs().stream().map(LyricTimeline.Glyph::text).toList();
        return LyricTextFlow.wrap(glyphs, ch -> width(ch, size, bold), maxWidth).stream()
                .map(row -> line.glyphs().subList(row.from(), row.to())).toList();
    }
    void image(Location location, double x, double y, double w, double h, double alpha) {
        if (location == null) return;
        RenderSystem.color(LyricMath.alpha(0xFFFFFFFF, alpha * opacity));
        Image.draw(location, x, y, w, h, Image.Type.NoColor);
    }
}
