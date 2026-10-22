package tritium.ncm.music.lyrics;

import com.google.gson.*;
import tritium.ncm.api.CloudMusicApi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Folia's anonymous QQ/Kugou protocols and AMLL DB lookup by the playing NetEase ID. */
public final class OnlineLyricProviders implements BestLyricService.Providers {
    private final LyricHttp http;
    public OnlineLyricProviders() { this(LyricHttp.create()); }
    OnlineLyricProviders(LyricHttp http) { this.http = http; }

    @Override
    public LyricDocument netease(LyricSong song) throws Exception {
        var document = OnlineLyricParser.netease(CloudMusicApi.lyricNew(Long.parseLong(song.id())).toJsonObject());
        // Keep the existing bundled corrections; never copy by index into a shorter remote result.
        try (var stream = getClass().getResourceAsStream("/tritium/yrc/" + song.id() + ".yrc")) {
            if (stream == null) return document;
            var corrected = OnlineLyricParser.parse("yrc", new String(stream.readAllBytes(), StandardCharsets.UTF_8), "", "");
            if (!corrected.usable()) return document;
            var lines = corrected.toLines();
            for (var line : lines) document.lines().stream().filter(old -> old.start() == line.timestamp).findFirst().ifPresent(old -> {
                line.translationText = old.translation(); line.romanizationText = old.romanization();
            });
            return LyricDocument.fromLines(lines, document.pureMusic());
        }
    }

    @Override
    public List<LyricSong> search(LyricSource source, LyricSong target) throws Exception {
        return switch (source) {
            case QQ -> searchQq(target);
            case KUGOU -> searchKugou(target);
            default -> List.of();
        };
    }

    @Override
    public LyricDocument fetch(LyricSource source, LyricSong song) throws Exception {
        return switch (source) {
            case NETEASE -> netease(song);
            case AMLL -> {
                var response = http.request(LyricHttp.query("https://api.amll.dev/v1/lyrics/get", Map.of("ncmMusicId", song.id())), null, Map.of(), 5000);
                if (number(response, "status") != 200) yield LyricDocument.EMPTY;
                String ttml = string(object(response, "data"), "lyrics");
                yield ttml.isBlank() ? LyricDocument.EMPTY : OnlineLyricParser.parse("ttml", ttml, "", "");
            }
            case QQ -> fetchQq(song);
            case KUGOU -> fetchKugou(song);
        };
    }

    private JsonObject qq(String method, String module, JsonObject params, int timeout) throws Exception {
        var comm = json(Map.of("ct", 11, "cv", "1003006", "v", "1003006", "os_ver", "15", "phonetype", "24122RKC7C",
                "rom", "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.102.0.VOMCNXM:user/release-keys", "tmeAppID", "qqmusiclight", "nettype", "NETWORK_WIFI", "udid", "0", "uid", "0"));
        var request = json(Map.of("method", method, "module", module));
        request.add("param", params);
        var payload = new JsonObject(); payload.add("comm", comm); payload.add("request", request);
        var response = http.request("https://u.y.qq.com/cgi-bin/musicu.fcg", payload,
                Map.of("Cookie", "tmeLoginType=-1;", "User-Agent", "okhttp/3.14.9"), timeout);
        var result = object(response, "request");
        if (!result.has("data") || number(response, "code") != 0 || number(result, "code") != 0) throw new java.io.IOException("QQ lyric request refused");
        return object(result, "data");
    }

    private List<LyricSong> searchQq(LyricSong target) throws Exception {
        String query = LyricMatchScore.query(target);
        if (query.length() > 60) query = query.substring(0, 60);
        var params = json(Map.of("search_id", String.valueOf(System.currentTimeMillis()), "remoteplace", "search.android.keyboard",
                "query", query, "search_type", 7, "num_per_page", 10, "page_num", 1, "highlight", 0, "nqc_flag", 0, "page_id", 1, "grp", 1));
        var response = qq("DoSearchForQQMusicLite", "music.search.SearchCgiService", params, 3500);
        var songs = new ArrayList<LyricSong>();
        for (var entry : array(object(response, "body"), "item_song")) {
            var item = entry.getAsJsonObject(); var album = object(item, "album");
            String id = string(item, "id"), mid = string(item, "mid");
            if (!id.isBlank() && !mid.isBlank()) songs.add(new LyricSong(id, string(item, "title"), names(array(item, "singer")),
                    string(album, "name"), number(item, "interval") * 1000, mid, ""));
        }
        return songs;
    }

    private LyricDocument fetchQq(LyricSong song) throws Exception {
        var params = json(Map.of("albumName", base64(song.album()), "crypt", 1, "ct", 19, "cv", 2111,
                "interval", song.durationMs() / 1000, "lrc_t", 0, "qrc", 1, "qrc_t", 0, "roma", 1, "roma_t", 0));
        params.addProperty("singerName", base64(song.artist())); params.addProperty("songID", Long.parseLong(song.id()));
        params.addProperty("songName", base64(song.title())); params.addProperty("trans", 1); params.addProperty("trans_t", 0); params.addProperty("type", 0);
        var response = qq("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", params, 5000);
        String encrypted = string(response, "lyric");
        if (encrypted.isBlank()) return LyricDocument.EMPTY;
        String main = QrcDecoder.decode(encrypted);
        return OnlineLyricParser.parse("qrc", main, optionalQrc(string(response, "trans")), optionalQrc(string(response, "roma")));
    }

