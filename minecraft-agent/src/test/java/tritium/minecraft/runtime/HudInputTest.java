package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HudInputTest {
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
    @Test void nativeChatEditingCapturesOnlyHudAndSavesOnClose() {
        List<PlaybackState.Event> events = new ArrayList<PlaybackState.Event>();
        HudInput input = new HudInput(events::add); HudConfig c = new HudConfig();
        input.edit(true); input.tick(c, true, 0, 0, 1000, 700);
        assertFalse(input.mouse(c, true, 900, 400, 0, true, 0, 1000, 700));
        double[] bounds = c.modules[0].bounds(1000, 700);
        assertTrue(input.mouse(c, true, bounds[0] + 5, bounds[1] + 5, 0, true, 0, 1000, 700));
        input.tick(c, true, 300, 300, 1000, 700);
        input.tick(c, false, 300, 300, 1000, 700);
        assertTrue(input.editing); assertFalse(input.dragging());
        assertEquals(1, events.size()); assertEquals("layout", events.get(0).action);
        double[] moved = c.modules[0].bounds(1000, 700); assertEquals(295, moved[0], .001); assertEquals(295, moved[1], .001);
        input.tick(c, true, 0, 0, 1000, 700);
        assertTrue(input.mouse(c, true, moved[0] + 5, moved[1] + 5, 0, true, 0, 1000, 700));
        input.finish(c); input.edit(false);
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
}
