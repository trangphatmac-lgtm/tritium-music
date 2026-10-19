package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.desktop.background.latent.LatentPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** Main-window backgrounds only; deliberately independent of lyric and HUD preferences. */
public final class BackgroundPreferences {
    private final Map<String, PreferenceValue<?>> values = new LinkedHashMap<>();
    private final ModePreference mode = mode("mode", "static", "static", "picture", "video", "shader", "latent");
    private final LatentPreferences latent = new LatentPreferences();
    private final ModePreference pictureSource = mode("pictureSource", "file", "file", "folder");
    private final PreferenceValue<String> pictureFile = add("pictureFile", new PreferenceValue<>("Picture", ""));
    private final PreferenceValue<String> pictureFolder = add("pictureFolder", new PreferenceValue<>("Folder", ""));
    private final PreferenceValue<String> videoFile = add("videoFile", new PreferenceValue<>("Video", ""));
    private final NumberPreference interval = number("interval", 30, 5, 300, 1);
    private final PreferenceValue<Boolean> shuffle = add("shuffle", new PreferenceValue<>("Shuffle", false));
    private final ModePreference fit = mode("fit", "cover", "cover", "contain", "stretch");
    private final NumberPreference strength = number("strength", .65, 0, 1, .01);
    private final NumberPreference panelOpacity = number("panelOpacity", .25, 0, 1, .01);
    private final ModePreference shader = mode("shader", "rise", "rise", "aurora", "waves", "nebula", "liquid", "mandala", "torus");
    private final NumberPreference shaderSpeed = number("shaderSpeed", 1, .1, 3, .1);
    private Runnable onChange = () -> { };

    private <T extends PreferenceValue<?>> T add(String key, T value) {
        values.put(key, value);
        value.setValueCallback(ignored -> onChange.run());
        return value;
    }

    private ModePreference mode(String key, String initial, String... modes) {
        return add(key, new ModePreference(key, initial, modes));
    }

    private NumberPreference number(String key, double initial, double min, double max, double step) {
        return add(key, new NumberPreference(key, initial, min, max, step));
    }

    void setChangeCallback(Runnable callback) {
        onChange = callback;
        latent.setChangeCallback(callback);
    }

    JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        values.forEach((key, preference) -> {
            Object value = preference.getValue();
            if (value instanceof Boolean bool) json.addProperty(key, bool);
            else if (value instanceof Number number) json.addProperty(key, number);
            else json.addProperty(key, (String) value);
        });
        json.add("latent", latent.toJsonObject());
        return json;
    }

    void loadFromJsonObject(JsonObject json) {
        if (json.has("latent") && json.get("latent").isJsonObject()) latent.loadFromJsonObject(json.getAsJsonObject("latent"));
        values.forEach((key, preference) -> {
            JsonElement element = json.get(key);
            if (element == null || !element.isJsonPrimitive()) return;
            try {
                if (preference instanceof ModePreference choice) {
                    if (choice.getModes().contains(element.getAsString())) choice.setValue(element.getAsString());
                } else if (preference instanceof NumberPreference number) {
                    double value = element.getAsDouble();
                    if (Double.isFinite(value)) number.setValue(value);
                } else if (preference == shuffle) {
                    if (element.getAsJsonPrimitive().isBoolean()) shuffle.setValue(element.getAsBoolean());
                } else if (element.getAsJsonPrimitive().isString()) {
                    @SuppressWarnings("unchecked") PreferenceValue<String> path = (PreferenceValue<String>) preference;
                    path.setValue(element.getAsString());
                }
            } catch (RuntimeException ignored) {
                // A malformed entry must not prevent the remaining preferences from loading.
            }
        });
    }

    public ModePreference mode() { return mode; }
    public LatentPreferences latent() { return latent; }
    public ModePreference pictureSource() { return pictureSource; }
    public PreferenceValue<String> pictureFile() { return pictureFile; }
    public PreferenceValue<String> pictureFolder() { return pictureFolder; }
    public PreferenceValue<String> videoFile() { return videoFile; }
    public NumberPreference interval() { return interval; }
    public PreferenceValue<Boolean> shuffle() { return shuffle; }
    public ModePreference fit() { return fit; }
    public NumberPreference strength() { return strength; }
    public NumberPreference panelOpacity() { return panelOpacity; }
    public ModePreference shader() { return shader; }
    public NumberPreference shaderSpeed() { return shaderSpeed; }
}
