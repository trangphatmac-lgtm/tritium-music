package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Saved effect choices. Loading preferences never starts a song lookup or audio update. */
public final class AudioEffectPreferences {
    static final String JSON_KEY = "audioEffects";

    private final ModePreference mode = new ModePreference("Audio Effect", "Off",
            new String[]{"Off", "Smart", "Master"});
    private final ModePreference masterTone = new ModePreference("Master Tone", "Auto", new String[]{
            "Auto", "key_bright", "key_brilliant", "key_transparent", "key_pure", "key_light", "key_clear",
            "key_balanced", "key_mellow", "key_soft", "key_deep", "key_warm", "key_full", "key_thick",
            "key_ethereal", "key_agile", "key_delicate", "key_tight", "key_spacious", "key_intimate",
            "key_open", "key_spatial", "key_surround", "key_depth", "key_layered", "key_wide", "key_textured",
            "key_smooth", "key_quality", "key_rhythmic", "key_colorful", "key_presence", "key_silky"
    });
    private Runnable changeCallback = () -> {};
    private boolean loading;

    public AudioEffectPreferences() {
        mode.setValueCallback(ignored -> fireChanged());
        masterTone.setValueCallback(ignored -> fireChanged());
        masterTone.setHiddenPredicate(ignored -> !isMaster());
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
        return json;
    }

    public void loadFromJsonObject(JsonObject json) {
        if (json == null) return;
        loading = true;
        try {
            loadMode(json, "mode", mode);
            loadMode(json, "masterTone", masterTone);
            // Legacy enhancement switches are ignored; the composer now selects automatically.
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

    public boolean isMaster() { return "Master".equals(mode.getValue()); }
    public ModePreference mode() { return mode; }
    public ModePreference masterTone() { return masterTone; }
}
