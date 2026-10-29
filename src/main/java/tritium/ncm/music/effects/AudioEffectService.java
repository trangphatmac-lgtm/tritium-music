package tritium.ncm.music.effects;

import com.google.gson.*;
import tritium.desktop.AppPaths;
import tritium.ncm.RequestUtil;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Song recommendations and verified parameter packages. All I/O belongs on a worker. */
public final class AudioEffectService {
    static final long TTL = Duration.ofDays(3).toMillis();
    static final long RETRY_DELAY = Duration.ofSeconds(30).toMillis();
    static final int MAX_PACKAGE = 4 * 1024 * 1024;
    private static final int MAX_METADATA = 128 * 1024;
    private static final Set<String> SMART_KEYS = Set.of("0", "1", "2", "3", "4", "6", "7", "9", "10", "11", "12", "13", "14", "17", "18", "19", "20", "21");

    public record Selection(String mode, String tone, List<String> diy) {
        /** Offer all candidates to the song-aware filter; mutually exclusive effects are resolved there. */
        public static Selection automatic(String mode, String tone) {
            return new Selection(mode, tone, "Master".equals(mode)
                    ? List.of("en_bass", "en_treble", "en_vocal", "en_vibe", "en_live") : List.of());
        }

        public Selection {
            mode = Set.of("Off", "Smart", "Master", "Manual").contains(mode == null ? "" : mode) ? mode : "Off";
            tone = tone == null ? "Auto" : tone;
            diy = diy == null ? List.of() : List.copyOf(diy);
        }

