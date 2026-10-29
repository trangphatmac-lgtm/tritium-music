package tritium.minecraft.runtime.hook;

import tritium.minecraft.runtime.util.CombatMath;
import tritium.minecraft.runtime.combat.AutoBlockModule;
import tritium.minecraft.runtime.util.PacketDelayQueue;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatRuntimeTest {
    private static HudConfig config(String... enabled) {
        HudConfig c=new HudConfig(); c.hidden=true;
        for (String id:enabled) c.find(id).enabled=true;
        c.normalize(); return c;
    }
    @Test void optionSchemaMatchesRavenAndNormalizesOldOrMalformedConfiguration() {
        assertEquals(14,CombatSettings.options("aimAssist").size());
        assertEquals(17,CombatSettings.options("autoBlock").size());
        assertEquals(11,CombatSettings.options("autoClicker").size());
        HudConfig c=new HudConfig(); c.modules=new HudConfig.Module[]{c.find("musicInfo")}; c.normalize();
        assertEquals(10,c.modules.length); assertFalse(c.hidden); assertFalse(c.find("aimAssist").enabled);
        assertEquals(50,c.find("aimAssist").combat.number("speed"));
        assertEquals(6.1,CombatSettings.options("autoBlock").get(4).max);
        CombatSettings click=c.find("autoClicker").combat;
        click.numbers.put("minCps",19.8); click.numbers.put("maxCps",3.2); click.numbers.put("jitter",Double.NaN);
        c.normalize(); assertEquals(3,click.number("minCps")); assertEquals(20,click.number("maxCps")); assertEquals(0,click.number("jitter"));
        c.find("aimAssist").combat=null; c.normalize(); assertTrue(c.find("aimAssist").combat.on("weaponOnly"));
    }
    @Test void targetSelectionAndSilentAxesRestoreOnEveryExit() {
        Fake game=new Fake(); CombatRuntime r=new CombatRuntime(game,new Random(1)); HudConfig c=config("aimAssist");
        c.find("aimAssist").combat.toggles.put("silentAim",true);
        c.find("aimAssist").combat.toggles.put("rotatePitch",false);
        c.find("aimAssist").combat.numbers.put("speed",100.0);
        CombatAccess.Target tooFar=game.target(1,100),team=game.target(2,4),valid=game.target(12,4),invis=game.target(3,4);
        team.teammate=true; invis.invisible=true;
        r.configure(c); r.tickEnd(); assertTrue(r.mouseOver()); assertSame(valid,r.aimTarget());
        r.preMotion(); assertNotEquals(0,game.yaw); assertEquals(17,game.pitch);
        r.postMotion(); assertEquals(0,game.yaw); assertEquals(17,game.pitch);
        r.mouseOver(); r.preMotion(); r.abortMotion(); assertEquals(0,game.yaw);
        r.mouseOver(); r.preMotion(); c.hidden=false; r.configure(c); assertEquals(0,game.yaw); assertNull(r.aimTarget());
        assertTrue(c.find("aimAssist").enabled,"Hiding preserves the preference but stops operation");
    }
    @Test void aimGatesVisibilityClickingBreakingAndStopOnTarget() {
        Fake game=new Fake(); CombatRuntime r=new CombatRuntime(game,new Random(2)); HudConfig c=config("aimAssist");
        CombatSettings a=c.find("aimAssist").combat; CombatAccess.Target target=game.target(10,4);
        r.configure(c); a.toggles.put("clickAim",true); r.tickEnd(); assertNull(r.aimTarget());
        r.leftClick(System.currentTimeMillis()); r.tickEnd(); assertSame(target,r.aimTarget());
        a.toggles.put("stopWhenBreaking",true); game.mouse[0]=true; game.block=true; r.tickEnd(); assertNull(r.aimTarget());
        game.block=false; a.toggles.put("aimThroughBlocks",false); game.visible=false; r.tickEnd(); assertNull(r.aimTarget());
        game.visible=true; a.toggles.put("stopOnTarget",true); game.hit=target.entity; float yaw=game.yaw; r.tickEnd(); assertEquals(yaw,game.yaw);
        game.focus=false; r.configure(c); assertNull(r.aimTarget());
    }
    @Test void clickerSchedulesReleasesBreaksBlocksAndCleansUpOnHidden() {
        Fake game=new Fake(); game.mouse[0]=true; CombatRuntime r=new CombatRuntime(game,new Random(3)); HudConfig c=config("autoClicker"); r.configure(c);
        r.clicker.render(1000); long next=r.clicker.nextPress(); assertTrue(next>1000);
        r.clicker.render(next+1); assertEquals(1,game.clicks[0]); assertTrue(game.keys[0]);
        r.clicker.render(r.clicker.nextRelease()+1); assertFalse(game.keys[0]);
        game.block=true; r.clicker.render(2000); assertTrue(game.keys[0]); assertEquals(1,game.clicks[0]);
        c.hidden=false; game.mouse[0]=false; r.configure(c); assertFalse(game.keys[0]); assertEquals(0,r.clicker.nextPress());
        c.hidden=true; r.configure(c); game.mouse[0]=true; r.clicker.render(3000); assertEquals(1,game.clicks[0]);
        game.focus=false; r.clicker.render(4000); assertEquals(0,r.clicker.nextPress());
    }
    @Test void damageAcceleratesLeftClicksAndInventoryUsesTickWithShift() {
        Fake game=new Fake(); game.mouse[0]=true; game.hit=new Object(); CombatRuntime r=new CombatRuntime(game,new Random(4)); r.configure(config("autoClicker"));
        r.clicker.render(1000); r.clicker.knockback(1010,0); assertEquals(1030,r.clicker.nextPress()); assertEquals(2,r.clicker.nextRelease());
        r.clicker.render(1031); assertEquals(1,game.clicks[0]);
        HudConfig c=config("autoClicker"); c.find("autoClicker").combat.numbers.put("inventoryCps",10.0);
        game.focus=false; game.inventory=true; game.shift=true; r.configure(c); r.clicker.render(2000); r.clicker.render(r.clicker.nextPress()+1);
        assertEquals(0,game.inventoryClicks); r.preUpdate(); assertEquals(1,game.inventoryClicks);
        r.clicker.render(r.clicker.nextPress()+1); game.inventory=false; r.preUpdate(); assertEquals(1,game.inventoryClicks);
    }
    @Test void blockingHonorsHurtHoldCooldownAndKnockback() {
        Fake game=new Fake(); game.target(10,4); CombatRuntime r=new CombatRuntime(game,new Random(5)); HudConfig c=config("autoBlock");
        c.find("autoBlock").combat.toggles.put("forceBlockAnimation",true); r.configure(c);
        game.hurt=3; r.blocker.postMotion(1000); assertTrue(r.blocker.fakeBlocking()); assertEquals(0,game.clicks[1]);
        game.hurt=2; r.blocker.postMotion(1050); assertTrue(game.keys[1]); assertEquals(1,game.clicks[1]);
        r.blocker.postMotion(1100); r.blocker.postMotion(1150); assertTrue(game.keys[1]);
        r.blocker.postMotion(1200); assertFalse(game.keys[1]);
        for (int i=0;i<4;i++) r.blocker.postMotion(1250+i*50); assertEquals(1,game.clicks[1]);
        r.blocker.postMotion(1450); assertEquals(2,game.clicks[1]);
        r.blocker.knockback(); assertFalse(game.keys[1]);
        assertEquals(0,AutoBlockModule.ticks(0)); assertEquals(1,AutoBlockModule.ticks(10));
    }
    @Test void lagStartsAfterSwordUseFlushesOnAttackAndNeverCarriesIntoAnotherWorld() {
        Fake game=new Fake(); game.target(10,4); CombatRuntime r=new CombatRuntime(game,new Random(6)); HudConfig c=config("autoBlock");
        c.find("autoBlock").combat.numbers.put("lagChance",100.0); r.configure(c);
        assertFalse(r.send(game,"sword")); r.blocker.postMotion(1000); assertTrue(r.blocker.lagging());
        assertTrue(r.send(game,"move")); assertTrue(r.send(game,"attack")); assertTrue(game.sent.isEmpty());
        r.blocker.postMotion(1050); assertEquals(Arrays.asList("move","attack"),game.sent);
        r.send(game,"sword"); r.blocker.postMotion(1100); r.send(game,"old-world");
        game.world=new Object(); r.configure(c); assertFalse(game.sent.contains("old-world")); assertFalse(r.blocker.lagging());
        r.send(game,"sword"); r.blocker.postMotion(1200); r.send(game,"pending"); c.hidden=false; r.configure(c);
        assertTrue(game.sent.contains("pending")); assertFalse(game.keys[1]);
    }
    @Test void packetQueueReplaysInOrderOnceWithoutRecursion() {
        List<Object> sent=new ArrayList<Object>(); PacketDelayQueue[] q=new PacketDelayQueue[1];
        q[0]=new PacketDelayQueue((m,p)->{ assertFalse(q[0].hold(m,p)); sent.add(p); });
        q[0].open(); assertTrue(q[0].hold(this,"one")); assertTrue(q[0].hold(this,"two"));
        q[0].flush(); q[0].flush(); assertEquals(Arrays.asList("one","two"),sent);
        q[0].open(); q[0].hold(this,"discard"); q[0].discard(); q[0].flush(); assertEquals(2,sent.size());
    }
    @Test void aimMathUsesBoundingBoxAndSensitivityAndFinitePitch() {
        assertEquals(-180,CombatMath.wrap(180)); assertEquals(2,CombatMath.wrap(362));
        assertNotNull(CombatMath.intercept(new double[]{0,1,0},new double[]{-.3,0,2,.3,2,3},0,0,3));
        assertNull(CombatMath.intercept(new double[]{0,1,0},new double[]{-.3,0,2,.3,2,3},90,0,3));
        float[] r=CombatMath.toBox(new double[]{0,1,0},new double[]{-1,0,-1,1,2,1},20,30); assertArrayEquals(new float[]{20,30},r);
        assertEquals(90,CombatMath.sensitivity(0,200,0,0,.5f,0)[1]);
    }
    static class Fake extends CombatAccess {
        Object world=new Object(),player=new Object(),hit;
        boolean focus=true,sword=true,block,visible=true,inventory,shift,eating,blocking;
        float yaw,pitch=17; int hurt,inventoryClicks;
        final boolean[] mouse=new boolean[2],keys=new boolean[2]; final int[] clicks=new int[2];
        final List<Target> targets=new ArrayList<Target>(); final List<Object> sent=new ArrayList<Object>();
        Fake() { super(null); }
        Target target(double angle,double distanceSq) { Target t=new Target(); t.entity=new Object(); t.player=true; t.angle=angle; t.distanceSq=distanceSq; targets.add(t); return t; }
        @Override public Object world(){return world;} @Override public Object player(){return player;} @Override public boolean inWorld(){return world!=null;}
        @Override public boolean focused(){return focus;} @Override public boolean singleplayer(){return false;} @Override public boolean mouse(int b){return mouse[b];}
        @Override public boolean sword(){return sword;} @Override public boolean weapon(){return sword;} @Override public boolean eating(){return eating;}
        @Override public boolean blocking(){return blocking;} @Override public boolean blockItem(){return !sword;} @Override public boolean inventory(){return inventory;}
        @Override public boolean shift(){return shift;} @Override public void clickScreen(){inventoryClicks++;} @Override public int hurtTime(){return hurt;}
        @Override public boolean allowEdit(){return true;} @Override public boolean blockHit(){return block;} @Override public boolean breakingRay(){return block;}
        @Override public boolean breakableBlock(){return block;} @Override public float yaw(){return yaw;} @Override public float pitch(){return pitch;}
        @Override public void rotation(float y,float p){yaw=y;pitch=p;} @Override public float sensitivity(){return .5f;}
        @Override public Object hitEntity(){return hit;} @Override public List<Target> targets(){return targets;}
        @Override public boolean visible(Target t){return visible;} @Override public float[] aimPoint(Target t){return new float[]{40,35};}
        @Override public boolean rayHits(Target t,double range){return true;} @Override public void mouseOver(Target t,float y,float p){hit=t.entity;}
        @Override public void key(int b,boolean down,boolean tick){keys[b]=down;if(tick)clicks[b]++;}
        @Override public void restoreKey(int b){keys[b]=mouse[b];} @Override public boolean mouseEvent(int b,boolean down){return true;}
        @Override public boolean targetMatches(Object e,boolean players){return e!=null;} @Override public boolean interactable(){return false;}
        @Override public boolean lookingAtBot(){return false;} @Override public boolean attackPacket(Object p){return "attack".equals(p);}
        @Override public boolean placement(Object p){return "sword".equals(p)||"item".equals(p);}
        @Override public boolean swordPlacement(Object p){return "sword".equals(p);} @Override public boolean blockPlacement(Object p){return "sword".equals(p);}
        @Override public void send(Object m,Object p){sent.add(p);} @Override public boolean velocityForPlayer(Object p){return true;}
    }
}
