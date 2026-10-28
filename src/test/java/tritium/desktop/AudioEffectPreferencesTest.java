package tritium.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.i18n.I18n;
import tritium.i18n.Language;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AudioEffectPreferencesTest {
    @Test
    void defaultsToBypassWithAllMasterControlsHidden() {
        AudioEffectPreferences preferences = new AudioEffectPreferences();
        assertEquals("Off", preferences.mode().getValue());
        assertEquals(List.of("Off", "Smart", "Master"), preferences.mode().getModes());
        assertEquals("Auto", preferences.masterTone().getValue());
        assertFalse(preferences.isMaster());
        for (PreferenceValue<?> control : masterControls(preferences)) assertTrue(control.isHidden());
    }

    @Test
    void preservesMasterChoicesWhenChangingTheExclusiveMode() {
        AudioEffectPreferences preferences = new AudioEffectPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.mode().setValue("Master");
        for (PreferenceValue<?> control : masterControls(preferences)) assertFalse(control.isHidden());
        preferences.masterTone().setValue("key_warm");
        preferences.mode().setValue("Smart");
        for (PreferenceValue<?> control : masterControls(preferences)) assertTrue(control.isHidden());
        preferences.mode().setValue("Off");
        preferences.mode().setValue("Master");
        assertEquals("key_warm", preferences.masterTone().getValue());
        assertEquals(5, changes.get());
        preferences.mode().setValue("Master");
        assertEquals(5, changes.get(), "Selecting the current mode must not restart the effect");
    }

    @Test
    void preservesModeAndToneButDropsLegacyEnhancementSwitchesWithoutCallbacks(@TempDir Path dir) {
        JsonObject effects = new JsonObject();
        effects.addProperty("mode", "Master");
        effects.addProperty("masterTone", "key_silky");
        for (String key : List.of("bass", "treble", "vocal", "vibe", "live")) effects.addProperty(key, true);
        JsonObject stored = new JsonObject();
        stored.add(AudioEffectPreferences.JSON_KEY, effects);

        MusicPreferences preferences = new MusicPreferences();
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.loadFromJsonObject(stored);
        assertEquals(0, changes.get());
        DesktopPreferenceStore store = new DesktopPreferenceStore(dir.resolve("preferences.json"));
        store.save(preferences);
        MusicPreferences restored = new MusicPreferences();
        store.load(restored);
        JsonObject expected = new JsonObject();
        expected.addProperty("mode", "Master");
        expected.addProperty("masterTone", "key_silky");
        assertEquals(expected, restored.toJsonObject().getAsJsonObject(AudioEffectPreferences.JSON_KEY));

        AudioEffectPreferences isolated = new AudioEffectPreferences();
        isolated.setChangeCallback(changes::incrementAndGet);
        isolated.loadFromJsonObject(effects);
        assertEquals(0, changes.get(), "Loading the nested preferences must also be silent");
        isolated.masterTone().setValue("Auto");
        assertEquals(1, changes.get(), "Callbacks resume after loading");
    }

    @Test
    void ignoresMalformedAndUnknownEntriesWithoutDiscardingValidChoices() {
        AudioEffectPreferences preferences = new AudioEffectPreferences();
        JsonObject json = new JsonObject();
        json.addProperty("mode", "Emotion");
        json.addProperty("masterTone", "unknown");
        json.addProperty("bass", "true");
        json.add("treble", new JsonArray());
        json.add("vocal", JsonNull.INSTANCE);
        json.addProperty("vibe", 1);
        json.addProperty("live", true);
        preferences.loadFromJsonObject(json);
        assertEquals("Off", preferences.mode().getValue());
        assertEquals("Auto", preferences.masterTone().getValue());
        assertEquals(2, preferences.toJsonObject().entrySet().size());
        preferences.loadFromJsonObject(null);
        preferences.loadFromJsonObject(new JsonObject());
        assertFalse(preferences.toJsonObject().has("live"));

        MusicPreferences music = new MusicPreferences();
        JsonObject olderPreferences = new JsonObject();
        olderPreferences.addProperty(AudioEffectPreferences.JSON_KEY, false);
        music.loadFromJsonObject(olderPreferences);
        assertEquals("Off", music.audioEffects().mode().getValue());
    }

    @Test
    void allThirtyTwoTonesAndModesHaveTranslations() {
        AudioEffectPreferences preferences = new AudioEffectPreferences();
        assertEquals(33, preferences.masterTone().getModes().size());
        assertEquals(33, preferences.masterTone().getModes().stream().distinct().count());
        for (Language language : Language.values()) {
            for (String tone : preferences.masterTone().getModes()) {
                String key = "audioEffect.tone." + tone;
                assertNotEquals(key, I18n.text(language, key), language.tag());
            }
            for (String mode : preferences.mode().getModes()) {
                String key = "audioEffect.mode." + mode;
                assertNotEquals(key, I18n.text(language, key), language.tag());
            }
        }
    }

    private static List<PreferenceValue<?>> masterControls(AudioEffectPreferences preferences) {
        return List.of(preferences.masterTone());
    }
}
