package tritium.minecraft.runtime.util;

import tritium.minecraft.runtime.hook.AntiBotAccess;
import tritium.minecraft.runtime.world.AntiBotModule;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

/** OpenB4 BotCheck, with session-owned state instead of process-global entity references. */
public final class BotCheck {
    public static final String PREFIX = "&7[&dR&7]&r ";
    private final AntiBotAccess game;
    private final AntiBotModule module;
    private final AntiBotHypixel hypixel;
    private final Random random;
    public final HashMap<Object, Long> recentlySpawned = new HashMap<Object, Long>();
    private long lastWatchdogMessage;
    private int watchdogId;
    private boolean soundQueued;
    private static final String[] WATCHDOG = {
        "&cWatchdog just arrived. Staff is watching you RIGHT NOW!",
        "&cYo, Watchdog bot just rolled up. You're totally screwed, bro.",
        "&cWatchdog boutta spin da block on you. It's wraps fam.",
        "&cOh no, Watchdog just showed up. Time to say goodbye :/",
        "&cWatchdog's here and it's not looking good. RIP :(",
        "&cOh snap, Watchdog's here. Looks like the ban screen's coming up >_<",
        "&cYikes, Watchdog spotted you. You're in big trouble now!",
        "&cWatchdog's watching you closely. Better start praying!",
        "&cOh boy, Watchdog just entered the lobby. Things ain't looking good.",
        "&cWatchdog just said 'nice hacks' in chat. Pack it up, chief.",
        "&cBro, Watchdog just pulled up with receipts. It's GG for you."
    };

    public BotCheck(
            AntiBotAccess game, AntiBotModule module, AntiBotHypixel hypixel, Random random) {
        this.game = game;
        this.module = module;
        this.hypixel = hypixel;
        this.random = random;
    }

    public boolean aboveSpawn(Object entity) {
        return module.aboveSpawn(entity);
    }

    public boolean isBot(Object entity) {
        return isBot(entity, true);
    }

    public boolean isBot(Object entity, boolean useSpawnDelay) {
        if (!game.playerEntity(entity) || game.dead(entity)) return true;
        // Freecam camera/body are absent in this client; the original fake entity ID is retained.
        if (game.entityId(entity) == -170) return true;
        if (!module.enabled()) return false;
        if (useSpawnDelay
                && module.changed("Delay (spawn)")
                && !recentlySpawned.isEmpty()
                && recentlySpawned.containsKey(entity)
                && (!module.on("^ Disable in duels") || !hypixel.duelsGame())) return true;
        if (module.on("Tab list") && game.info(entity) == null) return true;
        if (!hypixel.onHypixel()) return false;
        String name = game.name(entity);
        if (name.isEmpty()) return true;
        float health = game.health(entity);
        if (health != 20f && name.length() >= 3 && name.charAt(0) == '§' && name.charAt(1) == 'c')
            return true;
        String display = game.display(entity);
        if (game.maxHurtTime(entity) == 0) {
            int n = display.length();
            if (health == 20f) {
                // Preserve the original direct charAt: malformed empty display names were not
                // special-cased.
                if (display.charAt(0) != '§') {
                    if (n == 10) return true;
                    if (n >= 4
                            && display.charAt(0) == 'c'
                            && display.charAt(1) == 'p'
                            && display.charAt(2) == '_') return true;
                    if (display.equals("Opalsmith")) return true;
                }
                if (n == 12 && game.sleeping(entity) && display.charAt(0) == '§') return true;
                if (n >= 7
                        && display.charAt(2) == '['
                        && display.charAt(3) == 'N'
                        && display.charAt(6) == ']') return true;
            } else if (n >= 3) {
                if (display.charAt(0) == '§') {
                    if (game.invisible(entity) && display.charAt(1) == 'c') return true;
                } else return true;
            }
        }
        Object info, team;
        if (module.on("Filter watchdog")
                && display.length() >= 3
                && display.charAt(0) == '§'
                && display.charAt(1) == 'c'
                && (info = game.info(entity)) != null
                && (team = game.networkTeam(info)) != null
                && game.teamName(team).isEmpty()) {
            long now = game.now();
            if (game.held(entity) != null && Math.abs(now - lastWatchdogMessage) >= 1000L) {
                lastWatchdogMessage = now;
                if (watchdogId == 0) {
                    soundQueued = game.inWorld();
                    print(WATCHDOG[random.nextInt(WATCHDOG.length)]);
                }
                watchdogId = game.entityId(entity);
            }
            return true;
        }
        return false;
    }

    public void resetWatchdog() {
        watchdogId = 0;
    }

    public void discardSound() {
        soundQueued = false;
    }

    public void flushSound() {
        if (soundQueued) {
            soundQueued = false;
            game.sound("note.pling", 1f, 1f);
        }
    }

    public void renderWatchdog(float partial) {
        if (watchdogId == 0) return;
        Object entity = game.entity(watchdogId);
        if (entity == null) {
            watchdogId = 0;
            return;
        }
        game.watchdogBox(entity, partial);
    }

