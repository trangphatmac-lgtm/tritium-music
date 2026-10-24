package tritium.desktop;

import org.junit.jupiter.api.Test;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.Quality;
import tritium.settings.ClientSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPreferencesTest {
    @Test
    void persistsAllLyricsStylesAndKeepsOldProfilesOnNormal() {
        var preferences = new MusicPreferences();
        assertEquals("Normal", preferences.lyricStyle().getValue());
        assertEquals(12, preferences.lyricStyle().getModes().size());
        assertTrue(preferences.showBottomLyrics().getValue());
        preferences.showBottomLyrics().setValue(false);
        for (var style : tritium.screens.ncm.lyrics.LyricStyle.values()) {
            preferences.lyricStyle().setValue(style.name());
            var restored = new MusicPreferences();
            restored.loadFromJsonObject(preferences.toJsonObject());
            assertEquals(style.name(), restored.lyricStyle().getValue());
            assertFalse(restored.showBottomLyrics().getValue());
        }
        var unknown = new com.google.gson.JsonObject();
        unknown.addProperty("lyrics.style", "future-mode");
        var restored = new MusicPreferences();
        restored.loadFromJsonObject(unknown);
        assertEquals("Normal", restored.lyricStyle().getValue());
        assertTrue(restored.showBottomLyrics().getValue());
        restored.loadFromJsonObject(new com.google.gson.JsonObject());
        assertEquals("Normal", restored.lyricStyle().getValue());
    }
    @Test
    void exposesDesktopDefaults() {
        MusicPreferences preferences = new MusicPreferences();

        assertEquals("Standard", preferences.quality().getValue());
        assertEquals("Dark", preferences.appearance().getValue());
        assertEquals("Static", preferences.interfaceStyle().getValue());
        assertEquals("SingleColumn", preferences.settingsLayout().getValue());
        assertTrue(preferences.musicToast().getValue());
        assertFalse(preferences.showWidgetBoundary().getValue());
        assertFalse(preferences.lyricDebug().getValue());
        assertFalse(preferences.autoMixDebug().getValue());
        assertEquals(0.1, preferences.volume().getValue(), 0.0001);
        assertFalse(preferences.autoMix().getValue());
        assertFalse(preferences.autoMixFullMode().getValue());
        assertTrue(preferences.showTranslation().getValue());
        assertFalse(preferences.showRoman().getValue());
    }

    @Test
    void clampsVolumeAndMapsQuality() {
        MusicPreferences preferences = new MusicPreferences();

        preferences.volume().setValue(3.0);
        assertEquals(1.0, preferences.volume().getValue(), 0.0001);

        preferences.volume().setValue(-1.0);
        assertEquals(0.0, preferences.volume().getValue(), 0.0001);

        preferences.quality().setValue("HiRes");
        assertEquals(Quality.HIRES, CloudMusic.quality);
        assertThrows(IllegalArgumentException.class, () -> preferences.quality().setValue("Unknown"));
        assertThrows(IllegalArgumentException.class, () -> preferences.appearance().setValue("Unknown"));
    }

    @Test
    void persistsInterfaceStyleAndToleratesOldOrUnknownSettings() {
        MusicPreferences preferences = new MusicPreferences();
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.interfaceStyle().setValue("Blur");
        preferences.interfaceStyle().setValue("LiquidGlass");
        assertEquals(2, changes.get());
        var stored = preferences.toJsonObject();
        MusicPreferences restored = new MusicPreferences();
        restored.loadFromJsonObject(stored);
        assertEquals("LiquidGlass", restored.interfaceStyle().getValue());
        assertThrows(IllegalArgumentException.class, () -> restored.interfaceStyle().setValue("unknown"));
        stored.addProperty("ui.interfaceStyle", "future-style");
        stored.addProperty("ui.appearance", "Light");
        MusicPreferences old = new MusicPreferences();
        old.loadFromJsonObject(stored);
        assertEquals("Static", old.interfaceStyle().getValue());
        assertEquals("Light", old.appearance().getValue());
        stored.remove("ui.interfaceStyle");
        old.loadFromJsonObject(stored);
        assertEquals("Static", old.interfaceStyle().getValue());
    }

    @Test
    void invokesBooleanCallbacks() {
        MusicPreferences preferences = new MusicPreferences();

        preferences.showWidgetBoundary().setValue(true);
        preferences.lyricDebug().setValue(true);

        assertTrue(ClientSettings.SHOW_WIDGET_BOUNDARY);
        assertTrue(ClientSettings.DEBUG_MODE);
    }

    @Test
    void settingsLayoutPersistsIndependentlyAndAcceptsLegacyPreferences() {
        MusicPreferences preferences = new MusicPreferences();
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.settingsLayout().setValue("TwoColumns");
        assertEquals(1, changes.get());
        assertEquals("Sidebar", preferences.layout().getValue());

        var stored = preferences.toJsonObject();
        MusicPreferences restored = new MusicPreferences();
        restored.setChangeCallback(changes::incrementAndGet);
        restored.loadFromJsonObject(stored);
        assertEquals("TwoColumns", restored.settingsLayout().getValue());
        assertEquals(1, changes.get(), "Loading must not save again");

        for (String value : new String[]{null, "unknown"}) {
            if (value == null) stored.remove("ui.settingsLayout");
            else stored.addProperty("ui.settingsLayout", value);
            stored.addProperty("ui.layout", "TopBar");
            MusicPreferences legacy = new MusicPreferences();
            legacy.loadFromJsonObject(stored);
            assertEquals("SingleColumn", legacy.settingsLayout().getValue());
            assertEquals("TopBar", legacy.layout().getValue());
        }
    }

    @Test
    void debugPreferencePersistsWithoutChangingPlaybackOrLyricsDebug() {
        MusicPreferences preferences = new MusicPreferences();
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.autoMixDebug().setValue(true);
        preferences.autoMixDebug().setValue(true);
        assertEquals(1, changes.get());
        assertFalse(preferences.autoMix().getValue());
        assertFalse(preferences.lyricDebug().getValue());
        MusicPreferences restored = new MusicPreferences();
        restored.setChangeCallback(changes::incrementAndGet);
        restored.loadFromJsonObject(preferences.toJsonObject());
        assertTrue(restored.autoMixDebug().getValue());
        assertEquals(1, changes.get());
        assertFalse(restored.autoMix().getValue());
    }

    @Test
    void fullAutoMixModeIsIndependentAndLoadingDoesNotSaveAgain() {
        MusicPreferences preferences = new MusicPreferences();
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.autoMixFullMode().setValue(true);
        preferences.autoMixFullMode().setValue(true);
        assertEquals(1, changes.get());
        assertFalse(preferences.autoMix().getValue());
        assertFalse(preferences.autoMixDebug().getValue());
        assertTrue(preferences.toJsonObject().get("player.autoMixFullMode").getAsBoolean());

        MusicPreferences restored = new MusicPreferences();
        restored.setChangeCallback(changes::incrementAndGet);
        restored.loadFromJsonObject(preferences.toJsonObject());
        assertTrue(restored.autoMixFullMode().getValue());
        assertFalse(restored.autoMix().getValue());
        assertFalse(restored.autoMixDebug().getValue());
        assertEquals(1, changes.get(), "Loading must not trigger a recursive preferences save");
        restored.autoMixFullMode().setValue(false);
        assertEquals(2, changes.get());
    }

    @Test
    void autoMixChangesNotifyPersistenceButLoadingDoesNotSaveAgain() {
        MusicPreferences preferences = new MusicPreferences();
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.autoMix().setValue(true);
        preferences.autoMix().setValue(true);
        assertEquals(1, changes.get());

        MusicPreferences restored = new MusicPreferences();
        restored.setChangeCallback(changes::incrementAndGet);
        restored.loadFromJsonObject(preferences.toJsonObject());
        assertTrue(restored.autoMix().getValue());
        assertEquals(1, changes.get(), "Loading must not trigger a recursive preferences save");
        restored.autoMix().setValue(false);
        assertEquals(2, changes.get());
    }
}
