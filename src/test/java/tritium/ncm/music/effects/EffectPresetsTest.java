package tritium.ncm.music.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EffectPresetsTest {
    @Test void loadsVerifiedPresetsAndEffectiveVersionTwo() {
        EffectPresets presets = new EffectPresets();
        assertEquals(32, presets.masterNames().size());
        assertEquals(32, presets.masterBase().entrySet().size());
        assertEquals(5, presets.diy().entrySet().size());
        assertEquals("通透", presets.masterNames().get("key_transparent"));
        JsonObject smart = presets.smart("13");
        assertFalse(smart.has("aev2"));
        assertTrue(smart.getAsJsonObject("rvb").get("on").getAsBoolean());
        assertEquals(-14, smart.getAsJsonObject("cmp").get("threshold").getAsDouble());
        assertEquals(10, smart.getAsJsonObject("eq").getAsJsonArray("eqs").size());
        assertTrue(smart.getAsJsonObject("peq").get("on").getAsBoolean());
    }

    @Test void defaultsMatchRecoveredSmartAndMasterFallbacks() {
        EffectPresets presets = new EffectPresets();
        assertEquals(presets.smart("0"), presets.smart("not-a-key"));
        assertEquals(presets.smart("0"), presets.smart(null));
        assertEquals(presets.master("key_transparent"), presets.master("not-a-key"));
        assertEquals(presets.master("key_transparent"), presets.master(null));
    }

    @Test void callersCannotModifySubsequentPresetsOrNames() {
        EffectPresets presets = new EffectPresets();
        JsonObject first = presets.smart("13");
        JsonObject original = presets.smart("13");
        first.getAsJsonObject("eq").addProperty("on", false);
        first.getAsJsonObject("eq").getAsJsonArray("eqs").add(new com.google.gson.JsonPrimitive(99));
        presets.masterBase().remove("key_transparent");
        presets.diy().remove("en_bass");
        assertEquals(original, presets.smart("13"));
        assertTrue(presets.masterBase().has("key_transparent"));
        assertTrue(presets.diy().has("en_bass"));
        assertThrows(UnsupportedOperationException.class, () -> presets.masterNames().put("x", "y"));
    }
}
