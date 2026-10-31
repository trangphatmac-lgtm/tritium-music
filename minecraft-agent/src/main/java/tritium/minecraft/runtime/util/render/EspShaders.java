package tritium.minecraft.runtime.util.render;
import tritium.minecraft.runtime.hook.RenderAccess;

import org.lwjgl.opengl.GL11;

public final class EspShaders {
    private final RenderAccess g;
    private final Render3D draw;
    private Shader fill;
    private Shader roundRect;
    private Shader outline;

    public EspShaders(RenderAccess g,Render3D draw) {this.g=g;this.draw=draw;}

    public Shader fill() {
        if (fill == null) {
            fill = new Shader(ShaderSources.OUTLINE_MASK, ShaderSources.OUTLINE_CAPTURE_VERTEX);
        }
        return fill;
    }

    public Shader roundRect() {
        if (roundRect == null) {
            roundRect = new Shader(ShaderSources.ELLIPSE);
        }
        return roundRect;
    }

    public Shader outline() {
        if (outline == null) {
            outline = new Shader(ShaderSources.OUTLINE);
        }
        return outline;
    }

    public boolean valid(Shader s) {
        return s.program > 0;
    }

    public void useFill(int n) {
        Shader s = fill();
        s.use();
        s.uniformi("u_texture", 0);
        s.uniformf("u_color", (float) (n >> 16 & 0xFF) / 255.0f, (float) (n >> 8 & 0xFF) / 255.0f, (float) (n & 0xFF) / 255.0f);
        s.uniformf("u_alpha_threshold", 0.001f);
    }

    public void roundRect(float x, float y, float x2, float y2, float radius, int n) {
        Shader s = roundRect();
        if (s.program == -1) {
            return;
        }
        s.use();
        float w = x2 - x;
        float h = y2 - y;
        s.uniformf("u_size", w, h);
        s.uniformf("u_radius", radius);
        float a = (float) (n >> 24 & 0xFF) / 255.0f;
        float r = (float) (n >> 16 & 0xFF) / 255.0f;
        float green = (float) (n >> 8 & 0xFF) / 255.0f;
        float b = (float) (n & 0xFF) / 255.0f;
        s.uniformf("u_color", r, green, b, a);
        g.state("color", r, green, b, a);
        g.state("enableBlend");
        g.state("blendFunc", 770, 771);
        g.state("enableAlpha");
        g.state("alphaFunc", 516, 0.0f);
        Render3D.quad(x, y, w, h);
        g.state("color", 1.0f, 1.0f, 1.0f, 1.0f);
        g.state("disableBlend");
        s.stop();
    }

    private void fullscreenQuad() {
        double w = g.width();
        double h = g.height();
        GL11.glBegin(7);
        GL11.glTexCoord2d(0.0, 1.0);
        GL11.glVertex2d(0.0, 0.0);
        GL11.glTexCoord2d(0.0, 0.0);
        GL11.glVertex2d(0.0, h);
        GL11.glTexCoord2d(1.0, 0.0);
        GL11.glVertex2d(w, h);
        GL11.glTexCoord2d(1.0, 1.0);
        GL11.glVertex2d(w, 0.0);
        GL11.glEnd();
    }

    public void outline(Framebuffer fb, float radius, float threshold) {
        Shader s = outline();
        if (!valid(s)) {
            return;
        }
        s.use();
        s.uniformi("u_texture", 0);
        s.uniformf("u_texel_size", 1.0f / (float) fb.framebufferWidth, 1.0f / (float) fb.framebufferHeight);
        s.uniformf("u_radius", radius);
        s.uniformf("u_alpha_threshold", threshold);
        g.state("bindTexture", fb.framebufferTexture);
        fullscreenQuad();
        g.state("bindTexture", 0);
        s.stop();
    }
public void close(){if(fill!=null)fill.close();if(outline!=null)outline.close();if(roundRect!=null)roundRect.close();}
}
