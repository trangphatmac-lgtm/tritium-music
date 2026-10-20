package tritium.desktop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.management.FontManager;
import tritium.desktop.background.BackgroundFilePicker;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.LyricLine;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.SettingsPanel;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class BackgroundRenderingTest {
    @TempDir Path temp;
    private NCMScreen screen;
    private Music lyricsFixture;

    @Test
    void rendersAllModesThroughTheRealMainWindowAndSupportsOptionalLyricsBackground() throws Exception {
        var original = DesktopAppState.preferences().toJsonObject();
        User profile = CloudMusic.profile;
        Music playing = CloudMusic.currentlyPlaying;
        var originalLyrics = new java.util.ArrayList<>(CloudMusic.lyrics);
        LyricLine originalCurrentLyric = CloudMusic.currentLyric;
        boolean originalHasTranslation = CloudMusic.hasTransLyrics;
        BackgroundPreferences bg = DesktopAppState.preferences().background();
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            DesktopAppState.preferences().appearance().setValue("Dark");
            CloudMusic.profile = null;
            screen = new NCMScreen();
            screen.initGui();
            CloudMusic.profile = new User(1, "Tritium Music", "", 0, "");
            SettingsPanel settings = new SettingsPanel();
            screen.setCurrentPanel(settings);
            bg.mode().setValue("static");
            settle();
            assertPixel(RenderSystem.getWidth() - 3, 150, 30, 30, 30);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            AbstractWidget<?> modeRow = preferenceRow(scroll, bg.mode());
            AbstractWidget<?> lyricsToggle = preferenceRow(scroll, bg.applyToLyrics());
            AbstractWidget<?> lyricsTransparency = preferenceRow(scroll, bg.lyricsTransparency());
            assertTrue(lyricsToggle.isHidden(), "Solid color has no lyrics-background option");
            assertTrue(lyricsTransparency.isHidden());
            scroll.actualScrollOffset = scroll.targetScrollOffset = modeRow.getRelativeY() - 8;
            render();
            render(modeRow.getX() + 20, modeRow.getY() + 20);
            settings.onMouseClickReceived(modeRow.getX() + 20, modeRow.getY() + 20, 0);
            settings.onMouseReleaseReceived(modeRow.getX() + 20, modeRow.getY() + 20, 0);
            assertEquals("picture", bg.mode().getValue(), "Background mode is reachable through settings input");
            render();
            assertFalse(lyricsToggle.isHidden());
            assertTrue(lyricsTransparency.isHidden(), "Transparency is hidden until lyrics backgrounds are enabled");
            render(lyricsToggle.getX() + 20, lyricsToggle.getY() + 20);
            settings.onMouseClickReceived(lyricsToggle.getX() + 20, lyricsToggle.getY() + 20, 0);
            settings.onMouseReleaseReceived(lyricsToggle.getX() + 20, lyricsToggle.getY() + 20, 0);
            assertTrue(bg.applyToLyrics().getValue(), "Lyrics-background toggle is reachable through settings input");
            render();
            assertFalse(lyricsTransparency.isHidden());
            bg.applyToLyrics().setValue(false);

            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++) image.setRGB(x, y, 0x285080);
            Path picture = temp.resolve("test.png");
            ImageIO.write(image, "png", picture.toFile());
            verifyFilePicker(picture, bg);
            bg.strength().setValue(1.0);
            bg.panelOpacity().setValue(0.0);
            bg.pictureFile().setValue(picture.toString());
            await(() -> DesktopAppState.background().isActive());
            settle();
            assertPixel(3, 200, 40, 80, 128);
            assertPixel(RenderSystem.getWidth() - 3, 200, 40, 80, 128);
            assertPixel(RenderSystem.getWidth() - 3, RenderSystem.getHeight() - 3, 40, 80, 128);
            save("picture-main");
            // An invalid source must remove the old texture, retain the selected mode and show an error.
            bg.pictureFile().setValue(temp.resolve("missing.png").toString());
            await(() -> DesktopAppState.background().status().contains("已恢复纯色"));
            render();
            assertFalse(DesktopAppState.background().isActive());
            assertPixel(RenderSystem.getWidth() - 3, 200, 30, 30, 30);

            for (String theme : bg.shader().getModes()) {
                bg.mode().setValue("shader");
                bg.shader().setValue(theme);
                settle();
                assertTrue(DesktopAppState.background().isActive(), DesktopAppState.background().status());
                assertTrue(Arrays.stream(capture().getRGB(0, 0, 200, 150, null, 0, 200)).distinct().count() > 40);
                save("shader-" + theme);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            }

            bg.mode().setValue("video");
            bg.videoFile().setValue(Path.of(getClass().getResource("/backgrounds/loop.mp4").toURI()).toString());
            await(() -> DesktopAppState.background().isActive());
            settle();
            save("video-main");
            Display.setDisplayMode(new DisplayMode(920, 680));
            MacOpenGLSurface.enableHighResolution();
            Display.update();
            settle();
            assertTrue(DesktopAppState.background().isActive());
            bg.mode().setValue("picture");
            bg.pictureFile().setValue(picture.toString());
            await(() -> DesktopAppState.background().isActive());
            assertPixel(RenderSystem.getWidth() - 3, 40, 40, 80, 128);
            bg.mode().setValue("video");
            await(() -> DesktopAppState.background().isActive());
            save("video-resized");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

            // Exercise the real lyrics renderer with a fixed song and no network or audio device.
            CloudMusic.currentlyPlaying = new Gson().fromJson("""
                    {"id":-500,"name":"Background isolation","ar":[],"al":{"id":-500,"name":"Test"},"dt":60000}
                    """, Music.class);
            lyricsFixture = CloudMusic.currentlyPlaying;
            BufferedImage lyricBackdrop = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) lyricBackdrop.setRGB(x, y, 0xC02090);
            TextureManager.getInstance().loadTexture(lyricsFixture.getBlurredCoverLocation(), new DynamicTexture(lyricBackdrop));
            screen.musicLyricsPanel = new MusicLyricsPanel(CloudMusic.currentlyPlaying);
            var alpha = MusicLyricsPanel.class.getDeclaredField("alpha");
            alpha.setAccessible(true);
            alpha.setFloat(screen.musicLyricsPanel, 1f);
            settle();
            assertFalse(DesktopAppState.background().isActive());
            int[] baseline = capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth());
            for (String mode : new String[]{"static", "picture", "shader", "latent"}) {
                bg.mode().setValue(mode);
                settle();
                int[] current = capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth());
                assertArrayEquals(baseline, current, "Background mode must not change lyrics pixels: " + mode);
            }
            save("lyrics-unchanged");
            bg.applyToLyrics().setValue(true);
            for (String mode : new String[]{"picture", "video", "shader", "latent"}) {
                bg.mode().setValue(mode);
                await(() -> DesktopAppState.background().isActive());
                settle();
                assertTrue(DesktopAppState.background().isAppliedToLyrics(), mode);
                int[] current = capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth());
                assertFalse(Arrays.equals(baseline, current), "Selected background must replace the cover: " + mode);
                if (mode.equals("picture")) {
                    assertPixel(RenderSystem.getWidth() - 3, 150, 26, 52, 83);
                    bg.lyricsTransparency().setValue(0.0);
                    render();
                    assertPixel(RenderSystem.getWidth() - 3, 150, 40, 80, 128);
                    bg.lyricsTransparency().setValue(.5);
                    render();
                    assertPixel(RenderSystem.getWidth() - 3, 150, 20, 40, 64);
                    bg.lyricsTransparency().setValue(1.0);
                    render();
                    assertPixel(RenderSystem.getWidth() - 3, 150, 0, 0, 0);
                    bg.lyricsTransparency().setValue(.35);
                    render();
                }
                save("lyrics-background-" + mode);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            }
            // Exercise focus transitions with real text, translation, and partially sung words.
            BufferedImage striped = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++)
                striped.setRGB(x, y, x % 8 < 4 ? 0x204080 : 0x80C040);
            Path stripedPicture = temp.resolve("striped.png");
            ImageIO.write(striped, "png", stripedPicture.toFile());
            bg.mode().setValue("picture");
            bg.pictureFile().setValue(stripedPicture.toString());
            bg.lyricsTransparency().setValue(0.0);
            LyricLine currentLine = new LyricLine(0, "Current lyric");
            currentLine.words.add(new LyricLine.Word("Current lyric", -500, 1000));
            currentLine.translationText = "当前歌词保持清晰";
            CloudMusic.hasTransLyrics = true;
            DesktopAppState.preferences().showTranslation().setValue(true);
            DesktopAppState.preferences().showRoman().setValue(false);
            LyricLine nextLine = new LyricLine(2000, "Other lyrics soften");
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.add(currentLine);
            CloudMusic.lyrics.add(nextLine);
            CloudMusic.currentLyric = currentLine;
            MusicLyricsPanel.updateLyricPositionsImmediate(screen.getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
            await(() -> DesktopAppState.background().isActive());
            for (int i = 0; i < 90; i++) render(RenderSystem.getWidth() * .75, 2);
            int[] focusedBackground = capture().getRGB(600, 30, 200, 50, null, 0, 200);
            save("lyrics-focused");
            settle();
            int[] blurredBackground = capture().getRGB(600, 30, 200, 50, null, 0, 200);
            assertFalse(Arrays.equals(focusedBackground, blurredBackground), "Losing lyric focus must blur the background");
            assertTrue(currentLine.blurAlpha < .01f);
            assertTrue(nextLine.blurAlpha > .8f);
            save("lyrics-unfocused");
            for (int i = 0; i < 90; i++) render(RenderSystem.getWidth() * .75, 2);
            assertArrayEquals(focusedBackground, capture().getRGB(600, 30, 200, 50, null, 0, 200),
                    "Returning focus must restore the original background");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            CloudMusic.lyrics.clear();
            CloudMusic.currentLyric = null;
            CloudMusic.hasTransLyrics = originalHasTranslation;
            bg.lyricsTransparency().setValue(.35);

            // Invalid media and Solid Color both keep the established cover fallback.
            bg.mode().setValue("picture");
            bg.pictureFile().setValue(temp.resolve("missing-lyrics.png").toString());
            settle();
            assertFalse(DesktopAppState.background().isAppliedToLyrics());
            assertArrayEquals(baseline, capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth()));
            bg.mode().setValue("static");
            settle();
            assertFalse(DesktopAppState.background().isActive());
            assertArrayEquals(baseline, capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth()));
            bg.mode().setValue("latent");
            bg.applyToLyrics().setValue(false);
            settle();
            assertFalse(DesktopAppState.background().isActive());
            assertArrayEquals(baseline, capture().getRGB(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), null, 0, RenderSystem.getFramebufferWidth()));
            screen.musicLyricsPanel = null;

            // Optional supplied media smoke checks; no personal paths or media enter the repository.
            bg.strength().setValue(.7);
            bg.panelOpacity().setValue(.25);
            for (String mode : new String[]{"picture", "video"}) {
                String sample = System.getProperty("tritium.backgroundSample." + mode);
                if (sample == null) continue;
                scroll.actualScrollOffset = scroll.targetScrollOffset = modeRow.getRelativeY() - 8;
                bg.mode().setValue(mode);
                (mode.equals("picture") ? bg.pictureFile() : bg.videoFile()).setValue(sample);
                render();
                await(() -> DesktopAppState.background().isActive());
                settle();
                save("sample-" + mode);
            }
        } finally {
            CloudMusic.profile = profile;
            CloudMusic.currentlyPlaying = playing;
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.addAll(originalLyrics);
            CloudMusic.currentLyric = originalCurrentLyric;
            CloudMusic.hasTransLyrics = originalHasTranslation;
            DesktopAppState.preferences().loadFromJsonObject(original);
            if (Display.isCreated()) {
                if (lyricsFixture != null) TextureManager.getInstance().deleteTexture(lyricsFixture.getBlurredCoverLocation());
                DesktopAppState.background().reload();
                if (screen != null) screen.onGuiClosed();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private void render() throws Exception {
        render(-1, -1);
    }

    private static AbstractWidget<?> preferenceRow(ScrollPanel scroll, PreferenceValue<?> preference) throws Exception {
        for (AbstractWidget<?> row : scroll.getChildren()) {
            try {
                var field = row.getClass().getDeclaredField("preference");
                field.setAccessible(true);
                if (field.get(row) == preference) return row;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("Settings row missing: " + preference.getName());
    }

    private void verifyFilePicker(Path picture, BackgroundPreferences bg) throws Exception {
        BackgroundFilePicker.choose("背景图片测试", "", false, false,
                path -> bg.pictureFile().setValue(path.toString()), message -> fail(message));
        JFileChooser[] chooser = new JFileChooser[1];
        await(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window.isShowing()) {
                            JFileChooser found = findChooser(window);
                            if (found != null) chooser[0] = found;
                        }
                    }
                });
            } catch (Exception e) { throw new RuntimeException(e); }
            return chooser[0] != null;
        });
        SwingUtilities.invokeAndWait(() -> {
            chooser[0].setSelectedFile(picture.toFile());
            chooser[0].approveSelection();
        });
        await(() -> !BackgroundFilePicker.isOpen() && bg.pictureFile().getValue().equals(picture.toString()));
    }

    private static JFileChooser findChooser(Component component) {
        if (component instanceof JFileChooser chooser) return chooser;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JFileChooser found = findChooser(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void render(double mouseX, double mouseY) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen((int) mouseX, (int) mouseY);
        Display.processMessages();
        Thread.sleep(15);
    }

    private void settle() throws Exception { for (int i = 0; i < 60; i++) render(); }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        do {
            render();
            if (condition.getAsBoolean()) return;
        } while (System.nanoTime() < end);
        fail(DesktopAppState.background().status());
    }

    private static void assertPixel(double x, double y, int r, int g, int b) {
        float scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(r, pixel.get(0) & 255, 2);
        assertEquals(g, pixel.get(1) & 255, 2);
        assertEquals(b, pixel.get(2) & 255, 2);
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
        Path directory = Path.of("target/background-previews");
        Files.createDirectories(directory);
        ImageIO.write(capture(), "png", directory.resolve(name + ".png").toFile());
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
