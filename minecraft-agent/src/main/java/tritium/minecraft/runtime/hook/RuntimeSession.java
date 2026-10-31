package tritium.minecraft.runtime.hook;

import com.google.gson.Gson;
import tritium.minecraft.protocol.*;
import tritium.minecraft.bridge.HookBridge;
import tritium.minecraft.runtime.lyric.*;
import tritium.minecraft.runtime.util.PacketBypass;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Isolated Java 8 runtime; no audio, player or desktop UI dependencies enter the game. */
public final class RuntimeSession implements IntPredicate {
    private final Gson json = new Gson();
    private final String token;
    private final int port;
    private final ClassLoader gameLoader;
    private final ArrayBlockingQueue<Wire.Frame> outgoing = new ArrayBlockingQueue<Wire.Frame>(128);
    private final AtomicReference<HudConfig> pendingConfig = new AtomicReference<HudConfig>();
    private final AtomicReference<HudArtwork> pendingArt = new AtomicReference<HudArtwork>();
    private final AtomicReference<String> pendingToast = new AtomicReference<String>();
    private final AtomicReference<Boolean> pendingEdit = new AtomicReference<Boolean>();
    // Radar is replaceable frame data, never a backlog of commands on the game thread.
    private final AtomicReference<RadarFrame> pendingRadar = new AtomicReference<RadarFrame>();
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private volatile PlaybackState state = new PlaybackState();
    private volatile PlaybackState.Track track = new PlaybackState.Track();
    private volatile long receivedAt, lastReceive = System.nanoTime();
    private volatile boolean stop, ready, installed;
    private volatile Socket socket;
    private volatile Wire wire;
    private GameAccess game;
    private HudConfig config;
    private volatile CombatRuntime combat;
    private volatile MovementRuntime movement;
    private volatile ExtendedCombatRuntime extended;
    private volatile PlayerRuntime playerModules;
    private volatile RenderRuntime renderModules;
    private volatile AntiBotRuntime antiBot;
    private RuntimeHooksAccess hooks;
    private float[] playerRotation;
    private float[] bridgeRotation;
    private boolean bridgeRotationPrepared;
    private Object bridgePlayer, bridgeWorld;
    private Float movementYaw;
    private HudInput input;
    private HudEditorScreen editor;
    private HudPainter painter;
    private GlHud gl;
    private WorldLyricsRenderer worldLyrics;
    private NativeFontCache nativeFontCache;
    private long lastPaint;
    private long toastPreviewUntil;
    private long lastHeartbeat;
    private boolean insideFrame;

