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
    }

    @Test
    void ignoresUnknownQualityAndClampsNumbers() throws IOException {
        Path file = tempDir.resolve("preferences.json");
        Files.writeString(file, """
                {
                  "music.quality": "DefinitelyNotAQuality",
                  "player.volume": 8.0,
                  "lyrics.height": -4.0,
                  "lyrics.showTranslation": false
                }
                """, StandardCharsets.UTF_8);

        MusicPreferences loaded = new MusicPreferences();
        new DesktopPreferenceStore(file).load(loaded);

        assertEquals("Standard", loaded.quality().getValue());
        assertEquals(1.0, loaded.volume().getValue(), 0.0001);
        assertEquals(14.0, loaded.lyricHeight().getValue(), 0.0001);
        assertFalse(loaded.showTranslation().getValue());
    }
}
