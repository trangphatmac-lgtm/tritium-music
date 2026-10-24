package tritium.screens.ncm;

import com.google.gson.Gson;
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
import tritium.ncm.music.dto.*;
import tritium.rendering.*;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.screens.ncm.panels.*;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tritium.testRendering",matches="true")
class NavigationModesRenderingTest {
    private final NCMScreen screen=NCMScreen.getInstance();
    @Test void solidWallKeepsBackdropDuringEntrance() throws Exception {
        var nav=DesktopAppState.preferences().navigation(); var saved=nav.toJsonObject();
        var view=new tritium.screens.ncm.navigation.CoverNavigationView(
                tritium.screens.ncm.navigation.NavigationContent.playlists("backdrop-test",()->"Test",
                        ()->List.of(new PlayList(9900,"Test","",1,0,null,"",false,0,0)),()->false));
        try {
            DesktopNativeLoader.configureLibraryPath(); invoke("createDisplay"); Display.update(); invoke("initializeOpenGL");
            Framebuffer.updateMcFramebuffer(); DesktopAppState.markMainThread(); new FontManager().init();
            nav.mode().setValue("Wall"); nav.wallLook().setValue("solid");
            nav.backdropBlur().setValue(false); nav.backdropLyrics().setValue(false);
            AppTheme.beginFrame(); invoke("prepareFrameState");
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
            double w=RenderSystem.getWidth(),h=RenderSystem.getHeight();
            // A two-colour scene makes replacement by any uniform backing plate detectable.
            Rect.draw(0,0,w/2,h,0xFF285080); Rect.draw(w/2,0,w/2,h,0xFF805028);
            int left=pixel(w*.25,h*.25),right=pixel(w*.75,h*.25);
            view.render(0,0,w,h,-1,-1,0);
            assertEquals(left,pixel(w*.25,h*.25),"Solid wall entrance must retain the scene underneath");
            assertEquals(right,pixel(w*.75,h*.25));
            assertNotEquals(left,right);
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        } finally {
            view.leave(); nav.loadFromJsonObject(saved);
            if(Display.isCreated()) { UiMaterials.release(); FontManager.deleteLoadedTextures(); Display.destroy(); }
        }
    }
    @Test void rendersModesInsideBothShellsAndNavigatesInSourceOrder() throws Exception {
        var prefs=DesktopAppState.preferences();
        var nav=prefs.navigation();
        var saved=nav.toJsonObject();
        String oldLayout=prefs.layout().getValue();
        String oldMaterial=prefs.interfaceStyle().getValue();
        User oldProfile=CloudMusic.profile;
        var oldLists=CloudMusic.playLists;
        var homeField=HomePanel.class.getDeclaredField("playLists"); homeField.setAccessible(true);
        @SuppressWarnings("unchecked") var home=(List<PlayList>)homeField.get(null);
        var oldHome=List.copyOf(home);
        try {
            DesktopNativeLoader.configureLibraryPath(); invoke("createDisplay"); Display.update(); invoke("initializeOpenGL");
            Framebuffer.updateMcFramebuffer(); DesktopAppState.markMainThread(); new FontManager().init();
            CloudMusic.profile=null; screen.initGui();
            User user=new User(812,"Preview","",0,""); CloudMusic.profile=user;
            TextureManager.getInstance().loadTexture(user.getAvatarLocation(),new DynamicTexture(art(0)));
            List<Music> tracks=new ArrayList<>();
            for(int i=0;i<80;i++) {
                Music song=new Gson().fromJson("{\"id\":"+(8500+i)+",\"name\":\"Song "+(i+1)+" · 风与海\",\"ar\":[{\"name\":\"Artist\"}],\"al\":{\"id\":10,\"name\":\"Shared album\"},\"dt\":180000}",Music.class);
                tracks.add(song); TextureManager.getInstance().loadTexture(song.getCoverLocation(),new DynamicTexture(art(i)));
                TextureManager.getInstance().loadTexture(song.getSmallCoverLocation(),new DynamicTexture(art(i)));
            }
            List<PlayList> playlists=new ArrayList<>();
            String[] names={"午后漫游","山海之间","城市夜航","Daydreams","深蓝色的夏天","晨光与远方"};
            for(int i=0;i<32;i++) {
                PlayList playlist=new PlayList(8800+i,names[i%names.length],"",80,0,user,"",i>=20,0,0);
                playlist.musics=List.copyOf(tracks); playlist.musicsLoaded=true; playlists.add(playlist);
                TextureManager.getInstance().loadTexture(playlist.getCoverLocation(),new DynamicTexture(art(i)));
            }
            CloudMusic.playLists=playlists; home.clear(); home.addAll(playlists.subList(2,14));
            nav.mode().setValue("Coverflow"); nav.coverflow().setValue("Card"); nav.rememberPosition().setValue(true);
            for(String layout:List.of("TopBar","Sidebar")) {
                prefs.layout().setValue(layout);
                screen.setCurrentPanel(new PlaylistLibraryPanel(screen,false)); settle();
                var controls=screen.getControlsBar();
                save("navigation-card-polaroid-"+layout);
                assertTrue(screen.currentPanelBg.getWidth()<RenderSystem.getWidth() || layout.equals("TopBar"));
                assertEquals(screen.currentPanelBg.getY()+screen.currentPanelBg.getHeight(),controls.getY(),.01);
                var library=screen.getCurrentPanel();
                screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
                verifyFilterAndDrag(library);
                screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
                screen.keyTyped('\0',Keyboard.KEY_RIGHT); settle();
                assertEquals("playlist:8801",nav.position("mine"));
                screen.keyTyped('\n',Keyboard.KEY_RETURN); settle();
                assertInstanceOf(PlaylistPanel.class,screen.getCurrentPanel());
                assertEquals(8801,((PlaylistPanel)screen.getCurrentPanel()).playList.getId());
                assertEquals(80,screen.getCurrentPanel().getCoverNavigation().content().entries().size());
                save("navigation-card-tracks-"+layout);
                screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
                screen.keyTyped('\0',Keyboard.KEY_RIGHT); settle();
                assertEquals("8501:1",nav.position("playlist:8801"));
                screen.keyTyped('\0',Keyboard.KEY_ESCAPE); settle();
                assertSame(library,screen.getCurrentPanel());
                assertEquals("playlist:8801",nav.position("mine"));
                nav.cardStyle().setValue("image"); settle(); save("navigation-card-artwork-"+layout);
                nav.cardStyle().setValue("card"); nav.fullBleed().setValue(true); nav.squareCards().setValue(true);
                settle(); save("navigation-card-square-"+layout);
                nav.fullBleed().setValue(false); nav.squareCards().setValue(false);
                for(String look:List.of("solid","partial","clear")) {
                    nav.mode().setValue("Wall"); nav.wallLook().setValue(look); settle();
                    if (look.equals("clear")) verifyTransparentTile();
                    save("navigation-wall-"+look+"-"+layout);
                    assertSame(controls,screen.getControlsBar());
                    screen.keyTyped('\0',Keyboard.KEY_END); settle();
                    assertEquals("playlist:8819",nav.position("mine"));
                    screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
                }
                nav.backdropBlur().setValue(true); nav.backdropLyrics().setValue(true);
                CloudMusic.currentLyric=new LyricLine(0,"让风带着我 去远方"); settle(); save("navigation-wall-backdrop-"+layout);
                nav.backdropBlur().setValue(false); nav.backdropLyrics().setValue(false); CloudMusic.currentLyric=null;
                nav.mode().setValue("Coverflow");
                for(String style:List.of("OldSchool","Mirror")) {
                    nav.coverflow().setValue(style); settle(); save("navigation-"+style+"-"+layout);
                    // All fullscreen exit paths must return to a usable page.
                    screen.keyTyped('\0',Keyboard.KEY_RIGHT); settle();
                    screen.keyTyped('\n',Keyboard.KEY_RETURN); settle(); assertInstanceOf(PlaylistPanel.class,screen.getCurrentPanel());
                    screen.keyTyped('\0',Keyboard.KEY_ESCAPE); settle(); assertSame(library,screen.getCurrentPanel());
                }
                nav.coverflow().setValue("Card");
                screen.setCurrentPanel(new HomePanel()); settle(); save("navigation-explore-"+layout);
                assertEquals(12,screen.getCurrentPanel().getCoverNavigation().content().entries().size());
                screen.setCurrentPanel(new PlaylistLibraryPanel(screen,true)); settle();
                assertEquals(12,screen.getCurrentPanel().getCoverNavigation().content().entries().size());
            }
            verifySparseLayoutsAndMotion(playlists);
            verifyGlassBackdrop();
            verifyCoverflowDetailChrome(playlists);
            nav.mode().setValue("Wall"); nav.wallLook().setValue("partial");
            Display.setDisplayMode(new DisplayMode(900,640));
            var highResolution=Class.forName("tritium.desktop.MacOpenGLSurface").getDeclaredMethod("enableHighResolution");
            highResolution.setAccessible(true); highResolution.invoke(null);
            Display.update(); invoke("initializeOpenGL"); Framebuffer.updateMcFramebuffer();
            settle(); save("navigation-wall-resized");
            screen.setCurrentPanel(new SettingsPanel()); settle();
            verifySettingsControls();
            nav.mode().setValue("List"); screen.setCurrentPanel(new PlaylistLibraryPanel(screen,false)); settle(); save("navigation-list");
        } finally {
            screen.onGuiClosed(); nav.loadFromJsonObject(saved); prefs.layout().setValue(oldLayout); prefs.interfaceStyle().setValue(oldMaterial);
            CloudMusic.profile=oldProfile; CloudMusic.playLists=oldLists; CloudMusic.currentLyric=null;
            home.clear(); home.addAll(oldHome);
            if(Display.isCreated()) { UiMaterials.release(); FontManager.deleteLoadedTextures(); Display.destroy(); }
        }
    }
    private void verifyGlassBackdrop() throws Exception {
        var prefs=DesktopAppState.preferences(); var nav=prefs.navigation();
        nav.mode().setValue("Wall"); nav.wallLook().setValue("solid");
        for(String layout:List.of("TopBar","Sidebar")) for(String material:List.of("Blur","LiquidGlass")) {
            prefs.layout().setValue(layout); prefs.interfaceStyle().setValue(material);
            screen.setCurrentPanel(new PlaylistLibraryPanel(screen,false)); settle();
            save("navigation-wall-"+layout+"-"+material);
            var ready=UiMaterials.class.getDeclaredField("ready"); ready.setAccessible(true); assertTrue(ready.getBoolean(null));
            var field=UiMaterials.class.getDeclaredField("scene"); field.setAccessible(true);
            int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight();
            var pixels=BufferUtils.createByteBuffer(w*h*4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,field.getInt(null));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            // The captured scene must contain covers under ALL shell regions, before glass is drawn.
            for(double[] region:new double[][]{{.5,.035},{.05,.5},{.5,.965}}) {
                int different=0;
                for(int dx=-10;dx<=10;dx+=5) {
                    int px=(int)(region[0]*w)+dx,py=(int)((1-region[1])*h),i=(py*w+px)*4;
                    int rgb=((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255);
                    if(rgb!=(AppTheme.background()&0xFFFFFF)) different++;
                }
                assertTrue(different>=3,"Covers must be present behind navigation and transport before material capture");
            }
            var library=screen.getCurrentPanel();
            if(layout.equals("TopBar")) {
                var searchField=NCMScreen.class.getDeclaredField("searchBar"); searchField.setAccessible(true);
                var search=(MusicSearchBar)searchField.get(screen);
                screen.mouseClicked((int)search.getX()+10,(int)search.getY()+10,0);
                screen.mouseReleased((int)search.getX()+10,(int)search.getY()+10,0);
                assertSame(library,screen.getCurrentPanel(),"Glass controls must not activate the wall behind them");
                screen.keyTyped('\0',Keyboard.KEY_ESCAPE);
            }
        }
        prefs.interfaceStyle().setValue("Static");
    }
    private void verifyCoverflowDetailChrome(List<PlayList> playlists) throws Exception {
        var prefs=DesktopAppState.preferences(); var nav=prefs.navigation();
        String appearance=prefs.appearance().getValue();
        String coverflow=nav.coverflow().getValue();
        var empty=new PlayList(9999,"Empty playlist","",0,0,CloudMusic.profile,"",false,0,0);
        empty.musics=List.of(); empty.musicsLoaded=true;
        CloudMusic.playLists=List.of();
        try {
            for(String theme:List.of("Dark","Light")) for(String layout:List.of("TopBar","Sidebar"))
                for(String style:List.of("Card","Mirror","OldSchool")) {
                    prefs.appearance().setValue(theme); prefs.layout().setValue(layout);
                    nav.mode().setValue("Coverflow"); nav.coverflow().setValue(style);
                    var library=new PlaylistLibraryPanel(screen,false);
                    screen.setCurrentPanel(library);
                    for(int i=0;i<40;i++) frame(-1,-1);
                    double x=RenderSystem.getWidth()-5,y=100;
                    int root=pixel(x,y);
                    assertEquals(AppTheme.background()&0xFFFFFF,root,"The library must not have a dark overlay: "+style);
                    screen.setCurrentPanel(new PlaylistPanel(empty));
                    for(int i=0;i<40;i++) frame(-1,-1);
                    int detail=pixel(x,y);
                    for(int shift:new int[]{0,8,16})
                        assertEquals(((root>>shift)&255)*.5,(detail>>shift)&255,2,"Every Coverflow detail dims its background: "+style);
                    var backField=NCMScreen.class.getDeclaredField("coverBackButton"); backField.setAccessible(true);
                    var back=(tritium.rendering.ui.widgets.RoundedRectWidget)backField.get(screen);
                    assertTrue(back.getX()>=screen.currentPanelBg.getX());
                    assertTrue(back.getY()>=screen.basePanel.getChildren().getFirst().getY());
                    int mx=(int)(back.getX()+back.getWidth()/2),my=(int)(back.getY()+back.getHeight()/2);
                    screen.mouseClicked(mx,my,0); screen.mouseReleased(mx,my,0);
                    assertSame(library,screen.getCurrentPanel(),"The back control returns exactly one page: "+style);
                    for(int i=0;i<40;i++) frame(-1,-1);
                    assertEquals(root,pixel(x,y),"Returning to the library removes the shade: "+style);
                }
        } finally {
            prefs.appearance().setValue(appearance); nav.coverflow().setValue(coverflow); CloudMusic.playLists=playlists;
        }
    }
    private void verifySparseLayoutsAndMotion(List<PlayList> playlists) throws Exception {
        var prefs=DesktopAppState.preferences(); var nav=prefs.navigation(); prefs.layout().setValue("TopBar");
        nav.wallLook().setValue("solid"); nav.mode().setValue("Wall");
        TextureManager.getInstance().deleteTexture(playlists.get(4).getCoverLocation());
        for(int count:new int[]{1,2,5}) {
            CloudMusic.playLists=List.copyOf(playlists.subList(0,count));
            screen.setCurrentPanel(new PlaylistLibraryPanel(screen,false)); settle();
            save("navigation-wall-sparse-"+count);
            var view=screen.getCurrentPanel().getCoverNavigation();
            if(count==5) {
                var zoom=view.getClass().getDeclaredField("zoom"); zoom.setAccessible(true);
                var layoutKey=view.getClass().getDeclaredField("layoutKey"); layoutKey.setAccessible(true);
                for(double scale:new double[]{.6,2.5,1}) {
                    zoom.setDouble(view,scale); layoutKey.set(view,"zoom"); settle();
                    save("navigation-wall-seams-zoom-"+scale);
                }
            }
            var field=view.getClass().getDeclaredField("hits"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var hits=(List<tritium.screens.ncm.navigation.FoliaGeometry.Box>)field.get(view);
            int covered=0,total=0;
            for(double y=screen.currentPanelBg.getY()+8;y<screen.currentPanelBg.getY()+screen.currentPanelBg.getHeight()-8;y+=9)
                for(double x=8;x<screen.currentPanelBg.getWidth()-8;x+=9) {
                    final double px=x,py=y; total++;
                    if(hits.stream().anyMatch(b->b.contains(px,py))) covered++;
                }
            assertTrue(covered/(double)total>.94,"The wall must fill the viewport even with "+count+" playlist(s)");
            var tile=hits.stream().filter(b->b.centerX()>50&&b.centerX()<screen.currentPanelBg.getWidth()-50&&b.centerY()>screen.currentPanelBg.getY()+40&&b.centerY()<screen.currentPanelBg.getY()+screen.currentPanelBg.getHeight()-40).findFirst().orElseThrow();
            int mx=(int)tile.centerX(),my=(int)tile.centerY();
            int rest=pixel(mx,my);
            for(int i=0;i<40;i++) frame(mx,my);
            int hover=pixel(mx,my);
            assertNotEquals(rest,hover,"Hover must change the artwork lighting");
            save("navigation-wall-hover-"+count);
            if(count==5) {
                screen.mouseClicked(mx,my,0); screen.mouseReleased(mx,my,0); frame(mx,my);
                assertInstanceOf(PlaylistPanel.class,screen.getCurrentPanel());
                for(int i=0;i<5;i++) frame(mx,my);
                save("navigation-wall-flip-middle");
                settle(); save("navigation-wall-tracks");
                screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
                screen.keyTyped('\n',Keyboard.KEY_RETURN);
                for(int i=0;i<5;i++) frame(-1,-1);
                save("navigation-wall-reflow-middle"); settle(); save("navigation-wall-expanded");
                var expanded=screen.getCurrentPanel().getCoverNavigation().getClass().getDeclaredField("expanded"); expanded.setAccessible(true);
                assertNotNull(expanded.get(screen.getCurrentPanel().getCoverNavigation()));
                screen.keyTyped('\0',Keyboard.KEY_ESCAPE); settle();
                assertInstanceOf(PlaylistPanel.class,screen.getCurrentPanel(),"Escape collapses the focused song before leaving");
            }
        }
        nav.mode().setValue("Coverflow"); nav.coverflow().setValue("Card"); nav.cardStyle().setValue("card");
        nav.rememberPosition().setValue(false);
        screen.setCurrentPanel(new PlaylistLibraryPanel(screen,false)); settle();
        screen.keyTyped('\0',Keyboard.KEY_HOME); screen.keyTyped('\0',Keyboard.KEY_RIGHT); screen.keyTyped('\0',Keyboard.KEY_RIGHT); settle();
        save("navigation-card-sparse-five");
        var view=screen.getCurrentPanel().getCoverNavigation();
        var geom=view.getClass().getDeclaredField("geometry"); geom.setAccessible(true);
        @SuppressWarnings("unchecked") var cards=(List<tritium.screens.ncm.navigation.FoliaGeometry.Box>)geom.get(view);
        assertEquals(1,cards.stream().map(tritium.screens.ncm.navigation.FoliaGeometry.Box::centerY).distinct().count());
        var hitField=view.getClass().getDeclaredField("hits"); hitField.setAccessible(true);
        @SuppressWarnings("unchecked") var visible=(List<tritium.screens.ncm.navigation.FoliaGeometry.Box>)hitField.get(view);
        assertEquals(5,visible.size(),"A centred library carousel must show two cards on each side");
        assertTrue(visible.stream().allMatch(b->b.x()>=screen.currentPanelBg.getX()
                &&b.x()+b.width()<=screen.currentPanelBg.getX()+screen.currentPanelBg.getWidth()),"All five cards fit the safe area");
        double libraryWidth=visible.stream().filter(b->b.index()==2).findFirst().orElseThrow().width();
        screen.keyTyped('\n',Keyboard.KEY_RETURN);
        frame(-1,-1);
        var detail=screen.getCurrentPanel().getCoverNavigation();
        var transition=detail.getClass().getDeclaredField("transitionAt"); transition.setAccessible(true);
        for(int age:new int[]{150,300,450,600}) {
            transition.setLong(detail,System.nanoTime()/1_000_000-age);
            frame(-1,-1); save("navigation-card-flip-"+age);
            var paintedField=detail.getClass().getDeclaredField("painted"); paintedField.setAccessible(true);
            var painted=(List<?>)paintedField.get(detail);
            int sleeves=0,songs=0;
            for(Object face:painted) {
                var boxMethod=face.getClass().getDeclaredMethod("box"); boxMethod.setAccessible(true);
                var box=(tritium.screens.ncm.navigation.FoliaGeometry.Box)boxMethod.invoke(face);
                var entryMethod=face.getClass().getDeclaredMethod("entry"); entryMethod.setAccessible(true);
                var entry=(tritium.screens.ncm.navigation.NavigationContent.Entry)entryMethod.invoke(face);
                if(entry.key().equals("playlist:8802")) sleeves++;
                else if(!entry.collection()&&box.index()==0) {
                    songs++;
                    if(age==450) for(double fraction:new double[]{.55,.6,.65,.7,.75})
                        assertTrue(((pixel(box.x()+box.width()*fraction,box.y()+box.height()*.4)>>16)&255)>70,
                                "The turning song artwork must stay solid, without depth stripes or clipped glyph planes");
                }
            }
            assertTrue(sleeves+songs<=1,"The flight slot must never paint both the old sleeve and the new song");
            if(age==150) assertEquals(0,songs,"The new song stays hidden until the sleeve turns edge-on");
            if(age>=450) assertEquals(0,sleeves,"The old sleeve is gone after the flip midpoint");
        }
        settle();
        @SuppressWarnings("unchecked") var songs=(List<tritium.screens.ncm.navigation.FoliaGeometry.Box>)hitField.get(detail);
        double songWidth=songs.stream().filter(b->b.index()==0).findFirst().orElseThrow().width();
        assertTrue(libraryWidth>songWidth&&libraryWidth<songWidth*1.2,"The library sleeve is only slightly wider than a song card");
        CloudMusic.playLists=playlists;
    }
    private static int pixel(double x,double y) {
        int px=(int)(x*RenderSystem.getFramebufferWidth()/RenderSystem.getWidth());
        int py=(int)((RenderSystem.getHeight()-y)*RenderSystem.getFramebufferHeight()/RenderSystem.getHeight());
        var data=BufferUtils.createByteBuffer(4); GL11.glReadPixels(px,py,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
        return ((data.get(0)&255)<<16)|((data.get(1)&255)<<8)|(data.get(2)&255);
    }
    private void verifySettingsControls() throws Exception {
        var settings=(SettingsPanel)screen.getCurrentPanel();
        var scroll=(tritium.rendering.ui.container.ScrollPanel)settings.getChildren().get(2);
        var mode=settingRow(scroll,"settings.navigationMode");
        var style=settingRow(scroll,"settings.coverflowMode");
        var card=settingRow(scroll,"settings.cardStyle");
        var wall=settingRow(scroll,"settings.wallLook");
        var prefs=DesktopAppState.preferences().navigation();
        prefs.mode().setValue("List"); settle();
        scroll.actualScrollOffset=scroll.targetScrollOffset=scroll.actualScrollOffset+(mode.getY() - scroll.getY())-12;
        settle();
        click(mode); settle(); assertEquals("Coverflow",prefs.mode().getValue());
        assertFalse(style.isHidden()); assertFalse(card.isHidden()); assertTrue(wall.isHidden());
        save("navigation-settings-card");
        click(style); settle(); assertEquals("OldSchool",prefs.coverflow().getValue()); assertTrue(card.isHidden());
        click(mode); settle(); assertEquals("Wall",prefs.mode().getValue());
        assertTrue(style.isHidden()); assertFalse(wall.isHidden());
        save("navigation-settings-wall");
    }
    private void click(tritium.rendering.ui.AbstractWidget<?> widget) {
        int x=(int)(widget.getX()+20), y=(int)(widget.getY()+widget.getHeight()/2);
        screen.mouseClicked(x,y,0); screen.mouseReleased(x,y,0);
    }
    private static tritium.rendering.ui.AbstractWidget<?> settingRow(tritium.rendering.ui.container.ScrollPanel scroll,String key) {
        return scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).filter(row -> {
            try { var label=row.getClass().getSuperclass().getDeclaredField("label"); label.setAccessible(true); return key.equals(label.get(row)); }
            catch(ReflectiveOperationException ignored) { return false; }
        }).findFirst().orElseThrow();
    }
    private void verifyTransparentTile() throws Exception {
        var view=screen.getCurrentPanel().getCoverNavigation();
        var field=view.getClass().getDeclaredField("hits"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var hits=(List<tritium.screens.ncm.navigation.FoliaGeometry.Box>)field.get(view);
        var tile=hits.stream().filter(box->box.index()==1 && box.centerX()>screen.currentPanelBg.getX()+10 && box.centerX()<screen.currentPanelBg.getX()+screen.currentPanelBg.getWidth()-10 && box.y()>screen.currentPanelBg.getY()).findFirst().orElseThrow();
        int px=(int)(tile.centerX()*RenderSystem.getFramebufferWidth()/RenderSystem.getWidth());
        int py=(int)((RenderSystem.getHeight()-tile.y()-10)*RenderSystem.getFramebufferHeight()/RenderSystem.getHeight());
        var pixel=BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(px,py,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
        int rgb=((pixel.get(0)&255)<<16)|((pixel.get(1)&255)<<8)|(pixel.get(2)&255);
        assertEquals(AppTheme.background()&0xFFFFFF,rgb,"Clear tiles must reveal the backdrop even with stack edges enabled");
    }
    private void verifyFilterAndDrag(NCMPanel library) throws Exception {
        var view=library.getCoverNavigation();
        var searchField=NCMScreen.class.getDeclaredField("searchBar"); searchField.setAccessible(true);
        var search=(MusicSearchBar)searchField.get(screen);
        int searchX=(int)(search.getX()+search.getWidth()/2);
        int searchY=(int)(search.getY()+search.getHeight()/2);
        screen.mouseClicked(searchX,searchY,0); screen.mouseReleased(searchX,searchY,0);
        screen.keyTyped('z',Keyboard.KEY_Z); settle();
        var entries=view.getClass().getDeclaredField("entries"); entries.setAccessible(true);
        assertTrue(((List<?>)entries.get(view)).isEmpty());
        screen.keyTyped('\0',Keyboard.KEY_ESCAPE); settle();
        assertSame(library,screen.getCurrentPanel(),"Escape clears a focused filter before leaving the page");
        assertEquals(20,((List<?>)entries.get(view)).size());
        int mx=(int)(screen.currentPanelBg.getX()+screen.currentPanelBg.getWidth()/2);
        int my=(int)(screen.currentPanelBg.getY()+screen.currentPanelBg.getHeight()/2-18);
        screen.mouseClicked(mx,my,0);
        frame(mx+60,my+25);
        screen.mouseReleased(mx+60,my+25,0); settle();
        assertSame(library,screen.getCurrentPanel(),"Panning over a cover must never open it");
        screen.keyTyped('\0',Keyboard.KEY_HOME); settle();
        screen.mouseClicked(mx,my,0); screen.mouseReleased(mx,my,0); settle();
        assertInstanceOf(PlaylistPanel.class,screen.getCurrentPanel(),"The focused cover opens on click");
        screen.mouseClicked(0,0,3); settle();
        assertSame(library,screen.getCurrentPanel(),"Mouse back advances history exactly once");
    }
    private void frame(int mx,int my) throws Exception {
        DesktopAppState.pumpMainThreadTasks(); RenderSystem.setFrameDeltaTime(2); AppTheme.beginFrame(); invoke("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
        screen.drawScreen(mx,my); Display.processMessages(); Thread.sleep(5);
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    private void settle() throws Exception {
        long end=System.nanoTime()+1_550_000_000L;
        do { frame(-1,-1); } while(System.nanoTime()<end);
    }
    private static BufferedImage art(int i) {
        BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics();
        g.setPaint(new GradientPaint(0,0,Color.getHSBColor(i*.13f,.45f,.85f),256,256,Color.getHSBColor(i*.13f+.15f,.7f,.28f)));
        g.fillRect(0,0,256,256); g.setColor(new Color(255,255,255,90)); g.fillOval(48,30,160,160);
        g.setColor(new Color(255,255,255,200)); g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,28)); g.drawString(String.format("%02d",i+1),24,230); g.dispose(); return image;
    }
    private static void save(String name) throws Exception {
        int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight(); var bytes=BufferUtils.createByteBuffer(w*h*4);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes); var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) { int i=((h-y-1)*w+x)*4; image.setRGB(x,y,((bytes.get(i)&255)<<16)|((bytes.get(i+1)&255)<<8)|(bytes.get(i+2)&255)); }
        Path file=Path.of("target/ui-preview",name+".png"); Files.createDirectories(file.getParent()); ImageIO.write(image,"png",file.toFile());
    }
    private static void invoke(String name) throws Exception { var method=TritiumMusicDesktopApp.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(null); }
}
