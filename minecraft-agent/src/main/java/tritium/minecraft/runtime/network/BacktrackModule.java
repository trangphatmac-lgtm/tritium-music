package tritium.minecraft.runtime.network;

import tritium.minecraft.runtime.hook.CombatAccess;
import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.*;

import java.util.*;

/** Tick-bucket inbound hold and server-position tracking from OpenB4. */
public final class BacktrackModule extends OpenB4Module {
    private static final class Inbound {
        final Object handler, packet;

        Inbound(Object h, Object p) {
            handler = h;
            packet = p;
        }
    }

    private final TreeMap<Integer, List<Inbound>> held = new TreeMap<Integer, List<Inbound>>();
    private int tick, delayTicks;
    private boolean holding, ready, onlyMovement, show;
    private Object target, ghost;
    private double[] serverPos;
    private long lastFlush;

    public BacktrackModule(ExtendedCombatRuntime runtime) {
        super(runtime, "backtrack");
    }

    public Object target() {
        return target;
    }

    public boolean holding() {
        return holding;
    }

    @Override
    public void enable() {
        onlyMovement = on("Only movement");
        show = on("Show server position");
    }

    private double minRange() {
        return Math.min(number("Distance (min)"), number("Distance (max)"));
    }

    private double maxRange() {
        return Math.max(number("Distance (min)"), number("Distance (max)"));
    }

    private boolean canStart() {
        return !runtime.knockbackDelaying()
                && (number("Cooldown") == 0
                        || Math.abs(runtime.now() - lastFlush) > number("Cooldown") * 1000 - 20);
    }

    private void start() {
        delayTicks = Math.max(1, (int) number("Delay") / 50);
        serverPos = game.serverPosition(target);
        holding = true;
        if (on("Show server position")) makeGhost();
    }

    private boolean valid(Object entity) {
        return (!on("Only hittable") || game.num(entity, "hurtTime/field_70737_aN") <= 3)
                && (!on("Disable while chasing") || !game.away(entity));
    }

    private boolean targetValid() {
        return !game.singleplayer()
                && target != null
                && game.entity(game.entityId(target)) != null
                && game.num(target, "deathTime/field_70725_aQ") == 0
                && game.screen() == null
                && !game.capability("allowFlying/field_75101_c")
                && (!on("Disable adventure") || game.allowEdit())
                && (!on("Weapon only") || game.weapon());
    }

    private boolean passesThrough(Object p) {
        String n = p.getClass().getSimpleName();
        return Arrays.asList(
                        "S02PacketChat",
                        "S06PacketUpdateHealth",
                        "S16PacketEntityLook",
                        "S19PacketEntityStatus",
                        "S22PacketMultiBlockChange",
                        "S23PacketBlockChange",
                        "S29PacketSoundEffect",
                        "S2APacketParticles",
                        "S3BPacketScoreboardObjective",
                        "S3CPacketUpdateScore",
                        "S3DPacketDisplayScoreboard",
                        "S3EPacketTeams")
                .contains(n);
    }

    public boolean receive(Object handler, Object packet) {
        if (InboundReplay.active()) return false;
        if (holding) {
            if (game.packet(packet, "S08PacketPlayerPosLook")
                    || game.packet(packet, "S40PacketDisconnect")
                    || on("Flush on damage")
                            && game.packet(packet, "S12PacketEntityVelocity")
                            && game.velocityForPlayer(packet)) {
                flush(false);
                return false;
            }
        } else if (!ready) return false;
        if (serverPos == null) return false;
        double[] pos = serverPos.clone();
        boolean moves = false, teleport = false;
        if (game.packet(packet, "S15PacketEntityRelMove")
                || game.packet(packet, "S17PacketEntityLookMove")) {
            Object entity = game.invoke(packet, "getEntity/func_149065_a", game.world());
            if (entity != null
                    && target != null
                    && game.entityId(entity) == game.entityId(target)) {
                moves = true;
                pos[0] += game.result(packet, "func_149062_c") / 32;
                pos[1] += game.result(packet, "func_149061_d") / 32;
                pos[2] += game.result(packet, "func_149064_e") / 32;
            }
        } else if (game.packet(packet, "S18PacketEntityTeleport")
                && target != null
                && (int) game.result(packet, "getEntityId/func_149451_c")
                        == game.entityId(target)) {
            teleport = moves = true;
            pos =
                    new double[] {
                        game.result(packet, "getX/func_149449_d") / 32,
                        game.result(packet, "getY/func_149448_e") / 32,
                        game.result(packet, "getZ/func_149446_f") / 32
                    };
        }
        if (moves) {
            boolean flush = true;
            double[] nextBox = game.offsetBox(target, pos);
            double max = maxRange();
            if (game.distanceSq(pos) <= Math.pow(max + 1.2, 2)
                    && game.rayDistanceSq(target, nextBox, minRange()) < 0) {
                double newDist = game.rayDistanceSq(target, nextBox, max);
                if (newDist >= 0) {
                    double oldDist =
                            game.rayDistanceSq(target, game.offsetBox(target, serverPos), max + 2);
                    if (oldDist >= 0
                            && newDist > oldDist
                            && (!teleport || newDist - oldDist < 64)) {
                        if (!holding && canStart()) start();
                        serverPos = pos;
                        if (ghost != null)
                            game.invoke(
                                    ghost,
                                    "setPositionAndRotation2/func_180426_a",
                                    pos[0],
                                    pos[1],
                                    pos[2],
                                    0f,
                                    0f,
                                    3,
                                    teleport);
                        flush = false;
                    }
                }
            }
            if (holding && flush) {
                flush(false);
                return false;
            }
        }
        if (holding && !(on("Only movement") ? !moves : passesThrough(packet))) {
            int due = tick + delayTicks;
            List<Inbound> list = held.get(due);
            if (list == null) {
                list = new ArrayList<Inbound>();
                held.put(due, list);
            }
            list.add(new Inbound(handler, packet));
            return true;
        }
        return false;
    }

