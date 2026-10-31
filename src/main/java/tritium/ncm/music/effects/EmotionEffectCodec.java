package tritium.ncm.music.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline port of the 9.5.40 emotion pipeline (Hermes modules 868, 872–962).
 * The model predicts 19 effect scalars, not PCM. Its output is merged into the
 * original 64 templates so PEQ, limiter, early reflections and other modules survive.
 * Resources are extracted from HBC literals with signed integer decoding.
 */
public final class EmotionEffectCodec {
    private static final double EPSILON = 0.0001;
    private static final List<String> EMOTIONS = List.of("default", "ecstatic", "passionate",
            "energetic", "joyful", "sweet", "confident", "romantic", "relaxed", "healing",
            "calm", "nostalgic", "lonely", "sad", "bitter", "depressed");
    private static final List<String> FAMILIES = List.of("pop-family", "folk-family",
            "rhythm-family", "instrumental-family");
    private static final String[] SCALARS = {"dynamic_range", "loudness_lufs", "stereo_width_ratio",
            "repbpm", "chorus_loudness", "vocal_main_freq", "vocal_time_ratio",
            "harmonic_intensity_ratio", "vocal_dynamic_range", "drum_time_ratio",
            "rhythm_complexity", "vocal_energy_ratio", "drum_energy_ratio", "bass_energy_ratio",
            "other_instrument_energy_ratio"};
    private static final double[][] INPUT_RANGES = {{0,30},{-40,0},{0,1},{30,300},{-40,0},
            {0,1200},{0,1},{0,1},{0,30},{0,1},{0,10},{0,1},{0,1},{0,1},{0,1}};
    private static final double[][] OUTPUT_RANGES = {{0,10},{-96.3,0},{-96.3,0},{0,1},{0,1},
            {0,5000},{0,1},{0,10},{0,10}};
    private static final String[] PRUNE = {"eq","cmp","limiter","bt","peq","rvb","dly",
            "se","chorus","flanger","rotate","alc","spspatial"};
    private static final JsonObject TEMPLATES = load("templates.json");
    private static final JsonObject LABELS = load("labels.json");
    private static final JsonObject CLASSIFICATION = load("classification.json");
    private static final JsonObject MULTICHANNEL = load("multichannel.json");
    private final JsonObject config;
    private final boolean specialMultichannel;

    /** Ordinary desktop stereo does not set Android's special multichannel flag. */
    public EmotionEffectCodec() { this(load("config.json"), false); }

    /** A complete config snapshot, useful for applying an explicitly fetched config or testing. */
    public EmotionEffectCodec(JsonObject config, boolean specialMultichannel) {
        this.config = EffectJson.copy(config);
        this.specialMultichannel = specialMultichannel;
    }

    public record Prepared(int emotionIndex, int genreIndex, float[] features, float[] base,
                           JsonObject template, boolean inferenceEligible, String label, String reason,
                           String templateKey, double chorusLoudness, boolean degrade,
                           boolean specialMultichannel) {
        public Prepared {
            features = features.clone();
            base = base.clone();
            template = EffectJson.copy(template);
        }
        @Override public float[] features() { return features.clone(); }
        @Override public float[] base() { return base.clone(); }
        @Override public JsonObject template() { return EffectJson.copy(template); }
    }

