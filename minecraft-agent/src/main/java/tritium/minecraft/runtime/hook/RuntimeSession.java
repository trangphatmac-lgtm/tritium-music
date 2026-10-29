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
                    if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(1)) {
                        wire.send(Wire.HEARTBEAT, ""); lastHeartbeat = System.nanoTime();
                    }
                } catch (IOException e) { disconnect(); }
            }, 0, 100, TimeUnit.MILLISECONDS);
            io.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastReceive > TimeUnit.SECONDS.toNanos(5)) disconnect();
            }, 1, 1, TimeUnit.SECONDS);
            game = new GameAccess(gameLoader);
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
                synchronized (this) { if (gl == null && worldLyrics == null && combat == null && movement == null && editor == null) cleaned.countDown(); }
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
                painter = null; nativeFontCache = null; gl = null; worldLyrics = null; combat = null; movement = null; editor = null; input = null; game = null; config = null;
                track = null; state = null; pendingArt.set(null); outgoing.clear();
            }
        }
    }

    @Override public synchronized boolean test(int kind) {
        try {
            if (stop) {
                if ((kind == 5 || kind == 11) && combat != null) combat.abortMotion();
                if ((kind == 0 || kind == 1 || kind == 3 || kind == 4) && cleaned.getCount() != 0) {
                    cleanupRenderResources();
                }
                return false;
            }
            if (!installed || game == null) return false;
            if (kind == 1 || kind == 4 || kind == 8) {
                HudConfig next = pendingConfig.getAndSet(null); if (next != null) config = next;
                if (combat == null && config != null && config.hidden) combat = new CombatRuntime(game.minecraft);
                if (combat != null && config != null) combat.configure(config);
                if (movement == null && config != null && config.hidden) movement = new MovementRuntime(game.minecraft);
                if (movement != null && config != null) movement.configure(config);
            }
            float visibleYaw = kind == 6 && movement != null ? movement.visibleYaw() : 0;
            if (combat != null) {
                if (kind == 0) combat.renderEnd();
                else if (kind == 4) combat.renderStart();
                else if (kind == 5) combat.postMotion();
                else if (kind == 6) combat.preMotion();
                else if (kind == 7) return combat.mouseOver();
                else if (kind == 8) combat.preUpdate();
                else if (kind == 9) combat.tickEnd();
                else if (kind == 10) return combat.mouse(true, Mouse.getEventButton());
                else if (kind == 11) combat.abortMotion();
            }
            if (movement != null) {
                if (kind == 6) movement.preMotion(visibleYaw);
                else if (kind == 8) movement.preUpdate();
                else if (kind == 9) movement.tickEnd();
                else if (kind == 12) movement.afterPlayerUpdate();
                else if (kind == 13) movement.postInput();
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
                if (combat != null) { if (stop) combat.close(); else combat.configure(config); }
                if (movement != null) {
                    if (stop) movement.close(); else { movement.configure(config); movement.tickStart(); }
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

    public Predicate<Object> receiveCallback() { return this::receive; }
    public synchronized boolean receive(Object packet) {
        try { return !stop && movement != null && movement.receive(packet); }
        catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return false; }
    }
    public boolean packet(Object manager, Object packet) {
        try {
            if (stop || PacketBypass.active()) return false;
            MovementRuntime m = movement; if (m != null && m.send(manager, packet)) return true;
            CombatRuntime c = combat; return c != null && c.send(manager, packet);
        }
        catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; return false; }
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
