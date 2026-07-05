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
