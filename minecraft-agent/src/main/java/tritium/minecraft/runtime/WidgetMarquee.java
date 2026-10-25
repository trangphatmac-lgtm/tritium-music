package tritium.minecraft.runtime;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** ScrollText timing is per text, including a fresh wait when a song/lyric changes. */
final class WidgetMarquee {
    private final WidgetText font;
    private String previous = "";
    private long started;
    private BufferedImage surface;
    WidgetMarquee(WidgetText font) { this.font = font; }

    void draw(Graphics2D g, String text, double x, double y, double width, float size, boolean bold,
              int color, long now, long wait, long duration) {
        if (text == null || text.isEmpty() || width <= 0) return;
        if (!previous.equals(text)) { previous = text; started = now; }
        double length = font.width(text, size, bold);
        if (length <= width) { font.draw(g, text, x, y, size, bold, color, false); return; }
        double elapsed = Math.max(0, now - started), gap = font.width("    ", size, bold), offset;
        if (duration > 0) offset = Math.min(1, Math.max(0, elapsed - wait) / duration) * (length - width + 4);
        else {
            double cycle = wait + (length + gap) / .02;
            offset = Math.max(0, elapsed % cycle - wait) * .02;
        }
        int density = Math.max(2, (int)Math.ceil(Math.abs(g.getTransform().getScaleX())));
        double height = font.height(size, bold) + 4;
        int w = Math.max(1, (int)Math.ceil(width * density)), h = Math.max(1, (int)Math.ceil(height * density));
        if (surface == null || surface.getWidth() != w || surface.getHeight() != h) surface = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D layer = surface.createGraphics();
        try {
            layer.setComposite(AlphaComposite.Clear); layer.fillRect(0, 0, w, h); layer.setComposite(AlphaComposite.SrcOver);
            layer.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            layer.scale(density, density);
            font.draw(layer, text, -offset, 2, size, bold, color, false);
            if (duration <= 0) font.draw(layer, text, length + gap - offset, 2, size, bold, color, false);
            layer.setComposite(AlphaComposite.DstIn);
            if (offset > 0) {
                layer.setPaint(new GradientPaint(0, 0, new Color(0xffffff, true), 6, 0, Color.WHITE));
                layer.fill(new Rectangle2D.Double(0, 0, 6, height));
            }
            layer.setPaint(new GradientPaint((float)width - 6, 0, Color.WHITE, (float)width, 0, new Color(0xffffff, true)));
            layer.fill(new Rectangle2D.Double(width - 6, 0, 6, height));
        } finally { layer.dispose(); }
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y - 2);
        transform.scale(width / w, height / h); g.drawImage(surface, transform, null);
    }
}
