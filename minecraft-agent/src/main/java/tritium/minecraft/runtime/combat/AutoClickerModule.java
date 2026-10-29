package tritium.minecraft.runtime.combat;

import tritium.minecraft.protocol.CombatSettings;
import tritium.minecraft.runtime.hook.CombatAccess;
import tritium.minecraft.runtime.hook.CombatRuntime;
import java.util.Random;
import tritium.minecraft.runtime.util.CombatMath;

/** Render-driven Raven click timing; inventory clicks are consumed on the game tick. */
public final class AutoClickerModule {
    private final CombatRuntime runtime;
    private final CombatAccess game;
    private final Random random;
    private long nextPress, nextRelease;
    private long phaseEnd, extraEnd;
    private boolean slowPhase, blockHitDown, breaking, paused, inventoryClick;
    private double slowFactor, slowMin, slowMax;
    private int extraMin, extraMax, blockHitAcc=100, activeButton=-1;
    private final boolean[] owned = new boolean[2];
    public AutoClickerModule(CombatRuntime runtime, CombatAccess game, Random random) { this.runtime=runtime; this.game=game; this.random=random; }
    private CombatSettings settings() { return runtime.settings("autoClicker"); }
    public long nextPress() { return nextPress; }
    public long nextRelease() { return nextRelease; }
    public void enable() { slowMin=between(1.09,1.12); slowMax=between(1.22,1.25); extraMin=integer(40,50); extraMax=integer(85,95); }
    private double between(double min,double max) { return min+(max-min)*random.nextDouble(); }
    private int integer(int min,int max) { return min+random.nextInt(max-min+1); }
    private void schedule(boolean inventory,long now) {
        CombatSettings c=settings();
        double cps=inventory ? c.number("inventoryCps")+between(-.6,.8) : between(c.number("minCps"),c.number("maxCps"))+between(-.3,.4);
        long delay=Math.round(1000/Math.max(.1,cps));
        if (now>phaseEnd) { if (slowPhase) slowPhase=false; else if (random.nextInt(100)>=80) { slowPhase=true; slowFactor=between(slowMin,slowMax); } phaseEnd=now+integer(700,2200); }
        if (slowPhase) delay=(long)(delay*slowFactor);
        if (now>extraEnd) { if (random.nextInt(100)>=75) delay+=integer(extraMin,extraMax); extraEnd=now+integer(700,2200); }
        nextPress=now+delay; nextRelease=now+delay/2-random.nextInt(10);
    }
    public void preUpdate() { if (inventoryClick) { inventoryClick=false; if (game.inventory() && game.mouse(0) && game.shift()) game.clickScreen(); } }
    public void render(long now) {
        CombatSettings c=settings();
        if (game.focused()) {
            boolean weapon=game.weapon();
            if (!weapon && game.eating()) { pause(); return; }
            int button=c.on("leftClick") && game.mouse(0) ? 0 : c.on("rightClick") && game.mouse(1) ? 1 : -1;
            if (button<0) { reset(); return; }
            if (button==0 && (!weapon && c.on("weaponOnly") || weapon && c.number("blockHit")==0 && game.blocking())
                    || button==1 && c.on("blocksOnly") && !game.blockItem()) { pause(); return; }
            if (activeButton!=button) { reset(); activeButton=button; }
            if (paused) { paused=false; nextRelease=2; nextPress=2; }
            click(button,now);
        } else if (c.number("inventoryCps")>0 && game.inventory() && game.shift() && game.mouse(0)) {
            if (activeButton!=-2) { reset(); activeButton=-2; }
            if (nextPress==0 || nextRelease==0) schedule(true,now);
            else if (now>nextPress) { schedule(true,now); inventoryClick=true; }
        } else reset();
    }
    private void click(int button,long now) {
        CombatSettings c=settings();
        if (c.on("breakBlocks") && button==0 && game.breakableBlock()) {
            breaking=true; owned[0]=true; game.key(0,true,false); return;
        }
        if (breaking) { breaking=false; game.key(0,false,false); }
        double jitter=c.number("jitter")*.45;
        if (jitter>0) game.rotation(game.yaw()+(float)((random.nextBoolean()?1:-1)*random.nextFloat()*jitter),
                CombatMath.pitch(game.pitch()+(float)((random.nextBoolean()?1:-1)*random.nextFloat()*jitter*.45)));
        if (nextPress>0 && nextRelease>0) {
            if (now>nextPress) {
                if (press(button)) {
                    if (button==0) runtime.leftClick(now);
                    double chance=c.number("blockHit");
                    if (button==0 && chance>0 && game.mouse(1) && game.sword()) {
                        blockHitAcc+=chance;
                        if (blockHitAcc>=100) { blockHitAcc-=100; blockHitDown=press(1); }
                    }
                }
                schedule(false,now);
            } else if (nextRelease!=1 && now>nextRelease) {
                release(button);
                if (button==0 && blockHitDown) { release(1); blockHitDown=false; }
                nextRelease=1;
            }
        } else schedule(false,now);
    }
    private boolean press(int button) { if (!game.mouseEvent(button,true)) return false; owned[button]=true; game.key(button,true,true); return true; }
    private void release(int button) { game.mouseEvent(button,false); game.key(button,false,false); owned[button]=true; }
    private void pause() { releaseOwned(); paused=true; inventoryClick=false; }
    private void releaseOwned() { for (int i=0;i<2;i++) if (owned[i]) { owned[i]=false; game.mouseEvent(i,false); game.restoreKey(i); } blockHitDown=false; breaking=false; }
    public void reset() { releaseOwned(); nextRelease=nextPress=0; paused=false; blockHitAcc=100; inventoryClick=false; activeButton=-1; }
    public void disable() { reset(); slowPhase=false; phaseEnd=extraEnd=0; }
    public void knockback(long now,long lastVelocity) {
        if (settings().on("clickOnDamage") && nextPress>0 && settings().on("leftClick") && game.mouse(0) && now-lastVelocity>250 && game.hitEntity()!=null) {
            if (now<nextRelease) { nextRelease=2; nextPress=now+20; } else nextPress=2;
        }
    }
}
