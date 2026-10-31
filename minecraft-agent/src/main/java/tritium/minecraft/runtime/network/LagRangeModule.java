package tritium.minecraft.runtime.network;

import tritium.minecraft.runtime.hook.CombatAccess;
import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.*;

/** Proximity-triggered outbound lag, random pulses and chained FakeLag/Blink dispatch. */
public final class LagRangeModule extends OpenB4Module {
    private final OutboundPacketQueue delayed = new OutboundPacketQueue();
    private Object target, pending, ghost;
    private double pendingDistSq;
    private double[] ghostPos;
    private boolean caughtUp, flushNext, show;
    private long delay, startedAt, maxTime;
    private int randomPause, missing, movingAway;

    public LagRangeModule(ExtendedCombatRuntime runtime) {
        super(runtime, "lagRange");
    }

    public Object target() {
        return target;
    }

    public Object ghost() {
        return ghost;
    }

    @Override
    public void enable() {
        show = on("Show server position");
    }

    private void stop(boolean discard) {
        ghost = pending = target = null;
        movingAway = missing = 0;
        if (discard) delayed.clear();
        else release(true);
        if (on("Random lag")) randomPause = randomRange(6000, 20000);
    }

    private int randomRange(int min, int max) {
        return min + runtime.random().nextInt(max - min + 1);
    }

    private void start(Object player, double distSq) {
        if (player == null) {
            target = game.player();
            delay = (int) number("Packet delay (close)");
        } else {
            if (distSq > 42.25)
                delay =
                        (int)
                                (atMin("Packet delay (far)")
                                        ? number("Packet delay (close)")
                                        : number("Packet delay (far)"));
            else {
                if (atMin("Packet delay (close)")) return;
                delay = (int) number("Packet delay (close)");
            }
            target = player;
        }
        delay += randomRange(-12, 12);
        startedAt = runtime.now();
        maxTime = (long) (number("Max lag time") * 1000);
        int n = (int) (maxTime / 11);
        maxTime += randomRange(-n, n);
        if (on("Show server position")) {
            caughtUp = true;
            ghost = game.ghost(game.player(), true);
            ghostPos = game.position(ghost);
        }
    }

    public void pulse() {
        if (target != null) release(false);
    }

    private void release(boolean all) {
        for (OutboundPacketQueue.Entry p : delayed.drain(runtime.now(), all)) {
            if (ghost != null
                    && game.packet(p.packet, "C03PacketPlayer")
                    && game.truth(p.packet, "isMoving/func_149466_j"))
                ghostPos = game.packetPosition(p.packet);
            runtime.afterLagRange(p.manager, p.packet);
        }
    }

    public boolean send(Object manager, Object packet) {
        if (target == null) return false;
        boolean flush = false;
        if (game.packet(packet, "C01PacketChatMessage")) flushNext = true;
        else if (game.packet(packet, "C02PacketUseEntity")) {
            if (game.attackPacket(packet)
                    && (game.packetEntity(packet) != target
                            || game.num(target, "hurtTime/field_70737_aN") <= 2)) flushNext = true;
        } else if (game.packet(packet, "C07PacketPlayerDigging")) {
            String action = String.valueOf(game.invoke(packet, "getStatus/func_180762_c"));
            if ("START_DESTROY_BLOCK".equals(action)
                    || "STOP_DESTROY_BLOCK".equals(action)
                    || "DROP_ITEM".equals(action)
                    || "DROP_ALL_ITEMS".equals(action)) flushNext = true;
        } else if (game.placement(packet)) {
            if (on("On interact item") && !game.item(game.packetStack(packet), "item.ItemSword"))
                flushNext = true;
        } else if (flushNext && game.packet(packet, "C03PacketPlayer")) {
            flushNext = false;
            flush = true;
        }
        delayed.add(manager, packet, runtime.now() + delay);
        if (flush) stop(false);
        return true;
    }

