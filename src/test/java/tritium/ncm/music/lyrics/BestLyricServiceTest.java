package tritium.ncm.music.lyrics;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BestLyricServiceTest {
    static final LyricSong SONG = new LyricSong("1", "Song", "Artist", "Album", 200000, "", "");
    static LyricDocument document(String text, boolean words) {
        return new LyricDocument(List.of(new LyricDocument.Line(1000, 1000, text, "译文", "roma",
                words ? List.of(new LyricDocument.Word(text, 1000, 1000)) : List.of())), false);
    }
    static class Providers implements BestLyricService.Providers {
        final Map<LyricSource, LyricDocument> documents = new EnumMap<>(LyricSource.class);
        final List<LyricSource> calls = new CopyOnWriteArrayList<>();
        List<LyricSong> candidates = List.of(SONG);
        @Override public LyricDocument netease(LyricSong song) { return documents.getOrDefault(LyricSource.NETEASE, LyricDocument.EMPTY); }
        @Override public List<LyricSong> search(LyricSource source, LyricSong target) throws Exception { calls.add(source); return candidates; }
        @Override public LyricDocument fetch(LyricSource source, LyricSong song) throws Exception { calls.add(source); return documents.getOrDefault(source, LyricDocument.EMPTY); }
    }
    @Test void preferenceWinsAmongWordTimedMatchesAndNativeIsReused() throws Exception {
        var providers = new Providers();
        for (var source : LyricSource.values()) providers.documents.put(source, document(source.id(), true));
        try (var service = new BestLyricService(providers)) {
            for (var source : LyricSource.values()) assertEquals(source, service.resolve(SONG, true, source, r -> {}).source());
        }
    }
    @Test void lowerPriorityWordTimingBeatsPreferredLineTiming() throws Exception {
        var providers = new Providers();
        providers.documents.put(LyricSource.QQ, document("qq lines", false));
        providers.documents.put(LyricSource.NETEASE, document("ncm lines", false));
        providers.documents.put(LyricSource.AMLL, document("amll words", true));
        try (var service = new BestLyricService(providers)) {
            assertEquals(LyricSource.AMLL, service.resolve(SONG, true, LyricSource.QQ, r -> {}).source());
            assertFalse(providers.calls.contains(LyricSource.KUGOU));
        }
    }
    @Test void preferredLinesAreRetainedWhenNoWordsExist() throws Exception {
        var providers = new Providers();
        providers.documents.put(LyricSource.QQ, document("qq", false));
        providers.documents.put(LyricSource.NETEASE, document("ncm", false));
        try (var service = new BestLyricService(providers)) {
            assertEquals(LyricSource.QQ, service.resolve(SONG, true, LyricSource.QQ, r -> {}).source());
        }
    }
    @Test void disabledAndPureMusicDoNotContactAlternatives() throws Exception {
        var providers = new Providers();
        providers.documents.put(LyricSource.NETEASE, document("native", false));
        try (var service = new BestLyricService(providers)) {
            assertEquals(LyricSource.NETEASE, service.resolve(SONG, false, LyricSource.QQ, r -> {}).source());
            assertTrue(providers.calls.isEmpty());
        }
        providers.documents.put(LyricSource.NETEASE, new LyricDocument(List.of(), true));
        try (var service = new BestLyricService(providers)) {
            assertTrue(service.resolve(SONG, true, LyricSource.QQ, r -> {}).document().pureMusic());
            assertTrue(providers.calls.isEmpty());
        }
    }
    @Test void rejectsWrongSongAndContinuesAfterFailure() throws Exception {
        var providers = new Providers() {
            @Override public LyricDocument fetch(LyricSource source, LyricSong song) throws Exception {
                if (source == LyricSource.AMLL) throw new java.io.IOException("offline");
                return super.fetch(source, song);
            }
        };
        providers.documents.put(LyricSource.NETEASE, document("native", false));
        providers.documents.put(LyricSource.QQ, document("wrong", true));
        providers.candidates = List.of(new LyricSong("2", "Different", "Stranger", "Another", 250000, "", ""));
        try (var service = new BestLyricService(providers)) {
            assertEquals("native", service.resolve(SONG, true, LyricSource.QQ, r -> {}).document().lines().getFirst().text());
        }
    }
    @Test void slowSourceTimesOutAndIsInterrupted() throws Exception {
        var interrupted = new CountDownLatch(1);
        var providers = new Providers() {
            @Override public List<LyricSong> search(LyricSource source, LyricSong target) throws Exception {
                if (source == LyricSource.QQ) {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException error) { interrupted.countDown(); throw error; }
                }
                return super.search(source, target);
            }
        };
        providers.documents.put(LyricSource.NETEASE, document("native", true));
        try (var service = new BestLyricService(providers, 40, 500)) {
            assertEquals(LyricSource.NETEASE, service.resolve(SONG, true, LyricSource.QQ, r -> {}).source());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }
    @Test void cacheIsImmutableAndPreferenceDoesNotReusePriorWinner() throws Exception {
        var providers = new Providers();
        providers.documents.put(LyricSource.QQ, document("qq", true));
        providers.documents.put(LyricSource.AMLL, document("amll", true));
        try (var service = new BestLyricService(providers)) {
            var first = service.resolve(SONG, true, LyricSource.QQ, r -> {});
            first.document().toLines().getFirst().words.clear();
            assertTrue(first.document().wordTimed());
            assertEquals(LyricSource.AMLL, service.resolve(SONG, true, LyricSource.AMLL, r -> {}).source());
            assertEquals(LyricSource.QQ, service.resolve(SONG, true, LyricSource.QQ, r -> {}).source());
            assertEquals(3, providers.calls.stream().filter(source -> source == LyricSource.QQ).count()); // two searches, one cached fetch
        }
    }
    @Test void transientMissIsRetried() throws Exception {
        var attempts = new AtomicInteger();
        var providers = new Providers() {
            @Override public LyricDocument fetch(LyricSource source, LyricSong song) {
                if (source == LyricSource.AMLL && attempts.incrementAndGet() > 1) return document("recovered", true);
                return LyricDocument.EMPTY;
            }
        };
        try (var service = new BestLyricService(providers)) {
            service.resolve(SONG, true, LyricSource.AMLL, r -> {});
            assertEquals(LyricSource.AMLL, service.resolve(SONG, true, LyricSource.AMLL, r -> {}).source());
        }
    }
    @Test void queuedOldResultsCannotPublishAfterSongOrSettingsChange() throws Exception {
        var providers = new Providers();
        providers.documents.put(LyricSource.NETEASE, document("native", false));
        var queue = new LinkedBlockingQueue<Runnable>();
        var accepted = new ArrayList<BestLyricService.Result>();
        try (var loader = new LyricLoader(new BestLyricService(providers), queue::add)) {
            loader.load(SONG, false, LyricSource.QQ, () -> true, accepted::add);
            var old = queue.poll(2, TimeUnit.SECONDS); assertNotNull(old);
            loader.load(SONG, false, LyricSource.NETEASE, () -> true, accepted::add);
            var current = queue.poll(2, TimeUnit.SECONDS); assertNotNull(current);
            old.run(); assertTrue(accepted.isEmpty());
            current.run(); assertEquals(1, accepted.size());
            loader.load(SONG, false, LyricSource.QQ, () -> false, accepted::add);
            var staleSong = queue.poll(2, TimeUnit.SECONDS); assertNotNull(staleSong);
            staleSong.run(); assertEquals(1, accepted.size());
        }
    }

    @Test void canceledProviderCannotPublishEvenWhenItIgnoresInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queue = new LinkedBlockingQueue<Runnable>();
        var accepted = new ArrayList<BestLyricService.Result>();
        var providers = new Providers() {
            @Override public LyricDocument netease(LyricSong song) {
                if (song.id().equals("1")) {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                }
                return document(song.id(), true);
            }
        };
        try (var loader = new LyricLoader(new BestLyricService(providers), queue::add)) {
            loader.load(SONG, true, LyricSource.NETEASE, () -> true, accepted::add);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var next = new LyricSong("2", "Next", "Artist", "Album", 200000, "", "");
            loader.load(next, true, LyricSource.NETEASE, () -> true, accepted::add);
            var current = queue.poll(2, TimeUnit.SECONDS); assertNotNull(current);
            current.run();
            release.countDown();
            assertEquals("2", accepted.getFirst().document().lines().getFirst().text());
            assertEquals(1, accepted.size());
        } finally { release.countDown(); }
    }
}
