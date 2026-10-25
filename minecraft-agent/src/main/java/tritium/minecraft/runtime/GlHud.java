package tritium.minecraft.runtime;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;

/** All methods run on Minecraft's render thread; raw GL calls leave its state cache unchanged. */
final class GlHud implements AutoCloseable {
    private final Map<String, Texture> textures = new HashMap<String, Texture>();
    private int program, activeTexture, matrixMode, unpackBuffer;
    private boolean shader, pbo;

    void begin(int pixelWidth, int pixelHeight, int width, int height) {
        ContextCapabilities caps = GLContext.getCapabilities();
        shader = caps.OpenGL20; pbo = caps.OpenGL21;
        program = shader ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        unpackBuffer = pbo ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        if (shader) GL20.glUseProgram(0);
        if (pbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        for (int i = units - 1; i >= 0; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_TEXTURE_1D); GL11.glDisable(GL12.GL_TEXTURE_3D); GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S); GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R); GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        }
        GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glViewport(0, 0, pixelWidth, pixelHeight);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG); GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glColorMask(true, true, true, true);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    }

    void upload(String id, BufferedImage image) {
        Texture t = textures.get(id);
        if (t == null) { t = new Texture(); t.id = GL11.glGenTextures(); textures.put(id, t); }
        int w = image.getWidth(), h = image.getHeight(), size = w * h * 4;
        if (t.bytes == null || t.bytes.capacity() < size) t.bytes = BufferUtils.createByteBuffer(size);
        t.bytes.clear();
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        t.bytes.asIntBuffer().put(pixels, 0, w * h); t.bytes.limit(size);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (t.width != w || t.height != h) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, t.bytes);
            t.width = w; t.height = h;
        } else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, t.bytes);
    }

    void draw(String id, double[] b) {
        Texture t = textures.get(id); if (t == null) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.id);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        vertex(0, 0, b[0], b[1]); vertex(0, 1, b[0], b[1] + b[3]);
        vertex(1, 1, b[0] + b[2], b[1] + b[3]); vertex(1, 0, b[0] + b[2], b[1]);
        GL11.glEnd();
    }
    void border(double[] b, int accent) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((accent >> 16 & 255) / 255f, (accent >> 8 & 255) / 255f, (accent & 255) / 255f, .9f);
        GL11.glLineWidth(1);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2d(b[0], b[1]); GL11.glVertex2d(b[0] + b[2], b[1]);
        GL11.glVertex2d(b[0] + b[2], b[1] + b[3]); GL11.glVertex2d(b[0], b[1] + b[3]); GL11.glEnd();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(b[0] + b[2] - 6, b[1] + b[3] - 6); GL11.glVertex2d(b[0] + b[2], b[1] + b[3] - 6);
        GL11.glVertex2d(b[0] + b[2], b[1] + b[3]); GL11.glVertex2d(b[0] + b[2] - 6, b[1] + b[3]); GL11.glEnd();
    }
    private static void vertex(double u, double v, double x, double y) { GL11.glTexCoord2d(u, v); GL11.glVertex2d(x, y); }
    void end() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPopMatrix();
        GL11.glMatrixMode(matrixMode);
        GL11.glPopClientAttrib(); GL11.glPopAttrib();
        if (pbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        if (shader) GL20.glUseProgram(program);
        GL13.glActiveTexture(activeTexture);
    }
    public void close() { for (Texture t : textures.values()) GL11.glDeleteTextures(t.id); textures.clear(); }
    private static final class Texture { int id, width, height; ByteBuffer bytes; }
}
