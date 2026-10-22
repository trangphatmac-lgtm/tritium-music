package tritium.desktop;

import org.junit.jupiter.api.Test;
import tritium.i18n.I18n;
import tritium.i18n.Language;
import tritium.ncm.music.automix.AutoMixDebugSnapshot;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.automix.AutoMixAutomation;
import tritium.ncm.music.automix.SongFeatures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoMixDebugOverlayTest {
    @Test void formatsCompleteAndMissingDiagnosticsInEveryLanguageWithoutInventedValues() {
        String previous = DesktopAppState.preferences().language().getValue();
        try {
            for (Language language : Language.values()) {
                DesktopAppState.preferences().language().setValue(language.tag());
                String full = text(example());
                assertTrue(full.contains("176.000s"));
                assertTrue(full.contains("120.0"));
                assertTrue(full.contains("500 Hz"));
                assertTrue(full.contains("15.0%"));
                assertFalse(full.contains("autoMixDebug."));
                assertFalse(full.contains("NaN"));
                String advanced = text(fullExample());
                assertTrue(advanced.contains("50 ms"));
                assertTrue(advanced.contains("80 ms"));
                assertTrue(advanced.contains("accompaniment_v2"));
                assertTrue(advanced.contains("175.000s"));
                assertTrue(advanced.contains("EQ2 HP"));
                assertFalse(advanced.contains("autoMixDebug."));
                assertFalse(advanced.contains("NaN"));
                String empty = text(AutoMixDebugSnapshot.idle());
                assertFalse(empty.contains("NaN"));
                assertFalse(empty.contains("Infinity"));
                assertTrue(empty.contains(I18n.text("autoMixDebug.featuresPending", "A")));
                for (var status : AutoMixDebugSnapshot.Status.values()) {
                    assertFalse(I18n.text("autoMixDebug.status." + status).startsWith("autoMixDebug."));
                }
                for (var strategy : AutoMixPlan.Strategy.values()) {
                    assertFalse(I18n.text("autoMixDebug.strategy." + strategy).startsWith("autoMixDebug."));
                }
            }
        } finally { DesktopAppState.preferences().language().setValue(previous); }
    }

    static AutoMixDebugSnapshot example() {
        long now = 1_792_634_123_456L;
        var feature = new SongFeatures.Summary(true, 180_000, 120, "C:Major", "电子 / Electronic", 90,
                500, 179_000, 10_000, 174_000, false);
        return new AutoMixDebugSnapshot(now, AutoMixDebugSnapshot.Status.MIXING, "", "Sequential", "LOSSLESS", 3,
                new AutoMixDebugSnapshot.Track(1234567, "测试歌曲 A / Test track A"),
                new AutoMixDebugSnapshot.Track(7654321, "测试歌曲 B / Test track B"),
                new AutoMixDebugSnapshot.Voice(176_000, 180_000, .3, .5, 0, 500, false, false),
                new AutoMixDebugSnapshot.Voice(3_000, 180_000, .3, .5, 4_000, 0, false, false),
                feature, feature,
                new AutoMixPlan(AutoMixPlan.Strategy.RHYTHM, 174_000, 1_000, 179_000, 176_500,
                        AutoMixPlan.Curve.EXP, AutoMixPlan.Curve.LOG, true), true, false,
                List.of(new AutoMixDebugSnapshot.Event(now, AutoMixDebugSnapshot.Status.MIXING, ""),
                        new AutoMixDebugSnapshot.Event(now - 2_000, AutoMixDebugSnapshot.Status.READY, ""),
                        new AutoMixDebugSnapshot.Event(now - 4_000, AutoMixDebugSnapshot.Status.DECODING, ""),
                        new AutoMixDebugSnapshot.Event(now - 5_000, AutoMixDebugSnapshot.Status.AUDIO, ""),
                        new AutoMixDebugSnapshot.Event(now - 8_000, AutoMixDebugSnapshot.Status.FEATURES, "")));
    }

    static AutoMixDebugSnapshot fullExample() {
        var basic = example();
        var ga = new AutoMixAutomation.Gain(174_000, 179_000, AutoMixPlan.Curve.LINEAR, false, false);
        var gb = new AutoMixAutomation.Gain(1_000, 5_000, AutoMixPlan.Curve.LOG, true, true);
        var fa = List.of(new AutoMixAutomation.EqSweep(174_000, 179_000, AutoMixAutomation.FilterType.LOW_PASS, 16000, 20));
        var fb = List.of(new AutoMixAutomation.EqSweep(1000, 5000, AutoMixAutomation.FilterType.LOW_PASS, 4000, 20000),
                new AutoMixAutomation.EqSweep(1000, 5000, AutoMixAutomation.FilterType.HIGH_PASS, 4000, 20));
        var automation = new AutoMixAutomation(175_000, new AutoMixAutomation.Deck(ga, fa),
                new AutoMixAutomation.Deck(gb, fb), "accompaniment_v2");
        var plan = new AutoMixPlan(AutoMixPlan.Strategy.ACCOMPANIMENT, 174_000, 1000, 179_000, 176_500,
                ga.curve(), gb.curve(), true, automation);
        return new AutoMixDebugSnapshot(basic.capturedAtMillis(), basic.status(), basic.detail(), basic.mode(), basic.quality(), basic.revision(),
                basic.a(), basic.b(),
                new AutoMixDebugSnapshot.Voice(176_000, 180_000, .3, .5, 1100, 0, false, false, automation.a().filtersAt(176_000)),
                new AutoMixDebugSnapshot.Voice(2000, 180_000, .3, .5, 5890, 1120, false, false, automation.b().filtersAt(2000)),
                basic.featuresA(), basic.featuresB(), plan, true, false, basic.events());
    }

    private String text(AutoMixDebugSnapshot snapshot) {
        return String.join("\n", AutoMixDebugOverlay.describe(snapshot, true).stream().map(AutoMixDebugOverlay.Line::text).toList());
    }
}
