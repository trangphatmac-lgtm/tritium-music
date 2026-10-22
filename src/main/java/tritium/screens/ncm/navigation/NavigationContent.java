package tritium.screens.ncm.navigation;

import tritium.i18n.I18n;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.PlaylistPanel;
import tritium.utils.Location;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** An ordered snapshot shared by every cover presentation; filtering never changes playback order. */
public final class NavigationContent {
    public record Entry(String key, String title, String subtitle, String searchText,
                        Location cover, String coverUrl, boolean collection, Runnable activate, String badge) {
        public Entry(String key, String title, String subtitle, String searchText, Location cover, String coverUrl, boolean collection, Runnable activate) {
            this(key, title, subtitle, searchText, cover, coverUrl, collection, activate, "");
        }
        public boolean matches(String query) { return searchText.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
    }
    private final String key;
    private final Supplier<String> title;
    private final Supplier<? extends List<?>> source;
    private final BooleanSupplier loading;
    private final boolean tracks;
    private BiConsumer<List<Music>, Integer> play = CloudMusic::play;
    private tritium.i18n.Language lastLanguage;
    private List<?> lastSource;
    private int lastSize = -1;
    private List<Entry> entries = List.of();

    private NavigationContent(String key, Supplier<String> title, Supplier<? extends List<?>> source, BooleanSupplier loading, boolean tracks) {
        this.key = key; this.title = title; this.source = source; this.loading = loading; this.tracks = tracks;
    }
    public static NavigationContent playlists(String key, Supplier<String> title, Supplier<List<PlayList>> source, BooleanSupplier loading) {
        return new NavigationContent(key, title, source, loading, false);
    }
    public static NavigationContent tracks(PlayList playlist) {
        return tracks(playlist, CloudMusic::play);
    }
    static NavigationContent tracks(PlayList playlist, BiConsumer<List<Music>, Integer> play) {
        var content = new NavigationContent("playlist:" + playlist.getId(), () -> safe(playlist.getName(), "nav.playlists"),
                () -> playlist.musics, () -> !playlist.musicsLoaded && playlist.musicsQueried, true);
        content.play = play;
        return content;
    }
    public String key() { return key; }
    public String title() { return title.get(); }
    public boolean isCollectionLibrary() { return !tracks; }
    public boolean loading() { return loading.getAsBoolean(); }
    public List<Entry> entries() {
        List<?> current = source.get();
        int size = current == null ? 0 : current.size();
        if (current == lastSource && size == lastSize && lastLanguage == I18n.language()) return entries;
        lastLanguage = I18n.language();
        lastSource = current; lastSize = size;
        if (current == null) return entries = List.of();
        List<Entry> result = new ArrayList<>();
        if (tracks) {
            List<Music> queue = current.stream().filter(Music.class::isInstance).map(Music.class::cast).toList();
            for (int i = 0; i < queue.size(); i++) {
                Music music = queue.get(i);
                int position = i;
                String title = safe(music.getName(), "track.unknown");
                String artist = music.getArtists() == null ? "" : music.getArtists().stream()
                        .filter(a -> a != null && a.getName() != null).map(a -> a.getName()).reduce((a,b) -> a + ", " + b).orElse("");
                String album = music.getAlbum() == null ? "" : safe(music.getAlbum().getName(), "album.unknown");
                result.add(new Entry(music.getId() + ":" + i, title, artist, title + " " + artist + " " + album + " " + music.getTranslatedNames(),
                        music.getCoverLocation(), music.getCoverUrl(1024), false, () -> play.accept(queue, position)));
            }
        } else {
            for (Object object : current.toArray()) {
                if (!(object instanceof PlayList playlist)) continue;
                String title = safe(playlist.getName(), "nav.playlists");
                String subtitle = playlist.getCreator() == null ? "" : safe(playlist.getCreator().getName(), "track.unknown");
                result.add(new Entry("playlist:" + playlist.getId(), title, subtitle, title + " " + subtitle,
                        playlist.getCoverLocation(), playlist.getCoverUrl() == null || playlist.getCoverUrl().isBlank() ? "" : playlist.getCoverUrl() + "?param=1024y1024",
                        true, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playlist)), I18n.songCount(playlist.getCount())));
            }
        }
        return entries = List.copyOf(result);
    }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? I18n.text(fallback) : value; }
}
