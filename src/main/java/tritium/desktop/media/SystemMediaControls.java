package tritium.desktop.media;

import com.sun.jna.Platform;
import org.lwjgl.opengl.Display;
import tritium.desktop.DesktopAppState;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.PlaybackState;
import tritium.widget.impl.MusicLyricsWidget;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Optional platform backends, isolated from rendering and audio playback. */
public final class SystemMediaControls implements AutoCloseable {
    private ScheduledExecutorService worker;
    private MediaSession.Backend backend;
    private MediaSession session;
    private volatile boolean closed;

    private SystemMediaControls() { }

    public static SystemMediaControls start() {
        SystemMediaControls controls = new SystemMediaControls();
        if (!Platform.isWindows() && !Platform.isMac() && !Platform.isLinux()) return controls;
        try {
            long hwnd = Platform.isWindows() ? mainWindowHandle() : 0;
            controls.worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "System media controls");
                thread.setDaemon(true);
                return thread;
            });
            controls.worker.scheduleWithFixedDelay(() -> controls.update(hwnd), 0, 250, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            System.err.println("[System media] Integration unavailable: " + failure);
            controls.close();
        }
        return controls;
    }

    private void update(long hwnd) {
        if (closed) return;
        try {
            if (backend == null) {
                backend = openBackend(hwnd, command -> DesktopAppState.runOnMainThread(() -> {
                    if (!closed && DesktopAppState.isRunning()) {
                        try { execute(command); }
                        catch (RuntimeException failure) {
                            System.err.println("[System media] Command failed: " + failure);
                        }
                    }
                }));
                session = new MediaSession(backend);
            }
            session.update(CloudMusic.playbackState(), System.nanoTime());
            backend.volume(DesktopAppState.preferences().volume().getValue());
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("[System media] Integration disabled: " + failure);
            closed = true;
            try { if (backend != null) backend.close(); }
            finally { worker.shutdown(); }
        }
    }

    private static MediaSession.Backend openBackend(long hwnd, java.util.function.Consumer<MediaCommand> commands) {
        if (Platform.isWindows()) return WindowsSmtc.open(hwnd, commands);
        if (Platform.isMac()) return MacNowPlaying.open(commands);
        return LinuxMpris.open(commands);
    }

    static void execute(MediaCommand command) {
        if (command.action() == MediaCommand.Action.VOLUME) {
            if (Double.isFinite(command.volume())) {
                DesktopAppState.preferences().volume().setValue(Math.clamp(command.volume(), 0, 1));
            }
            return;
        }
        PlaybackState state = CloudMusic.playbackState();
        if (command.action() == MediaCommand.Action.SEEK || command.action() == MediaCommand.Action.SEEK_RELATIVE) {
            if (!state.canPlay() || state.durationMillis() <= 0) return;
            if (command.trackId() != null && (state.song() == null || state.song().getId() != command.trackId()
                    || command.positionMillis() < 0 || command.positionMillis() > state.durationMillis())) return;
            long requested = command.positionMillis();
            if (command.action() == MediaCommand.Action.SEEK_RELATIVE) {
                // Compare before adding: arbitrary D-Bus offsets must not overflow.
                if (requested > state.durationMillis() - state.positionMillis()) {
                    if (state.canNext()) CloudMusic.next();
                    return;
                }
                requested = requested < -state.positionMillis() ? 0 : state.positionMillis() + requested;
            }
            float position = Math.clamp(requested, 0, state.durationMillis());
            CloudMusic.seek(position);
            MusicLyricsWidget.resetProgress(position);
            return;
        }
        switch (command.action()) {
            case PLAY -> { if (state.canPlay()) CloudMusic.setPaused(false); }
            case PAUSE -> { if (state.canPause()) CloudMusic.setPaused(true); }
            case PLAY_PAUSE -> {
                if (state.status() == PlaybackState.Status.PLAYING && state.canPause()) CloudMusic.setPaused(true);
                else if (state.canPlay()) CloudMusic.setPaused(false);
            }
            case PREVIOUS -> { if (state.canPrevious()) CloudMusic.prev(); }
            case NEXT -> { if (state.canNext()) CloudMusic.next(); }
            default -> { }
        }
    }

    // LWJGL 2 has no public HWND accessor. Read the actual owning window, never FindWindow by title.
    static long mainWindowHandle() throws ReflectiveOperationException {
        if (!Display.isCreated()) throw new IllegalStateException("Display has not been created");
        Method getter = Display.class.getDeclaredMethod("getImplementation");
        getter.setAccessible(true);
        Object implementation = getter.invoke(null);
        Field hwnd = implementation.getClass().getDeclaredField("hwnd");
        hwnd.setAccessible(true);
        return hwnd.getLong(implementation);
    }

    @Override public void close() {
        closed = true;
        if (worker == null) return;
        try { worker.execute(() -> { if (backend != null) backend.close(); }); }
        catch (RejectedExecutionException ignored) { /* A failed update is already closing the bridge. */ }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("[System media] Still releasing system media controls during shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
