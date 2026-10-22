package tritium.ncm.music.automix;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.automix.AutoMixAutomation.*;
import static tritium.ncm.music.automix.AutoMixAutomation.FilterType.*;

class AutoMixAutomationTest {
    @Test void volumeHoldsLeftSampleAndIncludesExactNonGridEndpoint() {
        var in = new Gain(123, 250, AutoMixPlan.Curve.LINEAR, true, true);
        var out = new Gain(123, 250, AutoMixPlan.Curve.LINEAR, false, true);
        assertEquals(4, in.sampleCount());
        assertEquals(0, in.valueAt(172.999));
        assertEquals(50.0 / 127, in.valueAt(173), 1e-12);
        assertEquals(100.0 / 127, in.valueAt(249.999), 1e-12);
        assertEquals(1, in.valueAt(250));
        assertEquals(0, out.valueAt(250));
        assertEquals(1, out.valueAt(0));
        assertEquals(1, in.valueAt(Double.POSITIVE_INFINITY));
        var held = new Gain(0, 1000, AutoMixPlan.Curve.S, false, false);
        assertEquals(1, held.valueAt(1000));
        assertEquals(0, held.sampleCount());
    }

    @Test void endpointEqUsesSeparateEightyMillisecondClockAndOnlyItsWindow() {
        var eq = new EqSweep(1000, 2000, LOW_PASS, 4000, 20000);
        assertEquals(0, eq.frequencyAt(999));
        assertEquals(4000, eq.frequencyAt(1079.99));
        assertEquals(4000 * Math.pow(5, .08), eq.frequencyAt(1080), 1e-8);
        assertEquals(20000, eq.frequencyAt(2000));
        assertEquals(0, eq.frequencyAt(2000.01));
        assertEquals(0, eq.frequencyAt(Double.NaN));
    }

    @Test void legacyEqUsesPointIndexAndMergesShortFinalGap() {
        // [0, 300, 600, 900, 1000] -> [0, 300, 600, 1000]
        var eq = EqSweep.legacy(0, 1000, HIGH_PASS, 20, 20000);
        assertEquals(300, eq.sampleMillis());
        assertEquals(20, eq.frequencyAt(299.99));
        assertEquals(200, eq.frequencyAt(300), 1e-8);
        assertEquals(2000, eq.frequencyAt(999.99), 1e-8);
        assertEquals(20000, eq.frequencyAt(1000));
        assertEquals(500, EqSweep.legacy(0, 4500, HIGH_PASS, 20, 20000).sampleMillis());
        // For a 100 ms interval the reference deletes the start point; before the endpoint it bypasses.
        var shortEq = EqSweep.legacy(1000, 1100, HIGH_PASS, 1000, 20);
        assertEquals(0, shortEq.frequencyAt(1000));
        assertEquals(0, shortEq.frequencyAt(1099.99));
        assertEquals(20, shortEq.frequencyAt(1100));
        assertEquals(1000, EqSweep.legacy(1000, 1200, HIGH_PASS, 1000, 20).frequencyAt(1000));
    }

    @Test void orderedStagesKeepTheirSlotsWhenAnEarlierSweepEnds() {
        var mutable = new ArrayList<>(List.of(new EqSweep(0, 1000, LOW_PASS, 2000, 2000),
                new EqSweep(1000, 2000, LOW_PASS, 2000, 20000)));
        var deck = new Deck(new Gain(0, 2000, AutoMixPlan.Curve.S, true, true), mutable);
        mutable.clear();
        assertEquals(2, deck.filters().size());
        assertEquals(List.of(new FilterSetting(LOW_PASS, 0), new FilterSetting(LOW_PASS, 2000)), deck.filtersAt(1001));
        assertThrows(UnsupportedOperationException.class, () -> deck.filters().clear());
        assertThrows(IllegalArgumentException.class, () -> new Gain(0, Double.NaN, AutoMixPlan.Curve.S, true, true));
        assertThrows(IllegalArgumentException.class, () -> new FilterSetting(LOW_PASS, Double.POSITIVE_INFINITY));
    }
}
