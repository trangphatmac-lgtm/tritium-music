package tritium.minecraft.runtime;

import org.lwjgl.opengl.*;

/** A world-space sandbox. Raw calls keep Minecraft's state cache untouched. */
final class WorldGlState implements AutoCloseable {
    private final int activeTexture, matrixMode, program, unpackBuffer;
    private final boolean shaders, pbo;

    WorldGlState() {
        ContextCapabilities caps = GLContext.getCapabilities();
        shaders = caps.OpenGL20; pbo = caps.OpenGL21;
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        program = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        unpackBuffer = pbo ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        if (shaders) GL20.glUseProgram(0);
        if (pbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        for (int i = units - 1; i >= 0; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glDisable(GL11.GL_TEXTURE_1D); GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL12.GL_TEXTURE_3D); GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S); GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R); GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glColorMask(true, true, true, true);
        textBlend();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    }

    static void textBlend() {
        GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override public void close() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(matrixMode);
        GL11.glPopClientAttrib(); GL11.glPopAttrib();
        if (pbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        if (shaders) GL20.glUseProgram(program);
        GL13.glActiveTexture(activeTexture);
    }
}
