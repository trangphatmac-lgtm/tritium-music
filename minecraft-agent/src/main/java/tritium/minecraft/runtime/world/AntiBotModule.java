package tritium.minecraft.runtime.world;

import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.AntiBotAccess;
import tritium.minecraft.runtime.util.AntiBotHypixel;
import tritium.minecraft.runtime.util.BotCheck;

import java.util.Random;

/** Original AntiBot settings, joins, respawn protection and hover diagnostics. */
public final class AntiBotModule {
    private final AntiBotAccess game;
    private final BotCheck bots;
    private OpenB4Settings settings;
    private boolean enabled;
    private long respawnTime;

    public AntiBotModule(AntiBotAccess game, AntiBotHypixel hypixel, Random random) {
        this.game = game;
        bots = new BotCheck(game, this, hypixel, random);
    }

    public BotCheck bots() {
        return bots;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean on(String name) {
        return settings.on(name);
    }

    public double number(String name) {
        return settings.number(name);
    }

    public boolean changed(String name) {
        for (OpenB4Settings.Option option : OpenB4Settings.options("antiBot"))
            if (option.key.equals(name)) return number(name) != option.defaultValue;
        throw new IllegalArgumentException(name);
    }

    public void configure(OpenB4Settings next, boolean active) {
        settings = next;
        if (enabled && !active) disable();
        enabled = active;
    }

    public void disable() {
        enabled = false;
        bots.recentlySpawned.clear();
        bots.resetWatchdog();
        respawnTime = 0;
    }

    public void chat(String stripped) {
        if (enabled && changed("Delay (respawn)") && stripped.equals("You have respawned!"))
            respawnTime = game.now();
    }

    public void join(Object entity) {
        if (!enabled || entity == game.player() || !game.playerEntity(entity)) return;
        if (changed("Delay (spawn)")) bots.recentlySpawned.put(entity, game.now());
        if (on("Print world join")) printJoin(entity, false);
    }

    public void update() {
        if (enabled && !bots.recentlySpawned.isEmpty() && game.ticks() % 2 == 0) {
            final double before = (double) game.now() - number("Delay (spawn)") * 1000;
            bots.recentlySpawned.values().removeIf(time -> (double) time < before);
        }
    }

    public boolean aboveSpawn(Object entity) {
        return enabled && changed("Pit spawn") && game.coordinate(entity, 1) >= number("Pit spawn");
    }

    public boolean ignored(Object entity) {
        if (!enabled) return false;
        if (aboveSpawn(entity)) return true;
        double delay = number("Delay (respawn)") * 1000;
        return respawnTime != 0 && delay > 0 && (double) (game.now() - respawnTime) < delay;
    }

    public void printJoin(Object entity, boolean watchdog) {
        String message =
                "&7Entity &b"
                        + game.entityId(entity)
                        + " &7joined: &r"
                        + game.display(entity)
                        + (watchdog ? " &7(&cwatchdog&7)" : "");
        try {
            String hover =
                    "&ename: &r"
                            + game.name(entity)
                            + "\n&edisplay name: &r"
                            + game.display(entity)
                            + "\n&estripped: &r"
                            + BotCheck.strip(game.display(entity))
                            + "\n&euuid: &7"
                            + game.uuid(entity)
                            + "\n&ex: &7"
                            + game.coordinate(entity, 0)
                            + "\n&ey: &7"
                            + game.coordinate(entity, 1)
                            + "\n&ez: &7"
                            + game.coordinate(entity, 2)
                            + "\n&eyaw: &7"
                            + (float) game.num(entity, "rotationYaw/field_70177_z")
                            + "\n&epitch: &7"
                            + (float) game.num(entity, "rotationPitch/field_70125_A")
                            + "\n&ehealth: &7"
                            + game.health(entity)
                            + "\n&ehurt time: &7"
                            + (int) game.num(entity, "hurtTime/field_70737_aN")
                            + "/"
                            + game.maxHurtTime(entity)
                            + "\n&einvisible: &7"
                            + game.invisible(entity)
                            + "\n&eitem: &7"
                            + (game.held(entity) == null
                                    ? "null"
                                    : game.itemName(game.held(entity)))
                            + (watchdog ? "" : "\n&ebot: &7" + bots.isBot(entity, false));
            Object team = game.team(entity);
            hover += "\n&eteam: &7" + (team == null ? "null" : game.registeredTeamName(team));
            Object info = game.info(entity);
            if (info != null) {
                Object networkTeam = game.networkTeam(info);
                hover +=
                        "\n&etablist: &7true\n&enetwork team: &7"
                                + (networkTeam == null ? "null" : game.teamName(networkTeam))
                                + "\n&eping: &7"
                                + game.ping(info);
            } else hover += "\ntablist: false";
            game.chat(BotCheck.color(BotCheck.PREFIX + message), BotCheck.color(hover));
        } catch (Exception error) {
            bots.print(
                    "&bAntiBot&7: &r"
                            + message
                            + " &7(&c"
                            + error.getClass().getSimpleName()
                            + "&7)");
        }
    }
}
