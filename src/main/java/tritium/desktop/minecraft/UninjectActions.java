package tritium.desktop.minecraft;

import tritium.desktop.MinecraftUninjectPreferences;
import java.util.function.Consumer;

/** One immutable action plan per Uninject, shared by button and in-game requests. */
final class UninjectActions {
    record Plan(String hideMode, boolean autoClose, boolean selfDestruct) { }
    private final InjectButtonVisibility visibility;
    private final Consumer<Boolean> exit;

    UninjectActions(InjectButtonVisibility visibility, Consumer<Boolean> exit) {
        this.visibility = visibility; this.exit = exit;
    }
    Plan requested(MinecraftUninjectPreferences preferences, boolean hidden) {
        Plan plan = hidden
                ? new Plan(preferences.hideInjectButton().getValue(), preferences.autoClose().getValue(), preferences.selfDestruct().getValue())
                : new Plan(MinecraftUninjectPreferences.OFF, false, false);
        visibility.onUninject(plan.hideMode());
        return plan;
    }
    void completed(Plan plan) {
        if (plan.selfDestruct() || plan.autoClose()) exit.accept(plan.selfDestruct());
    }
}
