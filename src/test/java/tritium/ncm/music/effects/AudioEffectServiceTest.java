package tritium.ncm.music.effects;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AudioEffectServiceTest {
    @TempDir Path directory;
    final AtomicLong now = new AtomicLong(1_000_000);
    static final AudioEffectService.Selection SMART = new AudioEffectService.Selection("Smart", "Auto", List.of());
    static final AudioEffectService.Selection MASTER = new AudioEffectService.Selection("Master", "Auto", List.of());

    static class FakeTransport implements AudioEffectService.Transport {
        int requests, downloads;
        String cookie;
        JsonObject response = json("{\"code\":200,\"data\":{\"soundKey\":\"1\"}}");
        byte[] bytes;
        Runnable duringRequest = () -> {};
        boolean fail;
        @Override public JsonObject request(String endpoint, Map<String, Object> params, String cookie) throws IOException {
            requests++; this.cookie = cookie; duringRequest.run();
            if (fail) throw new IOException("offline");
            return response;
        }
        @Override public byte[] download(String url, String cookie) throws IOException {
            downloads++; this.cookie = cookie;
            if (fail) throw new IOException("offline");
            return bytes;
        }
    }
    AudioEffectService service(FakeTransport transport) { return new AudioEffectService(directory, transport, now::get); }
    static JsonObject json(String text) { return new JsonParser().parse(text).getAsJsonObject(); }

    @Test void recommendationsAreCachedAcrossRestartsAndScopedToSessionWithoutSavingCookie() throws Exception {
        var transport = new FakeTransport(); var service = service(transport);
        var result = service.load(42, SMART, "test-session-A");
        assertEquals("smart", result.status());
        assertEquals(new EffectPresets().smart("1"), result.configuration());
        assertEquals("test-session-A", transport.cookie);
        result.configuration().addProperty("mutation", true);
        assertFalse(service.load(42, SMART, "test-session-A").configuration().has("mutation"));
        service(transport).load(42, SMART, "test-session-A");
        assertEquals(1, transport.requests);
        service.load(42, SMART, "test-session-B");
        assertEquals(2, transport.requests);
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains("test-session"));
                assertFalse(path.toString().contains("test-session"));
            }
        }
    }

    @Test void failureUsesStaleCacheAndBackoffWithoutRenewingTimestamp() {
        var transport = new FakeTransport(); var service = service(transport);
        service.load(42, SMART, "");
        now.addAndGet(AudioEffectService.TTL); transport.fail = true;
        var stale = service.load(42, SMART, "");
        assertEquals("smartFallback", stale.status());
        assertEquals(new EffectPresets().smart("1"), stale.configuration());
        service.load(42, SMART, ""); assertEquals(2, transport.requests);
        now.addAndGet(AudioEffectService.RETRY_DELAY); service.load(42, SMART, "");
        assertEquals(3, transport.requests);
        now.addAndGet(java.time.Duration.ofDays(31).toMillis());
        assertEquals(new EffectPresets().smart("0"), service.load(42, SMART, "").configuration());
    }

    @Test void noResultAndUnknownPresetUseBundledFallback() {
        var transport = new FakeTransport(); transport.response = json("{\"code\":403}");
        var service = service(transport);
        assertEquals("masterFallback", service.load(42, MASTER, "").status());
        transport.response = json("{\"code\":200,\"data\":{\"soundKey\":\"unknown\"}}");
        assertEquals(new EffectPresets().smart("0"), service.load(42, SMART, "").configuration());
        assertEquals("smartFallback", service.load(42, SMART, "").status());
    }

    @Test void verifiedSongPackageIsDecodedComposedAndReusableOffline() throws Exception {
        var transport = songTransport(); var service = service(transport);
        var selected = AudioEffectService.Selection.automatic("Master", "Auto");
        var result = service.load(300380, selected, "session");
        assertEquals("masterAuto", result.status()); assertEquals("key_balanced", result.tone());
        assertEquals(List.of("en_bass", "en_vocal", "en_live"), result.usedDiy());
        assertEquals(-.53, result.configuration().getAsJsonObject("eq").getAsJsonArray("eqs").get(0).getAsDouble());
        assertTrue(result.configuration().getAsJsonObject("rvb").get("on").getAsBoolean());
        transport.fail = true;
        assertEquals(result.configuration(), service(transport).load(300380, selected, "session").configuration());
        assertEquals(1, transport.requests); assertEquals(1, transport.downloads);
    }

    @Test void invalidPackageCannotReplaceTemplateAndHasRetryBackoff() throws Exception {
        var transport = songTransport(); transport.bytes[30] ^= 1;
        var service = service(transport);
        var result = service.load(300380, MASTER, "session");
        assertEquals("masterFallback", result.status());
        var manual = new AudioEffectService.Selection("Master", "key_balanced", List.of());
        assertEquals(service.fallback(manual).configuration(), result.configuration());
        service.load(300380, MASTER, "session"); assertEquals(1, transport.downloads);
        now.addAndGet(AudioEffectService.RETRY_DELAY); service.load(300380, MASTER, "session");
        assertEquals(2, transport.downloads);
        assertFalse(Files.exists(directory.resolve("packages")));
    }

    @Test void offAndManualToneDoNotRequestNetwork() {
        var transport = new FakeTransport(); var service = service(transport);
        assertNull(service.load(42, new AudioEffectService.Selection("Off", "Auto", List.of()), "").configuration());
        assertEquals("masterManual", service.load(42, new AudioEffectService.Selection("Master", "key_wide", List.of("en_live")), "").status());
        assertEquals(0, transport.requests);
    }

    @Test void metadataSelectsMatchingSongAndDiscardsUnneededFields() {
        var response = json("{\"code\":200,\"data\":[{\"id\":7,\"baseKey\":\"key_warm\"},{\"id\":8,\"baseKey\":\"key_wide\",\"longText\":\"ignored\"}]}");
        var metadata = AudioEffectService.parseMetadata("Master", 8, response);
        assertEquals("key_wide", metadata.get("baseKey").getAsString()); assertFalse(metadata.has("longText"));
        assertNull(AudioEffectService.parseMetadata("Master", 9, response));
    }

    @Test void resourceRequestsRestrictCredentialsToHttpsMusicHosts() throws Exception {
        assertEquals("https://p1.music.126.net/a", AudioEffectService.resourceUri("http://p1.music.126.net/a").toString());
        for (String url : List.of("https://music.126.net.evil.example/a", "https://evil.example/a", "file:///tmp/a",
                "https://user@p1.music.126.net/a", "https://p1.music.126.net:444/a")) {
            assertThrows(IOException.class, () -> AudioEffectService.resourceUri(url));
        }
    }

    static FakeTransport songTransport() throws Exception {
        var transport = new FakeTransport();
        try (var stream = AudioEffectServiceTest.class.getResourceAsStream("/tritium/audioeffects/master-song-300380.ncae")) {
            transport.bytes = Objects.requireNonNull(stream).readAllBytes();
        }
        transport.response = json("{\"code\":200,\"data\":[{\"id\":300380,\"baseKey\":\"key_balanced\",\"effect\":{\"url\":\"https://p1.music.126.net/test\",\"size\":"
                + transport.bytes.length + ",\"md5\":\"" + AudioEffectService.digest("MD5", transport.bytes) + "\"}}]}");
        return transport;
    }
}
