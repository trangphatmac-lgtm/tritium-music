package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Saved effect choices. Loading preferences never starts a song lookup or audio update. */
public final class AudioEffectPreferences {
    static final String JSON_KEY = "audioEffects";

    private final ModePreference mode = new ModePreference("Audio Effect", "Off",
            new String[]{"Off", "Smart", "Master", "Manual"});
    private final ModePreference masterTone = new ModePreference("Master Tone", "Auto", new String[]{
            "Auto", "key_bright", "key_brilliant", "key_transparent", "key_pure", "key_light", "key_clear",
            "key_balanced", "key_mellow", "key_soft", "key_deep", "key_warm", "key_full", "key_thick",
            "key_ethereal", "key_agile", "key_delicate", "key_tight", "key_spacious", "key_intimate",
            "key_open", "key_spatial", "key_surround", "key_depth", "key_layered", "key_wide", "key_textured",
            "key_smooth", "key_quality", "key_rhythmic", "key_colorful", "key_presence", "key_silky"
    });
    private final PreferenceValue<Boolean> bass = new PreferenceValue<>("Manual Bass", false);
    private final PreferenceValue<Boolean> treble = new PreferenceValue<>("Manual Treble", false);
    private final PreferenceValue<Boolean> vocal = new PreferenceValue<>("Manual Vocal", false);
    private final PreferenceValue<Boolean> vibe = new PreferenceValue<>("Manual Atmosphere", false);
    private final PreferenceValue<Boolean> live = new PreferenceValue<>("Manual Live", false);
    private Runnable changeCallback = () -> {};
    private boolean loading;
    private boolean updatingEnhancements;

    public AudioEffectPreferences() {
        mode.setValueCallback(ignored -> fireChanged());
        masterTone.setValueCallback(ignored -> fireChanged());
        masterTone.setHiddenPredicate(ignored -> !isMaster());
        for (var option : List.of(bass, treble, vocal, vibe, live)) {
            option.setHiddenPredicate(ignored -> !isManual());
            option.setValueCallback(value -> {
                if (loading || updatingEnhancements) return;
                updatingEnhancements = true;
                try {
                    if (Boolean.TRUE.equals(value)) {
                        if (option == vibe) live.setValue(false);
                        if (option == live) vibe.setValue(false);
                    }
                } finally { updatingEnhancements = false; }
                fireChanged();
            });
        }
    }

    public void setChangeCallback(Runnable callback) {
        changeCallback = callback == null ? () -> {} : callback;
    }

    private void fireChanged() {
        if (!loading) changeCallback.run();
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode.getValue());
        json.addProperty("masterTone", masterTone.getValue());
        JsonObject manual = new JsonObject();
        manual.addProperty("bass", bass.getValue());
        manual.addProperty("treble", treble.getValue());
        manual.addProperty("vocal", vocal.getValue());
        manual.addProperty("vibe", vibe.getValue());
        manual.addProperty("live", live.getValue());
        json.add("manual", manual);
        return json;
    }

    public void loadFromJsonObject(JsonObject json) {
        if (json == null) return;
        loading = true;
        try {
            loadMode(json, "mode", mode);
            loadMode(json, "masterTone", masterTone);
            // Ignore legacy top-level switches; manual choices have their own namespace.
            JsonElement manual = json.get("manual");
            if (manual != null && manual.isJsonObject()) {
                JsonObject choices = manual.getAsJsonObject();
                loadBoolean(choices, "bass", bass);
                loadBoolean(choices, "treble", treble);
                loadBoolean(choices, "vocal", vocal);
                loadBoolean(choices, "vibe", vibe);
                loadBoolean(choices, "live", live);
                if (Boolean.TRUE.equals(vibe.getValue()) && Boolean.TRUE.equals(live.getValue())) live.setValue(false);
            }
        } finally {
            loading = false;
        }
    }

    private static void loadMode(JsonObject json, String key, ModePreference preference) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && preference.getModes().contains(value.getAsString())) {
            preference.setValue(value.getAsString());
        }
    }

    private static void loadBoolean(JsonObject json, String key, PreferenceValue<Boolean> preference) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
            preference.setValue(value.getAsBoolean());
    }

    public List<String> manualEnhancements() {
        List<String> selected = new ArrayList<>(5);
        if (Boolean.TRUE.equals(bass.getValue())) selected.add("en_bass");
        if (Boolean.TRUE.equals(treble.getValue())) selected.add("en_treble");
        if (Boolean.TRUE.equals(vocal.getValue())) selected.add("en_vocal");
        if (Boolean.TRUE.equals(vibe.getValue())) selected.add("en_vibe");
        if (Boolean.TRUE.equals(live.getValue())) selected.add("en_live");
        return List.copyOf(selected);
    }

    public boolean isManual() { return "Manual".equals(mode.getValue()); }
    public boolean isMaster() { return "Master".equals(mode.getValue()); }
    public ModePreference mode() { return mode; }
    public ModePreference masterTone() { return masterTone; }
    public PreferenceValue<Boolean> bass() { return bass; }
    public PreferenceValue<Boolean> treble() { return treble; }
    public PreferenceValue<Boolean> vocal() { return vocal; }
    public PreferenceValue<Boolean> vibe() { return vibe; }
    public PreferenceValue<Boolean> live() { return live; }
}
