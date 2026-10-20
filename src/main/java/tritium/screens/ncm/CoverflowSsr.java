package tritium.screens.ncm;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.impl.VFFadeoutShader;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.EXTFramebufferObject.*;

/**
 * Artwork-only screen-space reflections on a horizontal plane. The plane normal is known, so
 * a normal G-buffer is unnecessary. A mirrored geometry pass supplies off-screen/occluded texels
 * that cannot be recovered from the camera's depth buffer. UI never enters either pass.
 */
final class CoverflowSsr implements AutoCloseable {
    private static final class Cover {
        int texture;
        double x, y, size;
        final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    }

    private final List<Cover> covers = new ArrayList<>();
    private int coverCount;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private float floorY, nearDepth, farDepth;
    private int width, height, framebuffer, scene, depth, planar, planarDepth, traced;
    private ShaderProgram trace, resolve;
    private boolean failed;
    private VFFadeoutShader legacy;

    /** Called with the Coverflow perspective projection active. floorY is in camera space. */
    void beginFrame(double floorY) {
        coverCount = 0;
        this.floorY = (float) floorY;
        nearDepth = Float.POSITIVE_INFINITY;
        farDepth = 0;
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
    }

    /** Record the current animated transform; replaying it must not advance animations or input. */
    void addCover(int texture, double x, double y, double size) {
        // Reuse direct buffers across frames; allocating one per animated cover causes GC stalls.
        if (coverCount == covers.size()) covers.add(new Cover());
        Cover cover = covers.get(coverCount++);
        cover.texture = texture;
        cover.x = x;
        cover.y = y;
        cover.size = size;
        FloatBuffer modelView = cover.modelView;
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        for (int i = 0; i < 4; i++) {
            float z = (float) -(modelView.get(2) * (x + (i % 2) * size)
                    + modelView.get(6) * (y + (i / 2) * size) + modelView.get(14));
            nearDepth = Math.min(nearDepth, z);
            farDepth = Math.max(farDepth, z);
        }
    }

