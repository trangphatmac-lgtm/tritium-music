package tritium.rendering.ui.container;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.AbstractWidget;
import tritium.screens.ncm.panels.PlaylistPanel;
import tritium.screens.ncm.panels.SettingsPanel;
import tritium.utils.json.JsonUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class ScrollPanelRenderingTest {
    @Test
    void rendersGrayCapsulesAndDragsRealSettingsAndPlaylistPages() throws Exception {
        var preferences = DesktopAppState.preferences();
        String oldStyle = preferences.interfaceStyle().getValue();
        String oldAppearance = preferences.appearance().getValue();
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

            ScrollPanel scroll = new ScrollPanel().setBounds(40, 40, 200, 240);
            scroll.addChild(new Panel().setBounds(200, 100_000));
            settle(scroll, -1, -1, 220, 0xFF202020);
            assertGray(235, 56, 32);
            // Near the edge, away from the thumb: reveal the thin capsule.
            settle(scroll, 239, 200, 220, 0xFF202020);
            assertGray(235, 56, 94);
            assertGray(232, 56, 32);
            // Hover grows leftwards while keeping the right inset fixed.
            settle(scroll, 235, 56, 400, 0xFF202020);
            assertGray(232, 56, 94);
            assertGray(239, 56, 32);
            assertGray(235, 80, 32); // The long page still has a 32-unit thumb.
            save("scrollbar-hover-dark");
            settle(scroll, 235, 56, 220, 0xFFFFFFFF);
            assertGray(232, 56, 173);
            save("scrollbar-hover-light");
            settle(scroll, -1, -1, 220, 0xFF202020);
            assertGray(235, 56, 32);
            scroll.onDWheel(20, 20, -120);
            settle(scroll, -1, -1, 220, 0xFF202020);
            assertGray(235, 56, 94);
            settle(scroll, -1, -1, 1100, 0xFF202020);
            assertGray(235, 56, 32);

            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, Math.min(720, RenderSystem.getWidth()), RenderSystem.getHeight());
            settings.onInit();
            verifyPage(settings, "settings");

            PlaylistPanel playlist = playlistFixture();
            playlist.setBounds(0, 0, Math.min(720, RenderSystem.getWidth()), RenderSystem.getHeight());
            playlist.onInit();
            verifyPage(playlist, "playlist");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            preferences.interfaceStyle().setValue(oldStyle);
            preferences.appearance().setValue(oldAppearance);
            if (Display.isCreated()) {
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static void verifyPage(AbstractWidget<?> page, String name) throws Exception {
        settle(page, -1, -1, 500, AppTheme.background());
        ScrollPanel scroll = findScroll(page);
        assertNotNull(scroll);
        double x = scroll.getX() + scroll.getWidth() - 5;
        double y = scroll.getY() + 12;
        settle(page, x, y, 400, AppTheme.background());
        save("scrollbar-" + name + "-top");
        page.onMouseClickReceived(x, y, 0);
        // A complete press/release also exercises release capture outside the scroll clip.
        page.onMouseReleaseReceived(x + 80, scroll.getY() + scroll.getHeight() + 80, 0);
        assertTrue(scroll.actualScrollOffset > 0);
        assertTrue(scroll.isScrolledToEnd());
        assertEquals(scroll.targetScrollOffset, scroll.actualScrollOffset);
        settle(page, x, scroll.getY() + scroll.getHeight() - 12, 400, AppTheme.background());
        save("scrollbar-" + name + "-bottom");
        var last = scroll.getChildren().getLast();
        assertEquals(scroll.getHeight(), last.getRelativeY() + last.getHeight(), .01);
    }

    private static PlaylistPanel playlistFixture() {
        User user = new User(990001, "Tritium Music", "", 0, "");
        PlayList playlist = new PlayList(990001, "Scrollbar · 长歌单", "", 200, 0, user, "", false, 0, 0);
        BufferedImage cover = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) cover.setRGB(x, y, 0x305080 + x * 0x010100 + y);
        }
        TextureManager.getInstance().loadTexture(playlist.getCoverLocation(), new DynamicTexture(cover));
        TextureManager.getInstance().loadTexture(user.getAvatarLocation(), new DynamicTexture(cover));
        playlist.musics = new ArrayList<>();
        playlist.musicsLoaded = true;
        for (int i = 0; i < 200; i++) {
            Music song = JsonUtils.parse("""
                    {"id":%d,"name":"Track %03d · 午后散步","dt":210000,
                    "ar":[{"name":"Tritium"}],"al":{"id":990001,"name":"Scrollbar Preview"}}
                    """.formatted(990001 + i, i + 1), Music.class);
            playlist.musics.add(song);
            TextureManager.getInstance().loadTexture(song.getSmallCoverLocation(), new DynamicTexture(cover));
        }
        return new PlaylistPanel(playlist);
    }

    private static ScrollPanel findScroll(AbstractWidget<?> widget) {
        if (widget instanceof ScrollPanel scroll) return scroll;
        for (var child : widget.getChildren()) {
            ScrollPanel found = findScroll(child);
            if (found != null) return found;
        }
        return null;
    }

    private static void settle(AbstractWidget<?> widget, double x, double y, long durationMs, int background) throws Exception {
        long end = System.nanoTime() + durationMs * 1_000_000;
        do {
            DesktopAppState.pumpMainThreadTasks();
            Interpolations.calcFrameDelta();
            AppTheme.beginFrame();
            invokeApp("prepareFrameState");
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), background);
            widget.renderWidget(x, y, 0);
            Display.processMessages();
            Thread.sleep(10);
        } while (System.nanoTime() < end);
    }

    private static void assertGray(double x, double y, int expected) {
        double scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected, pixel.get(channel) & 255, 3, "Pixel at " + x + ", " + y);
        }
    }

    private static void save(String name) throws Exception {
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
