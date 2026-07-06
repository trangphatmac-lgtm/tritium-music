package tritium.desktop.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudStateSnapshotTest {
    @Test
    void smokeSnapshotContainsRenderableState() {
        HudStateSnapshot snapshot = HudStateSnapshot.capture(true);

        assertTrue(snapshot.smoke);
        assertTrue(snapshot.playing);
        assertTrue(snapshot.downloading);
        assertFalse(snapshot.lyrics.isEmpty());
        assertTrue(snapshot.currentLyricIndex >= 0);
        assertTrue(snapshot.spectrum.length > 0);
    }

    @Test
    void emptyRuntimeSnapshotDoesNotThrow() {
        HudStateSnapshot snapshot = HudStateSnapshot.capture(false);

        assertFalse(snapshot.smoke);
        assertFalse(snapshot.playing);
        assertTrue(snapshot.spectrum.length >= 0);
    }
}
