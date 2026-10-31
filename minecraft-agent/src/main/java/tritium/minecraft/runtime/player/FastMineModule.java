package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;
public final class FastMineModule extends PlayerModule {
    private float lastDamage;
    public FastMineModule(PlayerAccess game){super(game);}
    @Override public void enable(){lastDamage=0;}
    @Override public void update(){
        if(on("Disable in creative")&&game.creative()||on("Ignore shears")&&game.holding("shears"))return;
        double delay=number("Break delay");if(delay<5&&(delay==0||game.blockHitDelay()>delay))game.blockHitDelay((int)delay);
        double speed=number("Break speed");int mode=(int)number("Visual mode");
        if(speed>1){if(!game.creative()&&game.mouse(0)){
            float damage=game.blockDamage();
            if(mode==0){double start=1-1/speed;if(damage>0&&damage<start)game.blockDamage((float)start);}
            else if(mode==1){if(damage<1&&damage>=1/speed)game.blockDamage(1);}
            else {float next=-1;
                if(damage<1){Object hit=game.hit();if(damage>lastDamage&&game.blockHit(hit))next=(float)(lastDamage+game.breakSpeed(game.block(game.hitPos(hit)),game.stack(game.slot()))*(speed-.2152857*(speed-1)));
                    if(next!=-1&&damage>0)game.blockDamage(next);
                }lastDamage=damage;
            }
        }else if(mode==2)lastDamage=0;}
    }
}
