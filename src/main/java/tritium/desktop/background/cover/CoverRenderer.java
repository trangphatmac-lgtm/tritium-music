package tritium.desktop.background.cover;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import tritium.desktop.BackgroundPreferences;
import tritium.desktop.DesktopAppState;
import tritium.desktop.background.BackgroundShader;
import tritium.desktop.background.latent.LatentPalette;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.TextureManager;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.ITextureObject;
import tritium.utils.Location;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Cover ownership, asynchronous image treatment, cached shaders and song/page crossfades. */
public final class CoverRenderer implements AutoCloseable {
    private final BackgroundPreferences preferences;
    private final ExecutorService worker;
    private final BackgroundShader compositor;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private ITextureObject artwork;
    private Location location;
    private BufferedImage snapshot;
    private Request request;
    private Future<Prepared> pending;
    private NomandRenderer nomand;
    private String nomandEffect;
    private int texture, previousTexture, fallbackTexture, imageWidth = 1, imageHeight = 1;
    private int previousWidth = 1, previousHeight = 1;
    private int tint, previousTint;
    private double transition = 1, time;

    private record Request(String mode, String effect, int background, int accent, int primary,
                           int secondary, boolean light, int width, int height, double logicalWidth) { }
    private record Prepared(BufferedImage image, int tint) { }

    public CoverRenderer(BackgroundPreferences preferences, ExecutorService worker) throws IOException {
        this.preferences = preferences;
        this.worker = worker;
        compositor = new BackgroundShader("#version 120\nvoid main(){gl_Position=ftransform();}",
                BackgroundShader.resource("cover/composite.frag"));
    }

    public void render(double width, double height, double seconds, Location source, boolean lyrics) throws IOException {
        int oldUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            if (fallbackTexture == 0) fallbackTexture = CoverArtwork.upload(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            String mode = preferences.coverMode().getValue();
            // Lyrics have always used a dark canvas and white controls, independently of the main theme.
            boolean light = !lyrics && DesktopAppState.preferences().appearance().getValue().equals("Light");
            int bg = light ? 0xFFFFFFFF : 0xFF1E1E1E, fg = light ? 0xFF000000 : 0xFFFFFFFF;
            int accent = DesktopAppState.preferences().accentColor().getValue() | 0xFF000000;
            int fw = Math.max(1, RenderSystem.getFramebufferWidth()), fh = Math.max(1, RenderSystem.getFramebufferHeight());
            double scale = Math.min(1, Math.sqrt(1920.0 * 1080 / ((double) fw * fh)));
            Request next = new Request(mode, preferences.nomandEffect().getValue(), bg, accent, fg,
                    light ? 0xFF6B6B6B : 0xFFAAAAAA, light,
                    mode.equals("nomand") ? Math.max(1, (int) (fw * scale)) : 0,
                    mode.equals("nomand") ? Math.max(1, (int) (fh * scale)) : 0,
                    mode.equals("nomand") ? width : 0);
            ITextureObject nextArtwork = source == null ? null : TextureManager.getInstance().getTexture(source);
            boolean changedArtwork = !Objects.equals(location, source) || artwork != nextArtwork;
            if (changedArtwork) {
                location = source;
                artwork = nextArtwork;
                snapshot = artwork == null ? null : CoverArtwork.read(artwork);
            }
            if (changedArtwork || !next.equals(request)) {
                if (pending != null) pending.cancel(true);
                pending = null;
                if (request == null || !request.mode.equals(mode) || snapshot == null) {
                    clearFrames();
                    previousTint = tint = bg;
                }
                request = next;
                if (snapshot != null) {
                    BufferedImage image = snapshot;
                    pending = worker.submit(() -> prepare(image, next));
                } else {
                    previousTint = tint = bg;
                    transition = 1;
                }
            }
            if (pending != null && pending.isDone()) {
                try {
                    Prepared prepared = pending.get();
                    accept(prepared, request);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { throw new IOException("Cover processing failed", e); }
                finally { pending = null; }
            }
            transition = Math.min(1, transition + seconds / .8);
            if (transition >= 1 && previousTexture != 0) {
                GL11.glDeleteTextures(previousTexture); previousTexture = 0;
            }
            time += seconds;
            double[] drift = mode.equals("monet") ? MonetDrift.at(time) : new double[]{0, 0, 1};
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture == 0 ? fallbackTexture : previousTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture == 0 ? fallbackTexture : texture);
            GL11.glDisable(GL11.GL_BLEND);
            compositor.draw(width, height, fw, fh, 0, () -> {
                i("image", 0); i("previousImage", 1);
                i("mode", switch (mode) { case "blur" -> 1; case "nomand" -> 2; case "monet" -> 3; default -> 0; });
                i("hasImage", texture == 0 ? 0 : 1); i("hasPrevious", previousTexture == 0 ? 0 : 1);
                i("light", light ? 1 : 0); i("halfPane", preferences.monetLayout().getValue().equals("half-pane-gradient") ? 1 : 0);
                GL20.glUniform2f(uniform("imageSize"), imageWidth, imageHeight);
                GL20.glUniform2f(uniform("previousSize"), previousWidth, previousHeight);
                GL20.glUniform3f(uniform("drift"), (float) drift[0], (float) drift[1], (float) drift[2]);
                f("transition", transition * transition * (3 - 2 * transition));
                f("paneWidth", width >= 1024 ? .60 : width >= 640 ? .68 : .72);
                color("background", bg); color("accent", accent); color("primary", fg);
                color("tint", tint); color("previousTint", previousTint);
            });
        } finally {
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            GL13.glActiveTexture(oldUnit);
        }
    }

