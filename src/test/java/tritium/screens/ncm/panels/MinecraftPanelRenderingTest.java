package tritium.screens.ncm.panels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.*;
import tritium.desktop.*;
import tritium.desktop.minecraft.MinecraftConnectionManager;
import tritium.management.FontManager;
import tritium.rendering.*;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** UI fixture only. READY is supplied to the view; this test makes no claim about a Minecraft connection. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class MinecraftPanelRenderingTest {
    private static Framebuffer renderTarget;
    @Test void staticCardsResponsiveColumnsAndKeyCapture() throws Exception {
        var connection = DesktopAppState.minecraft(); var field = MinecraftConnectionManager.class.getDeclaredField("status"); field.setAccessible(true);
        Object previous = field.get(connection); var preferences = DesktopAppState.preferences(); String material = preferences.interfaceStyle().getValue();
        try {
            DesktopNativeLoader.configureLibraryPath(); app("createDisplay"); Display.update(); app("initializeOpenGL");
            Framebuffer.updateMcFramebuffer(); DesktopAppState.markMainThread(); new FontManager().init();
            renderTarget = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            preferences.language().setValue("zh-CN"); preferences.accentColor().setValue(0xffc026d3);
            MinecraftPanel panel = new MinecraftPanel(); panel.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight()); panel.onInit();
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.INSTALLING, .52, "UI fixture", List.of()));
            settle(panel, 500); save("minecraft-inject-progress");
            panel.setWidth(620); settle(panel, 120); save("minecraft-inject-narrow");
            panel.setWidth(RenderSystem.getWidth());
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.FAILED, 0, "UI fixture: Agent 初始化失败。请重试。", List.of()));
            settle(panel, 120); save("minecraft-inject-failed");
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.READY, 1, "UI fixture", List.of()));
            settle(panel, 500);
            ScrollPanel grid = (ScrollPanel)panel.getChildren().get(2); var cards = grid.getChildren();
            assertEquals(6, cards.size()); assertTrue(cards.get(1).getRelativeX() > 0);
            assertEquals(cards.get(0).getRelativeX(),cards.get(2).getRelativeX());
            assertEquals(cards.get(1).getRelativeX(),cards.get(3).getRelativeX());
            assertEquals(cards.get(0).getRelativeX(),cards.get(4).getRelativeX());
            assertEquals(cards.get(1).getRelativeX(),cards.get(5).getRelativeX());
            int pixel = 0;
            for (String style : List.of("Static","Blur","LiquidGlass")) {
                preferences.interfaceStyle().setValue(style); settle(panel,200);
                int current = pixel(cards.get(0).getX()+8,cards.get(0).getY()+45);
                if (style.equals("Static")) { assertNotEquals(0, current, "Preview must contain rendered card pixels"); pixel = current; }
                else assertEquals(pixel,current,"Card background must remain Static");
                save("minecraft-cards-"+style);
            }
            AbstractWidget<?> card = cards.get(0);
            var keyX = card.getClass().getDeclaredField("keyX"); keyX.setAccessible(true);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_K));
            assertEquals(Keyboard.KEY_K,preferences.minecraft().snapshot().find("musicInfo").key);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_ESCAPE));
            assertEquals(Keyboard.KEY_K,preferences.minecraft().snapshot().find("musicInfo").key);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            panel.captureKey(Keyboard.KEY_DELETE); assertEquals(0,preferences.minecraft().snapshot().find("musicInfo").key);
            AbstractWidget<?> editor = cards.get(5);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_J));
            assertEquals(Keyboard.KEY_J,preferences.minecraft().snapshot().editKey);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            panel.captureKey(Keyboard.KEY_ESCAPE);
            assertEquals(Keyboard.KEY_J,preferences.minecraft().snapshot().editKey);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            panel.captureKey(Keyboard.KEY_BACK); assertEquals(0,preferences.minecraft().snapshot().editKey);
            panel.onMouseClickReceived(card.getX()+card.getWidth()/2,card.getY()+52,0); settle(panel,120);
            assertTrue(card.getHeight()>64); save("minecraft-expanded");
            panel.setWidth(620); settle(panel,120);
            for (var item : cards) assertEquals(0,item.getRelativeX()); save("minecraft-narrow");
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        } finally {
            field.set(connection,previous); preferences.interfaceStyle().setValue(material);
            if (Display.isCreated()) {
                if (renderTarget != null) { renderTarget.deleteFramebuffer(); renderTarget = null; }
                UiMaterials.release(); FontManager.deleteLoadedTextures(); Display.destroy();
            }
        }
    }
    private static void settle(AbstractWidget<?> page, int millis) throws Exception {
        long end=System.nanoTime()+millis*1_000_000L;
        do {
            DesktopAppState.pumpMainThreadTasks();
            // The visible game may minimize/occlude this test window; use a stable offscreen target.
            renderTarget.forceBind(false);
            Interpolations.calcFrameDelta(); AppTheme.beginFrame(); app("prepareFrameState");
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
            Rect.draw(0,0,RenderSystem.getWidth(),RenderSystem.getHeight(),AppTheme.background());
            UiMaterials.beginFrame(-1,-1); UiMaterials.captureBackdrop(false); page.renderWidget(-1,-1,0);
            Display.processMessages(); Thread.sleep(12);
        } while(System.nanoTime()<end);
    }
    private static int pixel(double x,double y) {
        ByteBuffer b=BufferUtils.createByteBuffer(4); double scale=RenderSystem.getUiScaleFactor();
        GL11.glReadPixels((int)(x*scale),RenderSystem.getFramebufferHeight()-(int)(y*scale)-1,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);
        return (b.get(0)&255)<<16|(b.get(1)&255)<<8|(b.get(2)&255);
    }
    private static void save(String name) throws Exception {
        int w=RenderSystem.getFramebufferWidth(),h=RenderSystem.getFramebufferHeight(); ByteBuffer b=BufferUtils.createByteBuffer(w*h*4);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b); BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=((h-y-1)*w+x)*4;image.setRGB(x,y,(b.get(p)&255)<<16|(b.get(p+1)&255)<<8|(b.get(p+2)&255));}
        Path output=Path.of("target/ui-preview/"+name+".png");Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
    }
    private static void app(String name)throws Exception{var m=TritiumMusicDesktopApp.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(null);}
}