    private static String optionalQrc(String text) {
        try { return text.isBlank() ? "" : QrcDecoder.decode(text); }
        catch (Exception ignored) { return ""; }
    }

    private JsonObject kugou(String url, Map<String, String> input, boolean lyric, int timeout) throws Exception {
        var params = new TreeMap<>(input);
        String now = String.valueOf(System.currentTimeMillis()), mid = md5(now);
        params.put("appid", "3116"); params.put("clientver", "11070");
        if (!lyric) {
            params.putAll(Map.of("userid", "0", "token", "", "clienttime", String.valueOf(Long.parseLong(now) / 1000),
                    "iscorrection", "1", "uuid", "-", "mid", mid, "dfid", "-", "platform", "AndroidFilter"));
        }
        String secret = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
        var sign = new StringBuilder(secret); params.forEach((k, v) -> sign.append(k).append('=').append(v));
        params.put("signature", md5(sign.append(secret).toString()));
        var headers = new HashMap<>(Map.of("User-Agent", "Android14-1070-11070-201-0-" + (lyric ? "Lyric" : "SearchSong") + "-wifi",
                "KG-Rec", "1", "KG-RC", "1", "KG-CLIENTTIMEMS", now, "mid", mid));
        if (!lyric) headers.put("x-router", "complexsearch.kugou.com");
        var result = http.request(LyricHttp.query(url, params), null, headers, timeout);
        long code = number(result, "error_code");
        if (code != 0 && code != 200) throw new java.io.IOException("Kugou lyric request refused: " + code);
        return result;
    }

    private List<LyricSong> searchKugou(LyricSong target) throws Exception {
        try {
            var response = kugou("https://complexsearch.kugou.com/v2/search/song", Map.of("sorttype", "0", "keyword", LyricMatchScore.searchTitle(target), "pagesize", "10", "page", "1"), false, 3500);
            var songs = new ArrayList<LyricSong>();
            for (var entry : array(object(response, "data"), "lists")) {
                var item = entry.getAsJsonObject(); String hash = string(item, "FileHash").toUpperCase(Locale.ROOT);
                if (!hash.isBlank()) songs.add(new LyricSong(hash, string(item, "SongName"), names(array(item, "Singers")), string(item, "AlbumName"),
                        number(item, "Duration") * 1000, hash, first(string(item, "album_audio_id"), string(item, "MixSongID"))));
            }
            return songs;
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            var params = Map.of("showtype", "14", "highlight", "", "pagesize", "10", "tag_aggr", "1", "plat", "0",
                    "sver", "5", "keyword", LyricMatchScore.searchTitle(target), "correct", "1", "api_ver", "1", "version", "9108");
            var query = new HashMap<>(params); query.put("page", "1");
            var response = http.request(LyricHttp.query("https://mobiles.kugou.com/api/v3/search/song", query), null,
                    Map.of("User-Agent", "Android14-1070-11070-201-0-SearchSong-wifi"), 3500);
            var songs = new ArrayList<LyricSong>();
            for (var entry : array(object(response, "data"), "info")) {
                var item = entry.getAsJsonObject(); String hash = string(item, "hash").toUpperCase(Locale.ROOT);
                if (!hash.isBlank()) songs.add(new LyricSong(hash, string(item, "songname"), string(item, "singername"), string(item, "album_name"),
                        number(item, "duration") * 1000, hash, first(string(item, "album_audio_id"), string(item, "mixsongid"))));
            }
            return songs;
        }
    }

    private LyricDocument fetchKugou(LyricSong song) throws Exception {
        var params = new HashMap<>(Map.of("duration", String.valueOf(song.durationMs()), "hash", song.providerKey(),
                "keyword", song.artist() + " - " + song.title(), "lrctxt", "1", "man", "no"));
        if (!song.catalogId().isBlank()) params.put("album_audio_id", song.catalogId());
        var response = kugou("https://lyrics.kugou.com/v1/search", params, true, 5000);
        var candidates = array(response, "candidates");
        if (candidates.size() == 0) return LyricDocument.EMPTY;
        var best = candidates.get(0).getAsJsonObject();
        var download = kugou("https://lyrics.kugou.com/download", Map.of("accesskey", string(best, "accesskey"), "charset", "utf8",
                "client", "mobi", "fmt", "krc", "id", string(best, "id"), "ver", "1"), true, 5000);
        String encoded = string(download, "content");
        if (encoded.isBlank()) return LyricDocument.EMPTY;
        String text = LyricCompression.kugou(Base64.getDecoder().decode(encoded));
        return OnlineLyricParser.parse("krc", text, "", "");
    }

    private static String base64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String md5(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static String names(JsonArray array) { var names = new ArrayList<String>(); array.forEach(item -> names.add(string(item.getAsJsonObject(), "name"))); return String.join(", ", names); }
    private static String first(String a, String b) { return a.isBlank() ? b : a; }
    private static String string(JsonObject object, String key) { return OnlineLyricParser.string(object, key); }
    private static long number(JsonObject object, String key) { try { return Long.parseLong(string(object, key)); } catch (NumberFormatException e) { return 0; } }
    private static JsonObject object(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static JsonObject json(Map<String, ?> values) { return new Gson().toJsonTree(values).getAsJsonObject(); }
}
