package tritium.ncm.music.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java port of the recovered Master composeEffect closing and merging rules
 * (Hermes modules 824/825, functions 8889–8907). It selects and combines DSP
 * settings; it does not implement or claim a local neural-network model.
 */
public final class MasterEffectComposer {
    private static final double[] HEADROOM = {0, -0.5, -1.2, -2.1, -3};

    public record Result(JsonObject effect, List<String> usedDiy, boolean zoneEffect) {
        public Result {
            effect = EffectJson.copy(effect);
            usedDiy = List.copyOf(usedDiy);
        }

        @Override public JsonObject effect() { return EffectJson.copy(effect); }
    }

    /**
     * @param baseJson a single-key {key_name:{name,effect}} template wrapper
     * @param songJson nullable per-song document with aev2 and optional feature
     * @param diyJson selected enhancement entries (en_bass, en_treble, etc.)
     * @param supportStereo the original special multichannel flag, NOT simply
     *                      whether decoded PCM has two channels
     * @return flattened effective DSP configuration, without the aev2 wrapper
     */
    public static Result compose(JsonObject baseJson, JsonObject songJson,
                                 JsonObject diyJson, boolean supportStereo) {
        boolean song = songJson != null;
        JsonObject basis;
        if (song) {
            basis = EffectJson.copy(songJson);
        } else if (baseJson == null || baseJson.entrySet().isEmpty()) {
            basis = new JsonObject();
        } else {
            if (baseJson.entrySet().size() != 1) {
                throw new IllegalArgumentException("Master composition requires one base template");
            }
            JsonElement only = baseJson.entrySet().iterator().next().getValue();
            if (!only.isJsonObject()) throw new IllegalArgumentException("Invalid Master base template");
            basis = EffectJson.copy(only.getAsJsonObject());
        }

        JsonObject selected = filter(baseJson, songJson, diyJson, supportStereo);
        JsonObject effect = EffectJson.object(basis, song ? "aev2" : "effect");
        effect = EffectJson.copy(effect);
        applyBassTreble(effect, selected);
        JsonObject vocal = EffectJson.object(selected, "en_vocal");
        if (vocal != null) copyField(vocal, "peq", effect);
        JsonObject vibe = EffectJson.object(selected, "en_vibe");
        if (vibe != null) {
            JsonObject reverb = EffectJson.object(effect, "rvb");
            if (song && EffectJson.on(reverb)) reverb.addProperty("on", false);
            copyField(vibe, "se", effect);
            JsonObject vibeCompressor = EffectJson.object(vibe, "cmp");
            if (vibeCompressor != null) {
                JsonObject compressor = compressor(effect, false);
                compressor.addProperty("pregain", EffectJson.numberOrZero(compressor, "pregain")
                        + EffectJson.numberOrZero(vibeCompressor, "pregain"));
            }
        }
        JsonObject live = EffectJson.object(selected, "en_live");
        if (live != null) copyField(live, "rvb", effect);

        int count = countEnhancements(selected);
        if (count > 0) {
            JsonObject compressor = compressor(effect, true);
            compressor.addProperty("pregain", EffectJson.numberOrZero(compressor, "pregain") + HEADROOM[count]);
        }
        if (supportStereo) {
            JsonObject equalizer = EffectJson.object(effect, "eq");
            JsonArray gains = EffectJson.array(equalizer, "eqs");
            if (gains != null) {
                JsonArray reduced = new JsonArray();
                for (JsonElement gain : gains) {
                    double value = EffectJson.number(gain);
                    if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid Master EQ gain");
                    reduced.add(new JsonPrimitive(value * 0.8));
                }
                equalizer.add("eqs", reduced);
            }
        }
        JsonObject compressor = EffectJson.object(effect, "cmp");
        double pregain = EffectJson.number(compressor, "pregain");
        if (Double.isFinite(pregain)) compressor.addProperty("on", pregain != 0);
        List<String> used = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : selected.entrySet()) used.add(entry.getKey());
        return new Result(effect, used, EffectJson.on(EffectJson.object(effect, "rvb"))
                || EffectJson.on(EffectJson.object(effect, "se")));
    }

    private static JsonObject filter(JsonObject base, JsonObject song, JsonObject diy, boolean supportStereo) {
        JsonObject filtered = new JsonObject();
        if (supportStereo || diy == null) return filtered;
        JsonObject context = song == null ? base : song;
        for (Map.Entry<String, JsonElement> entry : diy.entrySet()) {
            String key = entry.getKey();
            if (key.equals("en_vibe") || key.equals("en_live")) continue;
            boolean closed = switch (key) {
                case "en_bass" -> closeBass(context, song != null);
                case "en_treble" -> closeTreble(context, song != null);
                case "en_vocal" -> closeVocal(context, song != null);
                default -> false;
            };
            if (!closed && entry.getValue().isJsonObject()) {
                filtered.add(key, EffectJson.copyElement(entry.getValue()));
            }
        }
        JsonObject vibe = EffectJson.object(diy, "en_vibe");
        JsonObject live = EffectJson.object(diy, "en_live");
        if (vibe != null && live != null) {
            boolean chooseVibe = song == null
                    || EffectJson.on(EffectJson.object(EffectJson.object(song, "aev2"), "rvb"));
            filtered.add(chooseVibe ? "en_vibe" : "en_live", EffectJson.copy(chooseVibe ? vibe : live));
        } else {
            if (vibe != null) filtered.add("en_vibe", EffectJson.copy(vibe));
            if (live != null) filtered.add("en_live", EffectJson.copy(live));
        }
        return filtered;
    }

    private static boolean closeBass(JsonObject context, boolean song) {
        if (context == null) return false;
        if (!song) return true;
        JsonObject features = EffectJson.object(context, "feature");
        double drum = element(EffectJson.array(features, "vocal_drum_bass_other_ratio"), 1);
        if (drum < 0.05) return true;
        JsonObject equalizer = EffectJson.object(EffectJson.object(context, "aev2"), "eq");
        JsonArray gains = EffectJson.array(equalizer, "eqs");
        double low = element(EffectJson.array(features, "low_mid_high_ratio"), 0);
        return gains != null && gains.size() >= 3 && firstMean(gains) + (1 - low) * 6 > 6;
    }

    private static boolean closeTreble(JsonObject context, boolean song) {
        if (context == null) return false;
        JsonObject features = EffectJson.object(context, "feature");
        if (song && (EffectJson.number(features, "vocal_main_freq") > 600
                || EffectJson.number(features, "audio_spectral_centroid") > 4000)) return true;
        // Intentional literal port: module 824 reads baseJson.effect here, but
        // module 825 unwraps baseJson's sole key later. Do not silently "repair"
        // this observed shape mismatch by reading the unwrapped template.
        JsonObject effect = EffectJson.object(context, song ? "aev2" : "effect");
        JsonArray gains = EffectJson.array(EffectJson.object(effect, "eq"), "eqs");
        if (gains == null || gains.size() < 3) return false;
        int last = gains.size() - 1;
        return (element(gains, last) + element(gains, last - 1) + element(gains, last - 2)) / 3 > 5;
    }

    private static boolean closeVocal(JsonObject context, boolean song) {
        if (context == null) return false;
        if (!song) return true;
        JsonObject features = EffectJson.object(context, "feature");
        return element(EffectJson.array(features, "vocal_drum_bass_other_ratio"), 0) < 0.2;
    }

    private static double firstMean(JsonArray array) {
        return (element(array, 0) + element(array, 1) + element(array, 2)) / 3;
    }

    private static double element(JsonArray array, int index) {
        return array == null || index < 0 || index >= array.size() ? Double.NaN : EffectJson.number(array.get(index));
    }

    private static void applyBassTreble(JsonObject effect, JsonObject selected) {
        JsonObject bass = EffectJson.object(selected, "en_bass");
        JsonObject treble = EffectJson.object(selected, "en_treble");
        if (bass == null && treble == null) return;
        if (bass == null || treble == null) {
            copyField(bass == null ? treble : bass, "bt", effect);
            return;
        }
        JsonObject combined = new JsonObject();
        combined.addProperty("on", true);
        bass = EffectJson.object(bass, "bt");
        treble = EffectJson.object(treble, "bt");
        for (String key : List.of("bass", "bassFreq", "bassQ")) {
            combined.addProperty(key, EffectJson.numberOrZero(bass, key));
        }
        for (String key : List.of("treble", "trebleFreq", "trebleQ")) {
            combined.addProperty(key, EffectJson.numberOrZero(treble, key));
        }
        effect.add("bt", combined);
    }

    private static void copyField(JsonObject from, String key, JsonObject target) {
        if (from.has(key)) target.add(key, EffectJson.copyElement(from.get(key)));
        else target.remove(key);
    }

    private static int countEnhancements(JsonObject selected) {
        int result = 0;
        for (String key : List.of("en_bass", "en_treble", "en_vocal")) {
            if (selected.has(key)) result++;
        }
        if (selected.has("en_vibe") || selected.has("en_live")) result++;
        return result;
    }

    private static JsonObject compressor(JsonObject effect, boolean on) {
        JsonObject compressor = EffectJson.object(effect, "cmp");
        if (compressor != null) return compressor;
        compressor = new JsonObject();
        for (String key : List.of("attack", "pregain", "release", "range", "postgain", "threshold")) {
            compressor.addProperty(key, 0);
        }
        compressor.addProperty("ratio", 1);
        compressor.addProperty("on", on);
        effect.add("cmp", compressor);
        return compressor;
    }
}
