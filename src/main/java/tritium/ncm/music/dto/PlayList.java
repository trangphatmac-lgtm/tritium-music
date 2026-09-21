package tritium.ncm.music.dto;

import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import tritium.ncm.RequestUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.utils.Location;
import tritium.utils.json.JsonUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 歌单对象
 */
@Data
public class PlayList {

    @SerializedName("id")
    private final long id;

    @SerializedName("name")
    private final String name;

    @SerializedName(value = "coverImgUrl")
    private final String coverUrl;

    @SerializedName("trackCount")
    private final int count;

    @SerializedName(value = "playCount")
    private final long playCount;

    @SerializedName("creator")
    private final User creator;

    @SerializedName("description")
    private final String description;

    @SerializedName("subscribed")
    private final boolean subscribed;

    @SerializedName("specialType")
    private final int specialType;

    @SerializedName("createTime")
    private final long createTime;

    // unique fields
    public transient volatile List<Music> musics;
    private transient boolean searchMode = false;
    public transient volatile boolean musicsQueried = false, musicsLoaded = false;
    private transient long musicRevision;
    private transient CompletableFuture<List<Music>> musicLoad;

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/playlist/" + this.id + "/cover.png");
    }

    public synchronized List<Music> getMusics() {
        loadMusics(() -> CloudMusicApi.playlistTrackAll(id, 8), MultiThreadingUtil::runAsync);
        return musics;
    }

    public void loadMusicsWithCallback(MusicsLoadedCallback callback) {
        loadMusics(() -> CloudMusicApi.playlistTrackAll(id, 8), MultiThreadingUtil::runAsync)
                .thenAccept(callback::onMusicsLoaded);
    }

    /** Reload on the next access, without modifying lists held by playback or an open panel. */
    public synchronized void invalidateMusics() {
        musicRevision++;
        musicsLoaded = false;
        musicsQueried = musicLoad != null;
    }

    // A shared load also delivers results to panels opened while a request is in flight.
    synchronized CompletableFuture<List<Music>> loadMusics(Supplier<RequestUtil.RequestAnswer> request,
                                                            Executor executor) {
        if (musics == null) musics = new CopyOnWriteArrayList<>();
        if (musicsLoaded || searchMode) return CompletableFuture.completedFuture(musics);
        if (musicLoad != null) return musicLoad;

        CompletableFuture<List<Music>> result = new CompletableFuture<>();
        musicLoad = result;
        musicsQueried = true;
        try {
            executor.execute(() -> queryMusics(request, result));
        } catch (RuntimeException e) {
            musicLoad = null;
            musicsQueried = false;
            result.completeExceptionally(e);
        }
        return result;
    }

    private void queryMusics(Supplier<RequestUtil.RequestAnswer> request,
                             CompletableFuture<List<Music>> result) {
        try {
            while (true) {
                long revision;
                synchronized (this) {
                    revision = musicRevision;
                }
                RequestUtil.RequestAnswer answer = request.get();
                JsonArray songs = answer.toJsonObject().getAsJsonArray("songs");
                if (answer.getStatus() != 200 || songs == null) {
                    throw new IllegalStateException("Failed to load playlist songs");
                }
                List<Music> loaded = new ArrayList<>();
                songs.forEach(element -> loaded.add(JsonUtils.parse(element.getAsJsonObject(), Music.class)));
                List<Music> published;
                synchronized (this) {
                    // A favorite changed during the request: fetch again instead of publishing stale songs.
                    if (revision != musicRevision) continue;
                    published = new CopyOnWriteArrayList<>(loaded);
                    musics = published;
                    musicsLoaded = true;
                    musicLoad = null;
                }
                result.complete(published);
                return;
            }
        } catch (Exception e) {
            synchronized (this) {
                musicLoad = null;
                musicsQueried = false;
            }
            result.completeExceptionally(e);
            e.printStackTrace();
        }
    }

    public interface MusicsLoadedCallback {
        void onMusicsLoaded(List<Music> musics);
    }

    public void updPlayCount() {
        CloudMusicApi.playlistUpdatePlaycount(this.id);
    }

    public void addToList(long musicId) {
        CloudMusicApi.playlistTracks("add", this.id, String.valueOf(musicId));
    }

    public void removeFromList(long musicId) {
        CloudMusicApi.playlistTracks("del", this.id, String.valueOf(musicId));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PlayList playList = (PlayList) o;
        return id == playList.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
