package tritium.ncm.music.automix;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.automix.AutoMixAutomation.FilterType.*;
import static tritium.ncm.music.automix.AutoMixPlan.Curve.*;
import static tritium.ncm.music.automix.AutoMixPlan.Strategy.*;

class FullAutoMixPlannerTest {
    @Test void publicPlannerOptInKeepsBasicDefaultsAndExclusions() {
        var a = SongFeatures.empty(1, 120_000);
        var b = SongFeatures.empty(2, 120_000);
        assertFalse(AutoMixPlanner.plan(a, b, false).fullMode());
        var full = AutoMixPlanner.plan(a, b, true);
        assertTrue(full.fullMode());
        assertEquals(CROSSFADE, full.strategy());
        assertEquals("bounded_crossfade", full.automation().branch());
        assertNull(AutoMixPlanner.plan(a.withDuration(10_000), b, true));
    }

    @Test void harmonicVocalKeepsIndependentWindowsAndStartsBOnItsOwnClock() {
        var a = features(material(120, "C:Major", 8_000, 110_000, 0, 120_000));
        var b = features(material(130, "C:Major", 16_000, 112_000, 1_000, 120_000));
        var plan = FullAutoMixPlanner.plan(a, b, basic(VOCAL));
        var automation = plan.automation();
        assertEquals("key_compatible_bpm_matched", automation.branch());
        assertEquals(106_800, automation.a().gain().startMillis());
        assertEquals(114_800, automation.a().gain().endMillis());
        assertEquals(11_569, automation.b().gain().startMillis());
        assertEquals(18_954, automation.b().gain().endMillis());
        assertEquals(105_569, automation.incomingStartAMillis());
        assertEquals(105_569, plan.startAMillis());
        assertEquals(110_000, plan.switchAMillis());
        assertEquals(1, plan.outgoingGain(plan.startAMillis()));
        assertEquals(0, plan.incomingGain(plan.startBMillis()));
        assertEquals(1, plan.incomingGain(18_954));
    }

    @Test void accompanimentUsesThreeQuarterEntryAndOverlappingHighAndLowPass() {
        var a = features(material(120, "C:Major", 8_000, 110_000, 0, 119_000));
        var b = features(material(120, "C:Major", 8_000, 112_000, 500, 120_000));
        var plan = FullAutoMixPlanner.plan(a, b, basic(ACCOMPANIMENT));
        var automation = plan.automation();
        assertEquals("accompaniment_v2", automation.branch());
        assertEquals(112_000, plan.startAMillis());
        assertEquals(119_000, plan.endAMillis());
        assertEquals(5_375, plan.startBMillis());
        assertEquals(10_625, automation.b().gain().endMillis());
        assertEquals(113_750, automation.incomingStartAMillis());
        assertEquals(115_500, plan.switchAMillis());
        assertFalse(automation.a().gain().enabled());
        assertEquals(1, plan.outgoingGain(118_000));
        assertEquals(List.of(LOW_PASS, HIGH_PASS), automation.b().filters().stream().map(AutoMixAutomation.EqSweep::type).toList());
        assertEquals(4_000, automation.b().filters().get(0).fromHz());
        assertEquals(20_000, automation.b().filters().get(0).toHz());
        assertEquals(4_000, automation.b().filters().get(1).fromHz());
        assertEquals(20, automation.b().filters().get(1).toHz());
    }

    @Test void accompanimentSwitchesOutgoingFilterWhenBassDifferenceExceedsSixDb() {
        JsonObject a = material(120, "C:Major", 8_000, 110_000, 0, 119_000);
        JsonObject b = material(120, "C:Major", 8_000, 112_000, 500, 120_000);
        a.add("endAccEnergy", array(-30, -10, -30));
        b.add("startAccEnergy", array(-30, -17, -30));
        var filter = FullAutoMixPlanner.plan(features(a), features(b), basic(ACCOMPANIMENT)).automation().a().filters().get(0);
        assertEquals(HIGH_PASS, filter.type());
        assertEquals(20, filter.fromHz());
        assertEquals(16_000, filter.toHz());
        b.add("startAccEnergy", array(-30, -16, -30));
        assertEquals(LOW_PASS, FullAutoMixPlanner.plan(features(a), features(b), basic(ACCOMPANIMENT))
                .automation().a().filters().get(0).type());
    }

