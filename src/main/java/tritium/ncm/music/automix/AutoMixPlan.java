package tritium.ncm.music.automix;

import java.util.Objects;

/** A bounded, immutable transition in each track's own media clock (milliseconds).
 * Playback stays at its original rate; no resampling is used to imitate pitch-preserving tempo sync. */
public record AutoMixPlan(Strategy strategy, double startAMillis, double startBMillis,
                          double endAMillis, double switchAMillis,
                          Curve outgoingCurve, Curve incomingCurve, boolean filter) {
    public enum Strategy { RHYTHM, VOCAL, ATMOSPHERE, ACCOMPANIMENT, SILENCE, CROSSFADE }
    public enum Curve {
        LINEAR, EXP, LOG, S;

        public double value(double position) {
            double x = Double.isNaN(position) ? 0 : Math.clamp(position, 0, 1);
            return switch (this) {
                case LINEAR -> x;
                case EXP -> x * x;
                case LOG -> 1 - (1 - x) * (1 - x);
                case S -> {
                    double edge = 1 / (1 + Math.exp(5));
                    yield Math.clamp((1 / (1 + Math.exp(-10 * (x - .5))) - edge) / (1 - 2 * edge), 0, 1);
                }
            };
        }
    }

    public AutoMixPlan {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(outgoingCurve);
        Objects.requireNonNull(incomingCurve);
        if (!Double.isFinite(startAMillis) || !Double.isFinite(startBMillis)
                || !Double.isFinite(endAMillis) || !Double.isFinite(switchAMillis)
                || startAMillis < 0 || startBMillis < 0 || endAMillis <= startAMillis
                || switchAMillis < startAMillis || switchAMillis > endAMillis) {
            throw new IllegalArgumentException("Invalid AutoMix timeline");
        }
    }

    public double prepareAMillis() { return Math.max(0, startAMillis - 5_000); }
    public double durationMillis() { return endAMillis - startAMillis; }
    public double endBMillis() { return startBMillis + durationMillis(); }
    public double outgoingGain(double aMillis) {
        return 1 - outgoingCurve.value((aMillis - startAMillis) / durationMillis());
    }
    public double incomingGain(double bMillis) {
        return incomingCurve.value((bMillis - startBMillis) / durationMillis());
    }

    /** Gentle logarithmic low-pass exit; zero denotes bypass. */
    public double outgoingLowPassHz(double aMillis) {
        if (!filter || strategy == Strategy.RHYTHM || aMillis < startAMillis) return 0;
        return sweep(strategy == Strategy.ACCOMPANIMENT ? 16_000 : 20_000, 20,
                (aMillis - startAMillis) / durationMillis());
    }

    /** Remove competing bass on entry, restoring the unfiltered signal at completion. */
    public double incomingHighPassHz(double bMillis) {
        if (!filter || strategy == Strategy.RHYTHM || bMillis >= endBMillis()) return 0;
        return sweep(strategy == Strategy.ATMOSPHERE ? 20_000 : 1_000, 20,
                (bMillis - startBMillis) / durationMillis());
    }

    /** Rhythm exchanges the two tracks' frequency roles: remove A's bass as B takes over. */
    public double outgoingHighPassHz(double aMillis) {
        if (!filter || strategy != Strategy.RHYTHM || aMillis < startAMillis) return 0;
        return sweep(20, 20_000, (aMillis - startAMillis) / durationMillis());
    }

    public double incomingLowPassHz(double bMillis) {
        if (!filter || strategy != Strategy.RHYTHM || bMillis >= endBMillis()) return 0;
        return sweep(500, 20_000, (bMillis - startBMillis) / durationMillis());
    }

    private static double sweep(double from, double to, double x) {
        return from * Math.pow(to / from, Math.clamp(Double.isNaN(x) ? 0 : x, 0, 1));
    }
}
