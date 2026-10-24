package tritium.desktop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import tritium.desktop.background.latent.LatentPreferences;
import tritium.desktop.hud.HudArtworkCache;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.rendering.Framebuffer;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.ColorPickerWidget;
import tritium.screens.ncm.panels.SettingsPanel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Actual GLSL compilation, framebuffer composition, palette switching and settings input. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class LatentRenderingTest {
    private SettingsPanel settings;
    private boolean showSettings;

    @Test
    void rendersBothPalettesCoverColorsAndSettingsWithoutLeakingGlState() throws Exception {
        var preferences = DesktopAppState.preferences();
        var original = preferences.toJsonObject();
        Music originalMusic = CloudMusic.currentlyPlaying;
        var bg = preferences.background();
        var latent = bg.latent();
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            preferences.interfaceStyle().setValue("Static");
            preferences.appearance().setValue("Dark");
            bg.mode().setValue("latent");
            bg.strength().setValue(.65);
            latent.colorSource().setValue("theme");
            CloudMusic.currentlyPlaying = null;
            settings = new SettingsPanel();
            settings.setBounds(0, 0, Math.min(850, RenderSystem.getWidth()), RenderSystem.getHeight());
            settings.onInit();
            settle();
            assertTrue(DesktopAppState.background().isActive(), DesktopAppState.background().status());
            BufferedImage dark = capture();
            assertTrue(variation(dark) > 100);
            save(dark, "dark");
            preferences.appearance().setValue("Light");
            settle();
            BufferedImage light = capture();
            assertTrue(brightness(light) > brightness(dark) + 35, "Light mode must change the full compositor palette");
            assertEquals(0xFF1C1917, AppTheme.foreground());
            assertEquals(0xFFEA580C, AppTheme.accent());
            save(light, "light");
            for (String layer : latent.layers().getModes()) {
                latent.layers().setValue(layer);
                frame();
                assertTrue(variation(capture()) > 2, layer);
                save(capture(), "layer-" + layer);
            }
            latent.layers().setValue("both");
            // Artwork arrives after song metadata, as it does during normal playback.
            CloudMusic.currentlyPlaying = new Gson().fromJson("""
                    {"id":987654321,"name":"Latent test","ar":[],"al":{"id":1,"name":"Test"},"dt":60000}
                    """, Music.class);
            latent.colorSource().setValue("cover-only");
            settle();
            BufferedImage cover = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
            int[] colors = {0x5A246E, 0xAD5D5D, 0xD59C73, 0xE2C5AA, 0x627784, 0xBEC5C9};
            for (int y = 0; y < 60; y++) for (int x = 0; x < 60; x++) cover.setRGB(x, y, colors[x / 10]);
            HudArtworkCache.put(987654321, HudArtworkCache.ArtworkType.COVER, cover);
            settle();
            BufferedImage fromCover = capture();
            assertTrue(difference(light, fromCover) > 15, "Late artwork must update the rendered palette");
            save(fromCover, "cover-only");
            latent.colorSource().setValue("cover-theme");
            settle();
            save(capture(), "cover-theme");

            showSettings = true;
            frame();
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            AbstractWidget<?> colorRow = row(scroll, "settings.latent.ACCENT");
            scroll.actualScrollOffset = scroll.targetScrollOffset = (colorRow.getY() - scroll.getY()) - 8;
            settle();
            click(colorRow.getX() + 68, colorRow.getY() + 61);
            assertEquals(0xFF58A6FF, latent.color(true, LatentPreferences.Color.ACCENT).getValue());
            assertEquals(0xFFF4F4F5, latent.color(false, LatentPreferences.Color.ACCENT).getValue());
            click(colorRow.getX() + colorRow.getWidth() - 40, colorRow.getY() + 20);
            settle();
            ColorPickerWidget picker = (ColorPickerWidget) colorRow.getChildren().getFirst();
            assertFalse(picker.isHidden());
            BufferedImage settingsLight = capture();
            assertTitleText(settingsLight, AppTheme.foreground());
            save(settingsLight, "settings-light-picker");
            preferences.appearance().setValue("Dark");
            settle();
            click(colorRow.getX() + 108, colorRow.getY() + 61);
            assertEquals(0xFFA78BFA, latent.color(false, LatentPreferences.Color.ACCENT).getValue());
            assertEquals(0xFF58A6FF, latent.color(true, LatentPreferences.Color.ACCENT).getValue());
            settings.setWidth(440);
            settle();
            assertTrue(picker.getWidth() > 0 && picker.getWidth() < colorRow.getWidth());
            save(capture(), "settings-dark-narrow");

            showSettings = false;
            Display.setDisplayMode(new DisplayMode(920, 680));
            MacOpenGLSurface.enableHighResolution();
            Display.update();
            settle();
            save(capture(), "resized");
            assertTrue(DesktopAppState.background().isActive());
            bg.mode().setValue("static");
            frame();
            assertFalse(DesktopAppState.background().isActive());
            bg.mode().setValue("latent");
            frame();
            DesktopAppState.background().reload();
            frame();
            assertTrue(DesktopAppState.background().isActive());
            DesktopAppState.background().render(false);
            assertFalse(DesktopAppState.background().isActive());
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            CloudMusic.currentlyPlaying = originalMusic;
            preferences.loadFromJsonObject(original);
            if (Display.isCreated()) {
                DesktopAppState.background().reload();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private void frame() throws Exception {
        frame(-1, -1);
    }

    private void frame(double mouseX, double mouseY) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        int fbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        DesktopAppState.background().render(true);
        assertEquals(fbo, GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT));
        if (showSettings) settings.renderWidget(mouseX, mouseY, 0);
        Display.processMessages();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        Thread.sleep(12);
    }
    private void settle() throws Exception { for (int i = 0; i < 90; i++) frame(); }
    private void click(double x, double y) throws Exception {
        frame(x, y);
        settings.onMouseClickReceived(x, y, 0);
        settings.onMouseReleaseReceived(x, y, 0);
        frame();
    }
    private static AbstractWidget<?> row(ScrollPanel scroll, String label) throws Exception {
        for (AbstractWidget<?> row : scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).toList()) {
            try {
                var field = row.getClass().getSuperclass().getDeclaredField("label");
                field.setAccessible(true);
                if (label.equals(field.get(row))) return row;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("Missing settings row: " + label);
    }
    private static long variation(BufferedImage image) {
        return Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth())).distinct().count();
    }
    private static double brightness(BufferedImage image) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 10) for (int x = 0; x < image.getWidth(); x += 10) {
            int c = image.getRGB(x, y);
            sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255); count += 3;
        }
        return sum / (double) count;
    }
    private static void assertTitleText(BufferedImage image, int color) {
        float scale = RenderSystem.getUiScaleFactor();
        int count = 0;
        for (int y = (int) (24 * scale); y < 65 * scale; y++)
            for (int x = (int) (24 * scale); x < 120 * scale; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == (color & 0xFFFFFF)) count++;
            }
        assertTrue(count > 30, "Fonts must remain visible after the background compositor restores GL state");
    }
    private static double difference(BufferedImage a, BufferedImage b) {
        long sum = 0; int count = 0;
        for (int y = 0; y < a.getHeight(); y += 10) for (int x = 0; x < a.getWidth(); x += 10)
            for (int shift : new int[]{0, 8, 16}) {
                sum += Math.abs(((a.getRGB(x, y) >> shift) & 255) - ((b.getRGB(x, y) >> shift) & 255)); count++;
            }
        return sum / (double) count;
    }
    private static BufferedImage capture() {
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - y - 1) * w + x) * 4;
            image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
        }
        return image;
    }
    private static void save(BufferedImage image, String name) throws Exception {
        Path path = Path.of("target/latent-previews", name + ".png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }
    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
