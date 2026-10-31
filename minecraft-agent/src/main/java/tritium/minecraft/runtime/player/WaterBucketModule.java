package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Three-stage water placement/pickup and the source's independently retained silent pitch. */
public final class WaterBucketModule extends PlayerModule {
    private int stage;
    private long placedAt;
    private float aimPitch;
    public WaterBucketModule(PlayerAccess game){super(game);}
    private Object target(){Object h=game.ray(game.reach(),game.yaw(),on("Silent aim")?aimPitch:game.pitch());return game.topFace(h)?h:null;}
    @Override public void preUpdate(){
        if(game.paused())return;
        if(stage==1){boolean reset=false;if(!game.ground()){if(Math.abs(game.now()-placedAt)>500)reset=true;}
            else {if(on("Pickup water")&&game.holding("bucket")){Object hit=target();if(hit!=null){game.use(hit);stage=on("Silent aim")?2:0;}}if(stage!=2)reset=true;}
            if(reset){stage=0;aimPitch=0;}return;
        }
        if(stage==2){stage=0;aimPitch=0;return;}
        if(stage==0&&aimPitch!=0&&game.liquid()){aimPitch=0;return;}
        if(!game.flying()&&!game.creative()&&!game.ground()&&game.fallDistance()>=number("Minimum fall distance")-1.5+game.potionLevel("jump")){
            int slot=-1;for(int i=0;i<9;i++)if(game.item(game.stack(i))==game.itemConstant("water_bucket")){slot=i;break;}
            if(slot!=-1){
                if(!on("Silent aim")){if(game.pitch()<77)return;}
                else if(aimPitch==0){if(game.heightAboveGround()>1)aimPitch=game.forward()!=1?90:(float)(81+(int)(3*game.random()));return;}
                long now=game.now();if(Math.abs(now-placedAt)>=500){Object hit=target();if(hit==null)return;
                    if(game.holding("water_bucket")||on("Switch to item")&&game.slot(slot)){placedAt=now;game.use(hit);stage=1;return;}aimPitch=0;
                }return;
            }
        }
        if(aimPitch!=0){stage=0;aimPitch=0;}
    }
    public float[] rotations(){return aimPitch==0?null:new float[]{game.yaw(),aimPitch};}
    @Override public void disable(boolean samePlayer){stage=0;placedAt=0;aimPitch=0;}
}
