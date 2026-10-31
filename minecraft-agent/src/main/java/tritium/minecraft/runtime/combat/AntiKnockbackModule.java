package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.InboundReplay;
import tritium.minecraft.runtime.util.OpenB4Module;

import java.util.*;

/** OpenB4 velocity scaling, conditional cancellation, fireball exemption and inbound delay. */
public final class AntiKnockbackModule extends OpenB4Module {
    private final TreeMap<Long, List<Inbound>> delayed = new TreeMap<Long, List<Inbound>>();
    private long tick, delayUntil, boostStage, cancelCount, explosionAt, fireballStage, fireballAt;
    private int delayTicks;
    private boolean startedMidair;

    private static final class Inbound {
        final Object packet, handler;

        Inbound(Object packet, Object handler) {
            this.packet = packet;
            this.handler = handler;
        }
    }

    public AntiKnockbackModule(ExtendedCombatRuntime runtime) {
        super(runtime, "antiKnockback");
    }

    @Override
    public void enable() {
        delayTicks = (int) number("Delay") / 50;
    }

    public boolean delaying() {
        return delayUntil != 0;
    }

    public boolean attackDisabled() {
        return delaying() && on("Disable attack while delay");
    }

    private boolean active() {
        return game.inWorld()
                && (!on("Only while moving") || game.moving())
                && (!on("Disable in lobby") || !runtime.serverState().lobby());
    }

    private boolean ownVelocity(Object p) {
        return game.packet(p, "S12PacketEntityVelocity")
                && game.velocityForPlayer(p)
                && game.velocity(p, 1) != -627
                && game.velocity(p, 1) < 4500
                && runtime.now() != runtime.lastExplosion();
    }

    private boolean anyVelocity() {
        for (List<Inbound> list : delayed.values())
            for (Inbound p : list) if (ownVelocity(p.packet)) return true;
        return false;
    }

    public boolean receive(Object handler, Object packet) {
        if (InboundReplay.ownedBy(id)) return false;
        if (InboundReplay.active()) return filter(packet);
        if (delayTicks != 0) {
            if (!game.singleplayer() && active()) {
                if (delayUntil == 0 && ownVelocity(packet)) {
                    boolean start =
                            !(on("Delay only midair") && game.ground()
                                    || on("Delay only targeting") && !runtime.targetMatches()
                                    || on("Delay if landing") && !game.landsWithin(delayTicks));
                    if (start) {
                        runtime.flushBacktrack();
                        delayUntil = tick;
                        startedMidair = !game.ground();
                    }
                }
                if (delayUntil != 0 && !passesThrough(packet)) {
                    if (!on("Delay simultaneously") && ownVelocity(packet) && anyVelocity()) {
                        List<Inbound> list = drain();
                        delayUntil = 0;
                        process(list);
                    } else {
                        long due = tick + delayTicks;
                        List<Inbound> list = delayed.get(due);
                        if (list == null) {
                            list = new ArrayList<Inbound>();
                            delayed.put(due, list);
                        }
                        list.add(new Inbound(packet, handler));
                        return true;
                    }
                }
            } else if (delayUntil != 0) {
                delayed.clear();
                delayUntil = 0;
            }
        }
        return filter(packet);
    }

    public static boolean passesThroughName(String name) {
        return Arrays.asList(
                        "S02PacketChat",
                        "S06PacketUpdateHealth",
                        "S14PacketEntity",
                        "S15PacketEntityRelMove",
                        "S16PacketEntityLook",
                        "S17PacketEntityLookMove",
                        "S19PacketEntityStatus",
                        "S22PacketMultiBlockChange",
                        "S23PacketBlockChange",
                        "S29PacketSoundEffect",
                        "S2APacketParticles",
                        "S3BPacketScoreboardObjective",
                        "S3CPacketUpdateScore",
                        "S3DPacketDisplayScoreboard",
                        "S3EPacketTeams")
                .contains(name);
    }

    private boolean passesThrough(Object packet) {
        return passesThroughName(packet.getClass().getSimpleName())
                || game.packet(packet, "S14PacketEntity");
    }

    private List<Inbound> drain() {
        List<Inbound> result = new ArrayList<Inbound>();
        for (List<Inbound> list : delayed.values()) result.addAll(list);
        delayed.clear();
        return result;
    }

    private void process(List<Inbound> list) {
        for (Inbound p : list) if (!filter(p.packet)) runtime.replay(id, p.handler, p.packet);
    }

