package tritium.minecraft.runtime.render;

import java.util.Arrays;
import java.util.function.Consumer;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.protocol.RadarFrame;
import tritium.minecraft.render.RadarPainter;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;

public class RadarModule extends RenderModule {
    private Consumer<RadarFrame> frames;
    private boolean remoteVisible;

    public RadarModule(RenderAccess game, Render3D draw) { super("radar", game, draw); }
    public void frames(Consumer<RadarFrame> frames) { this.frames = frames; }

    @Override public void configure(OpenB4Settings next, boolean enabled) {
        super.configure(next, enabled);
        if (!enabled || !next.offscreenRendering) hideRemote();
    }
    @Override public void disable() { hideRemote(); }

    @Override public void overlay(float partialTicks) {
        if (!enabled || !g.overlay()) { hideRemote(); return; }
        RadarFrame frame = snapshot();
        if (s.offscreenRendering) {
            remoteVisible = true;
            if (frames != null) frames.accept(frame);
        } else {
            hideRemote();
            paint(frame);
        }
    }
    /** Snapshot once on the game render thread, before crossing the IPC boundary. */
    public RadarFrame snapshot() {
        RadarFrame frame = new RadarFrame(); frame.visible = true; frame.tracers = on("Show tracer lines");
        int scale = g.scale();
        // Fractional viewport dimensions avoid a one-pixel shift on odd-sized game windows.
        frame.viewportWidth = g.displayWidth() / (double) scale;
        frame.viewportHeight = g.displayHeight() / (double) scale;
        double[] points = new double[Math.min(g.players().size(), RadarFrame.MAX_POINTS) * 2]; int count = 0;
        Object self = g.player();
        for (Object player : g.players()) {
            if (player == self || g.integerField(player, "deathTime") != 0 || g.bot(player)) continue;
            double distance = g.distanceSq(player, self);
            if (distance > 360) continue;
            double angle = (g.value(self, "rotationYaw") + Math.atan2(g.pos(player, 0) - g.pos(self, 0),
                    g.pos(player, 2) - g.pos(self, 2)) * 57.29578f) % 360;
            if (count == points.length) break;
            points[count++] = frame.centerX - distance / 5 * Math.sin(Math.toRadians(angle));
            points[count++] = frame.centerY - distance / 5 * Math.cos(Math.toRadians(angle));
        }
        frame.points = Arrays.copyOf(points, count); return frame;
    }
    protected void paint(RadarFrame frame) {
        int scale = g.scale();
        RadarPainter.draw(frame, new RadarFrame.Placement(0, 0, 1), scale, scale, g.displayHeight());
    }
    private void hideRemote() {
        if (!remoteVisible) return;
        remoteVisible = false;
        if (frames != null) frames.accept(new RadarFrame());
    }
}