        public boolean needsRecommendation() {
            return "Smart".equals(mode) || ("Master".equals(mode) && "Auto".equals(tone));
        }
    }
    public record Result(JsonObject configuration, String status, String tone, List<String> usedDiy) {
        public Result { configuration = copy(configuration); usedDiy = List.copyOf(usedDiy); }
        @Override public JsonObject configuration() { return copy(configuration); }
    }
    interface Transport {
        JsonObject request(String endpoint, Map<String, Object> parameters, String cookie) throws Exception;
        byte[] download(String url, String cookie) throws Exception;
    }
    private record Cached(JsonObject data, long fetchedAt) { }
    private final Path directory;
    private final Transport transport;
    private final LongSupplier clock;
    private final EffectPresets presets = new EffectPresets();
    private final Map<String, Cached> recent = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 1000; }
    };
    private final Map<String, Long> failures = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 1000; }
    };

    public AudioEffectService() { this(AppPaths.cacheDir().resolve("AudioEffects"), new HttpTransport(), System::currentTimeMillis); }
    AudioEffectService(Path directory, Transport transport, LongSupplier clock) {
        this.directory = directory; this.transport = transport; this.clock = clock;
    }

    /** Immediate bundled configuration, with no network or disk cache reads. */
    public Result fallback(Selection selection) {
        return switch (selection.mode()) {
            case "Smart" -> new Result(presets.smart("0"), "smartFallback", "", List.of());
            case "Master" -> master(selection, "Auto".equals(selection.tone()) ? EffectPresets.DEFAULT_MASTER_KEY : selection.tone(),
                    null, "Auto".equals(selection.tone()) ? "masterFallback" : "masterManual");
            case "Manual" -> {
                var composed = MasterEffectComposer.composeManual(selectedEnhancements(selection));
                yield new Result(composed.effect(), "manual", "", composed.usedDiy());
            }
            default -> new Result(null, "off", "", List.of());
        };
    }

    public synchronized Result load(long songId, Selection selection, String cookie) {
        if (songId <= 0 || !selection.needsRecommendation())
            return fallback(selection);
        // No credentials are persisted. Separate recommendations across login changes.
        String session = digest("SHA-256", Objects.requireNonNullElse(cookie, "").getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        String key = session + "/" + selection.mode() + "-" + songId;
        long now = clock.getAsLong();
        Cached cached = recent.get(key);
        if (cached != null && (cached.fetchedAt > now || now - cached.fetchedAt > Duration.ofDays(30).toMillis())) {
            recent.remove(key); cached = null;
        }
        if (cached == null) cached = read(key, now);
        if (cached != null) recent.put(key, cached);
        boolean stale = cached == null || now < cached.fetchedAt || now - cached.fetchedAt >= TTL;
        JsonObject data = cached == null ? null : cached.data;
        if (stale && failures.getOrDefault(key, 0L) <= now && !Thread.currentThread().isInterrupted()) {
            try {
                JsonObject response = "Smart".equals(selection.mode())
                        ? transport.request("/api/usertool/smart/sound/get", Map.of("songId", Long.toString(songId)), cookie)
                        : transport.request("/api/vip-rights/svip/sound/effect/get", Map.of("songIds", "[" + songId + "]"), cookie);
                JsonObject fetched = parseMetadata(selection.mode(), songId, response);
                if (fetched != null) {
                    data = fetched; cached = new Cached(copy(data), now);
                    recent.put(key, cached); write(key, cached); failures.remove(key); stale = false;
                } else failures.put(key, now + RETRY_DELAY);
            } catch (Exception ignored) {
                failures.put(key, now + RETRY_DELAY);
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        if (data == null) return fallback(selection);
        if ("Smart".equals(selection.mode())) {
            String soundKey = string(data, "soundKey");
            return new Result(presets.smart(soundKey), !stale && SMART_KEYS.contains(soundKey) ? "smart" : "smartFallback", "", List.of());
        }
        String baseKey = string(data, "baseKey");
        JsonObject song = null;
        boolean packageFailed = false;
        JsonObject resource = object(data, "effect");
        if (resource != null && !string(resource, "url").isBlank()) {
            String packageKey = key + "/package/" + string(resource, "md5");
            if (failures.getOrDefault(packageKey, 0L) > now) packageFailed = true;
            else try { song = loadPackage(resource, cookie); failures.remove(packageKey); }
            catch (Exception ignored) { packageFailed = true; failures.put(packageKey, now + RETRY_DELAY); }
        }
        return master(selection, baseKey, song, stale || packageFailed || !presets.masterNames().containsKey(baseKey)
                ? "masterFallback" : "masterAuto");
    }

    private Result master(Selection selection, String key, JsonObject song, String status) {
        String actual = presets.masterNames().containsKey(key) ? key : EffectPresets.DEFAULT_MASTER_KEY;
        var composed = MasterEffectComposer.compose(presets.master(actual), song, selectedEnhancements(selection), false);
        return new Result(composed.effect(), status, actual, composed.usedDiy());
    }

    private JsonObject selectedEnhancements(Selection selection) {
        JsonObject allDiy = presets.diy(), selected = new JsonObject();
        for (String option : selection.diy()) if (allDiy.has(option)) selected.add(option, allDiy.get(option));
        return selected;
    }

    static JsonObject parseMetadata(String mode, long id, JsonObject response) {
        if (response == null || number(response, "code", -1) != 200) return null;
        JsonElement data = response.get("data");
        if ("Smart".equals(mode)) {
            if (data == null || !data.isJsonObject()) return null;
            String key = string(data.getAsJsonObject(), "soundKey");
            if (key.isBlank() || key.length() > 32) return null;
            JsonObject result = new JsonObject(); result.addProperty("soundKey", key); return result;
        }
        if (data == null || !data.isJsonArray()) return null;
        for (JsonElement item : data.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            if (number(entry, "id", -1) != id) continue;
            JsonObject result = new JsonObject();
            result.addProperty("baseKey", string(entry, "baseKey"));
            JsonObject resource = object(entry, "effect");
            if (resource != null) {
                JsonObject safe = new JsonObject(); safe.addProperty("url", string(resource, "url"));
                safe.addProperty("md5", string(resource, "md5")); safe.addProperty("size", number(resource, "size", -1));
                result.add("effect", safe);
            }
            return result;
        }
        return null;
    }

    private JsonObject loadPackage(JsonObject resource, String cookie) throws Exception {
        String md5 = string(resource, "md5").toLowerCase(Locale.ROOT);
        if (!md5.matches("[0-9a-f]{32}")) throw new IOException("Invalid effect digest");
        long size = number(resource, "size", -1);
        if (size <= 0 || size > MAX_PACKAGE) throw new IOException("Invalid effect size");
        Path file = directory.resolve("packages").resolve(md5 + ".ncae");
        byte[] bytes = null;
        if (Files.isRegularFile(file) && Files.size(file) == size) {
            byte[] local = Files.readAllBytes(file);
            if (digest("MD5", local).equals(md5)) bytes = local;
        }
        if (bytes == null) {
            bytes = transport.download(string(resource, "url"), cookie);
            if (bytes == null || bytes.length != size || !digest("MD5", bytes).equals(md5)) throw new IOException("Effect digest mismatch");
        }
        JsonObject decoded = NcaeDecoder.decodeJson(bytes);
        JsonObject effect = object(decoded, "aev2");
        if (effect == null || effect.entrySet().isEmpty()) throw new IOException("Empty song effect");
        // Validate before persisting. The caller can always use the bundled base template.
        writeFile(file, bytes);
        return decoded;
    }

    private Cached read(String key, long now) {
        Path file = directory.resolve("metadata").resolve(key + ".json");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_METADATA) return null;
            JsonObject value = new JsonParser().parse(Files.readString(file)).getAsJsonObject();
            long fetched = number(value, "fetchedAt", -1);
            JsonObject data = object(value, "data");
            // Retain a bounded stale fallback during outages, but don't renew its timestamp.
            if (fetched < 0 || fetched > now || now - fetched > Duration.ofDays(30).toMillis() || data == null) return null;
            return new Cached(data, fetched);
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    private void write(String key, Cached cached) {
        JsonObject value = new JsonObject(); value.addProperty("fetchedAt", cached.fetchedAt); value.add("data", cached.data);
        Path file = directory.resolve("metadata").resolve(key + ".json");
        writeFile(file, value.toString().getBytes(StandardCharsets.UTF_8));
        prune(file.getParent(), 1000);
        prune(directory.resolve("packages"), 1000);
    }

    private static void prune(Path directory, int keep) {
        try (var files = Files.list(directory)) {
            List<Path> paths = files.filter(Files::isRegularFile).sorted(Comparator.comparingLong(AudioEffectService::modified)).toList();
            for (int i = 0; i < paths.size() - keep; i++) Files.deleteIfExists(paths.get(i));
        } catch (IOException ignored) { }
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; }
    }
    private static void writeFile(Path file, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "effect-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { /* A full/read-only cache cannot stop playback. */ }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    static URI resourceUri(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("music.126.net") || host.endsWith(".music.126.net") || host.equals("music.163.com") || host.endsWith(".music.163.com"))
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                throw new IOException("Untrusted effect resource host");
            // Some API records still use http. Credentials must only travel over HTTPS.
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri : URI.create("https" + url.substring(url.indexOf(':')));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid effect resource URL", error); }
    }

    static final class HttpTransport implements Transport {
        @Override public JsonObject request(String endpoint, Map<String, Object> parameters, String cookie) {
            var options = RequestUtil.RequestOptions.builder().cookie(cookie).crypto("").ua("").proxy("").build();
            var response = RequestUtil.createRequest(endpoint, parameters, options);
            if (response == null || response.getStatus() != 200) return null;
            try { return response.toJsonObject(); } catch (RuntimeException ignored) { return null; }
        }
        @Override public byte[] download(String url, String cookie) throws IOException {
            URI uri = resourceUri(url);
            for (int redirect = 0; redirect < 4; redirect++) {
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "NeteaseMusic/9.5.40");
                connection.setRequestProperty("Referer", "https://music.163.com/");
                if (cookie != null && !cookie.isBlank()) connection.setRequestProperty("Cookie", cookie);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Missing resource redirect");
                        uri = resourceUri(uri.resolve(location).toString()); continue;
                    }
                    if (status != 200 || connection.getContentLengthLong() > MAX_PACKAGE) throw new IOException("Effect resource unavailable: " + status);
                    try (InputStream stream = connection.getInputStream()) {
                        byte[] bytes = stream.readNBytes(MAX_PACKAGE + 1);
                        if (bytes.length > MAX_PACKAGE) throw new IOException("Effect resource too large");
                        return bytes;
                    }
                } finally { connection.disconnect(); }
            }
            throw new IOException("Too many resource redirects");
        }
    }
    private static JsonObject copy(JsonObject object) { return object == null ? null : new JsonParser().parse(object.toString()).getAsJsonObject(); }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key); return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
    private static String string(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        try { return value != null && value.isJsonPrimitive() ? value.getAsString() : ""; } catch (RuntimeException ignored) { return ""; }
    }
    private static long number(JsonObject parent, String key, long fallback) {
        try { return parent != null && parent.has(key) ? parent.get(key).getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; }
    }
    static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