    public void flush(boolean discard) {
        List<Inbound> list = new ArrayList<Inbound>();
        for (List<Inbound> bucket : held.values()) list.addAll(bucket);
        held.clear();
        ready = holding = false;
        ghost = target = null;
        serverPos = null;
        tick = 0;
        lastFlush = runtime.now();
        if (!discard) process(list);
    }

    private void process(List<Inbound> list) {
        for (Inbound p : list) runtime.replay(id, p.handler, p.packet);
    }

    private Object findTarget() {
        Object aim = runtime.aimTarget();
        if (aim != null && valid(aim)) return aim;
        double best = 999, half = number("FOV") / 2;
        Object found = null;
        for (CombatAccess.Target t : game.targets()) {
            if (!t.player
                    || !valid(t.entity)
                    || game.ignored(t.entity)
                    || t.distanceSq > Math.pow(maxRange() + 2.5, 2)
                    || t.angle > half
                    || runtime.isFriend(t.entity)
                    || game.bot(t.entity)
                    || on("Ignore teammates") && t.teammate) continue;
            if (best == 999 || t.angle < best) {
                best = t.angle;
                found = t.entity;
            }
        }
        return found;
    }

    private void makeGhost() {
        ghost = game.ghost(target, false);
        game.invoke(ghost, "setPosition/func_70107_b", serverPos[0], serverPos[1], serverPos[2]);
        game.invoke(
                ghost,
                "setPositionAndRotation2/func_180426_a",
                serverPos[0],
                serverPos[1],
                serverPos[2],
                (float) game.num(target, "rotationYaw/field_70177_z"),
                (float) game.num(target, "rotationPitch/field_70125_A"),
                0,
                false);
    }

    public void update() {
        Object e = findTarget();
        if (holding) {
            if (e != target || !targetValid()) {
                flush(false);
                return;
            }
            if (ghost != null) {
                for (int i = 0; i < 3; i++) {
                    double p = game.coordinate(ghost, i);
                    game.field(
                            ghost,
                            new String[] {
                                        "prevPosX/field_70169_q",
                                        "prevPosY/field_70167_r",
                                        "prevPosZ/field_70166_s"
                                    }
                                    [i],
                            p);
                    game.field(
                            ghost,
                            new String[] {
                                        "lastTickPosX/field_70142_S",
                                        "lastTickPosY/field_70137_T",
                                        "lastTickPosZ/field_70136_U"
                                    }
                                    [i],
                            p);
                }
                game.invoke(ghost, "onLivingUpdate/func_70636_d");
            }
            List<Inbound> due = new ArrayList<Inbound>();
            Iterator<Map.Entry<Integer, List<Inbound>>> it = held.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, List<Inbound>> bucket = it.next();
                if (bucket.getKey() > tick) break;
                due.addAll(bucket.getValue());
                it.remove();
            }
            tick++;
            process(due);
        } else {
            if (e != null) {
                target = e;
                serverPos = game.serverPosition(e);
            }
            ready = target != null && targetValid();
        }
    }

    @Override
    public void settingsChanged() {
        if (onlyMovement != on("Only movement")) {
            onlyMovement = on("Only movement");
            flush(false);
        }
        if (show != on("Show server position")) {
            show = on("Show server position");
            if (!show) ghost = null;
            else if (holding) makeGhost();
        }
    }

    public void renderWorld(float partial) {
        if (ghost != null) runtime.renderer().box(ghost, partial, runtime.themeColor(), true);
    }

    @Override
    public void disable(boolean worldChanged) {
        flush(worldChanged);
    }
}
