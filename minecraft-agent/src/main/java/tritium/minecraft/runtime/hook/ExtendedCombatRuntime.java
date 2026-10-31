package tritium.minecraft.runtime.hook;

import tritium.minecraft.protocol.*;
import tritium.minecraft.runtime.combat.*;
import tritium.minecraft.runtime.network.*;
import tritium.minecraft.runtime.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Session lifecycle, original callback ordering and packet arbitration for the ten OpenB4 modules.
 */
public final class ExtendedCombatRuntime implements AutoCloseable {
    private final ExtendedCombatAccess game;
    private final Random random;
    private final LongSupplier clock;
    private final Map<String, OpenB4Module> modules = new LinkedHashMap<String, OpenB4Module>();
    private final Set<String> enabled = new HashSet<String>();
    private final AntiKnockbackModule knockback;
    private final ClickAssistModule click;
    private final HitSelectModule hit;
    private final JumpResetModule jump;
    private final ReachModule reach;
    private final WTapModule wTap;
    private final BacktrackModule backtrack;
    private final BlinkModule blink;
    private final FakeLagModule fakeLag;
    private final LagRangeModule lagRange;
    private final NetworkGhostRender renderer;
    private final OpenB4ServerState serverState;
    private final FriendRegistry friends = new FriendRegistry();
    private final Set<String> disabledIds = new LinkedHashSet<String>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<Long>();
    private final ScheduledExecutorService timer;
    private boolean scaffolding, rotationBaseSet;
    private volatile boolean autoBlockQueue;
    private float baseYaw, basePitch, jumpPitch;
    private Float fallbackYaw;
    private HudConfig config;
    private Object world, player, aimTarget;
    private boolean closed, configChanged, autoClicking, rotating, restoring, sprintRestoring;
    private float jumpYaw, savedYaw, savedPitch;
    private Object rotationOwner;
    private boolean savedSprint;
    private long lastExplosion, lastSetback, lastFakePulse;
    private Throwable asynchronousFailure;

    public ExtendedCombatRuntime(Object minecraft) {
        this(new ExtendedCombatAccess(minecraft), new Random(), System::currentTimeMillis, true);
    }

