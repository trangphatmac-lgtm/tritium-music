package tritium.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LyricPreferencesTest {
    @TempDir Path directory;
    @Test void defaultsToAutomaticNeteaseAndPersistsEverySource() {
        var preferences = new MusicPreferences();
        assertTrue(preferences.autoUseBestLyric().getValue()); assertEquals("netease", preferences.preferredLyricSource().getValue());
        var store = new DesktopPreferenceStore(directory.resolve("preferences.json"));
        for (String source : preferences.preferredLyricSource().getModes()) {
            preferences.preferredLyricSource().setValue(source); preferences.autoUseBestLyric().setValue(false); store.save(preferences);
            var loaded = new MusicPreferences(); store.load(loaded);
            assertEquals(source, loaded.preferredLyricSource().getValue()); assertFalse(loaded.autoUseBestLyric().getValue());
        }
    }
    @Test void invalidOrOldPreferencesKeepDefaultAndLoadingDoesNotFetch() {
        var preferences = new MusicPreferences(); var calls = new AtomicInteger();
        preferences.setLyricSourceChangeCallback(calls::incrementAndGet);
        var json = new JsonObject(); json.addProperty("lyrics.preferredSource", "invalid");
        preferences.loadFromJsonObject(json); assertEquals("netease", preferences.preferredLyricSource().getValue());
        json.addProperty("lyrics.preferredSource", "qq"); json.addProperty("lyrics.autoUseBest", false);
        preferences.loadFromJsonObject(json); assertEquals(0, calls.get());
        preferences.autoUseBestLyric().setValue(true); preferences.preferredLyricSource().setValue("amll");
        assertEquals(2, calls.get());
    }
}
