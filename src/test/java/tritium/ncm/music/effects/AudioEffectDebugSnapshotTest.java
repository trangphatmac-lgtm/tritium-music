package tritium.ncm.music.effects;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class AudioEffectDebugSnapshotTest {
    @TempDir Path directory;
    static class Target implements AudioEffectTarget {
        final AudioEffectDsp dsp = new AudioEffectDsp(48_000);
        int updates;
        boolean closed;
        public void setAudioEffect(JsonObject config) { dsp.setConfiguration(config); updates++; }
        public boolean isAudioEffectClosed() { return closed; }
        public AudioEffectDsp.DebugSnapshot audioEffectDebugSnapshot() { return dsp.debugSnapshot(); }
    }

    @Test void pollingIsReadOnlyAndKeepsBothDecksAndObsoleteResponsesConsistent() {
        var transport = new AudioEffectServiceTest.FakeTransport();
        Queue<Runnable> tasks = new ArrayDeque<>();
        var controller = new AudioEffectController(new AudioEffectService(directory, transport, () -> 1000), tasks::add);
        var a = new Target(); var b = new Target();
        var smart = AudioEffectServiceTest.SMART;
        controller.register(a, 42, smart, "private-session");
        controller.register(b, 43, smart, "private-session");
        var loading = controller.debugSnapshot(a, smart);
        for (int i = 0; i < 10; i++) assertEquals(loading, controller.debugSnapshot(a, smart));
        assertEquals(0, transport.requests);
        assertEquals(1, a.updates);
        assertEquals(2, tasks.size());
        assertEquals(List.of(42L, 43L), loading.decks().stream().map(AudioEffectDebugSnapshot.Deck::songId).toList());
        assertTrue(loading.decks().getFirst().current());
        assertEquals("loading", loading.decks().getFirst().result().status());
        assertEquals("0", loading.decks().getFirst().result().diagnostics().get("soundKey"));
        tasks.remove().run();
        var matched = controller.debugSnapshot(a, smart);
        assertEquals("smart", matched.decks().getFirst().result().status());
        assertEquals("1", matched.decks().getFirst().result().diagnostics().get("soundKey"));
        var off = AudioEffectControllerTest.off();
        controller.refresh(off, "private-session");
        tasks.remove().run();
        var disabled = controller.debugSnapshot(b, off);
        assertEquals(43, disabled.decks().getFirst().songId());
        for (var deck : disabled.decks()) {
            assertEquals("Off", deck.selection().mode());
            assertEquals("off", deck.result().status());
            assertEquals(2, deck.revision());
            assertFalse(deck.dsp().active());
        }
        assertEquals("loading", loading.decks().getFirst().result().status(), "Old snapshots remain unchanged");
        assertEquals(1, transport.requests, "Queued obsolete lookup must remain skipped");
        assertFalse(disabled.toString().contains("private-session"));
        b.closed = true;
        assertEquals(1, controller.debugSnapshot(a, off).decks().size());
    }

    @Test void diagnosticsUseCompiledClampsAndStageOrderAndDoNotChangePcm() {
        var dsp = new AudioEffectDsp(48_000);
        assertFalse(dsp.debugSnapshot().active());
        var config = AudioEffectServiceTest.json("{order:['cmp','eq','cmp'],eq:{on:true,eqs:[99]},cmp:{on:true,threshold:-200,attack:0.01},limiter:{on:true}}");
        dsp.setConfiguration(config);
        var snapshot = dsp.debugSnapshot();
        assertEquals(List.of("cmp", "eq", "limiter"), snapshot.modules().stream().map(AudioEffectDsp.Module::name).toList());
        assertEquals(-80, snapshot.modules().getFirst().parameters().getFirst().value());
        assertEquals(10, snapshot.modules().getFirst().parameters().get(2).value(), .000001);
        assertEquals(24, snapshot.modules().get(1).parameters().getFirst().value());
        config.getAsJsonObject("eq").addProperty("on", false);
        assertTrue(snapshot.active());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.modules().clear());
        dsp.reset();
        assertSame(snapshot, dsp.debugSnapshot());
        var reference = new AudioEffectDsp(48_000);
        config.getAsJsonObject("eq").addProperty("on", true);
        reference.setConfiguration(config); reference.reset();
        double[] input = new double[2048], left = new double[2048], right = new double[2048], expected = new double[2048];
        input[0] = .4;
        for (int i = 0; i < 10; i++) dsp.debugSnapshot();
        dsp.process(input, input, left, right, 0, input.length);
        reference.process(input, input, expected, new double[2048], 0, input.length);
        assertArrayEquals(expected, left, 0);
        dsp.setConfiguration(null);
        assertFalse(dsp.debugSnapshot().active());
        assertTrue(dsp.debugSnapshot().modules().isEmpty());
    }

    @Test void emotionReportsTemplateEligibilityAndSafeFailureReason() throws Exception {
        var transport = new EmotionEffectServiceTest.Transport();
        var service = new AudioEffectService(directory, transport, () -> 1000,
                (emotion, genre, features, base) -> { throw new IllegalStateException("private error details"); });
        var selection = EmotionEffectServiceTest.EMOTION;
        var fallback = service.fallback(selection);
        assertEquals("false", fallback.diagnostics().get("inferenceEligible"));
        assertTrue(fallback.diagnostics().get("reason").startsWith("missing_audio_features:"));
        var result = service.load(300380, selection, "private-session");
        assertEquals("emotionModelFallback", result.status());
        assertEquals("true", result.diagnostics().get("inferenceEligible"));
        assertTrue(result.diagnostics().get("template").startsWith("calm:"));
        assertEquals("IllegalStateException", result.diagnostics().get("reason"));
        assertFalse(result.diagnostics().toString().contains("private"));
    }
}
