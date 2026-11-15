package tritium.screens.ncm;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.AudioEnergy;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;
import tritium.ncm.music.lyrics.LocalLyricStore;
import tritium.ncm.music.lyrics.LyricSong;
import tritium.ncm.music.lyrics.OnlineLyricParser;
import tritium.rendering.Framebuffer;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.screens.ncm.lyrics.LyricStyle;
import tritium.utils.json.JsonUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class LyricImportRenderingTest {
    @TempDir Path temporary;
    private NCMScreen screen;

    @Test void emptyPageOpensFilteredPickerImportsAndReloadsLyrics() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid())),
                "This test automates the Swing chooser. The first-thread macOS launcher uses a native chooser.");
        var preferences = DesktopAppState.preferences();
        String oldStyle = preferences.lyricStyle().getValue();
        var song = JsonUtils.parse("{\"id\":991234501,\"name\":\"本地歌词测试\",\"ar\":[{\"name\":\"Test Artist\"}],\"al\":{\"id\":1,\"name\":\"Test Album\"}}", Music.class);
        Path saved = AppPaths.appDataDir().resolve("lyrics").resolve(
                HexFormat.of().formatHex(String.valueOf(song.getId()).getBytes(StandardCharsets.UTF_8)) + ".json");
        byte[] previousSaved = Files.exists(saved) ? Files.readAllBytes(saved) : null;
        var oldSong = CloudMusic.currentlyPlaying;
        var oldPlayer = CloudMusic.player;
        var oldProfile = CloudMusic.profile;
        var oldLikes = CloudMusic.likeList;
        var oldLyrics = List.copyOf(CloudMusic.lyrics);
        var oldCurrent = CloudMusic.currentLyric;
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            CloudMusic.profile = null;
            screen = new NCMScreen();
            screen.initGui();
            CloudMusic.profile = new User(991234502, "Test", "", 0, "");
            CloudMusic.likeList = List.of();
            CloudMusic.currentlyPlaying = song;
            var artwork = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            var graphics = artwork.createGraphics();
            graphics.setColor(new Color(48, 62, 92));
            graphics.fillRect(0, 0, 128, 128);
            graphics.dispose();
            TextureManager.getInstance().loadTexture(song.getCoverLocation(), new DynamicTexture(artwork));
            TextureManager.getInstance().loadTexture(song.getBlurredCoverLocation(), new DynamicTexture(artwork));
            TextureManager.getInstance().loadTexture(CloudMusic.profile.getAvatarLocation(), new DynamicTexture(artwork));
            var player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", FakePlayer.class);
            player.position = 1800;
            CloudMusic.player = player;
            var missingResponse = new com.google.gson.JsonParser().parse(
                    "{\"code\":200,\"uncollected\":true,\"lrc\":{\"version\":1,\"lyric\":\"[00:00.00]暂无歌词\"}}").getAsJsonObject();
            CloudMusic.initLyrics(missingResponse, song, OnlineLyricParser.netease(missingResponse).toLines());
            assertTrue(CloudMusic.lyrics.getFirst().isPlaceholder());
            screen.musicLyricsPanel = new MusicLyricsPanel(song);

            for (var style : LyricStyle.values()) {
                preferences.lyricStyle().setValue(style.name());
                for (int i = 0; i < 6; i++) render();
                double x = field("addLyricsX") + field("addLyricsWidth") * .5;
                double y = field("addLyricsY") + field("addLyricsHeight") * .5;
                assertTrue(field("addLyricsWidth") > 0, style.name());
                assertTrue(x > 0 && x < RenderSystem.getWidth());
                assertTrue(y > 0 && y < RenderSystem.getHeight());
                if (style == LyricStyle.Normal || style == LyricStyle.Hanabi) save(style.name());
                screen.mouseClicked((int) x, (int) y, 0);
                JFileChooser chooser = awaitChooser();
                onSwing(chooser::cancelSelection);
                assertEquals(1800, player.position, "Adding lyrics must preserve playback position");
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), style.name());
            }

            for (String format : List.of("lrc", "yrc")) {
                preferences.lyricStyle().setValue(format.equals("lrc") ? "Normal" : "Hanabi");
                CloudMusic.lyrics.clear();
                CloudMusic.lyrics.add(LyricLine.placeholder());
                for (int i = 0; i < 6; i++) render();
                Path file = temporary.resolve("song." + format);
                Files.writeString(file, format.equals("lrc") ? "[00:01]导入成功\n[00:03]下一行"
                        : "[1000,2000](1000,1000,0)导入(2000,1000,0)成功");
                screen.mouseClicked((int) (field("addLyricsX") + field("addLyricsWidth") * .5),
                        (int) (field("addLyricsY") + field("addLyricsHeight") * .5), 0);
                JFileChooser chooser = awaitChooser();
                onSwing(() -> {
                    for (String extension : LocalLyricStore.EXTENSIONS) assertTrue(chooser.getFileFilter().accept(new File("song." + extension)));
                    assertFalse(chooser.getFileFilter().accept(new File("song.mp3")));
                    chooser.setSelectedFile(file.toFile());
                    chooser.approveSelection();
                });
                await(() -> CloudMusic.lyrics.stream().anyMatch(line -> line.lyric.equals("导入成功")));
                assertEquals(1800, player.position);
                if (format.equals("yrc")) assertTrue(CloudMusic.lyrics.stream().anyMatch(line -> line.words.size() == 2));
                Files.delete(file);
                assertTrue(new LocalLyricStore(saved.getParent()).load(LyricSong.fromMusic(song)).usable());
                CloudMusic.lyrics.clear();
                CloudMusic.reloadLyrics();
                await(() -> CloudMusic.lyrics.stream().anyMatch(line -> line.lyric.equals("导入成功")));
                render();
                assertEquals(0, field("addLyricsWidth"));
            }
        } finally {
            onSwing(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
            if (screen != null && screen.musicLyricsPanel != null) screen.musicLyricsPanel.dispose();
            preferences.lyricStyle().setValue(oldStyle);
            CloudMusic.currentlyPlaying = oldSong;
            CloudMusic.player = oldPlayer;
            CloudMusic.profile = oldProfile;
            CloudMusic.likeList = oldLikes;
            CloudMusic.currentLyric = oldCurrent;
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.addAll(oldLyrics);
            if (previousSaved == null) Files.deleteIfExists(saved);
            else Files.write(saved, previousSaved);
            if (Display.isCreated()) {
                UiMaterials.release();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private void onSwing(Runnable action) throws Exception {
        var completion = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                SwingUtilities.invokeAndWait(action);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        await(completion::isDone);
        completion.get();
    }

    private void render() throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        Framebuffer.getMcFramebuffer().bindFramebuffer(true);
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen(-1, -1);
        Display.processMessages();
        Thread.sleep(15);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) render();
        assertTrue(condition.getAsBoolean());
    }

    private JFileChooser awaitChooser() throws Exception {
        JFileChooser[] found = new JFileChooser[1];
        await(() -> {
            try {
                onSwing(() -> {
                    for (Window window : Window.getWindows()) if (window.isShowing()) {
                        JFileChooser chooser = findChooser(window);
                        if (chooser != null) found[0] = chooser;
                    }
                });
            } catch (Exception e) { throw new RuntimeException(e); }
            return found[0] != null;
        });
        return found[0];
    }

    private static JFileChooser findChooser(Component component) {
        if (component instanceof JFileChooser chooser) return chooser;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JFileChooser chooser = findChooser(child);
            if (chooser != null) return chooser;
        }
        return null;
    }

    private double field(String name) throws Exception {
        var field = MusicLyricsPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(screen.musicLyricsPanel);
    }

    private void save(String style) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        var bytes = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = ((height - y - 1) * width + x) * 4;
            image.setRGB(x, y, (bytes.get(offset) & 255) << 16 | (bytes.get(offset + 1) & 255) << 8 | bytes.get(offset + 2) & 255);
        }
        Path file = Path.of("target/ui-preview/lyrics-import-" + style + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }

    static final class FakePlayer extends AudioPlayer {
        float position;
        FakePlayer(File file) { super(file); }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public float getTotalTimeMillis() { return 60000; }
        @Override public void setPlaybackTime(float time) { position = time; }
        @Override public boolean isPausing() { return true; }
        @Override public AudioEnergy getEnergy() { return AudioEnergy.SILENT; }
    }
}
