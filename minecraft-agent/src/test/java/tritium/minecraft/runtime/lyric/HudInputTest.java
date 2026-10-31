package tritium.minecraft.runtime.lyric;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HudInputTest {
    @Test void movementKeysAreHiddenAndSprintTextDragsInPixelsWithoutScaling() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        HudConfig.Module sprint = c.find("sprint"), stasis = c.find("stasis");
        sprint.key = 30; stasis.key = 30; c.modules = new HudConfig.Module[]{sprint, stasis};
        boolean[] keys = new boolean[256]; keys[30] = true; input.keys(c, true, keys);
        assertFalse(sprint.enabled); assertFalse(stasis.enabled);
        c.hidden = true; keys[30] = false; input.keys(c, true, keys); keys[30] = true; input.keys(c, true, keys);
        assertTrue(sprint.enabled); assertTrue(stasis.enabled); events.clear();
        input.edit(true); assertFalse(input.mouse(c, true, 8, 8, -1, false, 120, 500, 300));
        assertTrue(input.mouse(c, true, 8, 8, 0, true, 0, 500, 300));
        input.tick(c, true, 200, 150, 500, 300); input.mouse(c, true, 200, 150, 0, false, 0, 500, 300);
        assertEquals(197, sprint.movement.textX); assertEquals(147, sprint.movement.textY);
        assertEquals(1, sprint.scale); assertEquals(1, events.size()); assertEquals(197, events.get(0).x);
        c.hidden = false; assertFalse(input.mouse(c, true, 200, 150, 0, true, 0, 500, 300));
    }

    @Test void hiddenCombatShortcutsRequireHiddenAndCannotCaptureHudEditor() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        HudConfig.Module aim = c.find("aimAssist"); aim.key = 30;
        c.modules = new HudConfig.Module[]{aim}; boolean[] keys = new boolean[256]; keys[30] = true;
        input.keys(c, true, keys); assertFalse(aim.enabled); assertTrue(events.isEmpty());
        c.hidden = true; input.keys(c, true, keys); assertFalse(aim.enabled);
        keys[30] = false; input.keys(c, true, keys); keys[30] = true; input.keys(c, true, keys); assertTrue(aim.enabled);
        input.edit(true); assertFalse(input.mouse(c, true, 5, 5, 0, true, 0, 1000, 700)); assertFalse(input.dragging());
    }

    @Test void worldLyricsCanToggleButNeverCapturesHudLayoutInput() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        HudConfig.Module world = new HudConfig.Module("worldLyrics", .5, .5, 250, 100);
        world.key = 24; c.modules = new HudConfig.Module[]{world};
        boolean[] keys = new boolean[256]; keys[24] = true;
        input.keys(c, true, keys); assertTrue(world.enabled); assertEquals(1, events.size());
        assertEquals("toggle", events.get(0).action);
        input.edit(true);
        double[] bounds = world.bounds(1000, 700);
        assertFalse(input.mouse(c, true, bounds[0] + 5, bounds[1] + 5, 0, true, 0, 1000, 700));
        assertFalse(input.mouse(c, true, bounds[0] + 5, bounds[1] + 5, -1, false, 120, 1000, 700));
        assertEquals(1, world.scale); assertFalse(input.dragging()); assertEquals(1, events.size());
    }
    @Test void sharedKeysAreEdgesAndUninjectWins() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        c.modules[0].key = 24; c.modules[1].key = 24;
        boolean[] keys = new boolean[256]; keys[24] = true;
        assertFalse(input.keys(c, true, keys)); assertEquals(2, events.size());
        input.keys(c, true, keys); assertEquals(2, events.size());
        keys[24] = false; input.keys(c, true, keys); c.uninjectKey = 24;
        keys[24] = true; assertTrue(input.keys(c, true, keys)); assertEquals(2, events.size());
    }
    @Test void heldKeysAcrossChatOrFocusChangeDoNotToggle() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig(); c.modules[0].key = 30;
        boolean[] keys = new boolean[256]; keys[30] = true;
        input.keys(c, false, keys); input.keys(c, true, keys); assertTrue(events.isEmpty());
        keys[30] = false; input.keys(c, true, keys); keys[30] = true; input.keys(c, true, keys); assertEquals(1, events.size());
    }
    @Test void dedicatedEditorSavesDragAndStopsEditingWhenScreenCloses() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        input.edit(true); input.tick(c, true, 0, 0, 1000, 700);
        assertFalse(input.mouse(c, true, 900, 400, 0, true, 0, 1000, 700));
        double[] bounds = c.modules[0].bounds(1000, 700);
        assertTrue(input.mouse(c, true, bounds[0] + 5, bounds[1] + 5, 0, true, 0, 1000, 700));
        input.tick(c, true, 300, 300, 1000, 700);
        input.tick(c, false, 300, 300, 1000, 700);
        assertFalse(input.editing); assertFalse(input.dragging());
        assertEquals(2, events.size()); assertEquals("layout", events.get(0).action);
        assertEquals("edit", events.get(1).action); assertFalse(events.get(1).enabled);
        double[] moved = c.modules[0].bounds(1000, 700); assertEquals(295, moved[0], .001); assertEquals(295, moved[1], .001);
        input.edit(true); input.tick(c, true, 0, 0, 1000, 700);
        assertTrue(input.mouse(c, true, moved[0] + 5, moved[1] + 5, 0, true, 0, 1000, 700));
        input.close(c); input.close(c);
        assertEquals(4, events.size(), "Repeated close must not save or send edit state twice");
        assertFalse(input.editing); assertFalse(input.dragging());
        assertFalse(input.mouse(c, true, moved[0] + 5, moved[1] + 5, 0, true, 0, 1000, 700));
        double[] small = c.modules[0].bounds(150, 100); assertTrue(small[0] + small[2] <= 150); assertTrue(small[1] + small[3] <= 100);
    }
    @Test void editBindingTogglesBothWaysIgnoresRepeatAndHonorsUninjectPriority() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        c.editKey = 24; c.modules[0].key = 24;
        boolean[] keys = new boolean[256]; keys[24] = true;
        input.keys(c, false, keys); input.keys(c, true, keys);
        assertFalse(input.editing); assertTrue(events.isEmpty());
        keys[24] = false; input.keys(c, true, keys);
        keys[24] = true; input.keys(c, true, keys); input.keys(c, true, keys);
        assertTrue(input.editing); assertFalse(c.modules[0].enabled);
        assertEquals(1, events.size()); assertEquals("edit", events.get(0).action); assertTrue(events.get(0).enabled);
        keys[24] = false; input.keys(c, true, keys);
        keys[24] = true; input.keys(c, true, keys);
        assertFalse(input.editing); assertEquals(2, events.size()); assertFalse(events.get(1).enabled);
        input.edit(true); c.uninjectKey = 24;
        keys[24] = false; input.keys(c, true, keys);
        keys[24] = true; assertTrue(input.keys(c, true, keys));
        assertEquals(2, events.size());
    }
    @Test void targetHudDragPersistsCenterOffsetsAndNeverResizes() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig(); c.hidden = true;
        HudConfig.Module hud = c.find("targetHud"); hud.textWidth = 180; hud.textHeight = 37;
        input.edit(true); double[] before = hud.bounds(1000,700);
        assertTrue(input.mouse(c,true,before[0]+20,before[1]+10,0,true,0,1000,700));
        input.mouse(c,true,before[0]+60,before[1]-20,0,false,0,1000,700);
        assertEquals(110,hud.openB4.offsetX); assertEquals(10,hud.openB4.offsetY);
        assertEquals(1,events.size()); assertEquals("targetHud",events.get(0).id);
        assertEquals(110,events.get(0).x); assertEquals(10,events.get(0).y);
        double[] moved = hud.bounds(1000,700);
        input.mouse(c,true,moved[0]+5,moved[1]+5,-1,false,1,1000,700);
        assertEquals(1,hud.scale); assertEquals(180,hud.textWidth);
        c.hidden=false; assertFalse(input.mouse(c,true,moved[0]+5,moved[1]+5,0,true,0,1000,700));
    }

}
