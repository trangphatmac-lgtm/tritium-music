package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudPreferencesTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesHudDefaults() {
        MusicPreferences preferences = new MusicPreferences();

        assertFalse(preferences.hud().editMode().getValue());
        assertFalse(preferences.hud().musicInfo().enabled().getValue());
        assertFalse(preferences.hud().musicLyrics().enabled().getValue());
        assertFalse(preferences.hud().musicSpectrum().enabled().getValue());
        assertFalse(preferences.hud().musicInfo().turnComposerIntoLyric().getValue());
        assertEquals("Scroll", preferences.hud().musicLyrics().scrollEffect().getValue());
        assertEquals("Center", preferences.hud().musicLyrics().align().getValue());
        assertEquals("Rect", preferences.hud().musicSpectrum().style().getValue());
        assertEquals(0xC87D7D7D, preferences.hud().musicSpectrum().color().getValue());
    }

    @Test
    void resetsAndClampsLayoutToScreenBounds() {
        MusicPreferences preferences = new MusicPreferences();
        Rectangle screen = new Rectangle(100, 200, 1200, 800);

        preferences.hud().resetLayout(screen);

        assertEquals(124, preferences.hud().musicInfo().x().getValue(), 0.0001);
        assertEquals(224, preferences.hud().musicInfo().y().getValue(), 0.0001);
        assertEquals(830, preferences.hud().musicSpectrum().y().getValue(), 0.0001);

        preferences.hud().musicInfo().setBounds(5000, 5000, 5000, 5000);
        preferences.hud().musicInfo().clampTo(screen);

        assertTrue(preferences.hud().musicInfo().x().getValue() <= screen.getMaxX());
        assertTrue(preferences.hud().musicInfo().y().getValue() <= screen.getMaxY());
        assertEquals(1200, preferences.hud().musicInfo().width().getValue(), 0.0001);
        assertEquals(800, preferences.hud().musicInfo().height().getValue(), 0.0001);
    }

    @Test
    void persistsNestedHudPreferences() {
        Path file = tempDir.resolve("preferences.json");
        DesktopPreferenceStore store = new DesktopPreferenceStore(file);

        MusicPreferences preferences = new MusicPreferences();
        preferences.hud().editMode().setValue(true);
        preferences.hud().musicInfo().enabled().setValue(true);
        preferences.hud().musicInfo().turnComposerIntoLyric().setValue(true);
        preferences.hud().musicLyrics().scrollEffect().setValue("SlideIn");
        preferences.hud().musicLyrics().align().setValue("Right");
        preferences.hud().musicSpectrum().style().setValue("Line");
        preferences.hud().musicSpectrum().compact().setValue(true);
        preferences.hud().musicSpectrum().multiplier().setValue(2.3);
        preferences.hud().musicSpectrum().color().setValue(0xD0FFFFFF);
        preferences.hud().musicSpectrum().setBounds(1, 2, 300, 90);

        store.save(preferences);

        MusicPreferences loaded = new MusicPreferences();
        store.load(loaded);

        assertTrue(loaded.hud().editMode().getValue());
        assertTrue(loaded.hud().musicInfo().enabled().getValue());
        assertTrue(loaded.hud().musicInfo().turnComposerIntoLyric().getValue());
        assertEquals("SlideIn", loaded.hud().musicLyrics().scrollEffect().getValue());
        assertEquals("Right", loaded.hud().musicLyrics().align().getValue());
        assertEquals("Line", loaded.hud().musicSpectrum().style().getValue());
        assertTrue(loaded.hud().musicSpectrum().compact().getValue());
        assertEquals(2.3, loaded.hud().musicSpectrum().multiplier().getValue(), 0.0001);
        assertEquals(0xD0FFFFFF, loaded.hud().musicSpectrum().color().getValue());
        assertEquals(1, loaded.hud().musicSpectrum().x().getValue(), 0.0001);
        assertEquals(2, loaded.hud().musicSpectrum().y().getValue(), 0.0001);
        assertEquals(300, loaded.hud().musicSpectrum().width().getValue(), 0.0001);
        assertEquals(90, loaded.hud().musicSpectrum().height().getValue(), 0.0001);
    }
}
