package tritium.desktop.hud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "tritium.testHud", matches = "true")
class HudWindowPaintingTest {
    @Test
    void keepsPreviousWindowFrameUntilNewContentIsReady() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JWindow window = new HudWindow();
            BufferedImage surface = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
            surface.setRGB(5, 5, 0xFF0000FF);
            int[] frameNumber = {0};
            JPanel content = new JPanel() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    // Observe the destination WHILE rendering the next frame.
                    // Window.paint must not first expose a blank native surface.
                    assertEquals(frameNumber[0] == 0 ? 0xFF0000FF : 0x80FF0000,
                            surface.getRGB(5, 5), "Previous frame was cleared before content was ready");
                    graphics.setColor(new Color(255, 0, 0, 128));
                    graphics.fillRect(frameNumber[0] == 0 ? 0 : 20, 0, 20, 20);
                }
            };
            try {
                window.setBackground(new Color(0, 0, 0, 0));
                content.setOpaque(false);
                window.setContentPane(content);
                window.setSize(40, 20);
                window.setVisible(true);
                window.validate();
                for (int i = 0; i < 2; i++) {
                    frameNumber[0] = i;
                    Graphics2D graphics = surface.createGraphics();
                    try {
                        window.paint(graphics);
                    } finally {
                        graphics.dispose();
                    }
                }
                assertEquals(0, surface.getRGB(5, 5), "Old content must disappear in the completed frame");
                assertEquals(0x80FF0000, surface.getRGB(25, 5));
            } finally {
                window.dispose();
            }
        });
    }
}
