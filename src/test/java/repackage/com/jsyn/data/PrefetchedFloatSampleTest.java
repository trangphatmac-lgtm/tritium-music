package repackage.com.jsyn.data;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class PrefetchedFloatSampleTest {
    @Test void cachedStereoCueUsesNoSourceReadsAndOtherPositionsStillSeekCorrectly() {
        var source = new Source(100, 2);
        var sample = PrefetchedFloatSample.prepare(source, 20, 30, () -> false);
        assertEquals(100, sample.getNumFrames());
        assertEquals(48_000, sample.getFrameRate());
        assertEquals(2, sample.getChannelsPerFrame());
        assertEquals(60, source.reads);
        for (int i = 40; i < 100; i++) assertEquals((float) (i / 200.0), sample.readDouble(i));
        assertEquals(60, source.reads);
        assertEquals(.5, sample.readDouble(100)); // continues after the prefetched region
        assertEquals(.01, sample.readDouble(2)); // manual seek before the original cue
        assertEquals(62, source.reads);
        sample.cleanUp(); sample.cleanUp();
        assertEquals(1, source.closes);
        assertEquals(0, sample.readDouble(40));
    }

    @Test void preparationClipsAtEofAndKeepsAnExplicitMemoryBound() {
        var shortSource = new Source(100, 2);
        var tail = PrefetchedFloatSample.prepare(shortSource, 98, 50, () -> false);
        assertEquals(4, shortSource.reads);
        assertEquals((float) .995, tail.readDouble(199));
        assertEquals(4, shortSource.reads);
        var large = new Source(10_000_000, 2);
        var capped = PrefetchedFloatSample.prepare(large, 0, 10_000_000, () -> false);
        int max = PrefetchedFloatSample.MAX_CACHE_BYTES / Float.BYTES;
        assertEquals(max, large.reads);
        capped.readDouble(max - 1); assertEquals(max, large.reads);
        capped.readDouble(max); assertEquals(max + 1, large.reads);
    }

    @Test void cancelledPreparationLeavesSourceOwnershipWithCallerAndMemoryPcmIsReused() {
        var source = new Source(10_000, 2);
        assertThrows(CancellationException.class,
                () -> PrefetchedFloatSample.prepare(source, 0, 10_000, () -> source.reads >= 4096));
        assertEquals(4096, source.reads);
        assertEquals(0, source.closes);
        source.cleanUp(); assertEquals(1, source.closes);
        var resident = new FloatSample(500, 2);
        assertSame(resident, PrefetchedFloatSample.prepare(resident, 0, 500, () -> false));
    }

    private static class Source extends FloatSample {
        int reads, closes;
        Source(int frames, int channels) { numFrames = frames; channelsPerFrame = channels; setFrameRate(48_000); }
        @Override public double readDouble(int index) {
            assertTrue(index >= 0 && index < (long) numFrames * channelsPerFrame);
            reads++;
            return index / 200.0;
        }
        @Override public void cleanUp() { closes++; }
    }
}
