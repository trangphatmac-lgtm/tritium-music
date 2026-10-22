// Adapted from Folia's monetBackgroundPaint.ts (AGPL-3.0); see tritium/background/cover/NOTICE.txt.
package tritium.desktop.background.cover;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.concurrent.CancellationException;

/** The original 1920 × 1080 Monet poster pipeline, with Folia's default treatment. */
public final class MonetPaint {
    private static final int WIDTH = 1920, HEIGHT = 1080;
    private MonetPaint() { }

    static BufferedImage paint(BufferedImage cover, int background, int accent, int primary) {
        // Padding gives the separable Gaussian the same backdrop beyond the canvas edge.
        int pad = 18;
        BufferedImage padded = new BufferedImage(WIDTH + pad * 2, HEIGHT + pad * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setColor(new Color(background));
        g.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.max((double) WIDTH / cover.getWidth(), (double) HEIGHT / cover.getHeight());
        int w = (int) Math.round(cover.getWidth() * scale), h = (int) Math.round(cover.getHeight() * scale);
        g.drawImage(cover, pad + (WIDTH - w) / 2, pad + (HEIGHT - h) / 2, w, h, null);
        g.dispose();
        float[] kernel = new float[pad * 2 + 1];
        double sum = 0;
        for (int i = -pad; i <= pad; i++) { kernel[i + pad] = (float) Math.exp(-i * i / 72.0); sum += kernel[i + pad]; }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= (float) sum;
        BufferedImage horizontal = new ConvolveOp(new Kernel(kernel.length, 1, kernel), ConvolveOp.EDGE_NO_OP, null).filter(padded, null);
        BufferedImage blurred = new ConvolveOp(new Kernel(1, kernel.length, kernel), ConvolveOp.EDGE_NO_OP, null).filter(horizontal, null);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] pixels = blurred.getRGB(pad, pad, WIDTH, HEIGHT, null, 0, WIDTH);
        for (int i = 0; i < pixels.length; i++) {
            if (i % WIDTH == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
            pixels[i] = treat(pixels[i], background, accent, primary);
        }
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        g = image.createGraphics();
        g.setPaint(new LinearGradientPaint(0, 0, WIDTH, HEIGHT, new float[]{0, .44f, 1}, new Color[]{
                alpha(accent, .08 + .74 * .62), alpha(background, .16 + .74 * .58), alpha(primary, .06 + .74 * .36)}));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setPaint(new RadialGradientPaint(WIDTH * .18f, HEIGHT * .34f, WIDTH * .55f,
                new float[]{0, 1}, new Color[]{alpha(accent, .74 * .42), alpha(background, 0)}));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setPaint(new LinearGradientPaint(WIDTH * .45f, 0, WIDTH, 0, new float[]{0, 1},
                new Color[]{alpha(background, 0), alpha(background, .18 + .74 * .38)}));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(alpha(background, .1 + .74 * .08));
        for (int i = 0; i < 18; i++) g.fillRect((i * 127) % WIDTH, ((i * 211) % HEIGHT) - 40, 1, (int) (HEIGHT * .28));
        g.dispose();
        return image;
    }

    public static int treat(int color, int background, int accent, int primary) {
        double luminance = luminance(color);
        int shadow = luminance(background) <= luminance(primary) ? background : primary;
        int highlight = shadow == background ? primary : background;
        double l = luminance / 255;
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            double original = luminance + (((color >> shift) & 255) - luminance) * 1.05;
            double wash = l <= .55 ? mix(channel(shadow, shift), channel(accent, shift), l / .55)
                    : mix(channel(accent, shift), channel(highlight, shift), (l - .55) / .45);
            int value = (int) Math.round(Math.max(0, Math.min(255, mix(original, wash, .34))));
            result |= value << shift;
        }
        return result;
    }

    private static double channel(int color, int shift) { return (color >> shift) & 255; }
    private static double luminance(int color) { return .2126 * channel(color, 16) + .7152 * channel(color, 8) + .0722 * channel(color, 0); }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    private static Color alpha(int color, double opacity) { return new Color((color & 0xFFFFFF) | ((int) Math.round(opacity * 255) << 24), true); }
}
