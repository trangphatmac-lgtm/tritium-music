package tritium.desktop.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.desktop.DesktopPreferenceStore;
import tritium.desktop.MusicPreferences;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpSettingsTest {
    @TempDir Path directory;

    @Test void exposesUiChoicesAndAppliesThroughExistingCallbacksAndPersistence() {
        var preferences = new MusicPreferences();
        var settings = new McpSettings(preferences);
        var changes = new AtomicInteger();
        var store = new DesktopPreferenceStore(directory.resolve("preferences.json"));
        preferences.setChangeCallback(() -> {
            changes.incrementAndGet();
            store.save(preferences);
        });
        assertEquals(2, settings.snapshot().getAsJsonObject("ui.appearance").getAsJsonObject("schema").getAsJsonArray("enum").size());
        settings.update(json("""
                {"ui.appearance":"Light","lyrics.showTranslation":false,"background.mode":"shader",
                "background.shader":"aurora","navigation.mode":"Wall","audioEffects.mode":"Manual",
                "audioEffects.manual.bass":true,"mcp.port":19000,"mcp.enabled":true}
                """));
        assertEquals(9, changes.get());
        var restored = new MusicPreferences();
        store.load(restored);
        assertEquals("Light", restored.appearance().getValue());
        assertFalse(restored.showTranslation().getValue());
        assertEquals("aurora", restored.background().shader().getValue());
        assertEquals("Wall", restored.navigation().mode().getValue());
        assertTrue(restored.audioEffects().bass().getValue());
        assertTrue(restored.mcpEnabled().getValue());
        assertEquals(19000, restored.mcpPort().getValue());
    }

    @Test void rejectsEntireInvalidPatchBeforeAnyMutationAndChecksAliases() {
        var preferences = new MusicPreferences();
        var settings = new McpSettings(preferences);
        for (String invalid : new String[]{
                "{\"ui.appearance\":\"Light\",\"player.volume\":2}",
                "{\"ui.appearance\":\"Light\",\"lyrics.showTranslation\":\"false\"}",
                "{\"ui.appearance\":\"Unknown\"}", "{\"unknown\":true}",
                "{\"mcp.port\":1234.5}", "{\"mcp.port\":65536}", "{\"mcp.port\":80}",
                "{\"ui.accentColor\":4294967296}", "{\"player.volume\":null}",
                "{\"spectrum.absoluteVolume\":true,\"hud.musicSpectrum.absoluteVolume\":false}", "{}"}) {
            assertThrows(IllegalArgumentException.class, () -> settings.update(json(invalid)), invalid);
            assertEquals("Dark", preferences.appearance().getValue());
            assertEquals(.1, preferences.volume().getValue());
        }
    }

    @Test void handlesUnsignedColorsAndExistingManualMutualExclusion() {
        var preferences = new MusicPreferences();
        var settings = new McpSettings(preferences);
        settings.update(json("{\"ui.accentColor\":4294967295,\"hud.musicSpectrum.color\":4294967295,\"audioEffects.manual.vibe\":true}"));
        assertEquals(-1, preferences.accentColor().getValue());
        assertEquals(-1, preferences.hud().musicSpectrum().color().getValue());
        settings.update(json("{\"audioEffects.manual.live\":true}"));
        assertFalse(preferences.audioEffects().vibe().getValue());
        assertTrue(preferences.audioEffects().live().getValue());
    }

    @Test void oldPreferencesDefaultToDisabledAndCopiedConfigurationContainsEndpoint() {
        var preferences = new MusicPreferences();
        assertFalse(preferences.mcpEnabled().getValue());
        assertEquals(17863, preferences.mcpPort().getValue());
        var config = json(McpService.clientConfiguration(19000));
        var connection = config.getAsJsonObject("mcpServers").getAsJsonObject("tritium-music");
        assertEquals("http", connection.get("type").getAsString());
        assertEquals("http://127.0.0.1:19000/mcp", connection.get("url").getAsString());
    }

    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
}
