package tritium.utils.other.multithreading;

import lombok.SneakyThrows;
import tritium.desktop.DesktopAppState;
import tritium.utils.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * @since 2024/12/28 17:57
 */
public class MultiThreadingUtil {

    public static final Logger LOGGER = new Logger("MultiThreadingUtil");

    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @SneakyThrows
    public static CompletableFuture<Void> runAsync(Runnable runnable) {

        if (runnable == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Runnable is null"));
            return future;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        FutureTaskWrapper wrapper = new FutureTaskWrapper(runnable, future);

        executor.submit(wrapper);

        return future;
    }

    @SneakyThrows
    public static <T> T runOnMainThreadBlocking(Supplier<T> supplier) {
        return DesktopAppState.runOnMainThreadBlocking(supplier);
    }

    public static void runOnMainThread(Runnable runnable) {
        DesktopAppState.runOnMainThread(runnable);
    }

    private static class FutureTaskWrapper implements Runnable {
        private final Runnable runnable;
        private final CompletableFuture<Void> future;

        public FutureTaskWrapper(Runnable runnable, CompletableFuture<Void> future) {
            this.runnable = runnable;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }
}
