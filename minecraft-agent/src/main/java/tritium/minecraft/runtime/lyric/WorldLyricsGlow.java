package tritium.minecraft.runtime.lyric;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Seven-scale Gale glow: downsample, additive upsample, exponential exposure composite. */
final class WorldLyricsGlow implements AutoCloseable {
    private static final int LEVELS = 7;
    private static final String VERTEX = "#version 120\nvoid main(){gl_TexCoord[0]=gl_MultiTexCoord0;gl_Position=gl_Vertex;}";
    private static final String DOWN = "#version 120\nuniform sampler2D tex;uniform vec2 texel;\n"
            + "void main(){vec2 uv=gl_TexCoord[0].xy;vec4 c=texture2D(tex,uv)*4.0;"
            + "c+=texture2D(tex,uv-texel);c+=texture2D(tex,uv+texel);"
            + "c+=texture2D(tex,uv+vec2(texel.x,-texel.y));c+=texture2D(tex,uv+vec2(-texel.x,texel.y));gl_FragColor=c/8.0;}";
    private static final String UP = "#version 120\nuniform sampler2D tex;uniform vec2 texel;uniform float intensity;\n"
            + "void main(){vec2 uv=gl_TexCoord[0].xy;vec4 s=texture2D(tex,uv+vec2(-2.0*texel.x,0.0));"
            + "s+=texture2D(tex,uv+vec2(2.0*texel.x,0.0));s+=texture2D(tex,uv+vec2(0.0,-2.0*texel.y));"
            + "s+=texture2D(tex,uv+vec2(0.0,2.0*texel.y));s+=texture2D(tex,uv+vec2(-texel.x,texel.y))*2.0;"
            + "s+=texture2D(tex,uv+vec2(texel.x,texel.y))*2.0;s+=texture2D(tex,uv+vec2(texel.x,-texel.y))*2.0;"
            + "s+=texture2D(tex,uv+vec2(-texel.x,-texel.y))*2.0;gl_FragColor=s/12.0*intensity;}";
    private static final String COMPOSITE = "#version 120\nuniform sampler2D tex;uniform vec3 tint;uniform float exposure;\n"
            + "float rand(vec2 co){return fract(sin(dot(co,vec2(12.9898,78.233)))*43758.5453);}\n"
            + "void main(){vec3 energy=texture2D(tex,gl_TexCoord[0].xy).rgb*tint*exposure;"
            + "vec3 c=vec3(1.0)-exp(-energy);c+=(rand(gl_FragCoord.xy)-0.5)/255.0;"
            + "gl_FragColor=vec4(max(c,vec3(0.0)),1.0);}";
    private int down, up, composite, width, height, maskTexture, maskFramebuffer;
    private final int[] textures = new int[LEVELS], framebuffers = new int[LEVELS], widths = new int[LEVELS], heights = new int[LEVELS];
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private int savedDraw, savedRead;
    private boolean active, failed, arb, ext;
    private String error = "";

