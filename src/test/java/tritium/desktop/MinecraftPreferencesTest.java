package tritium.desktop;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.PlaybackState;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftPreferencesTest {
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
