package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.OpenB4Module;

import java.util.*;

/** Attack pacing, combo confirmations, critical timing and first-hit tracking from OpenB4. */
public final class HitSelectModule extends OpenB4Module {
    private int combo, lastTarget;
    private long lastAttack, damagedAt;
    private final Map<Integer, Long> firstHits = new HashMap<Integer, Long>();

    public HitSelectModule(ExtendedCombatRuntime runtime) {
        super(runtime, "hitSelect");
    }

    public void send(Object packet) {
        if (game.packet(packet, "C09PacketHeldItemChange")) {
            if (on("Reset combo on swap")) combo = 0;
        } else if (game.attackPacket(packet) && number("Only in combo") != 0) {
            Object e = game.packetEntity(packet);
            if (e != null) {
                lastTarget = game.entityId(e);
                lastAttack = runtime.now();
            }
        }
    }

    public void receive(Object packet) {
        if (number("Only in combo") == 0
                || !game.packet(packet, "S19PacketEntityStatus")
                || game.result(packet, "getOpCode/func_149160_c") != 2
                || Math.abs(runtime.now() - lastAttack) >= 750) return;
        Object e = game.invoke(packet, "getEntity/func_149161_a", game.world());
        if (e != null && game.entityId(e) == lastTarget) combo++;
    }

    public boolean mouse(boolean down, int button) {
        return down
                && button == 0
                && (!runtime.autoClicking() || on("AutoClicker"))
                && on("Manual")
                && hold(game.hitEntity());
    }

    public boolean hold(Object entity) {
        if (!game.kind(entity, "entity.EntityLivingBase")) return false;
        if (on("Disable on low health") && !game.creative() && game.health() <= 4) return false;
        if (number("Only in combo") != 0 && combo < (int) number("Only in combo")) return false;
        if (on("Only while jumping") && !game.keyDown("keyBindJump/field_74314_A")) return false;
        if (damagedAt != 0 && Math.abs(runtime.now() - damagedAt) < 170) return false;
        boolean hold =
                number("Pause") != 0
                        && game.num(entity, "hurtTime/field_70737_aN")
                                > 10 - (int) number("Pause") / 50;
        if (!hold)
            hold =
                    on("Only critical hits")
                            && !game.ground()
                            && game.num(game.player(), "fallDistance/field_70143_R") == 0
                            && !game.truth(game.player(), "isInWater/func_70090_H")
                            && game.field(game.player(), "ridingEntity/field_70154_o") == null
                            && !game.potion("blindness/field_76440_q")
                            && !game.truth(game.player(), "isOnLadder/func_70617_f_");
        if (hold && on("Force first hit")) {
            long now = runtime.now();
            Long first = firstHits.get(game.entityId(entity));
            if (first == null || now - first > 10000) hold = false;
            firstHits.put(game.entityId(entity), now);
        }
        if (hold && on("Fake swing")) game.restartSwing();
        return hold;
    }

    public void update() {
        if (combo > 0 && Math.abs(runtime.now() - lastAttack) > 1250) combo = 0;
    }

    public void knockback() {
        combo = 0;
        if (on("Reset on damage")) damagedAt = runtime.now();
    }

    @Override
    public void disable(boolean worldChanged) {
        combo = 0;
        damagedAt = 0;
        firstHits.clear();
    }
}
