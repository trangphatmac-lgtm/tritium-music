package tritium.screens.ncm.lyrics;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;
import tritium.rendering.Framebuffer;
import tritium.rendering.StencilClipManager;
import tritium.rendering.rendersystem.RenderSystem;

/** Composite a complete scene once, including opaque cover shaders, during page fades. */
final class LyricSurface implements AutoCloseable, SharedConstants {
    private Framebuffer buffer;

    void render(double opacity, Runnable draw) {
        Framebuffer target = Framebuffer.currentlyBinding;
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        try {
            if (buffer == null || buffer.framebufferWidth != width || buffer.framebufferHeight != height) {
                close(); buffer = new Framebuffer(width, height, true);
                buffer.setFramebufferColor(0, 0, 0, 0);
            }
            buffer.bindFramebuffer(true);
            buffer.framebufferClearNoBinding();
            StencilClipManager.disableStencilTest();
            draw.run();
        } finally { target.bindFramebuffer(true); }

        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().disableDepth();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        buffer.bindFramebufferTexture();
        GL11.glPushMatrix(); GL11.glLoadIdentity(); GL11.glTranslated(0, 0, -2000);
        try {
            float alpha = (float) LyricMath.clamp(opacity);
            api.getGLStateManager().color(alpha, alpha, alpha, alpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2d(0, 1); GL11.glVertex2d(0, 0);
            GL11.glTexCoord2d(1, 1); GL11.glVertex2d(RenderSystem.getWidth(), 0);
            GL11.glTexCoord2d(1, 0); GL11.glVertex2d(RenderSystem.getWidth(), RenderSystem.getHeight());
            GL11.glTexCoord2d(0, 0); GL11.glVertex2d(0, RenderSystem.getHeight());
            GL11.glEnd();
        } finally {
            GL11.glPopMatrix();
            api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    @Override public void close() {
        if (buffer == null) return;
        Framebuffer previous = Framebuffer.currentlyBinding;
        Framebuffer disposed = buffer;
        buffer = null;
        try { disposed.deleteFramebuffer(); }
        finally {
            // Tritium's deletion helper unconditionally binds the display framebuffer.
            // A scene switch may occur inside the parent page's stencil clip.
            if (previous != null && previous != disposed) previous.bindFramebuffer(true);
        }
    }
}
