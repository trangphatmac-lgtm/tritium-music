package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPreferenceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsPreferences() {
        Path file = tempDir.resolve("preferences.json");
        DesktopPreferenceStore store = new DesktopPreferenceStore(file);

        MusicPreferences preferences = new MusicPreferences();
        preferences.quality().setValue("HiRes");
        preferences.musicToast().setValue(false);
        preferences.volume().setValue(0.42);
        preferences.showTranslation().setValue(false);
        preferences.showRoman().setValue(true);
        preferences.lyricHeight().setValue(32.5);
        preferences.spectrumAbsoluteVolume().setValue(false);
        preferences.showFps().setValue(true);
        preferences.maxFps().setValue(144.0);
        preferences.accentColor().setValue(0xFF2DD4BF);
        preferences.appearance().setValue("Light");
        preferences.layout().setValue("TopBar");
        preferences.hud().musicSpectrum().color().setValue(0x803B719A);

        store.save(preferences);

        MusicPreferences loaded = new MusicPreferences();
        store.load(loaded);

        assertEquals("HiRes", loaded.quality().getValue());
        assertFalse(loaded.musicToast().getValue());
        assertEquals(0.42, loaded.volume().getValue(), 0.0001);
        assertFalse(loaded.showTranslation().getValue());
        assertTrue(loaded.showRoman().getValue());
        assertEquals(32.5, loaded.lyricHeight().getValue(), 0.0001);
        assertFalse(loaded.spectrumAbsoluteVolume().getValue());
        assertTrue(loaded.showFps().getValue());
        assertEquals(144.0, loaded.maxFps().getValue());
        assertEquals(0xFF2DD4BF, loaded.accentColor().getValue());
        assertEquals("Light", loaded.appearance().getValue());
        assertEquals("TopBar", loaded.layout().getValue());
        assertEquals(0x803B719A, loaded.hud().musicSpectrum().color().getValue());

        preferences.maxFps().setValue((double) MusicPreferences.UNLIMITED_FPS);
        store.save(preferences);
        store.load(loaded);
        assertEquals((double) MusicPreferences.UNLIMITED_FPS, loaded.maxFps().getValue());
    }

    @Test
    void ignoresUnknownQualityAndClampsNumbers() throws IOException {
        Path file = tempDir.resolve("preferences.json");
        Files.writeString(file, """
                {
                  "music.quality": "DefinitelyNotAQuality",
                  "ui.appearance": "Unknown",
                  "ui.layout": "Unknown",
                  "player.volume": 8.0,
                  "lyrics.height": -4.0,
                  "lyrics.showTranslation": false
                }
                """, StandardCharsets.UTF_8);

        MusicPreferences loaded = new MusicPreferences();
        new DesktopPreferenceStore(file).load(loaded);

        assertEquals("Standard", loaded.quality().getValue());
        assertEquals("Dark", loaded.appearance().getValue());
        assertEquals("Sidebar", loaded.layout().getValue());
        assertEquals(1.0, loaded.volume().getValue(), 0.0001);
        assertEquals(14.0, loaded.lyricHeight().getValue(), 0.0001);
        assertFalse(loaded.showTranslation().getValue());
        assertFalse(loaded.showFps().getValue());
        assertEquals(60.0, loaded.maxFps().getValue());
        assertEquals(AppTheme.DEFAULT_ACCENT, loaded.accentColor().getValue());
    }

    @Test
    void invalidAccentDoesNotPreventOtherPreferencesFromLoading() throws IOException {
        Path file = tempDir.resolve("preferences.json");
        for (String color : new String[]{"\"oops\"", "4294967296", "-2147483649", "1.5", "null", "{}"}) {
            Files.writeString(file, "{\"ui.accentColor\":" + color + ",\"ui.showFps\":true,\"player.volume\":0.42}");
            MusicPreferences loaded = new MusicPreferences();
            new DesktopPreferenceStore(file).load(loaded);
            assertEquals(AppTheme.DEFAULT_ACCENT, loaded.accentColor().getValue());
            assertTrue(loaded.showFps().getValue());
            assertEquals(.42, loaded.volume().getValue(), .0001);
        }
    }

    @Test
    void acceptsRgbAndUnsignedArgbAndKeepsThemeOpaque() throws IOException {
        Path file = tempDir.resolve("preferences.json");
        for (long color : new long[]{0x2DD4BF, 0x802DD4BFL, 0xFF2DD4BFL}) {
            Files.writeString(file, "{\"ui.accentColor\":" + color + "}");
            MusicPreferences loaded = new MusicPreferences();
            new DesktopPreferenceStore(file).load(loaded);
            assertEquals(0xFF2DD4BF, loaded.accentColor().getValue());
        }
    }
}
