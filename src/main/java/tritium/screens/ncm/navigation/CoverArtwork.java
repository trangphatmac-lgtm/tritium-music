package tritium.screens.ncm.navigation;

import tritium.rendering.TextureManager;
import tritium.utils.Location;
import tritium.rendering.texture.ITextureObject;
import tritium.rendering.texture.Textures;
import java.util.HashMap;
import java.util.Map;

/** Visible-only requests with a cooldown for missing artwork; no eager whole-library downloads. */
public final class CoverArtwork {
    private static final Map<String, Long> requested = new HashMap<>();
    public ITextureObject texture(NavigationContent.Entry entry) {
        // A list thumbnail may already occupy entry.cover(); never let it prevent a large request.
        var location = Location.of(entry.cover() + "/navigation-1024");
        var texture = TextureManager.getInstance().getTexture(location);
        long now = System.currentTimeMillis();
        if (texture == null && !entry.coverUrl().isBlank() && now - requested.getOrDefault(location.toString(), 0L) > 30_000) {
            if (requested.size() > 1024) requested.clear();
            requested.put(location.toString(), now);
            Textures.downloadTextureAndLoadAsync(entry.coverUrl(), location);
        }
        return texture != null ? texture : TextureManager.getInstance().getTexture(entry.cover());
    }
}
