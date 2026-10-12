package tritium.desktop.hud;

import org.junit.jupiter.api.Test;
import tritium.desktop.MusicPreferences;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudAppearanceTest {
    @Test
    void mainWindowAppearanceDoesNotChangeHudPixels() {
        HudStateSnapshot snapshot = HudStateSnapshot.capture(true);
        List<Supplier<HudRenderer>> renderers = List.of(
                MusicInfoHudRenderer::new, MusicLyricsHudRenderer::new, MusicSpectrumHudRenderer::new);
        for (Supplier<HudRenderer> factory : renderers) {
            int[] dark = render(factory.get(), snapshot, "Dark");
            int[] light = render(factory.get(), snapshot, "Light");
            assertTrue(java.util.Arrays.stream(dark).anyMatch(pixel -> pixel != 0));
            assertArrayEquals(dark, light);
        }
    }

    private static int[] render(HudRenderer renderer, HudStateSnapshot snapshot, String mode) {
        MusicPreferences preferences = new MusicPreferences();
        preferences.appearance().setValue(mode);
        // Custom main backgrounds must not leak into any of the desktop HUD renderers.
        if (mode.equals("Light")) {
            preferences.background().mode().setValue("shader");
            preferences.background().shader().setValue("aurora");
            preferences.background().strength().setValue(1.0);
            preferences.background().panelOpacity().setValue(0.0);
        }
        Rectangle bounds = new Rectangle(0, 0, 640, 240);
        HudRenderContext context = new HudRenderContext(preferences, snapshot, bounds, bounds, false);
        BufferedImage image = new BufferedImage(640, 280, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            HudRenderUtil.configure(graphics);
            renderer.render(graphics, context);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(0, 0, 640, 280, null, 0, 640);
    }
}
