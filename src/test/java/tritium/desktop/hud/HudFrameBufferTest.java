package tritium.desktop.hud;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudFrameBufferTest {
    @Test
    void repeatedFramesDoNotAccumulateAlphaAndRemoveOldPixels() {
        HudFrameBuffer buffer = new HudFrameBuffer();
        for (int i = 0; i < 120; i++) {
            BufferedImage output = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            int x = i % 2 == 0 ? 0 : 20;
            try {
                buffer.paint(graphics, 40, 20, frame -> {
                    frame.setColor(new Color(255, 0, 0, 128));
                    frame.fillRect(x, 0, 20, 20);
                });
            } finally {
                graphics.dispose();
            }
            assertEquals(0x80FF0000, output.getRGB(x + 5, 5));
            assertEquals(0, output.getRGB((x + 20) % 40 + 5, 5));
        }
    }

    @Test
    void preservesDeviceResolutionAcrossResizeAndScaleChanges() {
        HudFrameBuffer buffer = new HudFrameBuffer();
        for (double scale : new double[]{1, 2, 1.25, 1}) {
            int width = (int) (20 * scale);
            BufferedImage output = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.scale(scale, scale);
                buffer.paint(graphics, 20, 20, frame -> {
                    // One physical pixel must stay one pixel instead of being upscaled.
                    frame.scale(1 / scale, 1 / scale);
                    frame.setColor(Color.WHITE);
                    frame.fillRect(width - 1, 0, 1, width);
                });
            } finally {
                graphics.dispose();
            }
            assertEquals(0xFFFFFFFF, output.getRGB(width - 1, width - 1));
            assertEquals(0, output.getRGB(width - 2, width - 1));
        }
    }
}
