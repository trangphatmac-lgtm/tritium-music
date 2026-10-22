package tritium.ncm.music.automix;

import java.util.List;
import java.util.Map;

import static tritium.ncm.music.automix.AutoMixPlan.Curve.*;
import static tritium.ncm.music.automix.AutoMixPlan.Strategy.*;

/** Feature-driven desktop adaptation of the 9.5.40 strategy order.
 * The Android native tempo engine and experimental/custom-playlist branches are not reproduced.
 * All selected windows are bounded by the decoded tracks and preserve original playback speed. */
public final class AutoMixPlanner {
    public static final double MIN_TRACK_MILLIS = 50_000;
    public static final double CUE_WINDOW_MILLIS = 20_000;
    private static final Map<String, String> MODES = Map.ofEntries(
            Map.entry("B:Major", "1B"), Map.entry("F#:Major", "2B"), Map.entry("Db:Major", "3B"),
            Map.entry("Ab:Major", "4B"), Map.entry("Eb:Major", "5B"), Map.entry("Bb:Major", "6B"),
            Map.entry("F:Major", "7B"), Map.entry("C:Major", "8B"), Map.entry("G:Major", "9B"),
            Map.entry("D:Major", "10B"), Map.entry("A:Major", "11B"), Map.entry("E:Major", "12B"),
            Map.entry("Ab:Minor", "1A"), Map.entry("Eb:Minor", "2A"), Map.entry("Bb:Minor", "3A"),
            Map.entry("F:Minor", "4A"), Map.entry("C:Minor", "5A"), Map.entry("G:Minor", "6A"),
            Map.entry("D:Minor", "7A"), Map.entry("A:Minor", "8A"), Map.entry("E:Minor", "9A"),
            Map.entry("B:Minor", "10A"), Map.entry("F#:Minor", "11A"), Map.entry("Db:Minor", "12A"));

    private AutoMixPlanner() { }

    public static AutoMixPlan plan(SongFeatures a, SongFeatures b, boolean fullMode) {
        AutoMixPlan basic = plan(a, b);
        return fullMode && basic != null ? FullAutoMixPlanner.plan(a, b, basic) : basic;
    }

