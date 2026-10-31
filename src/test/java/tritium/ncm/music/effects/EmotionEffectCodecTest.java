package tritium.ncm.music.effects;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmotionEffectCodecTest {
    private final EmotionEffectCodec bundled = new EmotionEffectCodec();
    private final EmotionEffectCodec plain = new EmotionEffectCodec(new JsonObject(), false);

    @Test void recoveredSongFixtureUsesBundledOverrideAndExactInputLayout() throws Exception {
        EmotionEffectCodec.Prepared p = bundled.prepare(song(),300380);
        assertTrue(p.inferenceEligible(),p.reason()); assertEquals("calm",p.label());
        assertEquals(10,p.emotionIndex()); assertEquals(0,p.genreIndex());
        assertEquals("calm:pop-family",p.templateKey());
        assertArrayEquals(new float[]{2,0,-26,1,.8f,0,0,.2f,0},java.util.Arrays.copyOfRange(p.base(),10,19));
        float[] f=p.features(); assertEquals(64,f.length);
        assertArrayEquals(new float[]{7.1f,-15.5f,.2833f,134,-15.0557f,236.65f,.71f,.24f,20.32f,.88f,.59f,.48f,.24f,.13f,.14f,1,0,1},java.util.Arrays.copyOfRange(f,32,50));
        for(int i=50;i<64;i++)assertEquals(0,f[i]);
        for(int i=19;i<32;i++)assertEquals(0,p.base()[i]);
        JsonObject rvb=p.template().getAsJsonObject("rvb");
        assertEquals(-30,rvb.getAsJsonObject("ol").get("er").getAsInt());
        assertEquals(-8,rvb.getAsJsonObject("tc").getAsJsonArray("f").get(0).getAsJsonObject().get("gain").getAsInt());
    }

    @Test void noOverrideUsesFirstMatchingSynonymAndDoesNotCopyPeqIntoModelEq() throws Exception {
        var p=plain.prepare(song(),300380);
        assertEquals("energetic",p.label()); assertEquals(3,p.emotionIndex());
        assertArrayEquals(new float[]{.9f,0,-26,.38f,.55f,0,0,4,1.5f},java.util.Arrays.copyOfRange(p.base(),10,19));
        for(int i=0;i<10;i++)assertEquals(0,p.base()[i]);
        assertEquals(6,p.template().getAsJsonObject("peq").getAsJsonArray("f").get(0).getAsJsonObject().get("type").getAsInt());
        JsonObject input=song();input.addProperty("emotion_label","动感,治愈");
        assertEquals("healing",plain.prepare(input,1).label()); // business label pass precedes synonym pass
    }

    @Test void everyEmotionFamilyHasAnIndependentOriginalTemplate() throws Exception {
        assertEquals(64,plain.templateKeys().size());
        for(String key:plain.templateKeys()) {
            String[] parts=key.split(":");JsonObject input=song();input.addProperty("emotion_label",parts[0]);
            input.addProperty("genre_id",switch(parts[1]){case "folk-family"->1003;case "rhythm-family"->1017;case "instrumental-family"->1022;default->1000;});
            var p=plain.prepare(input,1);assertEquals(key,p.templateKey());assertTrue(p.inferenceEligible());
            assertEquals(32,p.base().length);for(float value:p.base())assertTrue(Float.isFinite(value),key);
            JsonObject fallback=plain.fallback(p);assertEquals(p.template().get("order"),fallback.get("order"),key);assertTrue(fallback.has("eq"),key);
            // Editing a returned tree or tensor must not contaminate future songs.
            p.template().addProperty("effect_name","corrupted");p.base()[0]=1000;p.features()[0]=1000;
            assertNotEquals(new JsonPrimitive("corrupted"),plain.prepare(input,1).template().get("effect_name"));
        }
    }

    @Test void nullAndPartialFeaturesSelectSafeFallbackAndExplainMissingInputs() {
        var p=bundled.prepare(null,0);assertFalse(p.inferenceEligible());assertEquals("default",p.label());
        assertTrue(p.reason().contains("missing_audio_features"));assertTrue(bundled.fallback(p).has("order"));
        // Original classifier still labels a partial response from available mel/BPM.
        assertEquals("ecstatic",plain.prepare(json("{\"repbpm\":120}"),0).label());
        assertEquals("sad",plain.prepare(json("{\"repbpm\":75}"),0).label());
        assertEquals("default",plain.prepare(json("{\"emotion_label\":\"无合适业务标签\",\"loudness_lufs\":-10}"),0).label());
    }

    @Test void invalidAndMissingOptionalModelScalarsDisableOriginalInference() throws Exception {
        for(String key:List.of("vocal_main_freq","other_instrument_energy_ratio","dynamic_range")) {
            JsonObject input=song();input.remove(key);var p=plain.prepare(input,1);
            assertFalse(p.inferenceEligible());assertTrue(p.reason().contains(key));
        }
        JsonObject input=song();input.addProperty("dynamic_range",31);assertFalse(plain.prepare(input,1).inferenceEligible());
        input=song();input.addProperty("dynamic_range","7.1");assertFalse(plain.prepare(input,1).inferenceEligible());
        input=song();JsonArray invalidMel=new JsonArray();for(int i=0;i<32;i++)invalidMel.add(new JsonPrimitive(i==0?Double.NaN:0));input.add("mel_spectrogram_32",invalidMel);
        assertFalse(plain.prepare(input,1).inferenceEligible());
    }

    @Test void modelScalarsMergeWithoutLosingOriginalEffectBlocks() throws Exception {
        var p=bundled.prepare(song(),300380);float[] out=p.base();out[0]=3;out[10]=1.25f;out[12]=-18;out[13]=.4f;out[14]=.6f;out[17]=2.5f;
        JsonObject effect=bundled.decode(p,out);
        assertEquals(3,effect.getAsJsonObject("eq").getAsJsonArray("eqs").get(0).getAsFloat());
        assertEquals(p.template().get("peq"),effect.get("peq"));assertEquals(p.template().get("limiter"),effect.get("limiter"));
        assertEquals(p.template().getAsJsonObject("rvb").get("tc"),effect.getAsJsonObject("rvb").get("tc"));
        assertEquals(1.25,effect.getAsJsonObject("rvb").getAsJsonObject("rvb").get("dtime").getAsDouble());
        assertEquals(40,effect.getAsJsonObject("rvb").getAsJsonObject("rvb").get("rshape").getAsDouble(),.00001);
        assertEquals(-30,effect.getAsJsonObject("rvb").getAsJsonObject("ol").get("er").getAsDouble());
        assertEquals(1,effect.getAsJsonObject("dly").entrySet().size());
    }

    @Test void outputValidationRejectsCorruptionIncludingDisabledModuleOutputs() throws Exception {
        var p=bundled.prepare(song(),300380);
        for(int i:new int[]{0,10,11,12,13,14,15,16,17,18}) {
            float[] output=p.base();output[i]=Float.NaN;assertThrows(IllegalArgumentException.class,()->bundled.decode(p,output));
        }
        float[] badEq=p.base();badEq[0]=12.001f;assertThrows(IllegalArgumentException.class,()->bundled.decode(p,badEq));
        float[] badDelay=p.base();badDelay[16]=1.001f;assertThrows(IllegalArgumentException.class,()->bundled.decode(p,badDelay));
        assertThrows(IllegalArgumentException.class,()->bundled.decode(p,new float[19]));
        float[] boundary=p.base();boundary[0]=12.00005f;assertDoesNotThrow(()->bundled.decode(p,boundary));
    }

    @Test void defaultTemplateListSkipsModelWithoutOverwritingEmotionLabel() throws Exception {
        var codec=new EmotionEffectCodec(json("{\"defaultTemplateSongIds\":[300380]}"),false);
        var p=codec.prepare(song(),300380);assertFalse(p.inferenceEligible());assertEquals("default_template_song",p.reason());
        assertEquals("energetic",p.label());assertEquals("default:pop-family",p.templateKey());
    }

    @Test void loudChorusDisablesSshaperWithOriginalEpsilonThreshold() throws Exception {
        JsonObject input=song();input.addProperty("chorus_loudness",-6);
        assertTrue(bundled.fallback(bundled.prepare(input,300380)).getAsJsonObject("se").get("sshaper").getAsBoolean());
        input.addProperty("chorus_loudness",-5.99);
        assertFalse(bundled.fallback(bundled.prepare(input,300380)).getAsJsonObject("se").get("sshaper").getAsBoolean());
    }

    @Test void degradationPreservesFiltersAndAppliesOriginalGainsAfterInference() throws Exception {
        var codec=new EmotionEffectCodec(json("{\"degradeSongIds\":[300380]}"),false);var p=codec.prepare(song(),300380);
        JsonObject result=codec.decode(p,p.base());
        assertEquals(-29,result.getAsJsonObject("rvb").getAsJsonObject("ol").get("rvb").getAsDouble());
        assertEquals(.63,result.getAsJsonObject("rvb").getAsJsonObject("rvb").get("dtime").getAsDouble(),1e-6);
        assertEquals(2.4,result.getAsJsonObject("se").get("stereoizer").getAsDouble(),1e-6);
        assertEquals(.4,result.getAsJsonObject("peq").getAsJsonArray("f").get(0).getAsJsonObject().get("gain").getAsDouble());
    }

    @Test void multichannelIsExplicitAndRetainsPeqWhileClosingSpatialModules() throws Exception {
        var codec=new EmotionEffectCodec(new JsonObject(),true);var p=codec.prepare(song(),300380);
        JsonObject effect=codec.decode(p,p.base());assertFalse(effect.getAsJsonObject("se").get("on").getAsBoolean());
        assertTrue(effect.getAsJsonObject("peq").get("on").getAsBoolean());
        assertEquals(.15,effect.getAsJsonObject("eq").getAsJsonArray("eqs").get(0).getAsDouble(),1e-6);
        assertEquals(-24.5,effect.getAsJsonObject("rvb").getAsJsonObject("ol").get("rvb").getAsDouble());
        assertEquals(1.035,effect.getAsJsonObject("rvb").getAsJsonObject("rvb").get("dtime").getAsDouble(),1e-6);
    }

    private static JsonObject song() throws Exception {
        try(InputStream input=EmotionEffectCodecTest.class.getResourceAsStream("/tritium/audioeffects/emotion/features-300380.json")) {
            assertNotNull(input);return new JsonParser().parse(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject();
        }
    }
    private static JsonObject json(String s){return new JsonParser().parse(s).getAsJsonObject();}
}
