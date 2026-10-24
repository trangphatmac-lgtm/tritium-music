package tritium.screens.ncm.lyrics;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.*;
import tritium.desktop.*;
import tritium.management.FontManager;
import tritium.ncm.music.*;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;
import tritium.rendering.*;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.screens.ncm.*;
import tritium.screens.ncm.panels.*;
import tritium.utils.json.JsonUtils;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tritium.testRendering",matches="true")
class FoliaLyricsRenderingTest {
    private Framebuffer target;
    private NCMScreen screen;
    @Test void rendersAllNativeStylesAndRoutesBothControllers() throws Exception {
        var prefs=DesktopAppState.preferences();var oldStyle=prefs.lyricStyle().getValue();var oldControls=prefs.playerControls().getValue();
        var oldCollapse=prefs.miniAutoCollapse().getValue();var oldSong=CloudMusic.currentlyPlaying;var oldPlayer=CloudMusic.player;var oldProfile=CloudMusic.profile;
        var oldCurrentLyric=CloudMusic.currentLyric;var oldLyrics=List.copyOf(CloudMusic.lyrics);var oldLikes=CloudMusic.likeList;
        try{
            DesktopNativeLoader.configureLibraryPath();invoke("createDisplay");Display.update();invoke("initializeOpenGL");Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();new FontManager().init();
            target=new Framebuffer(RenderSystem.getFramebufferWidth(),RenderSystem.getFramebufferHeight(),true);
            CloudMusic.profile=null;screen=new NCMScreen();screen.initGui();
            CloudMusic.profile=new User(90005,"Preview","",0,"");CloudMusic.likeList=List.of();
            Music song=JsonUtils.parse("{\"id\":90001,\"name\":\"光穿过时间\",\"ar\":[{\"name\":\"Tritium Native Preview\"}],\"al\":{\"id\":1,\"name\":\"Folia\"}}",Music.class);
            CloudMusic.currentlyPlaying=song;
            BufferedImage art=artwork();TextureManager.getInstance().loadTexture(song.getCoverLocation(),new DynamicTexture(art));
            TextureManager.getInstance().loadTexture(song.getBlurredCoverLocation(),new DynamicTexture(CloudMusic.gaussianBlur(art,24)));
            TextureManager.getInstance().loadTexture(CloudMusic.profile.getAvatarLocation(),new DynamicTexture(art));
            var deck=new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson("{}",FakeDeck.class);deck.position=17500;CloudMusic.player=deck;
            String[] texts={"光穿过漫长的时间","像繁星缓缓落在肩上","让每一个字都有自己的光","The colors drift across the night","我们向着更远的天空","把所有回声留在风里","Listen to the rhythm of the rain","此刻世界轻轻发亮","循着旋律走过四季","每一段旅程都有新的颜色","Let the melody carry us home","星光与梦想一起盛开"};
            CloudMusic.lyrics.clear();
            for(int i=0;i<texts.length;i++){
                var line=new LyricLine(1000+i*4000,texts[i]);line.duration=3500;line.translationText="让旋律带我们走向远方";line.romanizationText="Let the melody carry us home";
                var chars=LyricTimeline.split(texts[i]);for(int j=0;j<chars.size();j++)line.words.add(new LyricLine.Word(chars.get(j),line.timestamp+j*3500/chars.size(),3500/chars.size()));CloudMusic.lyrics.add(line);
            }
            prefs.miniAutoCollapse().setValue(false);screen.musicLyricsPanel=new MusicLyricsPanel(song);
            for(var style:LyricStyle.values()){
                if(style==LyricStyle.Normal)continue;
                prefs.lyricStyle().setValue(style.name());deck.position=17500;
                for(String controls:new String[]{"BottomBar","FoliaMini"}){
                    prefs.playerControls().setValue(controls);settle(20);
                    assertTrue(screen.lyricsHaveControls());var bar=screen.getControlsBar();
                    assertTrue(bar.getWidth()>0);assertTrue(bar.getY()>=0);assertTrue(bar.getY()+bar.getHeight()<=RenderSystem.getHeight()+.1);
                    save(style.name()+"-"+controls);
                    var play=bar.getChildren().stream().filter(IconWidget.class::isInstance).findFirst().orElseThrow();
                    // Both controls put the play/pause widget first; the cover widget isn't an IconWidget.
                    boolean before=deck.paused;click(play.getX()+play.getWidth()/2,play.getY()+play.getHeight()/2);assertNotEquals(before,deck.paused,style+" / "+controls+" play/pause");
                    deck.paused=false;
                    if(controls.equals("FoliaMini")){
                        var progress=bar.getChildren().stream().filter(w->w.getClass().getSimpleName().equals("Progress")).findFirst().orElseThrow();
                        click(progress.getX()+progress.getWidth()/2,progress.getY()+progress.getHeight()/2);
                        assertEquals(30000,deck.position,600,style+" mini seek");
                        screen.mouseClicked((int)(progress.getX()+progress.getWidth()/2),(int)(progress.getY()+3),0);
                        screen.mouseReleased((int)(progress.getX()+progress.getWidth()+80),0,0);
                        assertEquals(60000,deck.position,1,style+" release outside controls");
                        deck.position=17500;
                    }
                    assertEquals(0,GL11.glGetError(),style+" / "+controls+" OpenGL state");
                }
                // A seek backwards must rebuild the current shot and remain finite.
                deck.position=2300;settle(2);deck.position=36000;settle(2);
            }
            // Catalog variations exercise GLSL uniform branches and native tessellation.
            var timeline=new LyricTimeline(CloudMusic.lyrics,60);var canvas=new LyricCanvas();
            for(var style:new LyricStyle[]{LyricStyle.Hanabi,LyricStyle.Lumierei,LyricStyle.Tempera,LyricStyle.Diorama,LyricStyle.Sonnet}){
                try(var scene=style.createScene()){
                    int variants=switch(style){case Hanabi->50;case Lumierei,Sonnet->100;case Tempera->121;default->13;};
                    for(int variant=0;variant<variants;variant++)for(double sample:style==LyricStyle.Hanabi?new double[]{.55,2.4,4.9}:new double[]{2.4}){
                        prepare();GL11.glPushMatrix();GL11.glScaled(RenderSystem.getWidth()/1280,RenderSystem.getHeight()/720,1);canvas.begin(1);
                        scene.reset();scene.render(canvas,new LyricFrame(timeline,sample,1.0/60,true,false,deck.getEnergy(),song,variant,1280,720,70,0xffce8bdb,true,false));
                        GL11.glPopMatrix();assertEquals(0,GL11.glGetError(),style+" variant "+variant);
                    }
                }
            }
            verifyEnglish(deck);
            // Every scene, including Monet's opaque cover compositor, must fade completely out.
            try(var host=new FoliaLyrics()){
                for(var style:LyricStyle.values())if(style!=LyricStyle.Normal){
                    prepare();Rect.draw(0,0,RenderSystem.getWidth(),RenderSystem.getHeight(),0xff203040);
                    host.render(style,0,0,RenderSystem.getWidth(),RenderSystem.getHeight(),-1,-1,0,64);
                    var pixel=BufferUtils.createByteBuffer(4);GL11.glReadPixels(RenderSystem.getFramebufferWidth()/2,RenderSystem.getFramebufferHeight()/2,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
                    assertEquals(0x20,pixel.get(0)&255,style+" fully faded background red");
                    assertEquals(0x30,pixel.get(1)&255,style+" fully faded background green");
                    assertEquals(0x40,pixel.get(2)&255,style+" fully faded background blue");
                    assertSame(target,Framebuffer.currentlyBinding);
                }
            }
            // Exercise reflow, seek, and empty/malformed lyric fallbacks at real window sizes.
            var fixture=List.copyOf(CloudMusic.lyrics);
            for(int[] dimensions:new int[][]{{980,720},{720,1000}}){
                Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(dimensions[0],dimensions[1]));Display.update();
                invoke("initializeOpenGL");Framebuffer.updateMcFramebuffer();target.deleteFramebuffer();
                target=new Framebuffer(RenderSystem.getFramebufferWidth(),RenderSystem.getFramebufferHeight(),true);
                for(var style:LyricStyle.values())if(style!=LyricStyle.Normal){
                    prefs.lyricStyle().setValue(style.name());deck.position=18400;settle(4);
                    save(style.name()+"-"+dimensions[0]+"x"+dimensions[1]);
                    assertEquals(0,GL11.glGetError(),style+" resized");
                }
            }
            CloudMusic.lyrics.clear();
            var longLine=new LyricLine(1000,"这是一句用于检验自动换行和超长歌词适配的测试歌词 Long lines must stay readable across every native layout");
            longLine.duration=15000;longLine.translationText="A long translated line that must wrap without covering the player controls at the bottom of the window.";
            CloudMusic.lyrics.add(longLine);deck.position=5000;
            for(var style:LyricStyle.values())if(style!=LyricStyle.Normal){prefs.lyricStyle().setValue(style.name());settle(3);assertEquals(0,GL11.glGetError(),style+" untimed long lyrics");}
            CloudMusic.lyrics.clear();
            for(var style:LyricStyle.values())if(style!=LyricStyle.Normal){prefs.lyricStyle().setValue(style.name());settle(2);assertEquals(0,GL11.glGetError(),style+" empty lyrics");}
            CloudMusic.lyrics.addAll(fixture);
            // Normal's existing cover bloom composites to the display framebuffer.
            prefs.lyricStyle().setValue("Normal");
            for(int i=0;i<4;i++){prepare(Framebuffer.getMcFramebuffer());screen.drawScreen(-1,-1);}
            assertFalse(screen.lyricsHaveControls());
            prefs.lyricStyle().setValue("Monet");settle(3);screen.keyTyped('\0',Keyboard.KEY_ESCAPE);settle(50);assertNull(screen.musicLyricsPanel);
        }finally{
            if(screen!=null&&screen.musicLyricsPanel!=null)screen.musicLyricsPanel.dispose();
            prefs.lyricStyle().setValue(oldStyle);prefs.playerControls().setValue(oldControls);prefs.miniAutoCollapse().setValue(oldCollapse);
            CloudMusic.currentLyric=oldCurrentLyric;CloudMusic.currentlyPlaying=oldSong;CloudMusic.player=oldPlayer;CloudMusic.profile=oldProfile;CloudMusic.likeList=oldLikes;CloudMusic.lyrics.clear();CloudMusic.lyrics.addAll(oldLyrics);
            if(Display.isCreated()){UiMaterials.release();if(target!=null)target.deleteFramebuffer();FontManager.deleteLoadedTextures();Display.destroy();}
        }
    }
    private void verifyEnglish(FakeDeck deck) throws Exception {
        var saved=List.copyOf(CloudMusic.lyrics);var english=new java.util.ArrayList<LyricLine>();
        String[] texts={"All I know", "Are love songs", "I'll stay, and we'll find our way.",
                "When the city lights are fading, we will follow every echo through the rain",
                "Don't let go — we're almost home!", "A little light is enough", "Carry every memory into tomorrow"};
        for(int i=0;i<texts.length;i++){
            var line=new LyricLine(1000+i*7000,texts[i]);line.duration=6200;
            line.translationText="English lyric layout and word timing regression";
            var matcher=java.util.regex.Pattern.compile("\\S+\\s*").matcher(texts[i]);
            var words=new java.util.ArrayList<String>();while(matcher.find())words.add(matcher.group());
            for(int j=0;j<words.size();j++)line.words.add(new LyricLine.Word(words.get(j),line.timestamp+j*6200/words.size(),6200/words.size()));
            english.add(line);
        }
        CloudMusic.lyrics.clear();CloudMusic.lyrics.addAll(english);
        var prefs=DesktopAppState.preferences();
        for(int[] dimensions:new int[][]{{1650,972},{720,1000}}){
            resize(dimensions[0],dimensions[1]);
            for(var style:LyricStyle.values()){
                prefs.lyricStyle().setValue(style.name());
                for(int sample:new int[]{0,2,3}){
                    deck.position=english.get(sample).timestamp+4200;CloudMusic.currentLyric=english.get(sample);
                    if(style==LyricStyle.Normal){
                        for(int j=0;j<8;j++){prepare(Framebuffer.getMcFramebuffer());screen.drawScreen(-1,-1);Thread.sleep(10);}
                    }else settle(8);
                    if(style==LyricStyle.Normal)Framebuffer.getMcFramebuffer().bindFramebuffer(true);
                    save(style.name()+"-English-"+sample+"-"+dimensions[0]+"x"+dimensions[1]);
                    assertEquals(0,GL11.glGetError(),style+" English layout");
                }
            }
        }
        resize(1280,720);
        var timeline=new LyricTimeline(english,60);var canvas=new LyricCanvas();
        try(var scene=new HanabiScene()){
            for(int variant=0;variant<50;variant++){
                for(int phase=0;phase<3;phase++){
                    double time=new double[]{.55,4.8,7.4}[phase];
                    prepare();GL11.glPushMatrix();GL11.glScaled(RenderSystem.getWidth()/1280,RenderSystem.getHeight()/720,1);canvas.begin(1);
                    scene.render(canvas,new LyricFrame(timeline,time,1.0/60,true,false,deck.getEnergy(),CloudMusic.currentlyPlaying,variant,1280,720,64,0xffce8bdb,true,false));
                    GL11.glPopMatrix();
                    if(phase==1)save("Hanabi-English-profile-"+variant);
                    assertEquals(0,GL11.glGetError(),"Hanabi English profile "+variant+" phase "+phase);
                }
            }
        }
        verifyClassic(deck,english);
        CloudMusic.lyrics.clear();CloudMusic.lyrics.addAll(saved);deck.position=17500;
        resize(1650,972);
    }
    private void verifyClassic(FakeDeck deck,List<LyricLine> english)throws Exception {
        var prefs=DesktopAppState.preferences();boolean captions=prefs.showBottomLyrics().getValue();
        prefs.lyricStyle().setValue("Classic");prefs.playerControls().setValue("FoliaMini");
        try {
            for(int time:new int[]{1050,1300,3000,7000,7650,8100}) {
                deck.position=time;settle(4);save("Classic-phase-"+time);
            }
            // A repeated line exercises Folia's chorus ripple, without changing the lyric source model.
            var chorus=new LyricLine(51000,english.getFirst().lyric);chorus.duration=6200;
            CloudMusic.lyrics.add(chorus);deck.position=english.getFirst().timestamp+50;settle(4);save("Classic-chorus");
            CloudMusic.lyrics.remove(chorus);
            deck.position=english.get(2).timestamp+4200;deck.paused=true;
            prefs.showBottomLyrics().setValue(false);settle(4);save("Classic-captions-off-paused");
            assertTrue(screen.lyricsHaveControls());
            prefs.showBottomLyrics().setValue(true);settle(4);save("Classic-captions-on-paused");
            assertEquals(0,GL11.glGetError(),"Classic transitions, chorus and captions");
            verifyClassicInk();
        } finally {prefs.showBottomLyrics().setValue(captions);deck.paused=false;}
    }
    private void verifyClassicInk()throws Exception {
        var c=new LyricCanvas();c.begin(1);
        try(var ink=new ClassicInk()) {
            java.nio.ByteBuffer crisp=null,glowing=null;
            for(double glow:new double[]{0,1}) {
                for(int i=0;i<4;i++) {
                    prepare();Rect.draw(0,0,RenderSystem.getWidth(),RenderSystem.getHeight(),0xff101018);
                    ink.draw(c,List.of(new ClassicInk.Glyph("Glow",64,RenderSystem.getWidth()/2,
                            RenderSystem.getHeight()/2,1,0,0xfff4f4f5,0xfff4f4f5,glow,0,20,40)));
                    assertSame(target,Framebuffer.currentlyBinding,"Glow atlas must restore the caller framebuffer");
                    Thread.sleep(10);
                }
                var pixels=BufferUtils.createByteBuffer(RenderSystem.getFramebufferWidth()*RenderSystem.getFramebufferHeight()*4);
                GL11.glReadPixels(0,0,RenderSystem.getFramebufferWidth(),RenderSystem.getFramebufferHeight(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                if(glow==0)crisp=pixels;else glowing=pixels;
            }
            int haloPixels=0;
            for(int i=0;i<crisp.capacity();i+=4)if((crisp.get(i)&255)<40&&(glowing.get(i)&255)>(crisp.get(i)&255)+8)haloPixels++;
            assertTrue(haloPixels>100,"The native double halo must add light beyond the readable glyph body");
            assertEquals(0,GL11.glGetError(),"Classic blur shader");
        }
        assertSame(target,Framebuffer.currentlyBinding,"Closing classic must restore the caller framebuffer");
    }
    private void resize(int width,int height)throws Exception{
        Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(width,height));Display.update();
        invoke("initializeOpenGL");Framebuffer.updateMcFramebuffer();target.deleteFramebuffer();
        target=new Framebuffer(RenderSystem.getFramebufferWidth(),RenderSystem.getFramebufferHeight(),true);
    }
    private void click(double x,double y){screen.mouseClicked((int)x,(int)y,0);screen.mouseReleased((int)x,(int)y,0);}
    private void prepare()throws Exception{prepare(target);}
    private void prepare(Framebuffer frame)throws Exception{
        DesktopAppState.pumpMainThreadTasks();Interpolations.calcFrameDelta();AppTheme.beginFrame();
        frame.bindFramebuffer(true);invoke("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
    }
    private void settle(int count)throws Exception{for(int i=0;i<count;i++){prepare();screen.drawScreen(-1,-1);Display.processMessages();Thread.sleep(10);}}
    private void save(String name)throws Exception{
        int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight();var bytes=BufferUtils.createByteBuffer(w*h*4);GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes);
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);long brightness=0;int darkest=765,brightest=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=((h-y-1)*w+x)*4,r=bytes.get(i)&255,g=bytes.get(i+1)&255,b=bytes.get(i+2)&255;image.setRGB(x,y,(r<<16)|(g<<8)|b);brightness+=r+g+b;darkest=Math.min(darkest,r+g+b);brightest=Math.max(brightest,r+g+b);}
        assertTrue(brightness>(long)w*h*8&&brightest-darkest>60,name+" must contain visible content");Path path=Path.of("target/ui-preview/lyrics-"+name+".png");Files.createDirectories(path.getParent());ImageIO.write(image,"png",path.toFile());
    }
    private static BufferedImage artwork(){var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setPaint(new GradientPaint(0,0,new Color(30,31,75),512,512,new Color(224,126,149)));g.fillRect(0,0,512,512);
        g.setColor(new Color(248,199,161));g.fillOval(235,70,180,180);g.setPaint(new GradientPaint(0,250,new Color(90,105,139),0,512,new Color(19,46,72)));g.fillPolygon(new int[]{0,160,300,420,512,512,0},new int[]{350,230,380,290,390,512,512},7);g.dispose();return image;}
    private static void invoke(String name)throws Exception{var method=TritiumMusicDesktopApp.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(null);}
    static final class FakeDeck extends AudioPlayer{
        float position;boolean paused;FakeDeck(File file){super(file);}
        @Override public float getTotalTimeMillis(){return 60000;}@Override public float getCurrentTimeMillis(){return position;}
        @Override public boolean isPausing(){return paused;}@Override public void pause(){paused=true;}@Override public void unpause(){paused=false;}
        @Override public void setPlaybackTime(float time){position=time;}
        @Override public AudioEnergy getEnergy(){return paused?AudioEnergy.SILENT:new AudioEnergy(.35,.55,.3,.35,.5,.25,0);}
    }
}
