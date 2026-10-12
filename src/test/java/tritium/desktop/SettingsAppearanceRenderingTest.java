package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.ColorPickerWidget;
import tritium.screens.ncm.panels.SettingsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.ControlsBar;
import tritium.screens.ncm.panels.PlayModeButton;
import tritium.screens.ncm.panels.FavoriteButton;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real settings tree, input dispatch, gradients and scroll clipping. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class SettingsAppearanceRenderingTest {
    @Test
    void rendersAndInteractsWithThemeAndSpectrumPickers() throws Exception {
        DesktopNativeLoader.configureLibraryPath();
        int originalAccent = DesktopAppState.preferences().accentColor().getValue();
        int originalSpectrum = DesktopAppState.preferences().hud().musicSpectrum().color().getValue();
        boolean originalFps = DesktopAppState.preferences().showFps().getValue();
        String originalAppearance = DesktopAppState.preferences().appearance().getValue();
        try {
            DesktopAppState.preferences().appearance().setValue("Dark");
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, Math.min(720, RenderSystem.getWidth()), RenderSystem.getHeight());
            settings.onInit();
            renderStable(settings);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            AbstractWidget<?> mode = scroll.getChildren().get(1);
            assertRgb(8, 8, 30, 30, 30, 1);
            savePreview("settings-dark");
            click(settings, mode.getX() + 20, mode.getY() + 20);
            assertEquals("Light", DesktopAppState.preferences().appearance().getValue());
            Thread.sleep(90);
            render(settings);
            int transitionBackground = AppTheme.background() & 255;
            assertTrue(transitionBackground > 30 && transitionBackground < 255);
            savePreview("settings-transition");
            renderStable(settings);
            assertRgb(8, 8, 255, 255, 255, 1);
            assertEquals(0xFF000000, AppTheme.foreground());
            assertEquals(0x6B6B6B, NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            assertRgb(mode.getX() + 8, mode.getY() + 30, 245, 245, 245, 1);
            assertContainsBlackText(24, 24, 60, 30);
            savePreview("settings-light");

            ControlsBar controls = new ControlsBar();
            controls.setBounds(0, RenderSystem.getHeight() - 75, RenderSystem.getWidth(), 75);
            controls.onInit();
            for (int i = 0; i < 40; i++) {
                render(settings);
                controls.renderWidget(-1, -1, 0);
                Display.processMessages();
                Thread.sleep(10);
            }
            assertRgb(4, RenderSystem.getHeight() - 4, 255, 255, 255, 1);
            PlayModeButton mainMode = controls.getChildren().stream().filter(PlayModeButton.class::isInstance)
                    .map(PlayModeButton.class::cast).findFirst().orElseThrow();
            assertEquals(0, mainMode.getHexColor() & 0xFFFFFF);
            // Lyrics reuse these controls, but explicitly retain their original palette.
            PlayModeButton lyricMode = new PlayModeButton(32).setFollowTheme(false);
            FavoriteButton lyricFavorite = new FavoriteButton(32).setFollowTheme(false);
            lyricMode.onRender(-1, -1);
            lyricFavorite.onRender(-1, -1);
            assertEquals(0xFFFFFF, lyricMode.getHexColor() & 0xFFFFFF);
            assertEquals(0xFFFFFF, lyricFavorite.getHexColor() & 0xFFFFFF);
            savePreview("settings-light-controls");
            verifyMainWindow();

            click(settings, mode.getX() + 20, mode.getY() + 20);
            renderStable(settings);
            assertEquals("Dark", DesktopAppState.preferences().appearance().getValue());
            assertRgb(8, 8, 30, 30, 30, 1);
            AbstractWidget<?> theme = scroll.getChildren().get(2);
            click(settings, theme.getX() + 68, theme.getY() + 61);
            assertEquals(0xFF58A6FF, DesktopAppState.preferences().accentColor().getValue());
            click(settings, theme.getX() + theme.getWidth() - 40, theme.getY() + 20);
            renderStable(settings);
            ColorPickerWidget themePicker = (ColorPickerWidget) theme.getChildren().getFirst();
            assertFalse(themePicker.isHidden());
            // Sample the interior, away from rounded corners and the selection ring.
            assertRgb(themePicker.getX() + 8, themePicker.getY() + 30, 235, 239, 240, 12);
            assertRgb(themePicker.getX() + themePicker.getWidth() - 8, themePicker.getY() + 134, 3, 9, 17, 12);
            savePreview("settings-theme");

            // Narrow content still lays out the palette and expands the picker inside the row.
            settings.setBounds(0, 0, Math.min(440, RenderSystem.getWidth()), RenderSystem.getHeight());
            renderStable(settings);
            assertTrue(themePicker.getWidth() > 0);
            assertTrue(themePicker.getX() + themePicker.getWidth() < theme.getX() + theme.getWidth());
            savePreview("settings-narrow");

            AbstractWidget<?> spectrum = scroll.getChildren().stream()
                    .filter(row -> row.getChildren().stream().anyMatch(ColorPickerWidget.class::isInstance))
                    .reduce((first, second) -> second).orElseThrow();
            // Expand and scroll the spectrum section to the top of the visible viewport.
            spectrum.onMouseClicked(30, 20, 0);
            scroll.actualScrollOffset = scroll.targetScrollOffset = spectrum.getRelativeY() - 12;
            renderStable(settings);
            ColorPickerWidget spectrumPicker = (ColorPickerWidget) spectrum.getChildren().getFirst();
            assertFalse(spectrumPicker.isHidden());
            double alphaX = spectrumPicker.getX() + spectrumPicker.getWidth() / 2;
            double alphaY = spectrumPicker.getY() + 259;
            click(settings, alphaX, alphaY);
            renderStable(settings);
            assertEquals(128, DesktopAppState.preferences().hud().musicSpectrum().color().getValue() >>> 24);
            savePreview("settings-spectrum");

            // Hidden controls cannot consume a click outside the scrolling viewport.
            int before = DesktopAppState.preferences().accentColor().getValue();
            click(settings, theme.getX() + 28, theme.getY() + 61);
            assertEquals(before, DesktopAppState.preferences().accentColor().getValue());
            scroll.scrollToEnd();
            scroll.actualScrollOffset = scroll.targetScrollOffset;
            renderStable(settings);
            AbstractWidget<?> fpsToggle = scroll.getChildren().get(scroll.getChildren().size() - 3);
            click(settings, fpsToggle.getX() + 20, fpsToggle.getY() + 20);
            assertTrue(DesktopAppState.preferences().showFps().getValue());
            for (int i = 0; i < 40; i++) {
                render(settings);
                invokeApp("renderFpsOverlay");
                Thread.sleep(10);
            }
            assertRgb(RenderSystem.getWidth() - 12, 12, 25, 25, 25, 4);
            savePreview("settings-fps");
            click(settings, fpsToggle.getX() + 20, fpsToggle.getY() + 20);
            assertFalse(DesktopAppState.preferences().showFps().getValue());
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            DesktopAppState.preferences().accentColor().setValue(originalAccent);
            DesktopAppState.preferences().hud().musicSpectrum().color().setValue(originalSpectrum);
            DesktopAppState.preferences().showFps().setValue(originalFps);
            DesktopAppState.preferences().appearance().setValue(originalAppearance);
            if (Display.isCreated()) {
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static void click(SettingsPanel settings, double x, double y) throws Exception {
        render(settings, x, y);
        settings.onMouseClickReceived(x, y, 0);
        settings.onMouseReleaseReceived(x, y, 0);
        render(settings);
    }

    private static void verifyMainWindow() throws Exception {
        User originalProfile = CloudMusic.profile;
        NCMScreen screen = new NCMScreen();
        try {
            // Build without an account to avoid network requests in the render test.
            CloudMusic.profile = null;
            screen.initGui();
            CloudMusic.profile = new User(1, "Tritium Music", "", 0, "");
            screen.setCurrentPanel(new SettingsPanel());
            for (int i = 0; i < 45; i++) {
                DesktopAppState.pumpMainThreadTasks();
                Interpolations.calcFrameDelta();
                AppTheme.beginFrame();
                invokeApp("prepareFrameState");
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
                screen.drawScreen(-1, -1);
                Display.processMessages();
                Thread.sleep(10);
            }
            assertRgb(4, 200, 255, 255, 255, 1);
            assertRgb(RenderSystem.getWidth() - 4, RenderSystem.getHeight() - 4, 255, 255, 255, 1);
            savePreview("main-window-light");
        } finally {
            CloudMusic.profile = originalProfile;
            screen.onGuiClosed();
        }
    }

    private static void render(SettingsPanel settings) throws Exception {
        render(settings, -1, -1);
    }

    private static void renderStable(SettingsPanel settings) throws Exception {
        // Glyph generation is asynchronous, just as in the normal application loop.
        for (int i = 0; i < 40; i++) {
            render(settings);
            Display.processMessages();
            Thread.sleep(10);
        }
    }

    private static void render(SettingsPanel settings, double x, double y) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), AppTheme.background());
        settings.renderWidget(x, y, 0);
    }

    private static void assertRgb(double x, double y, int r, int g, int b, int tolerance) {
        float scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(r, pixel.get(0) & 255, tolerance, "red at " + x + "," + y);
        assertEquals(g, pixel.get(1) & 255, tolerance, "green at " + x + "," + y);
        assertEquals(b, pixel.get(2) & 255, tolerance, "blue at " + x + "," + y);
    }

    private static void assertContainsBlackText(int x, int y, int width, int height) {
        float scale = RenderSystem.getUiScaleFactor();
        int w = (int) (width * scale), h = (int) (height * scale);
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) ((y + height) * scale),
                w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int black = 0;
        for (int i = 0; i < w * h; i++) {
            if ((pixels.get(i * 4) & 255) < 10 && (pixels.get(i * 4 + 1) & 255) < 10
                    && (pixels.get(i * 4 + 2) & 255) < 10) black++;
        }
        assertTrue(black > 20, "Expected actual black glyph pixels in the light-mode title");
    }

    private static void savePreview(String name) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = ((height - y - 1) * width + x) * 4;
                image.setRGB(x, y, ((pixels.get(offset) & 255) << 16)
                        | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255));
            }
        }
        Path output = Path.of("target", "ui-preview", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void invokeApp(String method) throws Exception {
        var target = TritiumMusicDesktopApp.class.getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(null);
    }
}
