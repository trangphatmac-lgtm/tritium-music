package tritium.minecraft.runtime.player;

import java.util.*;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.hook.PlayerAccess;
import tritium.minecraft.runtime.hook.PlayerRuntime;
import static org.junit.jupiter.api.Assertions.*;

class BridgeAssistTest {
    private static BridgeAssistModule module(Fake g){BridgeAssistModule m=new BridgeAssistModule(g);m.settings(new HudConfig().find("bridgeAssist").openB4);m.enable();return m;}
    @Test void edgeOffsetUsesSimulatedFeetAndChebyshevDistanceWithoutPrematureInputScaling(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.numbers.put("Edge offset",.15);
        g.predicted=new double[]{.9,64,.9,1.5,65.8,1.5};g.groundBoxes=Collections.singletonList(new double[]{0,63,0,1,64,1});
        m.preInput(g.input);assertTrue(g.input.sneak);assertEquals(1,g.simulations);assertEquals(-1,g.input.forward);assertEquals(-1,g.simForward);
        g.resetInput();g.predicted=new double[]{.84,64,.84,1.44,65.8,1.44};m.preInput(g.input);assertFalse(g.input.sneak);
    }
    @Test void unsneakDelaySubtractsFirstFiftyMsAndRandomlyRoundsFractionalTicks(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.numbers.put("Unsneak delay",125d);g.random=.4;
        m.preInput(g.input);assertTrue(g.input.sneak);g.supported();
        for(int i=0;i<2;i++){g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);g.tick++;}
        g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);
        g.unsupported();g.resetInput();m.preInput(g.input);g.supported();g.random=.6;g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);g.tick++;g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);
    }
    @Test void jumpHoldHasSeparateClockAndNoSimulationOnItsInitialTick(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.numbers.put("Sneak on jump",125d);g.random=.4;g.input.jump=true;
        m.preInput(g.input);assertTrue(g.input.sneak);assertEquals(0,g.simulations);g.supported();g.ground=false;g.tick++;
        g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);g.tick++;g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);
        g.tick++;g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);
    }
    @Test void jumpingWithoutConfiguredHoldAllowsLeavingSupport(){Fake g=new Fake();BridgeAssistModule m=module(g);m.preInput(g.input);g.resetInput();g.input.jump=true;m.preInput(g.input);assertFalse(g.input.sneak);}
    @Test void manualSneakIsUntouchedUnlessTheRequireSneakConditionIsEnabled(){
        Fake g=new Fake();BridgeAssistModule m=module(g);g.manual=true;g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);assertEquals(0,g.simulations);
        m.options.toggles.put("Sneak key pressed",true);g.manual=false;g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);assertEquals(0,g.simulations);
        g.manual=true;g.input.forward=0;g.resetInput();m.preInput(g.input);assertEquals(0,g.simulations);
        g.input.forward=-1;g.resetInput();m.preInput(g.input);assertEquals(1,g.simulations);
    }
    @Test void placementReleasesHeldBindingUntilTheNextSneakRequest(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.toggles.put("Sneak key pressed",true);g.manual=true;g.resetInput();m.preInput(g.input);
        m.observeSend("air");g.supported();g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);assertTrue(g.key);
        g.unsupported();g.resetInput();m.preInput(g.input);m.observeSend("block");g.supported();g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);assertFalse(g.key);
        g.resetInput();m.preInput(g.input);assertFalse(g.input.sneak);assertFalse(g.key);
        g.unsupported();g.resetInput();m.preInput(g.input);assertTrue(g.input.sneak);assertTrue(g.key);
    }
    @Test void disableAndUninjectRestorePhysicalKeyAndOnlyTheOwnedPlayersInput(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.toggles.put("Sneak key pressed",true);g.manual=true;g.resetInput();m.preInput(g.input);m.observeSend("block");g.supported();g.resetInput();m.preInput(g.input);assertFalse(g.key);
        m.disable(true);assertTrue(g.key);assertTrue(g.input.sneak);
        g.manual=false;g.unsupported();g.resetInput();m.options.toggles.put("Sneak key pressed",false);m.preInput(g.input);assertTrue(g.input.sneak);m.disable(false);assertTrue(g.input.sneak,"old input is left behind during a world/player change");
    }
    @Test void activeConditionsAffectScrollAndInputButPrePlaceHasItsOriginalIndependentGate(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.toggles.put("Disable hotbar scrolling",true);assertTrue(m.slotScroll());
        m.options.toggles.put("Not moving forward",true);g.input.forward=1;assertFalse(m.slotScroll());m.preInput(g.input);assertEquals(0,g.simulations);
        g.input.forward=-1;m.options.toggles.put("Looking down",true);g.pitch=69;assertFalse(m.slotScroll());g.pitch=70;
        m.options.toggles.put("Holding blocks",true);g.blocks=false;assertFalse(m.slotScroll());g.blocks=true;assertTrue(m.slotScroll());
        m.options.toggles.put("Sneak key pressed",true);assertFalse(m.slotScroll());m.options.toggles.put("Pre place",true);assertNotNull(m.clientRotations(45,80));
        g.screen=new Object();assertNull(m.clientRotations(45,80));assertFalse(m.slotScroll());
    }
    @Test void avoidDoubleSneakRightClickGuardAllowsSameTickRepeatsOnlyAfterAnAllowedClick(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.toggles.put("Avoid double sneaking",true);m.preInput(g.input);assertTrue(m.rightClick());
        g.hit.face=Face.UP;assertFalse(m.rightClick());g.hit.face=Face.NORTH;assertFalse(m.rightClick(),"guard == 20 permits another click during same tick");
        g.resetInput();g.tick++;m.preInput(g.input);assertTrue(m.rightClick());g.yaw=29;assertFalse(m.rightClick());
        g.focus=false;g.yaw=45;assertFalse(m.rightClick());
    }
    @Test void prePlaceUsesNearestMatchingSidePitchAndSourceSmoothRotation(){
        Fake g=new Fake();BridgeAssistModule m=module(g);m.options.toggles.put("Pre place",true);g.random=.5;
        float[] r=m.clientRotations(45,80.3f);assertArrayEquals(new float[]{45,80},r,.0001f);assertEquals(30,g.rays);
        g.rays=0;g.rayFace=Face.UP;assertNull(m.clientRotations(45,80));assertEquals(31,g.rays);
    }
    @Test void coordinatorOnlyHandlesLocalInputInventoryAndHonorsFocusLifecycle(){
        Fake g=new Fake();PlayerRuntime runtime=new PlayerRuntime(g);HudConfig c=new HudConfig();c.hidden=true;c.find("bridgeAssist").enabled=true;c.find("bridgeAssist").openB4.toggles.put("Disable hotbar scrolling",true);
        runtime.configure(c);runtime.preInput(new In());assertFalse(g.input.sneak);runtime.preInput(g.input);assertTrue(g.input.sneak);
        assertFalse(runtime.slotScroll(new Object(),1));assertTrue(runtime.slotScroll(g.inventory(),1));g.active=false;runtime.configure(c);assertFalse(g.input.sneak);assertFalse(runtime.slotScroll(g.inventory(),1));runtime.close();
    }
    enum Face{NORTH,SOUTH,EAST,WEST,UP,DOWN}
    static final class Pos {final int x,y,z;Pos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}public boolean equals(Object o){return o instanceof Pos&&x==((Pos)o).x&&y==((Pos)o).y&&z==((Pos)o).z;}public int hashCode(){return x+y*31+z*961;}}
    static final class Hit{Face face;final Pos pos=new Pos(0,63,0);Hit(Face f){face=f;}}
    static final class In{float forward=-1,strafe;boolean jump,sneak;}
    static final class Fake extends PlayerAccess {
        final Object p=new Object(),w=new Object(),inv=new Object();final In input=new In();Object screen;boolean manual,key=true,ground=true,blocks=true,focus=true,active=true;int tick=50,simulations,rays;float pitch=80,yaw=45,simForward;double random=.5;
        double[] predicted={.2,64,.2,.8,65.8,.8};List<double[]> groundBoxes=Collections.emptyList();Hit hit=new Hit(Face.NORTH);Face rayFace=Face.NORTH;
        Fake(){super(null);}void supported(){groundBoxes=Collections.singletonList(new double[]{0,63,0,1,64,1});}void unsupported(){groundBoxes=Collections.emptyList();}void resetInput(){input.sneak=manual&&key;input.jump=false;}
        public Object player(){return p;}public Object world(){return w;}public Object screen(){return screen;}public Object input(){return input;}public Object inventory(){return inv;}public boolean windowActive(){return active;}public boolean inGameFocus(){return focus;}public boolean flying(){return false;}public boolean ground(){return ground;}public int ticks(){return tick;}
        public boolean physicalKey(String name){return manual;}public void key(String name,boolean down){key=down;}public boolean keyDown(String name){return name.equals("Back");}
        public float inputForward(Object i){return ((In)i).forward;}public float inputStrafe(Object i){return ((In)i).strafe;}public boolean inputJump(Object i){return ((In)i).jump;}public boolean inputSneak(Object i){return ((In)i).sneak;}public void inputSneak(Object i,boolean s){((In)i).sneak=s;}
        public float forward(){return input.forward;}public float strafe(){return input.strafe;}public float yaw(){return yaw;}public float pitch(){return pitch;}public double random(){return random;}public float sqrt(float n){return (float)Math.sqrt(n);}
        public double[] simulateUnsneaked(Object i){simulations++;simForward=((In)i).forward;return predicted;}public List<double[]> collisions(double[] b){return groundBoxes;}public double[] playerBounds(){return new double[]{.2,64,.2,.8,65.8,.8};}
        public boolean blockItem(Object s){return blocks;}public Object held(){return this;}public boolean blockPlacement(Object packet){return packet.equals("block");}
        public Object hit(){return hit;}public boolean blockHit(Object h){return h instanceof Hit;}public Object hitPos(Object h){return ((Hit)h).pos;}public Object hitFace(Object h){return ((Hit)h).face;}public Object hitVec(Object h){return new double[]{.5,63.8,0};}
        public double x(){return .5;}public double y(){return 64;}public double z(){return .5;}public double vecX(Object v){return ((double[])v)[0];}public double vecY(Object v){return ((double[])v)[1];}public double vecZ(Object v){return ((double[])v)[2];}
        public Object facing(String n){return Face.valueOf(n);}public Object pos(int x,int y,int z){return new Pos(x,y,z);}public int posX(Object p){return ((Pos)p).x;}public int posY(Object p){return ((Pos)p).y;}public int posZ(Object p){return ((Pos)p).z;}
        public Object offset(Object p,Object f){Pos b=(Pos)p;Face side=(Face)f;return new Pos(b.x+(side==Face.EAST?1:side==Face.WEST?-1:0),b.y,b.z+(side==Face.SOUTH?1:side==Face.NORTH?-1:0));}
        public boolean ravenReplaceable(Object p){return !p.equals(new Pos(0,63,0));}public boolean samePos(Object a,Object b){return Objects.equals(a,b);}public double reach(){return 4.5;}public Object ray(double d,float y,float p){rays++;return new Hit(rayFace);}
    }
}
