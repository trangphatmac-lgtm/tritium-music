package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Rectangle;

public class HudElementPreferences {
    private static final String ENABLED_KEY = "enabled";
    private static final String X_KEY = "x";
    private static final String Y_KEY = "y";
    private static final String WIDTH_KEY = "width";
    private static final String HEIGHT_KEY = "height";
    private static final String OPACITY_KEY = "opacity";
    private static final String SCALE_KEY = "scale";

    private final String id;
    private final PreferenceValue<Boolean> enabled;
    private final NumberPreference x;
    private final NumberPreference y;
    private final NumberPreference width;
    private final NumberPreference height;
    private final NumberPreference opacity;
    private final NumberPreference scale;
    private final double minWidth;
    private final double minHeight;

    public HudElementPreferences(String id, boolean enabled, double x, double y, double width, double height,
                                 double opacity, double scale, double minWidth, double minHeight) {
        this.id = id;
        this.enabled = new PreferenceValue<>(id + ".enabled", enabled);
        this.x = new NumberPreference(id + ".x", x, -10000, 10000, 1);
        this.y = new NumberPreference(id + ".y", y, -10000, 10000, 1);
        this.width = new NumberPreference(id + ".width", width, minWidth, 10000, 1);
        this.height = new NumberPreference(id + ".height", height, minHeight, 10000, 1);
        this.opacity = new NumberPreference(id + ".opacity", opacity, 0.05, 1.0, 0.01);
        this.scale = new NumberPreference(id + ".scale", scale, 0.5, 2.0, 0.01);
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    void setChangeCallback(Runnable callback) {
        enabled.setValueCallback(value -> callback.run());
        x.setValueCallback(value -> callback.run());
        y.setValueCallback(value -> callback.run());
        width.setValueCallback(value -> callback.run());
        height.setValueCallback(value -> callback.run());
        opacity.setValueCallback(value -> callback.run());
        scale.setValueCallback(value -> callback.run());
    }

    JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty(ENABLED_KEY, enabled.getValue());
        json.addProperty(X_KEY, x.getValue());
        json.addProperty(Y_KEY, y.getValue());
        json.addProperty(WIDTH_KEY, width.getValue());
        json.addProperty(HEIGHT_KEY, height.getValue());
        json.addProperty(OPACITY_KEY, opacity.getValue());
        json.addProperty(SCALE_KEY, scale.getValue());
        return json;
    }

    void loadFromJsonObject(JsonObject json) {
        if (json == null) {
            return;
        }

        applyBoolean(json, ENABLED_KEY, enabled);
        applyNumber(json, X_KEY, x);
        applyNumber(json, Y_KEY, y);
        applyNumber(json, WIDTH_KEY, width);
        applyNumber(json, HEIGHT_KEY, height);
        applyNumber(json, OPACITY_KEY, opacity);
        applyNumber(json, SCALE_KEY, scale);
    }

    static void applyBoolean(JsonObject json, String key, PreferenceValue<Boolean> preference) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            preference.setValue(element.getAsBoolean());
        }
    }

    static void applyNumber(JsonObject json, String key, NumberPreference preference) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            preference.setValue(element.getAsDouble());
        }
    }

    static void applyMode(JsonObject json, String key, ModePreference preference) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        String value = element.getAsString();
        if (preference.getModes().contains(value)) {
            preference.setValue(value);
        }
    }

    public void setBounds(double x, double y, double width, double height) {
        this.x.setValue(x);
        this.y.setValue(y);
        this.width.setValue(width);
        this.height.setValue(height);
    }

    public void clampTo(Rectangle screenBounds) {
        double clampedWidth = Math.max(minWidth, Math.min(width.getValue(), screenBounds.getWidth()));
        double clampedHeight = Math.max(minHeight, Math.min(height.getValue(), screenBounds.getHeight()));
        double maxX = screenBounds.getX() + Math.max(0, screenBounds.getWidth() - clampedWidth);
        double maxY = screenBounds.getY() + Math.max(0, screenBounds.getHeight() - clampedHeight);
        width.setValue(clampedWidth);
        height.setValue(clampedHeight);
        x.setValue(Math.max(screenBounds.getX(), Math.min(maxX, x.getValue())));
        y.setValue(Math.max(screenBounds.getY(), Math.min(maxY, y.getValue())));
    }

    public String id() {
        return id;
    }

    public PreferenceValue<Boolean> enabled() {
        return enabled;
    }

    public NumberPreference x() {
        return x;
    }

    public NumberPreference y() {
        return y;
    }

    public NumberPreference width() {
        return width;
    }

    public NumberPreference height() {
        return height;
    }

    public NumberPreference opacity() {
        return opacity;
    }

    public NumberPreference scale() {
        return scale;
    }

    public double minWidth() {
        return minWidth;
    }

    public double minHeight() {
        return minHeight;
    }
}
