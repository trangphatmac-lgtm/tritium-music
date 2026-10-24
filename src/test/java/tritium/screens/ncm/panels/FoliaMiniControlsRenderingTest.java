package tritium.screens.ncm.panels;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.NCMPanel;
import tritium.utils.json.JsonUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class FoliaMiniControlsRenderingTest {
    private static Framebuffer renderTarget;
    @Test
    void switchesThroughSettingsAndRoutesMiniPlayerActions() throws Exception {
        var prefs = DesktopAppState.preferences();
        var originalProfile = CloudMusic.profile;
        var originalSong = CloudMusic.currentlyPlaying;
        var originalPlayer = CloudMusic.player;
        var originalLikes = CloudMusic.likeList;
        var originalMode = CloudMusic.playMode;
        String originalControls = prefs.playerControls().getValue();
        String originalLayout = prefs.layout().getValue();
        String originalTheme = prefs.appearance().getValue();
        String originalStyle = prefs.interfaceStyle().getValue();
        String originalSettingsLayout = prefs.settingsLayout().getValue();
        boolean originalAutoCollapse = prefs.miniAutoCollapse().getValue();
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            // Keep render assertions independent of window occlusion/minimization on Windows.
            renderTarget = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            prefs.playerControls().setValue("BottomBar");
            prefs.miniAutoCollapse().setValue(false);
            prefs.layout().setValue("Sidebar");
            CloudMusic.profile = null;
            NCMScreen screen = new NCMScreen();
            screen.initGui();
            CloudMusic.profile = new User(90210, "Tritium Music", "", 0, "");
            TextureManager.getInstance().loadTexture(CloudMusic.profile.getAvatarLocation(),
                    new DynamicTexture(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)));
            SettingsPanel settings = new SettingsPanel();
            screen.setCurrentPanel(settings);
            settle(screen);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            var choice = scroll.getChildren().stream().flatMap(section -> section.getChildren().stream())
                    .filter(row -> {
                        try {
                            var label = row.getClass().getSuperclass().getDeclaredField("label");
                            label.setAccessible(true);
                            return "settings.playerControls".equals(label.get(row));
                        } catch (ReflectiveOperationException ignored) { return false; }
                    }).findFirst().orElseThrow();
            scroll.actualScrollOffset = scroll.targetScrollOffset = choice.getY() - scroll.getY() - 90;
            settle(screen);
            click(screen, choice);
            settle(screen);
            assertInstanceOf(FoliaMiniControls.class, screen.getControlsBar());
            assertSame(settings, screen.getCurrentPanel());
            ControlsBar mini = screen.getControlsBar();
            assertEquals(RenderSystem.getHeight(), settings.getY() + settings.getHeight(), .01,
                    "The page extends behind the floating controls to the bottom of the window");
            assertEquals(RenderSystem.getHeight(), scroll.getY() + scroll.getHeight(), .01,
                    "The inner settings scroll viewport must also reach the window bottom");
            assertEquals(240, mini.getWidth(), .01);
            assertEquals(36, mini.getHeight(), .01);
            assertEquals(FoliaMiniControls.BOTTOM_GAP, RenderSystem.getHeight() - mini.getY() - mini.getHeight(), .01);

            Music song = JsonUtils.parse("""
                    {"id":90001,"name":"アスノヨゾラ哨戒班","ar":[{"name":"Orangestar"}],"al":{"id":1,"name":"Album"}}
                    """, Music.class);
            CloudMusic.currentlyPlaying = song;
            CloudMusic.likeList = List.of(song.getId());
            // A silent fake deck isolates UI dispatch from audio devices and network services.
            FakeDeck deck = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", FakeDeck.class);
            deck.position = 31000;
            deck.paused = true;
            CloudMusic.player = deck;
            for (String layout : new String[]{"Sidebar", "TopBar"}) {
                prefs.layout().setValue(layout);
                for (String theme : new String[]{"Dark", "Light"}) {
                    prefs.appearance().setValue(theme);
                    for (String style : new String[]{"Static", "Blur", "LiquidGlass"}) {
                        prefs.interfaceStyle().setValue(style);
                        settle(screen);
                        assertSame(mini, screen.getControlsBar());
                        save("folia-mini-" + layout + "-" + theme + "-" + style, mini);
                    }
                }
            }
            IconWidget play = (IconWidget) mini.getChildren().getFirst();
            click(screen, play);
            assertFalse(deck.paused);
            click(screen, play);
            assertTrue(deck.paused);
            var mode = mini.getChildren().stream().filter(PlayModeButton.class::isInstance).findFirst().orElseThrow();
            var heart = (FavoriteButton) mini.getChildren().stream().filter(FavoriteButton.class::isInstance).findFirst().orElseThrow();
            assertTrue(mode.getX() + mode.getWidth() < heart.getX());
            assertEquals("S", heart.getIcon());
            CloudMusic.likeList = List.of();
            settle(screen);
            assertEquals("e", heart.getIcon());
            CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
            click(screen, mode);
            assertEquals(CloudMusic.PlayMode.LoopInList, CloudMusic.playMode);
            click(screen, mode);
            assertEquals(CloudMusic.PlayMode.LoopSingle, CloudMusic.playMode);
            click(screen, mode);
            assertEquals(CloudMusic.PlayMode.Random, CloudMusic.playMode);
            var progress = mini.getChildren().stream().filter(FoliaMiniControls.Progress.class::isInstance).findFirst().orElseThrow();
            click(screen, progress);
            assertEquals(88500, deck.position, 1000);
            screen.mouseClicked((int) (progress.getX() + progress.getWidth() * .5), (int) (progress.getY() + 5), 0);
            screen.mouseReleased((int) (mini.getX() + mini.getWidth() + 50), (int) mini.getY(), 0);
            assertEquals(177000, deck.position, 1);
            float before = deck.position;
            screen.mouseClicked((int) progress.getX(), (int) progress.getY(), 0);
            screen.mouseReleased((int) progress.getX(), (int) progress.getY(), 0);
            assertEquals(before, deck.position, "Time labels are not seek targets");

            prefs.appearance().setValue("Dark");
            prefs.interfaceStyle().setValue("Static");
            mini.setBounds(20, 100, 160, 36);
            CloudMusic.currentlyPlaying = JsonUtils.parse("""
                    {"id":90002,"name":"很长的歌曲名称 · A very long song title that must stay inside the capsule","ar":[],"al":{"id":1,"name":"Album"}}
                    """, Music.class);
            for (int i = 0; i < 40; i++) {
                DesktopAppState.pumpMainThreadTasks();
                Interpolations.calcFrameDelta();
                invokeApp("prepareFrameState");
                renderTarget.bindFramebuffer(true);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                AppTheme.beginFrame();
                mini.renderWidget(-1, -1, 0);
                Display.processMessages();
                Thread.sleep(10);
            }
            assertTrue(mini.getChildren().get(1).getX() + mini.getChildren().get(1).getWidth() < mode.getX());
            save("folia-mini-narrow-long-title", mini);
            CloudMusic.player = null;
            CloudMusic.currentlyPlaying = null;
            settle(screen);
            click(screen, play);
            click(screen, progress);
            assertNull(CloudMusic.player);
            save("folia-mini-idle", mini);
            scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset + choice.getY() - scroll.getY() - 90;
            settle(screen);
            click(screen, choice);
            settle(screen);
            assertEquals(ControlsBar.class, screen.getControlsBar().getClass());
            assertSame(settings, screen.getCurrentPanel());
            assertEquals(RenderSystem.getHeight() * .93, settings.getY() + settings.getHeight(), .01,
                    "The original bottom bar still reserves its own space");
            assertEquals(settings.getY() + settings.getHeight() - 24, scroll.getY() + scroll.getHeight(), .01);
            prefs.playerControls().setValue("FoliaMini");
            prefs.interfaceStyle().setValue("LiquidGlass");
            prefs.settingsLayout().setValue("TwoColumns");
            scroll.actualScrollOffset = scroll.targetScrollOffset = 0;
            settle(screen);
            assertEquals(RenderSystem.getHeight(), scroll.getY() + scroll.getHeight(), .01,
                    "Two-column settings must not retain a bottom clipping gap either");
            save("folia-mini-floating-page", 0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
            verifyForegroundLayer(screen);
            verifyListPageViewports(screen, song);
            verifyAutoCollapse(song);
        } finally {
            CloudMusic.profile = originalProfile;
            CloudMusic.currentlyPlaying = originalSong;
            CloudMusic.player = originalPlayer;
            CloudMusic.likeList = originalLikes;
            CloudMusic.playMode = originalMode;
            prefs.playerControls().setValue(originalControls);
            prefs.layout().setValue(originalLayout);
            prefs.appearance().setValue(originalTheme);
            prefs.interfaceStyle().setValue(originalStyle);
            prefs.settingsLayout().setValue(originalSettingsLayout);
            prefs.miniAutoCollapse().setValue(originalAutoCollapse);
            if (Display.isCreated()) {
                UiMaterials.release();
                if (renderTarget != null) renderTarget.deleteFramebuffer();
                renderTarget = null;
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static void verifyAutoCollapse(Music song) throws Exception {
        var prefs = DesktopAppState.preferences();
        prefs.miniAutoCollapse().setValue(true);
        CloudMusic.currentlyPlaying = song;
        FakeDeck deck = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}", FakeDeck.class);
        deck.position = 31000;
        deck.paused = true;
        CloudMusic.player = deck;
        FoliaMiniControls mini = new FoliaMiniControls();
        mini.onInit();
        double x = RenderSystem.getWidth() * .5;
        double y = RenderSystem.getHeight() - FoliaMiniControls.BOTTOM_GAP - 12;
        renderMini(mini, -1, -1, true, false, 0);
        assertEquals(144, mini.getWidth(), .01);
        assertEquals(24, mini.getHeight(), .01);
        assertTrue(mini.getChildren().getFirst().isHidden());
        assertFalse(mini.getChildren().getLast().isHidden());
        save("folia-mini-collapsed", mini);
        renderMini(mini, x, y, true, false, 10);
        renderMini(mini, x, y, true, false, 29);
        assertEquals(144, mini.getWidth(), .01);
        for (long t = 30; t <= 1200; t += 10) renderMini(mini, x, y, true, false, t);
        assertEquals(240, mini.getWidth(), .01);
        assertEquals(36, mini.getHeight(), .01);
        assertFalse(mini.getChildren().getFirst().isHidden());
        assertTrue(mini.getChildren().getLast().isHidden());
        save("folia-mini-hover-expanded", mini);
        // Losing window focus must collapse even with the pointer still over the capsule.
        for (long t = 1210; t <= 2400; t += 10) renderMini(mini, x, y, false, false, t);
        assertEquals(144, mini.getWidth(), .01);
        var progress = mini.getChildren().getLast();
        mini.onMouseClickReceived(progress.getX() + progress.getWidth() * .5, progress.getY() + 5, 0);
        for (long t = 2410; t <= 3600; t += 10) renderMini(mini, x, y, true, true, t);
        assertEquals(144, mini.getWidth(), .01, "A captured seek keeps its original track geometry");
        mini.onMouseReleaseReceived(mini.getX() + mini.getWidth() + 50, y, 0);
        assertEquals(177000, deck.position, 1);
        prefs.miniAutoCollapse().setValue(false);
        for (long t = 3610; t <= 4800; t += 10) renderMini(mini, -1, -1, false, false, t);
        assertEquals(240, mini.getWidth(), .01, "Disabling auto-collapse keeps the controller expanded");
    }

    private static void renderMini(FoliaMiniControls mini, double x, double y, boolean focused,
                                   boolean captured, long millis) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        renderTarget.bindFramebuffer(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), 0xFF31475D);
        UiMaterials.beginFrame(x, y);
        UiMaterials.captureBackdrop(false);
        mini.arrange(0, RenderSystem.getWidth(), RenderSystem.getHeight(), x, y, focused, captured, millis * 1_000_000);
        mini.renderWidget(x, y, 0);
        Display.processMessages();
    }

    private static void click(NCMScreen screen, AbstractWidget<?> widget) {
        int x = (int) (widget.getX() + widget.getWidth() * .5), y = (int) (widget.getY() + widget.getHeight() * .5);
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private static void verifyListPageViewports(NCMScreen screen, Music song) throws Exception {
        var prefs = DesktopAppState.preferences();
        String originalNavigation = prefs.navigation().mode().getValue();
        var originalLibrary = CloudMusic.playLists;
        List<PlayList> originalRecommendations = List.copyOf(HomePanel.playLists);
        try {
            prefs.navigation().mode().setValue("List");
            TextureManager.getInstance().loadTexture(song.getSmallCoverLocation(),
                    new DynamicTexture(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)));
            List<Music> songs = java.util.Collections.nCopies(40, song);
            PlayList mine = new PlayList(91001, "测试歌单", "", 40, 0, CloudMusic.profile, "", false, 0, 0);
            PlayList saved = new PlayList(91002, "收藏歌单", "", 40, 0, CloudMusic.profile, "", true, 0, 0);
            PlayList search = new PlayList(91003, "测试搜索", "", 40, 0, CloudMusic.profile, "", false, 0, 0);
            search.setSearchMode(true);
            for (PlayList playlist : List.of(mine, saved, search)) {
                playlist.musics = songs;
                playlist.musicsLoaded = true;
                TextureManager.getInstance().loadTexture(playlist.getCoverLocation(),
                        new DynamicTexture(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)));
            }
            var library = new java.util.ArrayList<PlayList>();
            library.addAll(java.util.Collections.nCopies(30, mine));
            library.addAll(java.util.Collections.nCopies(30, saved));
            CloudMusic.playLists = library;
            HomePanel.playLists.clear();
            HomePanel.playLists.addAll(java.util.Collections.nCopies(40, mine));
            record Page(String name, NCMPanel panel, double inset) { }
            for (String layout : new String[]{"Sidebar", "TopBar"}) {
                prefs.layout().setValue(layout);
                for (Page page : List.of(
                        new Page("explore", new HomePanel(), 12),
                        new Page("my-playlists", new PlaylistLibraryPanel(screen, false), 24),
                        new Page("saved-playlists", new PlaylistLibraryPanel(screen, true), 24),
                        new Page("cloud", new CloudStoragePanel(() -> songs, Runnable::run, Runnable::run), 16),
                        new Page("tracks", new PlaylistPanel(mine), 0),
                        new Page("search", new PlaylistPanel(search), 0))) {
                    screen.setCurrentPanel(page.panel());
                    for (String controls : new String[]{"BottomBar", "FoliaMini"}) {
                        prefs.playerControls().setValue(controls);
                        settle(screen);
                        ScrollPanel viewport = findScroll(page.panel());
                        double bottom = viewport.getY() + viewport.getHeight();
                        double expected = controls.equals("FoliaMini") ? RenderSystem.getHeight()
                                : page.panel().getY() + page.panel().getHeight() - page.inset();
                        assertEquals(expected, bottom, .01, page.name() + " / " + layout + " / " + controls);
                        assertSame(page.panel(), screen.getCurrentPanel(), "Switching controls keeps the page open");
                        if (controls.equals("FoliaMini"))
                            save("folia-list-" + layout + "-" + page.name(), 0, 0,
                                    RenderSystem.getWidth(), RenderSystem.getHeight());
                    }
                }
            }
        } finally {
            CloudMusic.playLists = originalLibrary;
            HomePanel.playLists.clear();
            HomePanel.playLists.addAll(originalRecommendations);
            prefs.navigation().mode().setValue(originalNavigation);
        }
    }

    private static ScrollPanel findScroll(AbstractWidget<?> widget) {
        if (widget instanceof ScrollPanel scroll) return scroll;
        for (AbstractWidget<?> child : widget.getChildren()) {
            ScrollPanel found = findScroll(child);
            if (found != null) return found;
        }
        return null;
    }

    private static void settle(NCMScreen screen) throws Exception {
        for (int i = 0; i < 40; i++) {
            render(screen, -1, -1);
            Display.processMessages();
            Thread.sleep(10);
        }
    }

    private static void render(NCMScreen screen, int mouseX, int mouseY) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        renderTarget.bindFramebuffer(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen(mouseX, mouseY);
    }

    private static void verifyForegroundLayer(NCMScreen screen) throws Exception {
        BackdropProbe page = new BackdropProbe();
        screen.setCurrentPanel(page);
        settle(screen);
        FoliaMiniControls mini = (FoliaMiniControls) screen.getControlsBar();
        for (String style : new String[]{"Blur", "LiquidGlass"}) {
            DesktopAppState.preferences().interfaceStyle().setValue(style);
            page.color = 0xFFDC3020;
            settle(screen);
            int red = pixel(mini.getX() + mini.getWidth() * .5, mini.getY() + 4);
            page.color = 0xFF2050DC;
            settle(screen);
            int blue = pixel(mini.getX() + mini.getWidth() * .5, mini.getY() + 4);
            assertTrue(((red >> 16) & 255) > ((blue >> 16) & 255) + 20,
                    style + " must sample the page's current red content, not just the background");
            assertTrue((blue & 255) > (red & 255) + 20,
                    style + " must update the foreground backdrop when content below it changes");
            save("folia-mini-overlay-" + style, 0, mini.getY() - 40, RenderSystem.getWidth(), 92);
        }
        int centerX = (int) (mini.getX() + mini.getWidth() * .5), centerY = (int) (mini.getY() + 18);
        render(screen, centerX, centerY);
        assertEquals(-1, page.mouseX, "The covered page does not hover through the capsule");
        screen.mouseClicked(centerX, centerY, 0);
        screen.mouseReleased(centerX, centerY, 0);
        assertEquals(0, page.clicks);
        assertEquals(0, page.releases, "Captured player releases must not reach page controls");
        int outsideX = (int) mini.getX() - 20;
        render(screen, outsideX, centerY);
        assertEquals(outsideX, page.mouseX);
        screen.mouseClicked(outsideX, centerY, 0);
        screen.mouseReleased(outsideX, centerY, 0);
        assertEquals(1, page.clicks, "Content beside the capsule remains interactive at the same height");
        assertEquals(1, page.releases);
        int cornerX = (int) mini.getX() + 1, cornerY = (int) mini.getY() + 1;
        assertFalse(mini.contains(cornerX, cornerY));
        screen.mouseClicked(cornerX, cornerY, 0);
        screen.mouseReleased(cornerX, cornerY, 0);
        assertEquals(2, page.clicks, "Transparent rounded corners pass input through");
        assertEquals(RenderSystem.getHeight(), page.getY() + page.getHeight(), .01);
    }

    private static int pixel(double x, double y) {
        float scale = RenderSystem.getUiScaleFactor();
        var pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        return ((pixel.get(0) & 255) << 16) | ((pixel.get(1) & 255) << 8) | (pixel.get(2) & 255);
    }

    private static void save(String name, ControlsBar controls) throws Exception {
        save(name, controls.getX(), controls.getY(), controls.getWidth(), controls.getHeight());
    }

    private static void save(String name, double x, double y, double w, double h) throws Exception {
        float scale = RenderSystem.getUiScaleFactor();
        int width = (int) (w * scale), height = (int) (h * scale);
        var pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) ((y + h) * scale),
                width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int p = ((height - row - 1) * width + col) * 4;
            image.setRGB(col, row, ((pixels.get(p) & 255) << 16) | ((pixels.get(p + 1) & 255) << 8) | (pixels.get(p + 2) & 255));
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

    static final class FakeDeck extends AudioPlayer {
        float position;
        boolean paused;
        FakeDeck(File file) { super(file); }
        @Override public float getTotalTimeMillis() { return 177000; }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public boolean isPausing() { return paused; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public void setPlaybackTime(float millis) { position = millis; }
    }

    static final class BackdropProbe extends NCMPanel {
        int color = 0xFFDC3020;
        int clicks, releases;
        double mouseX;
        @Override public void onInit() { }
        @Override public void onRender(double mouseX, double mouseY) {
            this.mouseX = mouseX;
            Rect.draw(getX(), getY(), getWidth(), getHeight(), color);
        }
        @Override public boolean onMouseClicked(double x, double y, int button) { clicks++; return true; }
        @Override public void onMouseReleased(double x, double y, int button) { releases++; }
    }
}
