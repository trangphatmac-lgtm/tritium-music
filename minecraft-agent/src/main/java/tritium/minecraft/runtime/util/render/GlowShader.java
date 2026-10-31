package tritium.minecraft.runtime.util.render;
import tritium.minecraft.runtime.hook.RenderAccess;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class GlowShader {
    private final RenderAccess g;
    private final Render3D draw;
    

    private int iterations;
    private final Shader down;
    private final Shader up;
    private Framebuffer capture = null;
    private Framebuffer first = null;
    private final List<Framebuffer> buffers = new ArrayList<Framebuffer>();



    public GlowShader(RenderAccess g,Render3D draw) {
        this.g=g;this.draw=draw;this.capture=new Framebuffer(g,1,1,false);this.first=new Framebuffer(g,1,1,true);
        this.up = new Shader(ShaderSources.KAWASE_BLOOM_UP);
        this.down = new Shader(ShaderSources.KAWASE_BLOOM_DOWN);
    }

    public void render(int n, float f) {
        int i;
        this.capture.unbindFramebuffer();
        if (n <= 0 || f <= 0.0f) {
            new Framebuffer(g,g.invoke(g.mc,"getFramebuffer")).bindFramebuffer(true);
            return;
        }
        if (this.iterations != n || this.first.framebufferWidth != g.displayWidth() || this.first.framebufferHeight != g.displayHeight()) {
            this.setup(n);
            this.iterations = n;
        }
        g.state("enableAlpha");
        g.state("alphaFunc", 516, 0.0f);
        g.state("enableBlend");
        g.state("blendFunc", 1, 1);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        this.pass(this.buffers.get(1), this.capture.framebufferTexture, this.down, f);
        for (i = 1; i < n; ++i) {
            this.pass(this.buffers.get(i + 1), this.buffers.get(i).framebufferTexture, this.down, f);
        }
        for (i = n; i > 1; --i) {
            this.pass(this.buffers.get(i - 1), this.buffers.get(i).framebufferTexture, this.up, f);
        }
        Framebuffer fb = this.buffers.get(0);
        fb.framebufferClear();
        fb.bindFramebuffer(false);
        this.up.use();
        this.up.uniformf("offset", f, f);
        this.up.uniformi("inTexture", 0);
        this.up.uniformi("check", 1);
        this.up.uniformi("textureToCheck", 16);
        this.up.uniformf("halfpixel", 1.0f / (float) fb.framebufferWidth, 1.0f / (float) fb.framebufferHeight);
        this.up.uniformf("iResolution", fb.framebufferWidth, fb.framebufferHeight);
        g.state("setActiveTexture", 34000);
        GL11.glBindTexture(3553, this.capture.framebufferTexture);
        g.state("setActiveTexture", 33984);
        GL11.glBindTexture(3553, this.buffers.get(1).framebufferTexture);
        draw.quad();
        this.up.stop();
        g.state("clearColor", 0.0f, 0.0f, 0.0f, 0.0f);
        new Framebuffer(g,g.invoke(g.mc,"getFramebuffer")).bindFramebuffer(false);
        GL11.glBindTexture(3553, this.buffers.get(0).framebufferTexture);
        g.state("enableAlpha");
        g.state("alphaFunc", 516, 0.0f);
        g.state("enableBlend");
        g.state("blendFunc", 770, 771);
        draw.quad();
        g.state("bindTexture", 0);
        g.state("enableAlpha");
        g.state("alphaFunc", 516, 0.0f);
        g.state("enableBlend");
        g.state("blendFunc", 770, 771);
    }

    private Framebuffer resize(Framebuffer fb, boolean depth) {
        if (fb == null || fb.framebufferWidth != g.displayWidth() || fb.framebufferHeight != g.displayHeight()) {
            if (fb != null) {
                fb.deleteFramebuffer();
            }
            return new Framebuffer(g,g.displayWidth(), g.displayHeight(), depth);
        }
        return fb;
    }

    private void setup(float f) {
        if (!this.buffers.contains(this.first)) this.first.deleteFramebuffer();
        for (Framebuffer fb : this.buffers) {
            fb.deleteFramebuffer();
        }
        this.buffers.clear();
        this.first = this.resize(null, true);
        this.buffers.add(this.first);
        int n = 1;
        while ((float) n <= f) {
            Framebuffer fb = new Framebuffer(g,(int) ((double) g.displayWidth() / Math.pow(2.0, n)), (int) ((double) g.displayHeight() / Math.pow(2.0, n)), true);
            fb.setFramebufferFilter(9729);
            g.state("bindTexture", fb.framebufferTexture);
            GL11.glTexParameteri(3553, 10242, 33648);
            GL11.glTexParameteri(3553, 10243, 33648);
            g.state("bindTexture", 0);
            this.buffers.add(fb);
            ++n;
        }
    }

    private void pass(Framebuffer fb, int texture, Shader shader, float f) {
        fb.framebufferClear();
        fb.bindFramebuffer(false);
        shader.use();
        GL11.glBindTexture(3553, texture);
        shader.uniformf("offset", f, f);
        shader.uniformi("inTexture", 0);
        shader.uniformi("check", 0);
        shader.uniformf("halfpixel", 1.0f / (float) fb.framebufferWidth, 1.0f / (float) fb.framebufferHeight);
        shader.uniformf("iResolution", fb.framebufferWidth, fb.framebufferHeight);
        draw.quad();
        shader.stop();
    }

    public void startCapture() {
        this.capture = this.resize(this.capture, false);
        this.capture.framebufferClear();
        this.capture.bindFramebuffer(false);
    }

    public void render(Framebuffer fb, int n, float f) {
        Framebuffer saved = this.capture;
        this.capture = fb;
        try {
            this.render(n, f);
        } finally {
            this.capture = saved;
        }
    }
public void close(){capture.deleteFramebuffer();if(!buffers.contains(first))first.deleteFramebuffer();for(Framebuffer fb:buffers)fb.deleteFramebuffer();buffers.clear();up.close();down.close();}
}
