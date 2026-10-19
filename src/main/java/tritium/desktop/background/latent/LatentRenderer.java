package tritium.desktop.background.latent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import tritium.desktop.AppTheme;
import tritium.desktop.BackgroundPreferences;
import tritium.desktop.background.BackgroundShader;
import tritium.desktop.hud.HudArtworkCache;
import tritium.ncm.music.AudioEnergy;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.ShaderProgram;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Main-window Latent compositor. GL resources stay on the render thread; cover quantization does not. */
public final class LatentRenderer implements AutoCloseable {
    private static final int MAX_PIXELS = 1280 * 720;
    private final BackgroundPreferences background;
    private final ExecutorService worker;
    private final BackgroundShader shader;
    private final LatentMotion motion = new LatentMotion();
    private final FloatBuffer paletteBuffer = BufferUtils.createFloatBuffer(27);
    private final float[] palette = new float[27];
    private final int pixelRatioUniform, meshTimeUniform, ditherTimeUniform, paletteUniform, audioUniform, strengthUniform, layersUniform;
    private BufferedImage requestedCover;
    private long songId = Long.MIN_VALUE;
    private Future<int[]> paletteTask;
    private int[] coverColors = new int[0];
    private boolean initialized;
    private int framebuffer, texture, targetWidth, targetHeight;

    public LatentRenderer(BackgroundPreferences background, ExecutorService worker) throws IOException {
        this.background = background;
        this.worker = worker;
        shader = new BackgroundShader("latent");
        pixelRatioUniform = shader.uniform("pixelRatio");
        meshTimeUniform = shader.uniform("meshTime");
        ditherTimeUniform = shader.uniform("ditherTime");
        paletteUniform = shader.uniform("colors[0]");
        audioUniform = shader.uniform("audio");
        strengthUniform = shader.uniform("strength");
        layersUniform = shader.uniform("layers");
    }

    public void render(double width, double height, double seconds) {
        updateCover();
        AudioPlayer player = CloudMusic.player;
        boolean paused = player == null || CloudMusic.currentlyPlaying == null || player.isFinished() || player.isPausing();
        AudioEnergy signal = player == null ? AudioEnergy.SILENT : player.getEnergy();
        // A stalled decoder must not keep the last loud FFT alive indefinitely.
        if (System.nanoTime() - signal.sampledAt() > 250_000_000L) signal = AudioEnergy.SILENT;
        motion.update(signal, paused, background.latent().beat().getValue(), seconds, background.latent().speed().getValue());
        updatePalette(seconds);
        int previousFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            resizeTarget();
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
            GL11.glViewport(0, 0, targetWidth, targetHeight);
            shader.draw(width, height, targetWidth, targetHeight, 0, () -> {
                GL20.glUniform1f(pixelRatioUniform, (float) (targetWidth / width));
                GL20.glUniform1f(meshTimeUniform, motion.meshTime());
                GL20.glUniform1f(ditherTimeUniform, motion.ditheringTime());
                GL20.glUniform3(paletteUniform, paletteBuffer);
                GL20.glUniform3f(audioUniform, motion.power(), motion.bass(), motion.mid());
                GL20.glUniform1f(strengthUniform, background.strength().getValue().floatValue());
                GL20.glUniform1i(layersUniform, switch (background.latent().layers().getValue()) {
                    case "mesh" -> 1;
                    case "dithering" -> 2;
                    default -> 0;
                });
            });
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glPopAttrib();
        }
        try {
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glColor4f(1, 1, 1, 1);
            ShaderProgram.drawQuadFlipped(0, 0, width, height);
        } finally { GL20.glUseProgram(previousProgram); }
    }

    private void updateCover() {
        var song = CloudMusic.currentlyPlaying;
        long id = song == null ? -1 : song.getId();
        BufferedImage image = background.latent().colorSource().getValue().equals("theme") ? null
                : HudArtworkCache.get(id, HudArtworkCache.ArtworkType.COVER);
        if (image == null && !background.latent().colorSource().getValue().equals("theme")) {
            image = HudArtworkCache.get(id, HudArtworkCache.ArtworkType.SMALL);
        }
        if (id != songId || image != requestedCover) {
            if (paletteTask != null) paletteTask.cancel(true);
            paletteTask = null;
            requestedCover = image;
            songId = id;
            coverColors = new int[0];
            if (image != null) {
                BufferedImage cover = image;
                paletteTask = worker.submit(() -> LatentPalette.extract(cover));
            }
        }
        if (paletteTask != null && paletteTask.isDone()) {
            try { coverColors = paletteTask.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { System.err.println("[Latent] Cover palette unavailable: " + e); }
            paletteTask = null;
        }
    }

    private void updatePalette(double seconds) {
        int[] colors = LatentPalette.resolve(coverColors, themeColor(LatentPreferences.Color.BACKGROUND),
                themeColor(LatentPreferences.Color.PRIMARY), themeColor(LatentPreferences.Color.ACCENT),
                themeColor(LatentPreferences.Color.SECONDARY), background.latent().colorSource().getValue());
        float blend = initialized ? (float) (1 - Math.exp(-seconds / .3)) : 1;
        for (int i = 0; i < colors.length; i++) for (int c = 0; c < 3; c++) {
            float target = ((colors[i] >> (16 - c * 8)) & 255) / 255f;
            palette[i * 3 + c] += (target - palette[i * 3 + c]) * blend;
        }
        initialized = true;
        paletteBuffer.clear();
        paletteBuffer.put(palette).flip();
    }

    private int themeColor(LatentPreferences.Color color) {
        return AppTheme.color(background.latent().color(false, color).getValue(), background.latent().color(true, color).getValue());
    }

    private void resizeTarget() {
        int width = Math.max(1, RenderSystem.getFramebufferWidth()), height = Math.max(1, RenderSystem.getFramebufferHeight());
        double scale = Math.min(1, Math.sqrt(MAX_PIXELS / ((double) width * height)));
        int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
        if (w == targetWidth && h == targetHeight) return;
        if (texture == 0) texture = GL11.glGenTextures();
        if (framebuffer == 0) framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, texture, 0);
        if (EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) throw new IllegalStateException("Latent framebuffer incomplete");
        targetWidth = w;
        targetHeight = h;
    }

    @Override public void close() {
        if (paletteTask != null) paletteTask.cancel(true);
        shader.close();
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (framebuffer != 0) EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
    }
}
