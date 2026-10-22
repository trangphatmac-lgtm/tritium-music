package tritium.ncm.music.automix;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SongFeatureServiceTest {
    @TempDir Path directory;

    @Test void requestMatchesApkIdAndVersionFieldNames() {
        String c = (String) SongFeatureService.requestParameters(List.of(123L, 456L)).get("c");
        JsonArray array = new JsonParser().parse(c).getAsJsonArray();
        assertEquals("123", array.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(0, array.get(0).getAsJsonObject().get("v").getAsInt());
        assertFalse(array.get(0).getAsJsonObject().has("songId"));
    }

    @Test void batchesRequestsAndReusesMemoryAndDiskWithFreshAudioDurations() {
        AtomicInteger calls = new AtomicInteger();
        SongFeatureService service = new SongFeatureService(directory, ids -> {
            calls.incrementAndGet();
            assertEquals(List.of(1L, 2L), ids);
            return response(1, 2);
        }, () -> 1000L);
        SongFeatureService.Pair first = service.loadPair(1, 120_000, 2, 130_000);
        assertEquals(120, first.a().bpm());
        service.loadPair(2, 110_000, 1, 140_000);
        assertEquals(1, calls.get());
        SongFeatureService diskOnly = new SongFeatureService(directory, ids -> {
            fail("valid disk cache should suppress network requests"); return null;
        }, () -> 1500L);
        SongFeatureService.Pair disk = diskOnly.loadPair(1, 125_000, 2, 135_000);
        assertEquals(125_000, disk.a().durationMillis());
        assertEquals(120, disk.b().bpm());
    }

    @Test void unavailableMetadataIsNegativeCachedAndRetried() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000);
        SongFeatureService service = new SongFeatureService(directory, ids -> {
            calls.incrementAndGet(); throw new IllegalStateException("offline");
        }, clock::get);
        SongFeatureService.Pair missing = service.loadPair(1, 120_000, 2, 120_000);
        assertEquals(AutoMixPlan.Strategy.CROSSFADE, AutoMixPlanner.plan(missing.a(), missing.b()).strategy());
        service.loadPair(1, 120_000, 2, 120_000);
        assertEquals(1, calls.get());
        clock.addAndGet(Duration.ofMinutes(11).toMillis());
        service.loadPair(1, 120_000, 2, 120_000);
        assertEquals(2, calls.get());
    }

    @Test void loadingDiskIntoMemoryDoesNotExtendOriginalExpiry() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000);
        java.util.function.Function<List<Long>, JsonObject> fetch = ids -> {
            calls.incrementAndGet();
            return response(1, 2);
        };
        new SongFeatureService(directory, fetch, clock::get).loadPair(1, 120_000, 2, 120_000);
        assertEquals(1, calls.get());

        clock.addAndGet(Duration.ofDays(29).toMillis());
        SongFeatureService reopened = new SongFeatureService(directory, fetch, clock::get);
        reopened.loadPair(1, 120_000, 2, 120_000);
        assertEquals(1, calls.get(), "day 29 should still use the original disk cache");

        clock.addAndGet(Duration.ofDays(2).toMillis());
        reopened.loadPair(1, 120_000, 2, 120_000);
        assertEquals(2, calls.get(), "day 31 must refresh, even though memory was populated on day 29");
    }

    @Test void malformedForeignAndStaleEntriesDoNotPoisonCache() throws Exception {
        Files.writeString(directory.resolve("1.json"), "{broken");
        AtomicInteger calls = new AtomicInteger();
        SongFeatureService service = new SongFeatureService(directory, ids -> {
            calls.incrementAndGet(); return response(1, 99);
        }, () -> 1_000L);
        SongFeatureService.Pair pair = service.loadPair(1, 120_000, 2, 120_000);
        assertEquals(120, pair.a().bpm());
        assertTrue(Double.isNaN(pair.b().bpm()));
        assertFalse(Files.exists(directory.resolve("99.json")));
        SongFeatureService expired = new SongFeatureService(directory, ids -> {
            calls.incrementAndGet(); return null;
        }, () -> Duration.ofDays(31).toMillis());
        assertTrue(Double.isNaN(expired.loadPair(1, 120_000, 2, 120_000).a().bpm()));
        assertEquals(2, calls.get());
    }

    @Test void sameIdIsRequestedOnceAndNoMaterialIsNotPersisted() {
        SongFeatureService service = new SongFeatureService(directory, ids -> {
            assertEquals(List.of(1L), ids);
            return new JsonParser().parse("{\"code\":200,\"data\":[{\"songId\":1,\"version\":0}]}").getAsJsonObject();
        }, () -> 1000L);
        service.loadPair(1, 120_000, 1, 120_000);
        assertFalse(Files.exists(directory.resolve("1.json")));
    }

    private static JsonObject response(long... ids) {
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        JsonArray data = new JsonArray();
        for (long id : ids) {
            JsonObject feature = AutoMixPlannerTest.material();
            feature.addProperty("songId", id);
            data.add(feature);
        }
        result.add("data", data);
        return result;
    }
}
