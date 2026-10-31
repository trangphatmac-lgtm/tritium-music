package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Original two-stage support-block/ladder placement, with temporary slot ownership. */
public final class AutoLadderModule extends PlayerModule {
    private int previousSlot=-1;
    private Object target,direction,face;
    private boolean ladderNext,scaffolding;
    private long placedAt;
    public AutoLadderModule(PlayerAccess game){super(game);}
    public void scaffolding(boolean value){scaffolding=value;}
    @Override public void preUpdate(){
        if(game.paused()||game.flying()||game.creative()||scaffolding){reset(true);return;}
        if(game.ground()||game.onLadder()||game.fallDistance()<1.5){reset(true);return;}
        long now=game.now();if(Math.abs(now-placedAt)<1000){reset(true);return;}
        int most=0,blockSlot=-1,ladderSlot=-1;
        for(int i=0;i<9;i++){Object s=game.stack(i);if(game.scaffoldBlock(s)){int n=game.count(s);if(n<=most)continue;blockSlot=i;most=n;continue;}if(game.ladder(s))ladderSlot=i;}
        if(blockSlot==-1||ladderSlot==-1){reset(true);return;}
        if(on("Only while holding ladder")&&target==null&&game.slot()!=ladderSlot){reset(true);return;}
        Object hit=game.ray(7,game.yaw(),game.sprinting()?80:90);if(!game.topFace(hit)){reset(true);return;}
        int slot;
        if(ladderNext){ladderNext=false;slot=ladderSlot;target=game.up(target);face=game.opposite(direction);if(!game.replaceable(game.offset(target,face))){reset(true);return;}}
        else {
            previousSlot=game.slot();slot=blockSlot;Object ground=game.hitPos(hit);if(!game.replaceable(game.up(ground))){reset(true);return;}
            Object facing=game.horizontalFacing();Object[] order={game.clockwise(facing),game.counterClockwise(facing),facing,game.opposite(facing)};
            for(Object f:order){Object wall=game.up(game.offset(ground,f));if(game.replaceable(wall)||!game.full(wall))continue;slot=ladderSlot;target=wall;direction=f;face=game.opposite(f);break;}
            if(target==null){double x=game.x(),z=game.z(),m=.415,m1=m+1;
                for(Object f:order){Object beside=game.offset(ground,f),above=game.up(beside);
                    if(x>game.posX(above)-m&&x<game.posX(above)+m1&&z>game.posZ(above)-m&&z<game.posZ(above)+m1)continue;
                    if(game.replaceable(beside)||!game.replaceable(above))continue;target=beside;direction=f;face=game.facing("UP");break;
                }
            }
        }
        if(target==null){reset(true);return;}
        if(game.fallDistance()<number("Minimum fall distance")-1.5+game.potionLevel("jump"))return;
        hit=game.ray(game.reach(),game.yaw(),90);
        if(game.blockHit(hit)) {game.slot(slot);if(game.place(target,face,game.randomizedHitVec(target,face)))game.swing();if(slot==blockSlot)ladderNext=true;else placedAt=now;}
    }
    public float[] rotations(float lastYaw,float lastPitch){return target==null?null:game.blockRotations(target,face,lastYaw,lastPitch);}
    private void reset(boolean samePlayer){if(previousSlot!=-1){if(samePlayer)game.slot(previousSlot);previousSlot=-1;}ladderNext=false;target=null;direction=null;face=null;placedAt=0;}
    @Override public void disable(boolean samePlayer){reset(samePlayer);}
}