    /** Sanitizes raw per-song server features and selects the corresponding original template. */
    public Prepared prepare(JsonObject feature, long songId) {
        JsonObject clean = new JsonObject();
        List<String> missing = new ArrayList<>();
        JsonArray mel = EffectJson.array(feature, "mel_spectrogram_32");
        if (mel != null && mel.size() == 32 && allFinite(mel)) clean.add("mel_spectrogram_32", EffectJson.copyElement(mel));
        else missing.add("mel_spectrogram_32");
        for (int i = 0; i < SCALARS.length; i++) {
            double value = EffectJson.number(feature, SCALARS[i]);
            if (inRange(value, INPUT_RANGES[i][0], INPUT_RANGES[i][1])) clean.addProperty(SCALARS[i], value);
            else missing.add(SCALARS[i]);
        }
        double genre = EffectJson.number(feature, "genre_id");
        if (Double.isFinite(genre)) clean.addProperty("genre_id", genre);
        String keyMode = string(feature, "key_mode");
        if (!keyMode.isBlank()) clean.addProperty("key_mode", keyMode.trim());
        String label = mapEmotionTags(string(feature, "emotion_label"));
        if (label == null) label = predictEmotion(clean);
        String family = "pop-family";
        if (Double.isFinite(genre)) {
            String genreKey = genre == Math.rint(genre) ? Long.toString((long) genre) : Double.toString(genre);
            String mapped = string(LABELS.getAsJsonObject("genreFamilies"), genreKey);
            if (!mapped.isEmpty()) family = mapped;
        }
        JsonObject overrides = EffectJson.object(config, "emotionTagOverride");
        if (songId > 0 && overrides != null) {
            for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
                if (EMOTIONS.contains(entry.getKey()) && containsId(entry.getValue(), songId)) label = entry.getKey();
            }
        }
        boolean forcedDefault = songId > 0 && containsId(config.get("defaultTemplateSongIds"), songId);
        String templateLabel = forcedDefault ? "default" : label;
        String templateKey = templateLabel + ":" + family;
        JsonObject template = EffectJson.copy(TEMPLATES.getAsJsonObject(templateKey));
        float[] features = new float[64];
        JsonArray sanitizedMel = EffectJson.array(clean, "mel_spectrogram_32");
        if (sanitizedMel != null) for (int i = 0; i < 32; i++) features[i] = sanitizedMel.get(i).getAsFloat();
        for (int i = 0; i < SCALARS.length; i++) features[32+i] = (float) EffectJson.numberOrZero(clean, SCALARS[i]);
        features[47] = on(template, "rvb") ? 1 : 0;
        features[48] = on(template, "dly") ? 1 : 0;
        features[49] = on(template, "se") ? 1 : 0;
        String reason = forcedDefault ? "default_template_song" : missing.isEmpty() ? "" : "missing_audio_features: " + String.join(", ", missing);
        return new Prepared(EMOTIONS.indexOf(label), FAMILIES.indexOf(family), features,
                encodeBase(template), template, !forcedDefault && missing.isEmpty(), label, reason,
                templateKey, EffectJson.number(clean, "chorus_loudness"),
                songId > 0 && containsId(config.get("degradeSongIds"), songId), specialMultichannel);
    }

    /** Returns the complete DSP object; malformed model output throws so the caller can label fallback. */
    public JsonObject decode(Prepared prepared, float[] output) {
        Objects.requireNonNull(prepared, "prepared");
        if (output == null || output.length != 32) throw new IllegalArgumentException("Emotion output must contain 32 values");
        for (int i = 0; i < 19; i++) {
            double low = i < 10 ? -12 : OUTPUT_RANGES[i-10][0];
            double high = i < 10 ? 12 : OUTPUT_RANGES[i-10][1];
            if (!inRange(output[i], low, high)) throw new IllegalArgumentException("Emotion output[" + i + "] outside [" + low + ", " + high + "]");
        }
        JsonObject effect = prepared.template();
        JsonObject eq = child(effect, "eq");
        eq.addProperty("on", true);
        JsonArray gains = new JsonArray();
        for (int i = 0; i < 10; i++) gains.add(new JsonPrimitive(output[i]));
        eq.add("eqs", gains);
        if (on(effect, "rvb")) {
            JsonObject rvb = effect.getAsJsonObject("rvb");
            child(rvb, "rvb").addProperty("dtime", output[10]);
            child(rvb, "ol").addProperty("dry", output[11]);
            child(rvb, "ol").addProperty("rvb", output[12]);
            child(rvb, "rvb").addProperty("rshape", output[13] * 100.0);
            child(rvb, "rvb").addProperty("diffusion", output[14] * 100.0);
        }
        if (on(effect, "dly")) {
            JsonObject delay = effect.getAsJsonObject("dly");
            delay.addProperty("delay", output[15]); delay.addProperty("feedback", output[16]);
        }
        if (on(effect, "se")) {
            JsonObject stereo = effect.getAsJsonObject("se");
            stereo.addProperty("stereoizer", output[17]); stereo.addProperty("presence", output[18]);
        }
        return finish(prepared, effect);
    }

    /** The same template fallback used when the original kernel has no features or inference fails. */
    public JsonObject fallback(Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return finish(prepared, prepared.template());
    }

    public List<String> templateKeys() { return List.copyOf(TEMPLATES.entrySet().stream().map(Map.Entry::getKey).toList()); }

    private static JsonObject finish(Prepared p, JsonObject effect) {
        // module940 prunes before loudness/multichannel/degradation post-processing.
        for (String key : PRUNE) {
            JsonObject value = EffectJson.object(effect, key);
            if (explicitlyOff(value)) { JsonObject off = new JsonObject(); off.addProperty("on", false); effect.add(key, off); }
        }
        if (Double.isFinite(p.chorusLoudness) && p.chorusLoudness > -6 + EPSILON && on(effect, "se")) {
            effect.getAsJsonObject("se").addProperty("sshaper", false);
        }
        if (p.specialMultichannel) effect = applyMultichannelPostProcess(effect, p.emotionIndex);
        if (p.degrade) effect = applyDegradation(effect);
        return effect;
    }

    private static float[] encodeBase(JsonObject template) {
        float[] result = new float[32];
        JsonArray eq = EffectJson.array(EffectJson.object(template, "eq"), "eqs");
        if (eq != null) {
            if (eq.size() != 10 || !allFinite(eq)) throw new IllegalArgumentException("Invalid template EQ");
            for (int i = 0; i < 10; i++) result[i] = eq.get(i).getAsFloat();
        }
        if (on(template, "rvb")) {
            JsonObject rvb = template.getAsJsonObject("rvb"), params = rvb.getAsJsonObject("rvb"), ol = rvb.getAsJsonObject("ol");
            result[10] = finiteFloat(params, "dtime"); result[11] = finiteFloat(ol, "dry"); result[12] = finiteFloat(ol, "rvb");
            result[13] = finiteFloat(params, "rshape") / 100; result[14] = finiteFloat(params, "diffusion") / 100;
        } else { result[11] = -96.3f; result[12] = -96.3f; }
        if (on(template, "dly")) { result[15] = finiteFloat(template.getAsJsonObject("dly"), "delay"); result[16] = finiteFloat(template.getAsJsonObject("dly"), "feedback"); }
        if (on(template, "se")) { result[17] = finiteFloat(template.getAsJsonObject("se"), "stereoizer"); result[18] = finiteFloat(template.getAsJsonObject("se"), "presence"); }
        return result;
    }

    /** Original weighted Gaussian feature classifier, used only when direct/tag labels are absent. */
    private static String predictEmotion(JsonObject clean) {
        JsonArray mel = EffectJson.array(clean, "mel_spectrogram_32");
        if (mel == null && !clean.has("repbpm")) return "default";
        String best = "ecstatic"; double bestScore = Double.NEGATIVE_INFINITY;
        JsonObject weights = CLASSIFICATION.getAsJsonObject("3");
        for (int e = 1; e < EMOTIONS.size(); e++) {
            String label = EMOTIONS.get(e); double total = 0, weight = 0;
            for (Map.Entry<String, JsonElement> entry : weights.entrySet()) {
                String key = entry.getKey(); if (!clean.has(key)) continue;
                double score;
                if (key.equals("mel_spectrogram_32")) {
                    score = 0.75 * gaussian(label, 12, mean(mel,24,32)) + 0.25 * gaussian(label,13,mean(mel,0,8));
                } else if (key.equals("key_mode")) {
                    double major = CLASSIFICATION.getAsJsonObject("7").get(label).getAsDouble();
                    score = string(clean, key).endsWith("Major") ? major : 1-major;
                } else if (key.equals("genre_id")) {
                    double id = clean.get(key).getAsDouble();
                    score = containsNumber(CLASSIFICATION.getAsJsonObject("10").get(label),id) ? 1
                            : containsNumber(CLASSIFICATION.getAsJsonObject("11").get(label),id) ? 0.1 : 0.4;
                } else if (key.equals("stereo_width_ratio")) {
                    double mean = CLASSIFICATION.getAsJsonObject("14").get(label).getAsDouble();
                    score = gaussian(clean.get(key).getAsDouble(),mean,0.08);
                } else {
                    int slot = switch (key) {
                        case "repbpm" -> 6; case "loudness_lufs" -> 8; case "dynamic_range" -> 9;
                        case "chorus_loudness" -> 15; case "rhythm_complexity" -> 16; case "drum_time_ratio" -> 17;
                        case "vocal_time_ratio" -> 18; case "harmonic_intensity_ratio" -> 19; case "drum_energy_ratio" -> 20;
                        case "vocal_dynamic_range" -> 21; case "vocal_energy_ratio" -> 22; case "vocal_main_freq" -> 23;
                        case "bass_energy_ratio" -> 24; case "other_instrument_energy_ratio" -> 25;
                        default -> throw new IllegalStateException("Unmapped emotion classifier feature " + key);
                    };
                    double value = clean.get(key).getAsDouble();
                    if (key.equals("rhythm_complexity") && value > 1) value /= 10;
                    score = gaussian(label,slot,value);
                }
                double w = entry.getValue().getAsDouble(); total += score*w; weight += w;
            }
            double score = Math.abs(weight) < EPSILON ? 0 : total/weight;
            if (score > bestScore) { best = label; bestScore = score; }
        }
        return best;
    }

    private static String mapEmotionTags(String raw) {
        String trimmed = raw.trim(); if (EMOTIONS.contains(trimmed)) return trimmed;
        String[] tags = trimmed.split(",");
        for (String dictionary : List.of("preferredTags", "synonymTags")) {
            JsonObject map = LABELS.getAsJsonObject(dictionary);
            for (String tag : tags) {
                String value = string(map, tag.trim()); if (!value.isEmpty()) return value;
            }
        }
        return null;
    }

    /** This is the APK's special multichannel branch, not ordinary two-channel playback. */
    public static JsonObject applyMultichannelPostProcess(JsonObject original, int emotionIndex) {
        JsonObject effect = EffectJson.copy(original);
        for (String key : List.of("se","dly","alc","cmp","bt","chorus","flanger","spspatial","rotate")) {
            JsonObject module = EffectJson.object(effect,key); if (module != null) module.addProperty("on",false);
        }
        JsonObject eq = child(effect,"eq"); eq.addProperty("on",true);
        JsonArray old = EffectJson.array(eq,"eqs"), adjustment = EffectJson.array(MULTICHANNEL,Integer.toString(emotionIndex));
        JsonArray gains = new JsonArray();
        for (int i=0;i<10;i++) {
            double gain = old != null && i<old.size() ? EffectJson.number(old.get(i)) : 0;
            if (!Double.isFinite(gain)) gain=0;
            if (adjustment != null) gain=clamp(gain+adjustment.get(i).getAsDouble()*0.3,-12,12);
            gains.add(new JsonPrimitive(gain));
        }
        eq.add("eqs",gains);
        JsonObject rvb=EffectJson.object(effect,"rvb");
        if (EffectJson.on(rvb)) {
            transform(EffectJson.object(rvb,"rvb"),"dtime",1.15,0,0,10);
            transform(EffectJson.object(rvb,"rvb"),"rshape",1,10,0,100);
            transform(EffectJson.object(rvb,"rvb"),"diffusion",1,10,0,100);
            transform(EffectJson.object(rvb,"ol"),"rvb",1,1.5,-96.3,0);
        }
        transform(EffectJson.object(rvb,"ol"),"dry",1,0,-96.3,0);
        return effect;
    }

    /** Per-song degradation is applied after ordinary or special multichannel post-processing. */
    public static JsonObject applyDegradation(JsonObject original) {
        JsonObject effect = EffectJson.copy(original);
        JsonObject stereo=EffectJson.object(effect,"se");
        if (stereo != null) stereo.addProperty("sshaper",false);
        for (String key : List.of("dly","chorus","flanger")) {
            JsonObject module=EffectJson.object(effect,key); if(module!=null)module.addProperty("on",false);
        }
        JsonObject rvb=EffectJson.object(effect,"rvb");
        transform(EffectJson.object(rvb,"ol"),"rvb",1,-3,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        transform(EffectJson.object(rvb,"ol"),"er",1,-3,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        if (rvb!=null && !explicitlyOff(rvb)) transform(EffectJson.object(rvb,"rvb"),"dtime",0.7,0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        JsonObject eq=EffectJson.object(effect,"eq"); JsonArray gains=EffectJson.array(eq,"eqs");
        if (eq!=null && !explicitlyOff(eq) && gains!=null) {
            JsonArray reduced=new JsonArray(); for(JsonElement gain:gains)reduced.add(new JsonPrimitive(gain.getAsDouble()*0.8)); eq.add("eqs",reduced);
        }
        JsonObject peq=EffectJson.object(effect,"peq"); JsonArray filters=EffectJson.array(peq,"f");
        if (peq!=null && !explicitlyOff(peq) && filters!=null) for(JsonElement value:filters) {
            if(value.isJsonObject() && !explicitlyOff(value.getAsJsonObject())) transform(value.getAsJsonObject(),"gain",0.8,0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        }
        if (stereo!=null && !explicitlyOff(stereo)) {
            transform(stereo,"stereoizer",0.6,0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
            transform(stereo,"presence",0.6,0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        }
        return effect;
    }

    private static double gaussian(String label,int slot,double value) { JsonArray pair=CLASSIFICATION.getAsJsonObject(Integer.toString(slot)).getAsJsonArray(label); return gaussian(value,pair.get(0).getAsDouble(),pair.get(1).getAsDouble()); }
    private static double gaussian(double value,double mean,double sigma) { double z=(value-mean)/sigma; return Math.exp(-0.5*z*z); }
    private static double mean(JsonArray values,int from,int to) { double total=0;for(int i=from;i<to;i++)total+=values.get(i).getAsDouble();return total/(to-from); }
    private static boolean inRange(double value,double min,double max) { return Double.isFinite(value) && value>=min-EPSILON && value<=max+EPSILON; }
    private static boolean allFinite(JsonArray array) { for(JsonElement value:array)if(!Double.isFinite(EffectJson.number(value)))return false;return true; }
    private static boolean containsId(JsonElement list,long id) { return containsNumber(list,id); }
    private static boolean containsNumber(JsonElement list,double value) { if(list!=null && list.isJsonArray())for(JsonElement item:list.getAsJsonArray())if(EffectJson.number(item)==value)return true;return false; }
    private static boolean on(JsonObject parent,String key) { return EffectJson.on(EffectJson.object(parent,key)); }
    private static boolean explicitlyOff(JsonObject object) { JsonElement value=object==null?null:object.get("on");return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean(); }
    private static String string(JsonObject object,String key) { JsonElement value=object==null?null:object.get(key);return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()?value.getAsString():""; }
    private static JsonObject child(JsonObject object,String key) { JsonObject child=EffectJson.object(object,key);if(child==null){child=new JsonObject();object.add(key,child);}return child; }
    private static float finiteFloat(JsonObject object,String key) { double value=EffectJson.number(object,key);if(!Double.isFinite(value))throw new IllegalArgumentException("Missing template scalar "+key);return (float)value; }
    private static double clamp(double value,double low,double high) { return Math.max(low,Math.min(high,value)); }
    private static void transform(JsonObject object,String key,double scale,double add,double low,double high) { double value=EffectJson.number(object,key);if(Double.isFinite(value))object.addProperty(key,clamp(value*scale+add,low,high)); }
    private static JsonObject load(String name) {
        try(InputStream stream=EmotionEffectCodec.class.getResourceAsStream("/tritium/audioeffects/emotion/"+name)) {
            if(stream==null)throw new IllegalStateException("Missing emotion resource "+name);
            return new JsonParser().parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException exception){throw new IllegalStateException("Cannot load emotion resource "+name,exception);}
    }
}
