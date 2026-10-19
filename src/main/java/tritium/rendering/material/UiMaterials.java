package tritium.rendering.material;

import tritium.i18n.I18n;
import org.lwjgl.opengl.*;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shaders;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Main-window material pass. HUDs use their own Java2D renderers and never enter this pipeline. */
public final class UiMaterials {
    private static Program glass, blur;
    private static int scene, horizontal, softened, framebuffer;
    private static int width, height, blurWidth, blurHeight;
    private static boolean ready, failed, lyricLayer, suspended;
    private static String style = "Static";
    private static float pointerX = .5f, pointerY = .5f;

    private UiMaterials() { }

    public static boolean enabled() {
        return !suspended && !DesktopAppState.preferences().interfaceStyle().getValue().equals("Static") && !failed;
    }

    /** Keep selected legacy regions flat even when their shared widgets support glass elsewhere. */
    public static void withoutMaterials(Runnable render) {
        boolean previous = suspended;
        suspended = true;
        try {
            render.run();
        } finally {
            suspended = previous;
        }
    }

    public static String status() {
        return failed ? I18n.text("materials.failed") : I18n.text("materials.hint");
    }

    public static void beginFrame(double mouseX, double mouseY) {
        String requested = DesktopAppState.preferences().interfaceStyle().getValue();
        if (!style.equals(requested)) {
            release();
            failed = false;
            style = requested;
        }
        ready = false;
        lyricLayer = false;
        if (mouseX >= 0 && mouseY >= 0) {
            pointerX += ((float) Math.max(0, Math.min(1, mouseX / RenderSystem.getWidth())) - pointerX) * .12f;
            pointerY += ((float) Math.max(0, Math.min(1, mouseY / RenderSystem.getHeight())) - pointerY) * .12f;
        }
    }

