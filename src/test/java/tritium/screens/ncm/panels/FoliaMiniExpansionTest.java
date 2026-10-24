package tritium.screens.ncm.panels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoliaMiniExpansionTest {
    @Test
    void usesFoliaHoverDelaysAndCancelsBriefExits() {
        FoliaMiniExpansion state = new FoliaMiniExpansion(false);
        state.update(true, true, false, 0);
        state.update(true, true, false, 19_000_000);
        assertEquals(0, state.amount());
        state.update(true, true, false, 40_000_000);
        assertTrue(state.amount() > 0 && state.amount() < 1);
        for (long t = 50; t <= 1500; t += 10) state.update(true, true, false, t * 1_000_000);
        assertEquals(1, state.amount());
        state.update(true, false, false, 1510_000_000);
        state.update(true, false, false, 1609_000_000);
        assertEquals(1, state.amount());
        state.update(true, true, false, 1610_000_000);
        assertEquals(1, state.amount());
        state.update(true, false, false, 1620_000_000);
        for (long t = 1630; t <= 3000; t += 10) state.update(true, false, false, t * 1_000_000);
        assertEquals(0, state.amount());
    }

    @Test
    void captureFreezesShapeAndDisablingAutoCollapseExpandsWithoutHover() {
        FoliaMiniExpansion state = new FoliaMiniExpansion(false);
        for (long t = 0; t <= 1000; t += 10) state.update(true, true, true, t * 1_000_000);
        assertEquals(0, state.amount(), "Seeking on the collapsed track must keep its geometry");
        for (long t = 1010; t <= 2500; t += 10) state.update(false, false, false, t * 1_000_000);
        assertEquals(1, state.amount());
        for (long t = 2510; t <= 4000; t += 10) state.update(true, false, true, t * 1_000_000);
        assertEquals(1, state.amount(), "Dragging outside the expanded capsule must not collapse it");
        for (long t = 4010; t <= 5500; t += 10) state.update(true, false, false, t * 1_000_000);
        assertEquals(0, state.amount());
    }
}
