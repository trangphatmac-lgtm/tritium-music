package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import tritium.minecraft.runtime.util.PacketBypass;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MovementAccessTest {
    @Test void relativeTeleportResolvesAgainstOriginalPositionWithoutMutatingPlayer() {
        Mc mc=new Mc();Access access=new Access(mc);Teleport packet=new Teleport();packet.flags=EnumSet.of(Axis.X,Axis.Z);
        MovementAccess.Incoming result=access.incoming(packet);
        assertEquals(1,result.kind);assertArrayEquals(new double[]{12,3,34},result.vector);
        assertArrayEquals(new double[]{10,20,30},access.position());
    }
    @Test void onlyOwnVelocityIsScaledAndExplosionComponentsArePreserved() {
        Mc mc=new Mc();Access access=new Access(mc);Velocity packet=new Velocity();
        assertArrayEquals(new double[]{.1,-.2,.3},access.incoming(packet).vector);
        packet.id=5;assertNull(access.incoming(packet));
        assertArrayEquals(new double[]{.25,-.5,.75},access.incoming(new Explosion()).vector);
    }
    @Test void inputRetainsSneakAndAnchorResetsAllInterpolationCoordinates() {
        Mc mc=new Mc();Access access=new Access(mc);access.blockInput();
        assertEquals(0,mc.thePlayer.movementInput.moveForward);assertEquals(0,mc.thePlayer.movementInput.moveStrafe);
        assertFalse(mc.thePlayer.movementInput.jump);assertTrue(mc.thePlayer.movementInput.sneak);
        access.position(new double[]{1,2,3});Player p=mc.thePlayer;
        assertEquals(1,p.posX);assertEquals(1,p.prevPosX);assertEquals(1,p.lastTickPosX);
        assertEquals(2,p.posY);assertEquals(2,p.prevPosY);assertEquals(2,p.lastTickPosY);
        assertEquals(3,p.posZ);assertEquals(3,p.prevPosZ);assertEquals(3,p.lastTickPosZ);
    }
    @Test void replacementLookPreservesAnglesAndGroundAndBypassesAllModulesOnlyDuringSend() {
        Access access=new Access(new Mc());Manager manager=new Manager();PosLook packet=new PosLook();
        assertEquals(1,access.movingPacket(new Position()));assertEquals(2,access.movingPacket(packet));assertEquals(0,access.movingPacket(new Object()));
        assertFalse(PacketBypass.active());access.sendLook(manager,packet);assertFalse(PacketBypass.active());
        assertTrue(manager.bypassed);assertEquals(42,manager.packet.yaw);assertEquals(-15,manager.packet.pitch);assertTrue(manager.packet.ground);
        assertThrows(IllegalStateException.class,()->PacketBypass.run(()->{throw new IllegalStateException();}));assertFalse(PacketBypass.active());
    }
    private static class Access extends MovementAccess {
        Access(Mc mc){super(mc);}
        @Override protected Class<?> type(String name){
            if(name.endsWith("S08PacketPlayerPosLook"))return Teleport.class;
            if(name.endsWith("S12PacketEntityVelocity"))return Velocity.class;
            if(name.endsWith("S27PacketExplosion"))return Explosion.class;
            if(name.endsWith("$C04PacketPlayerPosition"))return Position.class;
            if(name.endsWith("$C06PacketPlayerPosLook"))return PosLook.class;
            if(name.endsWith("$C05PacketPlayerLook"))return Look.class;
            return super.type(name);
        }
    }
    static class Mc { Player thePlayer=new Player(); }
    static class Player {
        double posX=10,posY=20,posZ=30,prevPosX,prevPosY,prevPosZ,lastTickPosX,lastTickPosY,lastTickPosZ;
        Input movementInput=new Input();int getEntityId(){return 7;}
        void setPosition(double x,double y,double z){posX=x;posY=y;posZ=z;}
    }
    static class Input { float moveForward=1,moveStrafe=-1;boolean jump=true,sneak=true; }
    enum Axis { X,Y,Z }
    static class Teleport { Set<Axis> flags=Collections.emptySet();double getX(){return 2;}double getY(){return 3;}double getZ(){return 4;}Set<Axis> func_179834_f(){return flags;} }
    static class Velocity { int id=7;int getEntityID(){return id;}int getMotionX(){return 800;}int getMotionY(){return -1600;}int getMotionZ(){return 2400;} }
    static class Explosion { float func_149149_c(){return .25f;}float func_149144_d(){return -.5f;}float func_149147_e(){return .75f;} }
    static class Position { }
    static class PosLook { float getYaw(){return 42;}float getPitch(){return -15;}boolean isOnGround(){return true;} }
    public static class Look { final float yaw,pitch;final boolean ground;public Look(float y,float p,boolean g){yaw=y;pitch=p;ground=g;} }
    static class Manager { Look packet;boolean bypassed;void sendPacket(Look p){packet=p;bypassed=PacketBypass.active();} }
}
