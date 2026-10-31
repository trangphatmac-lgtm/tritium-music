package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.OpenB4Module;

/** OpenB4 Reach's click and tick ray picker, including riding-entity and inside-box cases. */
public final class ReachModule extends OpenB4Module {
    public ReachModule(ExtendedCombatRuntime runtime) {
        super(runtime, "reach");
    }

    public boolean pick() {
        if (on("Weapon only") && !game.weapon()
                || on("Moving only") && !game.moving()
                || on("Sprint only") && !game.sprinting()) return false;
        if (!on("Hit through blocks") && game.reachBlocked()) return false;
        double min = Math.min(number("Min"), number("Max")),
                max = Math.max(number("Min"), number("Max"));
        return game.pick(min + runtime.random().nextDouble() * (max - min));
    }
}
