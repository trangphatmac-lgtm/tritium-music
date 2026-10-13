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
    void exposesDesktopDefaults() {
        MusicPreferences preferences = new MusicPreferences();

        assertEquals("Standard", preferences.quality().getValue());
        assertEquals("Dark", preferences.appearance().getValue());
        assertEquals("Static", preferences.interfaceStyle().getValue());
        assertTrue(preferences.musicToast().getValue());
        assertFalse(preferences.showWidgetBoundary().getValue());
        assertFalse(preferences.lyricDebug().getValue());
        assertEquals(0.1, preferences.volume().getValue(), 0.0001);
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
}
