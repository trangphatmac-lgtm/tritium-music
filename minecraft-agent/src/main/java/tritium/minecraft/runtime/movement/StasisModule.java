package tritium.minecraft.runtime.movement;

import tritium.minecraft.protocol.MovementSettings;
import tritium.minecraft.runtime.hook.MovementAccess;
import java.util.List;

/** OpenB4's Simple Utils Stasis: fixed Flag mode, one-tick pulses and two-tick velocity windows. */
public final class StasisModule {
    private static final double EPS=1e-4;
    private final MovementAccess game;
    private MovementSettings settings;
    private boolean enabled, holding, keysDown;
    private double[] stored, anchor, lastMotion=new double[3], watch;
    private List<Object> feet;
    private int velocityTicks, velocityInputTicks, pulseTicks, pulseCooldown, blockAfterDisable, groundStable, lastHurt;
    private int ticksSinceTeleport=999;
    private double velocityFallStart;
    // Packet sends may arrive on Netty. Publish one immutable decision after every game-thread transition.
    private volatile boolean suppressPosition;
    public StasisModule(MovementAccess game) { this.game=game; }
    public void settings(MovementSettings settings) { this.settings=settings; }
    public void enable() {
        enabled=true; holding=false; keysDown=false; stored=null; anchor=null;
        velocityTicks=velocityInputTicks=pulseTicks=blockAfterDisable=groundStable=0;
        pulseCooldown=interval(); ticksSinceTeleport=999; feet=null;
        snapshotWatch();
        if (!game.ground()) beginHolding(); else idle(false);
        publish();
    }
    public void disable(boolean restore) {
        suppressPosition=false;
        if (restore && holding && frozen() && stored!=null) game.motion(stored);
        enabled=holding=keysDown=false; stored=anchor=watch=null; feet=null;
        velocityTicks=velocityInputTicks=pulseTicks=pulseCooldown=groundStable=0;
        blockAfterDisable=restore ? 3 : 0;
    }
    public boolean frozen() { return holding && pulseTicks<=0 && velocityTicks<=0; }
    private int interval() { return (int)settings.pulse; }
    private void publish() { suppressPosition=enabled && frozen() && anchor!=null && ticksSinceTeleport>2; }
    private void snapshotMotion() { lastMotion=game.motion(); }
    private void snapshotWatch() { watch=game.position();lastHurt=game.hurtTime();snapshotMotion();feet=game.feet(); }
    private void beginHolding() {
        boolean was=holding;holding=true;groundStable=0;
        if (!was) { anchor=game.position();stored=game.motion();game.motion(new double[3]);snapshotMotion();applyFrozen(); }
        else if (anchor==null) anchor=game.position();
        snapshotWatch();
    }
    private void idle(boolean zero) {
        holding=false;groundStable=velocityTicks=velocityInputTicks=pulseTicks=0;
        if (zero) { game.motion(new double[3]);stored=null; }
        snapshotWatch();
    }
    private boolean shouldActivate() {
        double[] motion=game.motion();
        return !game.ground() || game.hurtTime()>lastHurt || Math.hypot(motion[0],motion[2])>EPS
                || distance(game.position(),watch)>.001 || feet!=null && !feet.equals(game.feet());
    }
    public void tickStart() {
        if (!enabled) { if (blockAfterDisable>0) blockAfterDisable--;return; }
        if (!holding) { if (shouldActivate()) beginHolding(); else snapshotWatch(); }
        else {
            groundStable=game.ground() && velocityTicks<=0 && pulseTicks<=0 ? groundStable+1 : 0;
            if (groundStable>=3 && !keysDown && speed(game.motion())<=EPS) idle(true);
        }
        if (ticksSinceTeleport<1000) ticksSinceTeleport++;
        if (holding) {
            if (frozen()) { game.blockInput();applyFrozen(); }
            else if (pulseTicks>0 || velocityInputTicks>0) game.blockInput();
            if (pulseTicks>0) { if (--pulseTicks==0) { refreeze();pulseCooldown=interval(); } }
            else if (velocityTicks>0) {
                if (velocityInputTicks>0) velocityInputTicks--;
                if (--velocityTicks==0) { velocityInputTicks=0;refreeze(); }
            } else if (settings.pulse>2) {
                if (pulseCooldown>0) pulseCooldown--;
                if (pulseCooldown<=0) { pulseTicks=1;if (stored!=null) game.motion(stored);pulseCooldown=interval();groundStable=0; }
            }
        }
        publish();
    }
    public void postInput() {
        if (!enabled) { if (blockAfterDisable>0) game.blockInput();return; }
        keysDown=Math.abs(game.forward())>EPS || Math.abs(game.strafe())>EPS || game.jump();
        if (!holding) { if (!keysDown) return;beginHolding(); }
        if (velocityTicks<=0 || velocityInputTicks>0) game.blockInput();
        publish();
    }
    public void afterPlayerUpdate() {
        if (!enabled || !holding) return;
        if (frozen()) applyFrozen();
        else if (velocityTicks>0 && velocityFallStart-game.position()[1]>=.5) {
            velocityTicks=velocityInputTicks=0;refreeze();
        }
        publish();
    }
    public void tickEnd() {
        if (!enabled) return;
        if (!holding) snapshotWatch();
        else if (frozen()) {
            double[] motion=game.motion();
            if (speed(motion)>EPS && !onlyGravity(motion)) openVelocity();
            else { applyFrozen();snapshotMotion(); }
        } else snapshotMotion();
        publish();
    }
    private boolean onlyGravity(double[] motion) {
        if (distance(motion,lastMotion)<=EPS || Math.abs(motion[0]-lastMotion[0])>.02 || Math.abs(motion[2]-lastMotion[2])>.02) return false;
        return Math.abs(motion[1]-(lastMotion[1]-.08)*.98)<.02;
    }
    private void openVelocity() {
        pulseTicks=0;velocityTicks=velocityInputTicks=2;velocityFallStart=game.position()[1];groundStable=0;
    }
    /** True cancels the incoming packet. Called on the game thread, before vanilla changes motion. */
    public boolean receive(Object packet) {
        if (!enabled) return false;
        MovementAccess.Incoming incoming=game.incoming(packet); if (incoming==null) return false;
        if (incoming.kind==1) {
            beginHolding();velocityTicks=velocityInputTicks=pulseTicks=groundStable=0;pulseCooldown=interval();
            stored=new double[3];anchor=incoming.vector;game.motion(new double[3]);snapshotWatch();ticksSinceTeleport=0;
            // Vanilla must apply relative S08 coordinates to the original position exactly once.
            // The new anchor is enforced after vanilla, never before it resolves the relative flags.
        } else if (speed(incoming.vector)>EPS) { beginHolding();openVelocity(); }
        else if (incoming.kind==2 && holding) { publish();return true; }
        publish();return false;
    }
    public boolean send(Object manager,Object packet) {
        if (!suppressPosition) return false;
        int kind=game.movingPacket(packet);
        if (kind==2) game.sendLook(manager,packet);
        return kind!=0;
    }
    private void refreeze() { anchor=game.position();stored=game.motion();applyFrozen();snapshotMotion(); }
    private void applyFrozen() {
        if (anchor==null || !holding) return;
        game.motion(new double[3]);
        if (distance(game.position(),anchor)>.001) game.position(anchor);
    }
    private static double speed(double[] v) { return Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); }
    private static double distance(double[] a,double[] b) { return b==null ? 0 : speed(new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}); }
}
