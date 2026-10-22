package tritium.ncm.music.lyrics;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Ordered matching with word-timed precedence, bounded requests and a small expiring success cache. */
public final class BestLyricService implements AutoCloseable {
    public interface Providers {
        LyricDocument netease(LyricSong song) throws Exception;
        List<LyricSong> search(LyricSource source, LyricSong target) throws Exception;
        LyricDocument fetch(LyricSource source, LyricSong song) throws Exception;
    }
    public record Result(LyricSource source, LyricDocument document) {}
    private record Key(LyricSource source, LyricSong song) {}
    private record Cached(LyricDocument document, long storedAt) {}
    private final Providers providers;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Key, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private final long searchTimeoutMs, lyricTimeoutMs;

    public BestLyricService(Providers providers) { this(providers, 3500, 5000); }
    BestLyricService(Providers providers, long searchTimeoutMs, long lyricTimeoutMs) {
        this.providers = providers; this.searchTimeoutMs = searchTimeoutMs; this.lyricTimeoutMs = lyricTimeoutMs;
    }

    public Result resolve(LyricSong target, boolean automatic, LyricSource preferred, Consumer<Result> initial) throws InterruptedException {
        var baseline = fetch(LyricSource.NETEASE, target);
        var nativeResult = new Result(LyricSource.NETEASE, baseline);
        initial.accept(nativeResult);
        if (!automatic || baseline.pureMusic()) return nativeResult;
        Result fallback = null;
        for (var source : LyricSource.order(preferred)) {
            checkInterrupted();
            LyricSong candidate = target;
            if (source == LyricSource.QQ || source == LyricSource.KUGOU) {
                var songs = bounded(() -> providers.search(source, target), searchTimeoutMs, List.<LyricSong>of(), source + " search");
                candidate = LyricMatchScore.select(target, songs);
                if (candidate == null) continue;
            }
            var document = source == LyricSource.NETEASE ? baseline : fetch(source, candidate);
            if (document.pureMusic()) return new Result(source, document);
            if (!document.usable()) continue;
            var result = new Result(source, document);
            if (document.wordTimed()) return result;
            if (fallback == null) fallback = result;
        }
        return fallback == null ? nativeResult : fallback;
    }

    private LyricDocument fetch(LyricSource source, LyricSong song) throws InterruptedException {
        var key = new Key(source, song);
        synchronized (cache) {
            var cached = cache.get(key);
            if (cached != null && System.nanoTime() - cached.storedAt() < TimeUnit.MINUTES.toNanos(15)) return cached.document();
            cache.remove(key);
        }
        var document = bounded(() -> source == LyricSource.NETEASE ? providers.netease(song) : providers.fetch(source, song),
                lyricTimeoutMs, LyricDocument.EMPTY, source + " lyrics");
        if (document.usable() || document.pureMusic()) synchronized (cache) {
            cache.put(key, new Cached(document, System.nanoTime()));
            while (cache.size() > 200) cache.remove(cache.keySet().iterator().next());
        }
        return document;
    }

    private <T> T bounded(Callable<T> task, long timeout, T fallback, String label) throws InterruptedException {
        checkInterrupted();
        var future = requests.submit(task);
        try {
            T result = future.get(timeout, TimeUnit.MILLISECONDS);
            return result == null ? fallback : result;
        } catch (TimeoutException | ExecutionException error) {
            System.err.println("[BestLyrics] " + label + " unavailable: " + (error instanceof ExecutionException ? error.getCause() : "timeout"));
            return fallback;
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
    private static void checkInterrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    @Override public void close() { requests.shutdownNow(); }
}