    /** Called after returning to the UI projection, before titles/search are drawn. */
    void draw(float opacity) {
        if (coverCount == 0 || opacity <= 0) return;
        if (!GLContext.getCapabilities().GL_EXT_framebuffer_object) failed = true;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int previousFramebuffer = GLContext.getCapabilities().GL_EXT_framebuffer_object
                ? GL11.glGetInteger(GL_FRAMEBUFFER_BINDING_EXT) : 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (!failed) {
                try {
                    ensureResources();
                } catch (RuntimeException e) {
                    failed = true;
                    releaseTargets();
                    System.err.println("Coverflow SSR unavailable; using planar mirrors: " + e.getMessage());
                }
            }
            if (failed) {
                if (GLContext.getCapabilities().GL_EXT_framebuffer_object)
                    glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, previousFramebuffer);
                drawLegacy(opacity);
                return;
            }

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadMatrix(projection);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);

            glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, framebuffer);
            geometryPass(false);
            geometryPass(true);

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            attach(traced, 0);
            // Full-screen passes use clip-space coordinates and bottom-origin texture coordinates.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            trace.start();
            bind(0, scene);
            bind(1, depth);
            bind(2, planar);
            integer(trace, "scene", 0);
            integer(trace, "depthBuffer", 1);
            integer(trace, "planar", 2);
            cameraUniforms(trace);
            GL20.glUniform2f(uniform(trace, "depthRange"), Math.max(1, nearDepth - 2), farDepth + 2);
            ShaderProgram.drawQuad(-1, -1, 2, 2);

            glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glViewport(0, 0, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight());
            resolve.start();
            bind(0, traced);
            bind(1, depth);
            integer(resolve, "reflection", 0);
            integer(resolve, "depthBuffer", 1);
            cameraUniforms(resolve);
            GL20.glUniform2f(uniform(resolve, "pixelSize"), 1f / (float) RenderSystem.getWidth(),
                    1f / (float) RenderSystem.getHeight());
            GL20.glUniform1f(uniform(resolve, "opacity"), opacity);
            GL20.glUniform1f(uniform(resolve, "artworkDepth"), (nearDepth + farDepth) * .5f);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            ShaderProgram.drawQuad(-1, -1, 2, 2);
        } finally {
            if (GLContext.getCapabilities().GL_EXT_framebuffer_object)
                glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMatrixMode);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GL13.glActiveTexture(previousUnit);
        }
    }

    private void cameraUniforms(ShaderProgram program) {
        GL20.glUniform2f(uniform(program, "projectionScale"), projection.get(0), projection.get(5));
        // Derive near/far from the actual OpenGL projection instead of duplicating camera constants.
        float a = projection.get(10), b = projection.get(14);
        GL20.glUniform2f(uniform(program, "clipPlanes"), b / (a - 1), b / (a + 1));
        GL20.glUniform1f(uniform(program, "floorY"), floorY);
    }

    private void geometryPass(boolean mirrored) {
        attach(mirrored ? planar : scene, mirrored ? planarDepth : depth);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glClearDepth(1);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        for (int i = 0; i < coverCount; i++) {
            Cover cover = covers.get(i);
            GL11.glLoadIdentity();
            if (mirrored) {
                GL11.glTranslatef(0, 2 * floorY, 0);
                GL11.glScalef(1, -1, 1);
            }
            GL11.glMultMatrix(cover.modelView);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cover.texture);
            ShaderProgram.drawQuad(cover.x, cover.y, cover.size, cover.size);
        }
    }

    private void drawLegacy(float opacity) {
        if (legacy == null) legacy = new VFFadeoutShader();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadMatrix(projection);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        for (int i = 0; i < coverCount; i++) {
            Cover cover = covers.get(i);
            GL11.glLoadMatrix(cover.modelView);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cover.texture);
            legacy.draw(cover.x, cover.y + cover.size, cover.size, cover.size, .5, .85f * opacity);
        }
    }

    private void ensureResources() {
        if (trace == null) trace = program("coverflow_ssr.frag");
        if (resolve == null) resolve = program("coverflow_reflection_resolve.frag");
        int physicalWidth = RenderSystem.getFramebufferWidth(), physicalHeight = RenderSystem.getFramebufferHeight();
        double scale = Math.min(.5, Math.min(1440.0 / physicalWidth, 900.0 / physicalHeight));
        int w = Math.max(1, (int) Math.ceil(physicalWidth * scale));
        int h = Math.max(1, (int) Math.ceil(physicalHeight * scale));
        if (width == w && height == h && framebuffer != 0) return;
        releaseTargets();
        width = w;
        height = h;
        framebuffer = glGenFramebuffersEXT();
        scene = texture(false);
        depth = texture(true);
        planar = texture(false);
        planarDepth = texture(true);
        traced = texture(false);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, framebuffer);
        attach(scene, depth);
        if (glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT) != GL_FRAMEBUFFER_COMPLETE_EXT)
            throw new IllegalStateException("Incomplete reflection framebuffer");
    }

    private static ShaderProgram program(String fragment) {
        ShaderProgram program = new ShaderProgram(fragment, "vertex.vsh");
        if (GL20.glGetProgrami(program.getProgramId(), GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program.getProgramId(), 4096);
            GL20.glDeleteProgram(program.getProgramId());
            throw new IllegalStateException(log);
        }
        return program;
    }

    private int texture(boolean depthTexture) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int filter = depthTexture ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (depthTexture) GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depthTexture ? GL14.GL_DEPTH_COMPONENT24 : GL11.GL_RGBA8,
                width, height, 0, depthTexture ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA,
                depthTexture ? GL11.GL_UNSIGNED_INT : GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return id;
    }

    private void attach(int color, int depthTexture) {
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL11.glDrawBuffer(GL_COLOR_ATTACHMENT0_EXT);
    }

    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static int uniform(ShaderProgram shader, String name) {
        return GL20.glGetUniformLocation(shader.getProgramId(), name);
    }

    private static void integer(ShaderProgram shader, String name, int value) {
        GL20.glUniform1i(uniform(shader, name), value);
    }

    private void releaseTargets() {
        for (int texture : new int[]{scene, depth, planar, planarDepth, traced})
            if (texture != 0) GL11.glDeleteTextures(texture);
        if (framebuffer != 0) glDeleteFramebuffersEXT(framebuffer);
        scene = depth = planar = planarDepth = traced = framebuffer = width = height = 0;
    }

    @Override
    public void close() {
        releaseTargets();
        if (trace != null) GL20.glDeleteProgram(trace.getProgramId());
        if (resolve != null) GL20.glDeleteProgram(resolve.getProgramId());
        if (legacy != null) legacy.close();
        trace = resolve = null;
        legacy = null;
        covers.clear();
        coverCount = 0;
        failed = false;
    }
}
