package tritium.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachePreferencesTest {
    @Test
    void defaultsToTwoGigabytesAndPersistsLimitAndUnlimited(@TempDir Path directory) {
        MusicPreferences preferences = new MusicPreferences();
        preferences.loadFromJsonObject(new JsonObject());
        assertEquals(2L * 1024 * 1024 * 1024, preferences.musicCacheLimitBytes());
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.musicCacheLimitGb().setValue(.5);
        assertEquals(1, changes.get());

        DesktopPreferenceStore store = new DesktopPreferenceStore(directory.resolve("preferences.json"));
        store.save(preferences);
        MusicPreferences restored = new MusicPreferences();
        store.load(restored);
        assertEquals(512L * 1024 * 1024, restored.musicCacheLimitBytes());
        restored.musicCacheLimitGb().setValue(0.0);
        store.save(restored);
        store.load(preferences);
        assertEquals(0, preferences.musicCacheLimitBytes());
        assertEquals(1, changes.get(), "Loading must not trigger save callbacks");
    }

    @Test
    void malformedLimitsKeepTheDefaultAndDoNotPreventOtherSettingsLoading() {
        for (String invalid : new String[]{"invalid", "NaN", "Infinity", "-1"}) {
            MusicPreferences preferences = new MusicPreferences();
            JsonObject json = new JsonObject();
            json.addProperty(MusicPreferences.MUSIC_CACHE_LIMIT_KEY, invalid);
            json.addProperty(MusicPreferences.SHOW_FPS_KEY, true);
            preferences.loadFromJsonObject(json);
            assertEquals(2.0, preferences.musicCacheLimitGb().getValue());
            assertTrue(preferences.showFps().getValue());
        }
    }
}
