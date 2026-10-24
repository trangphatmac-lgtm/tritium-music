package tritium.desktop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.PlaylistPanel;
import tritium.screens.ncm.panels.SettingsPanel;
import tritium.utils.Location;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real desktop shaders, UI routing, missing artwork, resizing, and GL state restoration. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class CoverRenderingTest {
    private NCMScreen screen;
    private boolean direct;
    private Location source;
    private final BackgroundPreferences bg = DesktopAppState.preferences().background();

    @Test void coverEffectsRenderAndFollowTheVisiblePage() throws Exception {
        var original = DesktopAppState.preferences().toJsonObject();
        var oldSong = CloudMusic.currentlyPlaying;
        var oldProfile = CloudMusic.profile;
        Music song = new Gson().fromJson("""
                {"id":-827,"name":"Cover background preview","ar":[],"al":{"id":-827,"name":"Test"},"dt":60000}
                """, Music.class);
        PlayList playlist = new Gson().fromJson("""
                {"id":-881,"name":"Cover gradient playlist","coverImgUrl":"","trackCount":0,
                 "creator":{"userId":-20,"nickname":"Tritium","avatarUrl":""},"description":"Cover rendering test"}
                """, PlayList.class);
        playlist.musics = List.of(); playlist.musicsLoaded = playlist.musicsQueried = true;
        try {
            DesktopNativeLoader.configureLibraryPath();
            invoke("createDisplay"); Display.update(); invoke("initializeOpenGL");
            Framebuffer.updateMcFramebuffer(); DesktopAppState.markMainThread(); new FontManager().init();
            DesktopAppState.preferences().appearance().setValue("Dark");
            DesktopAppState.preferences().interfaceStyle().setValue("Static");
            CloudMusic.profile = null;
            screen = new NCMScreen(); screen.initGui();
            CloudMusic.profile = new User(-20, "Tritium", "", 0, "");
            CloudMusic.currentlyPlaying = song;
            put(song.getCoverLocation(), flat(0xFF307FC0));
            put(song.getBlurredCoverLocation(), flat(0xFF307FC0));
            put(playlist.getCoverLocation(), flat(0xFF909438));
            put(playlist.getCreator().getAvatarLocation(), flat(0xFF909438));
            bg.mode().setValue("cover"); bg.coverMode().setValue("solid");
            screen.setCurrentPanel(new PlaylistPanel(playlist));
            settle();
            BufferedImage solid = capture();
            assertTrue(channel(solid, 16, .99, .1) > channel(solid, 0, .99, .1) + 25);
            assertTrue(channel(solid, 16, .99, .1) > channel(solid, 16, .99, .85) + 20);
            save("solid-playlist", solid);
            screen.musicLyricsPanel = new MusicLyricsPanel(song);
            var alpha = MusicLyricsPanel.class.getDeclaredField("alpha"); alpha.setAccessible(true); alpha.setFloat(screen.musicLyricsPanel, 1);
            settle();
            BufferedImage lyrics = capture();
            assertTrue(DesktopAppState.background().isAppliedToLyrics());
            assertTrue(channel(lyrics, 0, .99, .1) > channel(lyrics, 16, .99, .1) + 30, "Lyrics must use blue song, not yellow playlist");
            save("solid-lyrics", lyrics);
            bg.coverMode().setValue("blur"); settle();
            int[] blurPixels = pixels(capture());
            bg.mode().setValue("static"); settle();
            assertArrayEquals(blurPixels, pixels(capture()), "Cover Blur must exactly preserve the original lyrics background");
            bg.mode().setValue("cover"); settle();
            assertFalse(DesktopAppState.background().isAppliedToLyrics());
            save("blur-lyrics", capture());
            screen.musicLyricsPanel = null;
            SettingsPanel settings = new SettingsPanel(); screen.setCurrentPanel(settings); settle();
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            assertFalse(row(scroll, bg.coverMode()).isHidden());
            assertTrue(row(scroll, bg.nomandEffect()).isHidden());
            assertTrue(row(scroll, bg.monetLayout()).isHidden());
            assertTrue(row(scroll, bg.applyToLyrics()).isHidden());
            AbstractWidget<?> submode = row(scroll, bg.coverMode());
            scroll.actualScrollOffset = scroll.targetScrollOffset = (row(scroll, bg.mode()).getY() - scroll.getY()) - 8;
            frame();
            click(settings, submode);
            assertEquals("nomand", bg.coverMode().getValue());
            settle();
            assertFalse(row(scroll, bg.nomandEffect()).isHidden());
            assertTrue(row(scroll, bg.monetLayout()).isHidden());
            save("settings-nomand", capture());
            click(settings, submode);
            assertEquals("monet", bg.coverMode().getValue());
            settle();
            assertTrue(row(scroll, bg.nomandEffect()).isHidden());
            assertFalse(row(scroll, bg.monetLayout()).isHidden());
            save("settings-monet", capture());

            // Direct backgrounds expose the whole output to pixel checks and screenshots.
            direct = true; source = song.getCoverLocation(); put(source, artwork());
            for (String theme : new String[]{"Dark", "Light"}) {
                DesktopAppState.preferences().appearance().setValue(theme);
                bg.coverMode().setValue("nomand");
                BufferedImage previous = null;
                for (String effect : bg.nomandEffect().getModes()) {
                    bg.nomandEffect().setValue(effect); settle();
                    assertTrue(DesktopAppState.background().isActive(), DesktopAppState.background().status());
                    BufferedImage image = capture();
                    assertTrue(variation(image) > 20, effect + " must draw the source artwork");
                    if (previous != null) assertFalse(Arrays.equals(pixels(previous), pixels(image)), "Distinct shader effects");
                    save(theme + "-nomand-" + effect, image); previous = image;
                }
                bg.coverMode().setValue("monet");
                for (String layout : bg.monetLayout().getModes()) {
                    bg.monetLayout().setValue(layout); settle();
                    assertTrue(variation(capture()) > 100);
                    save(theme + "-monet-" + layout, capture());
                }
                bg.coverMode().setValue("blur"); settle(); save(theme + "-blur", capture());
            }
            Display.setDisplayMode(new DisplayMode(800, 680));
            MacOpenGLSurface.enableHighResolution(); Display.update();
            bg.coverMode().setValue("nomand"); bg.nomandEffect().setValue("paper-texture"); settle();
            assertTrue(variation(capture()) > 100); save("resized-paper", capture());
            // Missing artwork clears the previous picture; late arrival must be noticed without navigation.
            source = Location.of("tritium/test/missing-cover.png");
            bg.coverMode().setValue("solid"); settle();
            assertEquals(1, variation(capture()));
            put(source, flat(0xFF40C080)); settle();
            assertTrue(variation(capture()) > 20);
            bg.mode().setValue("static"); settle();
            assertFalse(DesktopAppState.background().isActive());
            bg.mode().setValue("cover"); bg.coverMode().setValue("nomand"); settle();
            assertTrue(DesktopAppState.background().isActive());
        } finally {
            CloudMusic.currentlyPlaying = oldSong; CloudMusic.profile = oldProfile;
            DesktopAppState.preferences().loadFromJsonObject(original);
            if (Display.isCreated()) {
                DesktopAppState.background().reload();
                if (screen != null) screen.onGuiClosed();
                FontManager.deleteLoadedTextures(); Display.destroy();
            }
        }
    }

    private void settle() throws Exception {
        // Includes async CPU treatment and the original 0.8-second crossfade.
        long end = System.nanoTime() + 1_600_000_000L;
        do { frame(); } while (System.nanoTime() < end);
    }
    private void frame() throws Exception {
        DesktopAppState.pumpMainThreadTasks(); Framebuffer.updateMcFramebuffer(); Interpolations.calcFrameDelta(); AppTheme.beginFrame();
        invoke("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        int fbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        if (direct) DesktopAppState.background().render(true, source, false); else screen.drawScreen(-1, -1);
        assertEquals(fbo, GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT));
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        assertFalse(DesktopAppState.background().status().contains("失败"), DesktopAppState.background().status());
        Display.processMessages(); Thread.sleep(12);
    }
    private void click(SettingsPanel settings, AbstractWidget<?> row) throws Exception {
        int x = (int) row.getX() + 20, y = (int) row.getY() + 20;
        screen.drawScreen(x, y);
        settings.onMouseClickReceived(x, y, 0);
        settings.onMouseReleaseReceived(x, y, 0);
        frame();
    }
    private static AbstractWidget<?> row(ScrollPanel scroll, PreferenceValue<?> preference) throws Exception {
        for (AbstractWidget<?> row : scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).toList()) {
            try { var field = row.getClass().getDeclaredField("preference"); field.setAccessible(true); if (field.get(row) == preference) return row; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError(preference.getName());
    }
    private static void put(Location location, BufferedImage image) { TextureManager.getInstance().loadTexture(location, new DynamicTexture(image)); }
    private static BufferedImage flat(int color) {
        BufferedImage image = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setColor(new Color(color)); g.fillRect(0,0,320,320); g.dispose(); return image;
    }
    private static BufferedImage artwork() {
        BufferedImage image = flat(0xFF704030);
        Graphics2D g = image.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0,0,new Color(0xEFC98E),320,320,new Color(0x304E6B)));
        g.fillRect(0,0,320,320); g.setColor(new Color(0x222A28)); g.fillOval(70,40,180,240);
        g.setColor(new Color(0xDDC1A3)); g.fillOval(108,58,104,104); g.setColor(Color.WHITE); g.fillRect(60,200,200,70);
        g.setColor(new Color(0x253830)); g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,26));
        g.drawString("TRITIUM",93,244); g.dispose(); return image;
    }
    private static int[] pixels(BufferedImage image) { return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()); }
    private static long variation(BufferedImage image) { return Arrays.stream(pixels(image)).distinct().count(); }
    private static int channel(BufferedImage image,int shift,double x,double y) { return (image.getRGB((int)(x*image.getWidth()),(int)(y*image.getHeight())) >> shift)&255; }
    private static BufferedImage capture() {
        int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight(); ByteBuffer p=BufferUtils.createByteBuffer(w*h*4);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p); BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++){int i=((h-y-1)*w+x)*4; image.setRGB(x,y,((p.get(i)&255)<<16)|((p.get(i+1)&255)<<8)|(p.get(i+2)&255));} return image;
    }
    private static void save(String name,BufferedImage image) throws Exception { Path path=Path.of("target/cover-previews",name+".png"); Files.createDirectories(path.getParent()); ImageIO.write(image,"png",path.toFile()); }
    private static void invoke(String name) throws Exception { var method=TritiumMusicDesktopApp.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(null); }
}
