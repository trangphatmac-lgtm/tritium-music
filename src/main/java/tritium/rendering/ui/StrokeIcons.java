package tritium.rendering.ui;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;
import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.uniform.Uniform1f;
import tritium.rendering.shader.uniform.Uniform2f;
import tritium.rendering.shader.uniform.Uniform4f;

/** Small connected strokes, composited once so translucent joins stay seamless. */
public final class StrokeIcons implements SharedConstants {
    private static final ShaderProgram PROGRAM = new ShaderProgram("stroke-icon.frag", "vertex.vsh");
    private static final Uniform2f SIZE = new Uniform2f(PROGRAM, "size");
    private static final Uniform2f START = new Uniform2f(PROGRAM, "start");
    private static final Uniform2f JOINT = new Uniform2f(PROGRAM, "joint");
    private static final Uniform2f END = new Uniform2f(PROGRAM, "end");
    private static final Uniform1f RADIUS = new Uniform1f(PROGRAM, "radius");
    private static final Uniform4f COLOR = new Uniform4f(PROGRAM, "color");

    private StrokeIcons() { }

    public static void chevron(double x, double y, double width, double height,
                               boolean expanded, double thickness, int color) {
        double direction = expanded ? -1 : 1;
        draw(x - width / 2, y - direction * height / 2,
                x, y + direction * height / 2,
                x + width / 2, y - direction * height / 2, thickness, color);
    }

    public static void checkmark(double x, double y, double width, double height, double thickness, int color) {
        draw(x - width / 2, y, x - width / 5, y + height / 2,
                x + width / 2, y - height / 2, thickness, color);
    }

    private static void draw(double ax, double ay, double bx, double by, double cx, double cy,
                             double thickness, int color) {
        double padding = thickness / 2 + 2;
        double left = Math.min(ax, Math.min(bx, cx)) - padding;
        double top = Math.min(ay, Math.min(by, cy)) - padding;
        double width = Math.max(ax, Math.max(bx, cx)) + padding - left;
        double height = Math.max(ay, Math.max(by, cy)) + padding - top;
        PROGRAM.start();
        try {
            SIZE.setValue((float) width, (float) height);
            START.setValue((float) (ax - left), (float) (ay - top));
            JOINT.setValue((float) (bx - left), (float) (by - top));
            END.setValue((float) (cx - left), (float) (cy - top));
            RADIUS.setValue((float) (thickness / 2));
            COLOR.setValue(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f,
                    (color & 255) / 255f, (color >>> 24) / 255f);
            api.getGLStateManager().enableBlend();
            api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            api.getGLStateManager().disableAlpha();
            ShaderProgram.drawQuad(left, top, width, height);
        } finally {
            ShaderProgram.stop();
        }
    }
}
