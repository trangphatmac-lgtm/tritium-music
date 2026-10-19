package tritium.screens.ncm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.dto.Album;
import tritium.ncm.music.dto.Music;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.TextureManager;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class CoverflowRenderingTest {
    @TempDir Path temp;
    private CoverflowOverlay screen;

    @Test
    void reflectsScenePixelsAndSharesLiveBackgroundAcrossModesAndResizes() throws Exception {
        var preferences = DesktopAppState.preferences();
        var background = preferences.background();
        List<Runnable> restore = List.of(preferences.appearance(), preferences.interfaceStyle(),
                        background.mode(), background.pictureFile(), background.strength(),
                        background.shader(), background.videoFile()).stream()
                .map(CoverflowRenderingTest::restorePreference).toList();
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            preferences.appearance().setValue("Dark");
            preferences.interfaceStyle().setValue("Static");
            verifyMirrorPixels();

            screen = new CoverflowOverlay();
            String[] titles = {"天亮以前说再见", "红尘之客", "Payphone", "午后散步"};
            for (int i = 0; i < titles.length; i++) {
                Album album = new Album(-810 - i, titles[i], "", List.of());
                Music music = new Gson().fromJson("{\"id\":-1,\"name\":\"测试歌曲\",\"dt\":180000}", Music.class);
                screen.albumList.put(album, List.of(music));
                screen.renderList.add(album);
                TextureManager.getInstance().loadTexture(album.getCoverLocation(), new DynamicTexture(artwork(i)));
            }
            screen.initGui();
            background.mode().setValue("static");
            settle();
            assertPixel(capture(), 3, RenderSystem.getHeight() * .3, 0x1E1E1E, 1);
            BufferedImage dark = capture();
            assertTrue(brightTextPixels(dark, .86, 1) > 100, "The album title remains visible below the reflection");
            assertEquals(0, brightTextPixels(dark, .66, .84), "The album title must not appear in the reflection");
            save("coverflow-dark");

            BufferedImage picture = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                picture.setRGB(x, y, 0x285080);
            Path path = temp.resolve("background.png");
            ImageIO.write(picture, "png", path.toFile());
            background.pictureFile().setValue(path.toString());
            background.strength().setValue(1.0);
            background.mode().setValue("picture");
            awaitBackground();
            settle();
            assertPixel(capture(), 3, RenderSystem.getHeight() * .3, 0x285080, 1);
            save("coverflow-picture");
            String sample = System.getProperty("tritium.backgroundSample.picture");
            if (sample != null) {
                background.pictureFile().setValue(sample);
                background.strength().setValue(.63);
                awaitBackground();
                settle();
                save("coverflow-photo-floor");
                background.pictureFile().setValue(path.toString());
                background.strength().setValue(1.0);
                awaitBackground();
            }
            screen.keyTyped('\0', Keyboard.KEY_RIGHT);
            settle();
            assertEquals(1, screen.index);
            screen.filterAlbums("no matching album");
            render();
            assertTrue(screen.renderList.isEmpty());
            assertPixel(capture(), 3, RenderSystem.getHeight() * .3, 0x285080, 1);
            screen.filterAlbums("");
            settle();

            background.mode().setValue("shader");
            background.shader().setValue("aurora");
            preferences.interfaceStyle().setValue("LiquidGlass");
            settle();
            assertTrue(DesktopAppState.background().isActive());
            BufferedImage first = capture();
            Thread.sleep(100);
            render();
            assertTrue(changedPixels(first, capture(), 0, 60, 150, 100) > 100,
                    "The shared shader must keep animating in Coverflow");
            save("coverflow-shader");

            // The flipped track list uses stencil clipping and must also survive the capture pass.
            Object data = screen.albumRenderingData.get(screen.renderList.get(screen.index));
            var flipped = data.getClass().getDeclaredField("flipped");
            flipped.setAccessible(true);
            flipped.setBoolean(data, true);
            settle();
            save("coverflow-flipped");
            screen.keyTyped('\0', Keyboard.KEY_ESCAPE);
            assertFalse(flipped.getBoolean(data));

            background.mode().setValue("video");
            background.videoFile().setValue(Path.of(getClass().getResource("/backgrounds/loop.mp4").toURI()).toString());
            awaitBackground();
            settle();
            first = capture();
            Thread.sleep(150);
            render();
            assertTrue(changedPixels(first, capture(), 0, 60, 150, 100) > 100,
                    "The shared video must continue playing in Coverflow");
            save("coverflow-video");

            Display.setDisplayMode(new DisplayMode(920, 680));
            var highResolution = Class.forName("tritium.desktop.MacOpenGLSurface").getDeclaredMethod("enableHighResolution");
            highResolution.setAccessible(true);
            highResolution.invoke(null);
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            verifyMirrorPixels();
            background.mode().setValue("static");
            preferences.appearance().setValue("Light");
            settle();
            assertPixel(capture(), 3, RenderSystem.getHeight() * .3, 0xFFFFFF, 2);
            save("coverflow-light-resized");
            screen.onGuiClosed();
            screen.initGui();
            settle();
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            restore.forEach(Runnable::run);
            if (Display.isCreated()) {
                if (screen != null) screen.onGuiClosed();
                DesktopAppState.background().reload();
                UiMaterials.release();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static <T> Runnable restorePreference(PreferenceValue<T> preference) {
        T original = preference.getValue();
        return () -> preference.setValue(original);
    }

    private void verifyMirrorPixels() throws Exception {
        invokeApp("prepareFrameState");
        double width = RenderSystem.getWidth(), height = RenderSystem.getHeight();
        // A colored upper scene and a conspicuous lower wallpaper distinguish a solid floor
        // from the old translucent double exposure. The orange cover touches the mirror edge.
        Rect.draw(0, 0, width, height * .5, 0xFF275C91);
        Rect.draw(width * .3, height * .2, width * .4, height * .3, 0xFFDF7028);
        Rect.draw(0, height * .5, width, height * .5, 0xFFFF00FF);
        BufferedImage source = capture();
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (CoverflowReflection reflection = new CoverflowReflection()) {
            reflection.draw(height * .5, 1f);
            assertEquals(depthMask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertEquals(binding, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            BufferedImage result = capture();
            assertPixel(result, width * .5, height * .4, 0xDF7028, 1);
            assertPixel(result, width * .1, height * .4, 0x275C91, 1);
            Color contact = colorAt(result, .5, .51);
            Color reflectedCover = colorAt(result, .5, .61);
            Color beyondCover = colorAt(result, .5, .75);
            assertTrue(contact.getRed() > contact.getBlue() * 2, "Cover reflection must begin at the contact edge");
            assertTrue(reflectedCover.getRed() > reflectedCover.getBlue() * 2, "The floor must reflect the cover");
            assertTrue(beyondCover.getBlue() > beyondCover.getRed(), "Foreshortening must fit the reflected cover onto the floor");
            Color environment = colorAt(result, .1, .6);
            assertTrue(environment.getBlue() > environment.getRed() * 2, "The background must reflect in the same surface");
            Color bottom = colorAt(result, .1, .995);
            assertTrue(bottom.getRed() < 40 && bottom.getBlue() < 50, "Lower wallpaper must not bleed through the floor");

            // Changing only the wallpaper underneath the plane cannot change its reflection.
            Rect.draw(0, 0, width, height * .5, 0xFF275C91);
            Rect.draw(width * .3, height * .2, width * .4, height * .3, 0xFFDF7028);
            Rect.draw(0, height * .5, width, height * .5, 0xFF00FF00);
            reflection.draw(height * .5, 1f);
            BufferedImage replaced = capture();
            assertEquals(0, changedPixels(result, replaced, 0, result.getHeight() / 2,
                    result.getWidth(), result.getHeight() / 2), "The floor must have its own opaque material");
            assertEquals(source.getRGB(10, 10), replaced.getRGB(10, 10), "The upper scene stays unchanged");
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private void render() throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        RenderSystem.setFrameDeltaTime(2);
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen(-1, -1);
        Display.processMessages();
        Thread.sleep(15);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private void settle() throws Exception { for (int i = 0; i < 65; i++) render(); }

    private void awaitBackground() throws Exception {
        // Render at least once so changing modes cannot reuse the old active flag.
        long end = System.nanoTime() + 15_000_000_000L;
        do {
            render();
            if (DesktopAppState.background().isActive()) return;
        } while (System.nanoTime() < end);
        fail(DesktopAppState.background().status());
    }

    private static BufferedImage artwork(int seed) {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(seed * .22f + .08f, .45f, .9f),
                256, 256, Color.getHSBColor(seed * .22f + .14f, .8f, .3f)));
        g.fillRect(0, 0, 256, 256);
        g.setColor(new Color(255, 255, 255, 120));
        g.fillOval(60, 35, 136, 136);
        g.setColor(new Color(255, 255, 255, 180));
        g.fillRect(35, 205, 186, 3);
        g.fillRect(35, 222, 90, 2);
        g.dispose();
        return image;
    }

    private static int changedPixels(BufferedImage first, BufferedImage second, int x, int y, int width, int height) {
        int changed = 0;
        for (int row = y; row < y + height; row++) for (int col = x; col < x + width; col++)
            if (first.getRGB(col, row) != second.getRGB(col, row)) changed++;
        return changed;
    }

    private static int brightTextPixels(BufferedImage image, double top, double bottom) {
        int count = 0;
        for (int y = (int) (image.getHeight() * top); y < image.getHeight() * bottom; y++) {
            for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                if (r > 150 && Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2) count++;
            }
        }
        return count;
    }

    private static Color colorAt(BufferedImage image, double x, double y) {
        return new Color(image.getRGB((int) (x * image.getWidth()), (int) (y * image.getHeight())));
    }

    private static void assertPixel(BufferedImage image, double x, double y, int rgb, int tolerance) {
        int actual = image.getRGB((int) (x * image.getWidth() / RenderSystem.getWidth()),
                (int) (y * image.getHeight() / RenderSystem.getHeight()));
        for (int shift : new int[]{0, 8, 16})
            assertEquals((rgb >> shift) & 255, (actual >> shift) & 255, tolerance);
    }

    private static BufferedImage capture() {
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        ByteBuffer rgba = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - y - 1) * w + x) * 4;
            image.setRGB(x, y, ((rgba.get(i) & 255) << 16) | ((rgba.get(i + 1) & 255) << 8) | (rgba.get(i + 2) & 255));
        }
        return image;
    }

    private static void save(String name) throws Exception {
        Path directory = Path.of("target/ui-preview");
        Files.createDirectories(directory);
        ImageIO.write(capture(), "png", directory.resolve(name + ".png").toFile());
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
