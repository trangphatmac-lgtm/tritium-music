package tritium.minecraft.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlaybackState {
    public static final int MAX_SPECTRUM_BANDS = 2048;
    public long sequence, trackId = -1, timeline;
    public boolean playing, paused, downloading;
    public double positionMillis, durationMillis, volume = 1, downloadProgress;
    public String downloadSpeed = "";
    public float[] spectrum = new float[0];

    /** Keep transient audio values out of JSON and render math, on both sides of IPC. */
    public PlaybackState normalize() {
        positionMillis = nonNegative(positionMillis);
        durationMillis = nonNegative(durationMillis);
        if (durationMillis > 0) positionMillis = Math.min(positionMillis, durationMillis);
        volume = Math.min(1, nonNegative(volume));
        downloadProgress = Math.min(1, nonNegative(downloadProgress));
        if (downloadSpeed == null) downloadSpeed = "";
        spectrum = spectrum == null ? new float[0] : Arrays.copyOf(spectrum, Math.min(MAX_SPECTRUM_BANDS, spectrum.length));
        for (int i = 0; i < spectrum.length; i++) {
            if (!Float.isFinite(spectrum[i]) || spectrum[i] < 0) spectrum[i] = 0;
        }
        return this;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    public static final class Track {
        public long id = -1, timeline;
        public String title = "", artist = "";
        public List<Line> lyrics = new ArrayList<Line>();
    }

    public static final class Line {
        public long timestamp, duration;
        public String text = "", translation = "", romanization = "";
        public boolean breakLine;
        public List<Word> words = new ArrayList<Word>();
    }

    public static final class Word {
        public String text;
        public long timestamp, duration;
    }

    public static final class Event {
        public String action, id;
        public boolean enabled;
        public double x, y, width, height, scale;
    }
}
