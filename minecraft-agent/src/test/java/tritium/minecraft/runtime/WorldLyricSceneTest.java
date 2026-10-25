package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class WorldLyricSceneTest {
    private static final long NOW = 1_000_000_000L;
    private final WorldLyricScene scene = new WorldLyricScene(text -> text.length() * 10, new Random(12));
    private final HudConfig.Module module = module();
    private final WorldLyricScene.Camera camera = camera();
    private final PlaybackState.Track track = track();

    @Test void generatedTextKeepsWorldPositionAndDistanceCompensatesScale() {
        WorldLyricScene.Text first = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        double d = distance(first.x - camera.eyeX, first.y - camera.eyeY, first.z - camera.eyeZ);
        assertEquals(10, d, 1e-9); assertEquals(.03 * 10 / 6.5, first.scale, 1e-9);
        camera.x = camera.eyeX = 1;
        WorldLyricScene.Text second = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0);
        assertEquals(first.x, second.x); assertEquals(first.y, second.y); assertEquals(first.z, second.z);
        assertNotEquals(first.yaw, second.yaw);
    }

    @Test void wordPopUsesRecordedTimesWithFastFadeAndOneSixtyMsShrink() {
        track.lyrics.get(0).words.add(WorldLyricTimelineTest.word(1000, 500, "A"));
        track.lyrics.get(0).words.add(WorldLyricTimelineTest.word(2000, 500, "B"));
        track.lyrics.get(0).text = "AB";
        WorldLyricScene.Frame first = scene.update(track, 1016, true, module, camera, NOW);
        WorldLyricScene.Glyph a = first.texts.get(0).glyphs.get(0);
        assertEquals(1, first.texts.get(0).glyphs.size()); assertEquals(.3, a.alpha, 1e-9);
        assertEquals(1 + .55 * .9 * .9, a.scale, 1e-9);
        WorldLyricScene.Glyph settled = scene.update(track, 1160, true, module, camera, NOW + 144_000_000L).texts.get(0).glyphs.get(0);
        assertEquals(1, settled.alpha); assertEquals(1, settled.scale);
        assertEquals(2, scene.update(track, 2016, true, module, camera, NOW + 1_000_000_000L).texts.get(0).glyphs.size());
    }

    @Test void pauseFadesForFourHundredMsAndResumeFadesForThreeHundredMs() {
        scene.update(track, 2000, true, module, camera, NOW);
        WorldLyricScene.Text pause = scene.update(track, 2000, false, module, camera, NOW + 10_000_000L).texts.get(0);
        WorldLyricScene.Text halfway = scene.update(track, 2000, false, module, camera, NOW + 210_000_000L).texts.get(0);
        assertEquals(.5, halfway.glyphs.get(0).alpha, 1e-9); assertEquals(pause.y + .08, halfway.y, 1e-9);
        assertTrue(scene.update(track, 2000, false, module, camera, NOW + 410_000_000L).texts.isEmpty());
        assertTrue(scene.update(track, 2000, true, module, camera, NOW + 500_000_000L).texts.get(0).glyphs.isEmpty());
        WorldLyricScene.Text resumed = scene.update(track, 2000, true, module, camera, NOW + 650_000_000L).texts.get(0);
        assertEquals(.5, resumed.glyphs.get(0).alpha, 1e-9);
    }

    @Test void keepAwayPushesToSixtyPercentOfConfiguredDistance() {
        WorldLyricScene.Text first = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        camera.x = camera.eyeX = first.x; camera.y = camera.eyeY = first.y; camera.z = camera.eyeZ = first.z - 1;
        WorldLyricScene.Text pushed = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0);
        assertEquals(6, distance(pushed.x - camera.x, pushed.y - camera.y, pushed.z - camera.z), 1e-9);
    }

    @Test void stayOnScreenMovesAroundCameraSmoothlyWithoutChangingDistance() {
        module.world.keepAway = false; module.world.stayOnScreen = true; module.world.spread = 0;
        WorldLyricScene.Text first = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        camera.yaw = 100;
        WorldLyricScene.Text turned = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0);
        assertTrue(turned.x < first.x);
        assertEquals(distance(first.x, first.y, first.z), distance(turned.x, turned.y, turned.z), 1e-9);
        assertTrue(Math.abs(turned.yaw - camera.yaw) > 1, "follows smoothly instead of snapping to center");
    }

    @Test void settingsOffsetAndOpacityAffectTheSameImmutableFrameForBothPasses() {
        module.world.offset = 200; module.opacity = .4;
        WorldLyricScene.Frame frame = scene.update(track, 1000, true, module, camera, NOW);
        assertEquals(1200, frame.songMillis); assertEquals(.4, frame.texts.get(0).glyphs.get(0).alpha, 1e-9);
        assertThrows(UnsupportedOperationException.class, () -> frame.texts.clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.texts.get(0).glyphs.clear());
    }

    @Test void worldChangeAndDisablingRemoveOldFloatingObjects() {
        scene.update(track, 2000, true, module, camera, NOW);
        camera.worldIdentity = new Object(); camera.x = camera.eyeX = 100;
        WorldLyricScene.Frame changed = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L);
        assertEquals(1, changed.texts.size()); assertTrue(changed.texts.get(0).x > 90);
        module.enabled = false; assertTrue(scene.update(track, 2016, true, module, camera, NOW + 32_000_000L).texts.isEmpty());
    }

    @Test void wiggleIsHeldBetweenDiscreteAnimationFrames() {
        module.world.wiggle = true; module.world.wiggleFps = 8;
        WorldLyricScene.Glyph first = scene.update(track, 2000, true, module, camera, NOW).texts.get(0).glyphs.get(0);
        WorldLyricScene.Glyph held = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0).glyphs.get(0);
        WorldLyricScene.Glyph next = scene.update(track, 2130, true, module, camera, NOW + 130_000_000L).texts.get(0).glyphs.get(0);
        assertEquals(first.rotation, held.rotation); assertEquals(first.dx, held.dx); assertNotEquals(first.rotation, next.rotation);
        assertTrue(Math.abs(first.rotation) <= 7.2); assertTrue(Math.abs(first.dx) <= .64); assertTrue(Math.abs(first.dy) <= 1.28);
    }

    private static double distance(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }
    private static HudConfig.Module module() {
        HudConfig.Module module = new HudConfig.Module("worldLyrics", 0, 0, 200, 50);
        module.enabled = true; module.world.wiggle = false; return module;
    }
    private static PlaybackState.Track track() {
        PlaybackState.Track track = new PlaybackState.Track(); track.id = 42;
        track.lyrics.add(WorldLyricTimelineTest.line(1000, "hello")); track.lyrics.add(WorldLyricTimelineTest.line(5000, "world")); return track;
    }
    private static WorldLyricScene.Camera camera() {
        WorldLyricScene.Camera camera = new WorldLyricScene.Camera(); camera.worldIdentity = new Object(); return camera;
    }
}