    private static Prepared prepare(BufferedImage image, Request request) {
        return switch (request.mode) {
            case "solid" -> {
                int[] palette = LatentPalette.extract(image);
                yield new Prepared(null, palette.length == 0 ? request.background : palette[0]);
            }
            case "blur" -> new Prepared(CloudMusic.gaussianBlur(image, 31), 0);
            case "monet" -> new Prepared(MonetPaint.paint(image, request.background, request.accent, request.primary), 0);
            default -> new Prepared(image, 0);
        };
    }

    private void accept(Prepared prepared, Request request) throws IOException {
        int nextTexture = 0;
        int oldWidth = imageWidth, oldHeight = imageHeight;
        if (prepared.image != null) {
            nextTexture = CoverArtwork.upload(prepared.image);
            if (request.mode.equals("nomand")) {
                try {
                    if (!Objects.equals(nomandEffect, request.effect)) {
                        if (nomand != null) nomand.close();
                        nomand = null;
                        nomandEffect = request.effect;
                        nomand = new NomandRenderer(request.effect);
                    }
                    int baked = nomand.bake(nextTexture, prepared.image.getWidth(), prepared.image.getHeight(), request.width,
                            request.height, request.logicalWidth, request.background, request.accent, request.primary, request.secondary, request.light);
                    GL11.glDeleteTextures(nextTexture);
                    nextTexture = baked;
                } catch (IOException | RuntimeException e) { GL11.glDeleteTextures(nextTexture); throw e; }
                imageWidth = request.width; imageHeight = request.height;
            } else {
                imageWidth = prepared.image.getWidth(); imageHeight = prepared.image.getHeight();
            }
        }
        if (previousTexture != 0) GL11.glDeleteTextures(previousTexture);
        previousTexture = texture;
        previousWidth = oldWidth; previousHeight = oldHeight;
        texture = nextTexture;
        previousTint = tint == 0 ? request.background : tint;
        tint = prepared.tint;
        transition = previousTexture == 0 && !request.mode.equals("solid") ? 1 : 0;
    }

    private void clearFrames() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (previousTexture != 0) GL11.glDeleteTextures(previousTexture);
        texture = previousTexture = 0;
        tint = previousTint = 0;
    }
    private int uniform(String name) { return uniforms.computeIfAbsent(name, compositor::uniform); }
    private void i(String name, int value) { GL20.glUniform1i(uniform(name), value); }
    private void f(String name, double value) { GL20.glUniform1f(uniform(name), (float) value); }
    private void color(String name, int color) {
        GL20.glUniform3f(uniform(name), ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f);
    }
    @Override public void close() {
        if (pending != null) pending.cancel(true);
        clearFrames();
        if (fallbackTexture != 0) GL11.glDeleteTextures(fallbackTexture);
        if (nomand != null) nomand.close();
        compositor.close();
    }
}
