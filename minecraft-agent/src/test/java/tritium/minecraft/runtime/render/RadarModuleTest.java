package tritium.minecraft.runtime.render;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.protocol.RadarFrame;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RadarModuleTest {
    @Test void offscreenUsesTheSameCoordinatesAndStyleWithoutPaintingIntoMinecraft() {
        Game game = new Game(); Radar radar = new Radar(game);
        OpenB4Settings settings = new HudConfig().find("radar").openB4;
        List<RadarFrame> remote = new ArrayList<RadarFrame>(); radar.frames(remote::add);
        radar.configure(settings, true); radar.overlay(0);
        assertEquals(1, radar.paints); assertTrue(remote.isEmpty());
        RadarFrame local = radar.painted;
        assertEquals(4, local.points.length); // self, dead, bot and out-of-range players are excluded.
        assertArrayEquals(new double[]{55.5, 117, 35.5, 122}, local.points, .00001);
        assertEquals(640.5, local.viewportWidth); assertEquals(360.5, local.viewportHeight);
        settings.offscreenRendering = true; radar.configure(settings, true); radar.overlay(0);
        assertEquals(1, radar.paints); assertEquals(1, remote.size());
        assertArrayEquals(local.points, remote.get(0).points);
        assertEquals(local.background, remote.get(0).background); assertEquals(local.x, remote.get(0).x);
        settings.toggles.put("Show tracer lines", true); game.self.yaw = 90;
        radar.overlay(0); assertTrue(remote.get(1).tracers);
        assertEquals(50.5, remote.get(1).points[0], .00001);
        assertEquals(122, remote.get(1).points[1], .00001);
        assertEquals(1, radar.paints);
    }

    @Test void destinationChangesAndOverlaySuppressionClearTheDesktopImmediately() {
        Game game = new Game(); Radar radar = new Radar(game);
        OpenB4Settings settings = new HudConfig().find("radar").openB4; settings.offscreenRendering = true;
        List<RadarFrame> remote = new ArrayList<RadarFrame>(); radar.frames(remote::add);
        radar.configure(settings, true); radar.overlay(0); assertTrue(last(remote).visible);
        game.visible = false; radar.overlay(0); assertFalse(last(remote).visible);
        int count = remote.size(); radar.overlay(0); assertEquals(count, remote.size());
        game.visible = true; radar.overlay(0); assertTrue(last(remote).visible);
        settings.offscreenRendering = false; radar.configure(settings, true); assertFalse(last(remote).visible);
        radar.overlay(0); assertEquals(1, radar.paints);
        settings.offscreenRendering = true; radar.configure(settings, true); radar.overlay(0);
        radar.configure(settings, false); assertFalse(last(remote).visible);
        radar.overlay(0); assertEquals(1, radar.paints);
    }

    private static RadarFrame last(List<RadarFrame> frames) { return frames.get(frames.size() - 1); }
    static class Radar extends RadarModule {
        int paints; RadarFrame painted;
        Radar(Game game) { super(game, new Render3D(game)); }
        @Override protected void paint(RadarFrame frame) { paints++; painted = frame; }
    }
    static class Player {
        double x, z; float yaw; int death; boolean bot;
        Player(double x, double z) { this.x = x; this.z = z; }
    }
    static class Game extends RenderAccess {
        Player self = new Player(0, 0); boolean visible = true; List<Player> players = new ArrayList<Player>();
        Game() {
            super(null); players.add(self); players.add(new Player(0, 5)); players.add(new Player(10, 0));
            Player dead = new Player(1, 0); dead.death = 1; players.add(dead);
            Player bot = new Player(2, 0); bot.bot = true; players.add(bot); players.add(new Player(20, 0));
        }
        @Override public boolean overlay() { return visible; }
        @Override public Object player() { return self; }
        @Override public List<?> players() { return players; }
        @Override public int scale() { return 2; }
        @Override public int displayWidth() { return 1281; }
        @Override public int displayHeight() { return 721; }
        @Override public int integerField(Object e, String key) { return ((Player)e).death; }
        @Override public boolean bot(Object e) { return ((Player)e).bot; }
        @Override public double value(Object e, String key) { return ((Player)e).yaw; }
        @Override public double pos(Object e, int axis) { return axis == 0 ? ((Player)e).x : axis == 2 ? ((Player)e).z : 0; }
    }
}
