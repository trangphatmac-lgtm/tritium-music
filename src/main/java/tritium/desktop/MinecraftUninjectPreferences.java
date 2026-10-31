package tritium.desktop;

import com.google.gson.JsonObject;

/** Desktop-only actions; these settings never need to enter the Minecraft JVM. */
public final class MinecraftUninjectPreferences {
    public static final String OFF = "Off", NEXT_LAUNCH = "UntilNextLaunch", NEXT_REBOOT = "UntilNextReboot";
    private final ModePreference hideInjectButton = new ModePreference("Hide Inject Button", NEXT_LAUNCH,
            new String[]{OFF, NEXT_LAUNCH, NEXT_REBOOT});
    private final PreferenceValue<Boolean> autoClose = new PreferenceValue<>("Auto Close", false);
    private final PreferenceValue<Boolean> selfDestruct = new PreferenceValue<>("Self Destruct", false);
    private boolean loading;

    MinecraftUninjectPreferences(Runnable changed) {
        hideInjectButton.setValueCallback(value -> { if (!loading) changed.run(); });
        autoClose.setValueCallback(value -> { if (!loading) changed.run(); });
        selfDestruct.setValueCallback(value -> { if (!loading) changed.run(); });
    }
    public ModePreference hideInjectButton() { return hideInjectButton; }
    public PreferenceValue<Boolean> autoClose() { return autoClose; }
    public PreferenceValue<Boolean> selfDestruct() { return selfDestruct; }

    JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("hideInjectButton", hideInjectButton.getValue());
        json.addProperty("autoClose", autoClose.getValue());
        json.addProperty("selfDestruct", selfDestruct.getValue());
        return json;
    }
    void load(JsonObject json) {
        loading = true;
        try {
            hideInjectButton.setValue(NEXT_LAUNCH); autoClose.setValue(false); selfDestruct.setValue(false);
            if (json == null) return;
            try {
                String mode = json.get("hideInjectButton").getAsString();
                if (hideInjectButton.getModes().contains(mode)) hideInjectButton.setValue(mode);
            } catch (RuntimeException ignored) { }
            loadBoolean(json, "autoClose", autoClose);
            loadBoolean(json, "selfDestruct", selfDestruct);
        } finally { loading = false; }
    }
    private static void loadBoolean(JsonObject json, String key, PreferenceValue<Boolean> preference) {
        var value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
            preference.setValue(value.getAsBoolean());
    }
}
