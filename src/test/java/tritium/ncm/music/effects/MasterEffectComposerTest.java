package tritium.ncm.music.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class MasterEffectComposerTest {
    private final EffectPresets presets = new EffectPresets();

    @Test void songTakesPrecedenceAndSelectiveBassTrebleMergeRetainsEachSide() {
        JsonObject song = song("{}");
        MasterEffectComposer.Result result = MasterEffectComposer.compose(presets.master("key_light"), song,
                selected("en_bass", "en_treble"), false);
        assertEquals(1, result.effect().getAsJsonObject("eq").getAsJsonArray("eqs").get(0).getAsInt());
        JsonObject bt = result.effect().getAsJsonObject("bt");
        assertEquals(4.8, bt.get("bass").getAsDouble());
        assertEquals(0.6, bt.get("bassQ").getAsDouble());
        assertEquals(3.8, bt.get("treble").getAsDouble());
        assertEquals(0.8, bt.get("trebleQ").getAsDouble());
        assertEquals(-1.2, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
    }

    @Test void missingSongFeaturesDoNotDisableEnhancements() {
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song("{}"),
                selected("en_bass", "en_treble", "en_vocal"), false);
        assertEquals(List.of("en_bass", "en_treble", "en_vocal"), result.usedDiy());
        assertEquals(-2.1, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
        assertTrue(result.effect().has("peq"));
    }

    @Test void songFeatureFiltersUseRecoveredThresholds() {
        JsonObject song = song("{\"vocal_drum_bass_other_ratio\":[0.19,0.049,0.2,0.3],\"vocal_main_freq\":601}");
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song,
                selected("en_bass", "en_treble", "en_vocal"), false);
        assertTrue(result.usedDiy().isEmpty());
        assertFalse(result.effect().has("bt"));
        assertFalse(result.effect().has("peq"));
        assertFalse(result.effect().has("cmp"));
        result = MasterEffectComposer.compose(null,
                song("{\"vocal_drum_bass_other_ratio\":[0.2,0.05],\"vocal_main_freq\":600,\"audio_spectral_centroid\":4000}"),
                selected("en_bass", "en_treble", "en_vocal"), false);
        assertEquals(3, result.usedDiy().size());
    }

    @Test void lowFrequencyAndCentroidFiltersAreIndependent() {
        JsonObject song = song("{\"low_mid_high_ratio\":[0],\"audio_spectral_centroid\":4001}");
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song,
                selected("en_bass", "en_treble"), false);
        assertTrue(result.usedDiy().isEmpty()); // first EQ mean 1 plus 6 exceeds 6
        song.getAsJsonObject("feature").add("low_mid_high_ratio", new JsonParser().parse("[0.16666666666666666]"));
        result = MasterEffectComposer.compose(null, song, selected("en_bass"), false);
        assertEquals(List.of("en_bass"), result.usedDiy()); // equality does not disable
    }

    @Test void baseModeClosesBassVocalAndPreservesObservedWrappedTrebleShape() {
        JsonObject base = json("{\"key_custom\":{\"effect\":{\"eq\":{\"on\":true,\"eqs\":[9,9,9]}}}}");
        MasterEffectComposer.Result result = MasterEffectComposer.compose(base, null, presets.diy(), false);
        assertEquals(List.of("en_treble", "en_vibe"), result.usedDiy());
        assertEquals(-3.2, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
        assertTrue(result.zoneEffect());
    }

    @Test void spaceEnhancementsAreMutuallyExclusiveAndVibeClosesSongReverb() {
        JsonObject song = song("{}");
        song.getAsJsonObject("aev2").add("rvb", json("{\"on\":true}"));
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song, selected("en_vibe", "en_live"), false);
        assertEquals(List.of("en_vibe"), result.usedDiy());
        assertFalse(result.effect().getAsJsonObject("rvb").get("on").getAsBoolean());
        assertEquals(-2.5, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
        song.getAsJsonObject("aev2").getAsJsonObject("rvb").addProperty("on", false);
        result = MasterEffectComposer.compose(null, song, selected("en_vibe", "en_live"), false);
        assertEquals(List.of("en_live"), result.usedDiy());
        assertEquals(presets.diy().getAsJsonObject("en_live").get("rvb"), result.effect().get("rvb"));
    }

    @Test void fourEnhancementClassesGetThreeDecibelsAdditionalHeadroom() {
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song("{}"),
                selected("en_bass", "en_treble", "en_vocal", "en_vibe"), false);
        assertEquals(4, result.usedDiy().size());
        assertEquals(-5, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
    }

    @Test void specialMultichannelModeFiltersDiyAndScalesGains() {
        MasterEffectComposer.Result result = MasterEffectComposer.compose(presets.master("key_bright"), null,
                presets.diy(), true);
        assertTrue(result.usedDiy().isEmpty());
        assertEquals(-2.4, result.effect().getAsJsonObject("eq").getAsJsonArray("eqs").get(0).getAsDouble(), 1e-12);
        assertFalse(result.effect().has("bt"));
    }

    @Test void compressorOnFollowsPregainEvenForExistingCompression() {
        JsonObject song = song("{}");
        song.getAsJsonObject("aev2").add("cmp", json("{\"on\":true,\"pregain\":0,\"ratio\":2,\"threshold\":-8}"));
        MasterEffectComposer.Result result = MasterEffectComposer.compose(null, song, new JsonObject(), false);
        assertFalse(result.effect().getAsJsonObject("cmp").get("on").getAsBoolean());
        assertTrue(song.getAsJsonObject("aev2").getAsJsonObject("cmp").get("on").getAsBoolean());
        song.getAsJsonObject("aev2").getAsJsonObject("cmp").addProperty("pregain", -1);
        assertTrue(MasterEffectComposer.compose(null, song, new JsonObject(), false)
                .effect().getAsJsonObject("cmp").get("on").getAsBoolean());
    }

    @Test void inputAndOutputTreesRemainIndependent() {
        JsonObject base = presets.master("key_warm");
        JsonObject diy = selected("en_vibe");
        String before = base.toString() + diy;
        MasterEffectComposer.Result result = MasterEffectComposer.compose(base, null, diy, false);
        assertEquals(before, base.toString() + diy);
        JsonObject output = result.effect();
        output.getAsJsonObject("cmp").addProperty("pregain", 999);
        assertEquals(-2.5, result.effect().getAsJsonObject("cmp").get("pregain").getAsDouble());
        assertThrows(UnsupportedOperationException.class, () -> result.usedDiy().add("en_bass"));
        assertThrows(IllegalArgumentException.class, () -> MasterEffectComposer.compose(presets.masterBase(), null, diy, false));
    }

    @Test void authenticatedPerSongPackageFiltersTrebleUsingItsSpectralCentroid() throws java.io.IOException {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/tritium/audioeffects/master-song-300380.ncae")) {
            assertNotNull(stream);
            JsonObject song = NcaeDecoder.decodeJson(stream.readAllBytes());
            assertEquals(4301.55, song.getAsJsonObject("feature").get("audio_spectral_centroid").getAsDouble());
            MasterEffectComposer.Result result = MasterEffectComposer.compose(presets.master("key_transparent"), song,
                    selected("en_treble"), false);
            assertTrue(result.usedDiy().isEmpty());
            assertEquals(song.getAsJsonObject("aev2").get("eq"), result.effect().get("eq"));
        }
    }

    private JsonObject selected(String... keys) {
        JsonObject result = new JsonObject();
        JsonObject all = presets.diy();
        for (String key : keys) result.add(key, all.get(key));
        return result;
    }

    private static JsonObject song(String feature) {
        JsonObject song = json("{\"aev2\":{\"eq\":{\"on\":true,\"eqs\":[1,1,1,0,0,0,0,0,0,0]}}}");
        song.add("feature", json(feature));
        return song;
    }

    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
}
