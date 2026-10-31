package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

public final class AutoJumpModule extends PlayerModule {
    private boolean jumping;
    public AutoJumpModule(PlayerAccess game) {super(game);}
    @Override public void update() {
        if(game.ground()&&!(on("Stop while sneaking")&&game.sneaking())) {
            if(game.noCollision(game.motionX()/3,-1,game.motionZ()/3)) {jumping=true;game.key("Jump",true);}
            else if(jumping) {jumping=false;game.key("Jump",false);}
        } else if(jumping) {jumping=false;game.key("Jump",false);}
    }
    @Override public void disable(boolean samePlayer) { jumping=false;if(samePlayer)game.restoreKey("Jump"); }
}
