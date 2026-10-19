package tritium.desktop;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import tritium.desktop.background.latent.LatentPreferences;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundPreferencesTest {
    @TempDir Path temp;

    @Test
    void latentPalettesRoundTripIndependentlyAndRejectMalformedColors() {
        MusicPreferences preferences = new MusicPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        var latent = preferences.background().latent();
        preferences.background().mode().setValue("latent");
        latent.color(true, LatentPreferences.Color.ACCENT).setValue(0xFF12ABCD);
        latent.color(false, LatentPreferences.Color.BACKGROUND).setValue(0xFF102030);
        latent.beat().setValue(false);
        latent.colorSource().setValue("theme");
        latent.layers().setValue("mesh");
        latent.speed().setValue(1.7);
        assertEquals(7, changes.get());
        DesktopPreferenceStore store = new DesktopPreferenceStore(temp.resolve("latent.json"));
        store.save(preferences);
        MusicPreferences restored = new MusicPreferences();
        store.load(restored);
        assertEquals(preferences.toJsonObject(), restored.toJsonObject());
        latent.resetPalette(true);
        assertEquals(0xFFEA580C, latent.color(true, LatentPreferences.Color.ACCENT).getValue());
        assertEquals(0xFF102030, latent.color(false, LatentPreferences.Color.BACKGROUND).getValue());
        latent.loadFromJsonObject(new JsonParser().parse("""
                {"colorSource":"invalid","layers":{},"beat":"false","speed":1e300,
                 "light.ACCENT":"bad","dark.PRIMARY":4294967296,"dark.ACCENT":123.5,
                 "dark.SECONDARY":1122867}
                """).getAsJsonObject());
        assertEquals("theme", latent.colorSource().getValue());
        assertEquals(3, latent.speed().getValue());
        assertEquals(0xFFF4F4F5, latent.color(false, LatentPreferences.Color.PRIMARY).getValue());
        assertEquals(0xFFF4F4F5, latent.color(false, LatentPreferences.Color.ACCENT).getValue());
        assertEquals(0xFF112233, latent.color(false, LatentPreferences.Color.SECONDARY).getValue());
    }

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
