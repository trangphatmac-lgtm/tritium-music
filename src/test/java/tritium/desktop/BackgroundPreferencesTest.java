package tritium.desktop;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundPreferencesTest {
    @TempDir Path temp;

    @Test
    void oldPreferencesKeepTheExistingStaticBackground() {
        MusicPreferences preferences = new MusicPreferences();
        preferences.loadFromJsonObject(new JsonParser().parse("{\"ui.appearance\":\"Light\"}").getAsJsonObject());
        assertEquals("Light", preferences.appearance().getValue());
        assertEquals("static", preferences.background().mode().getValue());
    }

    @Test
    void savesAllBackgroundOptionsAndNotifiesTheExistingStore() {
        MusicPreferences preferences = new MusicPreferences();
        BackgroundPreferences bg = preferences.background();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        bg.mode().setValue("picture");
        bg.pictureSource().setValue("folder");
        bg.pictureFile().setValue("/照片/海边.jpg");
        bg.pictureFolder().setValue("/照片/幻灯片");
        bg.videoFile().setValue("C:\\Videos\\clip.mp4");
        bg.interval().setValue(55.0);
        bg.shuffle().setValue(true);
        bg.fit().setValue("contain");
        bg.strength().setValue(.8);
        bg.panelOpacity().setValue(.15);
        bg.shader().setValue("aurora");
        bg.shaderSpeed().setValue(2.1);
        assertEquals(12, changes.get());

        DesktopPreferenceStore store = new DesktopPreferenceStore(temp.resolve("preferences.json"));
        store.save(preferences);
        MusicPreferences loaded = new MusicPreferences();
        AtomicInteger loadChanges = new AtomicInteger();
        loaded.setChangeCallback(loadChanges::incrementAndGet);
        store.load(loaded);
        assertEquals(bg.toJsonObject().toString(), loaded.background().toJsonObject().toString());
        assertEquals(0, loadChanges.get(), "Loading must not trigger partial saves");
    }

    @Test
    void ignoresMalformedEntriesIndividuallyAndBoundsNumbers() {
        MusicPreferences preferences = new MusicPreferences();
        preferences.loadFromJsonObject(new JsonParser().parse("""
                {"background":{"mode":"remote", "pictureSource":[], "pictureFile":{},
                 "videoFile":"/good/video.mp4", "interval":"oops", "shaderSpeed":"NaN",
                 "strength":4, "panelOpacity":-2, "shuffle":"maybe", "shader":"waves"},
                 "lyrics.showTranslation":false}
                """).getAsJsonObject());
        BackgroundPreferences bg = preferences.background();
        assertEquals("static", bg.mode().getValue());
        assertEquals("", bg.pictureFile().getValue());
        assertEquals("/good/video.mp4", bg.videoFile().getValue());
        assertEquals(30, bg.interval().getValue());
        assertEquals(1, bg.shaderSpeed().getValue());
        assertEquals(1, bg.strength().getValue());
        assertEquals(0, bg.panelOpacity().getValue());
        assertFalse(bg.shuffle().getValue());
        assertEquals("waves", bg.shader().getValue());
        assertFalse(preferences.showTranslation().getValue());
    }
}
