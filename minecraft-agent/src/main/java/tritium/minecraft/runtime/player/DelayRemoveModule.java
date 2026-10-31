package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;
public final class DelayRemoveModule extends PlayerModule {
    public DelayRemoveModule(PlayerAccess game){super(game);}
    @Override public void update(){if(on("1.7 hitreg"))game.leftClickDelay(0);if(on("Remove jump ticks")&&game.jumpTicks()>3)game.jumpTicks(3);}
}
