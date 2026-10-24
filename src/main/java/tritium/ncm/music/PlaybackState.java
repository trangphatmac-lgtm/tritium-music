package tritium.ncm.music;

import tritium.ncm.music.dto.Music;

/** A consistent view of the active deck, including during an AutoMix handover. */
public record PlaybackState(Music song, Status status, long positionMillis, long durationMillis,
                            boolean canPlay, boolean canPause, boolean canPrevious, boolean canNext) {
    public enum Status { CLOSED, OPENING, STOPPED, PLAYING, PAUSED }

    public PlaybackState {
        durationMillis = Math.max(0, durationMillis);
        positionMillis = Math.clamp(positionMillis, 0, durationMillis);
    }

    public static PlaybackState empty() {
        return new PlaybackState(null, Status.CLOSED, 0, 0, false, false, false, false);
    }
}
