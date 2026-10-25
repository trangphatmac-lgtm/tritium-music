package tritium.minecraft.runtime;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.awt.*;
import java.awt.font.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;

/** Gale's 48px system-font rasterization, rendered at nominal size 10 on atlas quads. */
final class WorldGlyphFont implements AutoCloseable {
    private static final int PAGE = 1024, PAD = 4;
    private static final float BASE = 48, SIZE = 10;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final String[] FAMILIES = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Yu Gothic UI",
            "Meiryo UI", "Meiryo", "Malgun Gothic", "Noto Sans CJK SC", "Source Han Sans SC", "SimHei",
            "MS Gothic", "Segoe UI Symbol", "Segoe UI", "Arial Unicode MS"};
    private final List<Font> fonts = new ArrayList<Font>();
    private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();
    private final List<Page> pages = new ArrayList<Page>();
    private int px = PAD, py = PAD, rowHeight;

    WorldGlyphFont() {
        Set<String> available = new HashSet<String>();
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ENGLISH))
            available.add(f.toLowerCase(Locale.ROOT));
        for (String family : FAMILIES) if (available.contains(family.toLowerCase(Locale.ROOT)))
            fonts.add(new Font(family, Font.PLAIN, 1).deriveFont(BASE));
        fonts.add(new Font("Dialog", Font.PLAIN, 1).deriveFont(BASE));
    }

    double width(String text) {
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            Glyph g = glyph(text.charAt(i));
            if (g != null) width += g.advance * SIZE / BASE;
        }
        return width;
    }

    private Glyph glyph(char c) {
        if (glyphs.containsKey(c)) return glyphs.get(c);
        Font font = null;
        for (Font candidate : fonts) if (candidate.canDisplay(c)) { font = candidate; break; }
        if (font == null) { glyphs.put(c, null); return null; }
        GlyphVector vector = font.createGlyphVector(FRC, new char[]{c});
        Rectangle2D bounds = vector.getVisualBounds();
        Glyph g = new Glyph(); g.advance = vector.getGlyphMetrics(0).getAdvanceX();
        if (bounds.getWidth() > 0 && bounds.getHeight() > 0) {
            g.left = (int)Math.floor(bounds.getX()) - 1; g.top = (int)Math.floor(bounds.getY()) - 1;
            g.w = (int)Math.ceil(bounds.getMaxX()) + 1 - g.left;
            g.h = (int)Math.ceil(bounds.getMaxY()) + 1 - g.top;
            BufferedImage image = new BufferedImage(g.w, g.h, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE); graphics.drawGlyphVector(vector, -g.left, -g.top); graphics.dispose();
            g.coverage = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
        }
        glyphs.put(c, g);
        return g;
    }

    private boolean mipmaps() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_object;
    }
    private void mipmap() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps.OpenGL30) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        else EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
    }
    private void page() {
        Page page = new Page(); page.texture = GL11.glGenTextures(); pages.add(page);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, page.texture);
        ByteBuffer clear = BufferUtils.createByteBuffer(PAGE * PAGE * 4);
        for (int i = 0; i < PAGE * PAGE; i++) clear.put((byte)255).put((byte)255).put((byte)255).put((byte)0);
        clear.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, PAGE, PAGE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, clear);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmaps() ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        page.dirty = true; px = py = PAD; rowHeight = 0;
    }
    private void upload(Glyph g) {
        if (g.page >= 0 || g.coverage == null) return;
        if (pages.isEmpty()) page();
        if (px + g.w + PAD > PAGE) { px = PAD; py += rowHeight + PAD; rowHeight = 0; }
        if (py + g.h + PAD > PAGE) page();
        g.page = pages.size() - 1; g.u = px / (float)PAGE; g.v = py / (float)PAGE;
        Page page = pages.get(g.page);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, page.texture);
        ByteBuffer data = BufferUtils.createByteBuffer(g.w * g.h * 4);
        for (byte alpha : g.coverage) data.put((byte)255).put((byte)255).put((byte)255).put(alpha);
        data.flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, px, py, g.w, g.h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        page.dirty = true; g.coverage = null; px += g.w + PAD; rowHeight = Math.max(rowHeight, g.h);
    }

    void draw(char c, double x, double baseline, int color, double alpha) {
        Glyph g = glyph(c); if (g == null || g.w == 0) return;
        upload(g);
        Page page = pages.get(g.page); GL11.glBindTexture(GL11.GL_TEXTURE_2D, page.texture);
        if (page.dirty) { if (mipmaps()) mipmap(); page.dirty = false; }
        GL11.glColor4d((color >> 16 & 255) / 255.0, (color >> 8 & 255) / 255.0, (color & 255) / 255.0, alpha);
        double scale = SIZE / BASE, left = x + g.left * scale, top = baseline + g.top * scale;
        double right = left + g.w * scale, bottom = top + g.h * scale;
        float u1 = g.u + g.w / (float)PAGE, v1 = g.v + g.h / (float)PAGE;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(g.u, g.v); GL11.glVertex2d(left, top);
        GL11.glTexCoord2f(g.u, v1); GL11.glVertex2d(left, bottom);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2d(right, bottom);
        GL11.glTexCoord2f(u1, g.v); GL11.glVertex2d(right, top);
        GL11.glEnd();
    }

    @Override public void close() {
        for (Page page : pages) GL11.glDeleteTextures(page.texture);
        pages.clear(); glyphs.clear(); px = py = PAD; rowHeight = 0;
    }
    private static final class Page { int texture; boolean dirty; }
    private static final class Glyph {
        int page = -1, left, top, w, h; float advance, u, v; byte[] coverage;
    }
}
