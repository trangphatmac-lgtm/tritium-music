package tritium.ncm.music.effects;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioEffectControllerTest {
    @TempDir Path directory;
    static class Target implements AudioEffectTarget {
        JsonObject configuration; int updates; boolean closed;
        public void setAudioEffect(JsonObject value) { configuration = value; updates++; }
        public boolean isAudioEffectClosed() { return closed; }
    }
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final AudioEffectServiceTest.FakeTransport transport = new AudioEffectServiceTest.FakeTransport();
    AudioEffectController controller() {
        return new AudioEffectController(new AudioEffectService(directory, transport, () -> 1000), tasks::add);
    }
    static AudioEffectService.Selection off() { return new AudioEffectService.Selection("Off", "Auto", List.of()); }

    @Test void offWinsAgainstAnInFlightResponse() {
        var controller = controller(); var target = new Target();
        controller.register(target, 42, AudioEffectServiceTest.SMART, "");
        assertEquals("loading", controller.status(target).status());
        transport.duringRequest = () -> controller.refresh(off(), "");
        tasks.remove().run();
        assertNull(target.configuration); assertEquals("off", controller.status(target).status());
    }

    @Test void queuedObsoleteRequestsAreSkippedAndLatestManualToneWins() {
        var controller = controller(); var target = new Target();
        controller.register(target, 42, AudioEffectServiceTest.SMART, "");
        controller.refresh(new AudioEffectService.Selection("Master", "key_wide", List.of("en_live")), "");
        var selected = target.configuration;
        tasks.remove().run();
        assertEquals(0, transport.requests); assertEquals(selected, target.configuration);
        assertEquals("masterManual", controller.status(target).status());
    }

    @Test void rebindingOrClosingDeckCannotApplyOldSongResponse() {
        var controller = controller(); var target = new Target();
        controller.register(target, 42, AudioEffectServiceTest.SMART, "");
        controller.register(target, 43, AudioEffectServiceTest.SMART, "");
        tasks.remove().run(); assertEquals(0, transport.requests);
        target.closed = true; int updates = target.updates;
        tasks.remove().run(); assertEquals(updates, target.updates);
        controller.refresh(off(), ""); assertNull(controller.status(target));
    }

    @Test void bothAutoMixDecksRefreshButRemainBoundToTheirOwnSongs() {
        var controller = controller(); var outgoing = new Target(); var incoming = new Target();
        controller.register(outgoing, 42, AudioEffectServiceTest.SMART, "");
        controller.register(incoming, 43, AudioEffectServiceTest.SMART, "");
        tasks.remove().run();
        transport.response = AudioEffectServiceTest.json("{\"code\":200,\"data\":{\"soundKey\":\"2\"}}");
        tasks.remove().run();
        assertNotEquals(outgoing.configuration, incoming.configuration);
        controller.refresh(off(), "");
        assertNull(outgoing.configuration); assertNull(incoming.configuration);
    }

    @Test void loginChangeInvalidatesOldSessionResponse() {
        var controller = controller(); var target = new Target();
        controller.register(target, 42, AudioEffectServiceTest.SMART, "old-session");
        transport.duringRequest = () -> { transport.duringRequest = () -> {}; controller.refresh(AudioEffectServiceTest.SMART, "new-session"); };
        tasks.remove().run();
        assertEquals("loading", controller.status(target).status());
        tasks.remove().run();
        assertEquals("new-session", transport.cookie); assertEquals("smart", controller.status(target).status());
    }
}
