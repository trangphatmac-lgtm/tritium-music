package tritium.minecraft.runtime.hook;

import tritium.minecraft.protocol.*;
import tritium.minecraft.runtime.combat.AimAssistModule;
import tritium.minecraft.runtime.combat.AutoBlockModule;
import tritium.minecraft.runtime.combat.AutoClickerModule;
import java.util.Random;

/** Coordinates the three modules on the game thread; packet interception can run on Netty. */
public final class CombatRuntime implements AutoCloseable {
    private final CombatAccess game;
    private volatile HudConfig config;
    final AimAssistModule aim;
    final AutoClickerModule clicker;
    final AutoBlockModule blocker;
    private Object world;
    private volatile boolean aimOn,clickerOn,blockerOn;
    private volatile boolean singleplayer=true;
    private long lastLeftClick,lastVelocity;
    public CombatRuntime(Object minecraft) { this(new CombatAccess(minecraft),new Random()); }
    CombatRuntime(CombatAccess game,Random random) {
        this.game=game; aim=new AimAssistModule(this,game,random); clicker=new AutoClickerModule(this,game,random); blocker=new AutoBlockModule(this,game,random);
    }
    public CombatSettings settings(String id) { return config.find(id).combat; }
    public CombatAccess.Target aimTarget() { return aim.target(); }
    public boolean singleplayer() { return singleplayer; }
    public boolean blockerActive() { return blockerOn && enabled("autoBlock"); }
    public boolean enabled(String id) { HudConfig c=config; return c!=null && c.hidden && c.find(id).enabled; }
    public void configure(HudConfig next) {
        config=next;
        boolean active=game.inWorld(); Object current=active ? game.world() : null;
        if (world!=current) { reset(true); world=current; }
        singleplayer=!active || game.singleplayer();
        boolean a=active && enabled("aimAssist"), c=active && enabled("autoClicker"), b=active && game.focused() && enabled("autoBlock");
        boolean oldAim=aimOn, oldClicker=clickerOn, oldBlocker=blockerOn;
        // Publish the gate before teardown so concurrent sends cannot reopen a closed queue.
        aimOn=a; clickerOn=c; blockerOn=b;
        if (oldAim && !a) aim.reset();
        if (oldClicker && !c) clicker.disable(); else if (!oldClicker && c) clicker.enable();
        if (oldBlocker && !b) blocker.reset(false);
        if (!active || !game.focused()) { aim.reset(); if (blockerOn) blocker.reset(false); }
    }
    public void renderStart() { if (clickerOn) clicker.render(System.currentTimeMillis()); }
    public void renderEnd() { if (blockerOn) blocker.render(System.currentTimeMillis()); }
    public void preUpdate() { if (clickerOn) clicker.preUpdate(); }
    public void tickEnd() { if (aimOn) aim.update(); }
    public void preMotion() { if (aimOn) aim.preMotion(); }
    public void postMotion() { if (aimOn) aim.postMotion(); if (blockerOn) blocker.postMotion(System.currentTimeMillis()); }
    public void abortMotion() { aim.restore(); }
    public boolean mouseOver() { return aimOn && aim.mouseOver(); }
    public boolean mouse(boolean down,int button) {
        if (!down) return false;
        if (button==0) leftClick(System.currentTimeMillis());
        return button==1 && blockerOn && blocker.cancelRightMouse();
    }
    public void leftClick(long now) { lastLeftClick=now; }
    public boolean clicking() { return enabled("autoClicker") && settings("autoClicker").on("leftClick") ? game.mouse(0) : System.currentTimeMillis()-lastLeftClick<220; }
    public boolean send(Object manager,Object packet) { return blockerOn && enabled("autoBlock") && blocker.send(manager,packet); }
    public void velocity(Object packet) {
        if (!game.velocityForPlayer(packet)) return;
        long now=System.currentTimeMillis();
        if (blockerOn) blocker.knockback(); if (clickerOn) clicker.knockback(now,lastVelocity); lastVelocity=now;
    }
    public boolean fakeBlocking(Object player) { return blockerOn && enabled("autoBlock") && blocker.fakeBlocking() && game.inWorld() && player==game.player() && game.sword(); }
    private void reset(boolean worldChanged) { aimOn=clickerOn=blockerOn=false; aim.reset(); clicker.disable(); blocker.reset(worldChanged); lastLeftClick=lastVelocity=0; }
    @Override public void close() { reset(!game.inWorld()); }
}
