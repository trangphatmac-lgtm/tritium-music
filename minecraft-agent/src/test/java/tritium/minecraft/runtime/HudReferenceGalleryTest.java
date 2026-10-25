package tritium.minecraft.runtime;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** Headless composition previews, not Minecraft/Forge/Lunar acceptance. */
class HudReferenceGalleryTest {
    @Test void exportsReferenceComparison() throws Exception {
        BufferedImage sheet = new BufferedImage(1920, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics(); g.setColor(new Color(0x25262b)); g.fillRect(0, 0, 1920, 1100);
        g.setFont(new Font("SansSerif", Font.BOLD, 26)); g.setColor(Color.WHITE);
        Path oldJar = Paths.get("../target/minecraft-before-agent.jar");
        if (Files.exists(oldJar)) {
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{oldJar.toUri().toURL()}, ClassLoader.getSystemClassLoader().getParent())) {
                g.drawString("Before", 30, 40); drawColumn(g, 30, new Renderer(loader));
            }
        } else g.drawString("Reference geometry: 230 x 56 / 450 x 120 / gain 160", 30, 40);
        g.drawString("Updated", 990, 40); drawColumn(g, 990, new Renderer()); g.dispose();
        Path file = Paths.get("target/ui-preview/minecraft-hud-fidelity.png"); Files.createDirectories(file.getParent());
        ImageIO.write(sheet, "png", file.toFile()); assertTrue(Files.size(file) > 10000);
    }

    private void drawColumn(Graphics2D g, int x, Renderer renderer) throws Exception {
        HudConfig config = new HudConfig(); PlaybackState.Track track = HudReferenceTest.track();
        PlaybackState state = new PlaybackState(); state.playing = true; state.durationMillis = 180000; state.positionMillis = 43000;
        state.volume = .35; state.spectrum = new float[778];
        for (int i = 0; i < state.spectrum.length; i++) state.spectrum[i] = (float)(.12 + .10 * Math.sin(i * .05) + .04 * Math.cos(i * .13));
        BufferedImage cover = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB); Graphics2D art = cover.createGraphics();
        art.setPaint(new GradientPaint(0, 0, new Color(0xe9a4a0), 200, 256, new Color(0x273c6b))); art.fillRect(0, 0, 256, 256);
        art.setColor(new Color(0xffe8b3)); art.fillOval(135, 25, 85, 85); art.setColor(new Color(0x243853)); art.fillPolygon(new int[]{0, 70, 125, 195, 256, 256, 0}, new int[]{220, 115, 195, 145, 200, 256, 256}, 7); art.dispose();
        renderer.artwork(cover);
        HudConfig.Module info = config.find("musicInfo");
        for (long now = 1000; now <= 3000; now += 20) renderer.paint(info, track, state, 43000, now, false);
        g.drawImage(renderer.paint(info, track, state, 43000, 3020, false), x - renderer.pad(info) * 2, 90 - renderer.pad(info) * 2, null);
        state.downloading = true; state.downloadProgress = .62; state.downloadSpeed = "1.8 MB/s";
        for (long now = 3040; now <= 5000; now += 20) renderer.paint(info, track, state, 43000, now, false);
        g.drawImage(renderer.paint(info, track, state, 43000, 5020, false), x - renderer.pad(info) * 2, 290 - renderer.pad(info) * 2, null);
        HudConfig.Module lyrics = config.find("musicLyrics");
        for (long now = 1000; now < 2400; now += 20) renderer.paint(lyrics, track, state, now, now, false);
        g.drawImage(renderer.paint(lyrics, track, state, 2400, 2400, false), x, 540, null);
        HudConfig.Module spectrum = config.find("musicSpectrum"); spectrum.width = 450; spectrum.height = 100;
        for (long now = 1000; now < 3000; now += 20) renderer.paint(spectrum, track, state, 43000, now, false);
        g.drawImage(renderer.paint(spectrum, track, state, 43000, 3000, false), x, 840, null);
        renderer.close();
    }
    private static final class Renderer {
        final HudPainter current;
        final Object old;
        final Method paint, artwork, close;
        final Class<?> moduleClass, trackClass, stateClass;
        final Object oldJson;
        final Method fromJson;
        final Gson json = new Gson();
        Renderer() { current = new HudPainter(); old = oldJson = null; paint = artwork = close = fromJson = null; moduleClass = trackClass = stateClass = null; }
        Renderer(ClassLoader loader) throws Exception {
            current = null; Class<?> type = loader.loadClass("tritium.minecraft.runtime.HudPainter"); old = type.getConstructor().newInstance();
            moduleClass = loader.loadClass("tritium.minecraft.protocol.HudConfig$Module"); trackClass = loader.loadClass("tritium.minecraft.protocol.PlaybackState$Track"); stateClass = loader.loadClass("tritium.minecraft.protocol.PlaybackState");
            paint = type.getMethod("paint", moduleClass, trackClass, stateClass, double.class, double.class, double.class, int.class, long.class, boolean.class, int.class);
            artwork = type.getMethod("artwork", BufferedImage.class); close = type.getMethod("close");
            Class<?> gsonClass = loader.loadClass("tritium.minecraft.internal.gson.Gson"); oldJson = gsonClass.getConstructor().newInstance(); fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
        }
        BufferedImage paint(HudConfig.Module module, PlaybackState.Track track, PlaybackState state, double position, long now, boolean preview) throws Exception {
            if (current != null) {
                double[] bounds = current.bounds(module, state, 960, 540, now, preview);
                return current.paint(module, track, state, position, bounds[2], bounds[3], 2, now, preview, -1);
            }
            return (BufferedImage)paint.invoke(old, fromJson.invoke(oldJson, json.toJson(module), moduleClass), fromJson.invoke(oldJson, json.toJson(track), trackClass),
                    fromJson.invoke(oldJson, json.toJson(state), stateClass), position, module.width, module.height, 2, now, preview, -1);
        }
        void artwork(BufferedImage image) throws Exception { if (current != null) current.artwork(image); else artwork.invoke(old, image); }
        int pad(HudConfig.Module module) { return current == null ? 0 : (int)HudPainter.padding(module); }
        void close() throws Exception { if (current != null) current.close(); else close.invoke(old); }
    }
}
