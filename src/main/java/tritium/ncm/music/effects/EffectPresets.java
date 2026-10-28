package tritium.ncm.music.effects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verified, bundled fallbacks for the 9.5.40 smart and Master effect protocols. */
public final class EffectPresets {
    public static final String DEFAULT_MASTER_KEY = "key_transparent";
    private final JsonObject smart = load("smart-presets.json");
    private final JsonObject master = load("master-base.json");
    private final JsonObject diy = load("master-diy.json");
    private final Map<String, String> masterNames;

    public EffectPresets() {
        Map<String, String> names = new LinkedHashMap<>();
        master.entrySet().forEach(entry -> names.put(entry.getKey(),
                entry.getValue().getAsJsonObject().get("name").getAsString()));
        masterNames = Collections.unmodifiableMap(names);
    }

    /** Returns a standalone DSP object, preferring the richer aev2 protocol. */
    public JsonObject smart(String key) {
        JsonObject value = EffectJson.object(smart, key == null ? "0" : key);
        if (value == null) value = smart.getAsJsonObject("0");
        JsonObject v2 = EffectJson.object(value, "aev2");
        return EffectJson.copy(v2 == null ? value : v2);
    }

    /** The composer takes exactly one template key with its {name,effect} value. */
    public JsonObject master(String key) {
        String actual = key != null && master.has(key) ? key : DEFAULT_MASTER_KEY;
        JsonObject selected = new JsonObject();
        selected.add(actual, EffectJson.copyElement(master.get(actual)));
        return selected;
    }

    public JsonObject masterBase() { return EffectJson.copy(master); }
    public JsonObject diy() { return EffectJson.copy(diy); }
    public Map<String, String> masterNames() { return masterNames; }

    private static JsonObject load(String name) {
        String path = "/tritium/audioeffects/" + name;
        try (InputStream stream = EffectPresets.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing audio effect resource: " + name);
            return new JsonParser().parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalStateException("Could not load audio effect resource: " + name, exception);
        }
    }
}
