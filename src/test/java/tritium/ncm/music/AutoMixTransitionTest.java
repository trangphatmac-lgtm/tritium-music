package tritium.ncm.music;

import org.junit.jupiter.api.Test;
import tritium.ncm.music.automix.AutoMixPlan;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AutoMixTransitionTest {
    private static AutoMixPlan plan(boolean filter) {
        return new AutoMixPlan(AutoMixPlan.Strategy.RHYTHM, 95_000, 2_000,
                100_000, 97_500, AutoMixPlan.Curve.LINEAR, AutoMixPlan.Curve.LINEAR, filter);
    }

    @Test
    void startsIncomingAtItsCueWithZeroGainAndNoSpectrumOwnership() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(false));

        mix.start(.4f);

        assertEquals(2_000, b.position);
        assertEquals(0, b.gainAtStart);
        assertEquals(.4f, b.volumeAtStart);
        assertFalse(b.analysisAtStart);
        assertFalse(b.paused);
        assertTrue(a.analysis);
        assertSame(a, mix.current());
        assertEquals(0, a.starts);
    }

    @Test
    void usesEachDeckMediaPositionAndSwitchesMetadataExactlyOnce() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(true));
        AtomicInteger switches = new AtomicInteger();
        mix.start(.4f);

        a.position = 96_000;
        b.position = 3_500;
        mix.tick(switches::incrementAndGet);
        assertEquals(.8 / 1.1, a.gain, 1e-9);
        assertEquals(.3 / 1.1, b.gain, 1e-9, "Incoming gain follows B's cue-relative clock, with shared headroom");
        assertTrue(a.highPass > 0);
        assertTrue(b.lowPass > 0);
        assertEquals(0, switches.get());
        for (int i = 0; i < 100; i++) mix.tick(switches::incrementAndGet);
        assertEquals(.8 / 1.1, a.gain, 1e-9, "Scheduler ticks alone must not advance a fade");
        assertEquals(.3 / 1.1, b.gain, 1e-9);

        a.position = 97_500;
        b.position = 4_500;
        mix.tick(switches::incrementAndGet);
        assertSame(b, mix.current());
        assertTrue(mix.hasSwitched());
        assertFalse(a.analysis);
        assertTrue(b.analysis);
        mix.tick(switches::incrementAndGet);
        assertEquals(1, switches.get());
        assertEquals(0, a.closes, "Old track continues its tail after the UI switch");
        assertFalse(mix.isComplete());
    }

    @Test
    void completingTheOutgoingTimelineDisposesItsTailAndRestoresIncoming() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(true));
        AtomicInteger switches = new AtomicInteger();
        mix.start(.4f);
        a.position = 100_000;
        b.position = 6_800;
        mix.tick(switches::incrementAndGet);

        assertTrue(mix.isComplete());
        assertSame(b, mix.current());
        assertEquals(1, switches.get());
        assertEquals(1, a.closes);
        assertEquals(0, b.closes);
        assertEquals(1, b.gain);
        assertEquals(0, b.lowPass);
        assertEquals(0, b.highPass);
        mix.tick(switches::incrementAndGet);
        mix.cancel();
        assertEquals(1, a.closes);
        assertEquals(1, switches.get());
    }

    @Test
    void incomingEndCanCompleteTheTransitionWhenClocksDiffer() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(false));
        AtomicInteger switches = new AtomicInteger();
        mix.start(.4f);
        a.position = 97_000;
        b.position = 7_000;
        mix.tick(switches::incrementAndGet);

        assertTrue(mix.isComplete());
        assertTrue(mix.hasSwitched());
        assertEquals(1, switches.get());
        assertEquals(1, a.closes);
        assertEquals(1, b.gain);
    }

    @Test
    void pausesBothDecksAndResumesWithoutConsumingTheFade() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(false));
        AtomicInteger switches = new AtomicInteger();
        mix.start(.4f);
        a.advance(1_000);
        b.advance(1_000);
        mix.tick(switches::incrementAndGet);
        mix.pause();
        assertTrue(a.paused);
        assertTrue(b.paused);
        a.advance(10_000);
        b.advance(10_000);
        for (int i = 0; i < 100; i++) mix.tick(switches::incrementAndGet);
        assertEquals(.8, a.gain, 1e-9);
        assertEquals(.2, b.gain, 1e-9);
        assertEquals(0, switches.get());
        assertFalse(mix.isComplete());

        mix.resume();
        assertFalse(a.paused);
        assertFalse(b.paused);
        a.advance(1_500);
        b.advance(1_500);
        mix.tick(switches::incrementAndGet);
        assertEquals(.5, a.gain, 1e-9);
        assertEquals(.5, b.gain, 1e-9);
        assertEquals(1, switches.get());
    }

    @Test
    void cancelBeforeMetadataSwitchKeepsOutgoingAndCancelsIncoming() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(true));
        mix.start(.4f);
        a.position = 96_000;
        b.position = 3_000;
        mix.tick(() -> fail("Not yet at the metadata switch"));
        mix.pause();
        mix.cancel();

        assertSame(a, mix.current());
        assertTrue(mix.isComplete());
        assertFalse(mix.hasSwitched());
        assertEquals(0, a.closes);
        assertEquals(1, b.closes);
        assertEquals(1, a.gain);
        assertEquals(0, a.lowPass);
        assertEquals(0, a.highPass);
        assertTrue(a.analysis);
        assertTrue(a.paused, "Cancelling must not resume a paused current track");
    }

    @Test
    void cancelAfterMetadataSwitchKeepsIncomingAndDisposesOutgoing() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(true));
        mix.start(.4f);
        a.position = 97_500;
        b.position = 4_500;
        mix.tick(() -> { });
        mix.cancel();

        assertSame(b, mix.current());
        assertTrue(mix.isComplete());
        assertEquals(1, a.closes);
        assertEquals(0, b.closes);
        assertEquals(1, b.gain);
        assertEquals(0, b.lowPass);
        assertEquals(0, b.highPass);
        assertTrue(b.analysis);
        assertFalse(b.paused);
    }

    @Test
    void masterVolumeChangesReachBothDecksWithoutReplacingMixGains() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(false));
        mix.start(.4f);
        a.position = 96_000;
        b.position = 3_000;
        mix.tick(() -> { });
        mix.setVolume(.25f);

        assertEquals(.25f, a.volume);
        assertEquals(.25f, b.volume);
        assertEquals(.8, a.gain, 1e-9);
        assertEquals(.2, b.gain, 1e-9);
        assertEquals(.20, a.volume * a.gain, 1e-9);
        assertEquals(.05, b.volume * b.gain, 1e-9);
        mix.setVolume(0);
        assertEquals(0, a.volume * a.gain);
        assertEquals(0, b.volume * b.gain);
    }

    @Test
    void naturalFinishHandlesAnOutgoingClockThatHasWrappedToZero() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixTransition mix = new AutoMixTransition(a, b, plan(false));
        AtomicInteger switches = new AtomicInteger();
        mix.start(.4f);
        a.position = 0;
        a.finished = true;
        a.paused = true;
        b.position = 7_000;
        mix.tick(switches::incrementAndGet);

        assertEquals(1, switches.get());
        assertTrue(mix.isComplete());
        assertSame(b, mix.current());
        assertEquals(1, a.closes);
    }

    @Test
    void asymmetricExpAndLogCurvesPreserveHeadroomAcrossTheOverlap() {
        FakeDeck a = new FakeDeck(95_000, false);
        FakeDeck b = new FakeDeck(0, true);
        AutoMixPlan curved = new AutoMixPlan(AutoMixPlan.Strategy.VOCAL, 95_000, 2_000,
                100_000, 97_500, AutoMixPlan.Curve.EXP, AutoMixPlan.Curve.LOG, false);
        AutoMixTransition mix = new AutoMixTransition(a, b, curved);
        mix.start(1);
        for (int elapsed = 0; elapsed < 5_000; elapsed += 125) {
            a.position = 95_000 + elapsed;
            b.position = 2_000 + elapsed;
            mix.tick(() -> { });
            assertTrue(a.gain >= 0 && b.gain >= 0);
            assertTrue(a.gain + b.gain <= 1 + 1e-9,
                    "Two full-scale correlated signals must fit the output headroom");
            if (elapsed == 2_500) {
                assertEquals(.5, a.gain, 1e-9, "Both raw gains are 0.75 at the midpoint");
                assertEquals(.5, b.gain, 1e-9);
            }
        }
    }

    private static final class FakeDeck implements AutoMixTransition.Deck {
        float position;
        boolean paused;
        boolean finished;
        boolean analysis = true;
        double gain = 1;
        float volume = .4f;
        double lowPass;
        double highPass;
        int starts;
        int closes;
        double gainAtStart;
        float volumeAtStart;
        boolean analysisAtStart;

        FakeDeck(float position, boolean paused) {
            this.position = position;
            this.paused = paused;
        }

        void advance(float millis) { if (!paused && !finished) position += millis; }
        @Override public float getCurrentTimeMillis() { return position; }
        @Override public boolean isFinished() { return finished; }
        @Override public boolean isPausing() { return paused; }
        @Override public void startAt(float millis) {
            position = millis;
            gainAtStart = gain;
            volumeAtStart = volume;
            analysisAtStart = analysis;
            paused = false;
            starts++;
        }
        @Override public void setMixGain(double gain) { this.gain = gain; }
        @Override public void setMixFilter(double lowPassHz, double highPassHz) {
            lowPass = lowPassHz;
            highPass = highPassHz;
        }
        @Override public void setVolume(float volume) { this.volume = volume; }
        @Override public void setAnalysisEnabled(boolean enabled) { analysis = enabled; }
        @Override public void pause() { paused = true; }
        @Override public void unpause() { paused = false; }
        @Override public void close() { closes++; paused = true; finished = true; }
    }
}
