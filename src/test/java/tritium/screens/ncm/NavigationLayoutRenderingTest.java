package tritium.screens.ncm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.TextFieldWidget;
import tritium.screens.ncm.panels.*;
import tritium.utils.json.JsonUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class NavigationLayoutRenderingTest {
    private NCMScreen screen;

    @Test
    void switchesLayoutsAndNavigatesLibrariesWithoutLosingPageOrSearchState() throws Exception {
        User originalProfile = CloudMusic.profile;
        List<PlayList> originalPlaylists = CloudMusic.playLists;
        var preferences = DesktopAppState.preferences();
        String originalLayout = preferences.layout().getValue();
        String originalAppearance = preferences.appearance().getValue();
        String originalStyle = preferences.interfaceStyle().getValue();
        String originalLanguage = preferences.language().getValue();
        String originalBackground = preferences.background().mode().getValue();
        String originalShader = preferences.background().shader().getValue();
        var recommendationsField = HomePanel.class.getDeclaredField("playLists");
        recommendationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlayList> recommendations = (List<PlayList>) recommendationsField.get(null);
        List<PlayList> originalRecommendations = List.copyOf(recommendations);
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            CloudMusic.profile = null;
            preferences.layout().setValue("Sidebar");
            screen = new NCMScreen();
            screen.initGui();
            User user = new User(90210, "Tritium Music", "", 0, "");
            CloudMusic.profile = user;
            TextureManager.getInstance().loadTexture(user.getAvatarLocation(), new DynamicTexture(artwork(0)));
            List<PlayList> fixtures = new ArrayList<>();
            String[] names = {"我喜欢的音乐", "午后散步 · Afternoon Walk", "夜晚的城市", "轻音乐与白日梦", "一路向海", "周末慢放"};
            for (int i = 0; i < 18; i++) {
                PlayList playlist = new PlayList(90000 + i, names[i % names.length], "", 12 + i * 7, 0, user, "", i >= 12, 0, 0);
                playlist.musics = new ArrayList<>();
                playlist.musicsLoaded = true;
                fixtures.add(playlist);
                TextureManager.getInstance().loadTexture(playlist.getCoverLocation(), new DynamicTexture(artwork(i)));
            }
            CloudMusic.playLists = fixtures;
            Music moon = JsonUtils.parse("""
                    {"id":90001,"name":"Moon","ar":[{"name":"Artist"}],"al":{"id":90001,"name":"Album"}}
                    """, Music.class);
            Music sun = JsonUtils.parse("""
                    {"id":90002,"name":"Sun","ar":[{"name":"Artist"}],"al":{"id":90002,"name":"Album"}}
                    """, Music.class);
            fixtures.getFirst().musics = List.of(moon, sun);
            for (Music song : fixtures.getFirst().musics) {
                TextureManager.getInstance().loadTexture(song.getSmallCoverLocation(), new DynamicTexture(artwork(0)));
            }
            recommendations.clear();
            recommendations.add(fixtures.getFirst());
            SettingsPanel settings = new SettingsPanel();
            screen.setCurrentPanel(settings);
            settle();
            var controls = screen.getControlsBar();
            var settingsScroll = scroll(settings);
            var layoutRow = settingsScroll.getChildren().get(5);
            settingsScroll.actualScrollOffset = settingsScroll.targetScrollOffset = layoutRow.getRelativeY() - 12;
            settle();
            double scrollOffset = settingsScroll.targetScrollOffset;
            click(layoutRow);
            settle();
            assertEquals("TopBar", preferences.layout().getValue());
            assertSame(settings, screen.getCurrentPanel());
            assertSame(controls, screen.getControlsBar());
            assertEquals(scrollOffset, settingsScroll.targetScrollOffset, .01);
            assertEquals(NCMScreen.NavigationSection.SETTINGS, screen.getNavigationSection());
            verifyGeometry();
            save("top-navigation-settings");

            click(tab(1));
            settle();
            assertInstanceOf(PlaylistLibraryPanel.class, screen.getCurrentPanel());
            assertEquals(NCMScreen.NavigationSection.MY_PLAYLISTS, screen.getNavigationSection());
            ScrollPanel mine = scroll(screen.getCurrentPanel());
            assertEquals(12, mine.getChildren().size());
            save("top-navigation-my-playlists");
            click(mine.getChildren().getFirst());
            settle();
            assertSame(fixtures.getFirst(), ((PlaylistPanel) screen.getCurrentPanel()).playList);
            assertEquals(NCMScreen.NavigationSection.MY_PLAYLISTS, screen.getNavigationSection());
            verifyPlaylistSearchStaysLocal();

            screen.mouseClicked(-1, -1, 3);
            settle();
            assertSame(mine, scroll(screen.getCurrentPanel()));
            click(tab(2));
            settle();
            assertEquals(NCMScreen.NavigationSection.SAVED_PLAYLISTS, screen.getNavigationSection());
            assertEquals(6, scroll(screen.getCurrentPanel()).getChildren().size());
            save("top-navigation-saved-playlists");
            click(scroll(screen.getCurrentPanel()).getChildren().getFirst());
            settle();
            assertSame(fixtures.get(12), ((PlaylistPanel) screen.getCurrentPanel()).playList);
            assertEquals(NCMScreen.NavigationSection.SAVED_PLAYLISTS, screen.getNavigationSection());
            NCMPanel savedDetail = screen.getCurrentPanel();
            preferences.layout().setValue("Sidebar");
            settle();
            assertSame(savedDetail, screen.getCurrentPanel());
            ScrollPanel sidebar = (ScrollPanel) screen.getPlaylistsPanel().getChildren().stream()
                    .filter(ScrollPanel.class::isInstance).findFirst().orElseThrow();
            sidebar.scrollToEndImmediately();
            sidebar.targetScrollOffset = sidebar.actualScrollOffset;
            settle();
            assertEquals(1, sidebar.getChildren().stream().filter(NavigateBar.PlaylistItem.class::isInstance)
                    .map(NavigateBar.PlaylistItem.class::cast).filter(NavigateBar.PlaylistItem::isSelected).count());
            preferences.layout().setValue("TopBar");
            settle();
            click(tab(0));
            settle();
            assertInstanceOf(HomePanel.class, screen.getCurrentPanel());
            assertEquals(NCMScreen.NavigationSection.EXPLORE, screen.getNavigationSection());
            save("top-navigation-explore");
            click(tab(2));
            settle();

            TextFieldWidget field = searchField();
            field.setText("海边");
            field.setFocused(true);
            NCMPanel current = screen.getCurrentPanel();
            preferences.layout().setValue("Sidebar");
            settle();
            assertSame(current, screen.getCurrentPanel());
            assertSame(field, searchField());
            assertEquals("海边", searchField().getText());
            assertTrue(field.isFocused());
            assertSame(controls, screen.getControlsBar());
            assertTrue(screen.currentPanelBg.getX() > 0);
            save("sidebar-preserved");
            preferences.layout().setValue("TopBar");
            settle();
            field.setText("   ");
            screen.keyTyped('\n', Keyboard.KEY_RETURN);
            assertSame(current, screen.getCurrentPanel(), "Empty searches must not navigate or issue requests");
            click(tab(3));
            assertFalse(field.isFocused(), "Clicking navigation releases search keyboard focus");
            settle();
            click(tab(1));
            settle();

            // All three materials and both palettes must keep the header outside content/player bounds.
            for (String appearance : new String[]{"Dark", "Light"}) {
                preferences.appearance().setValue(appearance);
                for (String material : preferences.interfaceStyle().getModes()) {
                    preferences.interfaceStyle().setValue(material);
                    settle();
                    verifyGeometry();
                    assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
                }
            }
            // Inspect both end caps over a real backdrop in the mode that shows glass rims.
            preferences.background().mode().setValue("shader");
            preferences.background().shader().setValue("waves");
            preferences.appearance().setValue("Dark");
            preferences.language().setValue("en");
            field.setText("");
            click(tab(3));
            settle();
            verifyGeometry();
            save("top-navigation-glass-settings");
            click(tab(0));
            settle();
            save("top-navigation-glass-explore");
            click(tab(1));
            preferences.background().mode().setValue("static");
            preferences.appearance().setValue("Light");
            preferences.interfaceStyle().setValue("Static");
            settle();
            save("top-navigation-light");
            preferences.language().setValue("en");
            Display.setDisplayMode(new DisplayMode(800, 700));
            var highResolution = Class.forName("tritium.desktop.MacOpenGLSurface").getDeclaredMethod("enableHighResolution");
            highResolution.setAccessible(true);
            highResolution.invoke(null);
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            settle();
            assertEquals(80, screen.basePanel.getChildren().getFirst().getHeight());
            verifyGeometry();
            var next = controls.getChildren().get(4);
            var mode = controls.getChildren().stream().filter(PlayModeButton.class::isInstance).findFirst().orElseThrow();
            assertTrue(mode.getX() > next.getX() + next.getWidth(), "Volume controls must not crowd the transport at narrow widths");
            save("top-navigation-narrow-en");

            CloudMusic.playLists = List.of();
            click(tab(2));
            settle();
            assertTrue(scroll(screen.getCurrentPanel()).getChildren().isEmpty());
            save("top-navigation-empty");
            CloudMusic.playLists = fixtures;
            settle();
            assertEquals(6, scroll(screen.getCurrentPanel()).getChildren().size(), "Libraries update after account data arrives");
        } finally {
            preferences.layout().setValue(originalLayout);
            preferences.appearance().setValue(originalAppearance);
            preferences.interfaceStyle().setValue(originalStyle);
            preferences.language().setValue(originalLanguage);
            preferences.background().mode().setValue(originalBackground);
            preferences.background().shader().setValue(originalShader);
            recommendations.clear();
            recommendations.addAll(originalRecommendations);
            CloudMusic.profile = originalProfile;
            CloudMusic.playLists = originalPlaylists;
            if (Display.isCreated()) {
                UiMaterials.release();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private AbstractWidget<?> tab(int index) {
        return screen.basePanel.getChildren().getFirst().getChildren().get(index + 2);
    }

    private void verifyPlaylistSearchStaysLocal() throws Exception {
        NCMPanel playlist = screen.getCurrentPanel();
        TextFieldWidget global = searchField();
        global.setText("previous global query");
        TextFieldWidget local = (TextFieldWidget) playlist.getChildren().stream()
                .flatMap(widget -> widget.getChildren().stream())
                .filter(TextFieldWidget.class::isInstance).findFirst().orElseThrow();
        click(local.getParent());
        settle();
        assertTrue(local.isFocused());
        assertFalse(global.isFocused());

        screen.keyTyped('o', Keyboard.KEY_O);
        assertEquals("o", local.getText());
        List<AbstractWidget<?>> rows = playlist.getChildren().stream()
                .flatMap(widget -> widget.getChildren().stream())
                .filter(ScrollPanel.class::isInstance).findFirst().orElseThrow().getChildren();
        assertFalse(rows.getFirst().isHidden());
        assertTrue(rows.get(1).isHidden());
        screen.keyTyped('\n', Keyboard.KEY_RETURN);
        assertSame(playlist, screen.getCurrentPanel());
        assertEquals("previous global query", global.getText());

        local.setText("");
        assertTrue(rows.stream().noneMatch(AbstractWidget::isHidden));
        local.setFocused(false);
        global.setText("");
    }

    private TextFieldWidget searchField() {
        var search = screen.basePanel.getChildren().getFirst().getChildren().stream()
                .filter(MusicSearchBar.class::isInstance).findFirst().orElseThrow();
        return (TextFieldWidget) search.getChildren().get(1);
    }

    private static ScrollPanel scroll(NCMPanel panel) {
        return (ScrollPanel) panel.getChildren().stream().filter(ScrollPanel.class::isInstance).findFirst().orElseThrow();
    }

    private void verifyGeometry() {
        var navigation = screen.basePanel.getChildren().getFirst();
        assertEquals(navigation.getHeight(), screen.currentPanelBg.getY());
        assertEquals(0, screen.currentPanelBg.getX());
        assertEquals(screen.getPanelHeight(), screen.controlsBar.getY() + screen.controlsBar.getHeight(), .01);
        assertEquals(screen.currentPanelBg.getY() + screen.currentPanelBg.getHeight(), screen.controlsBar.getY(), .01);
        var search = navigation.getChildren().get(1);
        for (int i = 0; i < 4; i++) {
            var tab = tab(i);
            assertEquals(search.getHeight(), tab.getHeight(), "Search and navigation share one control height");
            if (navigation.getHeight() == 44) assertEquals(search.getY(), tab.getY());
            assertTrue(tab.getX() >= 0 && tab.getX() + tab.getWidth() <= screen.getPanelWidth());
            assertTrue(tab.getY() >= search.getY() + search.getHeight()
                    || tab.getX() + tab.getWidth() <= search.getX(), "Tabs must not overlap search");
        }
    }

    private void click(AbstractWidget<?> widget) throws Exception {
        int x = (int) (widget.getX() + widget.getWidth() * .5);
        int y = (int) (widget.getY() + widget.getHeight() * .5);
        render(x, y);
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private void settle() throws Exception {
        for (int i = 0; i < 45; i++) {
            render(-1, -1);
            Display.processMessages();
            Thread.sleep(10);
        }
    }

    private void render(int x, int y) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen(x, y);
    }

    private static BufferedImage artwork(int seed) {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(seed * .13f, .4f, .75f),
                128, 128, Color.getHSBColor(seed * .13f + .15f, .6f, .3f)));
        g.fillRect(0, 0, 128, 128);
        g.setColor(new Color(255, 255, 255, 60));
        g.fillOval(25, 25, 78, 78);
        g.dispose();
        return image;
    }

    private static void save(String name) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = ((height - y - 1) * width + x) * 4;
            image.setRGB(x, y, ((pixels.get(offset) & 255) << 16) | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255));
        }
        Path output = Path.of("target", "ui-preview", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
