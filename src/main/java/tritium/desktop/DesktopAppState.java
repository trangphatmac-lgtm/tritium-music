package tritium.desktop;

import tritium.desktop.hud.HudManager;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

public final class DesktopAppState {
    private static final DesktopApi API = new DesktopApi();
    private static final MusicPreferences PREFERENCES = new MusicPreferences();
    private static final DesktopPreferenceStore PREFERENCE_STORE = DesktopPreferenceStore.defaultStore();
    private static final ScreenManager SCREEN_MANAGER = new ScreenManager();
    private static final DownloadStatus DOWNLOAD_STATUS = new DownloadStatus();
    private static final HudManager HUD_MANAGER = new HudManager();
    private static final ConcurrentLinkedQueue<Runnable> MAIN_THREAD_TASKS = new ConcurrentLinkedQueue<>();

    private static volatile Thread mainThread;
    private static volatile boolean running;

    private DesktopAppState() {
    }

    public static DesktopApi api() {
        return API;
    }

    public static MusicPreferences preferences() {
        return PREFERENCES;
    }

    public static void loadPreferences() {
        PREFERENCE_STORE.load(PREFERENCES);
        PREFERENCES.setChangeCallback(() -> {
            PREFERENCE_STORE.save(PREFERENCES);
            HUD_MANAGER.onPreferencesChanged();
        });
    }

    public static void savePreferences() {
        PREFERENCE_STORE.save(PREFERENCES);
    }

    public static ScreenManager screenManager() {
        return SCREEN_MANAGER;
    }

    public static DownloadStatus downloadStatus() {
        return DOWNLOAD_STATUS;
    }

    public static HudManager hudManager() {
        return HUD_MANAGER;
    }

    public static void markMainThread() {
        mainThread = Thread.currentThread();
    }

    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean value) {
        running = value;
    }

    public static void runOnMainThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }

        if (isMainThread()) {
            runnable.run();
        } else {
            MAIN_THREAD_TASKS.add(runnable);
        }
    }

    public static <T> T runOnMainThreadBlocking(Supplier<T> supplier) {
        if (isMainThread()) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        MAIN_THREAD_TASKS.add(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        return future.join();
    }

    public static <T> CompletableFuture<T> submitToMainThread(Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runOnMainThread(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public static void pumpMainThreadTasks() {
        Runnable task;
        while ((task = MAIN_THREAD_TASKS.poll()) != null) {
            task.run();
        }
    }

    public static final class DownloadStatus {
        public volatile boolean downloading;
        public volatile double downloadProgress;
        public volatile String downloadSpeed = "0 b/s";
    }
}
