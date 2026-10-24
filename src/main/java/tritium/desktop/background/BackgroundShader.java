package tritium.desktop.background;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.rendering.shader.ShaderProgram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** GLSL 1.20 keeps the built-in themes compatible with the app's desktop OpenGL context. */
public final class BackgroundShader implements AutoCloseable {
    private final int program;
    private final int resolutionUniform;
    private final int timeUniform;

    public BackgroundShader(String theme) throws IOException {
        this("#version 120\nvoid main(){gl_Position=ftransform();}", resource(switch (theme) {
            case "aurora" -> "01_aurora.frag";
            case "nebula" -> "02_nebula.frag";
            case "liquid" -> "03_liquid.frag";
            case "mandala" -> "04_mandala.frag";
            case "torus" -> "05_torus.frag";
            case "waves" -> "waves.frag";
            case "rise" -> "nebula.frag";
            case "latent" -> "latent.frag";
            default -> throw new IllegalStateException("Unexpected value: " + theme);
        }));
    }

    public static String resource(String file) throws IOException {
        try (var input = BackgroundShader.class.getResourceAsStream("/tritium/shaders/background/" + file)) {
            if (input == null) throw new IOException("Missing background shader: " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public BackgroundShader(String vertexSource, String fragment) throws IOException {
        int vertex = 0, pixel = 0, linked = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
            pixel = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex);
            GL20.glAttachShader(linked, pixel);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IOException(GL20.glGetProgramInfoLog(linked, 4096));
            }
            program = linked;
            resolutionUniform = GL20.glGetUniformLocation(program, "r");
            timeUniform = GL20.glGetUniformLocation(program, "t");
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
        // LWJGL 2 encodes CharSequence shader input as single-byte text. Unicode comments
        // can contain a zero low byte and truncate a perfectly valid shader on this API.
        String glsl = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        GL20.glShaderSource(shader, glsl);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IOException((type == GL20.GL_VERTEX_SHADER ? "Vertex: " : "Fragment: ") + log);
        }
        return shader;
    }

    void draw(double width, double height, int framebufferWidth, int framebufferHeight, float time) {
        draw(width, height, framebufferWidth, framebufferHeight, time, () -> { });
    }

    public int uniform(String name) { return GL20.glGetUniformLocation(program, name); }

    /** Binds the same GLSL transport for native geometry such as lyric glyph atlases. */
    public void withProgram(Runnable draw) {
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        try { draw.run(); } finally { GL20.glUseProgram(previous); }
    }

    public void draw(double width, double height, int framebufferWidth, int framebufferHeight, float time, Runnable uniforms) {
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        try {
            GL20.glUniform2f(resolutionUniform, framebufferWidth, framebufferHeight);
            GL20.glUniform1f(timeUniform, time);
            uniforms.run();
            ShaderProgram.drawQuad(0, 0, width, height);
        } finally {
            GL20.glUseProgram(previous);
        }
    }

    @Override
    public void close() { GL20.glDeleteProgram(program); }
}