    private boolean allowed(boolean lagging) {
        if (game.singleplayer()
                || runtime.scaffolding()
                || atMin("Packet delay (close)") && atMin("Packet delay (far)")
                || game.capability("allowFlying/field_75101_c")
                || on("Disable adventure") && !game.allowEdit()
                || on("Weapon only") && !game.weapon()) return false;
        Object stack = game.held();
        if (stack != null
                && (on("Holding any block") && game.item(stack, "item.ItemBlock")
                        || on("Holding consumable") && game.consumable(stack)
                        || on("Holding buckets") && game.item(stack, "item.ItemBucket")
                        || on("Holding bow") && game.itemConstant(stack, "bow/field_151031_f")
                        || on("Holding fireball")
                                && game.itemConstant(stack, "fire_charge/field_151059_bz")
                        || on("Holding fishing rod")
                                && game.itemConstant(stack, "fishing_rod/field_151112_aM")))
            return false;
        // OpenB4 links "Holding ladders" but deliberately has no corresponding branch here.
        if (!lagging
                && (!game.ground() && game.hurtTime() > 0 && game.motion()[1] > 0
                        || !game.moving()
                        || game.backwards())) return false;
        return true;
    }

    private boolean lookingAtNothing() {
        if (game.pitch() > 70 && game.blockItem()) return false;
        return !game.blockHit() || !game.interactable();
    }

    public void preUpdate() {
        if (ghost != null) {
            game.positionGhost(ghost, ghostPos);
            caughtUp = game.caughtUp(ghost);
        }
        double range = number("Start range") + game.potionLevel("moveSpeed/field_76424_c"),
                rangeSq = range * range,
                keepRangeSq = (range + 1) * (range + 1),
                randomRangeSq = (range + 5) * (range + 5);
        long now = runtime.now();
        if (target != null) {
            if (now - startedAt > maxTime || !allowed(true)) {
                stop(false);
                return;
            }
            if (on("Random lag") && target == game.player()) {
                if (!lookingAtNothing()) {
                    stop(false);
                    return;
                }
                for (CombatAccess.Target t : game.targets())
                    if (t.player
                            && !runtime.isFriend(t.entity)
                            && (!on("Ignore teammates") || !t.teammate)
                            && t.distanceSq < randomRangeSq) {
                        stop(false);
                        break;
                    }
                return;
            }
            if (game.entity(game.entityId(target)) != null) {
                missing = 0;
                if (game.distanceSq(target) > keepRangeSq) {
                    stop(false);
                    return;
                }
                double[] motion = game.motionTowards(target);
                movingAway = motion[0] >= .02 && motion[1] <= -.15 ? movingAway + 1 : 0;
                if (movingAway >= 4) stop(false);
            } else if (++missing >= 3) stop(false);
        } else if (allowed(false)) {
            if (pending != null) {
                start(pending, pendingDistSq);
                pending = null;
                return;
            }
            double nearest = -1;
            for (CombatAccess.Target t : game.targets()) {
                if (!t.player
                        || !on("Invisible players")
                                && game.truth(t.entity, "isInvisible/func_82150_aj")
                        || game.ignored(t.entity)
                        || on("Only enemies") && !runtime.isEnemy(t.entity)) continue;
                if (nearest == -1 || nearest > t.distanceSq) nearest = t.distanceSq;
                if (t.distanceSq > rangeSq
                        || runtime.isFriend(t.entity)
                        || game.bot(t.entity)
                        || on("Ignore teammates") && t.teammate) continue;
                double[] motion = game.motionTowards(t.entity);
                if (motion[0] < .02 || motion[1] < .15) continue;
                if (on("Block sword on lag")
                        && !atMin("Packet delay (close)")
                        && t.distanceSq <= 42.25
                        && game.sword()
                        && !game.blocking()
                        && !game.interactable()) {
                    game.invoke(
                            game.clazz("client.settings.KeyBinding"),
                            "onTick/func_74507_a",
                            game.key(1));
                    pending = t.entity;
                    pendingDistSq = t.distanceSq;
                } else start(t.entity, t.distanceSq);
                return;
            }
            if (target == null
                    && on("Random lag")
                    && nearest > randomRangeSq
                    && startedAt != 0
                    && now > startedAt + randomPause
                    && lookingAtNothing()) start(null, 0);
        }
    }

    @Override
    public void settingsChanged() {
        if (show != on("Show server position")) {
            show = on("Show server position");
            if (!show) ghost = null;
        }
    }

    public void renderWorld(float partial) {
        if (ghost != null && !runtime.blinkGhost() && (!caughtUp || game.moving()))
            runtime.renderer().box(ghost, partial, runtime.themeColor(), false);
    }

    @Override
    public void disable(boolean worldChanged) {
        stop(worldChanged);
        flushNext = false;
    }
}
