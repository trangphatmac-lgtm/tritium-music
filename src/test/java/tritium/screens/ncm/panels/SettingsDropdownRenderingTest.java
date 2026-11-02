package tritium.screens.ncm.panels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.DropdownWidget;
import tritium.screens.ncm.NCMScreen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class SettingsDropdownRenderingTest {
    private SettingsPanel settings;
    private Framebuffer target;

    @Test
    void rendersAndSelectsMenusAcrossThemesWidthsAndScrollEdges() throws Exception {
        var preferences = DesktopAppState.preferences();
        var snapshot = MusicPreferences.class.getDeclaredMethod("toJsonObject");
        snapshot.setAccessible(true);
        var original = snapshot.invoke(preferences);
        String originalScale = System.getProperty("tritium.uiScale");
        try {
            System.setProperty("tritium.uiScale", "1");
            DesktopNativeLoader.configureLibraryPath();
            invoke("createDisplay");
            Display.setDisplayMode(new DisplayMode(1100, 760));
            Display.update();
            invoke("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            // Shader initialization creates scratch framebuffers; finish it before entering any clip.
            Class.forName("tritium.rendering.shader.Shaders");
            target = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            preferences.appearance().setValue("Dark");
            preferences.interfaceStyle().setValue("Static");
            preferences.background().mode().setValue("static");
            preferences.settingsLayout().setValue("TwoColumns");
            settings = new SettingsPanel();
            settings.setBounds(0, 0, 1100, 760);
            settings.onInit();
            settle();
            var scroll = (ScrollPanel) settings.getChildren().get(2);
            var language = row(scroll, preferences.language());
            open(language);
            settle();
            save("dropdown-dark");
            var dropdown = (DropdownWidget) language.getChildren().getFirst();
            assertEquals(dropdown.getWidth(), number(dropdown, "menuWidth"), .01);
            double x = number(dropdown, "menuX") + 20;
            double y = number(dropdown, "menuY") + 6 + 2 * 32 + 16;
            settings.onMouseClickReceived(x, y, 0);
            assertEquals("en", preferences.language().getValue());
            assertFalse(settings.hasOpenDropdown());

            preferences.appearance().setValue("Light");
            settle();
            open(language);
            settle();
            save("dropdown-light");
            settings.onKeyTypedReceived('\0', Keyboard.KEY_ESCAPE);
            preferences.language().setValue("zh-CN");
            preferences.appearance().setValue("Dark");
            preferences.audioEffects().mode().setValue("Master");
            settings.setWidth(440);
            settle();
            var tone = row(scroll, preferences.audioEffects().masterTone());
            scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset
                    + tone.getY() - scroll.getY() - 180;
            settle();
            open(tone);
            settle();
            dropdown = (DropdownWidget) tone.getChildren().getFirst();
            x = number(dropdown, "menuX") + 20;
            y = number(dropdown, "menuY") + 22;
            double offset = scroll.targetScrollOffset;
            frame(x, y, -120);
            assertEquals(1, number(dropdown, "firstVisible"));
            assertEquals(offset, scroll.targetScrollOffset, "Only the menu should scroll");
            save("dropdown-narrow-scroll");
            settings.onKeyTypedReceived('\0', Keyboard.KEY_END);
            settle();
            assertTrue(number(dropdown, "firstVisible") > 20);
            save("dropdown-last-option");
            settings.onKeyTypedReceived('\n', Keyboard.KEY_RETURN);
            assertEquals(preferences.audioEffects().masterTone().getModes().getLast(),
                    preferences.audioEffects().masterTone().getValue());

            scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset
                    + tone.getY() - scroll.getY() - scroll.getHeight() + 60;
            settle();
            open(tone);
            settle();
            assertTrue(number(dropdown, "menuY") < dropdown.getY());
            save("dropdown-upwards");
            // Screen-level dispatch must dismiss before navigation or playback sees the click/key.
            NCMScreen screen = new NCMScreen();
            screen.setCurrentPanel(settings);
            settings.setAlpha(1);
            settle();
            language = row((ScrollPanel) settings.getChildren().get(2), preferences.language());
            open(language);
            screen.keyTyped('\0', Keyboard.KEY_ESCAPE);
            assertFalse(settings.hasOpenDropdown());
            open(language);
            screen.mouseClicked(1, 1, 0);
            assertFalse(settings.hasOpenDropdown());
        } finally {
            var restore = MusicPreferences.class.getDeclaredMethod("loadFromJsonObject", com.google.gson.JsonObject.class);
            restore.setAccessible(true);
            restore.invoke(preferences, original);
            if (originalScale == null) System.clearProperty("tritium.uiScale");
            else System.setProperty("tritium.uiScale", originalScale);
            if (target != null) target.deleteFramebuffer();
            if (Display.isCreated()) Display.destroy();
        }
    }

    private void open(AbstractWidget<?> row) {
        settings.onMouseClickReceived(row.getX() + 20, row.getY() + 26, 0);
        assertTrue(settings.hasOpenDropdown());
    }

    private AbstractWidget<?> row(ScrollPanel scroll, ModePreference preference) throws Exception {
        var field = SettingsPanel.ChoiceRow.class.getDeclaredField("preference");
        field.setAccessible(true);
        return scroll.getChildren().stream().flatMap(section -> section.getChildren().stream())
                .filter(row -> row instanceof SettingsPanel.ChoiceRow).filter(row -> {
                    try { return field.get(row) == preference; }
                    catch (IllegalAccessException e) { throw new AssertionError(e); }
                }).findFirst().orElseThrow();
    }

    private static double number(DropdownWidget dropdown, String name) throws Exception {
        var field = DropdownWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(dropdown)).doubleValue();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 40; i++) {
            frame(-1, -1, 0);
            Display.processMessages();
            Thread.sleep(10);
        }
    }

    private void frame(double x, double y, int wheel) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invoke("prepareFrameState");
        target.bindFramebuffer(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), AppTheme.background());
        settings.renderWidget(x, y, wheel);
        assertFalse(StencilClipManager.stencilClipping());
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private void save(String name) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        var pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = ((height - y - 1) * width + x) * 4;
            image.setRGB(x, y, (pixels.get(offset) & 255) << 16
                    | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255);
        }
        Path output = Path.of("target", "ui-preview", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void invoke(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
