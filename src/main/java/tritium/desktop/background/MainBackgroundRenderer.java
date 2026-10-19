package tritium.desktop.background;

import tritium.i18n.I18n;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import tritium.desktop.AppTheme;
import tritium.desktop.BackgroundPreferences;
import tritium.desktop.background.latent.LatentRenderer;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Owned by the main window. All GL allocation, drawing and destruction stays on its render thread. */
public final class MainBackgroundRenderer implements AutoCloseable {
    private final BackgroundPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "background-media");
        thread.setDaemon(true);
        return thread;
    });
    private BackgroundMedia.Source source;
    private BackgroundMedia media;
    private Future<?> decodeTask;
    private BackgroundShader shader;
    private LatentRenderer latent;
    private String shaderTheme;
    private String shaderError;
    private int texture;
    private int textureWidth, textureHeight;
    private ByteBuffer uploadBuffer;
    private boolean active;
    private boolean closed;
    private long lastFrame;
    private double shaderTime;

    public MainBackgroundRenderer(BackgroundPreferences preferences) { this.preferences = preferences; }

    public boolean isActive() { return active; }

    /** Keep the original cover background until the selected custom background has a usable frame. */
    public boolean isAppliedToLyrics() { return active && preferences.appliesToLyrics(); }

    public String status() {
        if (preferences.mode().getValue().equals("static")) return I18n.text("background.staticHint");
        if (shaderError != null) return I18n.text(shaderError);
        if (media != null) return media.status();
        if (preferences.mode().getValue().equals("latent")) return I18n.text("background.latentHint");
        return preferences.mode().getValue().equals("shader") ? I18n.text("background.shaderHint") : I18n.text("background.previewHint");
    }

    public void render(boolean visible) {
        if (closed) return;
        double width = RenderSystem.getWidth(), height = RenderSystem.getHeight();
        Rect.draw(0, 0, width, height, AppTheme.background());
        synchronizeSource();
        if (media != null) {
            media.paused = !visible;
            media.interval = preferences.interval().getValue();
        }
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : Math.min(.1, (now - lastFrame) / 1e9);
        if (visible) shaderTime += seconds * preferences.shaderSpeed().getValue();
        lastFrame = now;
        active = false;
        if (!visible || preferences.mode().getValue().equals("static")) return;

        // Preserve fixed-function state used by fonts, clipping and other screens.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            if (preferences.mode().getValue().equals("latent")) {
                if (latent != null) {
                    try {
                        latent.render(width, height, seconds);
                        active = true;
                    } catch (RuntimeException e) {
                        latent.close();
                        latent = null;
                        shaderError = "background.shaderFailed";
                        System.err.println("[Latent] Rendering failed: " + e);
                    }
                }
            } else if (preferences.mode().getValue().equals("shader")) {
                if (shader != null) {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    shader.draw(width, height, RenderSystem.getFramebufferWidth(), RenderSystem.getFramebufferHeight(), (float) shaderTime);
                    active = true;
                }
            } else if (media != null && !media.failed) {
                BackgroundMedia.Pixels frame = media.pending.getAndSet(null);
                if (frame != null) upload(frame);
                if (texture != 0) {
                    drawTexture(width, height);
                    active = true;
                }
            }
            if (active && latent == null) Rect.draw(0, 0, width, height,
                    RenderSystem.reAlpha(AppTheme.background(), 1 - preferences.strength().getValue().floatValue()));
        } finally {
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private void synchronizeSource() {
        String mode = preferences.mode().getValue();
        boolean folder = mode.equals("picture") && preferences.pictureSource().getValue().equals("folder");
        String path = switch (mode) {
            case "picture" -> folder ? preferences.pictureFolder().getValue() : preferences.pictureFile().getValue();
            case "video" -> preferences.videoFile().getValue();
            default -> "";
        };
        BackgroundMedia.Source next = new BackgroundMedia.Source(mode, path, folder, folder && preferences.shuffle().getValue());
        if (!Objects.equals(next, source)) {
            releaseMedia();
            source = next;
            if (mode.equals("picture") || mode.equals("video")) {
                media = new BackgroundMedia(next);
                media.interval = preferences.interval().getValue();
                decodeTask = decoder.submit(media);
            }
        }
        String theme = mode.equals("latent") ? "latent" : mode.equals("shader") ? preferences.shader().getValue() : null;
        if (!Objects.equals(theme, shaderTheme)) {
            if (shader != null) shader.close();
            if (latent != null) latent.close();
            latent = null;
            shader = null;
            shaderError = null;
            shaderTheme = theme;
            shaderTime = 0;
            if (theme != null) {
                try {
                    if (theme.equals("latent")) latent = new LatentRenderer(preferences, decoder);
                    else shader = new BackgroundShader(theme);
                } catch (Exception e) {
                    shaderError = "background.shaderFailed";
                    System.err.println("Background shader: " + e);
                }
            }
        }
    }

    private void upload(BackgroundMedia.Pixels frame) {
        if (uploadBuffer == null || uploadBuffer.capacity() < frame.rgba().length) {
            uploadBuffer = BufferUtils.createByteBuffer(frame.rgba().length);
        }
        uploadBuffer.clear();
        uploadBuffer.put(frame.rgba()).flip();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (texture == 0) texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (textureWidth != frame.width() || textureHeight != frame.height()) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frame.width(), frame.height(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuffer);
            textureWidth = frame.width();
            textureHeight = frame.height();
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, textureWidth, textureHeight,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuffer);
        }
    }

    static record Bounds(double x, double y, double width, double height) { }

    static Bounds fit(double width, double height, int imageWidth, int imageHeight, String fit) {
        if (fit.equals("stretch")) return new Bounds(0, 0, width, height);
        double scale = fit.equals("contain") ? Math.min(width / imageWidth, height / imageHeight)
                : Math.max(width / imageWidth, height / imageHeight);
        double w = imageWidth * scale, h = imageHeight * scale;
        return new Bounds((width - w) / 2, (height - h) / 2, w, h);
    }

    private void drawTexture(double width, double height) {
        Bounds box = fit(width, height, textureWidth, textureHeight, preferences.fit().getValue());
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2d(box.x(), box.y());
        GL11.glTexCoord2f(0, 1); GL11.glVertex2d(box.x(), box.y() + box.height());
        GL11.glTexCoord2f(1, 1); GL11.glVertex2d(box.x() + box.width(), box.y() + box.height());
        GL11.glTexCoord2f(1, 0); GL11.glVertex2d(box.x() + box.width(), box.y());
        GL11.glEnd();
    }

    public void pause() {
        if (media != null) media.paused = true;
        lastFrame = 0;
    }

    /** Explicit retry also rescans a folder changed while the application was open. */
    public void reload() {
        releaseMedia();
        source = null;
        if (shader != null) shader.close();
        if (latent != null) latent.close();
        latent = null;
        shader = null;
        shaderTheme = null;
        shaderError = null;
    }

    private void releaseMedia() {
        if (media != null) media.cancel();
        if (decodeTask != null) decodeTask.cancel(true);
        media = null;
        decodeTask = null;
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = textureWidth = textureHeight = 0;
        uploadBuffer = null;
        active = false;
    }

    @Override
    public void close() {
        if (closed) return;
        reload();
        decoder.shutdownNow();
        closed = true;
    }
}
