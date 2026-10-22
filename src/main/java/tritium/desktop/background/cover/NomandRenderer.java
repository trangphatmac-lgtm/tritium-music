// Folia Nomand defaults and overscan, AGPL-3.0; Paper shaders, Apache-2.0. See cover/NOTICE.txt.
package tritium.desktop.background.cover;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import tritium.desktop.background.BackgroundShader;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Bakes the original Paper image shaders once per artwork, theme, effect or viewport change. */
final class NomandRenderer implements AutoCloseable {
    private final BackgroundShader shader;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private final String effect;
    private int noise;

    NomandRenderer(String effect) throws IOException {
        this.effect = effect;
        shader = new BackgroundShader(BackgroundShader.resource("cover/nomand.vert"),
                BackgroundShader.resource("cover/" + (effect.equals("dithering") ? "image-dithering" : effect) + ".frag"));
        try {
            if (effect.equals("paper-texture")) {
                try (var stream = getClass().getResourceAsStream("/tritium/background/cover/paper-noise.png")) {
                    if (stream == null) throw new IOException("Missing Paper noise texture");
                    noise = CoverArtwork.upload(ImageIO.read(stream));
                }
            }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }

    int bake(int source, int imageWidth, int imageHeight, int width, int height, double logicalWidth,
             int background, int accent, int primary, int secondary, boolean light) {
        int oldFb = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        int framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
        int result = CoverArtwork.allocate(width, height);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
            EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                    EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, result, 0);
            if (EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                    != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) throw new IllegalStateException("Nomand framebuffer incomplete");
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, noise);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            shader.draw(logicalWidth, logicalWidth * height / width, width, height, 0, () -> {
                GL20.glUniform2f(uniform("u_resolution"), width, height);
                f("u_pixelRatio", width / logicalWidth);
                f("u_imageAspectRatio", (double) imageWidth / imageHeight);
                i("u_image", 0); i("u_noiseTexture", 1);
                f("u_fit", 2); f("u_originX", .5); f("u_originY", .5);
                f("u_scale", effect.equals("paper-texture") ? paperOverscan() : effect.equals("lens-distortion") ? lensOverscan() : 1);
                color("u_colorBack", background); color("u_colorFront", accent);
                color("u_colorHighlight", primary); color("u_colorShadow", secondary);
                switch (effect) {
                    case "dithering" -> {
                        f("u_type", 4); f("u_pxSize", 3); f("u_colorSteps", 4);
                        i("u_inverted", light ? 1 : 0);
                    }
                    case "fluted-glass" -> {
                        f("u_shadows", .25); f("u_size", .5); f("u_distortion", .5); f("u_blur", .08);
                        f("u_shape", 1); f("u_distortionShape", 1); f("u_highlights", .1); f("u_edges", .25);
                    }
                    case "paper-texture" -> {
                        f("u_contrast", .32); f("u_roughness", .42); f("u_fiber", .3); f("u_fiberSize", .2);
                        f("u_crumples", .35); f("u_crumpleSize", .35); f("u_folds", .65); f("u_foldCount", 5);
                        f("u_drops", .2); f("u_seed", 5.8);
                    }
                    case "halftone-dots" -> {
                        f("u_size", .5); f("u_radius", 1.25); f("u_contrast", .4); i("u_inverted", light ? 0 : 1);
                        f("u_grid", 1); f("u_type", 1); f("u_grainMixer", .12); f("u_grainOverlay", .06); f("u_grainSize", .5);
                    }
                    case "lens-distortion" -> {
                        f("u_spread", .45); f("u_dispersion", .65); f("u_lensBulge", .3); f("u_perspective", .4);
                        f("u_count", 20); f("u_focusCenter", .55); f("u_focusEdges", .8); f("u_swirl", .08);
                        f("u_lensCircle", .1); f("u_bias", 1); f("u_dispersionColor", .6); f("u_noiseFrequency", .25);
                    }
                }
            });
            return result;
        } catch (RuntimeException e) { GL11.glDeleteTextures(result); throw e; }
        finally {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, oldFb);
            EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    private static double paperOverscan() { return 1 / (1 - 2 * .02 * (2 * .65 + 1.5 * .35 + .2 * .2 + .75 * .42 + .1 * .3)) + .01; }
    private static double lensOverscan() {
        double radius = .5 * Math.hypot(16.0 / 9, 1), bulge = .3 * 1.4;
        double reach = .7 * Math.pow(.45, 1.3 + 2.7 * .45);
        double headroom = Math.max(.35, 1 - reach * .2 / radius);
        double low = Math.max(1, 2 * radius * bulge / 1.45), high = 1.8;
        for (int step = 0; step < 32; step++) {
            double mid = (low + high) / 2, rn = 2 * radius / mid;
            if (Math.tan(rn * bulge) / Math.tan(bulge) / rn > mid * headroom) low = mid;
            else high = mid;
        }
        return Math.min(1.8, high + .01);
    }

    private int uniform(String name) { return uniforms.computeIfAbsent(name, shader::uniform); }
    private void f(String name, double value) { GL20.glUniform1f(uniform(name), (float) value); }
    private void i(String name, int value) { GL20.glUniform1i(uniform(name), value); }
    private void color(String name, int color) {
        GL20.glUniform4f(uniform(name), ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, 1);
    }
    @Override public void close() { shader.close(); if (noise != 0) GL11.glDeleteTextures(noise); }
}
