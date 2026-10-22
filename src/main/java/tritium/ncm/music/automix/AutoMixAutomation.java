package tritium.ncm.music.automix;

import java.util.List;
import java.util.Objects;

/** Independent media-clock automation. Volume sampling matches the reference's left-value hold;
 * the 80 ms EQ sweep is a desktop interpretation of the native endpoint protocol. */
public record AutoMixAutomation(double incomingStartAMillis, Deck a, Deck b, String branch) {
    public static final int VOLUME_SAMPLE_MILLIS = 50;
    public static final int EQ_SWEEP_MILLIS = 80;
    public static final int MAX_FILTERS = 8;

    public AutoMixAutomation {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        Objects.requireNonNull(branch);
        if (!Double.isFinite(incomingStartAMillis) || incomingStartAMillis < 0)
            throw new IllegalArgumentException("Invalid incoming start");
    }

    public enum FilterType { LOW_PASS, HIGH_PASS }
    /** Stable slot index, zero frequency means bypass. */
    public record FilterSetting(FilterType type, double frequencyHz) {
        public FilterSetting {
            Objects.requireNonNull(type);
            if (!Double.isFinite(frequencyHz) || frequencyHz < 0 || frequencyHz > 20_000)
                throw new IllegalArgumentException("Invalid EQ frequency");
        }
    }

    public record Gain(double startMillis, double endMillis, AutoMixPlan.Curve curve, boolean fadeIn, boolean enabled) {
        public Gain {
            window(startMillis, endMillis);
            Objects.requireNonNull(curve);
        }
        public double durationMillis() { return endMillis - startMillis; }
        public int sampleCount() { return enabled ? (int) Math.ceil(durationMillis() / VOLUME_SAMPLE_MILLIS) + 1 : 0; }
        public double valueAt(double millis) {
            if (!enabled) return 1;
            double t = heldOffset(millis, startMillis, endMillis, VOLUME_SAMPLE_MILLIS);
            double value = curve.value(t / durationMillis());
            return fadeIn ? value : 1 - value;
        }
    }

    public record EqSweep(double startMillis, double endMillis, FilterType type,
                          double fromHz, double toHz, int sampleMillis, boolean legacySteps) {
        public EqSweep(double startMillis, double endMillis, FilterType type, double fromHz, double toHz) {
            this(startMillis, endMillis, type, fromHz, toHz, EQ_SWEEP_MILLIS, false);
        }
        public EqSweep {
            window(startMillis, endMillis);
            Objects.requireNonNull(type);
            if (!Double.isFinite(fromHz) || !Double.isFinite(toHz) || fromHz < 10 || toHz < 10
                    || fromHz > 20_000 || toHz > 20_000 || sampleMillis < 1)
                throw new IllegalArgumentException("Invalid EQ sweep");
        }
        /** Old eq_values protocol: 300/500 ms grid, merge final gap below 200 ms, log values by index. */
        public static EqSweep legacy(double start, double end, FilterType type, double from, double to) {
            return new EqSweep(start, end, type, from, to, end - start <= 4_000 ? 300 : 500, true);
        }
        public double frequencyAt(double millis) {
            if (!Double.isFinite(millis) || millis < startMillis || millis > endMillis) return 0;
            double duration = endMillis - startMillis;
            if (millis == endMillis) return toHz;
            double progress;
            if (legacySteps) {
                int grids = (int) Math.floor(duration / sampleMillis);
                double remainder = duration - grids * (double) sampleMillis;
                boolean extraEnd = remainder > 0;
                boolean merge = extraEnd && remainder < 200;
                int intervals = grids + (extraEnd ? 1 : 0) - (merge ? 1 : 0);
                if (intervals == 0) return 0; // The merged table contains only the final point.
                int index = (int) Math.floor((millis - startMillis) / sampleMillis);
                if (merge && index >= grids) index = Math.max(0, grids - 1);
                progress = (double) index / intervals;
            } else progress = heldOffset(millis, startMillis, endMillis, sampleMillis) / duration;
            return fromHz * Math.pow(toHz / fromHz, Math.clamp(progress, 0, 1));
        }
    }

    public record Deck(Gain gain, List<EqSweep> filters) {
        public Deck {
            Objects.requireNonNull(gain);
            filters = List.copyOf(filters);
            if (filters.size() > MAX_FILTERS) throw new IllegalArgumentException("Too many EQ stages");
        }
        public double startMillis() {
            double result = gain.startMillis();
            for (EqSweep filter : filters) result = Math.min(result, filter.startMillis());
            return result;
        }
        public double endMillis() {
            double result = gain.endMillis();
            for (EqSweep filter : filters) result = Math.max(result, filter.endMillis());
            return result;
        }
        public List<FilterSetting> filtersAt(double millis) {
            return filters.stream().map(f -> new FilterSetting(f.type(), f.frequencyAt(millis))).toList();
        }
    }

    private static void window(double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end - start > 60_000)
            throw new IllegalArgumentException("Invalid automation window");
    }
    private static double heldOffset(double millis, double start, double end, int interval) {
        if (Double.isNaN(millis) || millis <= start) return 0;
        if (millis >= end) return end - start;
        return Math.floor((millis - start) / interval) * interval;
    }
}
