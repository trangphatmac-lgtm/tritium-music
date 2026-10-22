package tritium.desktop;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPreferencesTest {
    @Test void oldProfilesKeepListAndAllNavigationValuesRoundTrip() {
        MusicPreferences prefs=new MusicPreferences();
        prefs.loadFromJsonObject(new JsonParser().parse("{}").getAsJsonObject());
        var nav=prefs.navigation();
        assertTrue(nav.isList());
        assertFalse(nav.isFullscreen());
        nav.mode().setValue("Coverflow"); nav.coverflow().setValue("Card"); nav.cardStyle().setValue("image");
        nav.minScale().setValue(.6); nav.minOpacity().setValue(.7); nav.squareCards().setValue(true); nav.fullBleed().setValue(true);
        nav.wallLook().setValue("partial"); nav.wallWindows().setValue(5.0); nav.stackEdges().setValue(false);
        nav.backdropBlur().setValue(true); nav.backdropLyrics().setValue(true);
        nav.remember("mine","playlist:42");
        MusicPreferences restored=new MusicPreferences(); restored.loadFromJsonObject(prefs.toJsonObject());
        assertEquals(nav.toJsonObject(),restored.navigation().toJsonObject());
        assertEquals("playlist:42",restored.navigation().position("mine"));
        nav.coverflow().setValue("OldSchool"); assertTrue(nav.isFullscreen());
        nav.coverflow().setValue("Mirror"); assertTrue(nav.isFullscreen());
        nav.mode().setValue("Wall"); assertFalse(nav.isFullscreen()); assertTrue(nav.isWall());
    }
    @Test void malformedSettingsAreIsolatedAndFalloffCanBeReset() {
        var nav=new NavigationPreferences();
        nav.loadFromJsonObject(new JsonParser().parse("{\"mode\":\"bad\",\"coverflow\":[],\"minScale\":-5,\"minOpacity\":\"NaN\",\"wallWindows\":99,\"backdropBlur\":true}").getAsJsonObject());
        assertTrue(nav.isList()); assertEquals(.2,nav.minScale().getValue()); assertEquals(.4,nav.minOpacity().getValue());
        assertEquals(6,nav.wallWindows().getValue()); assertTrue(nav.backdropBlur().getValue());
        nav.resetFalloff(); assertEquals(.45,nav.minScale().getValue()); assertEquals(.4,nav.minOpacity().getValue());
        nav.rememberPosition().setValue(false); nav.remember("mine","ignored"); assertEquals("",nav.position("mine"));
    }
}
