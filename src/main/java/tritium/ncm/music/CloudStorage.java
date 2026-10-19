package tritium.ncm.music;

import tritium.i18n.I18n;
import tritium.i18n.LocalizedException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.ncm.RequestUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.dto.Album;
import tritium.ncm.music.dto.Artist;
import tritium.ncm.music.dto.Music;
import tritium.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;

/** Loads private uploads as ordinary playable songs, retaining the cloud song IDs. */
public final class CloudStorage {
    private static final int PAGE_SIZE = 200;

    private CloudStorage() {
    }

    public static List<Music> loadAll() {
        return loadAll(CloudMusicApi::userCloud);
    }

    static List<Music> loadAll(BiFunction<Integer, Integer, RequestUtil.RequestAnswer> request) {
        LinkedHashMap<Long, Music> songs = new LinkedHashMap<>();
        int offset = 0;
        while (true) {
            RequestUtil.RequestAnswer answer = request.apply(PAGE_SIZE, offset);
            JsonObject body = answer.toJsonObject();
            int code = (int) number(body, "code", answer.getStatus());
            if (code == 301 || code == 401 || answer.getStatus() == 401) {
                throw new LocalizedException("cloud.expired");
            }
            if (answer.getStatus() != 200 || code != 200 || !JsonUtils.isJsonArray(body, "data")) {
                throw new LocalizedException("cloud.failed");
            }
            JsonArray data = body.getAsJsonArray("data");
            int before = songs.size();
            for (JsonElement entry : data) {
                Music music = toMusic(entry.getAsJsonObject());
                songs.putIfAbsent(music.getId(), music);
            }
            offset += data.size();
            boolean more = body.has("hasMore") && !body.get("hasMore").isJsonNull()
                    ? body.get("hasMore").getAsBoolean()
                    : offset < number(body, "count", offset);
            if (!more) return new ArrayList<>(songs.values());
            if (data.size() == 0 || songs.size() == before) {
                throw new LocalizedException("cloud.pageFailed");
            }
        }
    }

    static Music toMusic(JsonObject entry) {
        JsonObject detail = entry.has("simpleSong") && entry.get("simpleSong").isJsonObject()
                ? entry.getAsJsonObject("simpleSong") : new JsonObject();
        long id = number(entry, "songId", number(detail, "id", 0));
        if (id <= 0) throw new LocalizedException("cloud.incomplete");
        String name = text(detail, "name", text(entry, "songName", text(entry, "fileName", I18n.text("track.unknown"))));
        List<Artist> artists = new ArrayList<>();
        if (JsonUtils.isJsonArray(detail, "ar")) {
            for (JsonElement artist : detail.getAsJsonArray("ar")) {
                if (artist.isJsonObject() && !text(artist.getAsJsonObject(), "name", "").isEmpty()) {
                    artists.add(JsonUtils.parse(artist, Artist.class));
                }
            }
        }
        if (artists.isEmpty()) artists.add(new Artist(0, text(entry, "artist", I18n.text("artist.unknown")), List.of(), List.of()));
        JsonObject album = detail.has("al") && detail.get("al").isJsonObject()
                ? detail.getAsJsonObject("al") : new JsonObject();
        Album normalizedAlbum = new Album(number(album, "id", 0),
                text(album, "name", text(entry, "album", I18n.text("album.unknown"))), text(album, "picUrl", ""), List.of());
        Music parsed = JsonUtils.parse(detail, Music.class);
        return new Music(name, parsed.getMainTitle(), parsed.getAdditionalTitle(), id, artists,
                parsed.getAliasName(), normalizedAlbum, number(detail, "dt", number(entry, "duration", 0)),
                parsed.getFeatureFlag(), parsed.getPublishTime(), parsed.getTranslatedName());
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() || value.getAsString().isBlank()
                ? fallback : value.getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }
}