    @Test void incompatibleKeysKeepEqualWindowsAndSequentialLowPassStages() {
        var a = features(material(120, "C:Major", 8_000, 110_000, 0, 120_000));
        var b = features(material(100, "F#:Major", 16_000, 112_000, 1_000, 120_000));
        var plan = FullAutoMixPlanner.plan(a, b, basic(VOCAL));
        var automation = plan.automation();
        assertEquals("key_incompatible", automation.branch());
        assertEquals(4_800, automation.a().gain().endMillis() - automation.a().gain().startMillis());
        assertEquals(4_800, automation.b().gain().endMillis() - automation.b().gain().startMillis());
        assertEquals(S, plan.incomingCurve());
        assertEquals(109_600, automation.incomingStartAMillis());
        assertEquals(112_000, plan.switchAMillis());
        var first = automation.b().filters().get(0);
        var second = automation.b().filters().get(1);
        assertEquals(first.endMillis(), second.startMillis());
        assertEquals(11_200, first.startMillis());
        assertEquals(13_100, first.endMillis());
        assertEquals(14_100, second.endMillis());
        assertEquals(2_000, first.fromHz());
        assertEquals(2_000, first.toHz());
        assertEquals(20_000, second.toHz());
    }

    @Test void mismatchedTempoWithDrumGapUsesEqualDurationAndBandEntry() {
        JsonObject a = material(120, "C:Major", 8_000, 110_000, 0, 120_000);
        JsonObject b = material(90, "C:Major", 16_000, 112_000, 1_000, 120_000);
        a.getAsJsonObject("drumTime").addProperty("e", 115_000);
        var plan = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        var automation = plan.automation();
        assertEquals("long_drum_gap_bpm_mismatch", automation.branch());
        assertEquals(8_000, automation.a().gain().endMillis() - automation.a().gain().startMillis());
        assertEquals(8_000, automation.b().gain().endMillis() - automation.b().gain().startMillis());
        assertEquals(LOG, plan.incomingCurve());
        assertEquals(2, automation.b().filters().size());
    }

    @Test void energyBranchesKeepTheirIndependentCapsAndCurveRatios() {
        JsonObject a = material(120, "C:Major", 8_000, 110_000, 0, 120_000);
        JsonObject b = material(90, "C:Major", 16_000, 112_000, 1_000, 120_000);
        a.add("endAccEnergy", array(-10, -30, -30));
        var louderA = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("a_energy_higher", louderA.automation().branch());
        assertEquals(EXP, louderA.incomingCurve());
        assertEquals(106_800, louderA.automation().a().gain().startMillis());
        assertEquals(9_600, louderA.automation().b().gain().startMillis());
        a.add("endAccEnergy", array(-30, -30, -30));
        b.add("startAccEnergy", array(-10, -30, -30));
        var louderB = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("b_energy_higher", louderB.automation().branch());
        assertEquals(LINEAR, louderB.outgoingCurve());
        assertEquals(LINEAR, louderB.incomingCurve());
        assertEquals(109_200, louderB.automation().a().gain().startMillis());
        assertEquals(113_200, louderB.automation().a().gain().endMillis());
        b.add("startAccEnergy", array(-30, -30, -30));
        var similar = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("default_quick_cut", similar.automation().branch());
        assertEquals(108_400, similar.automation().a().gain().startMillis());
    }

    @Test void strongMiddleBandOverridesBassCutoffAndKeepsLegacyEqProtocol() {
        JsonObject a = material(120, "C:Major", 8_000, 110_000, 0, 120_000);
        JsonObject b = material(130, "C:Major", 16_000, 112_000, 1_000, 120_000);
        a.add("endAccEnergy", array(-30, -15, -10));
        b.add("startAccEnergy", array(-30, -15, -10));
        var plan = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        var filter = plan.automation().a().filters().get(0);
        assertEquals(AutoMixAutomation.EqSweep.legacy(110_000, 114_800, HIGH_PASS, 20, 2_000), filter);
        assertEquals(2_000, plan.automation().b().filters().get(0).fromHz());
    }

