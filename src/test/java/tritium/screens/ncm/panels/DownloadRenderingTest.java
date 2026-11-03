package tritium.screens.ncm.panels;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.desktop.download.SongDownloads;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.Quality;
import tritium.ncm.music.dto.*;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.DropdownTestSupport;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.utils.Tuple;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class DownloadRenderingTest {
    @TempDir Path directory;
    private Framebuffer target;
    private double mouseX = -1, mouseY = -1;

    @Test void alignsListActionsDownloadsWithoutPlayingAndEditsIndependentSettings() throws Exception {
        var preferences = DesktopAppState.preferences();
        var snapshot = MusicPreferences.class.getDeclaredMethod("toJsonObject");
        snapshot.setAccessible(true);
        var original = snapshot.invoke(preferences);
        Music originalSong = CloudMusic.currentlyPlaying;
        String originalScale = System.getProperty("tritium.uiScale");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            byte[] audio = "fLaC-render-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("tritium.uiScale", "1");
            DesktopNativeLoader.configureLibraryPath();
            invoke("createDisplay");
            Display.setDisplayMode(new DisplayMode(900, 650));
            Display.update();
            invoke("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            Class.forName("tritium.rendering.shader.Shaders");
            target = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            preferences.interfaceStyle().setValue("Static");
            preferences.background().mode().setValue("static");
            preferences.navigation().mode().setValue("List");
            preferences.downloadDirectory().setValue(directory.toString());
            preferences.downloadQuality().setValue("HiRes");
            preferences.quality().setValue("Standard");
            AtomicReference<Quality> requestedQuality = new AtomicReference<>();
            Panel list = new Panel().setBounds(24, 40, 720, 170);
            List<MusicWidget> rows = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Music song = new Music(i == 0 ? "晴天" : i == 1 ? "这是一首用来检查长标题与按钮间距的歌曲" : "夜曲", null, null, 9000 + i,
                        List.of(new Artist(1, "歌手", List.of(), List.of())), List.of(),
                        new Album(1, "专辑", "", List.of()), i == 1 ? 4_000_000 : 220_000, 0, 0, List.of()) {
                    @Override public Tuple<String, String> getDownloadUrl(Quality quality) {
                        requestedQuality.set(quality);
                        return Tuple.of("http://127.0.0.1:" + server.getAddress().getPort() + "/audio", "flac");
                    }
                };
                MusicWidget row = new MusicWidget(song, null, i);
                row.setPosition(0, i * 34);
                list.addChild(row);
                rows.add(row);
            }
            CloudMusic.currentlyPlaying = rows.get(2).music;
            for (String theme : List.of("Dark", "Light")) {
                preferences.appearance().setValue(theme);
                settle(list);
                for (MusicWidget row : rows) {
                    var actions = row.getChildren().stream().filter(IconWidget.class::isInstance).toList();
                    assertEquals(3, actions.size());
                    for (int i = 0; i < actions.size(); i++) {
                        assertEquals(20, actions.get(i).getWidth());
                        assertEquals(5, actions.get(i).getRelativeY());
                        if (i > 0) assertEquals(26, actions.get(i).getRelativeX() - actions.get(i - 1).getRelativeX());
                    }
                    var duration = row.getChildren().stream().filter(LabelWidget.class::isInstance)
                            .map(LabelWidget.class::cast).filter(label -> label.getLabel().matches("[0-9:]+") && label.getLabel().contains(":"))
                            .findFirst().orElseThrow();
                    assertEquals(6, duration.getX() - (download(row).getX() + download(row).getWidth()), .01,
                            "Actions must sit directly beside the actual duration, including hour-long tracks");
                }
                save("playlist-download-" + theme.toLowerCase());
            }
            list.setWidth(380);
            settle(list);
            save("playlist-download-narrow");
            list.setWidth(720);
            settle(list);
            DownloadButton button = download(rows.getFirst());
            list.onMouseClickReceived(button.getX() + 10, button.getY() + 10, 0);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (DesktopAppState.songDownloads().status(9000).busy() && System.nanoTime() < deadline) Thread.sleep(10);
            var result = DesktopAppState.songDownloads().status(9000);
            assertEquals(SongDownloads.State.COMPLETE, result.state());
            assertEquals(directory, result.file().getParent());
            assertEquals(Quality.HIRES, requestedQuality.get());
            assertSame(rows.get(2).music, CloudMusic.currentlyPlaying, "The download action must consume the row click");
            settle(list);
            save("playlist-download-complete");
            mouseX = button.getX() + 10;
            mouseY = button.getY() + 10;
            settle(list);
            save("playlist-download-tooltip");
            mouseX = mouseY = -1;
            for (String mode : List.of("Coverflow", "Wall", "List")) {
                preferences.navigation().mode().setValue(mode);
                frame(list);
                assertEquals(!mode.equals("List"), button.isHidden());
            }

            preferences.appearance().setValue("Dark");
            preferences.settingsLayout().setValue("SingleColumn");
            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, 720, 650);
            settings.onInit();
            settle(settings);
            var scroll = (ScrollPanel) settings.getChildren().get(2);
            var folder = settingRow(scroll, "settings.downloadDirectory");
            scroll.actualScrollOffset = scroll.targetScrollOffset = folder.getY() - scroll.getY() - 50;
            settle(settings);
            var quality = settingRow(scroll, "settings.downloadQuality");
            DropdownTestSupport.select(settings, quality, preferences.downloadQuality(), "LossLess");
            assertEquals(Quality.LOSSLESS, preferences.selectedDownloadQuality());
            assertEquals(Quality.STANDARD, CloudMusic.quality);
            settings.onMouseClickReceived(folder.getX() + folder.getWidth() - 105, folder.getY() + 20, 0);
            assertEquals(AppPaths.downloadDir().toString(), preferences.downloadDirectory().getValue());
            settle(settings);
            save("settings-downloads");
            settings.setWidth(380);
            settle(settings);
            save("settings-downloads-narrow");
        } finally {
            server.stop(0);
            CloudMusic.currentlyPlaying = originalSong;
            var restore = MusicPreferences.class.getDeclaredMethod("loadFromJsonObject", com.google.gson.JsonObject.class);
            restore.setAccessible(true);
            restore.invoke(preferences, original);
            if (originalScale == null) System.clearProperty("tritium.uiScale"); else System.setProperty("tritium.uiScale", originalScale);
            if (target != null) target.deleteFramebuffer();
            if (Display.isCreated()) { FontManager.deleteLoadedTextures(); Display.destroy(); }
        }
    }

    private static DownloadButton download(MusicWidget row) {
        return row.getChildren().stream().filter(DownloadButton.class::isInstance).map(DownloadButton.class::cast).findFirst().orElseThrow();
    }

    private static AbstractWidget<?> settingRow(ScrollPanel scroll, String key) {
        return scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).filter(row -> {
            try {
                var label = row.getClass().getSuperclass().getDeclaredField("label");
                label.setAccessible(true);
                return key.equals(label.get(row));
            } catch (ReflectiveOperationException ignored) { return false; }
        }).findFirst().orElseThrow();
    }

    private void settle(AbstractWidget<?> widget) throws Exception {
        for (int i = 0; i < 35; i++) { frame(widget); Display.processMessages(); Thread.sleep(10); }
    }

    private void frame(AbstractWidget<?> widget) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invoke("prepareFrameState");
        target.bindFramebuffer(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), AppTheme.background());
        widget.renderWidget(mouseX, mouseY, 0);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private void save(String name) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        var pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = ((height - y - 1) * width + x) * 4;
            image.setRGB(x, y, (pixels.get(offset) & 255) << 16 | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255);
        }
        Path output = Path.of("target", "download-previews", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void invoke(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
