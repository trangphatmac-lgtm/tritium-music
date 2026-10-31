package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.OpenB4Module;

public final class WTapModule extends OpenB4Module {
    private boolean pending, reset;
    private long hitTime, lastReset;

    public WTapModule(ExtendedCombatRuntime runtime) {
        super(runtime, "wTap");
    }

    public void preUpdate() {
        if (pending && runtime.now() - hitTime >= number("Delay until reset") - 20) {
            pending = false;
            lastReset = runtime.now();
            reset = true;
        }
    }

    public void attack(Object target) {
        if (pending || runtime.jumpPending() || !game.sprinting()) return;
        if (on("Players only")) {
            if (!game.kind(target, "entity.player.EntityPlayer")
                    || game.num(target, "hurtTime/field_70737_aN") > 3) return;
        } else if (!game.kind(target, "entity.EntityLivingBase")) return;
        if (game.num(target, "deathTime/field_70725_aQ") != 0
                || on("Disable while chasing") && game.away(target)
                || !runtime.chance(number("Chance"))) return;
        long now = runtime.now();
        if (now - lastReset < number("Delay between reset") - 20) return;
        hitTime = now;
        pending = true;
    }

    public void send(Object packet) {
        if (game.packet(packet, "C0BPacketEntityAction")
                && "START_SPRINTING"
                        .equals(String.valueOf(game.invoke(packet, "getAction/func_180764_b"))))
            lastReset = runtime.now() - 50;
    }

    public boolean reset() {
        return reset;
    }

    public boolean silent() {
        return on("Silent");
    }

    public void consume() {
        reset = false;
    }

    @Override
    public void disable(boolean worldChanged) {
        pending = reset = false;
    }
}
