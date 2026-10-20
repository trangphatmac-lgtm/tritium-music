package tritium.rendering.shader.impl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import tritium.rendering.Framebuffer;
import tritium.rendering.StencilClipManager;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shader;
import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.uniform.Uniform1i;
import tritium.rendering.shader.uniform.Uniform2f;
import tritium.rendering.shader.uniform.UniformFB;

import java.nio.FloatBuffer;
import java.util.List;

public class GaussianBlurShader extends Shader {

    private final ShaderProgram blurProgram = new ShaderProgram("blur.frag", "vertex.vsh");
    private Framebuffer inputFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
    private Framebuffer outputFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
    private Framebuffer sceneFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);
    private GaussianKernel gaussianKernel = new GaussianKernel(0);

    private final Uniform1i u_radius = new Uniform1i(blurProgram, "u_radius");
    private final UniformFB u_kernel = new UniformFB(blurProgram, "u_kernel");

    private final Uniform1i u_diffuse_sampler = new Uniform1i(blurProgram, "u_diffuse_sampler");
    private final Uniform1i u_other_sampler = new Uniform1i(blurProgram, "u_other_sampler");
    private final Uniform1i u_original_sampler = new Uniform1i(blurProgram, "u_original_sampler");
    private final Uniform1i u_layer = new Uniform1i(blurProgram, "u_layer");
    private final Uniform2f u_texel_size = new Uniform2f(blurProgram, "u_texel_size");
    private final Uniform2f u_direction = new Uniform2f(blurProgram, "u_direction");

    @Override
    public void run(List<Runnable> masks) {
        if (!Display.isVisible() || masks.isEmpty()) return;
        Framebuffer target = Framebuffer.currentlyBinding;
        this.update();
        target.bindFramebuffer(true);
        sceneFramebuffer.bindFramebufferTexture();
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight());
        renderBlur(masks, target, false);
    }

    /** Render and blur premultiplied foreground pixels without re-blurring the backdrop. */
    public void runLayer(Runnable render, List<Runnable> masks) {
        if (!Display.isVisible()) return;
        Framebuffer target = Framebuffer.currentlyBinding;
        this.update();
        sceneFramebuffer.bindFramebuffer(true);
        sceneFramebuffer.setFramebufferColor(0, 0, 0, 0);
        sceneFramebuffer.framebufferClearNoBinding();
        StencilClipManager.disableStencilTest();
        try {
            render.run();
            renderBlur(masks, target, true);
        } finally {
            target.bindFramebuffer(true);
        }
    }

    private void renderBlur(List<Runnable> runnable, Framebuffer target, boolean layer) {
        this.inputFramebuffer.bindFramebuffer(true);
        this.inputFramebuffer.setFramebufferColor(1, 1, 1, 0);
        this.inputFramebuffer.framebufferClearNoBinding();

        StencilClipManager.disableStencilTest();
        runnable.forEach(Runnable::run);

        // TODO: make radius and other things as a setting
        final int radius = 5;
        final float compression = .5f;

        this.outputFramebuffer.bindFramebuffer(true);
        this.outputFramebuffer.framebufferClearNoBinding();
        StencilClipManager.disableStencilTest();

        // Masks use the scene's transform; texture passes must use screen coordinates.
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().loadIdentity();
        api.getGLStateManager().translate(0, 0, -2000);
        this.blurProgram.start();
        u_layer.setValue(layer ? 1 : 0);
        u_original_sampler.setValue(2);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        sceneFramebuffer.bindFramebufferTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        if (this.gaussianKernel.getSize() != radius) {
            this.gaussianKernel = new GaussianKernel(radius);
            this.gaussianKernel.compute();

            final FloatBuffer buffer = BufferUtils.createFloatBuffer(radius);
            buffer.put(this.gaussianKernel.getKernel());
            buffer.flip();

            u_radius.setValue(radius);
            u_kernel.setValue(buffer);
        }

        u_diffuse_sampler.setValue(0);
        // Use a low texture unit supported by the OpenGL 2.1 desktop profile.
        u_other_sampler.setValue(1);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        inputFramebuffer.bindFramebufferTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        u_texel_size.setValue((float) (1.0F / RenderSystem.getWidth()), (float) (1.0F / RenderSystem.getHeight()));
        u_direction.setValue(compression, 0.0F);

        api.getGLStateManager().disableBlend();
        api.getGLStateManager().disableAlpha();
        sceneFramebuffer.bindFramebufferTexture();
        ShaderProgram.drawQuadFlipped();

        target.bindFramebuffer(true);
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(layer ? GL11.GL_ONE : GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        u_direction.setValue(0.0F, compression);
        outputFramebuffer.bindFramebufferTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        inputFramebuffer.bindFramebufferTexture();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        ShaderProgram.drawQuadFlipped();
        api.getGLStateManager().disableBlend();

        ShaderProgram.stop();
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        api.getGLStateManager().bindTexture(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        api.getGLStateManager().bindTexture(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        api.getGLStateManager().popMatrix();
    }

    @Override
    public void update() {
        this.setActive(false);

        if (RenderSystem.getFramebufferWidth() != inputFramebuffer.framebufferWidth || RenderSystem.getFramebufferHeight() != inputFramebuffer.framebufferHeight) {
            inputFramebuffer.deleteFramebuffer();
            inputFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);

            outputFramebuffer.deleteFramebuffer();
            outputFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);

            sceneFramebuffer.deleteFramebuffer();
            sceneFramebuffer = new Framebuffer(RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), true);

        }
    }
}