    public RuntimeSession(String options, ClassLoader gameLoader) {
        String[] parts = options.split(":", -1);
        if (parts.length != 3 || Integer.parseInt(parts[0]) != Wire.VERSION || !parts[2].matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid HUD session options");
        port = Integer.parseInt(parts[1]); token = parts[2]; this.gameLoader = gameLoader;
    }

    public void start(final Runnable install, final Runnable uninstall) {
        Thread worker = new Thread(() -> run(install, uninstall), "Tritium HUD session");
        worker.setDaemon(true); worker.start();
    }
    private void run(Runnable install, Runnable uninstall) {
        ScheduledExecutorService io = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Tritium HUD IPC"); t.setDaemon(true); return t;
        });
        try {
            socket = new Socket(); socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setTcpNoDelay(true);
            wire = new Wire(socket.getInputStream(), socket.getOutputStream());
            Wire.Hello hello = new Wire.Hello(); hello.token = token;
            hello.javaVersion = System.getProperty("java.version");
            wire.send(Wire.HELLO, json.toJson(hello));
            io.scheduleAtFixedRate(() -> {
                try {
                    Wire.Frame f; while ((f = outgoing.poll()) != null) wire.send(f.type, f.body);
                    RadarFrame radar = pendingRadar.getAndSet(null);
                    if (radar != null && !stop) wire.send(Wire.RADAR, json.toJson(radar));
                    if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(1)) {
                        wire.send(Wire.HEARTBEAT, ""); lastHeartbeat = System.nanoTime();
                    }
                } catch (IOException e) { disconnect(); }
            }, 0, 16, TimeUnit.MILLISECONDS);
            io.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastReceive > TimeUnit.SECONDS.toNanos(5)) disconnect();
            }, 1, 1, TimeUnit.SECONDS);
            game = new GameAccess(gameLoader);
            hooks = new RuntimeHooksAccess(game.minecraft);
            input = new HudInput(event -> send(Wire.EVENT, json.toJson(event)));
            send(Wire.PROGRESS, "install");
            install.run(); installed = true;
            send(Wire.PROGRESS, "render");
            while (!stop) {
                Wire.Frame f = wire.read(); lastReceive = System.nanoTime();
                switch (f.type) {
                    case Wire.CONFIG:
                        HudConfig next = json.fromJson(f.text(), HudConfig.class); next.normalize(); pendingConfig.set(next); break;
                    case Wire.TRACK: track = json.fromJson(f.text(), PlaybackState.Track.class); break;
                    case Wire.STATE:
                        PlaybackState nextState = json.fromJson(f.text(), PlaybackState.class);
                        state = (nextState == null ? new PlaybackState() : nextState).normalize();
                        receivedAt = System.nanoTime(); break;
                    case Wire.ARTWORK:
                        BufferedImage art = f.body.length == 0 ? null : ImageIO.read(new ByteArrayInputStream(f.body));
                        if (art != null && (art.getWidth() > 2048 || art.getHeight() > 2048)) throw new IOException("Artwork too large");
                        pendingArt.set(new HudArtwork(art)); break;
                    case Wire.EDIT: pendingEdit.set(Boolean.valueOf(f.text())); break;
                    case Wire.PREVIEW: pendingToast.set("preview"); break;
                    case Wire.TOAST: pendingToast.set(f.text()); break;
                    case Wire.UNINJECT: stop = true; break;
                    case Wire.HEARTBEAT: break;
                    default: throw new IOException("Unknown IPC message " + f.type);
                }
            }
        } catch (Throwable failure) {
            send(Wire.ERROR, describe(failure));
        } finally {
            stop = true;
            try {
                synchronized (this) { if (gl == null && worldLyrics == null && combat == null && movement == null && extended == null && playerModules == null && renderModules == null && antiBot == null && editor == null) cleaned.countDown(); }
                if (!cleaned.await(5, TimeUnit.SECONDS)) {
                    send(Wire.ERROR, "HUD stopped; waiting for the game render thread to release textures");
                    while (!cleaned.await(1, TimeUnit.SECONDS)) if (!Display.isCreated()) { cleaned.countDown(); break; }
                }
                uninstall.run();
                if (wire != null) {
                    Wire.Frame f; while ((f = outgoing.poll()) != null) wire.send(f.type, f.body);
                    wire.send(Wire.STOPPED, "clean");
                }
            } catch (Throwable failure) {
                try { if (wire != null) wire.send(Wire.STOPPED, "HUD stopped; hook rollback incomplete: " + describe(failure)); }
                catch (IOException ignored) { }
            } finally {
                disconnect(); io.shutdownNow();
                painter = null; nativeFontCache = null; gl = null; worldLyrics = null; combat = null; movement = null; extended = null; playerModules = null; renderModules = null; antiBot = null; hooks = null; editor = null; input = null; game = null; config = null;
                track = null; state = null; pendingArt.set(null); pendingRadar.set(null); outgoing.clear();
            }
        }
    }

    @Override public synchronized boolean test(int kind) {
        try {
            if (stop) {
                if (kind == 5 || kind == 11) restoreMotion(true);
                if ((kind == 0 || kind == 1 || kind == 3 || kind == 4) && cleaned.getCount() != 0) {
                    cleanupRenderResources();
                }
                return false;
            }
            if (!installed || game == null) return false;
            if (kind == 1 || kind == 4 || kind == 8) configureModules();
            if (kind == 1) {
                hooks.bridgeTickStart(bridgeRotation != null);
                bridgeRotationPrepared = false; bridgeRotation = null;
            }
            if (kind == 7) {
                syncCombatDependencies();
                if (combat != null && combat.mouseOver()) return true;
                return extended != null && extended.mouseOver();
            }
            if (kind == 10) return mouse(Mouse.getEventButtonState(), Mouse.getEventButton());
            if (kind == 16) { if (extended != null) extended.updateEntityActionState(); return false; }
            if (kind == 6) {
                float visibleYaw = movement == null ? 0 : movement.visibleYaw();
                if (combat != null) combat.preMotion();
                hooks.beginRotation(playerRotation);
                if (extended != null) extended.preMotion();
                hooks.rememberRotation();
                if (movement != null) movement.preMotion(visibleYaw);
            } else if (kind == 5 || kind == 11) {
                restoreMotion(kind == 11);
            } else if (kind == 8) {
                if (antiBot != null) antiBot.preUpdate();
                prepareBridgeRotation();
                if (combat != null) combat.preUpdate();
                if (playerModules != null) playerModules.preUpdate();
                syncCombatDependencies();
                if (extended != null) extended.rotationBase(hooks.lastYaw(), hooks.lastPitch());
                if (extended != null) extended.preUpdate();
                if (movement != null) movement.preUpdate();
                playerRotation = playerModules == null ? null : playerModules.rotations(hooks.lastYaw(), hooks.lastPitch());
                if (playerRotation == null) playerRotation = bridgeRotation;
                float[] aimRotation = combat == null ? null : combat.pendingRotation();
                float[] selected = playerRotation != null ? playerRotation : aimRotation;
                movementYaw = selected != null && (playerRotation == null || playerModules.movementFix()) ? selected[0] : null;
                if (extended != null) {
                    extended.movementYawFallback(movementYaw);
                    if (extended.hasRotation()) selected = new float[]{extended.rotationYaw(), hooks.lastPitch()};
                }
                hooks.selectRotation(selected);
            } else if (kind == 9) {
                if (movement != null) movement.tickEnd();
                if (extended != null) extended.packetQueueTick();
                if (combat != null) combat.tickEnd();
                if (extended != null) extended.tickEnd();
                if (playerModules != null) playerModules.tickEnd();
                if (renderModules != null) renderModules.tickEnd();
                if (antiBot != null) antiBot.tickEnd();
                publishModuleChanges();
            } else if (kind == 12) {
                if (movement != null) movement.afterPlayerUpdate();
            } else if (kind == 13) {
                if (movement != null) movement.postInput();
                if (playerModules != null) playerModules.postInputEarly();
                if (extended != null) extended.postInput();
                if (playerModules != null) playerModules.postInput();
                if (movementYaw != null && (extended == null || !extended.hasRotation())) hooks.fixMovement(movementYaw);
            } else if (kind == 4) {
                if (combat != null) combat.renderStart();
            } else if (kind == 0) {
                if (combat != null) combat.renderEnd();
                if (renderModules != null) { renderModules.editor(input.editing); renderModules.renderOverlay(HookBridge.partialTicks); }
                if (extended != null) extended.renderHud();
                if (playerModules != null) playerModules.renderHud();
            } else if (kind == 3) {
                hooks.bridgeRenderArms(bridgeRotation != null);
                if (renderModules != null) renderModules.renderWorld(HookBridge.partialTicks);
                if (extended != null) extended.renderWorld(HookBridge.partialTicks);
                if (antiBot != null) antiBot.renderWorld(HookBridge.partialTicks);
                if (playerModules != null) playerModules.renderWorld(HookBridge.partialTicks);
            }
            if (kind == 0 && !insideFrame) {
                insideFrame = true;
                try { frame(); } finally { insideFrame = false; }
            } else if (kind == 3 && !insideFrame) {
                insideFrame = true;
                try { worldFrame(HookBridge.partialTicks); } finally { insideFrame = false; }
            } else if (kind == 1 && config != null) {
                boolean[] down = new boolean[256];
                if (Keyboard.isCreated()) for (int i = 1; i < 256; i++) down[i] = Keyboard.isKeyDown(i);
                if (input.keys(config, game.acceptsKeys() || editorOpen() && Display.isActive(), down)) stop = true;
                if (!stop) syncEditor();
                if (!stop) {
                    configureModules();
                    if (movement != null) movement.tickStart();
                    if (extended != null) extended.tickStart();
                }
            } else if (kind == HudEditorScreen.MOUSE && config != null && editorOpen() && Mouse.isCreated()) {
                return input.mouse(config, true, Mouse.getEventX() * game.width() / (double)game.pixelWidth(),
                        game.height() - Mouse.getEventY() * game.height() / (double)game.pixelHeight() - 1,
                        Mouse.getEventButton(), Mouse.getEventButtonState(), Mouse.getEventDWheel(), game.width(), game.height());
            } else if (kind == HudEditorScreen.KEYBOARD && config != null && editorOpen() && Keyboard.isCreated()) {
                if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
                    input.close(config); editor.hide();
                }
            } else if (kind == HudEditorScreen.CLOSED && config != null) {
                // Also covers replacement by another screen or leaving the world during a drag.
                input.close(config);
            }
        } catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; }
        return false;
    }

    public BiPredicate<Object,Object> packetCallback() { return this::packet; }
    public Consumer<Object> velocityCallback() { return this::velocity; }
    public Predicate<Object> blockingCallback() { return this::blocking; }

    private void configureModules() throws IllegalAccessException {
        HudConfig next = pendingConfig.getAndSet(null); if (next != null) config = next;
        if (config == null) return;
        if (config.hidden) {
            if (combat == null) { combat = new CombatRuntime(game.minecraft); combat.mouseGate(this::mouse); combat.scaffolding(() -> playerModules!=null && playerModules.scaffolding()); }
            if (movement == null) movement = new MovementRuntime(game.minecraft);
            if (extended == null) extended = new ExtendedCombatRuntime(game.minecraft);
            if (playerModules == null) playerModules = new PlayerRuntime(game.minecraft);
            if (renderModules == null) { renderModules = new RenderRuntime(game.minecraft); renderModules.radarFrames(pendingRadar::set); }
            if (antiBot == null) antiBot = new AntiBotRuntime(game.minecraft);
        }
        if (antiBot != null) antiBot.configure(config);
        if (combat != null) { combat.extended(extended); combat.antiBot(antiBot); combat.configure(config); }
        if (extended != null) { extended.antiBot(antiBot); extended.configure(config); }
        if (playerModules != null) playerModules.configure(config);
        if (renderModules != null) { renderModules.antiBot(antiBot); renderModules.configure(config); }
        if (movement != null) { movement.dependencies(extended, playerModules); movement.configure(config); }
        if (!config.hidden || !game.inWorld()) { playerRotation = null; movementYaw = null; hooks.selectRotation(null); }
        if (playerModules == null || !playerModules.enabled("bridgeAssist")
                || bridgePlayer != hooks.player() || bridgeWorld != hooks.world()) {
            bridgeRotation = null; bridgeRotationPrepared = false;
            bridgePlayer = hooks.player(); bridgeWorld = hooks.world();
        }
        syncCombatDependencies();
    }
    private void prepareBridgeRotation() {
        if (bridgeRotationPrepared) return;
        bridgeRotationPrepared = true;
        bridgeRotation = playerModules == null ? null : hooks.bridgeRotation(
                playerModules.clientRotations(hooks.lastYaw(), hooks.lastPitch()));
        // RavenBs keeps its helper inactive when quantization leaves the visible rotation unchanged.
        if (bridgeRotation != null && bridgeRotation[0] == hooks.yaw() && bridgeRotation[1] == hooks.pitch())
            bridgeRotation = null;
    }
    private void syncCombatDependencies() {
        if (extended == null) return;
        extended.autoClicking(combat != null && combat.autoClicking());
        extended.scaffolding(playerModules != null && playerModules.scaffolding());
        CombatAccess.Target target = combat == null ? null : combat.aimTarget();
        extended.aimTarget(target == null ? null : target.entity);
    }
    private boolean mouse(boolean down, int button) {
        syncCombatDependencies();
        if (extended != null) extended.mouseHigh(down, button);
        if (combat != null && combat.mouse(down, button)) return true;
        if (playerModules != null && playerModules.mouse(down, button)) return true;
        return extended != null && extended.mouse(down, button);
    }
    private void restoreMotion(boolean aborted) {
        if (extended != null) { if (aborted) extended.abortMotion(); else extended.postMotion(); }
        if (hooks != null) hooks.restoreRotation();
        if (combat != null) { if (aborted) combat.abortMotion(); else combat.postMotion(); }
    }
    private void publishModuleChanges() {
        if (extended == null) return;
        for (String id : extended.consumeDisabledIds()) {
            PlaybackState.Event event = new PlaybackState.Event(); event.action = "toggle"; event.id = id;
            event.enabled = config.find(id).enabled; send(Wire.EVENT, json.toJson(event));
        }
    }
    public Predicate<Object> receiveCallback() { return this::receive; }
    public BiPredicate<Object,Object> incomingCallback() { return this::incoming; }
    public synchronized boolean receive(Object packet) { return incoming(hooks == null ? null : hooks.handler(), packet); }
    public synchronized boolean incoming(final Object handler, final Object packet) {
        try {
            if (stop || hooks == null) return false;
            if (!hooks.gameThread()) {
                if (extended == null || !extended.needsReceive()) return false;
                hooks.execute(() -> { if (!stop && hooks.handler() == handler) hooks.process(handler, packet); });
                return true;
            }
            if (movement != null && movement.receive(packet)) return true;
            if (extended != null && extended.receive(handler, packet)) return true;
            return renderModules != null && renderModules.receive(packet);
        } catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return false; }
    }
    public boolean packet(Object manager, Object packet) {
        try {
            if (stop || PacketBypass.active()) return false;
            PlayerRuntime p = playerModules; if (p != null) p.observeSend(packet);
            RenderRuntime r = renderModules; if (r != null) r.send(packet);
            MovementRuntime m = movement; if (m != null && m.send(manager, packet)) return true;
            if (p != null && p.send(packet)) return true;
            ExtendedCombatRuntime e = extended; if (e != null && e.sendBeforeLag(manager, packet)) return true;
            CombatRuntime c = combat; if (c != null && c.send(manager, packet)) return true;
            return e != null && e.sendLag(manager, packet);
        } catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return false; }
    }
    public BiFunction<Integer,Object[],Object> moduleCallback() { return this::module; }
    private synchronized Object module(Integer kind, Object[] args) {
        try {
            if (kind == 19) { if (hooks != null) hooks.endMouseOver(); return null; }
            if (stop || hooks == null) return kind == 8 ? args[0] : null;
            switch (kind) {
                case 0: return playerModules == null ? args[1] : playerModules.stopsOnEdge(args[0], (Boolean) args[1]);
                case 1:
                    if (!hooks.local(args[0])) return args[1];
                    if (extended != null && extended.hasRotation()) return extended.movementYaw();
                    return movementYaw != null ? movementYaw : args[1];
                case 2: if (extended != null && hooks.local(args[0])) extended.attack(args[1]); return null;
                case 3: return renderModules == null ? args[0] : renderModules.hurtCameraMultiplier();
                case 4: return renderModules == null || renderModules.cameraClip();
                case 5: if (renderModules != null) renderModules.playerPre(args[0]); return null;
                case 6: if (renderModules != null) renderModules.playerPost(args[0]); return null;
                case 7: return renderModules != null && renderModules.suppressName(args[0], (Double) args[1], (Double) args[2], (Double) args[3]);
                case 8: return playerModules == null ? args[0] : playerModules.itemToRender(args[0]);
                case 9: return playerModules == null ? args[0] : playerModules.animationProgress((Float) args[0]);
                case 10: if (playerModules != null && playerModules.spoofingItemAsFist()) hooks.hideHeldModel(args[0], args[1]); return null;
                case 11:
                    if (antiBot != null) antiBot.entityJoin(args[0]);
                    if (renderModules != null) renderModules.entityJoin(args[0]);
                    return null;
                case 12: return hooks.headPitch(args[0], (Float) args[1], (Float) args[2]);
                case 13: if (playerModules != null) playerModules.preInput(args[0]); return null;
                case 14: return playerModules != null && playerModules.rightClick();
                case 15: return playerModules != null && playerModules.slotScroll(args[0], (Integer) args[1]);
                case 16: prepareBridgeRotation(); return null;
                case 17: if (antiBot != null) antiBot.chat(args[0], (Integer) args[1]); return null;
                case 18: hooks.beginMouseOver(bridgeRotation); return null;
                default: return null;
            }
        } catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return kind == 8 ? args[0] : null; }
    }
    public synchronized void velocity(Object packet) {
        try { if (!stop && combat != null) combat.velocity(packet); }
        catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; }
    }
    public boolean blocking(Object player) {
        try { return !stop && combat != null && combat.fakeBlocking(player); }
        catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return false; }
    }

    private void frame() throws Exception {
        HudConfig next = pendingConfig.getAndSet(null); if (next != null) config = next;
        if (config == null) return;
        HudConfig.Module worldModule = config.find("worldLyrics");
        if (worldLyrics != null && (!game.inWorld() || worldModule == null || !worldModule.enabled)) worldLyrics.reset();
        if (painter == null) { painter = new HudPainter(); gl = new GlHud(); }
        syncEditor();
        HudArtwork artwork = pendingArt.getAndSet(null); if (artwork != null) painter.artwork(artwork);
        String toast = pendingToast.getAndSet(null);
        if (toast != null) {
            boolean previewToast = toast.equals("preview");
            if (previewToast) toastPreviewUntil = System.currentTimeMillis() + (long)((config.find("musicToast").holdSeconds + 1.5) * 1000);
            painter.toast(previewToast ? (track.title.isEmpty() ? "Tritium Music" : track.title) : toast, System.currentTimeMillis());
        }
        int width = game.width(), height = game.height();
        boolean preview = input.editing && editorOpen();
        HudConfig.Module sprint = config.find("sprint");
        HudConfig.Module targetHud = config.find("targetHud");
        if (preview && config.hidden && renderModules != null && game.inWorld()) {
            double[] bounds = renderModules.hudBounds(width, height);
            targetHud.textWidth = bounds[2]; targetHud.textHeight = bounds[3];
        }
        if (config.hidden && sprint != null) sprint.textWidth = Math.max(1, game.nativeWidth(sprint.movement.formattedText()));
        input.tick(config, preview, Mouse.getX() * width / (double)game.pixelWidth(), height - Mouse.getY() * height / (double)game.pixelHeight() - 1, width, height);
        gl.begin(width, height);
        try {
            // READY is emitted only here, after an actual game frame has initialized the renderer and applied configuration.
            if (!ready) { ready = true; send(Wire.READY, game.client); }
            if (!game.inWorld()) return;
            if (preview) gl.editorBackdrop();
            boolean hudVisible = !game.hidden() || preview;
            long now = System.currentTimeMillis();
            boolean repaint = System.nanoTime() - lastPaint >= 33_000_000L || next != null || preview || gl.resized();
            PlaybackState s = state; PlaybackState.Track t = track;
            double position = s.positionMillis + (s.playing && !s.paused ? (System.nanoTime() - receivedAt) / 1_000_000.0 : 0);
            position = Math.max(0, Math.min(s.durationMillis > 0 ? s.durationMillis : Double.MAX_VALUE, position));
            for (HudConfig.Module m : config.modules) {
                if (!m.isHud() || !hudVisible) continue;
                if (!m.enabled && !preview && !(m.id.equals("musicToast") && now < toastPreviewUntil)) continue;
                if (m.id.equals("musicToast") && !preview && !painter.toastVisible(now, m.holdSeconds)) continue;
                double[] bounds = painter.bounds(m, s, width, height, now, preview);
                double[] textureBounds = HudPainter.textureBounds(m, bounds);
                if (repaint) gl.upload(m.id, painter.paintAtResolution(m, t, s, position, bounds[2], bounds[3],
                        gl.textureWidth(textureBounds[2]), gl.textureHeight(textureBounds[3]), now, preview, config.accent));
                gl.draw(m.id, textureBounds); if (preview) gl.border(bounds, config.accent);
            }
            if (config.hidden && sprint != null && (preview || sprint.enabled && sprint.movement.displayText && game.overlayVisible())) {
                double[] bounds = sprint.bounds(width, height);
                int color = sprint.movement.rainbow ? java.awt.Color.HSBtoRGB((now % 7500L) / 7500f, .75f, 1f) : -1;
                if (nativeFontCache == null) nativeFontCache = new NativeFontCache(gameLoader);
                try (NativeFontCache.Snapshot saved = nativeFontCache.save()) {
                    nativeFontCache.prepare();
                    game.drawNative(sprint.movement.formattedText(), (float)bounds[0], (float)bounds[1], color, true);
                }
                if (preview) gl.border(bounds, config.accent, false);
            }
            if (preview) {
                if (config.hidden && targetHud != null) gl.border(targetHud.bounds(width, height), config.accent, false);
                if (nativeFontCache == null) nativeFontCache = new NativeFontCache(gameLoader);
                try (NativeFontCache.Snapshot saved = nativeFontCache.save()) {
                    nativeFontCache.prepare();
                    game.drawNative(config.editorHint, Math.max(2, (width - game.nativeWidth(config.editorHint)) / 2), 6, -1, true);
                }
            }
            if (repaint) lastPaint = System.nanoTime();
        } finally { gl.end(); }
    }

    private boolean editorOpen() throws IllegalAccessException { return editor != null && editor.isOpen(); }

    private void syncEditor() throws Exception {
        Boolean requested = pendingEdit.getAndSet(null);
        if (requested != null) {
            input.finish(config);
            if (requested) input.edit(true); else input.close(config);
        }
        if (input.editing && !game.inWorld()) input.close(config);
        if (input.editing) {
            if (editor == null) editor = new HudEditorScreen(game, gameLoader, this);
            editor.open();
        } else if (editor != null) editor.hide();
        if (requested != null) {
            PlaybackState.Event event = new PlaybackState.Event(); event.action = "edit"; event.enabled = input.editing;
            send(Wire.EVENT, json.toJson(event));
        }
    }

    private void worldFrame(float partialTicks) throws Exception {
        HudConfig next = pendingConfig.getAndSet(null);
        if (next != null) config = next;
        if (config == null) return;
        HudConfig.Module module = config.find("worldLyrics");
        if (module == null || !module.enabled || !game.inWorld()) {
            if (worldLyrics != null) worldLyrics.reset();
            return;
        }
        PlaybackState s = state;
        PlaybackState.Track t = track;
        if (t == null || s == null || t.id < 0 || t.id != s.trackId) {
            if (worldLyrics != null) worldLyrics.reset();
            return;
        }
        if (worldLyrics == null) worldLyrics = new WorldLyricsRenderer(game);
        double position = s.positionMillis + (s.playing && !s.paused ? (System.nanoTime() - receivedAt) / 1_000_000.0 : 0);
        position = Math.max(0, Math.min(s.durationMillis > 0 ? s.durationMillis : Double.MAX_VALUE, position));
        worldLyrics.render(t, position, s.playing && !s.paused, module, partialTicks);
    }

    private void cleanupRenderResources() {
        Throwable failure = null;
        try {
            for (Runnable cleanup : new Runnable[]{
                    () -> { if (hooks != null) hooks.clearMouseOver(); },
                    () -> restoreMotion(true),
                    () -> { if (extended != null) extended.close(); },
                    () -> { if (playerModules != null) playerModules.close(); },
                    () -> { if (renderModules != null) renderModules.close(); },
                    () -> { if (antiBot != null) antiBot.close(); },
                    () -> { if (combat != null) combat.close(); },
                    () -> { if (movement != null) movement.close(); },
                    () -> { if (input != null && config != null) input.finish(config); },
                    () -> { if (editor != null) editor.close(); },
                    () -> { if (worldLyrics != null) worldLyrics.close(); },
                    () -> { if (gl != null) gl.close(); },
                    () -> { if (painter != null) painter.close(); }}) {
                try { cleanup.run(); }
                catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            }
        } finally { cleaned.countDown(); }
        if (failure != null) throw new IllegalStateException("Renderer cleanup failed", failure);
    }
    private void send(int type, String value) {
        if (!outgoing.offer(new Wire.Frame(type, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) disconnect();
    }
    private void disconnect() { stop = true; try { if (socket != null) socket.close(); } catch (IOException ignored) { } }
    private static String describe(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
}
