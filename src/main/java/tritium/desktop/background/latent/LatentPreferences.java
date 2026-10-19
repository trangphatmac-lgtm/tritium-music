package tritium.desktop.background.latent;

import com.google.gson.JsonObject;
import tritium.desktop.ModePreference;
import tritium.desktop.NumberPreference;
import tritium.desktop.PreferenceValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** Saved light/dark palettes and the small set of Latent controls exposed in the existing settings. */
public final class LatentPreferences {
    public enum Color { BACKGROUND, PRIMARY, ACCENT, SECONDARY }
    private static final int[] DARK = {0xFF09090B, 0xFFF4F4F5, 0xFFF4F4F5, 0xFF71717A};
    private static final int[] LIGHT = {0xFFF5F5F4, 0xFF1C1917, 0xFFEA580C, 0xFF44403C};
    private final Map<String, PreferenceValue<?>> values = new LinkedHashMap<>();
    private final ModePreference colorSource = add("colorSource", new ModePreference("Color source", "cover-theme",
            new String[]{"cover-theme", "theme", "cover-only"}));
    private final ModePreference layers = add("layers", new ModePreference("Layers", "both", new String[]{"both", "mesh", "dithering"}));
    private final PreferenceValue<Boolean> beat = add("beat", new PreferenceValue<>("Follow music", true));
    private final NumberPreference speed = add("speed", new NumberPreference("Speed", 1, .1, 3, .1));
    private Runnable onChange = () -> { };

    public LatentPreferences() {
        for (boolean light : new boolean[]{false, true}) {
            for (Color color : Color.values()) {
                add(key(light, color), new PreferenceValue<>(color.name(), (light ? LIGHT : DARK)[color.ordinal()]));
            }
        }
    }

    private <T extends PreferenceValue<?>> T add(String key, T preference) {
        values.put(key, preference);
        preference.setValueCallback(ignored -> onChange.run());
        return preference;
    }

    private static String key(boolean light, Color color) { return (light ? "light." : "dark.") + color.name(); }
    public void setChangeCallback(Runnable callback) { onChange = callback; }
    public ModePreference colorSource() { return colorSource; }
    public ModePreference layers() { return layers; }
    public PreferenceValue<Boolean> beat() { return beat; }
    public NumberPreference speed() { return speed; }

    @SuppressWarnings("unchecked")
    public PreferenceValue<Integer> color(boolean light, Color color) {
        return (PreferenceValue<Integer>) values.get(key(light, color));
    }

    public void resetPalette(boolean light) {
        for (Color color : Color.values()) color(light, color).setValue((light ? LIGHT : DARK)[color.ordinal()]);
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        values.forEach((key, preference) -> {
            Object value = preference.getValue();
            if (value instanceof Number number) json.addProperty(key, number);
            else if (value instanceof Boolean bool) json.addProperty(key, bool);
            else json.addProperty(key, (String) value);
        });
        return json;
    }

    public void loadFromJsonObject(JsonObject json) {
        values.forEach((key, preference) -> {
            var element = json.get(key);
            if (element == null || !element.isJsonPrimitive()) return;
            var value = element.getAsJsonPrimitive();
            try {
                if (preference instanceof ModePreference mode) {
                    if (value.isString() && mode.getModes().contains(value.getAsString())) mode.setValue(value.getAsString());
                } else if (preference instanceof NumberPreference number) {
                    if (value.isNumber() && Double.isFinite(value.getAsDouble())) number.setValue(value.getAsDouble());
                } else if (preference == beat) {
                    if (value.isBoolean()) beat.setValue(value.getAsBoolean());
                } else if (value.isNumber()) {
                    long argb = value.getAsLong();
                    if (argb < Integer.MIN_VALUE || argb > 0xFFFFFFFFL || value.getAsDouble() != argb) return;
                    @SuppressWarnings("unchecked") var color = (PreferenceValue<Integer>) preference;
                    color.setValue((int) argb | 0xFF000000);
                }
            } catch (RuntimeException ignored) {
                // A damaged setting must not discard the rest of the saved palette.
            }
        });
    }
}
