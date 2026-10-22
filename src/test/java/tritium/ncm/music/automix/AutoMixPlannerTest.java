package tritium.ncm.music.automix;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.automix.AutoMixPlan.Strategy.*;

class AutoMixPlannerTest {
    @Test void missingFeaturesUseFiveSecondSCurveWithMidpointMetadataSwitch() {
        AutoMixPlan plan = AutoMixPlanner.plan(SongFeatures.empty(1, 180_000), SongFeatures.empty(2, 190_000));
        assertEquals(CROSSFADE, plan.strategy());
        assertEquals(175_000, plan.startAMillis());
        assertEquals(0, plan.startBMillis());
        assertEquals(177_500, plan.switchAMillis());
        assertEquals(170_000, plan.prepareAMillis());
        assertEquals(1, plan.outgoingGain(175_000));
        assertEquals(0, plan.outgoingGain(180_000));
        assertEquals(.5, plan.incomingGain(2_500), 1e-9);
        assertEquals(0, plan.incomingHighPassHz(0));
    }

    @Test void shortInvalidAndExcludedTracksDoNotMix() {
        for (double length : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, 49_999 }) {
            assertNull(AutoMixPlanner.plan(SongFeatures.empty(1, length), SongFeatures.empty(2, 120_000)));
            assertNull(AutoMixPlanner.plan(SongFeatures.empty(1, 120_000), SongFeatures.empty(2, length)));
        }
        JsonObject data = material();
        data.addProperty("genre", "古典");
        assertNull(AutoMixPlanner.plan(features(data), SongFeatures.empty(2, 120_000)));
        data.addProperty("genre", "流行");
        data.add("genreIdList", new JsonParser().parse("[1044]"));
        assertNull(AutoMixPlanner.plan(features(data), SongFeatures.empty(2, 120_000)));
    }

    @Test void rhythmHasPriorityAndUsesRealDownbeats() {
        JsonObject a = material(), b = material();
        AutoMixPlan plan = AutoMixPlanner.plan(features(a), features(b));
        assertEquals(RHYTHM, plan.strategy());
        assertEquals(0, plan.startAMillis() % 2_000);
        assertEquals(0, plan.startBMillis() % 2_000);
        assertTrue(plan.filter());
        assertTrue(plan.durationMillis() > 5_000);
    }

    @Test void tempoDifferenceUsesOneBarHandoffWithoutSpeedChange() {
        JsonObject b = material();
        b.addProperty("bpm", 126);
        AutoMixPlan plan = AutoMixPlanner.plan(features(material()), features(b));
        assertEquals(RHYTHM, plan.strategy());
        assertTrue(plan.durationMillis() <= 60_000d / 126 * 4);
    }

    @Test void repBpmCorrectsOctave() {
        JsonObject a = material();
        a.addProperty("bpm", 60);
        a.addProperty("repBpm", 120);
        assertEquals(120, features(a).bpm());
        assertEquals(RHYTHM, AutoMixPlanner.plan(features(a), features(material())).strategy());
    }

    @Test void vocalHandoffProtectsBothVocalBoundaries() {
        JsonObject a = material(), b = material();
        a.add("endSpectrum", array(-20, -100, -40, -20));
        b.add("startSpectrum", array(-20, -100, -40, -20));
        AutoMixPlan plan = AutoMixPlanner.plan(features(a), features(b));
        assertEquals(VOCAL, plan.strategy());
        assertTrue(plan.startAMillis() >= 112_000);
        assertTrue(plan.endBMillis() <= 8_000);
    }

    @Test void atmosphereThenAccompanimentUseKeyAndEnergy() {
        JsonObject a = instrumental(), b = instrumental();
        AutoMixPlan atmosphere = AutoMixPlanner.plan(features(a), features(b));
        assertEquals(ATMOSPHERE, atmosphere.strategy());
        b.addProperty("mode", "F#:Major");
        AutoMixPlan accompaniment = AutoMixPlanner.plan(features(a), features(b));
        assertEquals(ACCOMPANIMENT, accompaniment.strategy());
        assertTrue(accompaniment.durationMillis() < atmosphere.durationMillis());
    }

    @Test void silenceUsesActualBoundariesAndTwoSeconds() {
        JsonObject a = new JsonParser().parse("{\"silenceTime\":{\"s\":0,\"e\":117000}}").getAsJsonObject();
        JsonObject b = new JsonParser().parse("{\"silenceTime\":{\"s\":3000,\"e\":119000}}").getAsJsonObject();
        AutoMixPlan plan = AutoMixPlanner.plan(features(a), features(b));
        assertEquals(SILENCE, plan.strategy());
        assertEquals(115_000, plan.startAMillis());
        assertEquals(117_000, plan.endAMillis());
        assertEquals(3_000, plan.startBMillis());
        assertEquals(2_000, plan.durationMillis());
    }

    @Test void realAudioDurationOverridesCatalogueAndIsImmutable() {
        JsonObject raw = material();
        raw.addProperty("length_ms", 999_000);
        SongFeatures features = features(raw);
        raw.addProperty("bpm", 35);
        assertEquals(120, features.bpm());
        assertEquals(120_000, features.durationMillis());
        assertEquals(70_000, features.withDuration(70_000).durationMillis());
        assertEquals(120_000, features.durationMillis());
    }

    @Test void malformedAndOutOfBoundsMaterialNeverProducesUnsafeTimelines() {
        Random random = new Random(42);
        for (int i = 0; i < 300; i++) {
            JsonObject a = material(), b = material();
            a.addProperty("bpm", random.nextDouble() * 1_000 - 200);
            b.add("voiceTime", new JsonParser().parse(i % 2 == 0 ? "[]" : "null"));
            a.add("silenceTime", new JsonParser().parse("{\"s\":-10,\"e\":999999999}"));
            b.addProperty("mode", "invalid");
            AutoMixPlan plan = AutoMixPlanner.plan(features(a), features(b));
            if (plan == null) continue;
            assertTrue(plan.startAMillis() >= 60_000);
            assertTrue(plan.startBMillis() >= 0 && plan.startBMillis() <= 20_000);
            assertTrue(plan.endAMillis() <= 120_000);
            assertTrue(plan.endBMillis() <= 120_000);
            for (int step = -1; step <= 11; step++) {
                double aTime = plan.startAMillis() + step * plan.durationMillis() / 10;
                double bTime = plan.startBMillis() + step * plan.durationMillis() / 10;
                assertTrue(plan.outgoingGain(aTime) >= 0 && plan.outgoingGain(aTime) <= 1);
                assertTrue(plan.incomingGain(bTime) >= 0 && plan.incomingGain(bTime) <= 1);
            }
        }
    }

    @Test void curveEndpointsAndFiltersRestoreSignal() {
        AutoMixPlan plan = new AutoMixPlan(RHYTHM, 100_000, 2_000, 110_000, 105_000,
                AutoMixPlan.Curve.EXP, AutoMixPlan.Curve.LOG, true);
        assertEquals(0, plan.outgoingLowPassHz(99_999));
        assertEquals(0, plan.outgoingLowPassHz(100_000));
        assertEquals(0, plan.outgoingHighPassHz(99_999));
        assertEquals(20, plan.outgoingHighPassHz(100_000));
        assertEquals(20_000, plan.outgoingHighPassHz(110_000), 1e-9);
        assertEquals(500, plan.incomingLowPassHz(2_000));
        assertEquals(0, plan.incomingHighPassHz(2_000));
        assertEquals(0, plan.incomingLowPassHz(12_000));
        assertEquals(0, plan.incomingHighPassHz(12_000));
        AutoMixPlan atmosphere = new AutoMixPlan(ATMOSPHERE, 100_000, 2_000, 110_000, 105_000,
                AutoMixPlan.Curve.S, AutoMixPlan.Curve.S, true);
        assertEquals(20_000, atmosphere.outgoingLowPassHz(100_000));
        assertEquals(20, atmosphere.outgoingLowPassHz(110_000), 1e-9);
        assertEquals(0, atmosphere.outgoingHighPassHz(105_000));
        assertEquals(20_000, atmosphere.incomingHighPassHz(2_000));
        assertEquals(0, atmosphere.incomingHighPassHz(12_000));
        assertEquals(0, atmosphere.incomingLowPassHz(3_000));
        assertThrows(IllegalArgumentException.class, () -> new AutoMixPlan(CROSSFADE,
                100, 0, 100, 100, AutoMixPlan.Curve.S, AutoMixPlan.Curve.S, false));
        for (AutoMixPlan.Curve curve : AutoMixPlan.Curve.values()) {
            assertEquals(0, curve.value(-10));
            assertEquals(1, curve.value(10));
            assertEquals(0, curve.value(Double.NaN));
        }
    }

    @Test void keysSupportOfficialFlatAndSharpSpelling() {
        assertTrue(AutoMixPlanner.compatibleKeys("bG:Major", "F#:Major"));
        assertTrue(AutoMixPlanner.compatibleKeys("#F:Minor", "F#:Minor"));
        assertTrue(AutoMixPlanner.compatibleKeys("C:Major", "A:Minor"));
        assertFalse(AutoMixPlanner.compatibleKeys("C:Major", "F#:Major"));
        assertFalse(AutoMixPlanner.compatibleKeys("Unknown", "Unknown"));
    }

    private static SongFeatures features(JsonObject object) { return SongFeatures.fromJson(1, 120_000, object); }
    static JsonObject material() {
        JsonObject object = new JsonParser().parse("""
                {"songId":1,"version":1,"genre":"流行","bpm":120,"mode":"C:Major",
                "voiceTime":{"s":8000,"e":112000},"drumTime":{"s":0,"e":118000},
                "silenceTime":{"s":0,"e":120000},
                "startSpectrum":[-20,-20,-30,-20],"endSpectrum":[-20,-20,-30,-20],
                "startAccEnergy":[-20,-30,-20,-20],"endAccEnergy":[-20,-30,-20,-20],
                "paragraph":[{"s":0,"e":8000},{"s":112000,"e":120000}],
                "beat":{"beatsPerBar":4,"beatFirst":1}}
                """).getAsJsonObject();
        object.getAsJsonObject("beat").addProperty("downbeatCompressed",
                IntStream.rangeClosed(0, 240).mapToObj(i -> String.valueOf(i * 500)).collect(Collectors.joining(",")));
        return object;
    }
    private static JsonObject instrumental() {
        JsonObject object = material();
        object.add("startSpectrum", array(-100, -100, -40, -20));
        object.add("endSpectrum", array(-100, -100, -40, -20));
        object.add("voiceTime", new JsonParser().parse("{\"s\":-1,\"e\":-1}"));
        return object;
    }
    private static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) array.add(new com.google.gson.JsonPrimitive(value));
        return array;
    }
}
