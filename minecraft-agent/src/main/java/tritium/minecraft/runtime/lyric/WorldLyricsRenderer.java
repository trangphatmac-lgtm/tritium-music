package tritium.minecraft.runtime.lyric;

import org.lwjgl.opengl.GL11;
import tritium.minecraft.runtime.hook.GameAccess;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;

/** Drawn at the end of renderWorldPass, while Minecraft's world matrices are still active. */
public final class WorldLyricsRenderer implements AutoCloseable {
    private final GameAccess game;
    private final WorldGlyphFont font = new WorldGlyphFont();
    private final WorldLyricsGlow glow = new WorldLyricsGlow();
    private final WorldLyricScene scene;
    private NativeFontCache nativeCache;
    private boolean smooth = true;

    public WorldLyricsRenderer(GameAccess game) {
        this.game = game;
        scene = new WorldLyricScene(text -> {
            if (smooth) return font.width(text);
            try { return game.nativeWidth(text); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot measure Minecraft font", e); }
        });
    }

    public void render(PlaybackState.Track track, double positionMillis, boolean playing, HudConfig.Module module,
                float partialTicks) throws ReflectiveOperationException {
        if (module == null || !module.enabled) { reset(); return; }
        GameAccess.Camera camera = game.camera(partialTicks);
        if (camera == null) { reset(); return; }
        if (smooth != module.world.smoothFont) { smooth = module.world.smoothFont; scene.reset(); }
        WorldLyricScene.Camera view = new WorldLyricScene.Camera();
        view.x = camera.originX; view.y = camera.originY; view.z = camera.originZ;
        view.eyeX = camera.eyeX; view.eyeY = camera.eyeY; view.eyeZ = camera.eyeZ;
        view.yaw = camera.yaw; view.pitch = camera.pitch; view.verticalFov = camera.fov;
        view.aspect = camera.pixelWidth / (double)Math.max(1, camera.pixelHeight); view.worldIdentity = camera.worldIdentity;
        WorldLyricScene.Frame frame = scene.update(track, positionMillis, playing, module, view, System.nanoTime());
        if (frame.texts.isEmpty()) return;
        draw(frame, view, module, camera.pixelWidth, camera.pixelHeight);
    }

    /** Kept independent of the reflection adapter for real-GL rendering/state tests. */
    void draw(WorldLyricScene.Frame frame, WorldLyricScene.Camera view, HudConfig.Module module, int width, int height)
            throws ReflectiveOperationException {
        smooth = module.world.smoothFont;
        if (!smooth && nativeCache == null) nativeCache = new NativeFontCache(game.minecraft.getClass().getClassLoader());
        NativeFontCache.Snapshot cached = smooth ? null : nativeCache.save();
        try (WorldGlState saved = new WorldGlState()) {
            if (module.world.glow && module.world.glowStrength > .01 && glow.available()) {
                try {
                    if (glow.begin(width, height)) {
                        drawText(frame, view, module.color, false);
                        glow.end(module.world.glowColor, module.world.glowStrength, module.world.glowRadius);
                    }
                } catch (RuntimeException failure) { glow.disable(failure); }
                finally { glow.abort(); }
            }
            drawText(frame, view, module.color, true);
        } finally { if (cached != null) cached.close(); }
    }

    private void drawText(WorldLyricScene.Frame frame, WorldLyricScene.Camera view, int color, boolean shadow)
            throws ReflectiveOperationException {
        WorldGlState.textBlend(); GL11.glDisable(GL11.GL_ALPHA_TEST);
        if (!smooth) nativeCache.prepare();
        for (WorldLyricScene.Text text : frame.texts) {
            if (text.glyphs.isEmpty()) continue;
            GL11.glPushMatrix();
            try {
                GL11.glTranslated(text.x - view.x, text.y - view.y, text.z - view.z);
                GL11.glRotated(-text.yaw, 0, 1, 0); GL11.glRotated(text.pitch, 1, 0, 0);
                GL11.glScaled(-text.scale, -text.scale, text.scale);
                for (WorldLyricScene.Glyph glyph : text.glyphs) {
                    GL11.glPushMatrix();
                    try {
                        double cx = glyph.x + glyph.width / 2, cy = glyph.y + 3.5;
                        GL11.glTranslated(cx + glyph.dx, cy + glyph.dy, 0);
                        GL11.glRotated(glyph.rotation, 0, 0, 1); GL11.glScaled(glyph.scale, glyph.scale, 1);
                        GL11.glTranslated(-cx, -cy, 0);
                        if (smooth) {
                            if (shadow) font.draw(glyph.character, glyph.x + .6, glyph.y + 8.2, 0, glyph.alpha * .45);
                            font.draw(glyph.character, glyph.x, glyph.y + 7.6, color, glyph.alpha);
                        } else {
                            int alpha = (int)(glyph.alpha * 255);
                            if (alpha >= 5) game.drawNative(String.valueOf(glyph.character), (float)glyph.x, (float)glyph.y,
                                    alpha << 24 | color & 0xffffff, shadow);
                        }
                    } finally { GL11.glPopMatrix(); }
                }
            } finally { GL11.glPopMatrix(); }
        }
    }
    String glowError() { return glow.error(); }
    public void reset() { scene.reset(); }
    @Override public void close() {
        reset();
        // Deletion unbinds owned textures, so protect game state here too.
        try (WorldGlState saved = new WorldGlState()) { glow.close(); font.close(); }
    }
}
