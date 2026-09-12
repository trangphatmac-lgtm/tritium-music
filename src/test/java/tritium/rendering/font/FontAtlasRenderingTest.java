package tritium.rendering.font;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.desktop.DesktopNativeLoader;
import tritium.desktop.TritiumMusicDesktopApp;
import tritium.rendering.Framebuffer;
import tritium.rendering.rendersystem.RenderSystem;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class FontAtlasRenderingTest {
    @Test
    void rendersGlyphsFromOldAndNewPagesInTheSameString() throws Exception {
        DesktopNativeLoader.configureLibraryPath();
        TextureAtlas atlas = new TextureAtlas();
        CFontRenderer renderer = null;
        try {
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            invokeApp("prepareFrameState");
            renderer = new CFontRenderer(new Font(Font.SANS_SERIF, Font.PLAIN, 20), 20);
            // Each image fills one production-size page. Distinct alpha values detect
            // both switching to the new page and switching back to an earlier page.
            Glyph first = new Glyph(20, 20, 'A');
            first.setAtlasRegion(atlas.upload(solidGlyph(255)));
            Glyph second = new Glyph(20, 20, 'B');
            second.setAtlasRegion(atlas.upload(solidGlyph(128)));
            assertNotEquals(first.textureId, second.textureId);
            renderer.allGlyphs['A'] = first;
            renderer.allGlyphs['B'] = second;
            GL11.glClearColor(0, 0, 0, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            assertTrue(renderer.drawString("AABA", 10, 12, 1, 1, 1, 1));
            assertBrightness(15, 15, 255);
            assertBrightness(25, 15, 255);
            assertBrightness(35, 15, 128);
            assertBrightness(45, 15, 255);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            if (Display.isCreated()) {
                if (renderer != null) renderer.close();
                atlas.destroy();
                Display.destroy();
            }
        }
    }

    private static BufferedImage solidGlyph(int alpha) {
        BufferedImage image = new BufferedImage(2044, 2044, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[2044 * 2044];
        Arrays.fill(pixels, (alpha << 24) | 0xffffff);
        image.setRGB(0, 0, 2044, 2044, pixels, 0, 2044);
        return image;
    }

    private static void assertBrightness(int x, int y, int expected) {
        float scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(Math.round(x * scale), RenderSystem.getFramebufferHeight() - Math.round(y * scale),
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected, pixel.get(channel) & 255, 1, "Glyph at x=" + x);
        }
    }

    private static void invokeApp(String method) throws Exception {
        var target = TritiumMusicDesktopApp.class.getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(null);
    }
}
