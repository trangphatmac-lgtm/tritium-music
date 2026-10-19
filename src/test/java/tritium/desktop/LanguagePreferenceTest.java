package tritium.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.i18n.Language;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LanguagePreferenceTest {
    @TempDir Path directory;

    @Test
    void savesEveryLanguageAndNotifiesChanges() {
        DesktopPreferenceStore store = new DesktopPreferenceStore(directory.resolve("preferences.json"));
        MusicPreferences preferences = new MusicPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        for (Language language : Language.values()) {
            preferences.language().setValue(language.tag());
            store.save(preferences);
            MusicPreferences restored = new MusicPreferences();
            store.load(restored);
            assertEquals(language.tag(), restored.language().getValue());
            assertEquals("Standard", restored.quality().getValue());
        }
        assertEquals(2, changes.get());
        assertThrows(IllegalArgumentException.class, () -> preferences.language().setValue("fr"));
    }

    @Test
    void oldAndInvalidPreferencesKeepSimplifiedChineseAndLoadOtherSettings() {
        for (String value : new String[]{null, "fr", ""}) {
            MusicPreferences preferences = new MusicPreferences();
            JsonObject json = new JsonObject();
            if (value != null) json.addProperty("ui.language", value);
            json.addProperty("ui.appearance", "Light");
            preferences.loadFromJsonObject(json);
            assertEquals("zh-CN", preferences.language().getValue());
            assertEquals("Light", preferences.appearance().getValue());
        }
    }
}
