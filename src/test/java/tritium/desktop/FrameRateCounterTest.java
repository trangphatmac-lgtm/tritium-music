package tritium.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRateCounterTest {
    @Test
    void measuresCompletedFramesAndIncludesStalls() {
        FrameRateCounter counter = new FrameRateCounter();
        counter.recordFrame(0);
        for (int i = 1; i < 30; i++) counter.recordFrame(i * 1_000_000_000L / 60);
        assertEquals(0, counter.fps()); // No complete sample yet.
        counter.recordFrame(500_000_000L);
        assertEquals(60, counter.fps());
        counter.recordFrame(1_500_000_000L); // A real one-second stall must lower the displayed FPS.
        assertEquals(1, counter.fps());
        for (int i = 1; i <= 30; i++) counter.recordFrame(1_500_000_000L + i * 1_000_000_000L / 60);
        assertEquals(60, counter.fps());
    }
}
