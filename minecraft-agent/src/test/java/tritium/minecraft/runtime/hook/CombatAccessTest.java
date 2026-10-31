package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatAccessTest {
    @Test void actualBlockingIgnoresPresentationHookAndResolvesMcp() {
        Mc mc=new Mc(); CombatAccess access=new CombatAccess(mc);
        assertTrue(mc.thePlayer.isBlocking()); assertFalse(access.blocking());
        mc.thePlayer.using=true; mc.thePlayer.item.action="EAT"; assertFalse(access.blocking());
        mc.thePlayer.item.action="BLOCK"; assertTrue(access.blocking());
        mc.thePlayer.item=null; assertFalse(access.blocking());
    }
    @Test void srgAliasesResolveThroughPrivateInheritedFields() {
        SrgMc mc=new SrgMc(); CombatAccess access=new CombatAccess(mc);
        assertFalse(access.blocking()); mc.field_71439_g.using=true; assertTrue(access.blocking());
        access.rotation(42,13); assertEquals(42,access.yaw()); assertEquals(13,access.pitch());
    }
    @Test void onlyPositiveSub4500VelocityIsKnockbackAfterPacketFilters() {
        CombatAccess access = new CombatAccess(new Mc());
        Velocity packet = new Velocity();
        for (int y : new int[]{-100,0,4500,8000}) { packet.y=y; assertFalse(access.knockback(packet)); }
        for (int y : new int[]{1,3200,4499}) { packet.y=y; assertTrue(access.knockback(packet)); }
    }
    static class Velocity { int y; int getMotionY() { return y; } }
    static class Mc { Player thePlayer=new Player(); }
    static class Player { boolean using; Stack item=new Stack(); boolean isBlocking(){return true;} boolean isUsingItem(){return using;} Stack getItemInUse(){return item;} }
    static class Stack { String action="BLOCK"; String getItemUseAction(){return action;} }
    static class SrgMc { SrgPlayer field_71439_g=new SrgPlayer(); }
    static class SrgEntity { private float field_70177_z,field_70125_A; }
    static class SrgPlayer extends SrgEntity { boolean using; boolean func_71039_bw(){return using;} SrgStack func_71011_bu(){return new SrgStack();} }
    static class SrgStack { String func_77975_n(){return "BLOCK";} }
}
