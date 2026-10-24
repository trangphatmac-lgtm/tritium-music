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

/** Optional Windows integration; other platforms never load WinRT or start a worker. */
public final class SystemMediaControls implements AutoCloseable {
    private ScheduledExecutorService worker;
    private WindowsSmtc backend;
    private MediaSession session;
    private volatile boolean closed;

    private SystemMediaControls() { }

    public static SystemMediaControls start() {
        SystemMediaControls controls = new SystemMediaControls();
        if (!Platform.isWindows()) return controls;
        try {
            long hwnd = mainWindowHandle();
            controls.worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "Windows SMTC");
                thread.setDaemon(true);
                return thread;
            });
            controls.worker.scheduleWithFixedDelay(() -> controls.update(hwnd), 0, 250, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            System.err.println("[SMTC] Integration unavailable: " + failure);
            controls.close();
        }
        return controls;
    }

    private void update(long hwnd) {
        if (closed) return;
        try {
            if (backend == null) {
                backend = WindowsSmtc.open(hwnd, command -> DesktopAppState.runOnMainThread(() -> {
                    if (!closed && DesktopAppState.isRunning()) {
                        try { execute(command); }
                        catch (RuntimeException failure) {
                            System.err.println("[SMTC] Command failed: " + failure);
                        }
                    }
                }));
                session = new MediaSession(backend);
            }
            session.update(CloudMusic.playbackState(), System.nanoTime());
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("[SMTC] Integration disabled: " + failure);
            closed = true;
            if (backend != null) backend.close();
            worker.shutdown();
        }
    }

    static void execute(WindowsSmtc.Command command) {
        PlaybackState state = CloudMusic.playbackState();
        if (command.positionMillis() != null) {
            if (state.canPlay() && state.durationMillis() > 0) {
                float position = Math.clamp(command.positionMillis(), 0, state.durationMillis());
                CloudMusic.seek(position);
                MusicLyricsWidget.resetProgress(position);
            }
            return;
        }
        switch (command.button()) {
            case PLAY -> { if (state.canPlay()) CloudMusic.setPaused(false); }
            case PAUSE -> { if (state.canPause()) CloudMusic.setPaused(true); }
            case PREVIOUS -> { if (state.canPrevious()) CloudMusic.prev(); }
            case NEXT -> { if (state.canNext()) CloudMusic.next(); }
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
                System.err.println("[SMTC] Still releasing system media controls during shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
