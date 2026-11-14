package tritium.desktop.mcp;

import com.google.gson.*;
import tritium.desktop.DesktopAppState;
import tritium.desktop.download.SongDownloads;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.*;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Network operations run on HTTP workers. Player and preference edits run on the desktop thread. */
public final class McpTools implements McpHttpServer.Handler {
    private static final Gson JSON = new Gson();
    private final McpSettings settings;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    @FunctionalInterface
    private interface Action { JsonObject run(JsonObject arguments) throws Exception; }
    private record Tool(JsonObject definition, Action action) {}

    public McpTools() {
        settings = new McpSettings(DesktopAppState.preferences());
        add("get_playback_state", "查看当前歌曲、播放状态、播放顺序、音量、收藏状态和来源歌单。", true,
                Map.of(), List.of(), a -> onMain(this::playback));
        add("get_queue", "查看当前播放列表和下一首播放队列，索引从 0 开始。", true,
                pagination(), List.of(), a -> onMain(() -> queue(a)));
        var playlists = pagination();
        playlists.put("source", choice("歌单来源", "explore", "mine", "subscribed", "user"));
        playlists.put("userId", integer("source=user 时的用户 ID", 1, Long.MAX_VALUE));
        add("list_playlists", "查看探索页推荐歌单、我的歌单、收藏歌单或指定用户的歌单。", true,
                playlists, List.of("source"), this::playlists);
        var playlist = pagination();
        playlist.put("playlistId", integer("歌单 ID", 1, Long.MAX_VALUE));
        add("get_playlist", "查看指定歌单的歌曲。", true, playlist, List.of("playlistId"), a -> {
            PlayList source = playlist(id(a, "playlistId"));
            JsonObject result = page(source.loadMusicsAsync().get(90, TimeUnit.SECONDS), a, McpTools::song);
            result.add("playlist", playlistInfo(source));
            return result;
        });
        add("get_cloud_storage", "查看已登录用户的音乐网盘，返回可播放、下载的歌曲 ID。", true,
                pagination(), List.of(), a -> {
                    requireLogin();
                    return page(CloudStorage.loadAll(), a, McpTools::song);
                });
        add("get_settings", "查看设置页全部配置及各项类型、可选值和数值范围。", true,
                Map.of(), List.of(), a -> onMain(settings::snapshot));
        add("control_playback", "播放、暂停、上一首或下一首。", false,
                Map.of("action", choice("播放操作", "play", "pause", "previous", "next")), List.of("action"), a -> onMain(() -> {
                    PlaybackState state = CloudMusic.playbackState();
                    switch (a.get("action").getAsString()) {
                        case "play" -> {
                            if (!state.canPlay()) throw new IllegalStateException("No playable track. Use play_song or play_playlist.");
                            CloudMusic.setPaused(false);
                        }
                        case "pause" -> {
                            if (!state.canPause()) throw new IllegalStateException("No active playback to pause");
                            CloudMusic.setPaused(true);
                        }
                        case "previous" -> {
                            if (!state.canPrevious()) throw new IllegalStateException("No previous track");
                            CloudMusic.prev();
                        }
                        case "next" -> {
                            if (!state.canNext()) throw new IllegalStateException("No next track");
                            CloudMusic.next();
                        }
                        default -> throw new IllegalArgumentException("Unknown playback action");
                    }
                    return playback();
                }));
        add("set_play_mode", "设置播放顺序。Heartbeat 为心动模式，需要登录及收藏歌曲。", false,
                Map.of("mode", choice("播放顺序", "Sequential", "LoopInList", "LoopSingle", "Random", "Heartbeat")), List.of("mode"), a -> {
                    CloudMusic.PlayMode mode = CloudMusic.PlayMode.valueOf(a.get("mode").getAsString());
                    String message = onMain(() -> CloudMusic.setPlayMode(mode)).get(90, TimeUnit.SECONDS);
                    JsonObject result = onMain(this::playback);
                    if (CloudMusic.playMode != mode) throw new IllegalStateException(message);
                    result.addProperty("message", message);
                    return result;
                });
        add("set_volume", "设置音量，0 为静音，1 为最大音量。", false,
                Map.of("volume", number("音量", 0, 1)), List.of("volume"), a -> onMain(() -> {
                    DesktopAppState.preferences().volume().setValue(a.get("volume").getAsDouble());
                    return playback();
                }));
        add("set_favorite", "收藏或取消收藏歌曲，省略 songId 时操作当前歌曲。重复设置相同状态不会反转收藏。", false,
                Map.of("songId", integer("歌曲 ID，省略时使用当前歌曲", 1, Long.MAX_VALUE), "liked", bool("是否收藏")), List.of("liked"), a -> {
                    requireLogin();
                    var user = CloudMusic.profile;
                    Music music = resolveSong(a);
                    boolean liked = a.get("liked").getAsBoolean();
                    String message = onMain(() -> {
                        if (CloudMusic.profile != user) throw new IllegalStateException("Login session changed");
                        if (CloudMusic.likeList == null) throw new IllegalStateException("Favorite songs are still loading");
                        if (CloudMusic.isLikePending(music.getId())) throw new IllegalStateException("Favorite update is already in progress");
                        return CloudMusic.setLike(music, liked);
                    }).get(60, TimeUnit.SECONDS);
                    if (CloudMusic.profile != user || CloudMusic.likeList == null || CloudMusic.isLiked(music.getId()) != liked)
                        throw new IllegalStateException(message);
                    JsonObject result = song(music);
                    result.addProperty("message", message);
                    return result;
                });
        var search = pagination();
        search.put("query", string("歌曲关键词"));
        add("search_songs", "搜索歌曲，返回前 100 个匹配结果中的指定分页。", true,
                search, List.of("query"), a -> {
                    JsonObject body = checked(CloudMusicApi.cloudSearch(a.get("query").getAsString(), CloudMusicApi.SearchType.Single).toJsonObject());
                    JsonObject result = body.getAsJsonObject("result");
                    List<Music> songs = new ArrayList<>();
                    if (result != null && JsonUtils.isJsonArray(result, "songs"))
                        result.getAsJsonArray("songs").forEach(item -> songs.add(JsonUtils.parse(item, Music.class)));
                    return page(songs, a, McpTools::song);
                });
        add("play_song", "播放指定歌曲并将其作为当前播放列表。", false,
                Map.of("songId", integer("歌曲 ID", 1, Long.MAX_VALUE)), List.of("songId"), a -> {
                    Music music = resolveSong(a);
                    return onMain(() -> {
                        CloudMusic.play(List.of(music), 0);
                        return accepted(music);
                    });
                });
        add("play_playlist", "播放指定歌单，可指定起始歌曲索引，从 0 开始。", false,
                Map.of("playlistId", integer("歌单 ID", 1, Long.MAX_VALUE), "startIndex", integer("起始索引，默认 0", 0, Integer.MAX_VALUE)), List.of("playlistId"), a -> {
                    PlayList source = playlist(id(a, "playlistId"));
                    List<Music> songs = source.loadMusicsAsync().get(90, TimeUnit.SECONDS);
                    int index = a.has("startIndex") ? a.get("startIndex").getAsInt() : 0;
                    if (songs.isEmpty() || index >= songs.size()) throw new IllegalArgumentException("Playlist is empty or startIndex is out of range");
                    return onMain(() -> {
                        CloudMusic.play(songs, index, source);
                        JsonObject result = accepted(songs.get(index));
                        result.add("playlist", playlistInfo(source));
                        return result;
                    });
                });
        add("play_next", "将歌曲插入下一首播放队列。没有活动播放时立即播放。", false,
                Map.of("songId", integer("歌曲 ID", 1, Long.MAX_VALUE)), List.of("songId"), a -> {
                    Music music = resolveSong(a);
                    return onMain(() -> {
                        JsonObject result = accepted(music);
                        result.addProperty("message", CloudMusic.playNext(music));
                        return result;
                    });
                });
        add("download_song", "下载歌曲到设置页指定目录并使用所选下载音质。省略 songId 时下载当前歌曲，返回最终下载结果。", false,
                Map.of("songId", integer("歌曲 ID，省略时使用当前歌曲", 1, Long.MAX_VALUE)), List.of(), a -> {
                    Music music = resolveSong(a);
                    var future = onMain(() -> {
                        var preferences = DesktopAppState.preferences();
                        return DesktopAppState.songDownloads().start(music, preferences.downloadDirectory().getValue(), preferences.selectedDownloadQuality());
                    });
                    SongDownloads.Status status = future.get(10, TimeUnit.MINUTES);
                    if (status.state() == SongDownloads.State.FAILED) throw new IllegalStateException(status.text());
                    JsonObject result = new JsonObject();
                    result.addProperty("state", status.state().name());
                    result.addProperty("file", status.file().toString());
                    result.addProperty("message", status.text());
                    return result;
                });
        add("set_translation", "开启或关闭歌词翻译。", false,
                Map.of("enabled", bool("是否显示翻译")), List.of("enabled"), a -> onMain(() -> {
                    DesktopAppState.preferences().showTranslation().setValue(a.get("enabled").getAsBoolean());
                    return playback();
                }));
        JsonObject changes = new JsonObject();
        changes.addProperty("type", "object");
        changes.addProperty("description", "先用 get_settings 获取键和值类型，再传入要修改的扁平键值对象，例如 {\"ui.appearance\":\"Light\"}");
        changes.addProperty("minProperties", 1);
        add("update_settings", "修改设置页配置，即时生效并保存。所有值先校验。mcp.enabled 和 mcp.port 的更改会关闭或迁移当前服务。", false,
                Map.of("changes", changes), List.of("changes"), a -> onMain(() -> settings.update(a.getAsJsonObject("changes"))));
    }

