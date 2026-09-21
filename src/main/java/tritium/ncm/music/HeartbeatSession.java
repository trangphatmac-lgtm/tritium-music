package tritium.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.ncm.RequestUtil;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.utils.json.JsonUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;

/** One cancellable recommendation stream. Network work never mutates the playback queue. */
final class HeartbeatSession {
    @FunctionalInterface
    interface Request {
        RequestUtil.RequestAnswer fetch(long songId, long playlistId, long startMusicId);
    }

    final User user;
    final PlayList source;
    final long seed;
    final List<Music> songs = new CopyOnWriteArrayList<>();
    private final Request request;
    private final Executor executor;
    private CompletableFuture<List<Music>> pending;
    volatile String error;

    HeartbeatSession(User user, PlayList source, long seed, Request request, Executor executor) {
        this.user = user;
        this.source = source;
        this.seed = seed;
        this.request = request;
        this.executor = executor;
    }

    synchronized CompletableFuture<List<Music>> requestMore(long songId) {
        if (pending == null) {
            error = null;
            pending = CompletableFuture.supplyAsync(
                    () -> parse(request.fetch(songId, source.getId(), seed)), executor);
        }
        return pending;
    }

    synchronized void consumed() {
        pending = null;
    }

    /** Keep the server's ordering, but avoid repeating recently queued songs. */
    List<Music> freshSongs(List<Music> batch) {
        Set<Long> recent = new HashSet<>();
        songs.subList(Math.max(0, songs.size() - 100), songs.size())
                .forEach(song -> recent.add(song.getId()));
        return batch.stream().filter(song -> recent.add(song.getId())).toList();
    }

    static List<Music> parse(RequestUtil.RequestAnswer answer) {
        JsonObject body = answer == null ? null : answer.toJsonObject();
        if (answer == null || answer.getStatus() != 200 || body == null
                || !body.has("code") || body.get("code").getAsInt() != 200
                || !body.has("data") || !body.get("data").isJsonArray()) {
            throw new IllegalStateException("心动推荐加载失败，请检查网络或重新登录");
        }
        LinkedHashMap<Long, Music> songs = new LinkedHashMap<>();
        for (JsonElement element : body.getAsJsonArray("data")) {
            if (!element.isJsonObject()) continue;
            JsonElement info = element.getAsJsonObject().get("songInfo");
            if (info == null || !info.isJsonObject()) continue;
            try {
                JsonObject songInfo = info.getAsJsonObject();
                // Both desktop and legacy song objects occur in intelligence responses.
                if (!songInfo.has("ar") && songInfo.has("artists")) songInfo.add("ar", songInfo.get("artists"));
                if (!songInfo.has("al") && songInfo.has("album")) songInfo.add("al", songInfo.get("album"));
                if (!songInfo.has("dt") && songInfo.has("duration")) songInfo.add("dt", songInfo.get("duration"));
                Music song = JsonUtils.parse(songInfo, Music.class);
                if (song.getId() > 0 && song.getName() != null && song.getAlbum() != null
                        && song.getArtists() != null) {
                    songs.putIfAbsent(song.getId(), song);
                }
            } catch (RuntimeException ignored) {
                // A malformed recommendation must not discard the remaining usable songs.
            }
        }
        if (songs.isEmpty()) throw new IllegalStateException("暂无心动推荐，请稍后重试");
        return List.copyOf(songs.values());
    }
}
