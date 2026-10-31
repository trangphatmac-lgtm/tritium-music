package tritium.minecraft.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import tritium.minecraft.bridge.HookBridge;
import static org.junit.jupiter.api.Assertions.*;

class BridgeAssistHooksTest {
    @AfterEach void clearCallbacks() { HookBridge.moduleCallback = null; }

    @Test void bridgeForwardsExactEventArgumentsAndCancellationResults() {
        Object input = new Object(), inventory = new Object(), component = new Object();
        List<Integer> order = new ArrayList<Integer>();
        HookBridge.moduleCallback = (kind, args) -> {
            order.add(kind);
            switch (kind) {
                case 13: assertArrayEquals(new Object[]{input}, args); break;
                case 14: assertEquals(0, args.length); return true;
                case 15: assertArrayEquals(new Object[]{inventory, -120}, args); return true;
                case 17: assertArrayEquals(new Object[]{component, 1}, args); break;
                default: assertEquals(0, args.length);
            }
            return null;
        };
        HookBridge.preInput(input); assertTrue(HookBridge.rightClick()); assertTrue(HookBridge.slotScroll(inventory, -120));
        HookBridge.clientRotation(); HookBridge.chat(component, 1); HookBridge.beforeMouseOver(); HookBridge.afterMouseOver();
        assertEquals(Arrays.asList(13, 14, 15, 16, 17, 18, 19), order);
    }

    @Test void absentOrFailedCallbacksPreserveVanillaActions() {
        assertFalse(HookBridge.rightClick()); assertFalse(HookBridge.slotScroll(new Object(), 1));
        HookBridge.moduleCallback = (kind, args) -> { throw new IllegalStateException("callback failure"); };
        assertFalse(HookBridge.rightClick()); assertFalse(HookBridge.slotScroll(new Object(), 1));
        assertDoesNotThrow(() -> { HookBridge.preInput(new Object()); HookBridge.clientRotation(); HookBridge.chat(new Object(), 0); HookBridge.beforeMouseOver(); HookBridge.afterMouseOver(); });
    }
}
