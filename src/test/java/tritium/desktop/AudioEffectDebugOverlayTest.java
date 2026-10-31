package tritium.desktop;

import org.junit.jupiter.api.Test;
import tritium.i18n.I18n;
import tritium.i18n.Language;
import tritium.ncm.music.effects.AudioEffectDebugSnapshot;
import tritium.ncm.music.effects.AudioEffectDsp;
import tritium.ncm.music.effects.AudioEffectService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AudioEffectDebugOverlayTest {
    @Test void describesAllFourModesAndEmptyStatesInEveryLanguage() {
        String original = DesktopAppState.preferences().language().getValue();
        try {
            for (Language language : Language.values()) {
                DesktopAppState.preferences().language().setValue(language.tag());
                for (String mode : List.of("Off", "Smart", "Master", "Emotion", "Manual")) {
                    var snapshot = example(mode);
                    String text = text(snapshot);
                    assertTrue(text.contains(I18n.text("audioEffect.mode." + mode)));
                    assertTrue(text.contains("1234567"));
                    assertTrue(text.contains("48000.00 Hz"));
                    assertFalse(text.contains("audioEffectDebug."));
                    assertFalse(text.contains("audioEffect.status."));
                    assertFalse(text.contains("audioEffect.tone."));
                    assertFalse(text.contains("NaN"));
                    assertFalse(text.contains("Infinity"));
                    switch (mode) {
                        case "Smart" -> assertTrue(text.contains("soundKey"));
                        case "Master" -> assertTrue(text.contains("key_warm"));
                        case "Emotion" -> assertTrue(text.contains("default:pop-family"));
                        case "Manual" -> assertTrue(text.contains(I18n.text("settings.audioEffectBass")));
                    }
                    String empty = text(new AudioEffectDebugSnapshot(snapshot.selection(), List.of()));
                    assertTrue(empty.contains(I18n.text("audioEffect.status." + (mode.equals("Off") ? "off" : "ready"))));
                    assertFalse(empty.contains("48000"), "No invented DSP state without a deck");
                }
            }
        } finally { DesktopAppState.preferences().language().setValue(original); }
    }

    @Test void debugPreferencePersistsWithoutChangingEffectSelection() {
        MusicPreferences preferences = new MusicPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        assertFalse(preferences.audioEffectDebug().getValue());
        preferences.audioEffectDebug().setValue(true);
        assertEquals(1, changes.get());
        assertEquals("Off", preferences.audioEffects().mode().getValue());
        assertFalse(preferences.autoMixDebug().getValue());
        MusicPreferences restored = new MusicPreferences();
        restored.loadFromJsonObject(preferences.toJsonObject());
        assertTrue(restored.audioEffectDebug().getValue());
        assertEquals("Off", restored.audioEffects().mode().getValue());
    }

    @Test void simultaneousOverlaysHaveSeparateBoundsAtWideAndNarrowSizes() {
        for (double width : new double[]{440, 720, 760, 825, 1000, 1440}) {
            var mix = DebugOverlayLayout.area(width, 600, true, false);
            var effect = DebugOverlayLayout.area(width, 600, true, true);
            assertTrue(mix.x() + mix.width() <= effect.x() || mix.y() + mix.height() <= effect.y());
            for (var area : List.of(mix, effect)) {
                assertTrue(area.x() >= 12);
                assertTrue(area.x() + area.width() <= width - 12);
                assertTrue(area.y() + area.height() <= 600 - 100);
            }
        }
    }

    static AudioEffectDebugSnapshot example(String mode) {
        var selection = new AudioEffectService.Selection(mode, "key_warm", List.of("en_bass", "en_vocal"));
        var result = new AudioEffectService().fallback(selection);
        var dsp = new AudioEffectDsp(48_000);
        dsp.setConfiguration(result.configuration());
        return new AudioEffectDebugSnapshot(selection, List.of(new AudioEffectDebugSnapshot.Deck(1234567,
                true, 3, 1_792_634_123_456L, selection, result, dsp.debugSnapshot())));
    }

    private static String text(AudioEffectDebugSnapshot snapshot) {
        return String.join("\n", AudioEffectDebugOverlay.describe(snapshot).stream().map(AudioEffectDebugOverlay.Line::text).toList());
    }
}
