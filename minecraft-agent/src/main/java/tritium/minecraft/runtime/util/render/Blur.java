package tritium.minecraft.runtime.util.render;
import tritium.minecraft.runtime.hook.RenderAccess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.List;

public class Blur {
    public Blur(RenderAccess g,Render3D draw){this.g=g;this.draw=draw;this.first=new Framebuffer(g,1,1,false);this.stencil=new Framebuffer(g,1,1,true);}
    private final RenderAccess g;
    private final Render3D draw;
    

    public Framebuffer first = null;
    public List<Framebuffer> buffers = new ArrayList<Framebuffer>();
    public Shader up = new Shader(Shader.KAWASE_UP);
    public Shader down = new Shader(Shader.KAWASE_DOWN);
    public Framebuffer stencil = null;
    public int iterations;



    public void setup(float n) {
        if (!this.buffers.contains(this.first)) this.first.deleteFramebuffer();
        for (Framebuffer fb : this.buffers) {
            fb.deleteFramebuffer();
        }
        this.buffers.clear();
        this.first = this.resize(null, false);
        this.buffers.add(this.first);
        for (int i = 1; (float) i <= n; ++i) {
            Framebuffer fb = new Framebuffer(g,(int) ((double) g.displayWidth() / Math.pow(2.0, i)), (int) ((double) g.displayHeight() / Math.pow(2.0, i)), false);
            fb.setFramebufferFilter(9729);
            g.state("bindTexture", fb.framebufferTexture);
            GL11.glTexParameteri(3553, 10242, 33648);
            GL11.glTexParameteri(3553, 10243, 33648);
            g.state("bindTexture", 0);
            this.buffers.add(fb);
        }
    }

    public Framebuffer resize(Framebuffer fb, boolean depth) {
        if (fb == null || fb.framebufferWidth != g.displayWidth() || fb.framebufferHeight != g.displayHeight()) {
            if (fb != null) {
                fb.deleteFramebuffer();
            }
            return new Framebuffer(g,g.displayWidth(), g.displayHeight(), depth);
        }
        return fb;
    }

    public void pass(Framebuffer fb, int texture, Shader shader, float offset) {
        fb.framebufferClear();
        fb.bindFramebuffer(false);
        shader.use();
        GL11.glBindTexture(3553, texture);
        shader.uniformf("offset", offset, offset);
        shader.uniformi("inTexture", 0);
        shader.uniformi("check", 0);
        shader.uniformf("halfpixel", 1.0f / (float) fb.framebufferWidth, 1.0f / (float) fb.framebufferHeight);
        shader.uniformf("iResolution", fb.framebufferWidth, fb.framebufferHeight);
        draw.quad();
        shader.stop();
    }

    public void startStencil() {
        this.stencil = this.resize(this.stencil, false);
        this.stencil.framebufferClear();
        this.stencil.bindFramebuffer(false);
    }

    public void render(int n, float offset) {
        int i;
        this.stencil.unbindFramebuffer();
        if (n <= 0 || offset <= 0.0f) {
            new Framebuffer(g,g.invoke(g.mc,"getFramebuffer")).bindFramebuffer(true);
            return;
        }
        if (this.iterations != n || this.first.framebufferWidth != g.displayWidth() || this.first.framebufferHeight != g.displayHeight()) {
            this.setup(n);
            this.iterations = n;
        }
        this.pass(this.buffers.get(1), new Framebuffer(g,g.invoke(g.mc,"getFramebuffer")).framebufferTexture, this.down, offset);
        for (i = 1; i < n; ++i) {
            this.pass(this.buffers.get(i + 1), this.buffers.get(i).framebufferTexture, this.down, offset);
        }
        for (i = n; i > 1; --i) {
            this.pass(this.buffers.get(i - 1), this.buffers.get(i).framebufferTexture, this.up, offset);
        }
        Framebuffer out = this.buffers.get(0);
        out.framebufferClear();
        out.bindFramebuffer(false);
        this.up.use();
        this.up.uniformf("offset", offset, offset);
        this.up.uniformi("inTexture", 0);
        this.up.uniformi("check", 1);
        this.up.uniformi("textureToCheck", 16);
        this.up.uniformf("halfpixel", 1.0f / (float) out.framebufferWidth, 1.0f / (float) out.framebufferHeight);
        this.up.uniformf("iResolution", out.framebufferWidth, out.framebufferHeight);
        GL13.glActiveTexture(34000);
        GL11.glBindTexture(3553, this.stencil.framebufferTexture);
        GL13.glActiveTexture(33984);
        GL11.glBindTexture(3553, this.buffers.get(1).framebufferTexture);
        draw.quad();
        this.up.stop();
        new Framebuffer(g,g.invoke(g.mc,"getFramebuffer")).bindFramebuffer(true);
        GL11.glBindTexture(3553, this.buffers.get(0).framebufferTexture);
        g.state("enableAlpha");
        g.state("alphaFunc", 516, 0.0f);
        g.state("enableBlend");
        g.state("blendFunc", 770, 771);
        draw.quad();
        g.state("bindTexture", 0);
        g.state("disableBlend");
    }
public void close(){stencil.deleteFramebuffer();if(!buffers.contains(first))first.deleteFramebuffer();for(Framebuffer fb:buffers)fb.deleteFramebuffer();buffers.clear();up.close();down.close();}
}