    @Override
    public JsonArray list() {
        JsonArray result = new JsonArray();
        tools.values().forEach(tool -> result.add(tool.definition));
        return result;
    }

    @Override
    public JsonObject call(String name, JsonObject arguments) {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        validate(arguments, tool.definition.getAsJsonObject("inputSchema"));
        try {
            JsonObject result = tool.action.run(arguments);
            JsonObject response = content(JSON.toJson(result), false);
            response.add("structuredContent", result);
            return response;
        } catch (Exception error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return content(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()), true);
        }
    }

    static JsonObject content(String text, boolean error) {
        JsonObject response = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(item);
        response.add("content", content);
        response.addProperty("isError", error);
        return response;
    }

    private void add(String name, String description, boolean readOnly, Map<String, JsonObject> properties, List<String> required, Action action) {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", name);
        definition.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject fields = new JsonObject();
        properties.forEach(fields::add);
        schema.add("properties", fields);
        schema.add("required", JSON.toJsonTree(required));
        schema.addProperty("additionalProperties", false);
        definition.add("inputSchema", schema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", !readOnly);
        annotations.addProperty("openWorldHint", true);
        definition.add("annotations", annotations);
        tools.put(name, new Tool(definition, action));
    }

    private JsonObject playback() {
        PlaybackState state = CloudMusic.playbackState();
        JsonObject result = new JsonObject();
        result.addProperty("status", state.status().name());
        result.add("song", state.song() == null ? JsonNull.INSTANCE : song(state.song()));
        result.addProperty("positionMillis", state.positionMillis());
        result.addProperty("durationMillis", state.durationMillis());
        result.addProperty("playMode", CloudMusic.playMode.name());
        result.addProperty("volume", DesktopAppState.preferences().volume().getValue());
        result.addProperty("translationEnabled", DesktopAppState.preferences().showTranslation().getValue());
        result.addProperty("favoriteKnown", CloudMusic.profile != null && CloudMusic.likeList != null);
        result.add("sourcePlaylist", CloudMusic.playedFrom == null ? JsonNull.INSTANCE : playlistInfo(CloudMusic.playedFrom));
        result.addProperty("canPlay", state.canPlay());
        result.addProperty("canPause", state.canPause());
        result.addProperty("canPrevious", state.canPrevious());
        result.addProperty("canNext", state.canNext());
        return result;
    }

    private JsonObject queue(JsonObject arguments) {
        PlaybackSession session = CloudMusic.capturePlaybackSession();
        List<Music> songs = session == null ? List.copyOf(CloudMusic.playList) : session.songs();
        JsonObject result = page(songs, arguments, McpTools::song);
        result.addProperty("currentIndex", session == null ? -1 : session.index());
        JsonArray next = new JsonArray();
        if (session != null) session.nextSongs().forEach(music -> next.add(song(music)));
        result.add("nextSongs", next);
        result.addProperty("playMode", CloudMusic.playMode.name());
        return result;
    }

    private JsonObject playlists(JsonObject arguments) throws Exception {
        String source = arguments.get("source").getAsString();
        if (source.equals("explore")) return page(PlaylistDiscovery.loadRecommendations(), arguments, McpTools::playlistInfo);
        if (source.equals("user")) {
            if (!arguments.has("userId")) throw new IllegalArgumentException("userId is required for source=user");
            int offset = offset(arguments);
            int limit = limit(arguments);
            JsonObject body = checked(CloudMusicApi.userPlaylist(id(arguments, "userId"), limit, offset).toJsonObject());
            List<PlayList> playlists = new ArrayList<>();
            if (!JsonUtils.isJsonArray(body, "playlist")) throw new IllegalStateException("Playlist response is incomplete");
            body.getAsJsonArray("playlist").forEach(item -> playlists.add(JsonUtils.parse(item, PlayList.class)));
            JsonObject pageArguments = new JsonObject();
            pageArguments.addProperty("limit", limit);
            JsonObject result = page(playlists, pageArguments, McpTools::playlistInfo);
            result.addProperty("offset", offset);
            result.addProperty("limit", limit);
            result.remove("total");
            result.addProperty("hasMore", body.has("more") && body.get("more").getAsBoolean());
            return result;
        }
        requireLogin();
        List<PlayList> playlists = onMain(() -> {
            if (CloudMusic.playLists == null) throw new IllegalStateException("User playlists are still loading");
            return CloudMusic.playLists.stream().filter(item -> item.isSubscribed() == source.equals("subscribed")).toList();
        });
        return page(playlists, arguments, McpTools::playlistInfo);
    }

    private PlayList playlist(long id) throws Exception {
        PlayList cached = onMain(() -> {
            if (CloudMusic.playLists != null) {
                for (PlayList item : CloudMusic.playLists) if (item.getId() == id) return item;
            }
            for (PlayList item : PlaylistDiscovery.recommendations) if (item.getId() == id) return item;
            return null;
        });
        if (cached != null) return cached;
        JsonObject body = checked(CloudMusicApi.playlistDetail(id).toJsonObject());
        if (!body.has("playlist") || !body.get("playlist").isJsonObject()) throw new IllegalStateException("Playlist was not found");
        return JsonUtils.parse(body.get("playlist"), PlayList.class);
    }

    private Music resolveSong(JsonObject arguments) throws Exception {
        if (!arguments.has("songId")) return onMain(() -> {
            Music song = CloudMusic.currentlyPlaying;
            if (song == null) throw new IllegalStateException("No current song. Supply songId.");
            return song;
        });
        long id = id(arguments, "songId");
        Music cached = onMain(() -> {
            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.getId() == id) return CloudMusic.currentlyPlaying;
            PlaybackSession session = CloudMusic.capturePlaybackSession();
            if (session != null) {
                for (Music music : session.songs()) if (music.getId() == id) return music;
                for (Music music : session.nextSongs()) if (music.getId() == id) return music;
            }
            return null;
        });
        if (cached != null) return cached;
        JsonObject body = checked(CloudMusicApi.songDetail(id).toJsonObject());
        if (!JsonUtils.isJsonArray(body, "songs")) throw new IllegalStateException("Song was not found");
        for (JsonElement item : body.getAsJsonArray("songs")) {
            Music song = JsonUtils.parse(item, Music.class);
            if (song.getId() == id) return song;
        }
        throw new IllegalStateException("Song was not found");
    }

    static JsonObject song(Music music) {
        JsonObject result = new JsonObject();
        result.addProperty("id", music.getId());
        result.addProperty("name", music.getName());
        result.add("artists", JSON.toJsonTree(music.getArtists()));
        result.add("album", JSON.toJsonTree(music.getAlbum()));
        result.addProperty("durationMillis", music.getDuration());
        result.add("liked", CloudMusic.profile == null || CloudMusic.likeList == null
                ? JsonNull.INSTANCE : new JsonPrimitive(CloudMusic.isLiked(music.getId())));
        return result;
    }

    private static JsonObject playlistInfo(PlayList playlist) {
        JsonObject result = new JsonObject();
        result.addProperty("id", playlist.getId());
        result.addProperty("name", playlist.getName());
        result.addProperty("trackCount", playlist.getCount());
        result.addProperty("coverUrl", playlist.getCoverUrl());
        result.addProperty("subscribed", playlist.isSubscribed());
        if (playlist.getCreator() != null) {
            result.addProperty("creatorId", playlist.getCreator().getId());
            result.addProperty("creatorName", playlist.getCreator().getName());
        }
        return result;
    }

    private static JsonObject accepted(Music song) {
        JsonObject result = new JsonObject();
        result.addProperty("accepted", true);
        result.add("song", song(song));
        return result;
    }

    private static void requireLogin() {
        if (CloudMusic.profile == null) throw new IllegalStateException("Sign in to NetEase Cloud Music first");
    }

    private static JsonObject checked(JsonObject body) {
        if (body == null || !body.has("code") || body.get("code").getAsInt() != 200)
            throw new IllegalStateException("Music API request failed. Check your login session and network.");
        return body;
    }

    static <T> T onMain(Callable<T> action) throws Exception {
        if (DesktopAppState.isMainThread()) return action.call();
        CompletableFuture<T> result = new CompletableFuture<>();
        DesktopAppState.runOnMainThread(() -> {
            if (result.isCancelled()) return;
            try {
                if (!DesktopAppState.isRunning()) throw new IllegalStateException("Player is shutting down");
                result.complete(action.call());
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        try { return result.get(15, TimeUnit.SECONDS); }
        finally { result.cancel(false); }
    }

    private static <T> JsonObject page(List<T> items, JsonObject arguments, Function<T, JsonObject> serialize) {
        int offset = offset(arguments), limit = limit(arguments);
        JsonArray entries = new JsonArray();
        int end = (int) Math.min(items.size(), (long) offset + limit);
        for (int index = Math.min(offset, items.size()); index < end; index++) entries.add(serialize.apply(items.get(index)));
        JsonObject result = new JsonObject();
        result.add("items", entries);
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("total", items.size());
        result.addProperty("hasMore", end < items.size());
        return result;
    }

    private static int offset(JsonObject a) { return a.has("offset") ? a.get("offset").getAsInt() : 0; }
    private static int limit(JsonObject a) { return a.has("limit") ? a.get("limit").getAsInt() : 100; }
    private static long id(JsonObject a, String key) { return a.get(key).getAsLong(); }

    private static Map<String, JsonObject> pagination() {
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        fields.put("offset", integer("分页起点，默认 0", 0, Integer.MAX_VALUE));
        fields.put("limit", integer("每页数量，默认 100，最多 500", 1, 500));
        return fields;
    }

    private static JsonObject string(String description) { return field("string", description); }
    private static JsonObject bool(String description) { return field("boolean", description); }
    private static JsonObject choice(String description, String... choices) {
        JsonObject result = string(description);
        result.add("enum", JSON.toJsonTree(choices));
        return result;
    }
    private static JsonObject number(String description, double min, double max) {
        JsonObject result = field("number", description);
        result.addProperty("minimum", min);
        result.addProperty("maximum", max);
        return result;
    }
    private static JsonObject integer(String description, long min, long max) {
        JsonObject result = field("integer", description);
        result.addProperty("minimum", min);
        result.addProperty("maximum", max);
        return result;
    }
    private static JsonObject field(String type, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("description", description);
        return result;
    }

    private static void validate(JsonObject arguments, JsonObject schema) {
        for (JsonElement required : schema.getAsJsonArray("required")) {
            if (!arguments.has(required.getAsString())) throw new IllegalArgumentException("Missing argument: " + required.getAsString());
        }
        JsonObject fields = schema.getAsJsonObject("properties");
        arguments.entrySet().forEach(entry -> {
            String key = entry.getKey();
            if (!fields.has(key)) throw new IllegalArgumentException("Unknown argument: " + key);
            JsonObject field = fields.getAsJsonObject(key);
            JsonElement value = entry.getValue();
            String type = field.get("type").getAsString();
            if (type.equals("object")) {
                if (!value.isJsonObject()) throw new IllegalArgumentException(key + " requires an object");
                return;
            }
            if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid value for " + key);
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            switch (type) {
                case "boolean" -> { if (!primitive.isBoolean()) throw new IllegalArgumentException(key + " requires a boolean"); }
                case "string" -> {
                    if (!primitive.isString() || primitive.getAsString().isBlank()) throw new IllegalArgumentException(key + " requires a nonempty string");
                    if (field.has("enum") && !JSON.fromJson(field.get("enum"), List.class).contains(value.getAsString()))
                        throw new IllegalArgumentException("Unsupported value for " + key);
                }
                case "integer", "number" -> {
                    if (!primitive.isNumber()) throw new IllegalArgumentException(key + " requires a number");
                    var number = primitive.getAsBigDecimal();
                    if (type.equals("integer") && number.stripTrailingZeros().scale() > 0)
                        throw new IllegalArgumentException(key + " requires an integer");
                    if (number.compareTo(field.get("minimum").getAsBigDecimal()) < 0
                            || number.compareTo(field.get("maximum").getAsBigDecimal()) > 0)
                        throw new IllegalArgumentException(key + " is out of range");
                }
                default -> throw new IllegalArgumentException("Unsupported argument type");
            }
        });
    }
}
