package tritium.screens.ncm;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import tritium.desktop.AppTheme;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.ShaderProgram;

import java.nio.ByteBuffer;

/** Background-only floor; artwork depth and reflections are handled separately by {@link CoverflowSsr}. */
final class CoverflowReflection implements AutoCloseable {
    private int texture, width, height;
    private ShaderProgram shader;
    private int sourceUniform, horizonUniform, extentUniform, pixelUniform, surfaceUniform, opacityUniform;

    void draw(double mirrorY, float opacity) {
        double screenWidth = RenderSystem.getWidth(), screenHeight = RenderSystem.getHeight();
        double reflectionHeight = screenHeight - mirrorY;
        if (reflectionHeight <= 0 || opacity <= 0) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            ensureTexture();
            ensureShader();
            // Capture before any covers or text, keeping background and cover reflections independent.
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            shader.start();
            GL20.glUniform1i(sourceUniform, 0);
            GL20.glUniform1f(horizonUniform, (float) (mirrorY / screenHeight));
            GL20.glUniform1f(extentUniform, (float) (reflectionHeight / screenHeight));
            GL20.glUniform4f(pixelUniform, 1f / (float) screenWidth, 1f / (float) screenHeight,
                    1f / width, 1f / height);
            int surface = AppTheme.color(0xFF11161D, 0xFFE2E7EC);
            GL20.glUniform3f(surfaceUniform, ((surface >> 16) & 255) / 255f,
                    ((surface >> 8) & 255) / 255f, (surface & 255) / 255f);
            GL20.glUniform1f(opacityUniform, opacity);
            ShaderProgram.drawQuad(0, mirrorY, screenWidth, reflectionHeight);
        } finally {
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GL13.glActiveTexture(previousUnit);
        }
    }

    private void ensureShader() {
        if (shader != null) return;
        shader = new ShaderProgram("coverflow_floor.frag", "vertex.vsh");
        int program = shader.getProgramId();
        sourceUniform = GL20.glGetUniformLocation(program, "scene");
        horizonUniform = GL20.glGetUniformLocation(program, "horizon");
        extentUniform = GL20.glGetUniformLocation(program, "extent");
        pixelUniform = GL20.glGetUniformLocation(program, "pixelSize");
        surfaceUniform = GL20.glGetUniformLocation(program, "surface");
        opacityUniform = GL20.glGetUniformLocation(program, "opacity");
    }

    private void ensureTexture() {
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        if (texture == 0) texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (width == w && height == h) return;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        width = w;
        height = h;
    }

    @Override
    public void close() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = width = height = 0;
        if (shader != null) GL20.glDeleteProgram(shader.getProgramId());
        shader = null;
    }
}
