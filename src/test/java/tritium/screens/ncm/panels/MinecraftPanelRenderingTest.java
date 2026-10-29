package tritium.screens.ncm.panels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.*;
import tritium.desktop.*;
import tritium.desktop.minecraft.MinecraftConnectionManager;
import tritium.management.FontManager;
import tritium.minecraft.protocol.HudConfig;
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
    @Test void globalMaterialsResponsiveColumnsAndHudEditor() throws Exception {
        var connection = DesktopAppState.minecraft(); var field = MinecraftConnectionManager.class.getDeclaredField("status"); field.setAccessible(true);
        var connected = MinecraftConnectionManager.class.getDeclaredField("connected"); connected.setAccessible(true);
        var editing = MinecraftConnectionManager.class.getDeclaredField("editing"); editing.setAccessible(true);
        boolean wasConnected = connection.connected(), wasEditing = connection.editing();
        boolean wasHidden = DesktopAppState.preferences().minecrafthidden().getValue();
        Object previous = field.get(connection); var preferences = DesktopAppState.preferences(); String material = preferences.interfaceStyle().getValue();
        try {
            DesktopNativeLoader.configureLibraryPath(); app("createDisplay"); Display.update(); app("initializeOpenGL");
            Framebuffer.updateMcFramebuffer(); DesktopAppState.markMainThread(); new FontManager().init();
            renderTarget = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            preferences.minecrafthidden().setValue(false);
            preferences.language().setValue("zh-CN"); preferences.accentColor().setValue(0xffc026d3);
            MinecraftPanel panel = new MinecraftPanel(); panel.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight()); panel.onInit();
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.INSTALLING, .52, "UI fixture", List.of()));
            settle(panel, 500); save("minecraft-inject-progress");
            panel.setWidth(620); settle(panel, 120); save("minecraft-inject-narrow");
            panel.setWidth(RenderSystem.getWidth());
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.FAILED, 0, "UI fixture: Agent 初始化失败。请重试。", List.of()));
            settle(panel, 120); save("minecraft-inject-failed");
            field.set(connection, new MinecraftConnectionManager.Status(MinecraftConnectionManager.Stage.READY, 1, "UI fixture", List.of()));
            connected.setBoolean(connection, true); editing.setBoolean(connection, false);
            settle(panel, 500);
            ScrollPanel grid = (ScrollPanel)panel.getChildren().get(2); var cards = grid.getChildren();
            assertEquals(12, cards.size());
            assertEquals(7, cards.stream().filter(c -> !c.isHidden()).count()); assertTrue(cards.get(1).getRelativeX() > 0);
            assertEquals(cards.get(0).getRelativeX(),cards.get(2).getRelativeX());
            assertEquals(cards.get(1).getRelativeX(),cards.get(3).getRelativeX());
            assertEquals(cards.get(0).getRelativeX(),cards.get(4).getRelativeX());
            assertEquals(cards.get(1).getRelativeX(),cards.get(5).getRelativeX());
            assertEquals(cards.get(1).getRelativeX(),cards.get(6).getRelativeX());
            AbstractWidget<?> card = cards.get(0);
            panel.onMouseClickReceived(card.getX()+card.getWidth()/2,card.getY()+52,0); settle(panel,120);
            var sliderRow = card.getChildren().getFirst();
            int cardPixel = 0, rowPixel = 0;
            for (String style : List.of("Static","Blur","LiquidGlass")) {
                preferences.interfaceStyle().setValue(style); settle(panel,200,true);
                int currentCard = pixel(card.getX()+8,card.getY()+45);
                int currentRow = pixel(sliderRow.getX()+8,sliderRow.getY()+sliderRow.getHeight()/2);
                if (style.equals("Static")) {
                    assertNotEquals(0, currentCard, "Preview must contain rendered card pixels");
                    cardPixel = currentCard; rowPixel = currentRow;
                } else {
                    assertTrue(UiMaterials.enabled(), UiMaterials.status());
                    assertNotEquals(cardPixel,currentCard,"Card background must follow " + style);
                    assertNotEquals(rowPixel,currentRow,"Expanded controls must follow " + style);
                }
                save("minecraft-cards-"+style);
            }
            panel.onMouseClickReceived(card.getX()+card.getWidth()/2,card.getY()+52,0); settle(panel,120);
            var keyX = card.getClass().getDeclaredField("keyX"); keyX.setAccessible(true);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_K));
            assertEquals(Keyboard.KEY_K,preferences.minecraft().snapshot().find("musicInfo").key);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_ESCAPE));
            assertEquals(Keyboard.KEY_K,preferences.minecraft().snapshot().find("musicInfo").key);
            panel.onMouseClickReceived(card.getX()+keyX.getDouble(card)+6,card.getY()+30,0);
            panel.captureKey(Keyboard.KEY_DELETE); assertEquals(0,preferences.minecraft().snapshot().find("musicInfo").key);
            AbstractWidget<?> editor = cards.get(6);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_J));
            assertEquals(Keyboard.KEY_J,preferences.minecraft().snapshot().editKey);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            panel.captureKey(Keyboard.KEY_ESCAPE);
            assertEquals(Keyboard.KEY_J,preferences.minecraft().snapshot().editKey);
            panel.onMouseClickReceived(editor.getX()+keyX.getDouble(editor)+6,editor.getY()+30,0);
            panel.captureKey(Keyboard.KEY_BACK); assertEquals(0,preferences.minecraft().snapshot().editKey);
            preferences.minecraft().update(c -> {
                var info = c.find("musicInfo"); info.x = .6; info.y = .4; info.scale = 1.5;
                info.enabled = true; info.opacity = .65;
            });
            var header = panel.getChildren().getFirst();
            panel.onMouseClickReceived(header.getX()+20,header.getY()+40,0);
            assertEquals(.6,preferences.minecraft().snapshot().find("musicInfo").x,"The old header reset target must be inactive");
            panel.onMouseClickReceived(editor.getX()+editor.getWidth()/2,editor.getY()+52,0); settle(panel,120);
            assertFalse(connection.editing(),"Expanding the editor must not toggle editing");
            assertTrue(editor.getHeight()>64);
            var reset = editor.getChildren().getFirst(); assertFalse(reset.isHidden());
            panel.onMouseClickReceived(editor.getX()+editor.getWidth()-36,editor.getY()+27,0);
            assertTrue(connection.editing(),"The editor switch must enable editing");
            panel.onMouseClickReceived(reset.getX()+20,reset.getY()+reset.getHeight()/2,0);
            var info = preferences.minecraft().snapshot().find("musicInfo");
            var defaults = new HudConfig().find("musicInfo");
            assertEquals(defaults.x,info.x); assertEquals(defaults.y,info.y); assertEquals(defaults.scale,info.scale);
            assertTrue(info.enabled); assertEquals(.65,info.opacity);
            assertTrue(connection.editing(),"Resetting the layout must preserve editing mode");
            settle(panel,120); save("minecraft-edit-hud-expanded");
            panel.onMouseClickReceived(editor.getX()+editor.getWidth()-36,editor.getY()+27,0);
            assertFalse(connection.editing());
            panel.onMouseClickReceived(editor.getX()+editor.getWidth()/2,editor.getY()+52,0); settle(panel,120);
            assertTrue(reset.isHidden()); assertEquals(64,editor.getHeight());
            AbstractWidget<?> world = cards.get(4);
            assertEquals(18, world.getChildren().size());
            panel.onMouseClickReceived(world.getX()+keyX.getDouble(world)+6,world.getY()+30,0);
            assertTrue(panel.captureKey(Keyboard.KEY_L));
            assertEquals(Keyboard.KEY_L,preferences.minecraft().snapshot().find("worldLyrics").key);
            panel.onMouseClickReceived(world.getX()+world.getWidth()/2,world.getY()+52,0); settle(panel,120);
            double allEffectsHeight = world.getHeight();
            assertTrue(allEffectsHeight > 1000); save("minecraft-world-lyrics-expanded");
            grid.actualScrollOffset = grid.targetScrollOffset = world.getRelativeY() + 64;
            settle(panel,120); save("minecraft-world-lyrics-effects");
            grid.scrollToEndImmediately(); settle(panel,120); save("minecraft-world-lyrics-position");
            var lastWorldRow = world.getChildren().get(world.getChildren().size() - 1);
            assertTrue(lastWorldRow.getY() >= grid.getY());
            assertTrue(lastWorldRow.getY() + lastWorldRow.getHeight() <= grid.getY() + grid.getHeight());
            grid.actualScrollOffset = grid.targetScrollOffset = 0; settle(panel,120);
            preferences.minecraft().update(c -> { c.find("worldLyrics").world.glow = false; c.find("worldLyrics").world.wiggle = false; });
            settle(panel,120);
            assertTrue(world.getHeight() < allEffectsHeight);
            assertEquals(13,world.getChildren().stream().filter(row -> !row.isHidden()).count());
            preferences.minecraft().update(c -> { c.find("worldLyrics").world.glow = true; c.find("worldLyrics").world.wiggle = true; });
            panel.onMouseClickReceived(world.getX()+world.getWidth()/2,world.getY()+52,0); settle(panel,120);
            panel.onMouseClickReceived(card.getX()+card.getWidth()/2,card.getY()+52,0); settle(panel,120);
            assertTrue(card.getHeight()>64); save("minecraft-expanded");
            panel.setWidth(620); settle(panel,120);
            for (var item : cards) if (!item.isHidden()) assertEquals(0,item.getRelativeX()); save("minecraft-narrow");
            preferences.minecrafthidden().setValue(true); panel.setWidth(RenderSystem.getWidth()); settle(panel,120);
            assertEquals(12,cards.stream().filter(c -> !c.isHidden()).count());
            assertEquals(14,cards.get(7).getChildren().size()); assertEquals(17,cards.get(8).getChildren().size()); assertEquals(11,cards.get(9).getChildren().size());
            assertEquals(6,cards.get(10).getChildren().size()); assertEquals(1,cards.get(11).getChildren().size());
            // Use local widget coordinates after scrolling to each newly visible module.
            for (int i=7;i<12;i++) {
                var combat = cards.get(i);
                grid.actualScrollOffset = grid.targetScrollOffset = combat.getRelativeY() + grid.actualScrollOffset;
                settle(panel,120);
                panel.onMouseClickReceived(combat.getX()+combat.getWidth()/2,combat.getY()+52,0); settle(panel,120);
                assertEquals(combat.getChildren().size(),combat.getChildren().stream().filter(row -> !row.isHidden()).count());
                save("minecraft-module-"+i);
                grid.scrollToEndImmediately(); settle(panel,120);
                var last = combat.getChildren().getLast();
                assertTrue(last.getY()+last.getHeight() <= grid.getY()+grid.getHeight()+1);
                if (i >= 10) save("minecraft-module-"+i+"-options");
                if (i == 10) {
                    var textField = (tritium.rendering.ui.widgets.TextFieldWidget) combat.getChildren().get(4).getChildren().getFirst();
                    String originalText = textField.getText();
                    assertFalse(originalText.isEmpty());
                    panel.onMouseClickReceived(textField.getX()+12, textField.getY()+12, 0);
                    assertTrue(textField.isFocused()); textField.getTextField().selectAll();
                    assertTrue(panel.onKeyTypedReceived('X', Keyboard.KEY_X));
                    assertEquals("X", preferences.minecraft().snapshot().find("sprint").movement.text);
                    panel.onKeyTypedReceived('\n', Keyboard.KEY_RETURN); assertFalse(textField.isFocused());
                    preferences.minecraft().update(c -> c.find("sprint").movement.text=originalText);
                    var editPosition = combat.getChildren().getFirst();
                    // The same action opens the existing editor; text dragging is covered by HudInputTest.
                    editPosition.onMouseClicked(editPosition.getWidth()-40, 26, 0);
                    assertTrue(connection.editing()); connection.setEditing(false);
                }
                var expanded = combat.getClass().getDeclaredField("expanded"); expanded.setAccessible(true); expanded.setBoolean(combat,false);
                settle(panel,120);
            }
            preferences.minecrafthidden().setValue(false); settle(panel,120);
            assertEquals(7,cards.stream().filter(c -> !c.isHidden()).count());
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        } finally {
            field.set(connection,previous); preferences.interfaceStyle().setValue(material);
            preferences.minecrafthidden().setValue(wasHidden);
            connected.setBoolean(connection,wasConnected); editing.setBoolean(connection,wasEditing);
            if (Display.isCreated()) {
                if (renderTarget != null) { renderTarget.deleteFramebuffer(); renderTarget = null; }
                UiMaterials.release(); FontManager.deleteLoadedTextures(); Display.destroy();
            }
        }
    }
    private static void settle(AbstractWidget<?> page, int millis) throws Exception {
        settle(page, millis, false);
    }
    private static void settle(AbstractWidget<?> page, int millis, boolean patternedBackdrop) throws Exception {
        long end=System.nanoTime()+millis*1_000_000L;
        do {
            DesktopAppState.pumpMainThreadTasks();
            // The visible game may minimize/occlude this test window; use a stable offscreen target.
            renderTarget.forceBind(false);
            Interpolations.calcFrameDelta(); AppTheme.beginFrame(); app("prepareFrameState");
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT);
            Rect.draw(0,0,RenderSystem.getWidth(),RenderSystem.getHeight(),AppTheme.background());
            // Colored bands distinguish backdrop sampling from coincidentally equal gray pixels.
            if (patternedBackdrop) for (int x=0; x<RenderSystem.getWidth(); x+=96)
                Rect.draw(x,0,96,RenderSystem.getHeight(),(x/96)%2==0 ? 0xff283c58 : 0xff493450);
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
