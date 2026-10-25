package tritium.minecraft.runtime;

import java.awt.*;
import java.awt.image.*;

/** Prepared on the IPC worker. The render thread only swaps references and uploads textures. */
final class HudArtwork {
    final BufferedImage cover, blurred;
    HudArtwork(BufferedImage cover) { this.cover = cover; this.blurred = blur(cover); }

    private static BufferedImage blur(BufferedImage cover) {
        if (cover == null) return null;
        if (Math.min(cover.getWidth(), cover.getHeight()) < 41) return cover;
        // Same 41-tap, sigma=size/3 Gaussian and 31px edge expansion as CloudMusic.gaussianBlur.
        int size = 41; float sigma = size / 3f, sum = 0;
        float[] kernel = new float[size];
        for (int i = 0; i < size; i++) { double d = i - size / 2; kernel[i] = (float)Math.exp(-d * d / (2 * sigma * sigma)); sum += kernel[i]; }
        for (int i = 0; i < size; i++) kernel[i] /= sum;
        BufferedImage source = new BufferedImage(cover.getWidth(), cover.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = source.createGraphics(); sg.drawImage(cover, 0, 0, null); sg.dispose();
        BufferedImage horizontal = new ConvolveOp(new Kernel(size, 1, kernel), ConvolveOp.EDGE_NO_OP, null).filter(source, null);
        BufferedImage filtered = new ConvolveOp(new Kernel(1, size, kernel), ConvolveOp.EDGE_NO_OP, null).filter(horizontal, null);
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(filtered, -31, -31, filtered.getWidth() + 62, filtered.getHeight() + 62, null); g.dispose();
        return result;
    }
}
