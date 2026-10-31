package tritium.minecraft.runtime.player;

import java.util.*;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.PlayerAccess;
import tritium.minecraft.runtime.hook.PlayerRuntime;
import static org.junit.jupiter.api.Assertions.*;

class PlayerModulesTest {
    private static <T extends PlayerModule>T configured(T module,String id){module.settings(new HudConfig().find(id).openB4);return module;}
    @Test void autoJumpOwnsOnlyEdgeJumpAndRestoresPhysicalKeyOnDisable(){
        Fake game=new Fake();AutoJumpModule module=configured(new AutoJumpModule(game),"autoJump");
        module.update();assertTrue(game.down("Jump"));game.emptyCollision=false;module.update();assertFalse(game.down("Jump"));
        game.emptyCollision=true;game.physical.add("Jump");module.update();module.disable(true);assertTrue(game.down("Jump"));
        game.sneaking=true;module.update();assertTrue(game.down("Jump"),"no owned jump remains to release");
    }
    @Test void autoToolHoverDelaySwapsOnceAndRestoresTheOriginalSlot(){
        Fake game=new Fake();AutoToolModule module=configured(new AutoToolModule(game),"autoTool");module.options.numbers.put("Hover delay",3d);game.hit=new Hit(new Pos(0,63,0),Face.UP);game.bestTool=4;
        module.update();module.update();assertEquals(0,game.slot);module.update();assertEquals(4,game.slot);assertEquals(0,module.previousSlot());
        module.update();assertEquals(0,module.previousSlot());game.focused=false;module.update();assertEquals(0,game.slot);assertEquals(-1,module.previousSlot());
    }
    @Test void replacementPlayerNeverGetsPreviousPlayersToolSlot(){
        Fake game=new Fake();PlayerRuntime runtime=new PlayerRuntime(game);HudConfig config=new HudConfig();config.hidden=true;config.find("autoTool").enabled=true;
        game.hit=new Hit(new Pos(0,63,0),Face.UP);game.bestTool=4;runtime.configure(config);runtime.tickEnd();assertEquals(4,game.slot);
        game.player=new Object();game.slot=7;game.hit=null;runtime.configure(config);assertEquals(7,game.slot);runtime.close();assertEquals(7,game.slot);
    }
    @Test void autoSwapUsesLargestAllowedStackAndReverseOrderForTies(){
        Fake game=new Fake();Object stone=new Object(),wood=new Object();game.stacks[0]=new Stack(stone,1);game.stacks[2]=new Stack(wood,64);game.stacks[7]=new Stack(stone,32);game.stacks[8]=new Stack(stone,32);
        AutoSwapModule module=configured(new AutoSwapModule(game),"autoSwap");module.options.toggles.put("Only same type",true);module.swap();assertEquals(8,game.slot);
        module.swap();assertEquals(8,game.slot);assertFalse(module.swapped);module.options.toggles.put("Only same type",false);module.options.toggles.put("Swap to greater stack",true);module.swap();assertEquals(2,game.slot);
    }
    @Test void delayRemoveCapsJumpDelayRatherThanZeroingIt(){Fake game=new Fake();DelayRemoveModule module=configured(new DelayRemoveModule(game),"delayRemove");module.options.toggles.put("1.7 hitreg",true);module.options.toggles.put("Remove jump ticks",true);game.jumpTicks=10;module.update();assertEquals(0,game.leftDelay);assertEquals(3,game.jumpTicks);game.jumpTicks=2;module.update();assertEquals(2,game.jumpTicks);}
    @Test void fastMinePreservesAllThreeOriginalDamageModes(){
        Fake game=new Fake();FastMineModule module=configured(new FastMineModule(game),"fastMine");module.options.numbers.put("Break speed",2d);module.options.numbers.put("Break delay",2d);game.hitDelay=5;game.damage=.1f;
        module.update();assertEquals(2,game.hitDelay);assertEquals(.5f,game.damage);
        module.options.numbers.put("Visual mode",1d);game.damage=.49f;module.update();assertEquals(.49f,game.damage);game.damage=.5f;module.update();assertEquals(1f,game.damage);
        module.options.numbers.put("Visual mode",2d);module.enable();game.hit=new Hit(new Pos(0,63,0),Face.UP);game.damage=.1f;game.breakSpeed=.1f;module.update();assertEquals(.17847143f,game.damage,.000001);
    }
    @Test void fastPlaceWaitsTheOriginalHoldThresholdAndOnlyReplacesVanillaDelayFour(){
        Fake game=new Fake();game.right=true;game.stacks[0]=new Stack(new Object(),30);AutoSwapModule swap=configured(new AutoSwapModule(game),"autoSwap");FastPlaceModule module=configured(new FastPlaceModule(game,swap),"fastPlace");module.options.numbers.put("Delay",2d);module.options.numbers.put("Minimum hold time",150d);
        game.now=1000;game.rightDelay=4;module.update();assertEquals(4,game.rightDelay);game.now=1139;module.update();assertEquals(4,game.rightDelay);game.now=1140;module.update();assertEquals(2,game.rightDelay);game.rightDelay=3;module.update();assertEquals(3,game.rightDelay);
        assertTrue(module.send("airPlacement"));game.random=.3;assertFalse(module.send("airPlacement"));assertFalse(module.send("normal"));
    }
    @Test void safeWalkStateModeUsesPredictionAndDelayedUnsneak(){
        Fake game=new Fake();AutoSwapModule swap=configured(new AutoSwapModule(game),"autoSwap");SafeWalkModule module=configured(new SafeWalkModule(game,new ScaffoldSupport(game,swap)),"safeWalk");
        module.options.numbers.put("Mode",2d);module.options.numbers.put("Unsneak delay",100d);game.stacks[0]=new Stack(new Object(),10);game.now=1000;module.preUpdate();module.postInput();assertTrue(game.sneakState);assertEquals(.3f,game.forward,.00001);
        game.solids.add(new Pos(0,63,0));game.sneakState=false;game.now=1140;module.preUpdate();module.postInput();assertTrue(game.sneakState);
        game.sneakState=false;game.now=1141;module.preUpdate();module.postInput();assertFalse(game.sneakState);
    }
    @Test void safeWalkOnlySneakingPassesPhysicalKeyWhenPitchConditionFails(){
        Fake game=new Fake();AutoSwapModule swap=configured(new AutoSwapModule(game),"autoSwap");SafeWalkModule module=configured(new SafeWalkModule(game,new ScaffoldSupport(game,swap)),"safeWalk");
        module.options.toggles.put("Only while sneaking",true);game.physical.add("Sneak");game.pitch=10;module.preUpdate();assertTrue(game.down("Sneak"));assertFalse(module.stopOnEdge());module.options.numbers.put("Minimum pitch",19d);module.options.toggles.put("Blocks only",false);assertTrue(module.stopOnEdge(),"19 is the original disabled sentinel");
    }
    @Test void waterBucketRetainsSilentPitchForPlacementAndPickupThenReleasesIt(){
        Fake game=new Fake();game.ground=false;game.fall=5;game.stacks[1]=new Stack("water_bucket",1,false);game.rayHit=new Hit(new Pos(0,60,0),Face.UP);game.now=1000;
        WaterBucketModule module=configured(new WaterBucketModule(game),"waterBucket");module.options.toggles.put("Silent aim",true);module.options.toggles.put("Switch to item",true);module.options.toggles.put("Pickup water",true);
        module.preUpdate();assertNotNull(module.rotations());assertEquals(0,game.uses);module.preUpdate();assertEquals(1,game.slot);assertEquals(1,game.uses);assertNotNull(module.rotations());
        game.stacks[1]=new Stack("bucket",1,false);game.ground=true;module.preUpdate();assertEquals(2,game.uses);assertNotNull(module.rotations());module.preUpdate();assertNull(module.rotations());
    }
    @Test void ladderChoosesExistingWallAndRestoresSlotOnLanding(){
        Fake game=new Fake();game.ground=false;game.fall=8;game.stacks[1]=new Stack(new Object(),64);game.stacks[2]=new Stack("ladder",10,false);game.rayHit=new Hit(new Pos(0,60,0),Face.UP);game.solids.add(new Pos(1,61,0));
        AutoLadderModule module=configured(new AutoLadderModule(game),"autoLadder");module.options.toggles.put("Only while holding ladder",false);module.preUpdate();assertEquals(2,game.slot);assertEquals(1,game.places);assertNotNull(module.rotations(0,0));
        game.ground=true;module.preUpdate();assertEquals(0,game.slot);assertNull(module.rotations(0,0));
    }
    @Test void scaffoldDependencyActuallyPlacesAndHonorsTrackedInteractions() throws Exception{
        Fake game=new Fake();game.stacks[0]=new Stack(new Object(),64);game.solids.add(new Pos(-1,63,0));game.rayHit=new Hit(new Pos(-1,63,0),Face.SOUTH);game.random=.5;
        AutoSwapModule swap=configured(new AutoSwapModule(game),"autoSwap");ScaffoldSupport scaffold=new ScaffoldSupport(game,swap);scaffold.enable();scaffold.preUpdate();assertTrue(scaffold.active());assertEquals(0,game.places);
        scaffold.observeSend(2);scaffold.preUpdate();assertEquals(0,game.places);scaffold.observeSend(1);scaffold.preUpdate();assertEquals(1,game.places);assertNotNull(scaffold.rotations(0,70));
        scaffold.disable(true);assertFalse(scaffold.active());assertNull(scaffold.rotations(0,0));
    }
    @Test void guiChangesPreserveModuleLifecycleWhileApplicationFocusLossRestoresKeys(){
        Fake game=new Fake();PlayerRuntime runtime=new PlayerRuntime(game);HudConfig config=new HudConfig();config.hidden=true;config.find("autoJump").enabled=true;config.find("safeWalk").enabled=true;
        runtime.configure(config);runtime.tickEnd();assertTrue(game.down("Jump"));game.screen=new Object();runtime.configure(config);assertTrue(runtime.enabled("safeWalk"));
        game.screen=null;runtime.configure(config);assertTrue(runtime.enabled("safeWalk"));game.windowActive=false;runtime.configure(config);assertFalse(runtime.enabled("safeWalk"));assertFalse(game.down("Jump"));
    }
    @Test void itemSpoofingKeepsOriginalFistAndResetsEquipForFiveTicksAfterSwap(){
        Fake game=new Fake();PlayerRuntime runtime=new PlayerRuntime(game);HudConfig config=new HudConfig();config.hidden=true;config.find("autoTool").enabled=true;config.find("autoTool").openB4.toggles.put("Spoof item",true);
        game.hit=new Hit(new Pos(0,63,0),Face.UP);game.bestTool=4;game.stacks[4]=new Stack("tool",1,false);runtime.configure(config);runtime.tickEnd();
        assertNull(runtime.itemToRender(game.held()));assertTrue(runtime.spoofingItemAsFist());assertEquals(0,runtime.animationProgress(.6f));
        game.hit=null;runtime.tickEnd();assertNull(runtime.itemToRender(game.stacks[4]));assertFalse(runtime.spoofingItemAsFist());assertEquals(0,runtime.animationProgress(.6f));
        for(int i=0;i<5;i++){game.tick++;runtime.itemToRender(null);assertEquals(0,runtime.animationProgress(.6f));}
        runtime.itemToRender(null);assertEquals(.6f,runtime.animationProgress(.6f));
    }
    private enum Face{DOWN,UP,NORTH,SOUTH,WEST,EAST}
    private static final class Pos{final int x,y,z;Pos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public boolean equals(Object o){return o instanceof Pos&&((Pos)o).x==x&&((Pos)o).y==y&&((Pos)o).z==z;}public int hashCode(){return x*961+y*31+z;}public String toString(){return x+","+y+","+z;}}
    private static final class Hit{final Pos pos;final Face face;Hit(Pos p,Face f){pos=p;face=f;}}
    private static final class Stack{final Object item;final int count;final boolean block;Stack(Object item,int count){this(item,count,true);}Stack(Object item,int count,boolean block){this.item=item;this.count=count;this.block=block;}}
    private static class Fake extends PlayerAccess{
        Object player=new Object(),world=new Object(),screen;boolean windowActive=true,focused=true,ground=true,sneaking,emptyCollision=true,sneakState,right;
        int tick=10,slot,bestTool,hitDelay,rightDelay,leftDelay=8,jumpTicks,uses,places;float pitch=80,fall,damage,breakSpeed,forward=1;long now=10000;double random=.8;
        Object hit,rayHit;Object[] stacks=new Object[9];Set<String> keys=new HashSet<String>(),physical=new HashSet<String>();Set<Pos> solids=new HashSet<Pos>();
        Fake(){super(null);}boolean down(String s){return keys.contains(s);}
        public Object player(){return player;}public Object world(){return world;}public Object screen(){return screen;}public boolean focused(){return focused;}public boolean windowActive(){return windowActive;}public boolean screenOpen(){return false;}
        public boolean paused(){return false;}public boolean creative(){return false;}public boolean flying(){return false;}public boolean adventure(){return false;}
        public long now(){return now;}public double random(){return random;}public boolean mouse(int i){return i==0||right;}
        public boolean ground(){return ground;}public boolean sneaking(){return sneaking;}public boolean sprinting(){return false;}public boolean onLadder(){return false;}public boolean liquid(){return false;}
        public float yaw(){return 0;}public float pitch(){return pitch;}public float fallDistance(){return fall;}public int ticks(){return tick;}public float forward(){return forward;}public float strafe(){return 0;}
        public double x(){return .5;}public double y(){return 64;}public double z(){return .5;}public double boxMinY(){return 64;}
        public double motionX(){return 0;}public double motionY(){return 0;}public double motionZ(){return 0;}public float moveSpeed(){return .1f;}public float sin(float v){return (float)Math.sin(v);}public float cos(float v){return (float)Math.cos(v);}public float sqrt(float v){return (float)Math.sqrt(v);}
        public boolean noCollision(double x,double y,double z){return emptyCollision;}public void key(String n,boolean d){if(d)keys.add(n);else keys.remove(n);}public boolean keyDown(String n){return down(n);}public boolean physicalKey(String n){return physical.contains(n);}public void restoreKey(String n){key(n,physicalKey(n));}public void release(String n){key(n,false);}public void jumpState(boolean value){key("jumpState",value);}
        public void sneakState(boolean v){if(!sneakState&&v)forward*=.3f;sneakState=v;}
        public int slot(){return slot;}public boolean slot(int v){if(v<0)return false;slot=v;return true;}public Object stack(int s){return stacks[s];}public Object held(){return stacks[slot];}
        public Object item(Object s){return s==null?null:((Stack)s).item;}public int count(Object s){return s==null?0:((Stack)s).count;}public Object itemConstant(String s){return s;}
        public boolean holding(String s){return Objects.equals(item(held()),s);}public boolean blockItem(Object s){return s!=null&&((Stack)s).block;}public boolean scaffoldBlock(Object s){return blockItem(s);}public boolean ladder(Object s){return Objects.equals(item(s),"ladder");}public void clearEmptyStacks(){}public boolean tool(){return true;}
        public Object hit(){return hit;}public boolean blockHit(Object h){return h instanceof Hit;}public Object hitPos(Object h){return ((Hit)h).pos;}public Object hitFace(Object h){return ((Hit)h).face;}public Object hitVec(Object h){return h;}public Object block(Object p){return p;}public boolean interactable(Object b){return false;}public int bestTool(Object b){return bestTool;}
        public Object pos(int x,int y,int z){return new Pos(x,y,z);}public int posX(Object p){return ((Pos)p).x;}public int posY(Object p){return ((Pos)p).y;}public int posZ(Object p){return ((Pos)p).z;}
        public Object facing(String n){return Face.valueOf(n);}public Object opposite(Object f){switch((Face)f){case UP:return Face.DOWN;case DOWN:return Face.UP;case EAST:return Face.WEST;case WEST:return Face.EAST;case SOUTH:return Face.NORTH;default:return Face.SOUTH;}}
        public Object horizontalFacing(){return Face.NORTH;}public Object clockwise(Object f){return Face.EAST;}public Object counterClockwise(Object f){return Face.WEST;}
        public Object offset(Object p,Object f){Pos a=(Pos)p;switch((Face)f){case UP:return new Pos(a.x,a.y+1,a.z);case DOWN:return new Pos(a.x,a.y-1,a.z);case EAST:return new Pos(a.x+1,a.y,a.z);case WEST:return new Pos(a.x-1,a.y,a.z);case SOUTH:return new Pos(a.x,a.y,a.z+1);default:return new Pos(a.x,a.y,a.z-1);}}
        public Object up(Object p){return offset(p,Face.UP);}public Object down(Object p){return offset(p,Face.DOWN);}public boolean solid(int x,int y,int z){return solids.contains(new Pos(x,y,z));}public boolean replaceable(Object p){return !solids.contains(p);}public boolean full(Object p){return solids.contains(p);}public boolean airBelow(){return !solids.contains(new Pos(0,63,0));}
        public Object ray(double range,float y,float p){return rayHit;}public double reach(){return 4.5;}public int potionLevel(String n){return 0;}public double heightAboveGround(){return 3;}
        public void use(Object hit){uses++;}public boolean place(Object p,Object f,Object v){places++;return true;}public void swing(){}public Object randomizedHitVec(Object p,Object f){return p;}public float[] blockRotations(Object p,Object f,float y,float pitch){return new float[]{y,80};}
        public float breakSpeed(Object b,Object s){return breakSpeed;}public int blockHitDelay(){return hitDelay;}public void blockHitDelay(int n){hitDelay=n;}public float blockDamage(){return damage;}public void blockDamage(float n){damage=n;}
        public int rightClickDelay(){return rightDelay;}public void rightClickDelay(int n){rightDelay=n;}public void leftClickDelay(int n){leftDelay=n;}public int jumpTicks(){return jumpTicks;}public void jumpTicks(int n){jumpTicks=n;}
        public boolean airBlockPlacement(Object p){return "airPlacement".equals(p);}public int packetKind(Object p){return p instanceof Integer?(Integer)p:0;}public void airPlacement(){}
        public double vecX(Object h){return ((Hit)h).pos.x+1;}public double vecY(Object h){return ((Hit)h).pos.y+.5;}public double vecZ(Object h){return ((Hit)h).pos.z+.5;}
        public float[] sensitivityNoJitter(float y,float p,float lastY,float lastP){return new float[]{y,p};}
    }
}
