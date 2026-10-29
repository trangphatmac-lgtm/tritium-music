package tritium.minecraft.runtime.hook;

import tritium.minecraft.runtime.util.GameReflection;
import tritium.minecraft.runtime.util.PacketBypass;
import java.util.*;

/** Exact 1.8.9 MCP/SRG access; movement algorithms do not depend on game classes. */
public class MovementAccess extends GameReflection {
    private final Object minecraft;
    MovementAccess(Object minecraft) { super(minecraft.getClass().getClassLoader()); this.minecraft = minecraft; }
    Object player() { return get(minecraft, "thePlayer/field_71439_g"); }
    Object world() { return get(minecraft, "theWorld/field_71441_e"); }
    private Object input() { return get(player(), "movementInput/field_71158_b"); }
    public double[] position() { return vector("posX/field_70165_t", "posY/field_70163_u", "posZ/field_70161_v"); }
    public double[] motion() { return vector("motionX/field_70159_w", "motionY/field_70181_x", "motionZ/field_70179_y"); }
    private double[] vector(String x, String y, String z) {
        Object p = player(); return new double[]{number(get(p,x)).doubleValue(),number(get(p,y)).doubleValue(),number(get(p,z)).doubleValue()};
    }
    public void motion(double[] v) {
        Object p = player(); set(p,"motionX/field_70159_w",v[0]); set(p,"motionY/field_70181_x",v[1]); set(p,"motionZ/field_70179_y",v[2]);
    }
    public void position(double[] v) {
        Object p = player(); call(p,"setPosition/func_70107_b",v[0],v[1],v[2]);
        set(p,"lastTickPosX/field_70142_S",v[0]); set(p,"lastTickPosY/field_70137_T",v[1]); set(p,"lastTickPosZ/field_70136_U",v[2]);
        set(p,"prevPosX/field_70169_q",v[0]); set(p,"prevPosY/field_70167_r",v[1]); set(p,"prevPosZ/field_70166_s",v[2]);
    }
    public boolean ground() { return bool(get(player(),"onGround/field_70122_E")); }
    public int hurtTime() { return integer(get(player(),"hurtTime/field_70737_aN")); }
    public float yaw() { return number(get(player(),"rotationYaw/field_70177_z")).floatValue(); }
    public float forward() { return number(get(input(),"moveForward/field_78900_b")).floatValue(); }
    public float strafe() { return number(get(input(),"moveStrafe/field_78902_a")).floatValue(); }
    public boolean jump() { return bool(get(input(),"jump/field_78901_c")); }
    public void blockInput() {
        Object in = input(); if (in == null) return;
        set(in,"moveForward/field_78900_b",0f); set(in,"moveStrafe/field_78902_a",0f); set(in,"jump/field_78901_c",false);
    }
    private Object sprintBinding() { return get(get(minecraft,"gameSettings/field_71474_y"),"keyBindSprint/field_151444_V"); }
    public void sprintKey(boolean down) { call(type("client.settings.KeyBinding"),"setKeyBindState/func_74510_a",call(sprintBinding(),"getKeyCode/func_151463_i"),down); }
    public boolean physicalSprintKey() { return bool(call(type("client.settings.GameSettings"),"isKeyDown/func_100015_a",sprintBinding())); }
    public void stopSprint() { sprintKey(false); set(player(),"sprintToggleTimer/field_71156_d",0); call(player(),"setSprinting/func_70031_b",false); }

    /** Coordinates, identity, metadata and solidity all matter, even without player displacement. */
    public List<Object> feet() {
        Object p = player(), box = call(p,"getEntityBoundingBox/func_174813_aQ"), world = get(p,"worldObj/field_70170_p");
        if (box == null || world == null) return null;
        int x0 = floor(box,"minX/field_72340_a",0), x1 = floor(box,"maxX/field_72336_d",1e-7);
        int z0 = floor(box,"minZ/field_72339_c",0), z1 = floor(box,"maxZ/field_72334_f",1e-7);
        int y = floor(box,"minY/field_72338_b",.001);
        List<Object> result = new ArrayList<Object>();
        for (int x=x0;x<=x1;x++) for (int z=z0;z<=z1;z++) {
            Object state = call(world,"getBlockState/func_180495_p",construct("util.BlockPos",x,y,z));
            Object block = state == null ? null : call(state,"getBlock/func_177230_c");
            result.add(x); result.add(y); result.add(z); result.add(block);
            result.add(block == null ? 0 : call(block,"getMetaFromState/func_176201_c",state));
            result.add(block != null && bool(call(call(block,"getMaterial/func_149688_o"),"blocksMovement/func_76230_c")));
        }
        return result;
    }
    private int floor(Object box,String name,double epsilon) { return (int)Math.floor(number(get(box,name)).doubleValue()-epsilon); }
    // Incoming callbacks run after PacketThreadUtil's game-thread handoff, before vanilla mutation.
    public Incoming incoming(Object packet) {
        if (is(packet,"network.play.server.S08PacketPlayerPosLook")) {
            double[] pos = position(); Set<?> flags = (Set<?>)call(packet,"func_179834_f");
            String[] names = {"getX/func_148932_c","getY/func_148928_d","getZ/func_148933_e"};
            String[] axes = {"X","Y","Z"}; double[] target = new double[3];
            for (int i=0;i<3;i++) {
                target[i] = number(call(packet,names[i])).doubleValue();
                for (Object flag:flags) if (((Enum<?>)flag).name().equals(axes[i])) target[i] += pos[i];
            }
            return new Incoming(1,target);
        }
        if (is(packet,"network.play.server.S12PacketEntityVelocity")) {
            if (integer(call(packet,"getEntityID/func_149412_c")) != integer(call(player(),"getEntityId/func_145782_y"))) return null;
            return new Incoming(2,new double[]{number(call(packet,"getMotionX/func_149411_d")).doubleValue()/8000,
                    number(call(packet,"getMotionY/func_149410_e")).doubleValue()/8000,number(call(packet,"getMotionZ/func_149409_f")).doubleValue()/8000});
        }
        if (is(packet,"network.play.server.S27PacketExplosion")) return new Incoming(3,new double[]{
                number(call(packet,"func_149149_c")).doubleValue(),number(call(packet,"func_149144_d")).doubleValue(),number(call(packet,"func_149147_e")).doubleValue()});
        return null;
    }
    public static final class Incoming {
        public final int kind;
        public final double[] vector;
        Incoming(int kind,double[] vector) { this.kind=kind;this.vector=vector; }
    }
    public int movingPacket(Object packet) {
        if (is(packet,"network.play.client.C03PacketPlayer$C04PacketPlayerPosition")) return 1;
        if (is(packet,"network.play.client.C03PacketPlayer$C06PacketPlayerPosLook")) return 2;
        return 0;
    }
    public void sendLook(final Object manager,Object packet) {
        final Object look = construct("network.play.client.C03PacketPlayer$C05PacketPlayerLook",
                call(packet,"getYaw/func_149462_g"),call(packet,"getPitch/func_149470_h"),call(packet,"isOnGround/func_149465_i"));
        PacketBypass.run(() -> call(manager,"sendPacket/func_179290_a",look));
    }
}
