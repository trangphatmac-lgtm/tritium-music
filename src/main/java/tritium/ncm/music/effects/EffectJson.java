package tritium.ncm.music.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/** Small tree helpers compatible with the application's Gson 2.2 API. */
final class EffectJson {
    private EffectJson() {}

    static JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : copyElement(source).getAsJsonObject();
    }

    static JsonElement copyElement(JsonElement source) {
        if (source.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                result.add(entry.getKey(), copyElement(entry.getValue()));
            }
            return result;
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement value : source.getAsJsonArray()) result.add(copyElement(value));
            return result;
        }
        return source; // JsonNull and JsonPrimitive are immutable.
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    static JsonArray array(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return Double.NaN;
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : Double.NaN;
    }

    static double number(JsonObject parent, String key) {
        return number(parent == null ? null : parent.get(key));
    }

    static double numberOrZero(JsonObject parent, String key) {
        double value = number(parent, key);
        return Double.isNaN(value) ? 0 : value;
    }

    static boolean on(JsonObject parent) {
        if (parent == null) return false;
        JsonElement value = parent.get("on");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
