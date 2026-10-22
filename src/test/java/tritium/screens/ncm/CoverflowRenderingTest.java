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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
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
import tritium.rendering.shader.ShaderProgram;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
        List<Runnable> restore = List.of(preferences.appearance(), preferences.interfaceStyle(), preferences.coverflowCoverReflection(),
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
            preferences.coverflowCoverReflection().setValue(true);
            verifyMirrorPixels();
            verifyScreenSpaceDepthHits();

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
            preferences.coverflowCoverReflection().setValue(false);
            render();
            BufferedImage noCoverReflection = capture();
            assertNotEquals(colorAt(dark, .5, .73), colorAt(noCoverReflection, .5, .73),
                    "Disabling cover reflections must remove the reflected artwork");
            assertEquals(colorAt(dark, .5, .4), colorAt(noCoverReflection, .5, .4),
                    "The original cover stays visible");
            assertEquals(colorAt(dark, .1, .73), colorAt(noCoverReflection, .1, .73),
                    "Background reflections are independent of the artwork toggle");
            // Antialiased glyph edges blend with the reflection underneath; compare opaque cores.
            int titlePixels = 0;
            for (int y = (int) (dark.getHeight() * .86); y < dark.getHeight(); y++) {
                for (int x = dark.getWidth() / 3; x < dark.getWidth() * 2 / 3; x++) {
                    if ((dark.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                        assertEquals(dark.getRGB(x, y), noCoverReflection.getRGB(x, y),
                                "The album title keeps the same position and color");
                        titlePixels++;
                    }
                }
            }
            assertTrue(titlePixels > 100, "The title must remain visible with reflections disabled");
            assertEquals(0, brightTextPixels(noCoverReflection, .66, .84), "Text remains excluded from reflections");
            save("coverflow-reflections-disabled");
            preferences.coverflowCoverReflection().setValue(true);
            render();
            assertEquals(colorAt(dark, .5, .73), colorAt(capture(), .5, .73),
                    "Re-enabling reflections must restore the artwork without reopening Coverflow");
            verifyReflectionState();
            measureReflectionFrames();

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
            BufferedImage empty = capture();
            assertEquals(colorAt(empty, .1, .75), colorAt(empty, .5, .75),
                    "Filtering out all albums must also remove pooled reflection geometry");
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
            verifyResourcesReleased();
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

    /** Deliberately give SSR and its planar fallback different colors: a depth hit must win. */
    private void verifyScreenSpaceDepthHits() throws Exception {
        invokeApp("prepareFrameState");
        ShaderProgram shader = new ShaderProgram("coverflow_ssr.frag", "vertex.vsh");
        int[] textures = {GL11.glGenTextures(), GL11.glGenTextures(), GL11.glGenTextures()};
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            for (int unit = 0; unit < 3; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[unit]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                if (unit == 1) {
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
                    uploadDepth(240, false);
                } else {
                    ByteBuffer pixels = BufferUtils.createByteBuffer(16 * 16 * 4);
                    int color = unit == 0 ? 0xDF7028 : 0x275C91;
                    for (int i = 0; i < 256; i++) pixels.put((byte) (color >> 16))
                            .put((byte) (color >> 8)).put((byte) color).put((byte) 255);
                    pixels.flip();
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                }
            }
            shader.start();
            int program = shader.getProgramId();
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "scene"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "depthBuffer"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "planar"), 2);
            float f = (float) (1 / Math.tan(Math.toRadians(22.5)));
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "projectionScale"),
                    f * (float) (RenderSystem.getHeight() / RenderSystem.getWidth()), f);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "clipPlanes"), 1, 3000);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "depthRange"), 200, 280);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "floorY"), -28.118f);

            double w = RenderSystem.getWidth(), h = RenderSystem.getHeight();
            ShaderProgram.drawQuadFlipped(0, 0, w, h);
            assertPixel(capture(), w * .5, h * .75, 0xDF7028, 1);
            assertPixel(capture(), w * .1, h * .75, 0x275C91, 1);

            // Same color buffer, wrong depth: the orange rectangle must no longer be reflected.
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            uploadDepth(100, false);
            ShaderProgram.drawQuadFlipped(0, 0, w, h);
            assertPixel(capture(), w * .5, h * .75, 0x275C91, 1);

            // Empty depth (off-screen/missing geometry) also uses the planar fallback.
            uploadDepth(3000, false);
            ShaderProgram.drawQuadFlipped(0, 0, w, h);
            assertPixel(capture(), w * .5, h * .75, 0x275C91, 1);

            // Foreground geometry at the destination must occlude the mirror.
            uploadDepth(240, true);
            ShaderProgram.drawQuadFlipped(0, 0, w, h);
            assertPixel(capture(), w * .5, h * .75, 0, 1);
        } finally {
            ShaderProgram.stop();
            GL20.glDeleteProgram(shader.getProgramId());
            for (int texture : textures) GL11.glDeleteTextures(texture);
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private static void uploadDepth(float viewDepth, boolean occluder) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(256);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            float z = x >= 5 && x <= 10 && y >= 6 && y <= 11 ? viewDepth : 3000;
            if (occluder && x >= 5 && x <= 10 && y >= 2 && y <= 5) z = 100;
            pixels.put(3000f / 2999f - (3000f / 2999f) / z);
        }
        pixels.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 16, 16, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
    }

    private CoverflowSsr reflections() throws Exception {
        var field = CoverflowOverlay.class.getDeclaredField("coverReflection");
        field.setAccessible(true);
        return (CoverflowSsr) field.get(screen);
    }

    private void verifyReflectionState() throws Exception {
        CoverflowSsr reflections = reflections();
        var failed = CoverflowSsr.class.getDeclaredField("failed");
        failed.setAccessible(true);
        assertFalse(failed.getBoolean(reflections), "Native GL test must exercise SSR, not the compatibility fallback");
        FloatBuffer projection = BufferUtils.createFloatBuffer(16), modelView = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        reflections.draw(.7f);
        assertEquals(GL13.GL_TEXTURE2, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        assertEquals(binding, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        assertEquals(mask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        FloatBuffer actual = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, actual);
        assertEquals(projection, actual);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, actual);
        assertEquals(modelView, actual);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private void measureReflectionFrames() throws Exception {
        long[] timings = new long[24];
        for (int i = 0; i < timings.length; i++) {
            GL11.glFinish();
            long start = System.nanoTime();
            reflections().draw(1);
            GL11.glFinish();
            timings[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(timings);
        System.out.printf(java.util.Locale.ROOT,
                "Coverflow SSR GPU-complete pass at %dx%d: median %.2f ms, p95 %.2f ms%n",
                RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(),
                timings[12] / 1e6, timings[22] / 1e6);
    }

    private void verifyResourcesReleased() throws Exception {
        CoverflowSsr reflections = reflections();
        String[] names = {"scene", "depth", "planar", "planarDepth", "traced"};
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            var field = CoverflowSsr.class.getDeclaredField(names[i]);
            field.setAccessible(true);
            ids[i] = field.getInt(reflections);
            assertTrue(GL11.glIsTexture(ids[i]));
        }
        screen.onGuiClosed();
        for (int id : ids) assertFalse(GL11.glIsTexture(id), "Closing Coverflow must free reflection textures");
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