    boolean available() { return !failed; }
    String error() { return error; }
    private boolean setup() {
        if (failed) return false;
        if (composite != 0) return true;
        ContextCapabilities caps = GLContext.getCapabilities();
        arb = !caps.OpenGL30 && caps.GL_ARB_framebuffer_object;
        ext = !caps.OpenGL30 && !arb && caps.GL_EXT_framebuffer_object;
        if (!caps.OpenGL20 || !(caps.OpenGL30 || arb || ext)) {
            failed = true; error = "World lyric glow requires OpenGL 2.0 and framebuffer objects"; return false;
        }
        try { down = program(DOWN); up = program(UP); composite = program(COMPOSITE); return true; }
        catch (RuntimeException e) { disable(e); return false; }
    }
    private int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096); GL20.glDeleteShader(shader);
            throw new IllegalStateException("World lyric shader: " + log);
        }
        return shader;
    }
    private int program(String fragment) {
        int vertex = 0, frag = 0, result = 0;
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX); frag = shader(GL20.GL_FRAGMENT_SHADER, fragment);
            result = GL20.glCreateProgram(); GL20.glAttachShader(result, vertex); GL20.glAttachShader(result, frag);
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("World lyric shader link: " + GL20.glGetProgramInfoLog(result, 4096));
            return result;
        } catch (RuntimeException e) { if (result != 0) GL20.glDeleteProgram(result); throw e; }
        finally { if (vertex != 0) GL20.glDeleteShader(vertex); if (frag != 0) GL20.glDeleteShader(frag); }
    }
    private int newFramebuffer() {
        return ext ? EXTFramebufferObject.glGenFramebuffersEXT() : arb ? ARBFramebufferObject.glGenFramebuffers() : GL30.glGenFramebuffers();
    }
    private void bind(int framebuffer) {
        if (ext) EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        else if (arb) ARBFramebufferObject.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        else GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    }
    private void deleteFramebuffer(int framebuffer) {
        if (framebuffer == 0) return;
        if (ext) EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
        else if (arb) ARBFramebufferObject.glDeleteFramebuffers(framebuffer);
        else GL30.glDeleteFramebuffers(framebuffer);
    }
    private void attach(int texture) {
        if (ext) EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, texture, 0);
        else if (arb) ARBFramebufferObject.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        else GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
    }
    private boolean complete() {
        int status = ext ? EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                : arb ? ARBFramebufferObject.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) : GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        return status == GL30.GL_FRAMEBUFFER_COMPLETE;
    }
    private int texture(int w, int h, boolean hdr) {
        int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        allocate(w, h, hdr);
        return texture;
    }
    private void allocate(int w, int h, boolean hdr) {
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, hdr ? GL30.GL_RGBA16F : GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
    }
    private void targets(int w, int h) {
        if (maskFramebuffer != 0 && width == w && height == h) return;
        releaseTargets(); width = w; height = h;
        maskTexture = texture(w, h, false); maskFramebuffer = newFramebuffer(); bind(maskFramebuffer); attach(maskTexture);
        if (!complete()) throw new IllegalStateException("Incomplete world lyric mask framebuffer");
        ContextCapabilities caps = GLContext.getCapabilities();
        boolean hdr = caps.OpenGL30 || caps.GL_ARB_texture_float;
        for (int i = 0; i < LEVELS; i++) {
            w = Math.max(1, w / 2); h = Math.max(1, h / 2); widths[i] = w; heights[i] = h;
            textures[i] = texture(w, h, hdr); framebuffers[i] = newFramebuffer(); bind(framebuffers[i]); attach(textures[i]);
            if (!complete() && hdr) { allocate(w, h, false); }
            if (!complete()) throw new IllegalStateException("Incomplete world lyric blur framebuffer " + i);
        }
    }

    boolean begin(int w, int h) {
        if (!setup() || w <= 0 || h <= 0) return false;
        savedDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        savedRead = ext ? savedDraw : GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        viewport.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        active = true; // Allocation can fail; abort must still restore the game's framebuffer.
        try {
            targets(w, h); bind(maskFramebuffer); GL11.glViewport(0, 0, w, h);
            GL11.glClearColor(0, 0, 0, 0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            return true;
        } catch (RuntimeException e) { disable(e); return false; }
    }

    void end(String tint, double strength, double radius) {
        if (!active) return;
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        try {
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glColor4f(1, 1, 1, 1);
            GL20.glUseProgram(down); uniform(down, "tex", 0);
            int src = maskTexture, sw = width, sh = height;
            for (int i = 0; i < LEVELS; i++) {
                bind(framebuffers[i]); GL11.glViewport(0, 0, widths[i], heights[i]); GL11.glBindTexture(GL11.GL_TEXTURE_2D, src);
                GL20.glUniform2f(GL20.glGetUniformLocation(down, "texel"), 1f / sw, 1f / sh); quad();
                src = textures[i]; sw = widths[i]; sh = heights[i];
            }
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL20.glUseProgram(up); uniform(up, "tex", 0);
            GL20.glUniform1f(GL20.glGetUniformLocation(up, "intensity"), (float)(.55 + Math.max(0, Math.min(1, radius)) * .6));
            for (int i = LEVELS - 2; i >= 0; i--) {
                bind(framebuffers[i]); GL11.glViewport(0, 0, widths[i], heights[i]); GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i + 1]);
                GL20.glUniform2f(GL20.glGetUniformLocation(up, "texel"), 1f / widths[i + 1], 1f / heights[i + 1]); quad();
            }
            restoreTarget(); GL20.glUseProgram(composite); uniform(composite, "tex", 0);
            float r = 1, g = 1, b = 1;
            if ("Pink".equals(tint)) { g = .38f; b = .62f; }
            else if ("Blue".equals(tint)) { r = .32f; g = .66f; }
            else if ("Gold".equals(tint)) { g = .74f; b = .3f; }
            GL20.glUniform3f(GL20.glGetUniformLocation(composite, "tint"), r, g, b);
            GL20.glUniform1f(GL20.glGetUniformLocation(composite, "exposure"), (float)strength * 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[0]); quad();
        } finally {
            GL20.glUseProgram(0); abort();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            WorldGlState.textBlend();
        }
    }
    private static void uniform(int program, String name, int value) { GL20.glUniform1i(GL20.glGetUniformLocation(program, name), value); }
    private void restoreTarget() {
        if (ext) bind(savedDraw);
        else if (arb) {
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
        } else {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead);
        }
        GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
    }
    void abort() { if (active) { restoreTarget(); active = false; } }
    void disable(Throwable failure) {
        failed = true; error = String.valueOf(failure.getMessage()); abort(); close();
    }
    private void releaseTargets() {
        if (maskTexture != 0) GL11.glDeleteTextures(maskTexture); deleteFramebuffer(maskFramebuffer);
        maskTexture = maskFramebuffer = 0;
        for (int i = 0; i < LEVELS; i++) {
            if (textures[i] != 0) GL11.glDeleteTextures(textures[i]); deleteFramebuffer(framebuffers[i]);
            textures[i] = framebuffers[i] = 0;
        }
    }
    @Override public void close() {
        abort(); releaseTargets();
        if (down != 0) GL20.glDeleteProgram(down); if (up != 0) GL20.glDeleteProgram(up);
        if (composite != 0) GL20.glDeleteProgram(composite);
        down = up = composite = 0;
    }
    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }
}