    /** Null means keep ordinary sequential playback. */
    public static AutoMixPlan plan(SongFeatures a, SongFeatures b) {
        if (a == null || b == null || !validLength(a) || !validLength(b)
                || a.unsupportedGenre() || b.unsupportedGenre()) return null;
        Cues ca = cues(a), cb = cues(b);
        boolean silence = validSilence(a) && validSilence(b);
        double bpmA = correctedBpm(a), bpmB = correctedBpm(b);
        if (a.text("genre").contains("电子") && b.text("genre").contains("电子")) {
            if (bpmA < bpmB && Math.abs(bpmA * 2 - bpmB) / bpmB <= .12) bpmA *= 2;
            else if (bpmB < bpmA && Math.abs(bpmA - bpmB * 2) / (bpmB * 2) <= .12) bpmB *= 2;
        }
        boolean validBpm = validBpm(bpmA) && validBpm(bpmB);
        boolean rhythmData = validBpm && !a.downbeats().isEmpty() && !b.downbeats().isEmpty();
        boolean missing = !a.has("genre") || !b.has("genre") || !rhythmData;
        double vocalA = ca.vocalEnd >= 0 ? ca.vocalEnd : a.durationMillis() - CUE_WINDOW_MILLIS;
        double vocalB = cb.vocalStart >= 0 ? cb.vocalStart : CUE_WINDOW_MILLIS;
        double endA = a.point("silenceTime", "e"), startB = b.point("silenceTime", "s");
        double accA = endA - vocalA, accB = vocalB - startB;
        boolean bpmMatched = validBpm && Math.abs(bpmA - bpmB) / bpmB <= .12;
        boolean harmonic = compatibleKeys(a.text("mode"), b.text("mode"));

        // Rhythm: use the audible drum segment and real downbeats, never an invented beat grid.
        if (rhythmData && ca.drumEnd >= 0 && cb.drumStart >= 0 && bpmMatched && bpmA >= 75 && bpmB >= 75) {
            if (!silence) missing = true;
            else {
                double drumA = Math.max(0, Math.min(ca.drumEnd, endA) - vocalA);
                double drumB = Math.max(0, vocalB - Math.max(cb.drumStart, startB));
                if (Math.max(drumA, drumB) > 1_000) {
                    double duration = Math.min(CUE_WINDOW_MILLIS, Math.max(drumA, drumB));
                    // Native pitch-preserving sync is unavailable: prefer one-bar handoff at differing tempos.
                    if (Math.abs(bpmA - bpmB) >= 3) duration = Math.min(duration, bar(b, bpmB));
                    double aStart = drumA >= drumB ? vocalA : Math.min(ca.drumEnd, endA) - duration;
                    double bStart = Math.max(startB, cb.drumStart);
                    aStart = align(a.downbeats(), aStart, false, 2_000);
                    bStart = align(b.downbeats(), bStart, true, 2_000);
                    AutoMixPlan result = bounded(a, b, RHYTHM, aStart, bStart,
                            Math.min(duration, endA - aStart), EXP, LOG, true);
                    if (result != null) return result;
                }
            }
        }

        // Vocal handoff: keep the tail vocal intact and bring B to full level before its first vocal.
        boolean vocalData = finite(a.energy("endAccEnergy", 1), b.energy("startAccEnergy", 1)) && silence;
        if (ca.vocalEnd >= 0 || cb.vocalStart >= 0) {
            if (!vocalData) missing = true;
            else if (accA >= 1_000 && accB >= 1_000) {
                double duration = Math.min(CUE_WINDOW_MILLIS, Math.min(accA, accB));
                if (validBpm) duration = Math.min(duration, (harmonic ? 4 : 1) * Math.min(bar(a, bpmA), bar(b, bpmB)));
                else if (!harmonic) duration = Math.min(duration, 2_000);
                double aStart = Math.max(vocalA, endA - duration);
                double bStart = Math.max(startB, vocalB - duration);
                AutoMixPlan result = bounded(a, b, VOCAL, aStart, bStart, duration,
                        harmonic ? EXP : S, harmonic ? EXP : S, !harmonic || strongBass(a, b));
                if (result != null) return result;
            }
        }

        // Atmosphere: compatible keys, similar total energy and no competing loud drum tracks.
        boolean atmosphereData = knownKey(a.text("mode")) && knownKey(b.text("mode"))
                && finite(a.energy("endSpectrum", 1), b.energy("startSpectrum", 1),
                a.energy("endAccEnergy", 0), b.energy("startAccEnergy", 0)) && silence;
        if (!atmosphereData) missing = true;
        else if (harmonic && !(a.energy("endSpectrum", 1) > -40 && b.energy("startSpectrum", 1) > -40)
                && Math.abs(a.energy("endAccEnergy", 0) - b.energy("startAccEnergy", 0)) < 10
                && accA >= 1_000 && accB >= 1_000) {
            double duration = Math.min(CUE_WINDOW_MILLIS, Math.min(accA, accB));
            AutoMixPlan result = bounded(a, b, ATMOSPHERE, vocalA, Math.max(startB, vocalB - duration),
                    duration, S, S, true);
            if (result != null) return result;
        }

        // Accompaniment: choose vocal, structure, then whole-bar music cues in that order.
        boolean accompanimentData = validBpm && knownKey(a.text("mode")) && knownKey(b.text("mode")) && vocalData;
        if (!accompanimentData) missing = true;
        else {
            double aCue = first(ca.vocalEnd, ca.structureEnd, ca.musicEnd);
            double bCue = first(cb.vocalStart, cb.structureStart, cb.musicStart);
            if (aCue >= 0 && bCue >= 0 && ((harmonic && bpmMatched)
                    || (a.durationMillis() - aCue >= 1_000 && bCue >= 1_000))) {
                double duration = Math.min(Math.min(endA - aCue, bCue - startB),
                        Math.min(CUE_WINDOW_MILLIS, (harmonic ? 6 : 2) * bar(a, bpmA)));
                AutoMixPlan result = bounded(a, b, ACCOMPANIMENT, aCue,
                        Math.max(startB, bCue - duration), duration, S, LOG, true);
                if (result != null) return result;
            }
        }

        if (silence) return bounded(a, b, SILENCE, endA - 2_000, startB, 2_000, S, S, false);
        // Missing data triggers the reference engine's five-second fallback, not a fabricated smart cue.
        missing |= !a.has("silenceTime") || !b.has("silenceTime");
        return missing ? bounded(a, b, CROSSFADE, a.durationMillis() - 5_000, 0, 5_000, S, S, false) : null;
    }

    private static AutoMixPlan bounded(SongFeatures a, SongFeatures b, AutoMixPlan.Strategy strategy,
                                       double aStart, double bStart, double duration,
                                       AutoMixPlan.Curve out, AutoMixPlan.Curve in, boolean filter) {
        if (!finite(aStart, bStart, duration) || aStart < 0 || bStart < 0 || bStart > CUE_WINDOW_MILLIS
                || aStart < a.durationMillis() - 60_000) return null;
        aStart = Math.floor(aStart);
        bStart = Math.floor(bStart);
        duration = Math.floor(Math.min(duration, Math.min(a.durationMillis() - aStart, b.durationMillis() - bStart)));
        if (duration < 500 || duration > CUE_WINDOW_MILLIS) return null;
        return new AutoMixPlan(strategy, aStart, bStart, aStart + duration, aStart + duration / 2, out, in, filter);
    }

