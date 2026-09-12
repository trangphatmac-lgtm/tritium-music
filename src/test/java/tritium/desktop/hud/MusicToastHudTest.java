package tritium.desktop.hud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testHud", matches = "true")
class MusicToastHudTest {
    @Test
    void displaysWithoutMainWindowReplacesMessageAndCleansUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MusicToastHud hud = new MusicToastHud();
            try {
                hud.tick("Leona Lewis - Bleeding Love", true, 0);
                JWindow window = window(hud);
                assertTrue(window.isVisible());
                assertTrue(window.isAlwaysOnTop());
                assertFalse(window.getFocusableWindowState());
                assertFalse(window.isAutoRequestFocus());
                assertTrue(HudPlatformWindow.isPassThroughApplied(window, true));
                hud.tick(null, true, 5_000_000_000L);
                assertTrue(window.isVisible());
                window.validate();
                BufferedImage frame = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = frame.createGraphics();
                try {
                    window.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                assertNotEquals(0, frame.getRGB(20, 24) >>> 24, "Toast paints independently of an OpenGL context");
                hud.tick("下一首 🎵".repeat(100), true, 6_000_000_000L);
                assertSame(window, window(hud));
                assertTrue(window.getWidth() <= window.getGraphicsConfiguration().getBounds().width);
                hud.tick(null, true, 7_000_000_000L);
                assertTrue(window.isVisible(), "Replacing the song restarts its lifetime");
                hud.tick(null, true, 12_500_000_000L);
                assertFalse(window.isVisible());
                hud.tick("New song", true, 13_000_000_000L);
                assertTrue(window.isVisible());
                hud.tick(null, false, 13_100_000_000L);
                assertFalse(window.isVisible(), "Disabling notifications hides an active toast");
                hud.tick(null, true, 14_000_000_000L);
                assertFalse(window.isVisible(), "Re-enabling does not replay a stale toast");
                hud.dispose();
                assertFalse(window.isDisplayable());
                assertTrue(hud.isDisposed());
            } finally {
                hud.dispose();
            }
        });
    }

    private static JWindow window(MusicToastHud hud) {
        try {
            Field field = MusicToastHud.class.getDeclaredField("window");
            field.setAccessible(true);
            return (JWindow) field.get(hud);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
