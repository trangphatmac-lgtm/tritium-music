package tritium.ncm.music.lyrics;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OnlineLyricParserTest {
    @Test void qrcUsesAbsoluteWordTimesAndKeepsTranslationAndRomanization() throws Exception {
        var document = OnlineLyricParser.parse("qrc", "[1000,2000]Hello (1000,800)世界(1800,1200)", "[00:01.00]你好世界", "[1000,2000]hello(1000,2000)");
        var line = document.lines().getFirst();
        assertEquals("Hello 世界", line.text()); assertEquals(1800, line.words().get(1).start());
        assertEquals("你好世界", line.translation()); assertEquals("hello", line.romanization());
        assertEquals("Hello", OnlineLyricParser.parse("qrc", "[1000,1000](1000,1000)Hello", "", "").lines().getFirst().text());
    }
    @Test void encryptedQrcMatchesOriginalFoliaReference() throws Exception {
        String hex, expected;
        try (var input = getClass().getResourceAsStream("/lyrics/qq-reference.hex")) { hex = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        try (var input = getClass().getResourceAsStream("/lyrics/qq-reference.txt")) { expected = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        assertEquals(expected, QrcDecoder.decode(hex));
        assertThrows(IllegalArgumentException.class, () -> QrcDecoder.decode("abc"));
    }
    @Test void krcRelativeTimesAndEmbeddedTracksSurviveDecryption() throws Exception {
        String metadata = Base64.getEncoder().encodeToString("{\"content\":[{\"type\":0,\"lyricContent\":[[\"ni \",\"hao\"]]},{\"type\":1,\"lyricContent\":[[\"Hello\"]]}]}".getBytes(StandardCharsets.UTF_8));
        String text = "[language:" + metadata + "]\n[1000,900]<0,400,0>你<400,500,0>好";
        var buffer = new ByteArrayOutputStream();
        try (var output = new DeflaterOutputStream(buffer)) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
        byte[] compressed = buffer.toByteArray(), bytes = new byte[compressed.length + 4];
        System.arraycopy("krc1".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        int[] key = {64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105};
        for (int i = 0; i < compressed.length; i++) bytes[i + 4] = (byte) (compressed[i] ^ key[i % key.length]);
        assertEquals(text, LyricCompression.kugou(bytes));
        var line = OnlineLyricParser.parse("krc", LyricCompression.kugou(bytes), "", "").lines().getFirst();
        assertEquals("你好", line.text()); assertEquals(1400, line.words().get(1).start());
        assertEquals("Hello", line.translation()); assertEquals("ni hao", line.romanization());
    }
    @Test void plainKugouAndLrcTimestampVariantsFallback() throws Exception {
        var document = OnlineLyricParser.parse("krc", "[offset:100]\n[00:01][00:02.5]Line\n[00:03:20]Other", "", "");
        assertEquals(3, document.lines().size()); assertFalse(document.wordTimed());
        assertEquals(2600, document.lines().get(1).start()); assertEquals(3300, document.lines().get(2).start());
        var enhanced = OnlineLyricParser.parse("krc", "[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.00>", "", "");
        assertTrue(enhanced.wordTimed()); assertEquals(500, enhanced.lines().getFirst().words().getFirst().duration());
    }
    @Test void neteaseEmptyYrcDoesNotDestroyUsableLrcAndFalseUncollectedIsAllowed() {
        var json = new JsonParser().parse("{\"uncollected\":false,\"lrc\":{\"lyric\":\"[00:01]Line\"},\"yrc\":{\"lyric\":\"\"},\"tlyric\":{\"lyric\":\"[00:01]翻译\"}}").getAsJsonObject();
        var document = OnlineLyricParser.netease(json);
        assertTrue(document.usable()); assertEquals("翻译", document.lines().getFirst().translation());
        json.addProperty("nolyric", true); assertTrue(OnlineLyricParser.netease(json).pureMusic());
    }
    @Test void yrcCanExistWithoutLrcAndSkipsBrokenLines() throws Exception {
        var json = new JsonParser().parse("{\"yrc\":{\"lyric\":\"[ar:Artist]\\n[1000,1000](1000,500,0)Hello (1500,500,0)world\"}}").getAsJsonObject();
        var result = OnlineLyricParser.netease(json);
        assertTrue(result.wordTimed()); assertEquals("Hello world", result.lines().getFirst().text());
    }
    @Test void ttmlKeepsWordTimesSecondaryTracksAndBackgroundVocals() throws Exception {
        var text = """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="00:01.000" end="3s"><span begin="1s" end="1.5s">Hello</span> <span begin="1.5s" end="3s">世界</span><span ttm:role="x-translation">你好世界</span><span ttm:role="x-roman">hello sekai</span><span ttm:role="x-bg" begin="2s" end="3s"><span begin="2s" end="3s">echo</span></span></p>
                <p begin="4s" end="5s">Plain line</p>
                </div></body></tt>
                """;
        var result = OnlineLyricParser.parse("ttml", text, "", "");
        assertEquals(3, result.lines().size());
        var line = result.lines().getFirst();
        assertEquals("Hello 世界", line.text()); assertEquals("Hello ", line.words().getFirst().text());
        assertEquals("你好世界", line.translation()); assertEquals("hello sekai", line.romanization());
        assertEquals(1500, line.words().get(1).start()); assertEquals("echo", result.lines().get(1).text());
        assertTrue(result.lines().getLast().words().isEmpty());
        assertThrows(Exception.class, () -> OnlineLyricParser.parse("ttml", "<!DOCTYPE tt [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><tt>&leak;</tt>", "", ""));
    }
    @Test void ttmlWordAnnotationsDoNotLeakIntoMainTextOrRemoveWordTiming() throws Exception {
        var document = OnlineLyricParser.parse("ttml", "<tt xmlns='http://www.w3.org/ns/ttml' xmlns:ttm='http://www.w3.org/ns/ttml#metadata'><body><p begin='1s' end='2s'>(<span begin='1s' end='2s'>Word<span ttm:role='x-roman'>reading</span></span>)</p></body></tt>", "", "");
        assertEquals("(Word)", document.lines().getFirst().text());
        assertEquals("(Word)", document.lines().getFirst().words().getFirst().text());
    }
    @Test void webVttProviderFallbackRemainsLineTimed() throws Exception {
        var document = OnlineLyricParser.parse("krc", "WEBVTT\n\n00:00:01.000 --> 00:00:03.500\n<c>Hello</c>\n\n00:00:04.000 --> 00:00:05.000\nWorld", "", "");
        assertEquals(2, document.lines().size()); assertFalse(document.wordTimed());
        assertEquals(2500, document.lines().getFirst().duration());
    }

    @Test void qrcXmlWrapperIsDecoded() throws Exception {
        var document = OnlineLyricParser.parse("qrc", "<QrcInfos><LyricInfo><Lyric_1 LyricContent=\"[1000,1000]A &amp; B(1000,1000)&#10;[3000,1000]C(3000,1000)\"/></LyricInfo></QrcInfos>", "", "");
        assertEquals("A & B", document.lines().getFirst().text()); assertEquals(2, document.lines().size());
        var literalNewlines = OnlineLyricParser.parse("qrc", "<QrcInfos><LyricInfo><Lyric_1 LyricContent=\"[ar:Artist]\n[1000,1000]First(1000,1000)\n[3000,1000]Second(3000,1000)\"/></LyricInfo></QrcInfos>", "", "");
        assertEquals(2, literalNewlines.lines().size());
        assertEquals("Second", literalNewlines.lines().getLast().text());
    }
}