    ExtendedCombatRuntime(
            ExtendedCombatAccess game, Random random, LongSupplier clock, boolean timers) {
        this.game = game;
        this.random = random;
        this.clock = clock;
        renderer = new NetworkGhostRender(game);
        serverState = new OpenB4ServerState(game);
        add(knockback = new AntiKnockbackModule(this));
        add(click = new ClickAssistModule(this));
        add(hit = new HitSelectModule(this));
        add(jump = new JumpResetModule(this));
        add(reach = new ReachModule(this));
        add(wTap = new WTapModule(this));
        add(backtrack = new BacktrackModule(this));
        add(blink = new BlinkModule(this));
        add(fakeLag = new FakeLagModule(this));
        add(lagRange = new LagRangeModule(this));
        if (timers) {
            timer =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                Thread t = new Thread(r, "Tritium OpenB4 packet release");
                                t.setDaemon(true);
                                return t;
                            });
            timer.scheduleAtFixedRate(
                    () -> {
                        synchronized (this) {
                            try {
                                networkPulse();
                            } catch (Throwable e) {
                                asynchronousFailure = e;
                            }
                        }
                    },
                    0,
                    5,
                    TimeUnit.MILLISECONDS);
        } else timer = null;
    }

    private void add(OpenB4Module module) {
        modules.put(module.id, module);
    }

    public void antiBot(AntiBotRuntime antiBot) { game.antiBot(antiBot); }

    public ExtendedCombatAccess game() {
        return game;
    }

    public Random random() {
        return random;
    }

    public long now() {
        return clock.getAsLong();
    }

    public boolean chance(double percent) {
        return percent == 100 || random.nextDouble() < percent / 100;
    }

    public OpenB4Settings settings(String id) {
        return config.find(id).openB4;
    }

    public NetworkGhostRender renderer() {
        return renderer;
    }

    public OpenB4ServerState serverState() {
        return serverState;
    }

    public int themeColor() {
        double blend = (Math.sin(now() / 1e8 * .5 * 400000) + 1) * .5;
        int n = (int) (blend * 255), other = 255 - n;
        return 0xff000000
                | ((253 * n + 210 * other >> 8) << 16)
                | ((253 * n + 225 * other >> 8) << 8)
                | (253 * n + 230 * other >> 8);
    }

    public synchronized boolean enabled(String id) {
        return enabled.contains(id);
    }

    public synchronized void configure(HudConfig config) {
        this.config = config;
        friends.configure(config.openB4Friends, config.openB4Enemies);
        Object nextWorld = game.world(), nextPlayer = game.player();
        if (world != nextWorld || player != nextPlayer) {
            reset(true);
            world = nextWorld;
            player = nextPlayer;
        }
        boolean active = !closed && world != null && player != null && config.hidden;
        for (OpenB4Module module : modules.values()) {
            HudConfig.Module state = config.find(module.id);
            boolean on = active && state != null && state.enabled,
                    was = enabled.contains(module.id);
            if (was && !on) {
                enabled.remove(module.id);
                module.disable(false);
            } else if (!was && on) {
                enabled.add(module.id);
                module.enable();
            } else if (on) module.settingsChanged();
        }
        if (!active) {
            rotating = false;
            restoreMotion();
            serverState.clear();
        }
    }

    public synchronized void disable(String id) {
        if (enabled.remove(id)) {
            modules.get(id).disable(false);
            if (config != null && config.find(id) != null) {
                config.find(id).enabled = false;
                configChanged = true;
                disabledIds.add(id);
            }
        }
    }

    public synchronized List<String> consumeDisabledIds() {
        List<String> ids = new ArrayList<String>(disabledIds);
        disabledIds.clear();
        configChanged = false;
        return ids;
    }

    /** Shared outbound tail: these methods own only AutoBlock's hold, independently of Blink. */
    public synchronized void openAutoBlockQueue() {
        if (!closed) autoBlockQueue = true;
    }

    // AutoBlock.send can run on Netty while inbound replay calls AutoBlock.knockback on the game
    // thread. A lock-free owner query avoids an ExtendedRuntime <-> AutoBlock lock inversion.
    public boolean autoBlockQueueActive() {
        return autoBlockQueue;
    }

    public synchronized void closeAutoBlockQueue() {
        autoBlockQueue = false;
        blink.flushIfIdle();
    }

    public synchronized void discardAutoBlockQueue() {
        autoBlockQueue = false;
        blink.discard();
    }

    public synchronized boolean needsReceive() {
        return enabled.contains("antiKnockback")
                || enabled.contains("backtrack")
                || enabled.contains("jumpReset")
                || enabled.contains("hitSelect")
                || enabled.contains("blink");
    }

    public synchronized boolean consumeConfigChanged() {
        boolean changed = configChanged;
        configChanged = false;
        return changed;
    }

    public synchronized void scaffolding(boolean active) {
        scaffolding = active;
    }

    public synchronized boolean scaffolding() {
        return scaffolding;
    }

    public synchronized void autoClicking(boolean active) {
        autoClicking = active;
    }

    public synchronized boolean autoClicking() {
        return autoClicking;
    }

    public synchronized void aimTarget(Object target) {
        aimTarget = target;
    }

    public synchronized Object aimTarget() {
        return aimTarget;
    }

    public boolean targetMatches() {
        return game.targetMatches(game.hitEntity(), false)
                || config != null
                        && config.find("aimAssist").combat.on("silentAim")
                        && game.targetMatches(aimTarget, false);
    }

    public boolean isFriend(Object entity) {
        return friends.isFriend(String.valueOf(game.invoke(entity, "getName/func_70005_c_")));
    }

    public boolean isEnemy(Object entity) {
        return friends.isEnemy(String.valueOf(game.invoke(entity, "getName/func_70005_c_")));
    }

    public synchronized int rightCps() {
        long cutoff = now() - 1000;
        while (!rightClicks.isEmpty() && rightClicks.peekFirst() < cutoff)
            rightClicks.removeFirst();
        return rightClicks.size();
    }

    public synchronized long lastExplosion() {
        return lastExplosion;
    }

    public synchronized long lastSetback() {
        return lastSetback;
    }

    private boolean active() {
        return !closed
                && config != null
                && config.hidden
                && player != null
                && world != null
                && world == game.world()
                && player == game.player();
    }

    private void checkFailure() {
        if (asynchronousFailure != null) {
            Throwable e = asynchronousFailure;
            asynchronousFailure = null;
            throw new IllegalStateException("OpenB4 delayed packet dispatch failed", e);
        }
    }

    public synchronized void tickStart() {
        checkFailure();
        if (active() && enabled("jumpReset")) jump.tickStart();
    }

    public synchronized void preUpdate() {
        if (!active()) return;
        serverState.refresh();
        if (enabled("lagRange")) lagRange.preUpdate();
        if (enabled("wTap")) wTap.preUpdate();
        Float yaw =
                enabled("jumpReset") ? jump.rotation(rotationBaseSet ? baseYaw : game.yaw()) : null;
        rotating = yaw != null;
        if (rotating) {
            jumpYaw = yaw;
            jumpPitch = rotationBaseSet ? basePitch : game.pitch();
        }
    }

    public synchronized void packetQueueTick() {
        if (active()) blink.tick();
    }

    public synchronized void tickEnd() {
        if (!active()) return;
        if (enabled("antiKnockback")) knockback.update();
        if (enabled("backtrack")) backtrack.update();
        if (enabled("hitSelect")) hit.update();
        if (enabled("jumpReset")) jump.update();
        if (enabled("fakeLag")) fakeLag.update();
    }

    public synchronized void postInput() {
        if (active() && enabled("jumpReset")) jump.postInput();
    }

    public synchronized void updateEntityActionState() {
        if (active() && enabled("jumpReset")) jump.updateEntityActionState();
    }

    public synchronized void rotationBase(float lastYaw, float lastPitch) {
        baseYaw = lastYaw;
        basePitch = lastPitch;
        rotationBaseSet = true;
    }

    public synchronized void movementYawFallback(Float yaw) {
        fallbackYaw = yaw;
    }

    public synchronized boolean hasRotation() {
        return active() && rotating;
    }

    public synchronized float rotationYaw() {
        return jumpYaw;
    }

    public synchronized float movementYaw() {
        return hasRotation() ? jumpYaw : fallbackYaw != null ? fallbackYaw : game.yaw();
    }

    public synchronized void preMotion() {
        if (!active()) return;
        if (rotating) {
            savedYaw = game.yaw();
            savedPitch = game.pitch();
            rotationOwner = player;
            restoring = true;
            game.rotation(jumpYaw, jumpPitch);
        }
        if (silentSprintReset()) {
            savedSprint = game.sprinting();
            sprintRestoring = true;
            rotationOwner = player;
            game.invoke(player, "setSprinting/func_70031_b", false);
            wTap.consume();
        }
    }

    public synchronized void postMotion() {
        restoreMotion();
    }

    public synchronized void abortMotion() {
        restoreMotion();
    }

    private void restoreMotion() {
        Object owner = rotationOwner;
        if (owner != null) {
            if (restoring) {
                game.field(owner, "rotationYaw/field_70177_z", savedYaw);
                game.field(owner, "rotationPitch/field_70125_A", savedPitch);
            }
            if (sprintRestoring) game.invoke(owner, "setSprinting/func_70031_b", savedSprint);
        }
        restoring = sprintRestoring = false;
        rotationOwner = null;
    }

    public synchronized boolean sprintReset() {
        return enabled("wTap") && wTap.reset() && !wTap.silent();
    }

    public synchronized boolean silentSprintReset() {
        return enabled("wTap") && wTap.reset() && wTap.silent();
    }

    public synchronized void consumeSprintReset() {
        wTap.consume();
    }

    public synchronized boolean jumpPending() {
        return enabled("jumpReset") && jump.pending();
    }

    public synchronized boolean attackDisabled() {
        return enabled("antiKnockback") && knockback.attackDisabled();
    }

    public synchronized boolean knockbackDelaying() {
        return enabled("antiKnockback") && knockback.delaying();
    }

    public synchronized void flushBacktrack() {
        if (backtrack.holding()) backtrack.flush(false);
    }

    public synchronized boolean holdClick(Object target, boolean synthetic) {
        return active()
                && enabled("hitSelect")
                && settings("hitSelect").on("Manual")
                && (!synthetic || settings("hitSelect").on("AutoClicker"))
                && hit.hold(target);
    }

    private boolean clickingForReach() {
        return autoClicking && config.find("autoClicker").combat.on("leftClick") && game.mouse(0);
    }

    public synchronized void mouseHigh(boolean down, int button) {
        if (active() && enabled("clickAssist")) click.mouse(down, button);
    }

    public synchronized boolean mouse(boolean down, int button) {
        if (!active()) return false;
        if (down && button == 1) rightClicks.addLast(now());
        if (enabled("reach") && down && button >= 0 && !clickingForReach()) reach.pick();
        return enabled("hitSelect") && hit.mouse(down, button);
    }

    public synchronized boolean mouseOver() {
        return active() && enabled("reach") && clickingForReach() && reach.pick();
    }

    public synchronized void attack(Object entity) {
        if (!active()) return;
        if (enabled("jumpReset")) jump.attack();
        if (enabled("wTap")) wTap.attack(entity);
    }

    public synchronized boolean sendBeforeLag(Object manager, Object packet) {
        if (!active()) return false;
        if (enabled("antiKnockback")) knockback.send(packet);
        if (enabled("wTap")) wTap.send(packet);
        if (enabled("hitSelect")) hit.send(packet);
        return false;
    }

    public synchronized boolean sendLag(Object manager, Object packet) {
        if (!active()) return false;
        if (enabled("lagRange") && lagRange.send(manager, packet)) return true;
        if (enabled("fakeLag") && fakeLag.send(manager, packet)) return true;
        return blink.hold(manager, packet);
    }

    public synchronized boolean send(Object manager, Object packet) {
        return sendBeforeLag(manager, packet) || sendLag(manager, packet);
    }

    public synchronized void afterLagRange(Object manager, Object packet) {
        if (enabled("fakeLag") && fakeLag.send(manager, packet)) return;
        afterFakeLag(manager, packet);
    }

    public synchronized void afterFakeLag(Object manager, Object packet) {
        if (!blink.hold(manager, packet)) game.sendDirect(manager, packet);
    }

    /** Called only after PacketThreadUtil's handoff. true cancels vanilla handling. */
    public synchronized boolean receive(Object handler, Object packet) {
        if (!active()) return false;
        if (game.packet(packet, "S27PacketExplosion")
                && game.distanceSq(game.explosionPosition(packet)) <= 25) lastExplosion = now();
        if (enabled("antiKnockback") && knockback.receive(handler, packet)) return true;
        if (enabled("backtrack") && backtrack.receive(handler, packet)) return true;
        if (enabled("jumpReset") && jump.receive(handler, packet)) return true;
        if (game.packet(packet, "S08PacketPlayerPosLook")
                && game.result(packet, "getPitch/func_148930_g") != 0) lastSetback = now();
        if (game.packet(packet, "S12PacketEntityVelocity") && game.velocityForPlayer(packet)) {
            if (blink.cancelsKnockback()) return true;
            if (game.knockback(packet)) {
                if (enabled("jumpReset")) jump.knockback(packet);
                if (enabled("hitSelect")) hit.knockback();
            }
        } else if (game.packet(packet, "S19PacketEntityStatus")) {
            if (enabled("hitSelect")) hit.receive(packet);
        } else if (game.packet(packet, "S27PacketExplosion")) {
            if (blink.cancelsKnockback()) return true;
            if (enabled("jumpReset")) jump.explosion(packet);
        }
        return false;
    }

    public void replay(String owner, Object handler, Object packet) {
        InboundReplay.run(owner, () -> game.process(packet, handler));
    }

    public synchronized void networkPulse() {
        if (!active()) return;
        if (enabled("lagRange")) lagRange.pulse();
        if (enabled("fakeLag") && now() - lastFakePulse >= 10) {
            lastFakePulse = now();
            fakeLag.pulse();
        }
    }

    public synchronized boolean blinkGhost() {
        return blink.ghost() != null;
    }

    public synchronized boolean lagRangeGhost() {
        return lagRange.ghost() != null;
    }

    public synchronized Object target() {
        return backtrack.target() != null ? backtrack.target() : lagRange.target();
    }

    public synchronized void renderWorld(float partialTicks) {
        checkFailure();
        if (!active()) return;
        if (enabled("backtrack")) backtrack.renderWorld(partialTicks);
        if (enabled("blink")) blink.renderWorld(partialTicks);
        if (enabled("lagRange")) lagRange.renderWorld(partialTicks);
        if (enabled("fakeLag")) fakeLag.renderWorld(partialTicks);
    }

    public synchronized void renderHud() {
        if (active()) blink.renderHud();
    }

    private void reset(boolean worldChanged) {
        restoreMotion();
        autoBlockQueue = false;
        enabled.clear();
        for (OpenB4Module module : modules.values())
            if (config != null) module.disable(worldChanged);
        rightClicks.clear();
        aimTarget = null;
        rotating = autoClicking = rotationBaseSet = false;
        fallbackYaw = null;
        lastSetback = lastExplosion = lastFakePulse = 0;
        serverState.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (timer != null) timer.shutdownNow();
        reset(world != game.world() || player != game.player());
    }
}
