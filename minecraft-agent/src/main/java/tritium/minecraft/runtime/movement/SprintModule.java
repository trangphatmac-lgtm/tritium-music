package tritium.minecraft.runtime.movement;

import tritium.minecraft.protocol.MovementSettings;
import tritium.minecraft.runtime.hook.MovementAccess;

/** Raven's held sprint key and next-tick backwards guard. */
public final class SprintModule {
    private final MovementAccess game;
    private boolean stopBackwards;
    public SprintModule(MovementAccess game) { this.game=game; }
    public void preUpdate() {
        preUpdate(false);
    }
    public boolean preUpdate(boolean wTapReset) {
        return preUpdate(wTapReset, false);
    }
    public boolean preUpdate(boolean wTapReset, boolean scaffoldBlocksSprint) {
        if (stopBackwards) { stopBackwards=false; game.stopSprint(); }
        else if (wTapReset) { game.stopSprint(); return true; }
        else if (scaffoldBlocksSprint) game.stopSprint();
        else game.sprintKey(true);
        return false;
    }
    public void preMotion(MovementSettings settings,float visibleYaw) {
        float serverYaw=game.yaw(), forward=game.forward();
        if (!settings.disableBackwards || (visibleYaw==serverYaw && forward<=0)) return;
        double radians=Math.toRadians(serverYaw-visibleYaw);
        if (Math.round(forward*Math.cos(radians)-game.strafe()*Math.sin(radians))<0) stopBackwards=true;
    }
    public void disable() { stopBackwards=false; game.sprintKey(game.physicalSprintKey()); }
}
