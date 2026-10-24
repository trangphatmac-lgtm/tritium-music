package tritium.ncm.music;

import tritium.ncm.music.dto.Music;

import java.util.List;

/** Durable queue and resume point, independent of audio players and expiring playback URLs. */
public record PlaybackSession(List<Music> songs, int index, long positionMillis, boolean paused,
                              CloudMusic.PlayMode mode, List<Music> nextSongs) {
    public PlaybackSession {
        songs = List.copyOf(songs);
        nextSongs = List.copyOf(nextSongs);
        if (index < 0 || index >= songs.size()) throw new IllegalArgumentException("Invalid playback index");
        positionMillis = Math.max(0, positionMillis);
        long duration = songs.get(index).getDuration();
        if (duration > 0) positionMillis = Math.min(positionMillis, duration - 1);
        // Heartbeat needs a live authenticated recommendation session. Keep its saved queue playable.
        if (mode == null || mode == CloudMusic.PlayMode.Heartbeat) mode = CloudMusic.PlayMode.Sequential;
    }

    public Music song() {
        return songs.get(index);
    }
}
