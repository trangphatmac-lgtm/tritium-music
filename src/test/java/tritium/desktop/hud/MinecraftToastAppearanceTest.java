package tritium.desktop.hud;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compare the real system HUD painter with the isolated Agent at Minecraft GUI scale 2. */
class MinecraftToastAppearanceTest {
    @Test void matchesDesktopTextSizeInsetsAndDefaultHeight() throws Exception {
        try (URLClassLoader agent = new URLClassLoader(new java.net.URL[]{Path.of("minecraft-agent/target/minecraft-hud-agent.jar").toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> painterType = agent.loadClass("tritium.minecraft.runtime.HudPainter");
            Class<?> configType = agent.loadClass("tritium.minecraft.protocol.HudConfig");
            Class<?> moduleType = agent.loadClass("tritium.minecraft.protocol.HudConfig$Module");
            Class<?> trackType = agent.loadClass("tritium.minecraft.protocol.PlaybackState$Track");
            Class<?> stateType = agent.loadClass("tritium.minecraft.protocol.PlaybackState");
            Object config = configType.getConstructor().newInstance(), painter = painterType.getConstructor().newInstance();
            Object toast = configType.getMethod("find", String.class).invoke(config, "musicToast");
            Object state = stateType.getConstructor().newInstance(), track = trackType.getConstructor().newInstance();
            assertEquals(120, moduleType.getField("width").getDouble(toast));
            assertEquals(24, moduleType.getField("height").getDouble(toast));
            var paint = painterType.getMethod("paint", moduleType, trackType, stateType, double.class, double.class, double.class, int.class, long.class, boolean.class, int.class);
            var boundsMethod = painterType.getMethod("bounds", moduleType, stateType, int.class, int.class, long.class, boolean.class);
            BufferedImage sheet = new BufferedImage(540, 220, BufferedImage.TYPE_INT_RGB);
            Graphics2D gallery = sheet.createGraphics(); gallery.setColor(new Color(0x303038)); gallery.fillRect(0, 0, 540, 220);
            gallery.setColor(Color.WHITE); gallery.setFont(new Font("SansSerif", Font.PLAIN, 14));
            gallery.drawString("System HUD", 20, 25); gallery.drawString("Minecraft (GUI 2x)", 280, 25);
            int row = 0;
            for (String label : new String[]{"Now playing", "正在播放 · 月光", "A very long song name that should be truncated here"}) {
                MusicToastHud desktop = new MusicToastHud();
                var text = MusicToastHud.class.getDeclaredField("text"); text.setAccessible(true); text.set(desktop, label);
                var elapsed = MusicToastHud.class.getDeclaredField("elapsed"); elapsed.setAccessible(true); elapsed.setLong(desktop, 1_000_000_000L);
                var panel = MusicToastHud.class.getDeclaredField("panel"); panel.setAccessible(true); ((JPanel)panel.get(desktop)).setSize(240, 48);
                for (String[] resource : new String[][]{{"background", "now_playing.png"}, {"notes", "music_notes.png"}}) {
                    var field = MusicToastHud.class.getDeclaredField(resource[0]); field.setAccessible(true);
                    field.set(desktop, ImageIO.read(getClass().getResource("/tritium/textures/hud/" + resource[1])));
                }
                BufferedImage expected = new BufferedImage(240, 48, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = expected.createGraphics(); var render = MusicToastHud.class.getDeclaredMethod("paintToast", Graphics2D.class); render.setAccessible(true); render.invoke(desktop, g); g.dispose();
                painterType.getMethod("toast", String.class, long.class).invoke(painter, label, 0L);
                BufferedImage actual = (BufferedImage)paint.invoke(painter, toast, track, state, 0d, 120d, 24d, 2, 1000L, false, -1);
                assertEquals(240, actual.getWidth()); assertEquals(48, actual.getHeight());
                // The note color cycle is independent; compare all background/text pixels to its right.
                assertArrayEquals(expected.getRGB(52, 0, 188, 48, null, 0, 188), actual.getRGB(52, 0, 188, 48, null, 0, 188), label);
                gallery.drawImage(expected, 20, 40 + row * 58, null); gallery.drawImage(actual, 280, 40 + row++ * 58, null);
                double[] bounds = (double[])boundsMethod.invoke(painter, toast, state, 960, 540, 1000L, false);
                assertTrue(bounds[2] >= 120); assertEquals(24, bounds[3]); assertTrue(bounds[0] + bounds[2] <= 960);
                if (label.startsWith("A very")) assertTrue(bounds[2] > 120, "Long titles expand the toast like the system HUD");
            }
            gallery.dispose(); painterType.getMethod("close").invoke(painter);
            Path output = Path.of("target/ui-preview/minecraft-toast-comparison.png"); Files.createDirectories(output.getParent()); ImageIO.write(sheet, "png", output.toFile());
        }
    }
}
