package tritium.desktop.media;

import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Artist;
import tritium.ncm.music.dto.Music;

import java.util.Objects;
import java.util.stream.Collectors;

/** Platform-independent change detection; keeps native calls out of the rendering loop. */
final class MediaSession {
    record Metadata(long id, String title, String artist, String album, String artworkUrl) {
        static Metadata from(Music song) {
            String artists = song.getArtists() == null ? "" : song.getArtists().stream()
                    .filter(Objects::nonNull).map(Artist::getName).filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            return new Metadata(song.getId(), Objects.toString(song.getName(), ""), artists,
                    song.getAlbum() == null ? "" : Objects.toString(song.getAlbum().getName(), ""),
                    song.getCoverUrl(512));
        }
    }

    // dbus-java requires every directly implemented interface on an exported object to be public.
    public interface Backend extends AutoCloseable {
        void metadata(Metadata metadata);
        void playback(PlaybackState state);
        void timeline(long positionMillis, long durationMillis);
        void clear();
        default void volume(double value) { }
        @Override void close();
    }

    private final Backend backend;
    private Metadata metadata;
    private PlaybackState previous;
    private long timelineAtNanos;
    private long timelinePosition;

    MediaSession(Backend backend) {
        this.backend = backend;
    }

    void update(PlaybackState state, long nowNanos) {
        if (state.song() == null) {
            if (metadata != null) backend.clear();
            metadata = null;
            previous = null;
            return;
        }
        Metadata nextMetadata = Metadata.from(state.song());
        boolean trackChanged = !nextMetadata.equals(metadata);
        boolean playbackChanged = previous == null || state.status() != previous.status()
                || state.durationMillis() != previous.durationMillis()
                || state.canPlay() != previous.canPlay() || state.canPause() != previous.canPause()
                || state.canPrevious() != previous.canPrevious() || state.canNext() != previous.canNext();
        if (trackChanged) backend.metadata(nextMetadata);
        if (trackChanged || playbackChanged) backend.playback(state);
        long elapsed = Math.max(0, (nowNanos - timelineAtNanos) / 1_000_000);
        long expected = timelinePosition + (state.status() == PlaybackState.Status.PLAYING ? elapsed : 0);
        if (trackChanged || playbackChanged || state.durationMillis() != previous.durationMillis()
                || elapsed >= 5_000 || Math.abs(state.positionMillis() - expected) >= 1_000) {
            backend.timeline(state.positionMillis(), state.durationMillis());
            timelineAtNanos = nowNanos;
            timelinePosition = state.positionMillis();
        }
        metadata = nextMetadata;
        previous = state;
    }
}
