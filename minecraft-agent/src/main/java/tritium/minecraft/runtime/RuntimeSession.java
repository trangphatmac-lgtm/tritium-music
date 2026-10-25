package tritium.minecraft.runtime;

import com.google.gson.Gson;
import tritium.minecraft.protocol.*;
import tritium.minecraft.bridge.HookBridge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntPredicate;

/** Isolated, Java 8 runtime. No audio, player, desktop UI, or Minecraft GUI classes are loaded here. */
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
    private HudInput input;
    private HudPainter painter;
    private GlHud gl;
    private WorldLyricsRenderer worldLyrics;
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
                synchronized (this) { if (gl == null && worldLyrics == null) cleaned.countDown(); }
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
                painter = null; gl = null; worldLyrics = null; input = null; game = null; config = null;
                track = null; state = null; pendingArt.set(null); outgoing.clear();
            }
        }
    }

    @Override public synchronized boolean test(int kind) {
        try {
            if (stop) {
                if ((kind == 0 || kind == 3) && cleaned.getCount() != 0) {
                    cleanupRenderResources();
                }
                return false;
            }
            if (!installed || game == null) return false;
            if (kind == 0 && !insideFrame) {
                insideFrame = true;
                try { frame(); } finally { insideFrame = false; }
            } else if (kind == 3 && !insideFrame) {
                insideFrame = true;
                try { worldFrame(HookBridge.partialTicks); } finally { insideFrame = false; }
            } else if (kind == 1 && config != null) {
                boolean[] down = new boolean[256];
                if (Keyboard.isCreated()) for (int i = 1; i < 256; i++) down[i] = Keyboard.isKeyDown(i);
                if (input.keys(config, game.acceptsKeys(), down)) stop = true;
            } else if (kind == 2 && config != null && Mouse.isCreated()) {
                return input.mouse(config, game.chatOpen(), Mouse.getEventX() * game.width() / (double)game.pixelWidth(),
                        game.height() - Mouse.getEventY() * game.height() / (double)game.pixelHeight() - 1,
                        Mouse.getEventButton(), Mouse.getEventButtonState(), Mouse.getEventDWheel(), game.width(), game.height());
            }
        } catch (Throwable failure) { send(Wire.ERROR, describe(failure)); stop = true; }
        return false;
    }

    private void frame() throws Exception {
        HudConfig next = pendingConfig.getAndSet(null); if (next != null) config = next;
        if (config == null) return;
        HudConfig.Module worldModule = config.find("worldLyrics");
        if (worldLyrics != null && (!game.inWorld() || worldModule == null || !worldModule.enabled)) worldLyrics.reset();
        if (painter == null) { painter = new HudPainter(); gl = new GlHud(); }
        Boolean edit = pendingEdit.getAndSet(null); if (edit != null) { input.finish(config); input.edit(edit); }
        HudArtwork artwork = pendingArt.getAndSet(null); if (artwork != null) painter.artwork(artwork);
        String toast = pendingToast.getAndSet(null);
        if (toast != null) {
            boolean previewToast = toast.equals("preview");
            if (previewToast) toastPreviewUntil = System.currentTimeMillis() + (long)((config.find("musicToast").holdSeconds + 1.5) * 1000);
            painter.toast(previewToast ? (track.title.isEmpty() ? "Tritium Music" : track.title) : toast, System.currentTimeMillis());
        }
        int width = game.width(), height = game.height();
        boolean chat = game.chatOpen(), preview = input.editing && chat;
        input.tick(config, chat, Mouse.getX() * width / (double)game.pixelWidth(), height - Mouse.getY() * height / (double)game.pixelHeight() - 1, width, height);
        gl.begin(game.pixelWidth(), game.pixelHeight(), width, height);
        try {
            // READY is emitted only here, after an actual game frame has initialized the renderer and applied configuration.
            if (!ready) { ready = true; send(Wire.READY, game.client); }
            if (!game.inWorld() || (game.hidden() && !preview)) return;
            long now = System.currentTimeMillis();
            boolean repaint = System.nanoTime() - lastPaint >= 33_000_000L || next != null || preview;
            PlaybackState s = state; PlaybackState.Track t = track;
            double position = s.positionMillis + (s.playing && !s.paused ? (System.nanoTime() - receivedAt) / 1_000_000.0 : 0);
            position = Math.max(0, Math.min(s.durationMillis > 0 ? s.durationMillis : Double.MAX_VALUE, position));
            for (HudConfig.Module m : config.modules) {
                if ("worldLyrics".equals(m.id)) continue;
                if (!m.enabled && !preview && !(m.id.equals("musicToast") && now < toastPreviewUntil)) continue;
                if (m.id.equals("musicToast") && !preview && !painter.toastVisible(now, m.holdSeconds)) continue;
                double[] bounds = painter.bounds(m, s, width, height, now, preview);
                if (repaint) gl.upload(m.id, painter.paint(m, t, s, position, bounds[2], bounds[3], game.scale(), now, preview, config.accent));
                gl.draw(m.id, HudPainter.textureBounds(m, bounds)); if (preview) gl.border(bounds, config.accent);
            }
            if (repaint) lastPaint = System.nanoTime();
        } finally { gl.end(); }
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
                    () -> { if (input != null && config != null) input.finish(config); },
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