    record Cues(double vocalStart, double vocalEnd, double drumStart, double drumEnd,
                        double structureStart, double structureEnd, double musicStart, double musicEnd) { }

    static Cues cues(SongFeatures f) {
        double vs = cue(f, "voiceTime", true, 0, -40), ve = cue(f, "voiceTime", false, 0, -40);
        double ds = cue(f, "drumTime", true, 1, -40), de = cue(f, "drumTime", false, 1, -100);
        double ss = windowPoint(f, f.paragraphBoundary(true), true);
        double se = windowPoint(f, f.paragraphBoundary(false), false);
        double ms = vs >= 0 ? vs : CUE_WINDOW_MILLIS, me = ve >= 0 ? ve : f.durationMillis() - CUE_WINDOW_MILLIS;
        if (validBpm(correctedBpm(f))) {
            for (int bars = 8; bars >= 1; bars--) {
                double length = bars * bar(f, correctedBpm(f));
                if (length <= CUE_WINDOW_MILLIS && (vs < 0 || length <= vs)) { ms = length; break; }
            }
            for (int bars = 8; bars >= 1; bars--) {
                double length = bars * bar(f, correctedBpm(f));
                if (length <= CUE_WINDOW_MILLIS && (ve < 0 || f.durationMillis() - length >= ve)) {
                    me = f.durationMillis() - length; break;
                }
            }
        }
        return new Cues(vs, ve, ds, de, ss, se, ms, me);
    }

    private static double cue(SongFeatures f, String field, boolean start, int index, double threshold) {
        if (!(f.energy(start ? "startSpectrum" : "endSpectrum", index) > threshold)) return -1;
        return windowPoint(f, f.point(field, start ? "s" : "e"), start);
    }

    private static double windowPoint(SongFeatures f, double value, boolean start) {
        if (!Double.isFinite(value) || value < 0 || value > f.durationMillis()) return -1;
        return (start ? value : f.durationMillis() - value) <= CUE_WINDOW_MILLIS ? value : -1;
    }

    private static boolean validSilence(SongFeatures f) {
        double s = f.point("silenceTime", "s"), e = f.point("silenceTime", "e");
        return finite(s, e) && s >= 0 && s < CUE_WINDOW_MILLIS && e >= f.durationMillis() - CUE_WINDOW_MILLIS
                && e <= f.durationMillis() && s < e;
    }

    private static boolean validLength(SongFeatures f) {
        return Double.isFinite(f.durationMillis()) && f.durationMillis() >= MIN_TRACK_MILLIS;
    }
    private static boolean validBpm(double bpm) { return bpm >= 30 && bpm <= 240; }
    static double bar(SongFeatures f, double bpm) { return 60_000d * f.beatsPerBar() / bpm; }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    private static double first(double... values) {
        for (double value : values) if (value >= 0) return value;
        return -1;
    }
    private static boolean strongBass(SongFeatures a, SongFeatures b) {
        return a.energy("endAccEnergy", 1) > -20 && b.energy("startAccEnergy", 1) > -20;
    }
    static double correctedBpm(SongFeatures f) {
        double bpm = f.bpm();
        if (bpm > 0 && bpm < 60) {
            String genre = f.text("genre");
            if (List.of("电子", "嘻哈说唱", "金属").stream().anyMatch(genre::contains)) return bpm * 2;
        }
        return bpm;
    }

    static double align(List<Double> downbeats, double position, boolean forward, double deviation) {
        double selected = position;
        for (double beat : downbeats) {
            if (forward && beat >= position) { selected = beat; break; }
            if (!forward && beat <= position) selected = beat;
        }
        return Math.abs(selected - position) <= deviation ? selected : position;
    }

    private static String camelot(String raw) {
        String mode = raw.trim();
        if (mode.startsWith("b") && mode.length() > 2) mode = mode.substring(1, 2) + "b" + mode.substring(2);
        if (mode.startsWith("#") && mode.length() > 2) mode = mode.substring(1, 2) + "#" + mode.substring(2);
        mode = mode.replace("Gb:", "F#:").replace("G#:Minor", "Ab:Minor").replace("C#:Minor", "Db:Minor");
        String mapped = MODES.getOrDefault(mode, mode);
        return mapped.matches("([1-9]|1[0-2])[AB]") ? mapped : "";
    }

    private static boolean knownKey(String key) { return !camelot(key).isEmpty(); }
    static boolean compatibleKeys(String first, String second) {
        String a = camelot(first), b = camelot(second);
        if (a.isEmpty() || b.isEmpty()) return false;
        int an = Integer.parseInt(a.substring(0, a.length() - 1)), bn = Integer.parseInt(b.substring(0, b.length() - 1));
        int difference = Math.min(Math.abs(an - bn), 12 - Math.abs(an - bn));
        return difference <= (a.charAt(a.length() - 1) == b.charAt(b.length() - 1) ? 2 : 1);
    }
}
