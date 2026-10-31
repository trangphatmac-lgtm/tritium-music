package tritium.ncm.music.effects;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class EmotionEffectServiceTest {
    @TempDir Path directory;
    static final AudioEffectService.Selection EMOTION = AudioEffectService.Selection.automatic("Emotion", "Auto");
    final AtomicLong now = new AtomicLong(1_000_000);

    static JsonObject featureResponse() throws IOException {
        try (var stream = EmotionEffectServiceTest.class.getResourceAsStream("/tritium/audioeffects/emotion/features-300380.json")) {
            return new JsonParser().parse(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    static final class Transport implements AudioEffectService.Transport {
        JsonObject response;
        String endpoint;
        Map<String, Object> parameters;
        int requests;
        boolean offline;
        Runnable duringRequest = () -> {};
        Transport() throws IOException { response = featureResponse(); }
        @Override public JsonObject request(String endpoint, Map<String, Object> parameters, String cookie) throws IOException {
            this.endpoint = endpoint; this.parameters = Map.copyOf(parameters); requests++;
            duringRequest.run();
            if (offline) throw new IOException("offline");
            return response;
        }
        @Override public byte[] download(String url, String cookie) {
            throw new AssertionError("The emotion model must come from the JAR, never from a download");
        }
    }
    AudioEffectService service(Transport transport) { return new AudioEffectService(directory, transport, now::get); }

    @Test void actualBundledModelRunsAfterSongFeatureLookupAndPreservesOriginalTemplate() throws Exception {
        Transport transport = new Transport();
        var result = service(transport).load(300380, EMOTION, "fake-test-session");
        assertEquals("/api/vip-rights/emotion/audio/effect/get", transport.endpoint);
        assertEquals(Map.of("songIds", "[300380]"), transport.parameters);
        assertEquals("emotionModel", result.status());
        assertEquals("calm", result.tone());
        assertTrue(result.configuration().getAsJsonObject("eq").get("on").getAsBoolean());
        assertEquals(10, result.configuration().getAsJsonObject("eq").getAsJsonArray("eqs").size());
        assertTrue(result.configuration().getAsJsonObject("peq").get("on").getAsBoolean());
        var codec = new EmotionEffectCodec();
        var prepared = codec.prepare(transport.response.getAsJsonArray("data").get(0).getAsJsonObject(), 300380);
        assertNotEquals(codec.fallback(prepared), result.configuration(), "Must apply real neural output, not just label a template as inference");
        assertEquals(prepared.template().get("peq"), result.configuration().get("peq"));
    }

    @Test void cachedFeaturesSurviveRestartAndAllowOfflineLocalInference() throws Exception {
        Transport transport = new Transport();
        var service = service(transport);
        var fresh = service.load(300380, EMOTION, "fake-test-session");
        transport.offline = true;
        assertEquals(fresh.configuration(), service(transport).load(300380, EMOTION, "fake-test-session").configuration());
        assertEquals(1, transport.requests);
        now.addAndGet(AudioEffectService.TTL);
        var stale = service.load(300380, EMOTION, "fake-test-session");
        assertEquals("emotionCached", stale.status());
        assertEquals(fresh.configuration(), stale.configuration());
        service.load(300380, EMOTION, "fake-test-session");
        assertEquals(2, transport.requests, "Feature failures use the shared retry backoff");
        now.addAndGet(Duration.ofDays(31).toMillis());
        assertEquals("emotionFallback", service.load(300380, EMOTION, "fake-test-session").status());
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(path.toString().contains("fake-test-session"));
                assertFalse(Files.readString(path).contains("fake-test-session"));
            }
        }
    }

    @Test void loginChangesNeverReuseAnotherAccountsSongFeatureCache() throws Exception {
        Transport transport = new Transport(); var service = service(transport);
        assertEquals("emotionModel", service.load(300380, EMOTION, "session-one").status());
        transport.offline = true;
        assertEquals("emotionFallback", service.load(300380, EMOTION, "session-two").status());
        assertEquals(2, transport.requests);
    }

    @Test void missingFeaturesAndWrongSongNeverInvokeTheModel() throws Exception {
        Transport transport = new Transport(); AtomicInteger calls = new AtomicInteger();
        var service = new AudioEffectService(directory, transport, now::get, (emotion, genre, features, base) -> {
            calls.incrementAndGet(); return base;
        });
        assertEquals("emotionFallback", service.fallback(EMOTION).status());
        assertEquals(0, transport.requests);
        assertEquals("emotionFallback", service.load(7, EMOTION, "").status());
        JsonObject feature = transport.response.getAsJsonArray("data").get(0).getAsJsonObject();
        feature.remove("mel_spectrogram_32");
        assertEquals("emotionTemplate", service.load(300380, EMOTION, "").status());
        assertEquals(0, calls.get());
    }

    @Test void invalidInferenceAndUnavailableRuntimeUseClearlyLabelledTemplateFallback() throws Exception {
        for (boolean unavailable : List.of(false, true)) {
            Transport transport = new Transport();
            var service = new AudioEffectService(directory, transport, now::get, (emotion, genre, features, base) -> {
                if (unavailable) throw new IOException("Model missing");
                base[0] = Float.NaN; return base;
            });
            var result = service.load(300380, EMOTION, "");
            assertEquals("emotionModelFallback", result.status());
            assertEquals("calm", result.tone());
            var codec = new EmotionEffectCodec();
            var prepared = codec.prepare(transport.response.getAsJsonArray("data").get(0).getAsJsonObject(), 300380);
            assertEquals(codec.fallback(prepared), result.configuration());
        }
    }

    @Test void featureMetadataDiscardsUnrelatedFieldsAndRejectsMalformedMelVector() throws Exception {
        JsonObject response = featureResponse();
        JsonObject raw = response.getAsJsonArray("data").get(0).getAsJsonObject();
        raw.addProperty("cookie", "must-not-persist"); raw.addProperty("unexpectedPayload", "must-not-persist");
        JsonObject safe = AudioEffectService.parseMetadata("Emotion", 300380, response);
        assertFalse(safe.has("cookie")); assertFalse(safe.has("unexpectedPayload"));
        assertEquals(32, safe.getAsJsonArray("mel_spectrogram_32").size());
        JsonArray invalid = new JsonArray(); invalid.add(new JsonPrimitive("not-a-number"));
        raw.add("mel_spectrogram_32", invalid);
        assertFalse(AudioEffectService.parseMetadata("Emotion", 300380, response).has("mel_spectrogram_32"));
        assertNull(AudioEffectService.parseMetadata("Emotion", 99, response));
        response.addProperty("code", 403);
        assertNull(AudioEffectService.parseMetadata("Emotion", 300380, response));
    }

    @Test void emotionInferenceCannotOverwriteOffOrNewDeckBinding() throws Exception {
        Transport transport = new Transport(); Queue<Runnable> tasks = new ArrayDeque<>();
        var controller = new AudioEffectController(service(transport), tasks::add);
        var target = new AudioEffectControllerTest.Target();
        controller.register(target, 300380, EMOTION, "");
        transport.duringRequest = () -> controller.refresh(AudioEffectControllerTest.off(), "");
        tasks.remove().run();
        assertNull(target.configuration);
        assertEquals("off", controller.status(target).status());
        transport.duringRequest = () -> {};
        controller.register(target, 300380, EMOTION, "");
        controller.register(target, 99, EMOTION, "");
        int requests = transport.requests;
        tasks.remove().run();
        assertEquals(requests, transport.requests, "Discard a queued lookup for the previous song");
        tasks.remove().run();
        assertEquals("emotionFallback", controller.status(target).status());
    }

    @Test void bothAutoMixDecksUseTheirOwnEmotionFeaturesAndUpdateTogether() throws Exception {
        Transport transport = new Transport(); Queue<Runnable> tasks = new ArrayDeque<>();
        var controller = new AudioEffectController(service(transport), tasks::add);
        var outgoing = new AudioEffectControllerTest.Target(); var incoming = new AudioEffectControllerTest.Target();
        controller.register(outgoing, 300380, EMOTION, "");
        controller.register(incoming, 516818336, EMOTION, "");
        tasks.remove().run();
        transport.response.getAsJsonArray("data").get(0).getAsJsonObject().addProperty("song_id", 516818336);
        tasks.remove().run();
        assertEquals("emotionModel", controller.status(outgoing).status());
        assertEquals("emotionModel", controller.status(incoming).status());
        assertEquals("calm", controller.status(outgoing).tone());
        assertEquals("romantic", controller.status(incoming).tone());
        assertNotEquals(outgoing.configuration, incoming.configuration);
        controller.refresh(AudioEffectControllerTest.off(), "");
        assertNull(outgoing.configuration); assertNull(incoming.configuration);
    }
}
