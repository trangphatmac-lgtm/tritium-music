package tritium.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppThemeTest {
    @Test
    void restoresEitherModeImmediatelyAndInterpolatesBothDirections() {
        for (boolean light : new boolean[]{false, true}) {
            AppTheme.Transition transition = new AppTheme.Transition();
            float start = light ? 1 : 0;
            assertEquals(start, transition.update(light, 0));
            float middle = transition.update(!light, 100_000_000);
            assertTrue(middle > 0 && middle < 1);
            assertEquals(1 - start, transition.update(!light, 1_000_000_000));
        }
    }

    @Test
    void interruptedTransitionPreservesPositionAndVelocity() {
        AppTheme.Transition transition = new AppTheme.Transition();
        transition.update(false, 0);
        float before = transition.update(true, 99_000_000);
        float reversal = transition.update(true, 100_000_000);
        assertEquals(reversal, transition.update(false, 100_000_000));
        float after = transition.update(false, 101_000_000);
        // The direction eases through the reversal instead of snapping back.
        assertEquals(reversal - before, after - reversal, .0004f);
        float previous = after;
        for (int frame = 1; frame <= 60; frame++) {
            float value = transition.update(false, 101_000_000L + frame * 16_666_667L);
            assertTrue(value >= 0 && value <= 1);
            assertTrue(Math.abs(value - previous) < .12f);
            previous = value;
        }
        assertEquals(0, previous);
    }

    @Test
    void animationTimingDoesNotDependOnFrameRate() {
        AppTheme.Transition slow = new AppTheme.Transition();
        AppTheme.Transition fast = new AppTheme.Transition();
        slow.update(false, 0);
        fast.update(false, 0);
        for (int millis = 10; millis <= 300; millis += 10) {
            fast.update(true, millis * 1_000_000L);
        }
        assertEquals(fast.update(true, 300_000_000), slow.update(true, 300_000_000), .000001);
    }
}
