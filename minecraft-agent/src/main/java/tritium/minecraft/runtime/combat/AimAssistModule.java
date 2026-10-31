package tritium.minecraft.runtime.combat;

import tritium.minecraft.protocol.CombatSettings;
import tritium.minecraft.runtime.hook.CombatAccess;
import tritium.minecraft.runtime.hook.CombatRuntime;
import java.util.Random;
import tritium.minecraft.runtime.util.CombatMath;

/** AimAssistModule steering and target selection adapted from the supplied Raven/OpenB4 module. */
public final class AimAssistModule {
    private final CombatRuntime runtime;
    private final CombatAccess game;
    private final Random random;
    private CombatAccess.Target target;
    private float[] silentRotation;
    private float lastYaw, lastPitch, savedYaw, savedPitch;
    private boolean initialized, restoring;
    public AimAssistModule(CombatRuntime runtime, CombatAccess game, Random random) { this.runtime=runtime; this.game=game; this.random=random; }
    private CombatSettings settings() { return runtime.settings("aimAssist"); }
    public CombatAccess.Target target() { return target; }
    public float[] pendingRotation() { return silentRotation == null ? null : silentRotation.clone(); }
    public void update() {
        if (!initialized) { lastYaw=game.yaw(); lastPitch=game.pitch(); initialized=true; }
        if (!settings().on("silentAim")) {
            silentRotation=null;
            float[] r=aim(); if (r != null) game.rotation(settings().on("rotateYaw") ? r[0] : game.yaw(), settings().on("rotatePitch") ? r[1] : game.pitch());
        }
    }
    public boolean mouseOver() {
        if (!settings().on("silentAim")) return false;
        if (!initialized) { lastYaw=game.yaw(); lastPitch=game.pitch(); initialized=true; }
        silentRotation=aim(); if (silentRotation==null) return false;
        game.mouseOver(target, silentRotation[0], silentRotation[1]); return true;
    }
    public void preMotion() {
        if (settings().on("silentAim") && silentRotation != null) {
            savedYaw=game.yaw(); savedPitch=game.pitch(); restoring=true;
            game.rotation(silentRotation[0], silentRotation[1]); silentRotation=null;
        }
    }
    public void postMotion() { lastYaw=game.yaw(); lastPitch=game.pitch(); restore(); }
    public void restore() { if (restoring) { restoring=false; if (game.inWorld()) game.rotation(savedYaw,savedPitch); } }
    public void reset() { restore(); target=null; silentRotation=null; initialized=false; }
    private float[] aim() {
        CombatSettings c=settings();
        if (!game.focused() || c.on("weaponOnly") && !game.weapon() || c.on("clickAim") && !runtime.clicking()
                || c.on("stopWhenBreaking") && game.allowEdit() && game.mouse(0) && (c.on("silentAim") ? game.breakingRay() : game.blockHit())) {
            target=null; return null;
        }
        target=null; double best=Double.POSITIVE_INFINITY, range=c.number("range");
        for (CombatAccess.Target e:game.targets()) {
            if (!c.on("aimInvis") && e.invisible || game.ignored(e.entity) || e.distanceSq>range*range || e.angle>c.number("fov")/2) continue;
            if (e.player && game.bot(e.entity)) continue;
            if (e.player ? c.on("ignoreTeammates") && e.teammate : !c.on("aimMobs") || !e.mob || e.teammate) continue;
            if (!c.on("aimThroughBlocks") && !game.visible(e) || e.angle>=best) continue;
            best=e.angle; target=e;
        }
        if (target==null || c.on("stopOnTarget") && game.hitEntity()==target.entity) return null;
        float[] to=game.aimPoint(target);
        float baseYaw=c.on("silentAim") ? lastYaw : game.yaw(), basePitch=c.on("silentAim") ? lastPitch : game.pitch();
        if (c.number("speed")<100) {
            float dy=CombatMath.wrap(to[0]-baseYaw), dp=to[1]-basePitch;
            double s=c.number("speed")/100, rate=.3+14.7*s*s, ay=Math.abs(dy), ap=Math.abs(dp);
            double near=Math.min(1,ay/Math.max(10,c.number("fov"))), step=rate*(.3+(5+random.nextInt(3))*near);
            double k=Math.max(1,Math.max(ay/Math.max(1e-6,step),ap/Math.max(1e-6,step)));
            to=new float[]{baseYaw+(float)Math.copySign(Math.min(ay,ay/k),dy),CombatMath.pitch(basePitch+(float)Math.copySign(Math.min(ap,ap/k),dp))};
        }
        // Shared Raven Settings default: Jitter yaw = 3 sensitivity steps.
        float[] fixed=CombatMath.sensitivity(to[0],to[1],lastYaw,lastPitch,game.sensitivity(),random.nextInt(7)-3);
        if (!c.on("rotateYaw")) fixed[0]=game.yaw();
        if (!c.on("rotatePitch")) fixed[1]=game.pitch();
        return fixed;
    }
}
