package tritium.minecraft.runtime.util;

import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.ExtendedCombatAccess;
import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;

/** Session-owned module; configuration uses the original OpenB4 setting names. */
public abstract class OpenB4Module {
    protected final ExtendedCombatRuntime runtime;
    protected final ExtendedCombatAccess game;
    public final String id;

    protected OpenB4Module(ExtendedCombatRuntime runtime, String id) {
        this.runtime = runtime;
        this.game = runtime.game();
        this.id = id;
    }

    protected OpenB4Settings settings() {
        return runtime.settings(id);
    }

    protected double number(String name) {
        return settings().number(name);
    }

    protected boolean on(String name) {
        return settings().on(name);
    }

    protected boolean atMin(String name) {
        return number(name) == option(name).min;
    }

    protected boolean changed(String name) {
        return number(name) != option(name).defaultValue;
    }

    private OpenB4Settings.Option option(String name) {
        for (OpenB4Settings.Option option : OpenB4Settings.options(id))
            if (option.key.equals(name)) return option;
        throw new IllegalArgumentException(id + ": " + name);
    }

    public void enable() {}

    public void disable(boolean worldChanged) {}

    public void settingsChanged() {}
}
