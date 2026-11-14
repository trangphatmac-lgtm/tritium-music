package tritium.minecraft.runtime.lyric;

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

    @Test void offsetsFollowCameraRightAndUpAndZeroSpreadCentersTheLine() {
        module.world.spread = 0; module.world.keepAway = false;
        for (double yaw : new double[]{0, 90, 180, 270}) {
            for (double pitch : new double[]{-90, -40, 0, 40, 90}) {
                scene.reset(); camera.yaw = yaw; camera.pitch = pitch;
                module.world.offsetX = 0; module.world.offsetY = 0;
                WorldLyricScene.Text centered = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
                module.world.offsetX = 2; module.world.offsetY = 3;
                WorldLyricScene.Text moved = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0);
                double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
                assertEquals(-Math.cos(yr) * 2 - Math.sin(yr) * Math.sin(pr) * 3, moved.x - centered.x, 1e-9);
                assertEquals(Math.cos(pr) * 3, moved.y - centered.y, 1e-9);
                assertEquals(-Math.sin(yr) * 2 + Math.cos(yr) * Math.sin(pr) * 3, moved.z - centered.z, 1e-9);
                assertEquals(10, distance(centered.x, centered.y, centered.z), 1e-9);
            }
        }
        scene.reset(); camera.yaw = camera.pitch = 0;
        module.world.offsetX = module.world.offsetY = 0;
        WorldLyricScene.Text centered = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        assertEquals(0, centered.x); assertEquals(0, centered.y); assertEquals(10, centered.z);
        module.scale = 2; module.world.distance = 20;
        WorldLyricScene.Text resized = scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0);
        assertEquals(20, resized.z); assertEquals(centered.scale * 4, resized.scale, 1e-9);
        assertEquals(1, scene.update(track, 2032, true, module, camera, NOW + 32_000_000L).texts.size());
    }

    @Test void rainbowIsPerCharacterAnimatedAndCanBeDisabledOnTheCurrentLine() {
        module.world.rainbow = true;
        WorldLyricScene.Text first = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        assertNotEquals(first.glyphs.get(0).color, first.glyphs.get(1).color);
        WorldLyricScene.Text later = scene.update(track, 2100, true, module, camera, NOW + 100_000_000L).texts.get(0);
        assertNotEquals(first.glyphs.get(0).color, later.glyphs.get(0).color);
        module.world.rainbow = false; module.color = 0xff123456;
        WorldLyricScene.Text plain = scene.update(track, 2116, true, module, camera, NOW + 116_000_000L).texts.get(0);
        assertTrue(plain.glyphs.stream().allMatch(glyph -> glyph.color == module.color));
    }

    @Test void translationIsSmallerCenteredWrappedAndTogglesOnTheCurrentLine() {
        track.lyrics.get(0).translation = "在夜空中闪耀的星";
        module.world.rainbow = true; module.opacity = .4;
        WorldLyricScene.Text text = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        java.util.List<WorldLyricScene.Glyph> translation = text.glyphs.stream().filter(glyph -> glyph.fontScale < 1)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(8, translation.size());
        assertEquals(-8 * 10 * .72 / 2, translation.get(0).x, 1e-9);
        assertTrue(translation.stream().allMatch(glyph -> glyph.y > text.glyphs.get(0).y && glyph.color == 0xffffffff));
        assertEquals(.4 * .75, translation.get(0).alpha, 1e-9);
        module.translation = false;
        assertTrue(scene.update(track, 2016, true, module, camera, NOW + 16_000_000L).texts.get(0).glyphs.stream()
                .allMatch(glyph -> glyph.fontScale == 1));
        module.translation = true;
        assertTrue(scene.update(track, 2032, true, module, camera, NOW + 32_000_000L).texts.get(0).glyphs.stream()
                .anyMatch(glyph -> glyph.fontScale < 1));
        track.lyrics.get(0).translation = String.join("", java.util.Collections.nCopies(8, "很长的中文翻译")); scene.reset();
        WorldLyricScene.Text wrapped = scene.update(track, 2000, true, module, camera, NOW).texts.get(0);
        assertTrue(wrapped.glyphs.stream().filter(glyph -> glyph.fontScale < 1).map(glyph -> glyph.y).distinct().count() > 1);
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
