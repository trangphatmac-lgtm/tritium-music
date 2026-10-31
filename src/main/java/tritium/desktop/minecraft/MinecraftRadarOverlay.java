package tritium.desktop.minecraft;

import tritium.desktop.DesktopAppState;
import tritium.minecraft.protocol.RadarFrame;
import tritium.minecraft.render.RadarPainter;
import tritium.rendering.rendersystem.RenderSystem;

/** Global, non-interactive overlay, independent of the currently selected music/settings page. */
public final class MinecraftRadarOverlay {
    private MinecraftRadarOverlay() { }
    public static void render() { render(DesktopAppState.minecraft().radarFrame()); }
    public static void render(RadarFrame frame) {
        if (frame == null || !frame.visible || !frame.valid()) return;
        double width = RenderSystem.getWidth(), height = RenderSystem.getHeight();
        RadarPainter.draw(frame, frame.fit(width, height), RenderSystem.getFramebufferWidth() / width,
                RenderSystem.getFramebufferHeight() / height, RenderSystem.getFramebufferHeight());
    }
}
