package tritium.minecraft.protocol;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenB4SettingsTest {
    @Test void allSourceOptionsAndCategoriesSurviveImportIncludingModesAndSentinels() throws Exception {
        JsonArray source = source("/openb4-modules.json");
        assertEquals(35, source.size());
        JsonArray ravenBs = source("/ravenbs-modules.json");
        assertEquals(1, ravenBs.size());
        for (JsonElement module : ravenBs) source.add(module);
        assertEquals(source.size(), OpenB4Settings.definitions().size());
        HudConfig config = new HudConfig();
        for (JsonElement entry : source) {
            JsonObject original = entry.getAsJsonObject(); String id = original.get("id").getAsString();
            OpenB4Settings.Definition definition = OpenB4Settings.definition(id);
            assertNotNull(definition, id); assertEquals(original.get("category").getAsString(), definition.category.id);
            assertEquals(definition.category, config.find(id).category()); assertTrue(config.find(id).isHidden());
            assertFalse(config.find(id).isHud()); assertFalse(config.find(id).enabled);
            JsonArray settings = original.getAsJsonArray("settings"); assertEquals(settings.size(), definition.options.size(), id);
            for (int i = 0; i < settings.size(); i++) {
                JsonObject expected = settings.get(i).getAsJsonObject(); OpenB4Settings.Option option = definition.options.get(i);
                assertEquals(expected.get("name").getAsString(), option.key); assertEquals(expected.get("t").getAsString(), option.kind);
                if (option.numeric) {
                    assertEquals(expected.get("value").getAsDouble(), config.find(id).openB4.number(option.key));
                    assertEquals(expected.get("min").getAsDouble(), option.min); assertEquals(expected.get("max").getAsDouble(), option.max);
                    assertEquals(expected.get("step").getAsDouble(), option.step);
                    assertEquals(expected.get("prefix").getAsString(), option.prefix); assertEquals(expected.get("suffix").getAsString(), option.suffix);
                    if (!expected.get("options").isJsonNull()) {
                        JsonArray choices = expected.getAsJsonArray("options"); assertEquals(choices.size(), option.choices.size());
                        for (int j = 0; j < choices.size(); j++) assertEquals(choices.get(j).getAsString(), option.choices.get(j));
                    }
                    if (!expected.get("labels").isJsonNull()) for (Map.Entry<String,JsonElement> label : expected.getAsJsonObject("labels").entrySet())
                        assertEquals(label.getValue().getAsString(), option.labels.get(Double.valueOf(label.getKey())));
                } else if (option.toggle) assertEquals(expected.get("value").getAsBoolean(), config.find(id).openB4.on(option.key));
            }
        }
    }
    @Test void oldConfigsGetDefaultsAndNonFiniteOrInvertedRangesAreNormalized() {
        HudConfig config = new Gson().fromJson("{\"modules\":[{\"id\":\"safeWalk\",\"enabled\":true,\"openB4\":null}]}", HudConfig.class);
        config.normalize(); assertEquals(46, config.modules.length); assertTrue(config.find("safeWalk").enabled);
        assertFalse(config.find("antiBot").enabled); assertFalse(config.find("bridgeAssist").enabled);
        assertEquals(0, config.find("safeWalk").openB4.number("Mode"));
        OpenB4Settings reach = config.find("reach").openB4;
        reach.numbers.put("Min", 6.0); reach.numbers.put("Max", 3.0); config.normalize(); assertTrue(reach.number("Min") <= reach.number("Max"));
        reach.numbers.put("Max", Double.NaN); config.normalize(); assertEquals(3.3, reach.number("Max"), .00001);
        OpenB4Settings walk = config.find("safeWalk").openB4; walk.numbers.put("Mode", 1000d); config.normalize(); assertEquals(3, walk.number("Mode"));
        config.find("targetHud").openB4.offsetX = -243; config.find("targetHud").openB4.offsetY = 124;
        config.openB4Friends = new String[]{"Friend"}; config.openB4Enemies = new String[]{"Enemy"};
        HudConfig saved = new Gson().fromJson(new Gson().toJson(config), HudConfig.class); saved.normalize();
        assertEquals(-243, saved.find("targetHud").openB4.offsetX); assertEquals(124, saved.find("targetHud").openB4.offsetY);
        assertArrayEquals(config.openB4Friends, saved.openB4Friends); assertArrayEquals(config.openB4Enemies, saved.openB4Enemies);
    }
    @Test void antiBotAndBridgeAssistKeepSourceDefaultsUnitsAndSavedValues() {
        HudConfig config = new HudConfig();
        assertEquals(ModuleCategory.WORLD, config.find("antiBot").category());
        assertEquals(7, OpenB4Settings.options("antiBot").size());
        assertEquals("Disabled", OpenB4Settings.options("antiBot").get(0).format(0));
        assertEquals(79, config.find("antiBot").openB4.number("Pit spawn"));
        assertTrue(config.find("antiBot").openB4.on("^ Disable in duels"));
        assertTrue(config.find("antiBot").openB4.on("Filter watchdog"));
        assertEquals(ModuleCategory.PLAYER, config.find("bridgeAssist").category());
        assertEquals(11, OpenB4Settings.options("bridgeAssist").size());
        assertEquals("50ms", OpenB4Settings.options("bridgeAssist").get(1).format(50));
        config.find("bridgeAssist").enabled = true;
        config.find("bridgeAssist").openB4.numbers.put("Edge offset", .146);
        config.find("bridgeAssist").openB4.numbers.put("Unsneak delay", 103d);
        config.find("bridgeAssist").openB4.toggles.put("Pre place", true);
        config.find("antiBot").openB4.numbers.put("Delay (spawn)", 1.34);
        config.normalize();
        HudConfig saved = new Gson().fromJson(new Gson().toJson(config), HudConfig.class); saved.normalize();
        assertTrue(saved.find("bridgeAssist").enabled);
        assertTrue(saved.find("bridgeAssist").openB4.on("Pre place"));
        assertEquals(.15, saved.find("bridgeAssist").openB4.number("Edge offset"));
        assertEquals(105, saved.find("bridgeAssist").openB4.number("Unsneak delay"));
        assertEquals(1.4, saved.find("antiBot").openB4.number("Delay (spawn)"));
    }
    private JsonArray source(String resource) throws IOException {
        try (Reader in = new InputStreamReader(getClass().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(in).getAsJsonArray();
        }
    }
}