    public void release(boolean all) {
        if (delayed.isEmpty()) return;
        if (!game.inWorld()) {
            delayed.clear();
            delayUntil = 0;
            return;
        }
        List<Inbound> list = new ArrayList<Inbound>();
        if (all) {
            list = drain();
            delayUntil = 0;
        } else {
            boolean velocity = false;
            Iterator<Map.Entry<Long, List<Inbound>>> it = delayed.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, List<Inbound>> e = it.next();
                if (e.getKey() > tick) break;
                for (Inbound p : e.getValue()) if (ownVelocity(p.packet)) velocity = true;
                list.addAll(e.getValue());
                it.remove();
            }
            if (velocity && !anyVelocity()) {
                list.addAll(drain());
                delayUntil = 0;
            }
        }
        process(list);
    }

    public void send(Object packet) {
        if (game.placement(packet)
                && (int) game.result(packet, "getPlacedBlockDirection/func_149568_f") != 255
                && game.itemConstant(game.packetStack(packet), "fire_charge/field_151059_bz")) {
            fireballAt = runtime.now();
            fireballStage = 1;
        }
    }

    public void update() {
        int n = (int) number("Delay") / 50;
        if (delayTicks != n && delayUntil != 0) release(true);
        delayTicks = n;
        if (fireballStage > 0 && Math.abs(runtime.now() - fireballAt) > 750)
            fireballStage = fireballAt = 0;
        if (boostStage > 0) {
            double[] m = game.motion();
            double speed = Math.sqrt(m[0] * m[0] + m[2] * m[2]);
            if (boostStage == 1)
                game.strafe(
                        Math.max(speed * number("Damage boost"), .35)
                                - runtime.random().nextDouble() * .001,
                        runtime.movementYaw());
            else if (boostStage == 2)
                game.strafe(.35 - runtime.random().nextDouble() * .001, runtime.movementYaw());
            boostStage = 0;
        }
        if (delayUntil != 0) release(on("Delay until ground") && startedMidair && game.ground());
        tick++;
    }

    private boolean canBoost() {
        return !game.ground()
                && game.moving()
                && !game.burning()
                && !game.liquid()
                && game.screen() == null
                && runtime.now() - runtime.lastSetback() >= 3000;
    }

    /**
     * true cancels. Horizontal zero updates Y directly, retaining the player's existing horizontal
     * motion.
     */
    public boolean filter(Object packet) {
        if (!active()) return false;
        if (game.packet(packet, "S12PacketEntityVelocity")) {
            if (!game.velocityForPlayer(packet)) return false;
            if (fireballStage == 2) {
                fireballStage = fireballAt = 0;
                return false;
            }
            if (explosionAt > 0) {
                boolean cancel = Math.abs(runtime.now() - explosionAt) < 10;
                explosionAt = 0;
                if (cancel) return true;
            }
            if (!game.ground()) {
                if (changed("Damage boost")
                        && (!on("Boost with LMB") || game.mouse(0))
                        && canBoost()) {
                    boostStage = 1;
                    return false;
                }
                if (on("Cancel off ground")) return true;
                double y = game.motion()[1];
                if (on("Cancel while falling")
                        && (y < -.55 || y < -.08 && game.heightAboveGround() > 5)) return true;
            } else if (on("Cancel ground")) return true;
            if (on("Cancel burning")
                    && game.burning()
                    && Math.abs(game.velocity(packet, 0)) < 2010
                    && Math.abs(game.velocity(packet, 2)) < 2010) return true;
            if (changed("Cancel interval")
                    && ++cancelCount >= number("Cancel interval")
                    && !game.ground()) {
                cancelCount = 0;
                return true;
            }
            double h = number("Horizontal"), v = number("Vertical");
            if (h == 0 && v == 0) return true;
            if (h == 0) {
                game.field(
                        game.player(),
                        "motionY/field_70181_x",
                        game.velocity(packet, 1) / 8000d * (v / 100));
                return true;
            }
            if (h != 100) {
                game.field(
                        packet,
                        "motionX/field_149415_b",
                        (int) (game.velocity(packet, 0) * h / 100));
                game.field(
                        packet,
                        "motionZ/field_149414_d",
                        (int) (game.velocity(packet, 2) * h / 100));
            }
            if (v != 100)
                game.field(
                        packet,
                        "motionY/field_149416_c",
                        (int) (game.velocity(packet, 1) * v / 100));
        } else if (game.packet(packet, "S27PacketExplosion")) {
            double d = game.distanceSq(game.explosionPosition(packet));
            if (fireballStage == 1 && d <= 6) {
                fireballAt = runtime.now();
                fireballStage = 2;
                return false;
            }
            if (on("Cancel explosion") && d <= 25) {
                explosionAt = runtime.now();
                return true;
            }
        }
        return false;
    }

    @Override
    public void disable(boolean worldChanged) {
        boostStage = cancelCount = explosionAt = fireballStage = fireballAt = 0;
        if (worldChanged) {
            delayed.clear();
            tick = 0;
        } else release(true);
        delayUntil = 0;
        startedMidair = false;
    }
}
