package tritium.minecraft.runtime.player;

import java.util.*;
import tritium.minecraft.runtime.hook.PlayerAccess;
import tritium.minecraft.runtime.util.render.RenderState;

/**
 * Dependency of SafeWalk's Scaffold mode. Executes Scaffold's original defaults:
 * Strict rotation, High precision, Legit motion, default theme, count and highlight.
 * Non-default standalone Scaffold modes are not exposed by this dependency.
 */
public final class ScaffoldSupport {
    private final PlayerAccess game;
    private final AutoSwapModule swap;
    private boolean enabled,active;
    private int delay,startTick,jumpState,airBelowTicks;
    private float restPitch,aimYaw,aimPitch=100;
    private double yawOffset;
    private long placedAt,startedAt,stoppedAt;
    private String countText;
    private volatile boolean sentCloseWindow,sentUseOrDig;
    private final Map<Object,Long> placed=new LinkedHashMap<Object,Long>();
    public ScaffoldSupport(PlayerAccess game,AutoSwapModule swap){this.game=game;this.swap=swap;}
    public boolean active(){return active;}
    public boolean blocksSprint(){return active&&game.ticks()==startTick&&jumpState==2;}
    public void enable(){enabled=true;restPitch=(float)(84.5+3*game.random());yawOffset=randomOffset();delay=1;}
    public void disable(boolean samePlayer){enabled=false;stop();delay=0;airBelowTicks=0;placedAt=0;if(!samePlayer){placed.clear();countText=null;jumpState=0;sentCloseWindow=sentUseOrDig=false;}}
    private float randomOffset(){return (float)(game.random()-.5);}
    private long sincePlace(){return Math.abs(game.now()-placedAt);}
    private void stop(){if(active){active=false;stoppedAt=game.now();jumpState=1;}aimPitch=100;}
    public void observeSend(Object packet){int kind=game.packetKind(packet);if(kind==1)sentCloseWindow=sentUseOrDig=false;else if(kind==2)sentUseOrDig=true;else if(kind==3)sentCloseWindow=true;}
    public void preUpdate(){
        if(!enabled)return;countText=FastPlaceModule.countText(swap.countBlocks());
        if(sentCloseWindow||game.screenOpen()||game.adventure()||!game.scaffoldBlock()){stop();return;}
        if(!active){startTick=game.ticks();startedAt=game.now();game.release("Attack");game.release("UseItem");}active=true;
        // Default Motion=1 and Legit motion=true preserve velocity; fast scaffold is disabled.
        if(delay>0){--delay;return;}if(sentUseOrDig)return;
        Object below=game.pos((int)Math.floor(game.x()),(int)Math.floor(game.y()-1),(int)Math.floor(game.z()));
        airBelowTicks=game.replaceable(below)?airBelowTicks+1:0;
        Object target=search();if(target==null)return;Object face=faceFor(target);if(face==null)return;
        Object against=game.offset(target,game.opposite(face));
        if(airBelowTicks>0){float[] rotation=searchRotation(against,face);if(rotation==null)return;aimYaw=rotation[0];aimPitch=rotation[1];
            Object hit=game.ray(game.reach(),aimYaw,aimPitch);if(!game.blockHit(hit)||!game.samePos(game.hitPos(hit),against))return;
            if(game.place(against,face,game.hitVec(hit))){game.swing();placedAt=game.now();placed.put(target,placedAt);airBelowTicks=0;}
        }else if(game.moving()&&game.random()>.8)game.airPlacement();
    }
    private Object search(){
        List<double[]> candidates=new ArrayList<double[]>();
        for(int i=-5;i<=5;i++)for(int j=-5;j<=5;j++)for(int k=-3;k<=-1;k++){
            int y=(int)Math.floor(game.y())+k;double x=game.x()+i,z=game.z()+j;
            if(game.replaceable(game.pos((int)Math.floor(x),y,(int)Math.floor(z))))continue;
            for(int n=-1;n<=1;n+=2){candidates.add(new double[]{x+n,y,z});candidates.add(new double[]{x,y,z+n});candidates.add(new double[]{x,y+n,z});}
        }
        Set<Long> seen=new HashSet<Long>();Iterator<double[]> it=candidates.iterator();
        while(it.hasNext()){double[] p=it.next();long key=Double.doubleToLongBits(p[0])^Double.doubleToLongBits(p[1])*31L^Double.doubleToLongBits(p[2])*31L*31L;if(!seen.add(key))it.remove();}
        final double x=game.x(),y=game.y()-1-(!game.ground()&&!game.physicalKey("Jump")?1:0),z=game.z();
        final double angle=game.yaw()*((float)Math.PI/180),dx=-Math.sin(angle),dz=Math.cos(angle);
        final boolean first=game.ticks()-startTick<=3;
        Collections.sort(candidates,(a,b)->{int cmp=Double.compare(distance(x,y,z,a),distance(x,y,z,b));return cmp!=0||!first?cmp:Double.compare(-((a[0]-x)*dx+(a[2]-z)*dz),-((b[0]-x)*dx+(b[2]-z)*dz));});
        for(double[] p:candidates){Object pos=game.pos((int)Math.floor(p[0]),(int)Math.floor(p[1]),(int)Math.floor(p[2]));if(game.replaceable(pos)&&faceFor(pos)!=null)return pos;}return null;
    }
    private static double distance(double x,double y,double z,double[] p){x-=p[0];y-=p[1];z-=p[2];return x*x+y*y+z*z;}
    private boolean canPlaceAgainst(Object p){
        if(!placed.isEmpty()&&!placed.containsKey(p)&&sincePlace()<250){for(Object recent:placed.keySet())if(game.distanceToPos(recent)<12)return false;}
        return !game.replaceable(p)&&!game.interactable(game.block(p));
    }
    private Object faceFor(Object pos){
        if(canPlaceAgainst(game.down(pos)))return game.facing("UP");
        double dx=game.x()-(game.posX(pos)+.5),dz=game.z()-(game.posZ(pos)+.5);
        Object face=game.facing(Math.abs(dx)>Math.abs(dz)?dx>0?"EAST":"WEST":dz>0?"SOUTH":"NORTH");
        if(canPlaceAgainst(game.offset(pos,face)))return game.opposite(face);
        for(String side:new String[]{"EAST","SOUTH","NORTH","WEST"}){Object f=game.facing(side);if(f!=face&&canPlaceAgainst(game.offset(pos,f)))return game.opposite(f);}return null;
    }
    private float[] searchRotation(Object pos,Object facing){
        float base=game.yaw(),best=360,bestYaw=0,bestPitch=0;
        for(float yaw=base-170;yaw<=base+170;yaw+=5){float y=yaw+randomOffset();
            for(float pitch=90;pitch>30;pitch-=pitch>75?1:pitch>60?2:5){float p=Math.max(-90,Math.min(90,pitch+randomOffset()));Object h=game.ray(game.reach(),y,p);
                if(!game.blockHit(h)||game.hitFace(h)!=facing||!game.samePos(game.hitPos(h),pos))continue;Object v=game.hitVec(h);
                if(!awayFromEdge((float)(game.vecX(v)-game.posX(pos)))||!awayFromEdge((float)(game.vecY(v)-game.posY(pos)))||!awayFromEdge((float)(game.vecZ(v)-game.posZ(pos))))continue;
                float diff=Math.abs(base-y);if(diff<best){best=diff;bestYaw=y;bestPitch=p;}
            }
        }return best==360||best>70?null:new float[]{bestYaw,bestPitch};
    }
    private static boolean awayFromEdge(float value){return value==0||value==1||value>.06&&value<.94;}
    public float[] rotations(float lastYaw,float lastPitch){
        if(!active)return null;long elapsed=sincePlace();float yaw,pitch;
        if(aimPitch!=100&&elapsed<110){yaw=aimYaw;pitch=aimPitch;if(elapsed>5&&game.moving()&&game.random()>.3){yaw+=(.05+game.random()*.45)*(game.random()<.5?-1:1);pitch+=randomOffset();}
            return game.sensitivityNoJitter(yaw,pitch,lastYaw,lastPitch);
        }
        aimPitch=100;int tick=game.ticks()-startTick;float desiredYaw=game.yaw()+(float)yawOffset,desiredPitch=restPitch+randomOffset();
        if(tick<=2&&!game.ground()&&game.ticks()>3){Object target=search();if(target!=null){Object f=faceFor(target);if(f!=null&&f!=game.facing("UP")){float[] r=searchRotation(game.offset(target,game.opposite(f)),f);if(r!=null){desiredYaw=r[0];desiredPitch=r[1];}}}}
        yaw=desiredYaw;pitch=desiredPitch;
        if(tick<=2){float weight=(float)((tick==0?.3:tick==1?.8:.55)-.02+game.random()*.04);yaw=lastYaw+weight*(desiredYaw-lastYaw);pitch=lastPitch+weight*(desiredPitch-lastPitch);if(Math.abs(yaw-desiredYaw)<5)delay=0;}
        float[] result=game.sensitivityNoJitter(yaw,pitch,lastYaw,lastPitch);yaw=result[0];if(yaw!=lastYaw){pitch=result[1];yawOffset=randomOffset();}else pitch=lastPitch;
        return new float[]{yaw,pitch};
    }
    public void postInput(){if(jumpState==1){if(game.physicalKey("Jump")){game.jumpState(false);jumpState=2;}else jumpState=0;}else if(jumpState==2){game.jumpState(true);jumpState=0;}}
    public boolean mouse(boolean down){return active&&down;}
    public void renderHud(){if(countText==null)return;long elapsed=game.now()-(active?startedAt:stoppedAt);int alpha=active?(elapsed<125?(int)(elapsed/125f*240):240):elapsed<125?(int)(240-elapsed/125f*240):0;if(!active&&alpha<=0)countText=null;if(alpha>0)game.blockCount(countText,alpha);}
    public void renderWorld(){
        if(placed.isEmpty())return;try(RenderState ignored=game.renderState()){
            long now=game.now();Iterator<Map.Entry<Object,Long>> iterator=placed.entrySet().iterator();
            double mix=(Math.sin(now/1.0E8*.5*400000)+1)*.5;
            int color=tritium.minecraft.runtime.util.render.Render3D.blend(0xfffdfdfd,0xffd2e1e6,mix);
            while(iterator.hasNext()){Map.Entry<Object,Long> e=iterator.next();float f=(now-Math.abs(e.getValue()))/500f;if(f>=1){iterator.remove();continue;}
                int alpha=(int)(255-Math.min(f*255,255));game.highlight(e.getKey(),alpha<<24|(color&0xffffff));
            }
        }
    }
}
