package tritium.minecraft.runtime;

import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** The original CFontRenderer's 2x glyph metrics, top origin and SF/PingFang fallback. */
final class WidgetText {
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private final Font regular = font("sfregular.otf"), bold = font("sfbold.otf");
    private final Font chinese = font("pf_normal.ttf"), chineseBold = font("pf_middleblack.ttf");
    private final Map<String, Face> faces = new HashMap<String, Face>();

    double height(float size, boolean weight) { return (face(size, weight).height - 8.5) * .5; }
    double width(String text, float size, boolean weight) {
        if (text == null) return 0;
        Face face = face(size, weight); double width = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            width += face.glyph(cp).advance * .5;
        }
        return width;
    }
    void draw(Graphics2D target, String text, double x, double y, float size, boolean weight, int color, boolean shadow) {
        if (text == null || text.isEmpty()) return;
        if (shadow) draw(target, text, x + 1, y + 1, size, weight, color & 0xff000000, false);
        Graphics2D g = (Graphics2D)target.create();
        try {
            g.translate(x, y - 2); g.scale(.5, .5); g.setColor(new Color(color, true));
            Face face = face(size, weight);
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i); i += Character.charCount(cp);
                Glyph glyph = face.glyph(cp); g.fill(glyph.shape); g.translate(glyph.advance, 0);
            }
        } finally { g.dispose(); }
    }
    private Face face(float size, boolean weight) {
        String key = size + ":" + weight;
        Face face = faces.get(key);
        if (face == null) { face = new Face((weight ? bold : regular).deriveFont(size), (weight ? chineseBold : chinese).deriveFont(size)); faces.put(key, face); }
        return face;
    }
    private static final class Face {
        final Font primary, fallback;
        final int height;
        final FontMetrics mainMetrics, fallbackMetrics;
        final Map<Integer, Glyph> glyphs = new HashMap<Integer, Glyph>();
        Face(Font primary, Font fallback) {
            this.primary = primary; this.fallback = fallback;
            Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try { mainMetrics = g.getFontMetrics(primary); fallbackMetrics = g.getFontMetrics(fallback); }
            finally { g.dispose(); }
            height = Math.max(mainMetrics.getAscent() + mainMetrics.getDescent(), fallbackMetrics.getAscent() + fallbackMetrics.getDescent());
        }
        Glyph glyph(int cp) {
            if (cp == '（') cp = '('; else if (cp == '）') cp = ')'; else if (cp == '・') cp = '·';
            Glyph cached = glyphs.get(cp); if (cached != null) return cached;
            boolean main = primary.canDisplay(cp); Font font = main ? primary : fallback;
            FontMetrics metrics = main ? mainMetrics : fallbackMetrics;
            GlyphVector vector = font.createGlyphVector(CONTEXT, new String(Character.toChars(cp)));
            float offset = Math.max(0, -vector.getGlyphMetrics(0).getLSB());
            double advance = Math.ceil(vector.getGlyphPosition(vector.getNumGlyphs()).getX() + offset);
            int baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
            cached = new Glyph(vector.getOutline((int)offset, baseline), advance);
            if (glyphs.size() > 4096) glyphs.clear();
            glyphs.put(cp, cached); return cached;
        }
    }
    private static final class Glyph {
        final Shape shape; final double advance;
        Glyph(Shape shape, double advance) { this.shape = shape; this.advance = advance; }
    }
    private static Font font(String name) {
        try (InputStream in = WidgetText.class.getResourceAsStream("/tritium/fonts/" + name)) { return Font.createFont(Font.TRUETYPE_FONT, in); }
        catch (Exception ignored) { return new Font("Dialog", Font.PLAIN, 14); }
    }
}
