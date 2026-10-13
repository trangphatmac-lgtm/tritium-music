package tritium.desktop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.LyricLine;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.SettingsPanel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Real GL tests: backdrop sampling, state isolation, UI input, resizing and lyric controls. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class UiMaterialRenderingTest {
    @TempDir Path temp;
    private NCMScreen screen;

    @Test
    void switchesMaterialsAcrossMainAndLyricsAndPreservesClipping() throws Exception {
        var preferences = DesktopAppState.preferences();
        var original = preferences.toJsonObject();
        User profile = CloudMusic.profile;
        Music playing = CloudMusic.currentlyPlaying;
        Music fixture = null;
        var lyrics = new java.util.ArrayList<>(CloudMusic.lyrics);
        var currentLyric = CloudMusic.currentLyric;
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            verifySamplingAndClip();

            BufferedImage backdrop = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < backdrop.getHeight(); y++) for (int x = 0; x < backdrop.getWidth(); x++) {
                backdrop.setRGB(x, y, ((40 + x / 4) << 16) | ((45 + y / 3) << 8) | (160 - y / 4));
            }
            Path picture = temp.resolve("gradient.png");
            ImageIO.write(backdrop, "png", picture.toFile());
            var bg = preferences.background();
            bg.mode().setValue("picture");
            bg.pictureFile().setValue(System.getProperty("tritium.backgroundSample.picture", picture.toString()));
            bg.strength().setValue(.85);
            bg.panelOpacity().setValue(.6);
            CloudMusic.profile = null;
            screen = new NCMScreen();
            screen.initGui();
            CloudMusic.profile = new User(1, "Tritium Music", "", 0, "");
            SettingsPanel settings = new SettingsPanel();
            screen.setCurrentPanel(settings);
            preferences.interfaceStyle().setValue("Static");
            settle();
            assertTrue(DesktopAppState.background().isActive());
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            var styleRow = scroll.getChildren().get(3);
            scroll.actualScrollOffset = scroll.targetScrollOffset = styleRow.getRelativeY() - 8;
            render();
            for (String expected : new String[]{"Blur", "LiquidGlass", "Static"}) {
                double x = styleRow.getX() + 20, y = styleRow.getY() + 20;
                render(x, y);
                settings.onMouseClickReceived(x, y, 0);
                settings.onMouseReleaseReceived(x, y, 0);
                assertEquals(expected, preferences.interfaceStyle().getValue());
                settle();
                assertFalse(UiMaterials.status().contains("不可用"), UiMaterials.status());
            }
            for (String appearance : new String[]{"Dark", "Light"}) {
                preferences.appearance().setValue(appearance);
                int[] previous = null;
                for (String style : preferences.interfaceStyle().getModes()) {
                    preferences.interfaceStyle().setValue(style);
                    settle();
                    int[] pixels = capture().getRGB(0, 0, 200, 180, null, 0, 200);
                    if (previous != null) assertFalse(Arrays.equals(previous, pixels), "Styles must visibly differ");
                    previous = pixels;
                    save(appearance + "-" + style);
                    assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
                }
            }

            Display.setDisplayMode(new DisplayMode(960, 720));
            MacOpenGLSurface.enableHighResolution();
            Display.update();
            settle();
            assertTrue(UiMaterials.enabled());
            save("LiquidGlass-resized");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

            fixture = new Gson().fromJson("""
                    {"id":-700,"name":"海岸","ar":[],"al":{"id":-700,"name":"Test"},"dt":60000}
                    """, Music.class);
            CloudMusic.currentlyPlaying = fixture;
            TextureManager.getInstance().loadTexture(fixture.getCoverLocation(), new DynamicTexture(backdrop));
            TextureManager.getInstance().loadTexture(fixture.getBlurredCoverLocation(), new DynamicTexture(backdrop));
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "Loading fixture textures");
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.add(new LyricLine(0, "海面映着落日的光"));
            CloudMusic.lyrics.add(new LyricLine(10000, "风掠过透明的窗"));
            CloudMusic.lyrics.add(new LyricLine(20000, "让音乐留在此刻"));
            CloudMusic.currentLyric = CloudMusic.lyrics.getFirst();
            screen.musicLyricsPanel = new MusicLyricsPanel(fixture);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "Creating lyrics panel");
            int[] previousTranslation = null;
            int[] flatControls = null;
            int[] previousHighlight = null;
            for (String style : preferences.interfaceStyle().getModes()) {
                preferences.interfaceStyle().setValue(style);
                render();
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "First lyrics frame " + style);
                settle();
                freezeLyricControls();
                render();
                BufferedImage image = capture();
                // Only translation and hovered lyrics adopt glass. The entire left transport area is identical.
                int[] controls = image.getRGB(0, image.getHeight() / 2, image.getWidth() * 46 / 100, image.getHeight() / 2,
                        null, 0, image.getWidth() * 46 / 100);
                if (flatControls == null) flatControls = controls;
                else assertArrayEquals(flatControls, controls, "Lyrics transport must stay flat: " + style);
                int[] translation = image.getRGB(image.getWidth() * 85 / 100, image.getHeight() * 70 / 100,
                        image.getWidth() / 10, image.getHeight() / 5, null, 0, image.getWidth() / 10);
                if (previousTranslation != null) assertFalse(Arrays.equals(previousTranslation, translation), "Translation button must adopt the material");
                previousTranslation = translation;
                save("lyrics-" + style);
                for (int i = 0; i < 40; i++) render(RenderSystem.getWidth() * .6,
                        CloudMusic.currentLyric.posY + CloudMusic.currentLyric.height * .5);
                BufferedImage hovered = capture();
                int[] highlight = hovered.getRGB(hovered.getWidth() / 2, 0, hovered.getWidth() / 2,
                        hovered.getHeight() / 2, null, 0, hovered.getWidth() / 2);
                if (previousHighlight != null) assertFalse(Arrays.equals(previousHighlight, highlight), "Lyric hover must adopt the material");
                previousHighlight = highlight;
                save("lyrics-" + style + "-hover");
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "Settled lyrics " + style);
            }
            // Switching back releases material resources and restores the flat renderer.
            screen.musicLyricsPanel = null;
            preferences.interfaceStyle().setValue("Static");
            settle();
            assertFalse(UiMaterials.enabled());
        } finally {
            CloudMusic.profile = profile;
            CloudMusic.currentlyPlaying = playing;
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.addAll(lyrics);
            CloudMusic.currentLyric = currentLyric;
            preferences.loadFromJsonObject(original);
            if (Display.isCreated()) {
                UiMaterials.release();
                if (fixture != null) {
                    TextureManager.getInstance().deleteTexture(fixture.getCoverLocation());
                    TextureManager.getInstance().deleteTexture(fixture.getBlurredCoverLocation());
                }
                DesktopAppState.background().reload();
                if (screen != null) screen.onGuiClosed();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private void freezeLyricControls() throws Exception {
        // Remove subpixel entrance-animation drift from the cross-style pixel comparison.
        for (String name : new String[]{"alpha", "musicBgAlpha", "coverAlpha"}) {
            var field = MusicLyricsPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setFloat(screen.musicLyricsPanel, 1f);
        }
        for (String name : new String[]{"coverSize", "progressBarHeight", "volumeBarHeight"}) {
            var field = MusicLyricsPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            double value = name.equals("coverSize") ? RenderSystem.getHeight() * .4 : 5;
            if (field.getType() == float.class) field.setFloat(screen.musicLyricsPanel, (float) value);
            else field.setDouble(screen.musicLyricsPanel, value);
        }
    }

    private void verifySamplingAndClip() throws Exception {
        double[] contrast = new double[2];
        int index = 0;
        for (String style : new String[]{"Blur", "LiquidGlass"}) {
            DesktopAppState.preferences().interfaceStyle().setValue(style);
            prepare();
            UiMaterials.beginFrame(30, 30);
            for (int x = 0; x < RenderSystem.getWidth(); x += 6) {
                Rect.draw(x, 0, 6, RenderSystem.getHeight(), (x / 6) % 2 == 0 ? 0xFF184878 : 0xFFDAAE82);
            }
            int beforeOutside = pixel(220, 80);
            StencilClipManager.beginClip(() -> Rect.draw(20, 20, 180, 140, -1));
            int framebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
            UiMaterials.captureBackdrop(false);
            assertTrue(UiMaterials.enabled(), UiMaterials.status());
            assertTrue(GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
            assertEquals(framebuffer, GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT));
            UiMaterials.surface(30, 30, 240, 100, 18, 0xFF303030);
            StencilClipManager.endClip();
            assertEquals(beforeOutside, pixel(220, 80), "Glass must not escape its scrolling stencil");
            int min = 255, max = 0;
            for (int x = 65; x < 165; x++) {
                int red = (pixel(x, 80) >> 16) & 255;
                min = Math.min(min, red); max = Math.max(max, red);
            }
            contrast[index++] = max - min;
            save("sampling-" + style);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
        assertTrue(contrast[0] < contrast[1], "Frosted glass should diffuse more detail than liquid glass: " + Arrays.toString(contrast));
        assertTrue(contrast[1] > 5, "Liquid glass must sample the scene rather than paint a solid fill");
    }

    private static int pixel(int x, int y) {
        float scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        return ((pixel.get(0) & 255) << 16) | ((pixel.get(1) & 255) << 8) | (pixel.get(2) & 255);
    }

    private static void prepare() throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
    }

    private void render() throws Exception { render(-1, -1); }
    private void render(double x, double y) throws Exception {
        prepare();
        screen.drawScreen((int) x, (int) y);
        Display.processMessages();
        Thread.sleep(12);
    }
    private void settle() throws Exception { for (int i = 0; i < 55; i++) render(); }

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
        Path directory = Path.of("target/material-previews");
        Files.createDirectories(directory);
        ImageIO.write(capture(), "png", directory.resolve(name + ".png").toFile());
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
