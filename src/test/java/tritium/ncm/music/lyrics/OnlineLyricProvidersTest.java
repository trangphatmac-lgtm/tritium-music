package tritium.ncm.music.lyrics;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OnlineLyricProvidersTest {
    private JsonObject json(String text) { return new JsonParser().parse(text).getAsJsonObject(); }
    @Test void qqSearchUsesCurrentAnonymousProtocolAndNormalizesMetadata() throws Exception {
        var providers = new OnlineLyricProviders((url, body, headers, timeout) -> {
            assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", url);
            assertEquals(7, body.getAsJsonObject("request").getAsJsonObject("param").get("search_type").getAsInt());
            assertEquals("tmeLoginType=-1;", headers.get("Cookie"));
            assertEquals(3500, timeout);
            return json("{\"code\":0,\"request\":{\"code\":0,\"data\":{\"body\":{\"item_song\":[{\"id\":123,\"mid\":\"mid\",\"title\":\"Song\",\"singer\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\"},\"interval\":200}]}}}}");
        });
        var songs = providers.search(LyricSource.QQ, BestLyricServiceTest.SONG);
        assertEquals(200000, songs.getFirst().durationMs()); assertEquals("mid", songs.getFirst().providerKey());
        assertTrue(LyricMatchScore.score(BestLyricServiceTest.SONG, songs.getFirst()).reliable());
    }
    @Test void qqFetchDecryptsAndKeepsMainWhenOptionalTrackIsMalformed() throws Exception {
        String encrypted;
        try (var stream = getClass().getResourceAsStream("/lyrics/qq-reference.hex")) { encrypted = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim(); }
        var providers = new OnlineLyricProviders((url, body, headers, timeout) -> {
            assertEquals("GetPlayLyricInfo", body.getAsJsonObject("request").get("method").getAsString());
            var data = new JsonObject(); data.addProperty("lyric", encrypted); data.addProperty("trans", "malformed");
            var request = new JsonObject(); request.addProperty("code", 0); request.add("data", data);
            var response = new JsonObject(); response.addProperty("code", 0); response.add("request", request); return response;
        });
        var document = providers.fetch(LyricSource.QQ, BestLyricServiceTest.SONG);
        assertTrue(document.wordTimed()); assertEquals("Hello 世界", document.lines().getFirst().text());
    }
    @Test void amllLooksUpCurrentNeteaseIdAndRejectsMissingPayload() throws Exception {
        var providers = new OnlineLyricProviders((url, body, headers, timeout) -> {
            assertTrue(url.endsWith("ncmMusicId=1")); assertNull(body); assertFalse(headers.containsKey("Cookie"));
            return json("{\"status\":200,\"data\":{\"lyrics\":\"<tt xmlns='http://www.w3.org/ns/ttml'><body><p begin='1s' end='2s'><span begin='1s' end='2s'>Lyric</span></p></body></tt>\"}}");
        });
        assertTrue(providers.fetch(LyricSource.AMLL, BestLyricServiceTest.SONG).wordTimed());
        var missing = new OnlineLyricProviders((u,b,h,t) -> new JsonObject());
        assertFalse(missing.fetch(LyricSource.AMLL, BestLyricServiceTest.SONG).usable());
    }
    @Test void kugouCarriesDurationCatalogIdSignatureAndPlainLrcFallback() throws Exception {
        var urls = new ArrayList<String>();
        var providers = new OnlineLyricProviders((url, body, headers, timeout) -> {
            urls.add(url); assertNull(body); assertFalse(headers.containsKey("Cookie")); assertTrue(url.contains("signature="));
            if (url.contains("complexsearch")) return json("{\"data\":{\"lists\":[{\"FileHash\":\"abc\",\"SongName\":\"Song\",\"AlbumName\":\"Album\",\"Duration\":200,\"Singers\":[{\"name\":\"Artist\"}],\"album_audio_id\":123}]}}");
            if (url.contains("/v1/search")) {
                assertTrue(url.contains("duration=200000")); assertTrue(url.contains("album_audio_id=123"));
                return json("{\"candidates\":[{\"id\":42,\"accesskey\":\"key\"}]}");
            }
            var result = new JsonObject(); result.addProperty("content", Base64.getEncoder().encodeToString("[00:01]Plain lyrics".getBytes(StandardCharsets.UTF_8))); result.addProperty("contenttype", 2); return result;
        });
        var song = providers.search(LyricSource.KUGOU, BestLyricServiceTest.SONG).getFirst();
        assertEquals("ABC", song.providerKey());
        var document = providers.fetch(LyricSource.KUGOU, song);
        assertTrue(document.usable()); assertFalse(document.wordTimed()); assertEquals(3, urls.size());
    }
    @Test void kugouFallsBackToLegacySearchOnRefusal() throws Exception {
        var providers = new OnlineLyricProviders((url, body, headers, timeout) -> {
            if (url.contains("complexsearch")) return json("{\"error_code\":500}");
            assertTrue(url.contains("mobiles.kugou.com"));
            return json("{\"data\":{\"info\":[{\"hash\":\"abc\",\"songname\":\"Song\",\"singername\":\"Artist\",\"album_name\":\"Album\",\"duration\":200}]}}");
        });
        assertEquals(200000, providers.search(LyricSource.KUGOU, BestLyricServiceTest.SONG).getFirst().durationMs());
    }

    @Test @EnabledIfSystemProperty(named = "tritium.testLyricNetwork", matches = "true")
    void liveAnonymousProviderSmoke() throws Exception {
        var providers = new OnlineLyricProviders();
        var target = new LyricSong("186016", "晴天", "周杰伦", "叶惠美", 269000, "", "");
        for (var source : List.of(LyricSource.QQ, LyricSource.KUGOU)) {
            var songs = providers.search(source, target);
            assertFalse(songs.isEmpty(), source + " search");
            var song = LyricMatchScore.select(target, songs);
            assertNotNull(song, source + " reliable match");
            var document = providers.fetch(source, song);
            assertTrue(document.usable(), source + " lyrics"); assertTrue(document.wordTimed(), source + " word timing");
            System.out.println("[BestLyrics smoke] " + source + ": " + document.lines().size() + " timed lines");
        }
        var document = providers.fetch(LyricSource.AMLL, target);
        assertTrue(document.usable(), "AMLL lyrics");
        System.out.println("[BestLyrics smoke] AMLL: " + document.lines().size() + " timed lines");
    }
}
