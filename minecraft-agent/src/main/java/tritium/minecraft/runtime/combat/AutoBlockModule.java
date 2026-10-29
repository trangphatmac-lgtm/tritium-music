package tritium.minecraft.runtime.combat;

import tritium.minecraft.protocol.CombatSettings;
import tritium.minecraft.runtime.hook.CombatAccess;
import tritium.minecraft.runtime.hook.CombatRuntime;
import java.util.Random;
import tritium.minecraft.runtime.util.PacketDelayQueue;

/** Tick-based Raven block/hold/cooldown state with an ordered, bounded packet delay. */
public final class AutoBlockModule {
    private final CombatRuntime runtime;
    private final CombatAccess game;
    private final Random random;
    private final PacketDelayQueue packets;
    private int holdTicks,cooldownTicks,lagAccumulator;
    private boolean hadSword,owned;
    private boolean fakeBlocking;
    private volatile boolean startLag,flush;
    private volatile long lagUntil;
    public AutoBlockModule(CombatRuntime runtime,CombatAccess game,Random random) {
        this.runtime=runtime; this.game=game; this.random=random; packets=new PacketDelayQueue(game::send);
    }
    private CombatSettings settings() { return runtime.settings("autoBlock"); }
    public boolean fakeBlocking() { return fakeBlocking; }
    public boolean lagging() { return packets.active(); }
    public synchronized void postMotion(long now) {
        CombatSettings c=settings();
        if (flush) { flush=false; packets.flush(); }
        if (startLag) { startLag=false; packets.open(); lagUntil=now+(long)c.number("lagDuration"); }
        boolean right=game.mouse(1), sword=game.sword();
        boolean manual=right && !c.on("onlyWhileRightClicking"), keepHeld=right && (!c.on("onlyWhileRightClicking") || !sword);
        boolean possible=game.focused() && sword && conditions();
        fakeBlocking=c.on("forceBlockAnimation") && possible;
        boolean block=possible && game.hurtTime()<=ticks(c.number("maxHurtTime"));
        if (cooldownTicks>0) cooldownTicks--;
        if (holdTicks>0) {
            holdTicks--;
            if (!block || holdTicks==0) { holdTicks=0; if (!keepHeld) release(); else restore(); }
        } else if (block && !manual && cooldownTicks==0) {
            int extra=ticks(c.number("randomHoldDuration"));
            if (extra>0) extra=Math.min(random.nextInt(extra+1),random.nextInt(extra+1));
            holdTicks=ticks(c.number("maxHoldDuration"))+extra;
            owned=true; game.key(1,true,true);
        }
        if (c.on("onlyWhileRightClicking") && right && !sword && hadSword) { owned=true; game.key(1,true,true); }
        hadSword=sword;
    }
    private boolean conditions() {
        CombatSettings c=settings();
        CombatAccess.Target aimTarget=runtime.aimTarget();
        Object target=aimTarget==null ? null : aimTarget.entity;
        if (c.on("onlyWhileTargeting") && !game.targetMatches(game.hitEntity(),c.on("ignoreMobs"))
                && !(runtime.enabled("aimAssist") && runtime.settings("aimAssist").on("silentAim") && game.targetMatches(target,c.on("ignoreMobs")))) return false;
        if (c.on("onlyWhileLeftClicking") && !runtime.clicking() || c.on("onlyWhileRightClicking") && !game.mouse(1)) return false;
        double range=c.number("range"); if (range>=6.1) return true;
        for (CombatAccess.Target e:game.targets()) {
            if (e.distanceSq>(range+1.2)*(range+1.2) || !e.player && (c.on("ignoreMobs") || !e.mob)
                    || c.on("disableWhileChasing") && e.away || c.on("ignoreTeammates") && e.teammate) continue;
            if (game.rayHits(e,range)) return true;
        }
        return false;
    }
    public boolean cancelRightMouse() { return settings().on("disableBlockOutOfRange") && game.focused() && game.sword() && !game.interactable() && !game.lookingAtBot(); }
    public synchronized void render(long now) { if (packets.active() && now>=lagUntil) { flush=false; packets.flush(); } }
    public synchronized boolean send(Object manager,Object packet) {
        if (!runtime.blockerActive() || packets.replaying()) return false;
        CombatSettings c=settings();
        if (packets.active()) {
            if (c.on("flushOnAttack") && game.attackPacket(packet) || c.on("flushOnInteractItem") && game.placement(packet) && !game.swordPlacement(packet)) flush=true;
        } else if (game.blockPlacement(packet) && c.number("lagChance")>0 && !runtime.singleplayer()) {
            lagAccumulator+=(int)c.number("lagChance");
            if (lagAccumulator>=100) { lagAccumulator-=100; startLag=true; }
        }
        return packets.hold(manager,packet);
    }
    public synchronized void knockback() { if (holdTicks>0) { holdTicks=0; release(); } }
    private void release() { game.key(1,false,false); owned=true; cooldownTicks=ticks(settings().number("cooldown")); }
    private void restore() { if (owned) { owned=false; game.restoreKey(1); } }
    public synchronized void reset(boolean worldChanged) {
        holdTicks=cooldownTicks=lagAccumulator=0; hadSword=fakeBlocking=startLag=flush=false;
        restore(); if (worldChanged) packets.discard(); else packets.flush();
    }
    public static int ticks(double ms) { return (int)Math.ceil((int)ms/50.0); }
}
