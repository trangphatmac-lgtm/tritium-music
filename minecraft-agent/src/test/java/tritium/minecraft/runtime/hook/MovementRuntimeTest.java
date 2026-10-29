package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MovementRuntimeTest {
    @Test void sprintHoldsKeyGuardsSilentBackwardsOnNextUpdateAndPreservesPhysicalKey() {
        Fake game=new Fake();MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("sprint");runtime.configure(config);
        runtime.preUpdate();assertTrue(game.sprintKey);
        game.forward=1;game.yaw=180;runtime.preMotion(0);assertTrue(game.sprintKey);
        runtime.preUpdate();assertFalse(game.sprintKey);assertEquals(1,game.sprintStops);
        runtime.preUpdate();assertTrue(game.sprintKey);
        config.find("sprint").movement.disableBackwards=false;runtime.preMotion(0);runtime.preUpdate();assertTrue(game.sprintKey);
        game.physicalSprint=true;config.hidden=false;runtime.configure(config);assertTrue(game.sprintKey);
        config.hidden=true;runtime.configure(config);game.physicalSprint=false;runtime.close();assertFalse(game.sprintKey);
    }
    @Test void hiddenAndWorldChangesResetWithoutApplyingOldWorldMotionToNewPlayer() {
        Fake game=new Fake();game.ground=false;game.motion=new double[]{.1,-.2,.3};
        MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("stasis");config.hidden=false;runtime.configure(config);
        assertFalse(runtime.frozen());assertEquals(-.2,game.motion[1]);
        config.hidden=true;runtime.configure(config);assertTrue(runtime.frozen());
        game.world=new Object();game.player=new Object();game.position=new double[]{10,10,10};game.motion=new double[]{0,.7,0};runtime.configure(config);
        runtime.close();assertEquals(.7,game.motion[1]);assertArrayEquals(new double[]{10,10,10},game.position);
    }
    @Test void respawnOrWorldUnloadMidTickCannotApplyAnOldAnchorToTheNewPlayer() {
        Fake game=new Fake();game.ground=false;MovementRuntime runtime=new MovementRuntime(game);runtime.configure(config("stasis"));
        game.player=new Object();game.position=new double[]{50,70,90};game.ground=true;runtime.tickEnd();
        assertFalse(runtime.frozen());assertArrayEquals(new double[]{50,70,90},game.position);
        game.player=null;game.world=null;runtime.tickEnd();runtime.postInput();runtime.afterPlayerUpdate();
        assertFalse(runtime.frozen());assertFalse(runtime.send(game,1));
    }
    @Test void airborneFreezeRestoresMotionAndBlocksInputBrieflyAfterDisable() {
        Fake game=new Fake();game.ground=false;game.motion=new double[]{.2,-.4,.1};
        MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("stasis");runtime.configure(config);
        assertTrue(runtime.frozen());assertArrayEquals(new double[3],game.motion);
        game.position[1]=5;runtime.afterPlayerUpdate();assertEquals(10,game.position[1]);
        config.find("stasis").enabled=false;runtime.configure(config);assertArrayEquals(new double[]{.2,-.4,.1},game.motion);
        game.forward=1;game.jump=true;runtime.postInput();assertEquals(0,game.forward);assertFalse(game.jump);assertTrue(game.sneak);
        for(int i=0;i<2;i++){runtime.tickStart();game.forward=1;runtime.postInput();assertEquals(0,game.forward);}
        runtime.tickStart();game.forward=1;runtime.postInput();assertEquals(1,game.forward);
    }
    @Test void groundedIdleActivatesForInputSupportChangesAndDamageThenSettles() {
        Fake game=new Fake();MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("stasis");config.find("stasis").movement.pulse=2;runtime.configure(config);
        assertFalse(runtime.frozen());runtime.tickStart();assertFalse(runtime.frozen());
        game.feet=Arrays.<Object>asList("changed");runtime.tickStart();assertTrue(runtime.frozen());
        for(int i=0;i<3;i++){runtime.postInput();runtime.tickStart();}assertFalse(runtime.frozen());
        game.hurt=5;runtime.tickStart();assertTrue(runtime.frozen());
        for(int i=0;i<3;i++){runtime.postInput();runtime.tickStart();}assertFalse(runtime.frozen());
        game.forward=1;runtime.postInput();assertTrue(runtime.frozen());assertEquals(0,game.forward);
    }
    @Test void pulsesReleaseStoredMotionForOneTickAndReanchor() {
        Fake game=new Fake();game.ground=false;game.motion=new double[]{0,-.3,0};
        MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("stasis");config.find("stasis").movement.pulse=3;runtime.configure(config);
        runtime.tickStart();runtime.tickStart();assertTrue(runtime.frozen());runtime.tickStart();assertFalse(runtime.frozen());assertEquals(-.3,game.motion[1]);
        game.forward=1;runtime.postInput();assertEquals(0,game.forward);
        game.position[1]=9.7;game.motion[1]=-.37;runtime.afterPlayerUpdate();runtime.tickEnd();runtime.tickStart();
        assertTrue(runtime.frozen());assertEquals(9.7,game.position[1]);assertEquals(0,game.motion[1]);
        runtime.close();assertEquals(-.37,game.motion[1]);
    }
    @Test void velocityAndExplosionWindowsAllowKnockbackButCancelZeroVelocityWhileHolding() {
        Fake game=new Fake();game.ground=false;MovementRuntime runtime=new MovementRuntime(game);HudConfig config=config("stasis");runtime.configure(config);
        assertTrue(runtime.receive(new MovementAccess.Incoming(2,new double[3])));
        assertFalse(runtime.receive(new Object()));
        assertFalse(runtime.receive(new MovementAccess.Incoming(2,new double[]{.2,.4,0})));assertFalse(runtime.frozen());
        game.motion=new double[]{.2,.4,0};runtime.tickStart();assertFalse(runtime.frozen());
        game.forward=1;runtime.postInput();assertEquals(0,game.forward);
        game.position[0]=.2;runtime.tickEnd();runtime.tickStart();assertTrue(runtime.frozen());assertEquals(.2,game.position[0]);
        runtime.receive(new MovementAccess.Incoming(3,new double[]{0,-.4,0}));assertFalse(runtime.frozen());
        game.position[1]=9.49;runtime.afterPlayerUpdate();assertTrue(runtime.frozen());assertEquals(9.49,game.position[1]);
    }
    @Test void gravityDoesNotCreateVelocityWindowButNewImpulseDoes() {
        Fake game=new Fake();game.ground=false;MovementRuntime runtime=new MovementRuntime(game);runtime.configure(config("stasis"));
        game.motion[1]=-.0784;runtime.tickEnd();assertTrue(runtime.frozen());assertEquals(0,game.motion[1]);
        game.motion[0]=.4;runtime.tickEnd();assertFalse(runtime.frozen());assertEquals(.4,game.motion[0]);
    }
    @Test void flagPacketsKeepLookAndGroundAndAllowTeleportAcknowledgementForTwoTicks() {
        Fake game=new Fake();game.ground=false;MovementRuntime runtime=new MovementRuntime(game);runtime.configure(config("stasis"));
        assertTrue(runtime.send(game,1));assertTrue(runtime.send(game,2));assertEquals(1,game.lookPackets);assertFalse(runtime.send(game,0));
        runtime.receive(new MovementAccess.Incoming(1,new double[]{20,30,40}));
        assertArrayEquals(new double[]{0,10,0},game.position,"relative teleport must use original position in vanilla");
        game.position=new double[]{20,30,40};assertFalse(runtime.send(game,2));
        runtime.tickStart();assertFalse(runtime.send(game,1));runtime.tickStart();assertFalse(runtime.send(game,1));
        runtime.tickStart();assertTrue(runtime.send(game,1));runtime.close();assertFalse(runtime.send(game,1));
        assertArrayEquals(new double[3],game.motion,"teleport clears previously stored motion");
    }
    @Test void settingsRestoreDefaultsAndClampMalformedValues() {
        HudConfig config=new HudConfig();assertFalse(config.find("sprint").enabled);assertTrue(config.find("sprint").movement.disableBackwards);
        assertFalse(config.find("sprint").movement.displayText);assertEquals(60,config.find("stasis").movement.pulse);
        config.find("sprint").movement=null;config.find("stasis").movement.pulse=Double.NaN;config.normalize();
        assertEquals("[Sprint (Toggled)]",config.find("sprint").movement.text);assertEquals(60,config.find("stasis").movement.pulse);
        config.find("stasis").movement.pulse=200;config.find("sprint").movement.text="&aSprint\nTest";config.normalize();
        assertEquals(85,config.find("stasis").movement.pulse);assertEquals("\u00a7aSprint Test",config.find("sprint").movement.formattedText());
        assertTrue(config.find("sprint").isHidden());assertFalse(config.find("sprint").isHud());
    }
    private static HudConfig config(String id) { HudConfig c=new HudConfig();c.hidden=true;c.find(id).enabled=true;return c; }
    private static final class Fake extends MovementAccess {
        Object world=new Object(),player=new Object();double[] position={0,10,0},motion=new double[3];
        boolean ground=true,jump,sneak=true,sprintKey,physicalSprint;float forward,strafe,yaw;int hurt,sprintStops,lookPackets;
        List<Object> feet=Arrays.<Object>asList("stone");
        Fake(){super(new Object());}
        @Override public Object player(){return player;} @Override public Object world(){return world;}
        @Override public double[] position(){return position.clone();} @Override public void position(double[] v){position=v.clone();}
        @Override public double[] motion(){return motion.clone();} @Override public void motion(double[] v){motion=v.clone();}
        @Override public boolean ground(){return ground;} @Override public int hurtTime(){return hurt;}
        @Override public float forward(){return forward;} @Override public float strafe(){return strafe;} @Override public float yaw(){return yaw;} @Override public boolean jump(){return jump;}
        @Override public void blockInput(){forward=strafe=0;jump=false;} @Override public List<Object> feet(){return feet;}
        @Override public void sprintKey(boolean down){sprintKey=down;} @Override public boolean physicalSprintKey(){return physicalSprint;}
        @Override public void stopSprint(){sprintKey=false;sprintStops++;}
        @Override public Incoming incoming(Object packet){return packet instanceof Incoming ? (Incoming)packet : null;}
        @Override public int movingPacket(Object packet){return (Integer)packet;}
        @Override public void sendLook(Object manager,Object packet){lookPackets++;}
    }
}
