package tritium.desktop;

import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftPreferencesTest {
    @Test void radarOffscreenDefaultsToFalseAndPersistsIndependentlyOfTheSourceOptions() {
        MusicPreferences original = new MusicPreferences(); original.minecraft().ensureInitialized(original);
        assertFalse(original.minecraft().offscreenRadarEnabled());
        original.minecraft().update(c -> {
            var radar = c.find("radar"); radar.enabled = true; radar.openB4.offscreenRendering = true;
            radar.openB4.toggles.put("Show tracer lines", true);
        });
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(original.toJsonObject());
        assertTrue(restored.minecraft().offscreenRadarEnabled());
        assertTrue(restored.minecraft().snapshot().find("radar").openB4.on("Show tracer lines"));
        restored.minecraft().update(c -> c.find("radar").enabled = false);
        assertFalse(restored.minecraft().offscreenRadarEnabled());
        assertTrue(restored.minecraft().snapshot().find("radar").openB4.offscreenRendering);
        var legacy = original.toJsonObject();
        for (var module : legacy.getAsJsonObject("minecraft").getAsJsonArray("modules"))
            module.getAsJsonObject().getAsJsonObject("openB4").remove("offscreenRendering");
        restored.loadFromJsonObject(legacy);
        assertFalse(restored.minecraft().offscreenRadarEnabled());
    }

    @Test void antiBotAndBridgeAssistPersistAcrossDesktopProfilesAndIgnoreHudReset() {
        MusicPreferences original = new MusicPreferences(); original.minecraft().ensureInitialized(original);
        original.minecraft().update(c -> {
            var bot = c.find("antiBot"); bot.enabled = true; bot.key = 48;
            bot.openB4.numbers.put("Delay (spawn)", 1.2); bot.openB4.toggles.put("Tab list", true);
            var bridge = c.find("bridgeAssist"); bridge.enabled = true; bridge.key = 49;
            bridge.openB4.numbers.put("Unsneak delay", 125d);
            bridge.openB4.toggles.put("Disable hotbar scrolling", true);
        });
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(original.toJsonObject());
        Gson json = new Gson();
        for (String id : new String[]{"antiBot", "bridgeAssist"}) {
            String expected = json.toJson(original.minecraft().snapshot().find(id));
            assertEquals(expected, json.toJson(restored.minecraft().snapshot().find(id)));
            restored.minecraft().resetLayout();
            assertEquals(expected, json.toJson(restored.minecraft().snapshot().find(id)));
        }
        var profile = original.toJsonObject(); var legacy = new com.google.gson.JsonArray();
        for (var item : profile.getAsJsonObject("minecraft").getAsJsonArray("modules"))
            if (!java.util.Set.of("antiBot", "bridgeAssist").contains(item.getAsJsonObject().get("id").getAsString())) legacy.add(item);
        profile.getAsJsonObject("minecraft").add("modules", legacy); restored.loadFromJsonObject(profile);
        assertFalse(restored.minecraft().snapshot().find("antiBot").enabled);
        assertFalse(restored.minecraft().snapshot().find("bridgeAssist").enabled);
        assertEquals(50, restored.minecraft().snapshot().find("bridgeAssist").openB4.number("Unsneak delay"));
    }

    @Test void movementSettingsAndSprintPixelLayoutPersistAndMigrate() {
        MusicPreferences original = new MusicPreferences(); original.minecraft().ensureInitialized(original);
        original.minecraft().update(c -> {
            var sprint = c.find("sprint"); sprint.enabled = true; sprint.key = 24;
            sprint.movement.text = "&aRunning"; sprint.movement.displayText = true; sprint.movement.rainbow = true;
            sprint.movement.disableBackwards = false;
            var stasis = c.find("stasis"); stasis.enabled = true; stasis.key = 25; stasis.movement.pulse = 2;
        });
        PlaybackState.Event layout = new PlaybackState.Event(); layout.id = "sprint"; layout.action = "layout";
        layout.x = 195; layout.y = 85; original.minecraft().apply(layout);
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(original.toJsonObject());
        var sprint = restored.minecraft().snapshot().find("sprint");
        assertEquals(195, sprint.movement.textX); assertEquals(85, sprint.movement.textY); assertEquals("&aRunning", sprint.movement.text);
        assertTrue(sprint.enabled && sprint.movement.displayText && sprint.movement.rainbow); assertFalse(sprint.movement.disableBackwards);
        assertEquals(24, sprint.key); assertEquals(25, restored.minecraft().snapshot().find("stasis").key);
        assertEquals(2, restored.minecraft().snapshot().find("stasis").movement.pulse);
        restored.minecraft().resetLayout(); sprint = restored.minecraft().snapshot().find("sprint");
        assertEquals(5, sprint.movement.textX); assertEquals(5, sprint.movement.textY); assertEquals("&aRunning", sprint.movement.text);
        var saved = original.toJsonObject(); var legacy = new com.google.gson.JsonArray();
        for (var item : saved.getAsJsonObject("minecraft").getAsJsonArray("modules"))
            if (!java.util.Set.of("sprint", "stasis").contains(item.getAsJsonObject().get("id").getAsString())) legacy.add(item);
        saved.getAsJsonObject("minecraft").add("modules", legacy); restored.loadFromJsonObject(saved);
        assertFalse(restored.minecraft().snapshot().find("sprint").enabled); assertFalse(restored.minecraft().snapshot().find("stasis").enabled);
        assertEquals(60, restored.minecraft().snapshot().find("stasis").movement.pulse);
    }

    @Test void combatOptionsAndHiddenPersistWithoutParticipatingInHudLayout() {
        MusicPreferences original = new MusicPreferences(); original.minecraft().ensureInitialized(original);
        original.minecrafthidden().setValue(true);
        original.minecraft().update(c -> {
            var aim = c.find("aimAssist"); aim.enabled = true; aim.key = 25;
            aim.combat.numbers.put("speed", 84.0); aim.combat.toggles.put("silentAim", true);
            c.find("autoClicker").combat.numbers.put("inventoryCps", 12.5);
        });
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(original.toJsonObject());
        assertTrue(restored.minecrafthidden().getValue());
        Gson json = new Gson(); String expected = json.toJson(original.minecraft().snapshot().find("aimAssist"));
        restored.minecraft().resetLayout();
        PlaybackState.Event event = new PlaybackState.Event(); event.action = "layout"; event.id = "aimAssist"; event.scale = 2;
        restored.minecraft().apply(event);
        assertEquals(expected, json.toJson(restored.minecraft().snapshot().find("aimAssist")));
        assertEquals(12.5, restored.minecraft().snapshot().find("autoClicker").combat.number("inventoryCps"));
    }

    @Test void oldConfigurationAddsDisabledWorldLyricsWithGaleDefaults() {
        MusicPreferences p = new MusicPreferences(); p.minecraft().ensureInitialized(p);
        JsonObject old = p.toJsonObject();
        var modules = old.getAsJsonObject("minecraft").getAsJsonArray("modules");
        var legacyModules = new com.google.gson.JsonArray();
        for (int i = 0; i < modules.size(); i++)
            if (!modules.get(i).getAsJsonObject().get("id").getAsString().equals("worldLyrics")) legacyModules.add(modules.get(i));
        old.getAsJsonObject("minecraft").add("modules", legacyModules);
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(old);
        var world = restored.minecraft().snapshot().find("worldLyrics");
        assertNotNull(world); assertFalse(world.enabled); assertEquals(0, world.key);
        assertEquals(1, world.scale); assertEquals(0xfff7e1d7, world.color);
        assertEquals(10, world.world.distance); assertEquals(0, world.world.offset);
        assertEquals("Text", world.world.glowColor); assertEquals(.3, world.world.glowStrength);
        assertEquals(.5, world.world.glowRadius); assertEquals(8, world.world.wiggleFps);
        assertEquals(.8, world.world.wiggleAmount); assertEquals(30, world.world.spread);
        assertTrue(world.world.glow && world.world.wiggle && world.world.smoothFont && world.world.wordPop);
        assertTrue(world.world.facePlayer && world.world.keepAway && world.world.splitLines);
        assertFalse(world.world.stayOnScreen);
    }

    @Test void worldEffectsRoundTripAndRemainIndependentOfHudLayout() {
        MusicPreferences p = new MusicPreferences(); p.minecraft().ensureInitialized(p);
        p.minecraft().update(c -> {
            var m = c.find("worldLyrics"); m.enabled = true; m.key = 34; m.scale = 2.75; m.color = 0xff123456;
            var w = m.world; w.offset = -350; w.distance = 24.5; w.glow = false; w.glowColor = "Gold";
            w.glowStrength = 1.2; w.glowRadius = .75; w.smoothFont = false; w.wiggle = false;
            w.wiggleFps = 12; w.wiggleAmount = 1.5; w.wordPop = false; w.facePlayer = false;
            w.keepAway = false; w.stayOnScreen = true; w.spread = 42; w.splitLines = false;
        });
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(p.toJsonObject());
        Gson json = new Gson();
        String expected = json.toJson(p.minecraft().snapshot().find("worldLyrics"));
        assertEquals(expected, json.toJson(restored.minecraft().snapshot().find("worldLyrics")));
        restored.minecraft().resetLayout();
        assertEquals(expected, json.toJson(restored.minecraft().snapshot().find("worldLyrics")));
        PlaybackState.Event layout = new PlaybackState.Event(); layout.action = "layout"; layout.id = "worldLyrics";
        layout.scale = .5; layout.x = .9; layout.width = 1000;
        restored.minecraft().apply(layout);
        assertEquals(expected, json.toJson(restored.minecraft().snapshot().find("worldLyrics")));
        layout.action = "toggle"; layout.enabled = false; restored.minecraft().apply(layout);
        assertFalse(restored.minecraft().snapshot().find("worldLyrics").enabled);
        assertEquals(2.75, restored.minecraft().snapshot().find("worldLyrics").scale);
    }

    @Test void incomingWorldConfigurationIsNormalizedWithoutChangingHudLimits() {
        HudConfig config = new HudConfig(); var m = config.find("worldLyrics");
        m.scale = 100; m.world.offset = Double.NaN; m.world.distance = Double.POSITIVE_INFINITY;
        m.world.glowStrength = -3; m.world.glowRadius = 9; m.world.wiggleFps = 200;
        m.world.wiggleAmount = -1; m.world.spread = 999; m.world.glowColor = "unknown";
        config.find("musicLyrics").scale = 100; config.normalize();
        assertEquals(3, m.scale); assertEquals(2, config.find("musicLyrics").scale);
        assertEquals(0, m.world.offset); assertEquals(10, m.world.distance);
        assertEquals(0, m.world.glowStrength); assertEquals(1, m.world.glowRadius);
        assertEquals(24, m.world.wiggleFps); assertEquals(0, m.world.wiggleAmount);
        assertEquals(60, m.world.spread); assertEquals("Text", m.world.glowColor);
        m.world = null; config.normalize(); assertNotNull(m.world); assertEquals(10, m.world.distance);
    }

    @Test void migratesOnlyLegacyDefaultToastDimensions() {
        MusicPreferences original = new MusicPreferences(); original.minecraft().ensureInitialized(original);
        original.minecraft().update(c -> { var toast = c.find("musicToast"); toast.width = 240; toast.height = 32; toast.scale = 1.5; toast.x = .4; });
        var saved = original.toJsonObject(); saved.getAsJsonObject("minecraft").remove("toastLayoutVersion");
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(saved);
        var toast = restored.minecraft().snapshot().find("musicToast");
        assertEquals(120, toast.width); assertEquals(24, toast.height);
        assertEquals(1.5, toast.scale); assertEquals(.4, toast.x);
        original.minecraft().update(c -> c.find("musicToast").width = 300);
        saved = original.toJsonObject(); saved.getAsJsonObject("minecraft").remove("toastLayoutVersion");
        restored.loadFromJsonObject(saved); assertEquals(300, restored.minecraft().snapshot().find("musicToast").width);
        original.minecraft().update(c -> c.find("musicToast").width = 240);
        restored.loadFromJsonObject(original.toJsonObject());
        assertEquals(240, restored.minecraft().snapshot().find("musicToast").width);
    }
    @Test void inheritsOnceAndPersistsIndependentGameSettings() {
        MusicPreferences p = new MusicPreferences();
        p.hud().musicLyrics().enabled().setValue(true);
        p.hud().musicLyrics().scrollEffect().setValue("SlideIn");
        p.hud().musicSpectrum().multiplier().setValue(2.0);
        p.showRoman().setValue(true);
        p.minecraft().ensureInitialized(p);
        assertTrue(p.minecraft().snapshot().find("musicLyrics").enabled);
        assertEquals("SlideIn", p.minecraft().snapshot().find("musicLyrics").scrollEffect);
        assertTrue(p.minecraft().snapshot().find("musicLyrics").romanization);
        p.minecraft().update(c -> { c.find("musicLyrics").enabled = false; c.find("musicSpectrum").multiplier = .5; c.uninjectKey = 45; c.editKey = 46; });
        p.minecraft().ensureInitialized(p);
        assertTrue(p.hud().musicLyrics().enabled().getValue());
        assertFalse(p.minecraft().snapshot().find("musicLyrics").enabled);
        assertEquals(2, p.hud().musicSpectrum().multiplier().getValue());
        MusicPreferences restored = new MusicPreferences(); restored.loadFromJsonObject(p.toJsonObject());
        restored.minecraft().ensureInitialized(restored);
        assertEquals(.5, restored.minecraft().snapshot().find("musicSpectrum").multiplier);
        assertEquals(45, restored.minecraft().snapshot().uninjectKey);
        assertEquals(46, restored.minecraft().snapshot().editKey);
        assertFalse(p.toJsonObject().getAsJsonObject("minecraft").has("connected"));
        assertEquals(5, restored.minecraft().snapshot().find("musicToast").holdSeconds);
    }
    @Test void clampsIncomingLayoutAndResetKeepsModuleOptions() {
        MusicPreferences p = new MusicPreferences(); p.minecraft().ensureInitialized(p);
        PlaybackState.Event event = new PlaybackState.Event(); event.id = "musicLyrics"; event.action = "layout";
        event.x = 8; event.y = Double.NaN; event.width = -10; event.height = 500; event.scale = 10;
        p.minecraft().apply(event);
        var m = p.minecraft().snapshot().find(event.id);
        assertEquals(1, m.x); assertEquals(0, m.y); assertEquals(100, m.width); assertEquals(2, m.scale);
        p.minecraft().update(c -> c.find(event.id).key = 23);
        p.minecraft().resetLayout();
        assertEquals(23, p.minecraft().snapshot().find(event.id).key);
        assertEquals(450, p.minecraft().snapshot().find(event.id).width);
    }
}
