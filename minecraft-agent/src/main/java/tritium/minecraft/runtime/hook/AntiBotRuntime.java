package tritium.minecraft.runtime.hook;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.util.AntiBotHypixel;
import tritium.minecraft.runtime.util.BotCheck;
import tritium.minecraft.runtime.world.AntiBotModule;

import java.util.Random;

/** Shared, session-owned BotCheck manager. Event entry points are called on the game thread. */
public final class AntiBotRuntime implements AutoCloseable {
    private final AntiBotAccess game;
    private final AntiBotHypixel hypixel = new AntiBotHypixel();
    private final AntiBotModule module;
    private HudConfig config;
    private Object world, player;

    public AntiBotRuntime(Object minecraft) {
        this(new AntiBotAccess(minecraft), new Random());
    }

    AntiBotRuntime(AntiBotAccess game, Random random) {
        this.game = game;
        module = new AntiBotModule(game, hypixel, random);
    }

    public void configure(HudConfig next) {
        config = next;
        Object w = game.world(), p = game.player();
        if (world != w || player != p) {
            module.disable();
            module.bots().discardSound();
            hypixel.clear();
            world = w;
            player = p;
        }
        module.configure(
                next.find("antiBot").openB4,
                next.hidden && next.find("antiBot").enabled && w != null && p != null);
    }

    private boolean current() {
        if (config != null && (world != game.world() || player != game.player())) configure(config);
        return world != null && player != null;
    }

    public void preUpdate() {
        if (current()) hypixel.refresh(game);
    }

    public void tickEnd() {
        if (current()) {
            module.update();
            module.bots().flushSound();
        }
    }

    public void entityJoin(Object entity) {
        if (current()) module.join(entity);
    }

    public void chat(Object component, int type) {
        if (type != 2 && component != null && current())
            module.chat(BotCheck.strip(game.text(component)));
    }

    public void renderWorld(float partial) {
        if (current()) module.bots().renderWatchdog(partial);
    }

    public boolean bot(Object entity) {
        return bot(entity, true);
    }

    public boolean bot(Object entity, boolean useSpawnDelay) {
        current();
        return module.bots().isBot(entity, useSpawnDelay);
    }

    public boolean aboveSpawn(Object entity) {
        current();
        return module.aboveSpawn(entity);
    }

    public boolean ignored(Object entity) {
        current();
        return module.ignored(entity);
    }

    public BotCheck bots() {
        return module.bots();
    }

    public AntiBotHypixel.State serverState() {
        return hypixel.state();
    }

    @Override
    public void close() {
        module.disable();
        module.bots().discardSound();
        world = player = null;
        config = null;
        hypixel.clear();
    }
}
