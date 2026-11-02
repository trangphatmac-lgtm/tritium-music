package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.desktop.MinecraftUninjectPreferences;
import tritium.desktop.MusicPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UninjectActionsTest {
    @TempDir Path directory;

    @Test void hiddenOffKeepsInjectVisibleAndDoesNotExitOrDeleteDespiteSavedOptions() throws Exception {
        var preferences = new MusicPreferences().minecraft().uninject();
        preferences.hideInjectButton().setValue(MinecraftUninjectPreferences.NEXT_REBOOT);
        preferences.autoClose().setValue(true); preferences.selfDestruct().setValue(true);
        Path marker = Files.writeString(directory.resolve("visibility"), "boot");
        var visibility = new InjectButtonVisibility(marker, "boot");
        List<Boolean> exits = new ArrayList<>();
        var actions = new UninjectActions(visibility, exits::add);

        var plan = actions.requested(preferences, false);
        assertEquals(new UninjectActions.Plan(MinecraftUninjectPreferences.OFF, false, false), plan);
        assertFalse(visibility.hidden()); assertFalse(Files.exists(marker));
        actions.completed(plan);
        assertTrue(exits.isEmpty());
        assertEquals(MinecraftUninjectPreferences.NEXT_REBOOT, preferences.hideInjectButton().getValue());
        assertTrue(preferences.autoClose().getValue()); assertTrue(preferences.selfDestruct().getValue());

        var enabledPlan = actions.requested(preferences, true);
        assertTrue(visibility.hidden()); assertEquals("boot", Files.readString(marker));
        actions.completed(enabledPlan);
        assertEquals(List.of(true), exits);
    }

    @Test void hiddenOnSnapshotsTheSelectedActionsUntilUninjectCompletes() {
        var preferences = new MusicPreferences().minecraft().uninject();
        preferences.hideInjectButton().setValue(MinecraftUninjectPreferences.NEXT_LAUNCH);
        preferences.autoClose().setValue(true);
        var visibility = new InjectButtonVisibility(directory.resolve("visibility"), "boot");
        List<Boolean> exits = new ArrayList<>();
        var actions = new UninjectActions(visibility, exits::add);

        var plan = actions.requested(preferences, true);
        assertTrue(visibility.hidden()); assertTrue(exits.isEmpty());
        preferences.autoClose().setValue(false); preferences.selfDestruct().setValue(true);
        actions.completed(plan);
        assertEquals(List.of(false), exits);
    }
}
