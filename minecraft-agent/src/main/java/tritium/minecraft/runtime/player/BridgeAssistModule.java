package tritium.minecraft.runtime.player;

import java.util.*;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** RavenBs Bridge Assist, including its input ownership and two independent delay clocks. */
public final class BridgeAssistModule extends PlayerModule {
    private static final String[] SIDES={"NORTH","SOUTH","EAST","WEST"};
    private boolean sneakingFromModule,placed,forceRelease,cancelRightClickThisTick;
    private boolean inputOwned,keyOwned;
    private Object ownedInput;
    private int rightClickGuard,sneakJumpDelayTicks=-1,sneakJumpStartTick=-1,unsneakDelayTicks=-1,unsneakStartTick=-1;
    public BridgeAssistModule(PlayerAccess game){super(game);}
    @Override public void disable(boolean samePlayer){
        // The source unloads only module fields. Inject must also restore a physically held binding.
        if(keyOwned)game.restoreKey("Sneak");
        if(samePlayer&&inputOwned&&ownedInput==game.input())game.inputSneak(ownedInput,game.physicalKey("Sneak"));
        sneakingFromModule=placed=forceRelease=cancelRightClickThisTick=inputOwned=keyOwned=false;ownedInput=null;rightClickGuard=0;resetUnsneak();
    }
    /** Called before vanilla scales forward and strafe by 0.3 for sneak. */
    public void preInput(Object input){
        cancelRightClickThisTick=false;
        if(!on("Avoid double sneaking"))rightClickGuard=0;else if(game.inGameFocus()&&rightClickGuard>0)--rightClickGuard;
        if(!available())return;
        Input e=new Input(input);
        boolean manual=manualSneak(),require=on("Sneak key pressed");
        if(manual&&!require)resetUnsneak();
        else if(!require||manual&&(e.forward!=0||e.strafe!=0)){
            if(on("Not moving forward")&&e.forward>0)clearSneak(e);
            else if(on("Looking down")&&game.pitch()<70)clearSneak(e);
            else if(on("Holding blocks")&&!game.blockItem(game.held()))clearSneak(e);
            else if(!e.jump||!game.ground()||e.forward==0&&e.strafe==0||!(number("Sneak on jump")>0)||require&&!forceRelease){
                double offset=computeEdgeOffset(game.simulateUnsneaked(input));
                if(!Double.isNaN(offset)){
                    if(offset>number("Edge offset"))pressSneak(e,true);else if(sneakingFromModule)tryReleaseSneak(e,true);
                }else if(e.jump&&(number("Sneak on jump")<=0||e.forward==0&&e.strafe==0)){
                    if(sneakingFromModule)tryReleaseSneak(e,true);
                }else if(game.ground())pressSneak(e,true);else if(sneakingFromModule)tryReleaseSneak(e,true);
            }else{
                sneakJumpStartTick=game.ticks();sneakJumpDelayTicks=randomTicks(number("Sneak on jump")/50.0);pressSneak(e,true);
            }
        }else{if(!manual)resetUnsneak();repressSneak(e);}
        e.apply();
    }
    public boolean rightClick(){
        if(on("Avoid double sneaking")&&game.inGameFocus()&&isAssistActive()&&game.blockItem(game.held())&&rightClickGuard!=20){
            if(cancelRightClickThisTick&&shouldCancelRightClick())return true;
            rightClickGuard=20;
        }
        return false;
    }
    private boolean shouldCancelRightClick(){
        Object hit=game.hit();if(!game.blockHit(hit))return false;
        Object face=game.hitFace(hit),vec=game.hitVec(hit),pos=game.hitPos(hit);
        if(face==null||face==game.facing("UP")||face==game.facing("DOWN")||vec==null||pos==null||!game.ground())return false;
        if(!game.keyDown("Back")||game.keyDown("Left")||game.keyDown("Right"))return false;
        float yawSector=Math.abs((float)(game.yaw()%90.0));if(yawSector<30||yawSector>60)return false;
        double dx=game.vecX(vec)-game.x(),dy=game.vecY(vec)-game.y(),dz=game.vecZ(vec)-game.z();
        if(Math.abs(dx)>1||dy< -1||dy>0||Math.abs(dz)>1)return false;
        double margin=game.z()>game.posZ(pos)&&game.z()<game.posZ(pos)+1.0?.25:.15;
        double fx=Math.abs(game.vecX(vec)%1),fz=Math.abs(game.vecZ(vec)%1);
        return fx>=margin&&fx<=1-margin||fz>=margin&&fz<=1-margin;
    }
    public void observeSend(Object packet){if(game.blockPlacement(packet)&&sneakingFromModule&&on("Sneak key pressed"))placed=true;}
    public boolean slotScroll(){return on("Disable hotbar scrolling")&&isAssistActive();}
    private boolean available(){return game.inWorld()&&game.screen()==null&&!game.flying();}
    private boolean isAssistActive(){
        if(!available())return false;float f=game.forward(),s=game.strafe();boolean manual=manualSneak(),require=on("Sneak key pressed");
        if(manual&&!require)return false;if(require&&!(manual&&(f!=0||s!=0)))return false;
        return !(on("Not moving forward")&&f>0)&&!(on("Looking down")&&game.pitch()<70)&&(!on("Holding blocks")||game.blockItem(game.held()));
    }
    /** ClientRotation event result; the shared Raven rotation helper performs final sensitivity quantization. */
    public float[] clientRotations(float baseYaw,float basePitch){
        if(!on("Pre place")||!available()||!game.blockItem(game.held())||on("Looking down")&&game.pitch()<70||on("Not moving forward")&&game.forward()>0)return null;
        // BedAura is absent. Pre-place intentionally does not require the manual sneak condition.
        float[] target=findTarget(basePitch,game.reach());return target==null?null:smoothRotation(baseYaw,basePitch,target[0],target[1]);
    }
    private void pressSneak(Input e,boolean resetDelay){e.sneak=true;sneakingFromModule=true;cancelRightClickThisTick=on("Avoid double sneaking");if(resetDelay)unsneakStartTick=-1;repressSneak(e);}
    private void tryReleaseSneak(Input e,boolean resetDelay){
        int tick=game.ticks();if(unsneakStartTick==-1&&sneakJumpStartTick==-1){unsneakStartTick=tick;unsneakDelayTicks=randomTicks((number("Unsneak delay")-50)/50.0);}
        if(sneakJumpStartTick!=-1&&tick-sneakJumpStartTick<sneakJumpDelayTicks)pressSneak(e,false);
        else if(unsneakStartTick!=-1&&tick-unsneakStartTick<unsneakDelayTicks)pressSneak(e,false);
        else releaseSneak(e,resetDelay);
    }
    private int randomTicks(double raw){int base=(int)raw;return base+(game.random()<raw-base?1:0);}
    private void releaseSneak(Input e,boolean resetDelay){
        if(!on("Sneak key pressed"))e.sneak=false;
        else if(!sneakingFromModule||!manualSneak()||!placed&&game.ground()){if(forceRelease)e.sneak=false;}
        else{game.key("Sneak",false);keyOwned=true;e.sneak=false;forceRelease=true;}
        sneakingFromModule=false;placed=false;if(resetDelay)resetUnsneak();
    }
    private void repressSneak(Input e){if(forceRelease&&manualSneak()){game.key("Sneak",true);keyOwned=false;e.sneak=true;}forceRelease=false;}
    private void clearSneak(Input e){sneakingFromModule=false;resetUnsneak();if(on("Sneak key pressed"))repressSneak(e);}
    private void resetUnsneak(){unsneakStartTick=sneakJumpStartTick=sneakJumpDelayTicks=unsneakDelayTicks=-1;}
    private boolean manualSneak(){return game.physicalKey("Sneak");}
    private double computeEdgeOffset(double[] b){
        List<double[]> ground=game.collisions(new double[]{b[0],b[1]-.51,b[2],b[3],b[1],b[5]});if(ground.isEmpty())return Double.NaN;
        double x=(b[0]+b[3])/2,z=(b[2]+b[5])/2,min=Double.MAX_VALUE;
        for(double[] box:ground){double cx=Math.max(box[0],Math.min(x,box[3])),cz=Math.max(box[2],Math.min(z,box[5]));min=Math.min(min,Math.max(Math.abs(x-cx),Math.abs(z-cz)));}return min;
    }
    private float[] findTarget(float currentPitch,double reach){
        float yaw=game.yaw();double[] b=game.playerBounds();int y=floor(b[1])-1;
        List<FaceTarget> targets=new ArrayList<FaceTarget>();
        for(int x=floor(b[0]);x<=floor(b[3]);x++)for(int z=floor(b[2]);z<=floor(b[5]);z++){
            Object pos=game.pos(x,y,z);if(!game.ravenReplaceable(pos))for(String side:SIDES){Object face=game.facing(side);if(game.ravenReplaceable(game.offset(pos,face)))targets.add(new FaceTarget(pos,face));}
        }
        if(targets.isEmpty())return null;
        float bestDelta=Float.MAX_VALUE,bestPitch=Float.NaN,pitch=60;boolean found=false;
        while(pitch<=90){
            float step=1+(float)(game.random()*2-1)*(.3f+.2f*.4f);if(step<.4f)step=.4f;if(step>1.8f)step=1.8f;
            pitch+=step;float sample=Math.min(pitch,90);Object hit=game.ray(reach,yaw,sample);
            if(game.blockHit(hit)){Object face=game.hitFace(hit);if(face!=game.facing("UP")&&face!=game.facing("DOWN")){
                Object pos=game.hitPos(hit);for(FaceTarget target:targets)if(game.samePos(pos,target.pos)&&face==target.face){float delta=Math.abs(sample-currentPitch);if(delta<bestDelta){bestDelta=delta;bestPitch=sample;found=true;}break;}
                if(pitch>=90)return found&&!Float.isNaN(bestPitch)?new float[]{yaw,bestPitch}:null;
            }}
        }
        return found&&!Float.isNaN(bestPitch)?new float[]{yaw,bestPitch}:null;
    }
    private float[] smoothRotation(float baseYaw,float basePitch,float targetYaw,float targetPitch){
        float dy=targetYaw-baseYaw;dy%=360;if(dy>=180)dy-=360;if(dy< -180)dy+=360;
        float dp=targetPitch-basePitch,magnitude=game.sqrt(dy*dy+dp*dp);if(magnitude<.001f)return new float[]{targetYaw,clampPitch(targetPitch)};
        float t=15/30.0f,step=t*t*180,range=.6f*(float)(20/100.0);
        float multiplier=range<=.001f?1:1-range/2+(float)(game.random()*range);step*=multiplier;
        float proximity=Math.min(1,magnitude/180);proximity=(float)Math.pow(proximity,.7);
        float proximityMult=Math.max(.8f,1-(float)(20/100.0)*(1-proximity));step*=proximityMult;
        float scale=Math.min(step,magnitude)/magnitude;return new float[]{baseYaw+dy*scale,clampPitch(basePitch+dp*scale)};
    }
    private static int floor(double n){int i=(int)n;return n<i?i-1:i;}
    private static float clampPitch(float n){return n< -90?-90:n>90?90:n;}
    private static final class FaceTarget{final Object pos,face;FaceTarget(Object p,Object f){pos=p;face=f;}}
    private final class Input {
        final Object raw;final float forward,strafe;final boolean jump,originalSneak;boolean sneak;
        Input(Object raw){this.raw=raw;forward=game.inputForward(raw);strafe=game.inputStrafe(raw);jump=game.inputJump(raw);sneak=originalSneak=game.inputSneak(raw);}
        void apply(){if(sneak!=originalSneak){game.inputSneak(raw,sneak);inputOwned=true;ownedInput=raw;}}
    }
}
