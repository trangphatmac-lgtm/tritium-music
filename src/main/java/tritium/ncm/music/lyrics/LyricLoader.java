package tritium.ncm.music.lyrics;

import java.util.concurrent.*;
import java.util.function.*;

/** Cancels obsolete requests and checks generation again when the render-thread callback runs. */
public final class LyricLoader implements AutoCloseable {
    private final BestLyricService service;
    private final Consumer<Runnable> dispatch;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Future<?> pending;
    private long generation;

    public LyricLoader(BestLyricService service, Consumer<Runnable> dispatch) {
        this.service = service; this.dispatch = dispatch;
    }

    public synchronized void load(LyricSong song, boolean automatic, LyricSource preferred,
                                  BooleanSupplier current, Consumer<BestLyricService.Result> publish) {
        long request = ++generation;
        if (pending != null) pending.cancel(true);
        Consumer<BestLyricService.Result> deliver = result -> dispatch.accept(() -> {
            synchronized (this) {
                if (request == generation && current.getAsBoolean()) publish.accept(result);
            }
        });
        pending = executor.submit(() -> {
            try {
                var initial = new BestLyricService.Result[1];
                var result = service.resolve(song, automatic, preferred, baseline -> {
                    initial[0] = baseline; deliver.accept(baseline);
                });
                if (!result.equals(initial[0])) deliver.accept(result);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                System.err.println("[BestLyrics] Unable to load lyrics: " + error);
            }
        });
    }

    @Override public synchronized void close() {
        generation++;
        if (pending != null) pending.cancel(true);
        executor.shutdownNow();
        service.close();
    }
}
