package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.desktop.DesktopNativeLoader;
import tritium.desktop.TritiumMusicDesktopApp;
import tritium.minecraft.protocol.RadarFrame;
import tritium.rendering.Framebuffer;
import tritium.rendering.rendersystem.RenderSystem;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class MinecraftRadarRenderingTest {
    @Test void desktopOverlayPreservesStyleClippingPositionAndGlStateAcrossFrames() throws Exception {
        Framebuffer target = null;
        try {
            DesktopNativeLoader.configureLibraryPath(); app("createDisplay"); Display.update(); app("initializeOpenGL");
            target = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
            target.bindFramebuffer(true); app("prepareFrameState");
            RadarFrame frame = new RadarFrame(); frame.visible = true;
            frame.viewportWidth = RenderSystem.getWidth(); frame.viewportHeight = RenderSystem.getHeight();
            frame.points = new double[]{35, 100, 120, 110};
            clear();
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(1, 2, 3, 4); GL11.glLineWidth(2);
            GL11.glColor4f(.2f, .3f, .4f, .5f);
            MinecraftRadarOverlay.render(frame);
            assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
            IntBuffer scissor = BufferUtils.createIntBuffer(16); GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
            assertEquals(1, scissor.get(0)); assertEquals(2, scissor.get(1)); assertEquals(3, scissor.get(2)); assertEquals(4, scissor.get(3));
            assertEquals(2, GL11.glGetFloat(GL11.GL_LINE_WIDTH));
            assertRgb(4.5, 80, 255, 255, 255);
            assertRgb(35, 100, 255, 0, 0);
            assertRgb(120, 110, 30, 30, 30); // Remote target outside the Radar is clipped.
            assertRgb(20, 90, 15, 15, 15); // Original translucent black panel.
            save("minecraft-radar-offscreen");

            clear(); frame.tracers = true; frame.x += 100; frame.centerX += 100;
            frame.points = new double[]{135, 100};
            MinecraftRadarOverlay.render(frame);
            assertRgb(35, 100, 30, 30, 30); assertRgb(135, 100, 255, 0, 0);
            assertRgb(104.5, 80, 255, 255, 255);
            save("minecraft-radar-offscreen-moved");

            clear(); frame.viewportWidth /= 2; frame.viewportHeight /= 2;
            MinecraftRadarOverlay.render(frame);
            assertRgb(270, 200, 255, 0, 0);
            assertRgb(135, 100, 30, 30, 30);
            save("minecraft-radar-offscreen-scaled");
            clear(); frame.visible = false; MinecraftRadarOverlay.render(frame);
            assertRgb(270, 200, 30, 30, 30);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            if (target != null) target.deleteFramebuffer();
            if (Display.isCreated()) Display.destroy();
        }
    }
    private static void clear() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClearColor(30 / 255f, 30 / 255f, 30 / 255f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
    }
    private static void assertRgb(double x, double y, int red, int green, int blue) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        int px = (int)(x * RenderSystem.getFramebufferWidth() / RenderSystem.getWidth());
        int py = RenderSystem.getFramebufferHeight() - 1 - (int)(y * RenderSystem.getFramebufferHeight() / RenderSystem.getHeight());
        GL11.glReadPixels(px, py, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(red, pixel.get(0) & 255, 2, "red at " + x + "," + y);
        assertEquals(green, pixel.get(1) & 255, 2, "green at " + x + "," + y);
        assertEquals(blue, pixel.get(2) & 255, 2, "blue at " + x + "," + y);
    }
    private static void save(String name) throws Exception {
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 4;
            image.setRGB(x, y, (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255);
        }
        Path output = Path.of("target/ui-preview/" + name + ".png"); Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
    }
    private static void app(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(null);
    }
}
