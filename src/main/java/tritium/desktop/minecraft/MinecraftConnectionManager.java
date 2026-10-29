package tritium.desktop.minecraft;

import com.google.gson.Gson;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.spi.AttachProvider;
import tritium.desktop.*;
import tritium.desktop.hud.HudArtworkCache;
import tritium.i18n.I18n;
import tritium.minecraft.protocol.*;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.AudioPlayer;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owns one authenticated, loopback-only session. Every asynchronous result is checked against its session. */
public final class MinecraftConnectionManager implements AutoCloseable {
    public enum Stage { IDLE, FINDING, SELECTING, ATTACHING, LOADING, INSTALLING, RENDERING, SYNCING, READY, STOPPING, FAILED }
    public record Candidate(String pid, String name) { }
    public record Status(Stage stage, double progress, String detail, List<Candidate> candidates) { }
    private static final Gson JSON = new Gson();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> daemon(r, "Minecraft connection"));
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Minecraft heartbeat"));
    private final AtomicLong generation = new AtomicLong();
    private volatile Status status = new Status(Stage.IDLE, 0, "", List.of());
    private volatile Session session;
    private volatile boolean connected, editing;
    // Session-only UI state: restarting the application restores the settings controls.
    private volatile boolean settingsControlsHidden;

    public MinecraftConnectionManager() { monitor.scheduleAtFixedRate(this::watchdog, 1, 1, TimeUnit.SECONDS); }
    public Status status() { return status; }
    public boolean connected() { return connected; }
    public boolean editing() { return editing; }
    public boolean settingsControlsHidden() { return settingsControlsHidden; }
    public void openAndInject() {
        NCMScreen.getInstance().setCurrentPanel(new MinecraftPanel());
        if (!connected && !busy()) begin();
    }
    public boolean busy() { return status.stage != Stage.IDLE && status.stage != Stage.FAILED && status.stage != Stage.SELECTING && status.stage != Stage.READY; }

    public synchronized void begin() {
        if (connected || busy()) return;
        closeSession(session);
        DesktopAppState.preferences().minecraft().ensureInitialized(DesktopAppState.preferences());
        Session s = new Session(generation.incrementAndGet()); session = s;
        update(s, Stage.FINDING, .04, "");
        workers.execute(() -> {
            try {
                Class.forName("com.sun.tools.attach.VirtualMachine");
                if (AttachProvider.providers().isEmpty())
                    throw new IOException("当前 Java 运行时没有可用的 Attach Provider。请使用包含 jdk.attach 及其本机库的完整 JDK 21+ 或官方运行时包。");
                List<Candidate> candidates = discover();
                if (session != s) return;
                if (candidates.isEmpty()) throw new IOException("未找到 Minecraft。请启动 Forge / Lunar 1.8.9 并进入主菜单后重试。");
                if (candidates.size() == 1) select(candidates.getFirst());
                else status = new Status(Stage.SELECTING, .12, "", candidates);
            } catch (Throwable e) { fail(s, explain(e)); }
        });
    }
    public synchronized void select(Candidate candidate) {
        Session s = session;
        if (s == null || s.target != null) return;
        s.target = candidate; update(s, Stage.ATTACHING, .2, candidate.name + " · PID " + candidate.pid);
        workers.execute(() -> attach(s));
    }

    static List<Candidate> discover() throws IOException {
        Map<String, Candidate> result = new LinkedHashMap<>();
        try {
            for (var descriptor : VirtualMachine.list()) {
                String name = clientName(descriptor.displayName());
                if (name != null) result.put(descriptor.id(), new Candidate(descriptor.id(), name));
            }
        } catch (RuntimeException unavailable) { /* ProcessHandle/WMI can still discover JVMs without perf data. */ }
        ProcessHandle.allProcesses().filter(p -> p.pid() != ProcessHandle.current().pid()).forEach(p -> {
            ProcessHandle.Info info = p.info();
            if (!isJavaExecutable(info.command().orElse(""))) return;
            String name = clientName(String.join(" ", info.arguments().orElse(new String[0])));
            if (name != null) result.put(Long.toString(p.pid()), new Candidate(Long.toString(p.pid()), name));
        });
        if (com.sun.jna.Platform.isWindows()) {
            try {
                for (var process : WindowsJvmProcesses.list()) {
                    if (process.pid() == ProcessHandle.current().pid()) continue;
                    String name = clientName(String.join(" ", process.arguments()));
                    if (name != null) result.put(Long.toString(process.pid()), new Candidate(Long.toString(process.pid()), name));
                }
            } catch (IOException unavailable) {
                if (result.isEmpty()) throw unavailable;
            }
        }
        return List.copyOf(result.values());
    }
    static boolean isJavaExecutable(String command) {
        // ProcessHandle returns bin/java on Linux/macOS and java[w].exe on Windows.
        String path = command.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
    static String clientName(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        if (c.contains("com.moonsworth.lunar") || c.contains("lunar") && c.contains("--version")) return "Lunar 1.8.9";
        if (c.contains("net.minecraft.launchwrapper.launch") || c.contains("net.minecraft.client.main.main")) return "Minecraft / Forge";
        return null;
    }
    private void attach(Session s) {
        VirtualMachine vm = null;
        try {
            String[] arguments = ProcessHandle.of(Long.parseLong(s.target.pid)).map(p -> p.info().arguments().orElse(new String[0])).orElse(new String[0]);
            if (arguments.length == 0 && com.sun.jna.Platform.isWindows()) {
                try { arguments = WindowsJvmProcesses.arguments(Long.parseLong(s.target.pid)); }
                catch (IOException unavailable) { /* The Attach operation reports access/disabled errors itself. */ }
            }
            if (flagEnabled(arguments, "DisableAttachMechanism", false))
                throw new IOException("目标 JVM 使用 -XX:+DisableAttachMechanism，已禁用 Attach。");
            if (!flagEnabled(arguments, "EnableDynamicAgentLoading", true))
                throw new IOException("目标 JVM 使用 -XX:-EnableDynamicAgentLoading，已禁用动态 Agent。");
            Path agent = extractAgent();
            s.server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            s.server.setSoTimeout(30000);
            workers.execute(() -> accept(s));
            if (session != s) return;
            vm = VirtualMachine.attach(s.target.pid);
            if (session != s) return;
            update(s, Stage.LOADING, .35, s.target.name);
            AttachLoader.load(vm, agent.toAbsolutePath().toString(), Wire.VERSION + ":" + s.server.getLocalPort() + ":" + s.token);
            // The Attach transport ends here. The independent agent session stays alive until UnInject.
        } catch (Throwable e) { fail(s, explainAttach(e)); }
        finally { if (vm != null) try { vm.detach(); } catch (IOException ignored) { } }
    }
    private void accept(Session s) {
        try {
            while (session == s) {
                Socket client = s.server.accept(); client.setTcpNoDelay(true); client.setSoTimeout(3000);
                Wire candidate = new Wire(client.getInputStream(), client.getOutputStream());
                try {
                    Wire.Frame f = candidate.read();
                    Wire.Hello hello = f.type == Wire.HELLO ? JSON.fromJson(f.text(), Wire.Hello.class) : null;
                    if (hello == null || hello.version != Wire.VERSION || !s.token.equals(hello.token)) { client.close(); continue; }
                } catch (Exception badHello) { client.close(); continue; }
                if (session != s) { client.close(); return; }
                s.socket = client; s.wire = candidate; client.setSoTimeout(0); s.lastReceive = System.nanoTime();
                s.server.close(); workers.execute(() -> publish(s));
                receive(s); return;
            }
        } catch (Throwable e) { if (session == s) fail(s, "与 Minecraft 的通信中断。\n" + explain(e)); }
    }
    private void receive(Session s) throws IOException {
        while (session == s) {
            Wire.Frame f = s.wire.read(); s.lastReceive = System.nanoTime();
            switch (f.type) {
                case Wire.PROGRESS -> {
                    if (f.text().equals("install")) update(s, Stage.INSTALLING, .52, "");
                    if (f.text().equals("render")) update(s, Stage.RENDERING, .68, "");
                }
                case Wire.READY -> {
                    if (s.stopping || s.ready) break;
                    update(s, Stage.SYNCING, .92, f.text());
                    DesktopAppState.runOnMainThread(() -> {
                        if (session != s || s.stopping) return;
                        s.ready = true; connected = true;
                        DesktopAppState.hudManager().setMinecraftSuppressed(true);
                        update(s, Stage.READY, 1, s.target.name + " · PID " + s.target.pid);
                        NCMScreen.getInstance().refreshMinecraftNavigation();
                        if (!(NCMScreen.getInstance().getCurrentPanel() instanceof MinecraftPanel))
                            NCMScreen.getInstance().setCurrentPanel(new MinecraftPanel());
                    });
                }
                case Wire.EVENT -> {
                    PlaybackState.Event event = JSON.fromJson(f.text(), PlaybackState.Event.class);
                    DesktopAppState.runOnMainThread(() -> {
                        if (generation.get() != s.id) return;
                        if ("edit".equals(event.action)) {
                            if (connected && status.stage() == Stage.READY) editing = event.enabled;
                        }
                        else DesktopAppState.preferences().minecraft().apply(event);
                    });
                }
                case Wire.ERROR -> { s.error = f.text(); s.stopping = true; s.stopAt = System.nanoTime(); update(s, Stage.STOPPING, status.progress, s.error); }
                case Wire.STOPPED -> {
                    boolean clean = f.text().equals("clean");
                    // Also covers Uninject triggered by the in-game key binding.
                    if (clean && s.error.isEmpty()) settingsControlsHidden = true;
                    finish(s, clean && s.error.isEmpty() ? "" : clean ? s.error : "显示已停止，Hook 撤销未完成。" + f.text()); return;
                }
                case Wire.HEARTBEAT -> { }
                default -> throw new IOException("Unexpected agent message: " + f.type);
            }
        }
    }

    private void publish(Session s) {
        String lastConfig = "", lastTrack = "";
        BufferedImage lastArtwork = null; long lastArtworkId = Long.MIN_VALUE, metaAt = 0, heartbeatAt = 0;
        try {
            while (session == s && !Thread.currentThread().isInterrupted()) {
                long start = System.nanoTime();
                Wire.Frame command; while ((command = s.commands.poll()) != null) s.wire.send(command.type, command.body);
                if (start - heartbeatAt >= TimeUnit.SECONDS.toNanos(1)) { s.wire.send(Wire.HEARTBEAT, ""); heartbeatAt = start; }
                if (!s.stopping) {
                    HudConfig config = DesktopAppState.preferences().minecraft().snapshot(); config.accent = AppTheme.accent();
                    config.hidden = DesktopAppState.preferences().minecrafthidden().getValue();
                    config.editorHint = I18n.text("minecraft.editorHint");
                    String encoded = JSON.toJson(config);
                    if (!encoded.equals(lastConfig)) { s.wire.send(Wire.CONFIG, encoded); lastConfig = encoded; }
                    PlaybackState state = captureState(config.find("musicSpectrum").absoluteVolume); state.sequence = ++s.sequence;
                    if (start - metaAt > TimeUnit.MILLISECONDS.toNanos(300) || state.trackId != lastArtworkId) {
                        String track = JSON.toJson(captureTrack());
                        if (!track.equals(lastTrack)) { s.wire.send(Wire.TRACK, track); lastTrack = track; }
                        BufferedImage art = HudArtworkCache.get(state.trackId, HudArtworkCache.ArtworkType.COVER);
                        if (art != lastArtwork || state.trackId != lastArtworkId) {
                            s.wire.send(Wire.ARTWORK, encodeArtwork(art)); lastArtwork = art; lastArtworkId = state.trackId;
                        }
                        metaAt = start;
                    }
                    // Sample after each send; there is never a queued backlog of old playback/spectrum states.
                    s.wire.send(Wire.STATE, JSON.toJson(state));
                }
                long wait = 33_333_333L - (System.nanoTime() - start);
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            }
        } catch (Throwable failure) { if (session == s) fail(s, "Minecraft 播放数据同步失败。\n" + explain(failure)); }
    }

    static PlaybackState captureState() {
        return captureState(DesktopAppState.preferences().minecraft().snapshot().find("musicSpectrum").absoluteVolume);
    }
    static PlaybackState captureState(boolean absoluteVolume) {
        PlaybackState s = new PlaybackState(); var music = CloudMusic.currentlyPlaying; var player = CloudMusic.player;
        s.trackId = music == null ? -1 : music.getId();
        try { if (player != null && music != null) {
            s.playing = !player.isFinished(); s.paused = player.isPausing();
            s.positionMillis = player.getCurrentTimeMillis(); s.durationMillis = player.getTotalTimeMillis();
        }} catch (RuntimeException ignored) { s.playing = false; }
        var download = DesktopAppState.downloadStatus(); s.downloading = download.downloading;
        s.downloadProgress = download.downloadProgress; s.downloadSpeed = download.downloadSpeed;
        s.volume = DesktopAppState.preferences().volume().getValue();
        var frame = AudioPlayer.spectrumFrame;
        s.spectrum = frame == null ? AudioPlayer.bandValues : absoluteVolume ? frame.absolute : frame.relative;
        return s.normalize();
    }
    static PlaybackState.Track captureTrack() {
        PlaybackState.Track t = new PlaybackState.Track(); var music = CloudMusic.currentlyPlaying;
        if (music != null) { t.id = music.getId(); t.timeline = t.id; t.title = music.getName(); t.artist = music.getArtistsName(); }
        synchronized (CloudMusic.lyrics) {
            for (var source : CloudMusic.lyrics) {
                PlaybackState.Line line = new PlaybackState.Line(); line.timestamp = source.timestamp; line.duration = source.duration;
                line.text = source.getLyric(); line.translation = source.translationText; line.romanization = source.romanizationText;
                line.breakLine = source.isBreakLine;
                line.placeholder = source.isPlaceholder();
                for (var word : source.words) { PlaybackState.Word w = new PlaybackState.Word(); w.text = word.word; w.timestamp = word.timestamp; w.duration = word.duration; line.words.add(w); }
                t.lyrics.add(line);
            }
        }
        return t;
    }
    private static byte[] encodeArtwork(BufferedImage source) throws IOException {
        if (source == null) return new byte[0];
        double ratio = Math.min(1, 512.0 / Math.max(source.getWidth(), source.getHeight()));
        BufferedImage resized = new BufferedImage(Math.max(1, (int)(source.getWidth() * ratio)), Math.max(1, (int)(source.getHeight() * ratio)), BufferedImage.TYPE_INT_ARGB);
        var graphics = resized.createGraphics(); graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null); graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(resized, "png", out); return out.toByteArray();
    }

    public void setEditing(boolean enabled) {
        if (!connected || status.stage() != Stage.READY || editing == enabled) return;
        editing = enabled; command(Wire.EDIT, Boolean.toString(enabled));
    }
    public void resetLayout() { DesktopAppState.preferences().minecraft().resetLayout(); }
    public void previewToast() { command(Wire.PREVIEW, ""); }
    public void pushToast(String text) { if (text != null && connected) command(Wire.TOAST, text); }
    public void uninject() {
        Session s = session; if (s == null) return;
        settingsControlsHidden = true;
        s.stopping = true; s.stopAt = System.nanoTime(); editing = false;
        update(s, Stage.STOPPING, 1, ""); command(Wire.UNINJECT, "");
    }
    private void command(int type, String value) {
        Session s = session;
        if (s != null && !s.commands.offer(new Wire.Frame(type, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) fail(s, "IPC 队列已满。");
    }
    private void watchdog() {
        Session s = session; if (s == null || s.target == null) return;
        long now = System.nanoTime();
        if (s.wire != null && now - s.lastReceive > TimeUnit.SECONDS.toNanos(5)) fail(s, "Minecraft 连接超时；游戏端将在失去心跳后清理 HUD。");
        else if (!s.ready && !s.stopping && now - s.created > TimeUnit.SECONDS.toNanos(45)) fail(s, "未收到游戏渲染 READY。请恢复游戏窗口并重试。");
        else if (s.stopping && s.stopAt != 0 && now - s.stopAt > TimeUnit.SECONDS.toNanos(10)) fail(s, "显示已停止，Hook 撤销未完成；请恢复游戏窗口以释放资源，必要时重启 Minecraft。");
    }
    private synchronized void update(Session s, Stage stage, double progress, String detail) {
        if (session == s) status = new Status(stage, progress, detail, List.of());
    }
    private void fail(Session s, String reason) { finish(s, reason); }
    private synchronized void finish(Session s, String error) {
        if (session != s) return;
        session = null; closeSession(s);
        boolean hadHud = connected; connected = false; editing = false;
        status = new Status(error.isEmpty() ? Stage.IDLE : Stage.FAILED, 0, error, List.of());
        DesktopAppState.runOnMainThread(() -> {
            if (generation.get() != s.id) return;
            DesktopAppState.hudManager().setMinecraftSuppressed(false);
            NCMScreen.getInstance().refreshMinecraftNavigation();
            if (hadHud && error.isEmpty()) NCMScreen.getInstance().setCurrentPanel(new SettingsPanel());
        });
    }
    private static void closeSession(Session s) {
        if (s == null) return;
        try { if (s.server != null) s.server.close(); } catch (IOException ignored) { }
        try { if (s.socket != null) s.socket.close(); } catch (IOException ignored) { }
    }
    static String explain(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        String raw = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        if ((e instanceof ClassNotFoundException || e instanceof NoClassDefFoundError)
                && (raw.contains("com.sun.tools.attach") || raw.contains("com/sun/tools/attach")))
            return raw + "\nTritium 运行时必须包含 jdk.attach；请使用完整 JDK 21 或官方运行时包。";
        return raw;
    }
    static String explainAttach(Throwable e) {
        if (e instanceof AttachLoader.StartupException) return e.getMessage();
        if (e instanceof com.sun.tools.attach.AgentInitializationException
                || e instanceof com.sun.tools.attach.AgentLoadException && String.valueOf(e.getMessage()).matches(".*(?:library: |reason: |^)(102)$"))
            return "Agent 已进入目标 JVM，但初始化失败（102）。\n" + explain(e)
                    + "\n请查看 Minecraft 启动器中的 Agent 异常；这不是 Attach 权限提示。";
        return explain(e) + "\n请使用同一系统用户及权限级别启动两个程序，并确保目标 JVM 能读取 Tritium 缓存中的 Agent JAR。Linux/macOS 下沙盒或容器可能隔离进程、临时目录和文件访问。目标需允许 Attach：移除 -XX:+DisableAttachMechanism。JDK 9+ 若禁用动态 Agent，使用 -XX:+EnableDynamicAgentLoading 后重启游戏（Java 8 不添加此参数）。启动器不允许这些设置时不兼容。";
    }
    static boolean flagEnabled(String[] arguments, String name, boolean fallback) {
        boolean enabled = fallback;
        for (String arg : arguments) {
            if (arg.equals("-XX:+" + name)) enabled = true;
            else if (arg.equals("-XX:-" + name)) enabled = false;
        }
        return enabled;
    }
    static Path extractAgent() throws Exception {
        byte[] bytes;
        try (InputStream in = MinecraftConnectionManager.class.getResourceAsStream("/tritium/minecraft/minecraft-hud-agent.jar")) {
            if (in == null) throw new IOException("发行包缺少 Minecraft Agent；请重新运行 mvn package。");
            bytes = in.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path directory = AppPaths.cacheDir().resolve("minecraft"); Files.createDirectories(directory);
        Path jar = directory.resolve("hud-" + hash + ".jar");
        if (!Files.exists(jar) || !Arrays.equals(Files.readAllBytes(jar), bytes)) Files.write(jar, bytes);
        return jar;
    }
    @Override public void close() {
        // Closing the authenticated connection also requests autonomous cleanup in the game.
        Session s = session; session = null; closeSession(s); monitor.shutdownNow(); workers.shutdownNow(); connected = false;
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    private static final class Session {
        final long id, created = System.nanoTime(); final String token;
        final ArrayBlockingQueue<Wire.Frame> commands = new ArrayBlockingQueue<>(64);
        volatile Candidate target; volatile ServerSocket server; volatile Socket socket; volatile Wire wire;
        volatile long lastReceive = System.nanoTime(), stopAt; long sequence;
        volatile boolean stopping, ready; volatile String error = "";
        Session(long id) { this.id = id; byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); token = HexFormat.of().formatHex(bytes); }
    }
}
