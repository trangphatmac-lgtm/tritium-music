package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.impl.GaussianBlurShader;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class LyricsBlurRenderingTest {
    @Test
    void foregroundBlurPreservesBackgroundAndKeepsUnmaskedLyricsSharp() throws Exception {
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            GaussianBlurShader blur = new GaussianBlurShader();
            Framebuffer.getMcFramebuffer().forceBind(true);
            invokeApp("prepareFrameState");
            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), 0xFF204080);
            // Fine stripes make any accidental second background blur measurable.
            for (int x = 0; x < 300; x += 4) Rect.draw(x, 0, 2, 300, 0xFF80C040);
            int sharpEdge = red(101, 40);
            blur.run(List.of(() -> Rect.draw(0, 0, 300, 300, -1)));
            assertNotEquals(sharpEdge, red(101, 40), "Background must actually be blurred");
            byte[] background = capture(20, 20, 240, 50);
            blur.runLayer(() -> {
                Rect.draw(100, 100, 50, 20, -1);
                Rect.draw(100, 150, 50, 20, -1);
            }, List.of(() -> Rect.draw(0, 0, 300, 140, -1)));
            assertArrayEquals(background, capture(20, 20, 240, 50),
                    "A lyric mask must not blur the background again");
            assertTrue(red(100, 110) < 250, "Non-current lyric edges should soften");
            assertEquals(255, red(100, 160), "Current lyrics outside the mask remain sharp");
            assertEquals(255, red(125, 110), "Opaque lyric interiors must stay opaque");

            // A transparent layer with no blur must composite once, without dark fringes.
            Rect.draw(350, 100, 100, 100, 0xFF000000);
            blur.runLayer(() -> Rect.draw(360, 110, 40, 40, 0x80FFFFFF), List.of());
            assertEquals(128, red(380, 130), 1);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            if (Display.isCreated()) Display.destroy();
        }
    }

    private static int red(int x, int y) {
        return capture(x, y, 1, 1)[0] & 255;
    }

    private static byte[] capture(int x, int y, int width, int height) {
        float scale = RenderSystem.getUiScaleFactor();
        int w = (int) (width * scale), h = (int) (height * scale);
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - h,
                w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] result = new byte[pixels.remaining()];
        pixels.get(result);
        return result;
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
}
