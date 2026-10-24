package tritium.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerControlsPreferencesTest {
    @Test
    void autoCollapseDefaultsOnAndPersistsItsToggle(@TempDir Path dir) {
        MusicPreferences preferences = new MusicPreferences();
        assertTrue(preferences.miniAutoCollapse().getValue());
        preferences.loadFromJsonObject(new JsonObject());
        assertTrue(preferences.miniAutoCollapse().getValue());
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.miniAutoCollapse().setValue(false);
        assertEquals(1, changes.get());
        DesktopPreferenceStore store = new DesktopPreferenceStore(dir.resolve("preferences.json"));
        store.save(preferences);
        MusicPreferences restored = new MusicPreferences();
        store.load(restored);
        assertFalse(restored.miniAutoCollapse().getValue());
        restored.miniAutoCollapse().setValue(true);
        store.save(restored);
        store.load(preferences);
        assertTrue(preferences.miniAutoCollapse().getValue());
    }

    @Test
    void persistsControllerChoiceAndNotifiesWithoutChangingOtherLayouts(@TempDir Path dir) {
        MusicPreferences preferences = new MusicPreferences();
        assertEquals("BottomBar", preferences.playerControls().getValue());
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.playerControls().setValue("FoliaMini");
        assertEquals(1, changes.get());
        DesktopPreferenceStore store = new DesktopPreferenceStore(dir.resolve("preferences.json"));
        store.save(preferences);
        MusicPreferences restored = new MusicPreferences();
        store.load(restored);
        assertEquals("FoliaMini", restored.playerControls().getValue());
        assertEquals("Sidebar", restored.layout().getValue());
        assertEquals("SingleColumn", restored.settingsLayout().getValue());
        restored.playerControls().setValue("BottomBar");
        store.save(restored);
        store.load(preferences);
        assertEquals("BottomBar", preferences.playerControls().getValue());
    }

    @Test
    void oldAndUnknownPreferencesKeepTheExistingBottomBar() {
        MusicPreferences preferences = new MusicPreferences();
        JsonObject json = new JsonObject();
        preferences.loadFromJsonObject(json);
        assertEquals("BottomBar", preferences.playerControls().getValue());
        json.addProperty("ui.playerControls", "future-controller");
        preferences.loadFromJsonObject(json);
        assertEquals("BottomBar", preferences.playerControls().getValue());
    }
}
