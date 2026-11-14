package tritium.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.dto.PlayList;
import tritium.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared by the explore page and MCP, including calls made before the page is opened. */
public final class PlaylistDiscovery {
    public static final List<PlayList> recommendations = new CopyOnWriteArrayList<>();

    private PlaylistDiscovery() {}

    public static synchronized List<PlayList> loadRecommendations() {
        if (!recommendations.isEmpty()) return List.copyOf(recommendations);
        JsonObject response = CloudMusicApi.recommendResource().toJsonObject();
        if (!response.has("code") || response.get("code").getAsInt() != 200
                || !JsonUtils.isJsonArray(response, "recommend"))
            throw new IllegalStateException("Failed to load recommended playlists. Check your login session.");
        List<PlayList> loaded = new ArrayList<>();
        for (JsonElement entry : response.getAsJsonArray("recommend")) {
            if (!entry.isJsonObject()) continue;
            JsonObject object = entry.getAsJsonObject();
            if (object.has("picUrl")) object.add("coverImgUrl", object.get("picUrl"));
            if (object.has("playcount")) object.add("playCount", object.get("playcount"));
            loaded.add(JsonUtils.parse(object, PlayList.class));
        }
        recommendations.addAll(loaded);
        return List.copyOf(recommendations);
    }
}
