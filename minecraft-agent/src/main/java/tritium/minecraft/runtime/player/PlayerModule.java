package tritium.minecraft.runtime.player;

import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Session-owned module state; options retain their exact OpenB4 setting names. */
public abstract class PlayerModule {
    protected final PlayerAccess game;
    protected OpenB4Settings options;
    protected PlayerModule(PlayerAccess game) { this.game=game; }
    public void settings(OpenB4Settings options) { this.options=options; }
    public void enable() { }
    public void disable(boolean samePlayer) { }
    public void preUpdate() { }
    public void update() { }
    protected boolean on(String name) { return options.on(name); }
    protected double number(String name) { return options.number(name); }
}
