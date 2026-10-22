package tritium.ncm.music.automix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tritium.desktop.AppPaths;
import tritium.ncm.OptionsUtil;
import tritium.ncm.RequestUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Fetch only acoustic metadata. Call from a background worker; failures preserve ordinary playback. */
public final class SongFeatureService {
    private static final long POSITIVE_TTL = Duration.ofDays(30).toMillis();
    private static final long NEGATIVE_TTL = Duration.ofMinutes(10).toMillis();
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private final Path directory;
    private final Function<List<Long>, JsonObject> fetcher;
    private final LongSupplier clock;
    private final Map<Long, Cached> recent = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Cached> entry) { return size() > 256; }
    };

    public record Pair(SongFeatures a, SongFeatures b) { }
    private record Cached(JsonObject feature, long expires) { }

    public SongFeatureService() {
        this(AppPaths.cacheDir().resolve("AutoMixFeatures"), SongFeatureService::request, System::currentTimeMillis);
    }

    SongFeatureService(Path directory, Function<List<Long>, JsonObject> fetcher, LongSupplier clock) {
        this.directory = directory;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public synchronized Pair loadPair(long aId, double aDuration, long bId, double bDuration) {
        long now = clock.getAsLong();
        List<Long> missing = new ArrayList<>();
        for (long id : new long[] { aId, bId }) {
            if (id <= 0 || missing.contains(id)) continue;
            Cached cached = recent.get(id);
            if (cached != null && cached.expires > now) continue;
            Cached disk = read(id, now);
            if (disk != null) recent.put(id, disk);
            else missing.add(id);
        }
        if (!missing.isEmpty() && !Thread.currentThread().isInterrupted()) {
            Map<Long, JsonObject> response = Map.of();
            try { response = parseResponse(fetcher.apply(List.copyOf(missing)), missing); }
            catch (RuntimeException ignored) { /* Metadata is optional; do not fail the audio request. */ }
            if (!Thread.currentThread().isInterrupted()) {
                for (long id : missing) {
                    JsonObject data = response.get(id);
                    recent.put(id, new Cached(data, now + (data == null ? NEGATIVE_TTL : POSITIVE_TTL)));
                    if (data != null) write(id, data, now);
                }
            }
        }
        return new Pair(features(aId, aDuration, now), features(bId, bDuration, now));
    }

    private SongFeatures features(long id, double duration, long now) {
        Cached value = recent.get(id);
        return value == null || value.expires <= now || value.feature == null
                ? SongFeatures.empty(id, duration) : SongFeatures.fromJson(id, duration, value.feature);
    }

    private static JsonObject request(List<Long> ids) {
        RequestUtil.RequestAnswer answer = RequestUtil.createRequest("/api/song/feature/detail",
                requestParameters(ids), OptionsUtil.createOptions());
        return answer != null && answer.getStatus() == 200 ? answer.toJsonObject() : null;
    }

    static Map<String, Object> requestParameters(List<Long> ids) {
        JsonArray requests = new JsonArray();
        for (long id : ids) {
            JsonObject entry = new JsonObject();
            // SongFeatureParam in classes20.dex uses id/v; response entries use songId/version.
            entry.addProperty("id", Long.toString(id));
            entry.addProperty("v", 0);
            requests.add(entry);
        }
        return Map.of("c", requests.toString());
    }

    static Map<Long, JsonObject> parseResponse(JsonObject response, List<Long> requested) {
        Map<Long, JsonObject> result = new HashMap<>();
        if (response == null || (response.has("code") && SongFeatures.number(response.get("code")) != 200)) return result;
        JsonElement data = response.get("data");
        if (data == null || !data.isJsonArray()) return result;
        for (JsonElement entry : data.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject object = entry.getAsJsonObject();
            long id;
            try { id = object.get("songId").getAsLong(); }
            catch (RuntimeException ignored) { continue; }
            if (!requested.contains(id) || object.toString().length() > MAX_FILE_BYTES) continue;
            // Version-only/no-material entries should be retried later instead of cached for a month.
            if (!object.has("bpm") && !object.has("repBpm") && !object.has("silenceTime")
                    && !object.has("voiceTime") && !object.has("drumTime") && !object.has("genre")) continue;
            result.put(id, object);
        }
        return result;
    }

    private Cached read(long id, long now) {
        Path path = directory.resolve(id + ".json");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) return null;
            JsonObject envelope = new JsonParser().parse(Files.readString(path)).getAsJsonObject();
            double fetched = SongFeatures.number(envelope.get("fetchedAt"));
            if (!Double.isFinite(fetched) || now - fetched >= POSITIVE_TTL || fetched > now) return null;
            JsonObject feature = envelope.getAsJsonObject("feature");
            // Moving a result from disk into memory must not restart its original lifetime.
            return feature != null && feature.get("songId").getAsLong() == id
                    ? new Cached(feature, (long) fetched + POSITIVE_TTL) : null;
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    private void write(long id, JsonObject feature, long now) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("fetchedAt", now);
            envelope.add("feature", feature);
            temporary = Files.createTempFile(directory, "feature-", ".tmp");
            Files.writeString(temporary, envelope.toString());
            try { Files.move(temporary, directory.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, directory.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // A read-only or full cache must not interrupt playback.
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
