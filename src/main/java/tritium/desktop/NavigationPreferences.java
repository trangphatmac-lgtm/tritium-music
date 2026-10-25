// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Folia browsing behavior; see THIRD_PARTY_NOTICES.md.
package tritium.desktop;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Saved browsing preferences, including Folia's card falloff and Bravais wall look. */
public final class NavigationPreferences {
    private final Map<String, PreferenceValue<?>> values = new LinkedHashMap<>();
    private final Map<String, String> positions = new LinkedHashMap<>();
    private Runnable changed = () -> {};
    private final ModePreference mode = mode("mode", "List", "List", "Coverflow", "Wall");
    private final ModePreference coverflow = mode("coverflow", "Mirror", "OldSchool", "Mirror", "Card");
    private final ModePreference cardStyle = mode("cardStyle", "card", "image", "card");
    private final PreferenceValue<Boolean> rememberPosition = flag("rememberPosition", true);
    private final PreferenceValue<Boolean> fullBleed = flag("fullBleed", false);
    private final PreferenceValue<Boolean> squareCards = flag("squareCards", false);
    private final NumberPreference minScale = number("minScale", .45, .2, 1.1, .01);
    private final NumberPreference minOpacity = number("minOpacity", .4, 0, 1, .01);
    private final ModePreference wallLook = mode("wallLook", "solid", "solid", "partial", "clear");
    private final NumberPreference wallWindows = number("wallWindows", 3, 1, 6, 1);
    private final PreferenceValue<Boolean> stackEdges = flag("stackEdges", true);
    private final PreferenceValue<Boolean> backdropLyrics = flag("backdropLyrics", false);
    private final PreferenceValue<Boolean> backdropBlur = flag("backdropBlur", false);

    private ModePreference mode(String key, String initial, String... choices) {
        var value = new ModePreference(key, initial, choices); values.put(key, value); return value;
    }
    private PreferenceValue<Boolean> flag(String key, boolean initial) {
        var value = new PreferenceValue<Boolean>(key, initial); values.put(key, value); return value;
    }
    private NumberPreference number(String key, double initial, double min, double max, double step) {
        var value = new NumberPreference(key, initial, min, max, step); values.put(key, value); return value;
    }
    public void setChangeCallback(Runnable callback) { changed = callback; values.values().forEach(v -> v.setValueCallback(ignored -> callback.run())); }
    public JsonObject toJsonObject() {
        var json = new JsonObject();
        values.forEach((key, preference) -> {
            Object value = preference.getValue();
            if (value instanceof Boolean b) json.addProperty(key, b);
            else if (value instanceof Number n) json.addProperty(key, n);
            else json.addProperty(key, value.toString());
        });
        var saved = new JsonObject();
        positions.forEach(saved::addProperty);
        json.add("positions", saved);
        return json;
    }
    public void loadFromJsonObject(JsonObject json) {
        if (json.has("positions") && json.get("positions").isJsonObject()) {
            positions.clear();
            for (var entry : json.getAsJsonObject("positions").entrySet()) {
                if (positions.size() >= 100) break;
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString())
                    positions.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        values.forEach((key, preference) -> {
            var value = json.get(key);
            if (value == null || !value.isJsonPrimitive()) return;
            try {
                if (preference instanceof ModePreference m) {
                    if (m.getModes().contains(value.getAsString())) m.setValue(value.getAsString());
                } else if (preference instanceof NumberPreference n) {
                    double number = value.getAsDouble();
                    if (Double.isFinite(number)) n.setValue(n == wallWindows ? (double) Math.round(number) : number);
                } else if (value.getAsJsonPrimitive().isBoolean()) {
                    @SuppressWarnings("unchecked") var flag = (PreferenceValue<Boolean>) preference;
                    flag.setValue(value.getAsBoolean());
                }
            } catch (RuntimeException ignored) { /* One corrupt value must not discard the remaining preferences. */ }
        });
    }
    public String position(String key) { return positions.getOrDefault(key, ""); }
    public void remember(String key, String entry) {
        if (!rememberPosition.getValue() || entry.equals(positions.get(key))) return;
        positions.remove(key); positions.put(key, entry);
        while (positions.size() > 100) positions.remove(positions.keySet().iterator().next());
        changed.run();
    }
    public boolean isList() { return mode.getValue().equals("List"); }
    public boolean isWall() { return mode.getValue().equals("Wall"); }
    public boolean isFullscreen() { return mode.getValue().equals("Coverflow") && !coverflow.getValue().equals("Card"); }
    public void resetFalloff() { minScale.setValue(.45); minOpacity.setValue(.4); }
    public ModePreference mode() { return mode; }
    public ModePreference coverflow() { return coverflow; }
    public ModePreference cardStyle() { return cardStyle; }
    public PreferenceValue<Boolean> rememberPosition() { return rememberPosition; }
    public PreferenceValue<Boolean> fullBleed() { return fullBleed; }
    public PreferenceValue<Boolean> squareCards() { return squareCards; }
    public NumberPreference minScale() { return minScale; }
    public NumberPreference minOpacity() { return minOpacity; }
    public ModePreference wallLook() { return wallLook; }
    public NumberPreference wallWindows() { return wallWindows; }
    public PreferenceValue<Boolean> stackEdges() { return stackEdges; }
    public PreferenceValue<Boolean> backdropLyrics() { return backdropLyrics; }
    public PreferenceValue<Boolean> backdropBlur() { return backdropBlur; }
}
