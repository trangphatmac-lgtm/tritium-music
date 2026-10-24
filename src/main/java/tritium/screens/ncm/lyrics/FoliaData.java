// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Read-only upstream design data. No script engine or browser is involved. */
final class FoliaData {
    private static final Map<String, JsonElement> CACHE = new HashMap<>();
    static String resource(String name) {
        try (var in = FoliaData.class.getResourceAsStream("/tritium/lyrics/folia/" + name)) {
            if (in == null) throw new IOException("Missing Folia resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static JsonElement data(String name) { return CACHE.computeIfAbsent(name, n -> new JsonParser().parse(resource(n))); }
    static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject() ? parent.getAsJsonObject(name) : new JsonObject();
    }
    static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray() ? parent.getAsJsonArray(name) : new JsonArray();
    }
    static double n(JsonObject obj, String key, double fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsDouble();
    }
    static String s(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
    static boolean b(JsonObject obj, String key) { return obj != null && obj.has(key) && obj.get(key).getAsBoolean(); }
    static int variant(long seed, int index, int count) {
        // Coprime stride walks the whole catalog without immediate repetitions.
        return Math.floorMod((int)(seed ^ (seed >>> 32)) + Math.max(0, index) * 37, count);
    }
    static JsonObject profile(String resource, LyricFrame frame) {
        JsonArray items = data(resource).getAsJsonArray();
        return items.get(variant(frame.seed(), frame.index(), items.size())).getAsJsonObject();
    }
}
