package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.desktop.DesktopNativeLoader;
import tritium.minecraft.protocol.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real GL integration, including the offscreen glow chain; does not claim live Minecraft acceptance. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class WorldLyricsRenderingTest {
    @Test void drawsWorldGlyphsAndGlowAndRestoresGameState() throws Exception {
        DesktopNativeLoader.configureLibraryPath(); Display.setDisplayMode(new DisplayMode(960, 540));
        Display.setLocation(-10000, -10000); Display.create();
        try (URLClassLoader loader = loader()) {
            Class<?> rendererType = loader.loadClass("tritium.minecraft.runtime.lyric.WorldLyricsRenderer");
            Constructor<?> ctor = rendererType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            Object renderer = ctor.newInstance(new Object[]{null});
            Class<?> cameraType = loader.loadClass("tritium.minecraft.runtime.lyric.WorldLyricScene$Camera");
            Object camera = cameraType.getConstructor().newInstance();
            cameraType.getField("eyeY").setDouble(camera, 1.62);
            cameraType.getField("aspect").setDouble(camera, 960.0 / 540);
            Class<?> metrics = loader.loadClass("tritium.minecraft.runtime.lyric.WorldLyricScene$Metrics");
            Field fontField = rendererType.getDeclaredField("font"); fontField.setAccessible(true); Object font = fontField.get(renderer);
            Method width = font.getClass().getDeclaredMethod("width", String.class); width.setAccessible(true);
            Object measure = Proxy.newProxyInstance(loader, new Class[]{metrics}, (p, m, args) -> width.invoke(font, args));
            Class<?> sceneType = loader.loadClass("tritium.minecraft.runtime.lyric.WorldLyricScene");
            Object scene = sceneType.getConstructor(metrics).newInstance(measure);
            HudConfig.Module module = new HudConfig().find("worldLyrics"); module.enabled = true;
            module.world.wiggle = false; module.world.spread = 0; module.world.glow = false;
            PlaybackState.Track track = new PlaybackState.Track(); track.id = 42;
            PlaybackState.Line line = new PlaybackState.Line(); line.text = "世界中的歌词  Tritium Music"; line.timestamp = 0;
            track.lyrics.add(line);
            Method update = sceneType.getMethod("update", PlaybackState.Track.class, double.class, boolean.class, HudConfig.Module.class, cameraType, long.class);
            Object frame = update.invoke(scene, track, 2000.0, true, module, camera, 2_000_000_000L);
            Class<?> frameType = loader.loadClass("tritium.minecraft.runtime.lyric.WorldLyricScene$Frame");
            Method draw = rendererType.getDeclaredMethod("draw", frameType, cameraType, HudConfig.Module.class, int.class, int.class);
            draw.setAccessible(true);
            int target = framebuffer(), readTarget = framebuffer();
            int targetTexture = attachColor(target), readTexture = attachColor(readTarget);
            int depth = GL30.glGenRenderbuffers(); GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, 960, 540);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            GL11.glViewport(0, 0, 960, 540);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
            double top = .1 * Math.tan(Math.toRadians(35)); GL11.glFrustum(-top * 960 / 540, top * 960 / 540, -top, top, .1, 100);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity(); GL11.glRotated(180, 0, 1, 0); GL11.glTranslated(0, -1.62, 0);
            // Fill depth with a near occluder. World lyrics deliberately remain visible through it.
            GL11.glDepthMask(true); GL11.glClearDepth(0); GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            int gameTexture = GL11.glGenTextures(), buffer = GL15.glGenBuffers();
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, gameTexture); GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glLoadIdentity(); GL11.glTranslated(.2, .3, 0);
            float[] lightmap = matrix(GL11.GL_TEXTURE_MATRIX);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0, 0, 1, 1);
            GL11.glEnable(GL11.GL_STENCIL_TEST); GL11.glEnable(GL11.GL_FOG); GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE); GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8); GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 7);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer); GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 128, GL15.GL_STREAM_DRAW);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            float[] projection = matrix(GL11.GL_PROJECTION_MATRIX), modelview = matrix(GL11.GL_MODELVIEW_MATRIX);
            int gameProgram = program(); GL20.glUseProgram(gameProgram);
            draw.invoke(renderer, frame, camera, module, 960, 540);
            verifyState(target, readTarget, gameTexture, buffer, gameProgram, projection, modelview, lightmap);
            BufferedImage plain = capture(target, readTarget);
            long plainEnergy = energy(plain); int plainPixels = pixels(plain);
            assertTrue(plainPixels > 200, "World-space glyphs must produce visible pixels");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); GL11.glEnable(GL11.GL_SCISSOR_TEST);
            module.world.glow = true; module.world.glowStrength = 1;
            draw.invoke(renderer, frame, camera, module, 960, 540);
            verifyState(target, readTarget, gameTexture, buffer, gameProgram, projection, modelview, lightmap);
            BufferedImage glowing = capture(target, readTarget);
            Method error = rendererType.getDeclaredMethod("glowError"); error.setAccessible(true);
            assertEquals("", error.invoke(renderer), "The shader/FBO pipeline must execute, not silently fall back");
            assertTrue(energy(glowing) > plainEnergy * 1.1, "Glow must add visible energy");
            assertTrue(pixels(glowing) > plainPixels * 1.3, "Glow must spread outside the crisp glyph footprint");
            Path folder = Paths.get("target/ui-preview"); Files.createDirectories(folder);
            ImageIO.write(plain, "png", folder.resolve("minecraft-world-lyrics-plain.png").toFile());
            ImageIO.write(glowing, "png", folder.resolve("minecraft-world-lyrics-glow.png").toFile());
            draw.invoke(renderer, frame, camera, module, 640, 360);
            verifyState(target, readTarget, gameTexture, buffer, gameProgram, projection, modelview, lightmap);
            Method close = rendererType.getDeclaredMethod("close"); close.setAccessible(true); close.invoke(renderer);
            verifyState(target, readTarget, gameTexture, buffer, gameProgram, projection, modelview, lightmap);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            GL20.glUseProgram(0); GL20.glDeleteProgram(gameProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0); GL30.glDeleteFramebuffers(target); GL30.glDeleteFramebuffers(readTarget);
            GL30.glDeleteRenderbuffers(depth);
            GL11.glDeleteTextures(targetTexture); GL11.glDeleteTextures(readTexture); GL11.glDeleteTextures(gameTexture); GL15.glDeleteBuffers(buffer);
        } finally { Display.destroy(); }
    }

    private static void verifyState(int target, int read, int texture, int buffer, int program, float[] projection, float[] modelview, float[] lightmap) {
        assertEquals(target, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)); assertEquals(read, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        assertEquals(program, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        assertEquals(GL13.GL_TEXTURE1, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        assertEquals(texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)); assertTrue(GL11.glIsEnabled(GL11.GL_TEXTURE_2D));
        assertEquals(buffer, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
        assertEquals(8, GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)); assertEquals(7, GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH));
        assertEquals(GL11.GL_PROJECTION, GL11.glGetInteger(GL11.GL_MATRIX_MODE));
        assertArrayEquals(projection, matrix(GL11.GL_PROJECTION_MATRIX)); assertArrayEquals(modelview, matrix(GL11.GL_MODELVIEW_MATRIX));
        assertArrayEquals(lightmap, matrix(GL11.GL_TEXTURE_MATRIX));
        assertTrue(GL11.glIsEnabled(GL11.GL_DEPTH_TEST)); assertTrue(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)); assertTrue(GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
        assertTrue(GL11.glIsEnabled(GL11.GL_FOG)); assertTrue(GL11.glIsEnabled(GL11.GL_LIGHTING));
        assertEquals(GL14.GL_FUNC_REVERSE_SUBTRACT, GL11.glGetInteger(GL14.GL_BLEND_EQUATION));
        assertEquals(GL11.GL_SRC_COLOR, GL11.glGetInteger(GL11.GL_BLEND_SRC));
    }
    private static URLClassLoader loader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{Paths.get("minecraft-agent/target/minecraft-hud-agent.jar").toUri().toURL()}, WorldLyricsRenderingTest.class.getClassLoader());
    }
    private static int framebuffer() { return GL30.glGenFramebuffers(); }
    private static int attachColor(int fbo) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo); int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 960, 540, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)); return texture;
    }
    private static BufferedImage capture(int target, int restoreRead) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
        ByteBuffer data = BufferUtils.createByteBuffer(960 * 540 * 4); GL11.glReadPixels(0, 0, 960, 540, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        BufferedImage image = new BufferedImage(960, 540, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 540; y++) for (int x = 0; x < 960; x++) {
            int r = data.get() & 255, g = data.get() & 255, b = data.get() & 255; data.get();
            image.setRGB(x, 539 - y, 0xff000000 | r << 16 | g << 8 | b);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, restoreRead); return image;
    }
    private static long energy(BufferedImage image) {
        long result = 0; for (int pixel : image.getRGB(0, 0, 960, 540, null, 0, 960)) result += (pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255); return result;
    }
    private static int pixels(BufferedImage image) {
        int result = 0; for (int pixel : image.getRGB(0, 0, 960, 540, null, 0, 960)) if ((pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255) > 12) result++; return result;
    }
    private static float[] matrix(int name) { FloatBuffer b = BufferUtils.createFloatBuffer(16); GL11.glGetFloat(name, b); float[] result = new float[16]; b.get(result); return result; }
    private static int program() {
        int vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER), fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(vertex, "#version 120\nvoid main(){gl_Position=ftransform();}");
        GL20.glShaderSource(fragment, "#version 120\nvoid main(){gl_FragColor=vec4(1.0,0.0,0.0,1.0);}");
        GL20.glCompileShader(vertex); GL20.glCompileShader(fragment);
        int program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment); GL20.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS)); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment); return program;
    }
}
