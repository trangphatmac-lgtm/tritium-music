package tritium.minecraft.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import tritium.minecraft.protocol.RadarFrame;

/** The same OpenB4 geometry is drawn by both JVMs; no Minecraft or desktop classes are linked here. */
public final class RadarPainter {
    private RadarPainter() { }

    public static void draw(RadarFrame frame, RadarFrame.Placement placement,
                            double pixelsPerUnitX, double pixelsPerUnitY, int framebufferHeight) {
        if (!frame.visible || !frame.valid()) return;
        int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
        try {
            GL20.glUseProgram(0);
            GL11.glTranslated(placement.x, placement.y, 0);
            GL11.glScaled(placement.scale, placement.scale, 1);
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false); GL11.glEnable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true); GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            double x = frame.x, y = frame.y, right = x + frame.width, bottom = y + frame.height, b = frame.borderWidth;
            rect(x, y, right, bottom, frame.background);
            rect(x - b, y - b, right + b, y, frame.border);
            rect(x - b, bottom, right + b, bottom + b, frame.border);
            rect(x - b, y, x, bottom, frame.border);
            rect(right, y, right + b, bottom, frame.border);
            polygon(frame.centerX, frame.centerY, frame.selfRadius, 3, frame.self);
            int leftPixel = (int) Math.floor((placement.x + x * placement.scale) * pixelsPerUnitX);
            int rightPixel = (int) Math.ceil((placement.x + right * placement.scale) * pixelsPerUnitX);
            int topPixel = (int) Math.floor((placement.y + y * placement.scale) * pixelsPerUnitY);
            int bottomPixel = (int) Math.ceil((placement.y + bottom * placement.scale) * pixelsPerUnitY);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(leftPixel, framebufferHeight - bottomPixel, rightPixel - leftPixel, bottomPixel - topPixel);
            for (int i = 0; i < frame.points.length; i += 2) {
                if (frame.tracers) {
                    GL11.glEnable(GL11.GL_LINE_SMOOTH);
                    // OpenB4's line width is in physical pixels, independent of Minecraft GUI scale.
                    GL11.glLineWidth(frame.tracerWidth); color(frame.border);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2d(frame.centerX, frame.centerY);
                    GL11.glVertex2d(frame.points[i], frame.points[i + 1]); GL11.glEnd();
                }
                polygon(frame.points[i], frame.points[i + 1], frame.targetRadius, 4, frame.target);
            }
        } finally {
            GL11.glPopMatrix(); GL11.glMatrixMode(matrix); GL11.glPopAttrib(); GL20.glUseProgram(program);
        }
    }
    private static void rect(double x, double y, double right, double bottom, int color) {
        color(color); GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x, y); GL11.glVertex2d(x, bottom); GL11.glVertex2d(right, bottom); GL11.glVertex2d(right, y);
        GL11.glEnd();
    }
    private static void polygon(double x, double y, double radius, int vertices, int color) {
        color(color); GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        for (int i = 0; i < vertices; i++) {
            double angle = Math.PI * 2 * i / vertices + Math.PI;
            GL11.glVertex2d(x + Math.sin(angle) * radius, y + Math.cos(angle) * radius);
        }
        GL11.glEnd();
    }
    private static void color(int color) {
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
    }
}
