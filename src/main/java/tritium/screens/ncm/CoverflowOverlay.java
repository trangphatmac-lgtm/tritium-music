package tritium.screens.ncm;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.desktop.DesktopScreen;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.rendering.Image;
import tritium.rendering.Rect;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shaders;
import tritium.screens.ncm.navigation.CoverArtwork;
import tritium.screens.ncm.navigation.FoliaGeometry.Box;
import tritium.screens.ncm.navigation.NavigationContent.Entry;
import java.util.ArrayList;
import java.util.List;

/** Ordered cover navigation. OldSchool restores a3f70d2's projection, scale anchor and fadeout reflection. */
public class CoverflowOverlay extends DesktopScreen implements SharedConstants, SharedRenderingConstants {
    private static final double COVER_SIZE = 96;
    private final CoverflowReflection reflection = new CoverflowReflection();
    private final CoverflowSsr coverReflection = new CoverflowSsr();
    private final CoverArtwork artwork = new CoverArtwork();
    private final List<Box> hits = new ArrayList<>();
    private List<Entry> entries = List.of();
    private int index;
    private double scrollOffset;
    private boolean oldSchool;
    private boolean resourcesUsed;
    private double[] rotations = new double[0], scales = new double[0];

    public void setEntries(List<Entry> value) {
        if (entries == value) return;
        entries = value;
        rotations = new double[value.size()]; scales = new double[value.size()];
        java.util.Arrays.fill(scales, .75);
        index = 0; scrollOffset = 0;
    }
    public void setIndex(int value) { index = Math.clamp(value, 0, Math.max(0, entries.size() - 1)); }
    public void setOldSchool(boolean value) { oldSchool = value; }
    public int hitIndex(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x,y)) return hits.get(i).index();
        return -1;
    }
    private double distance() { return oldSchool ? 200 : 240; }
    private double sceneCenter() { return oldSchool ? .5 : .4; }
    private double paneHeight() { return 2 * distance() * Math.tan(Math.toRadians(45) / 2); }
    private boolean mirrors() { return !oldSchool && DesktopAppState.preferences().coverflowCoverReflection().getValue(); }

    /** Standalone rendering entry point used by framebuffer tests. The application embeds renderScene. */
    @Override public void drawScreen(int mouseX, int mouseY) {
        UiMaterials.beginFrame(mouseX, mouseY);
        DesktopAppState.background().render(Display.isVisible());
        UiMaterials.captureBackdrop(false);
        renderScene();
    }
    public void renderScene() {
        hits.clear();
        resourcesUsed = true;
        double height = RenderSystem.getHeight();
        if (oldSchool) Rect.draw(0, 0, RenderSystem.getWidth(), height, AppTheme.color(0x80000000, 0x40FFFFFF));
        else {
            double bottom = height * (sceneCenter() + COVER_SIZE * .5 / paneHeight());
            reflection.draw(bottom + 1.5 * Math.min(1, height / 360), 1);
        }
        api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().loadIdentity();
        Project.gluPerspective(45, (float) (RenderSystem.getWidth() / height), 1, 3000);
        api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().loadIdentity();
        api.getGLStateManager().translate(0, 0, -distance());
        api.getGLStateManager().scale(1, -1, 1);
        api.getGLStateManager().translate(0, (sceneCenter() - .5) * paneHeight(), 0);
        if (mirrors()) coverReflection.beginFrame(-COVER_SIZE * .5 - (sceneCenter() - .5) * paneHeight());
        api.getGLStateManager().clear(GL11.GL_DEPTH_BUFFER_BIT);
        api.getGLStateManager().disableDepth();
        api.getGLStateManager().depthMask(false);
        scrollOffset = Interpolations.interpolate(scrollOffset, index * 72.0, .2f);
        // Back-to-front, bounded to the visible neighbourhood even for very large playlists.
        for (int i = Math.max(0, index - 7); i < index; i++) drawCover(i);
        for (int i = Math.min(entries.size() - 1, index + 7); i >= index; i--) drawCover(i);
        api.getGLStateManager().depthMask(true);
        api.getGLStateManager().popMatrix();
        api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
        api.getGLStateManager().popMatrix();
        api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
        if (mirrors()) coverReflection.draw(1);
        if (!entries.isEmpty()) {
            var font = FontManager.pf50bold;
            double scale = Math.min(1, height / 360);
            double titleY = height - (font.getHeight() + 18) * scale;
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(RenderSystem.getWidth() / 2, titleY, 0);
            api.getGLStateManager().scale(scale, scale, 1);
            font.drawCenteredStringWithShadow(font.trim(entries.get(index).title(), Math.max(1, (RenderSystem.getWidth() - 40) / scale)), 0, 0, AppTheme.foreground());
            api.getGLStateManager().popMatrix();
        }
    }
    private void drawCover(int i) {
        var entry = entries.get(i);
        var texture = artwork.texture(entry);
        rotations[i] = Interpolations.interpolate(rotations[i], i == index ? 0 : i < index ? 45 : -45, .2f);
        scales[i] = Interpolations.interpolate(scales[i], i == index ? 1 : .75, .2f);
        double centerX = i * 72 - scrollOffset;
        double anchor = oldSchool ? 0 : 48;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, 48, 0);
        api.getGLStateManager().rotate((float) rotations[i], 0, 1, 0);
        api.getGLStateManager().translate(0, -48, 0);
        api.getGLStateManager().translate(0, anchor, 0);
        api.getGLStateManager().scale(scales[i], scales[i], 1);
        api.getGLStateManager().translate(0, -anchor, 0);
        Rect.draw(-48, -48, 96, 96, AppTheme.surface());
        if (texture != null) {
            texture.linearFilter();
            api.getGLStateManager().bindTexture(texture.getGlTextureId());
            Image.draw(-48, -48, 96, 96, Image.Type.Normal);
            if (mirrors()) coverReflection.addCover(texture.getGlTextureId(), -48, -48, 96);
            if (oldSchool && DesktopAppState.preferences().coverflowCoverReflection().getValue())
                Shaders.VF_FADEOUT.draw(-48, 48, 96, 96, .5, .85f);
        } else {
            FontManager.music18.drawCenteredString("D", 0, -8, AppTheme.secondary());
        }
        api.getGLStateManager().popMatrix();
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        double radians = Math.toRadians(rotations[i]);
        for (int dx : new int[]{-48,48}) for (int dy : new int[]{-48,48}) {
            double x = dx * scales[i];
            double z = -Math.sin(radians) * x;
            double perspective = distance() / (distance() - z);
            double px = RenderSystem.getWidth() / 2 + (centerX + Math.cos(radians) * x) * perspective * RenderSystem.getHeight() / paneHeight();
            double py = RenderSystem.getHeight() / 2 + ((dy - anchor) * scales[i] + anchor + (sceneCenter() - .5) * paneHeight()) * perspective * RenderSystem.getHeight() / paneHeight();
            minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        hits.add(new Box(i,minX,minY,maxX-minX,maxY-minY));
    }
    @Override public void onGuiClosed() {
        if (!resourcesUsed) return;
        reflection.close(); coverReflection.close(); resourcesUsed = false;
    }
}