    /** Capture once per layer, then share two blur passes across all of that layer's controls. */
    public static void captureBackdrop(boolean lyrics) {
        ready = false;
        lyricLayer = lyrics;
        if (!enabled()) return;
        if (!GLContext.getCapabilities().GL_EXT_framebuffer_object) {
            failed = true;
            return;
        }
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            ensureResources();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, scene);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
            GL11.glViewport(0, 0, blurWidth, blurHeight);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(blur.id);
            blur.i("source", 0);
            float radius = (style.equals("Blur") ? 7f : 2.2f) * RenderSystem.getUiScaleFactor();
            blur.f2("stepSize", radius / width, 0);
            blur.i("encode", 0);
            blurPass(scene, horizontal);
            blur.f2("stepSize", 0, radius / height);
            blur.i("encode", 1);
            blurPass(horizontal, softened);
            ready = true;
        } catch (IOException | RuntimeException e) {
            failed = true;
            System.err.println("UI glass materials disabled: " + e.getMessage());
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GL13.glActiveTexture(previousUnit);
        }
    }

    private static void blurPass(int input, int output) throws IOException {
        EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, output, 0);
        if (EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) throw new IOException("Incomplete blur framebuffer");
        GL11.glDrawBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, input);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }

    /** Rectangular legacy panels retain exact Static geometry and alpha. */
    public static void panel(double x, double y, double w, double h, int color) {
        if (!draw(x, y, w, h, 12, color, 0, 0)) Rect.draw(x, y, w, h, color);
    }

    public static void surface(double x, double y, double w, double h, double radius, int color) {
        if (!draw(x, y, w, h, radius, color, 0, 0)) flat(x, y, w, h, radius, color);
    }

    public static void control(double x, double y, double w, double h, double radius, int color) {
        if (!draw(x, y, w, h, radius, color, 1, 0)) flat(x, y, w, h, radius, color);
    }

    /** Icons acquire a tactile glass face only in glass modes; their glyphs remain sharp. */
    public static void icon(double x, double y, double w, double h, float alpha, float hover) {
        int tint = lyricLayer ? 0xFF272B35 : AppTheme.settingsSurface();
        draw(x, y, w, h, Math.min(w, h) * .5, RenderSystem.reAlpha(tint, alpha), 1, hover);
    }

    private static void flat(double x, double y, double w, double h, double r, int color) {
        Shaders.RQ_SHADER.draw(x, y, w, h, r, (color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24);
    }

    private static boolean draw(double x, double y, double w, double h, double radius, int color, int role, float hover) {
        if (!enabled() || !ready) return false;
        float opacity = (color >>> 24) / 255f;
        if (w <= 0 || h <= 0 || opacity <= .001f) return true;
        double r = Math.min(Math.min(w, h) * .5, Math.max(radius, Math.min(12, h * .5)));
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, softened);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL20.glUseProgram(glass.id);
            glass.i("backdrop", 0);
            glass.f2("screenSize", width, height);
            glass.f2("size", (float) w, (float) h);
            glass.f("radius", (float) r);
            glass.f("pixelScale", RenderSystem.getUiScaleFactor());
            glass.f4("tint", ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, opacity);
            glass.f("liquid", style.equals("LiquidGlass") ? 1 : 0);
            glass.f("light", lyricLayer ? 0 : (AppTheme.background() & 255) / 255f);
            glass.f("control", role);
            glass.f("hover", hover);
            glass.f2("lightDirection", -.8f + pointerX * .5f, -.9f + pointerY * .5f);
            double pad = Math.min(5, h * .25);
            GL11.glBegin(GL11.GL_QUADS);
            vertex(x - pad, y - pad, -pad, -pad);
            vertex(x - pad, y + h + pad, -pad, h + pad);
            vertex(x + w + pad, y + h + pad, w + pad, h + pad);
            vertex(x + w + pad, y - pad, w + pad, -pad);
            GL11.glEnd();
        } finally {
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GL13.glActiveTexture(previousUnit);
        }
        return true;
    }

    private static void vertex(double x, double y, double u, double v) {
        GL11.glTexCoord2d(u, v);
        GL11.glVertex2d(x, y);
    }

    private static void ensureResources() throws IOException {
        if (!GLContext.getCapabilities().GL_EXT_framebuffer_object) throw new IOException("Framebuffer support required");
        if (glass == null) glass = new Program("glass.frag", false);
        if (blur == null) blur = new Program("blur.frag", true);
        int w = RenderSystem.getFramebufferWidth(), h = RenderSystem.getFramebufferHeight();
        if (scene != 0 && width == w && height == h) return;
        releaseTextures();
        width = w;
        height = h;
        // Full-resolution capture preserves refraction coordinates; blur is capped at 1080p.
        double scale = Math.min(.5, Math.min(1920.0 / w, 1080.0 / h));
        blurWidth = Math.max(1, (int) Math.ceil(w * scale));
        blurHeight = Math.max(1, (int) Math.ceil(h * scale));
        scene = texture(w, h);
        horizontal = texture(blurWidth, blurHeight);
        softened = texture(blurWidth, blurHeight);
        framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
    }

    private static int texture(int w, int h) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return id;
    }

    private static void releaseTextures() {
        if (scene != 0) GL11.glDeleteTextures(scene);
        if (horizontal != 0) GL11.glDeleteTextures(horizontal);
        if (softened != 0) GL11.glDeleteTextures(softened);
        if (framebuffer != 0) EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
        scene = horizontal = softened = framebuffer = 0;
        ready = false;
    }

    public static void release() {
        releaseTextures();
        if (glass != null) GL20.glDeleteProgram(glass.id);
        if (blur != null) GL20.glDeleteProgram(blur.id);
        glass = blur = null;
    }

    private static final class Program {
        private final int id;
        private final Map<String, Integer> uniforms = new HashMap<>();

        Program(String fragment, boolean postProcess) throws IOException {
            int vertex = 0, pixel = 0, linked = 0;
            try (var input = UiMaterials.class.getResourceAsStream("/tritium/shaders/material/" + fragment)) {
                if (input == null) throw new IOException("Missing shader " + fragment);
                vertex = compile(GL20.GL_VERTEX_SHADER, "#version 120\nvoid main(){gl_TexCoord[0]=gl_MultiTexCoord0;gl_Position="
                        + (postProcess ? "gl_Vertex" : "gl_ModelViewProjectionMatrix*gl_Vertex") + ";}");
                pixel = compile(GL20.GL_FRAGMENT_SHADER, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                linked = GL20.glCreateProgram();
                GL20.glAttachShader(linked, vertex);
                GL20.glAttachShader(linked, pixel);
                GL20.glLinkProgram(linked);
                if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) throw new IOException(GL20.glGetProgramInfoLog(linked, 4096));
                id = linked;
            } catch (IOException | RuntimeException e) {
                if (linked != 0) GL20.glDeleteProgram(linked);
                throw e;
            } finally {
                if (vertex != 0) GL20.glDeleteShader(vertex);
                if (pixel != 0) GL20.glDeleteShader(pixel);
            }
        }

        private static int compile(int type, String source) throws IOException {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader, 4096);
                GL20.glDeleteShader(shader);
                throw new IOException(log);
            }
            return shader;
        }

        private int location(String name) { return uniforms.computeIfAbsent(name, key -> GL20.glGetUniformLocation(id, key)); }
        void i(String name, int value) { GL20.glUniform1i(location(name), value); }
        void f(String name, float value) { GL20.glUniform1f(location(name), value); }
        void f2(String name, float x, float y) { GL20.glUniform2f(location(name), x, y); }
        void f4(String name, float r, float g, float b, float a) { GL20.glUniform4f(location(name), r, g, b, a); }
    }
}
