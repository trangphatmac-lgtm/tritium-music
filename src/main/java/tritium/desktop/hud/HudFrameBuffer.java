package tritium.desktop.hud;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Composes translucent layers before submitting a frame to the window surface. */
final class HudFrameBuffer {
    private BufferedImage image;

    void paint(Graphics2D target, int width, int height, Consumer<Graphics2D> painter) {
        AffineTransform transform = target.getTransform();
        double scaleX = Math.hypot(transform.getScaleX(), transform.getShearY());
        double scaleY = Math.hypot(transform.getShearX(), transform.getScaleY());
        if (width <= 0 || height <= 0 || scaleX == 0 || scaleY == 0) {
            return;
        }
        int pixelWidth = Math.max(1, (int) Math.ceil(width * scaleX));
        int pixelHeight = Math.max(1, (int) Math.ceil(height * scaleY));
        if (image == null || image.getWidth() != pixelWidth || image.getHeight() != pixelHeight) {
            image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        Graphics2D frame = image.createGraphics();
        try {
            // Reusing the buffer must not accumulate alpha or retain old text/cover pixels.
            frame.setComposite(AlphaComposite.Clear);
            frame.fillRect(0, 0, pixelWidth, pixelHeight);
            frame.setComposite(AlphaComposite.SrcOver);
            frame.scale(scaleX, scaleY);
            HudRenderUtil.configure(frame);
            painter.accept(frame);
        } finally {
            frame.dispose();
        }

        // Replace the previous frame, including pixels that are now transparent,
        // without a separate clear of the native surface. Preserve caller state.
        Graphics2D presentation = (Graphics2D) target.create();
        try {
            presentation.setComposite(AlphaComposite.Src);
            // Keep the backing image at device resolution, including Retina displays.
            presentation.drawImage(image, AffineTransform.getScaleInstance(1 / scaleX, 1 / scaleY), null);
        } finally {
            presentation.dispose();
        }
    }

    void release() {
        if (image != null) {
            image.flush();
            image = null;
        }
    }
}
