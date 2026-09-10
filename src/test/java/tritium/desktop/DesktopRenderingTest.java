package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.rendersystem.RenderSystem;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real drawable readback: viewport numbers alone cannot prove HiDPI works. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class DesktopRenderingTest {
    @Test
    void rendersAcrossTheActualDrawableAndClipsAfterResize() throws Exception {
        DesktopNativeLoader.configureLibraryPath();
        try {
            invokeApp("createDisplay");
            verifyPixels();
            Display.setDisplayMode(new DisplayMode(1000, 700));
            MacOpenGLSurface.enableHighResolution();
            verifyPixels();
        } finally {
            if (Display.isCreated()) {
                Display.destroy();
            }
        }
    }

    private static void verifyPixels() throws Exception {
        Display.update();
        invokeApp("initializeOpenGL");
        Framebuffer.updateMcFramebuffer();
        invokeApp("prepareFrameState");
        assertTrue(GL11.glGetInteger(GL11.GL_STENCIL_BITS) >= 8);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        double width = RenderSystem.getWidth(), height = RenderSystem.getHeight();
        Rect.draw(0, 0, width, height, 0xFF0000FF);
        StencilClipManager.beginClip(() -> Rect.draw(0, 0, width / 2, height / 2, -1));
        Rect.draw(0, 0, width, height, 0xFFFF0000);
        StencilClipManager.endClip();
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        // Top-left red is inside the clip. The three other quadrants stay blue.
        // Sampling upper/right pixels catches a 1x drawable paired with a 2x viewport.
        assertPixel(w / 4, h * 3 / 4, 255, 0, 0);
        assertPixel(w * 3 / 4, h * 3 / 4, 0, 0, 255);
        assertPixel(w / 4, h / 4, 0, 0, 255);
        assertPixel(w * 3 / 4, h / 4, 0, 0, 255);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private static void assertPixel(int x, int y, int r, int g, int b) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(r, pixel.get(0) & 255, "red at " + x + "," + y);
        assertEquals(g, pixel.get(1) & 255, "green at " + x + "," + y);
        assertEquals(b, pixel.get(2) & 255, "blue at " + x + "," + y);
    }

    private static void invokeApp(String method) throws Exception {
        var target = TritiumMusicDesktopApp.class.getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(null);
    }
}