    @Test void invalidOrMissingV2MaterialKeepsTheBoundedSampledPlan() {
        JsonObject a = material(120, "C:Major", 8_000, 110_000, 0, 120_000);
        JsonObject b = material(120, "C:Major", 16_000, 112_000, 1_000, 120_000);
        a.remove("bpm");
        var missing = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("fallback_missing_bpm", missing.automation().branch());
        assertEquals(114_000, missing.startAMillis());
        a.addProperty("bpm", 120);
        a.getAsJsonObject("silenceTime").addProperty("e", 999_999);
        var invalid = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("fallback_invalid_silence", invalid.automation().branch());
        a = material(30, "C:Major", 8_000, 119_000, 0, 120_000);
        b = material(30, "F#:Major", 500, 112_000, 0, 120_000);
        a.getAsJsonObject("beat").addProperty("beatsPerBar", 16);
        b.getAsJsonObject("beat").addProperty("beatsPerBar", 16);
        // Two very slow long bars exceed the permitted window; no negative/overrun EQ stages are emitted.
        var outOfBounds = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals("fallback_invalid_v2_window", outOfBounds.automation().branch());
        assertEquals(119_000, outOfBounds.endAMillis());
    }

    @Test void realDownbeatsAlignVocalKeypointsLaterForAAndEarlierForB() {
        JsonObject a = material(120, "C:Major", 8_000, 110_100, 0, 120_000);
        JsonObject b = material(120, "C:Major", 15_900, 112_000, 1_000, 120_000);
        String beats = "100000,100500,101000,101500,102000,102500,103000,103500,104000,104500,105000,105500,106000,106500,107000,107500,108000,108500,109000,109500,110000,110500,111000,111500,112000";
        a.getAsJsonObject("beat").addProperty("downbeatCompressed", beats);
        b.getAsJsonObject("beat").addProperty("downbeatCompressed", "12000,12500,13000,13500,14000,14500,15000,15500,16000");
        var plan = FullAutoMixPlanner.plan(features(a), features(b), basic(VOCAL));
        assertEquals(112_000, plan.switchAMillis());
        assertEquals(9_200, plan.automation().b().gain().startMillis());
    }

    @Test void otherStrategiesRetainTheirWindowsAndSelectedFilterRoles() {
        var a = SongFeatures.empty(1, 120_000);
        var b = SongFeatures.empty(2, 120_000);
        for (var strategy : List.of(RHYTHM, ATMOSPHERE, SILENCE, CROSSFADE)) {
            var basic = basic(strategy);
            var full = FullAutoMixPlanner.plan(a, b, basic);
            assertEquals(strategy, full.strategy());
            assertEquals(basic.startAMillis(), full.startAMillis());
            assertEquals(basic.startBMillis(), full.startBMillis());
            assertEquals(basic.endAMillis(), full.endAMillis());
            assertEquals(basic.switchAMillis(), full.switchAMillis());
            assertEquals("bounded_" + strategy.name().toLowerCase(java.util.Locale.ROOT), full.automation().branch());
            if (strategy == RHYTHM) {
                assertEquals(HIGH_PASS, full.automation().a().filters().get(0).type());
                assertEquals(LOW_PASS, full.automation().b().filters().get(0).type());
            } else if (strategy == ATMOSPHERE) {
                assertEquals(LOW_PASS, full.automation().a().filters().get(0).type());
                assertEquals(HIGH_PASS, full.automation().b().filters().get(0).type());
            } else assertTrue(full.automation().a().filters().isEmpty());
        }
        assertNull(FullAutoMixPlanner.plan(a, b, null));
    }

    private static AutoMixPlan basic(AutoMixPlan.Strategy strategy) {
        return new AutoMixPlan(strategy, 114_000, 2_000, 119_000, 116_500, S, S,
                strategy != SILENCE && strategy != CROSSFADE);
    }
    private static SongFeatures features(JsonObject object) { return SongFeatures.fromJson(1, 120_000, object); }
    private static JsonObject material(double bpm, String mode, double vocalStart, double vocalEnd,
                                       double silenceStart, double silenceEnd) {
        JsonObject object = AutoMixPlannerTest.material();
        object.addProperty("bpm", bpm);
        object.addProperty("mode", mode);
        object.getAsJsonObject("voiceTime").addProperty("s", vocalStart);
        object.getAsJsonObject("voiceTime").addProperty("e", vocalEnd);
        object.getAsJsonObject("drumTime").addProperty("s", vocalStart - 1_000);
        object.getAsJsonObject("drumTime").addProperty("e", vocalEnd + 1_000);
        object.getAsJsonObject("silenceTime").addProperty("s", silenceStart);
        object.getAsJsonObject("silenceTime").addProperty("e", silenceEnd);
        object.getAsJsonObject("beat").remove("downbeatCompressed");
        object.remove("paragraph");
        object.add("endAccEnergy", array(-30, -30, -30));
        object.add("startAccEnergy", array(-30, -30, -30));
        return object;
    }
    private static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) array.add(new com.google.gson.JsonPrimitive(value));
        return array;
    }
}
