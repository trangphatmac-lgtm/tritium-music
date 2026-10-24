package tritium.desktop.media;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.PlaybackState;
import tritium.ncm.music.dto.Music;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.ncm.music.PlaybackState.Status.*;

class MediaSessionTest {
    private final RecordingBackend backend = new RecordingBackend();
    private final MediaSession session = new MediaSession(backend);
    private final Music song = new Gson().fromJson("""
            {"id":1,"name":"歌曲 🎵","ar":[{"name":"歌手"}],"al":{"name":"专辑","picUrl":"https://example.com/cover.jpg"}}
            """, Music.class);

    @Test void synchronizesMetadataAndThrottlesNormalProgress() {
        session.update(state(PLAYING, 1_000), 0);
        assertEquals("歌曲 🎵", backend.metadata.title());
        assertEquals("歌手", backend.metadata.artist());
        assertEquals("专辑", backend.metadata.album());
        assertEquals("https://example.com/cover.jpg?param=512y512", backend.metadata.artworkUrl());
        session.update(state(PLAYING, 1_250), 250_000_000);
        assertEquals(1, backend.metadataUpdates);
        assertEquals(1, backend.playbackUpdates);
        assertEquals(1, backend.timelineUpdates);
        session.update(state(PLAYING, 6_000), 5_000_000_000L);
        assertEquals(2, backend.timelineUpdates);
    }

    @Test void immediatelyPublishesPauseSeekAndResume() {
        session.update(state(PLAYING, 1_000), 0);
        session.update(state(PAUSED, 1_250), 250_000_000);
        session.update(state(PAUSED, 40_000), 500_000_000);
        assertEquals(3, backend.timelineUpdates);
        assertEquals(40_000, backend.position);
        session.update(state(PLAYING, 40_000), 750_000_000);
        assertEquals(3, backend.playbackUpdates);
        assertEquals(4, backend.timelineUpdates);
    }

    @Test void replacesMetadataForAutoMixAndClearsAnEmptySessionOnce() {
        session.update(state(PLAYING, 50_000), 0);
        Music incoming = new Gson().fromJson("{\"id\":2,\"name\":\"Incoming\"}", Music.class);
        session.update(new PlaybackState(incoming, PLAYING, 2_000, 90_000, true, true, true, false), 1);
        assertEquals(2, backend.metadataUpdates);
        assertEquals("", backend.metadata.artworkUrl());
        assertEquals("", backend.metadata.artist());
        assertEquals("", backend.metadata.album());
        assertFalse(backend.playback.canNext());
        assertEquals(2_000, backend.position);
        session.update(PlaybackState.empty(), 2);
        session.update(PlaybackState.empty(), 3);
        assertEquals(1, backend.clears);
        session.update(state(PLAYING, 0), 4);
        assertEquals(3, backend.metadataUpdates);
    }

    @Test void clampsInvalidTimelineAndUpdatesChangedDuration() {
        var invalid = new PlaybackState(song, PLAYING, 100, -1, true, true, false, false);
        assertEquals(0, invalid.positionMillis());
        assertEquals(0, invalid.durationMillis());
        session.update(state(PAUSED, 20_000), 0);
        session.update(new PlaybackState(song, PAUSED, 20_000, 10_000, true, true, false, true), 1);
        assertEquals(10_000, backend.position);
        assertEquals(2, backend.timelineUpdates);
    }

    private PlaybackState state(PlaybackState.Status status, long position) {
        return new PlaybackState(song, status, position, 60_000, true, true, false, true);
    }

    private static class RecordingBackend implements MediaSession.Backend {
        int metadataUpdates, playbackUpdates, timelineUpdates, clears;
        long position;
        MediaSession.Metadata metadata;
        PlaybackState playback;
        public void metadata(MediaSession.Metadata value) { metadata = value; metadataUpdates++; }
        public void playback(PlaybackState value) { playback = value; playbackUpdates++; }
        public void timeline(long position, long duration) { this.position = position; timelineUpdates++; }
        public void clear() { clears++; }
        public void close() { }
    }
}
