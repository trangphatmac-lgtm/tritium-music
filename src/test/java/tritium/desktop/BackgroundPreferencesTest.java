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
        assertFalse(preferences.background().applyToLyrics().getValue());
        assertEquals(.35, preferences.background().lyricsTransparency().getValue());
    }

    @Test
    void migratesSavedOpacityWithoutChangingTheBackgroundAppearance() {
        var bg = new BackgroundPreferences();
        bg.loadFromJsonObject(new JsonParser().parse("{\"lyricsOpacity\":0.65}").getAsJsonObject());
        assertEquals(.35, bg.lyricsTransparency().getValue(), 1e-9);
        assertFalse(bg.toJsonObject().has("lyricsOpacity"));
        bg.loadFromJsonObject(new JsonParser().parse("{\"lyricsOpacity\":0.65,\"lyricsTransparency\":0.8}").getAsJsonObject());
        assertEquals(.8, bg.lyricsTransparency().getValue());
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
        bg.applyToLyrics().setValue(true);
        bg.lyricsTransparency().setValue(.42);
        assertEquals(14, changes.get());

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
                 "strength":4, "panelOpacity":-2, "shuffle":"maybe", "applyToLyrics":"true", "shader":"waves"},
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
        assertFalse(bg.applyToLyrics().getValue());
        assertEquals("waves", bg.shader().getValue());
        assertFalse(preferences.showTranslation().getValue());
    }

    @Test
    void lyricsOptInAppliesToEveryNonSolidModeAndSurvivesModeChanges() {
        var bg = new BackgroundPreferences();
        for (String mode : bg.mode().getModes()) {
            bg.mode().setValue(mode);
            assertEquals(mode.equals("cover"), bg.appliesToLyrics());
        }
        bg.applyToLyrics().setValue(true);
        for (String mode : bg.mode().getModes()) {
            bg.mode().setValue(mode);
            assertEquals(!mode.equals("static"), bg.appliesToLyrics());
            assertTrue(bg.applyToLyrics().getValue());
        }
    }

    @Test
    void coverOptionsPersistAndOnlyBlurKeepsTheOriginalLyricsCompositor() {
        var preferences = new MusicPreferences();
        var bg = preferences.background();
        bg.mode().setValue("cover");
        bg.coverMode().setValue("nomand");
        bg.nomandEffect().setValue("paper-texture");
        bg.monetLayout().setValue("half-pane-gradient");
        DesktopPreferenceStore store = new DesktopPreferenceStore(temp.resolve("cover.json"));
        store.save(preferences);
        var restored = new MusicPreferences();
        store.load(restored);
        assertEquals(preferences.toJsonObject(), restored.toJsonObject());
        bg.loadFromJsonObject(new JsonParser().parse("""
                {"coverMode":"wrong","nomandEffect":{},"monetLayout":"wrong"}
                """).getAsJsonObject());
        assertEquals("nomand", bg.coverMode().getValue());
        assertEquals("paper-texture", bg.nomandEffect().getValue());
        assertEquals("half-pane-gradient", bg.monetLayout().getValue());
        for (String mode : bg.coverMode().getModes()) {
            bg.coverMode().setValue(mode);
            assertEquals(!mode.equals("blur"), bg.appliesToLyrics());
        }
    }
}
