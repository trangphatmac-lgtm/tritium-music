package tritium.ncm.music.automix;

import java.util.List;

/** Immutable, bounded diagnostics published by the playback thread, never by the audio callback. */
public record AutoMixDebugSnapshot(long capturedAtMillis, Status status, String detail,
                                   String mode, String quality, long revision,
                                   Track a, Track b, Voice voiceA, Voice voiceB,
                                   SongFeatures.Summary featuresA, SongFeatures.Summary featuresB,
                                   AutoMixPlan plan, boolean mixing, boolean switched, List<Event> events) {
    public enum Status {
        IDLE, DISABLED, SINGLE_REPEAT, NO_NEXT, CONTEXT_CHANGED, WAITING_WINDOW, SHORT_TRACK,
        PAUSED, FEATURES, URL, AUDIO, DECODING, PLANNING, WARMING, READY, NO_PLAN, NO_URL,
        PREPARATION_FAILED, LATE, MIXING, SWITCHED, COMPLETED, CANCELLED, SEEK,
        QUEUE_CHANGED, MODE_CHANGED, STOPPED, INCOMING_ENDED, START_FAILED, TRANSITION_FAILED
    }

    public record Track(long id, String name) { }
    /** Gain is the applied fade multiplier; outputLevel is amplitude, not measured loudness. */
    public record Voice(double positionMillis, double durationMillis, double volume, double gain,
                        double lowPassHz, double highPassHz, boolean paused, boolean finished,
                        List<AutoMixAutomation.FilterSetting> equalizer) {
        public Voice { equalizer = List.copyOf(equalizer); }
        public Voice(double positionMillis, double durationMillis, double volume, double gain,
                     double lowPassHz, double highPassHz, boolean paused, boolean finished) {
            this(positionMillis, durationMillis, volume, gain, lowPassHz, highPassHz, paused, finished, List.of());
        }
        public double outputLevel() { return paused || finished ? 0 : volume * gain; }
    }
    public record Event(long timeMillis, Status status, String detail) { }

    public AutoMixDebugSnapshot {
        events = List.copyOf(events);
    }

    private static final AutoMixDebugSnapshot IDLE = new AutoMixDebugSnapshot(0, Status.IDLE, "", "", "", 0,
            null, null, null, null, null, null, null, false, false, List.of());

    public static AutoMixDebugSnapshot idle() { return IDLE; }
}
