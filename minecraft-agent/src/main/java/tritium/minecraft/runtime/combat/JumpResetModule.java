package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.CombatMath;
import tritium.minecraft.runtime.util.InboundReplay;
import tritium.minecraft.runtime.util.OpenB4Module;

import java.util.*;

/** Includes OpenB4 u20 hard reset and aim jump, with ordered inbound replay at tick start. */
public final class JumpResetModule extends OpenB4Module {
    private boolean pending, explosion, otherAttack, aimAirborne, aimApplied;
    private double kbX, kbZ;
    private int aimLeft;
    private float aimYaw;
    private final List<Object> held = new ArrayList<Object>();
    private Object heldHandler;
    private long heldSince;

    public JumpResetModule(ExtendedCombatRuntime runtime) {
        super(runtime, "jumpReset");
    }

    public boolean pending() {
        return pending;
    }

    public boolean receive(Object handler, Object packet) {
        if (!InboundReplay.active() && !game.singleplayer()) {
            if (heldSince != 0) {
                if (game.packet(packet, "S08PacketPlayerPosLook")
                        || game.packet(packet, "S01PacketJoinGame")
                        || game.packet(packet, "S07PacketRespawn")) release(false);
                else {
                    held.add(packet);
                    return true;
                }
            } else if (on("Hard reset")
                    && game.packet(packet, "S12PacketEntityVelocity")
                    && game.velocityForPlayer(packet)
                    && game.knockback(packet)
                    && !game.ground()
                    && !game.burning()
                    && !badPotion()
                    && target() != null) {
                heldSince = runtime.now();
                heldHandler = handler;
                held.add(packet);
                return true;
            }
        }
        return false;
    }

    public void explosion(Object packet) {
        if (game.distanceSq(game.explosionPosition(packet)) <= 36) explosion = true;
    }

    public void tickStart() {
        otherAttack = false;
        if (aimLeft > 0) {
            if (game.ground() && aimAirborne) aimLeft = 0;
            else {
                aimAirborne |= !game.ground();
                --aimLeft;
            }
        }
        if (heldSince != 0
                && (!on("Hard reset")
                        || game.dead(game.player())
                        || game.ground()
                        || target() == null
                        || runtime.now() - heldSince >= (long) number("Hard reset max delay")))
            release(false);
    }

    private void release(boolean discard) {
        List<Object> list = new ArrayList<Object>(held);
        Object handler = heldHandler;
        held.clear();
        heldHandler = null;
        heldSince = 0;
        if (!discard && handler != null)
            for (Object packet : list) runtime.replay(id, handler, packet);
    }

    public void attack() {
        otherAttack = true;
    }

    public Float rotation(float yaw) {
        aimApplied = false;
        if (!on("Aim jump")) {
            aimLeft = 0;
            return null;
        }
        if (pending && game.ground() && kbX * kbX + kbZ * kbZ >= 1e-4) {
            aimYaw = (float) Math.toDegrees(Math.atan2(kbX, -kbZ));
            aimLeft = (int) number("Aim jump ticks");
            aimAirborne = false;
        }
        if (aimLeft <= 0 || otherAttack) return null;
        aimApplied = true;
        return yaw + CombatMath.wrap(aimYaw - yaw);
    }

    private boolean badPotion() {
        return game.potion("jump/field_76430_j")
                || game.potion("poison/field_76436_u")
                || game.potion("wither/field_82731_v");
    }

    private Object target() {
        double range = number("Hard reset distance");
        for (Object p : game.players())
            if (p != game.player()
                    && game.alive(p)
                    && game.entityId(p) != -170
                    && !game.bot(p)
                    && game.distanceSq(p) <= range * range) return p;
        return null;
    }

    public void knockback(Object packet) {
        if (explosion || on("Minimize vertical") && game.velocity(packet, 1) < 3000) return;
        kbX = game.velocity(packet, 0) / 8000d;
        kbZ = game.velocity(packet, 2) / 8000d;
        pending = true;
    }

    public void updateEntityActionState() {
        if (!pending) return;
        pending = false;
        int aim = aimLeft;
        aimLeft = 0;
        if (!game.ground()
                || !game.sprinting()
                || game.liquid()
                || game.truth(game.player(), "isRiding/func_70115_ae")
                || game.potion("jump/field_76430_j")
                || on("Only while targeting") && !runtime.targetMatches()
                || !runtime.chance(number("Chance"))) return;
        if (!helps(kbX, kbZ, runtime.movementYaw())) return;
        game.invoke(game.player(), "setJumping/func_70637_d", true);
        aimLeft = aim;
    }

    public void postInput() {
        if (aimLeft > 0 && aimApplied && !game.truth(game.player(), "isSneaking/func_70093_af")) {
            game.field(game.input(), "moveForward/field_78900_b", 1f);
            game.field(game.input(), "moveStrafe/field_78902_a", 0f);
        }
    }

    public static boolean helps(double x, double z, float yaw) {
        double kb = Math.sqrt(x * x + z * z);
        if (kb == 0) return false;
        double r = Math.toRadians(yaw), nx = x - Math.sin(r) * .2, nz = z + Math.cos(r) * .2;
        return (kb - Math.sqrt(nx * nx + nz * nz)) / kb >= .03;
    }

    public void update() {
        explosion = false;
    }

    @Override
    public void disable(boolean worldChanged) {
        explosion = pending = aimApplied = false;
        aimLeft = 0;
        release(worldChanged);
    }
}