    public void print(String message) {
        if (game.inWorld()) game.chat(color(PREFIX + message), null);
    }

    public static String color(String text) {
        return text.replace('&', '§');
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder(text.length());
        boolean skip = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skip) {
                skip = false;
                continue;
            }
            if (c == '§') {
                skip = true;
                continue;
            }
            if (c > '\u0014' && c < '\u007f') result.append(c);
        }
        return result.toString();
    }

    public Object lookingAt(double range) {
        Object player = game.player();
        Object hit =
                rayTrace(
                        null,
                        range,
                        game.coordinate(player, 0),
                        game.coordinate(player, 1),
                        game.coordinate(player, 2),
                        game.yaw(),
                        game.pitch());
        if (hit == null
                || !"ENTITY".equals(String.valueOf(game.field(hit, "typeOfHit/field_72313_a"))))
            return null;
        Object entity = game.field(hit, "entityHit/field_72308_g");
        return game.kind(entity, "entity.EntityLivingBase") ? entity : null;
    }

    public Object rayTraceBlocks(double distance) {
        Object player = game.player();
        Object eyes =
                game.make(
                        "util.Vec3",
                        game.coordinate(player, 0),
                        game.coordinate(player, 1)
                                + game.result(player, "getEyeHeight/func_70047_e"),
                        game.coordinate(player, 2));
        double[] look = look(game.yaw(), game.pitch());
        Object end =
                game.invoke(
                        eyes,
                        "addVector/func_72441_c",
                        look[0] * distance,
                        look[1] * distance,
                        look[2] * distance);
        return game.invoke(
                game.world(), "rayTraceBlocks/func_147447_a", eyes, end, false, false, false);
    }

    public double[] look(float yaw, float pitch) {
        yaw *= -((float) Math.PI / 180);
        pitch *= -((float) Math.PI / 180);
        float c =
                (float)
                        game.result(
                                game.clazz("util.MathHelper"),
                                "cos/func_76134_b",
                                yaw - (float) Math.PI);
        float s =
                (float)
                        game.result(
                                game.clazz("util.MathHelper"),
                                "sin/func_76126_a",
                                yaw - (float) Math.PI);
        float cp = -(float) game.result(game.clazz("util.MathHelper"), "cos/func_76134_b", pitch);
        float sp = (float) game.result(game.clazz("util.MathHelper"), "sin/func_76126_a", pitch);
        return new double[] {s * cp, sp, c * cp};
    }

    public Object rayTrace(
            Object only, double distance, double x, double y, double z, float yaw, float pitch) {
        Object hit = null, hitVec = null;
        Object eyes =
                game.make(
                        "util.Vec3",
                        x,
                        y + game.result(game.player(), "getEyeHeight/func_70047_e"),
                        z);
        double[] dir = look(yaw, pitch);
        Object end =
                game.invoke(
                        eyes,
                        "addVector/func_72441_c",
                        dir[0] * distance,
                        dir[1] * distance,
                        dir[2] * distance);
        Object view = game.invoke(game.minecraft(), "getRenderViewEntity/func_175606_aa");
        Object box = game.invoke(view, "getEntityBoundingBox/func_174813_aQ");
        box =
                game.invoke(
                        box,
                        "addCoord/func_72321_a",
                        dir[0] * distance,
                        dir[1] * distance,
                        dir[2] * distance);
        box = game.invoke(box, "expand/func_72314_b", 1d, 1d, 1d);
        List<?> entities =
                (List<?>)
                        game.invoke(
                                game.world(),
                                "getEntitiesWithinAABBExcludingEntity/func_72839_b",
                                view,
                                box);
        double best = distance;
        for (Object e : entities) {
            if (!game.truth(e, "canBeCollidedWith/func_70067_L") || only != null && e != only)
                continue;
            double border = game.result(e, "getCollisionBorderSize/func_70111_Y");
            Object bounds =
                    game.invoke(
                            game.invoke(e, "getEntityBoundingBox/func_174813_aQ"),
                            "expand/func_72314_b",
                            border,
                            border,
                            border);
            Object intercept = game.invoke(bounds, "calculateIntercept/func_72327_a", eyes, end);
            if (game.truth(bounds, "isVecInside/func_72318_a", eyes)) {
                if (!(best >= 0)) continue;
                hit = e;
                hitVec = intercept == null ? eyes : game.field(intercept, "hitVec/field_72307_f");
                best = 0;
                continue;
            }
            if (intercept == null) continue;
            Object vec = game.field(intercept, "hitVec/field_72307_f");
            double dist = game.result(eyes, "distanceTo/func_72438_d", vec);
            if (!(dist < best) && best != 0) continue;
            if (e == game.field(view, "ridingEntity/field_70154_o") && !game.canRiderInteract(e)) {
                if (best != 0) continue;
                hit = e;
                hitVec = vec;
                continue;
            }
            hit = e;
            hitVec = vec;
            best = dist;
        }
        return hit == null ? null : game.make("util.MovingObjectPosition", hit, hitVec);
    }
}
