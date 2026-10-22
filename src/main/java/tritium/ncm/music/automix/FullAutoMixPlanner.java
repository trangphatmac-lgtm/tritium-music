package tritium.ncm.music.automix;

import java.util.ArrayList;
import java.util.List;

import static tritium.ncm.music.automix.AutoMixAutomation.FilterType.*;
import static tritium.ncm.music.automix.AutoMixPlan.Curve.*;
import static tritium.ncm.music.automix.AutoMixPlan.Strategy.*;

/** V2 acoustic parameters applied after the ordinary planner has selected a strategy.
 * Windows follow the supplied static reference; playback remains at its original speed. */
public final class FullAutoMixPlanner {
    private static final double MAX_CUE = AutoMixPlanner.CUE_WINDOW_MILLIS;
    private static final double MAX_WINDOW = 60_000;

    private FullAutoMixPlanner() { }

    public static AutoMixPlan plan(SongFeatures a, SongFeatures b, AutoMixPlan basic) {
        if (basic == null) return null;
        if (a == null || b == null) return basic;
        if (basic.strategy() != VOCAL && basic.strategy() != ACCOMPANIMENT) {
            return sampled(a, b, basic, "bounded_" + basic.strategy().name().toLowerCase(java.util.Locale.ROOT));
        }
        Bpms bpms = bpms(a, b);
        if (!validBpm(bpms.a) || !validBpm(bpms.b)) {
            return sampled(a, b, basic, "fallback_missing_bpm");
        }
        if (!validSilence(a) || !validSilence(b)) {
            return sampled(a, b, basic, "fallback_invalid_silence");
        }
        try {
            return basic.strategy() == VOCAL ? vocal(a, b, basic, bpms) : accompaniment(a, b, basic, bpms);
        } catch (IllegalArgumentException invalidWindow) {
            // Keep the ordinary planner's decoded-duration bounds when V2 cues cannot be represented safely.
            return sampled(a, b, basic, "fallback_invalid_v2_window");
        }
    }

    private static AutoMixPlan vocal(SongFeatures a, SongFeatures b, AutoMixPlan basic, Bpms bpm) {
        var ca = AutoMixPlanner.cues(a);
        var cb = AutoMixPlanner.cues(b);
        double barA = Math.floor(AutoMixPlanner.bar(a, bpm.a));
        double barB = Math.floor(AutoMixPlanner.bar(b, bpm.b));
        if (!AutoMixPlanner.compatibleKeys(a.text("mode"), b.text("mode"))) {
            double duration = 2 * Math.max(barA, barB);
            double keyA;
            if (ca.vocalEnd() >= 0 && ca.vocalEnd() + duration / 2 < a.durationMillis()) {
                keyA = ca.vocalEnd();
                if (ca.structureEnd() >= 0 && ca.structureEnd() + duration / 2 < a.durationMillis()
                        && keyA < ca.structureEnd()) keyA = ca.structureEnd();
                else if (keyA + barA + duration / 2 < a.durationMillis()) keyA += barA;
            } else if (ca.structureEnd() >= 0 && ca.structureEnd() + duration / 2 < a.durationMillis()) {
                keyA = ca.structureEnd();
            } else if (ca.drumEnd() >= 0 && ca.drumEnd() + duration / 2 < a.durationMillis()) {
                keyA = ca.drumEnd();
            } else {
                keyA = ca.musicEnd() < 0 && ca.structureEnd() < 0
                        ? a.durationMillis() - MAX_CUE + duration / 2 : a.point("silenceTime", "e") - duration / 2;
            }
            double keyB;
            if (cb.vocalStart() >= 0 && cb.vocalStart() - duration / 2 > 0) {
                keyB = cb.vocalStart();
                if (cb.structureStart() >= 0 && cb.structureStart() - duration / 2 > 0
                        && keyB > cb.structureStart()) keyB = cb.structureStart();
                else if (keyB - barB - duration / 2 > 0) keyB -= barB;
            } else if (cb.structureStart() >= 0 && cb.structureStart() - duration / 2 > 0) {
                keyB = cb.structureStart();
            } else if (cb.drumStart() >= 0 && cb.drumStart() - duration / 2 > 0) {
                keyB = cb.drumStart();
            } else {
                keyB = cb.vocalStart() < 0 && cb.structureStart() < 0
                        ? MAX_CUE - duration / 2 : b.point("silenceTime", "s") + duration / 2;
            }
            double startA = aligned(a, b, Math.floor(Math.max(0, keyA - duration / 2)), true);
            double startB = aligned(b, a, Math.floor(Math.max(0, keyB - duration / 2)), false);
            var filtersA = new ArrayList<AutoMixAutomation.EqSweep>();
            var filtersB = new ArrayList<AutoMixAutomation.EqSweep>();
            add(filtersA, a, startA + duration / 2 - 500, startA + duration / 2 + 500,
                    LOW_PASS, 20_000, 2_000, false);
            add(filtersB, b, startB, startB + duration / 2 - 500, LOW_PASS, 2_000, 2_000, false);
            add(filtersB, b, startB + duration / 2 - 500, startB + duration / 2 + 500,
                    LOW_PASS, 2_000, 20_000, false);
            return build(a, b, basic, gain(startA, startA + duration, S, false, true),
                    gain(startB, startB + duration, S, true, true), filtersA, filtersB,
                    startA, startA + duration / 2, "key_incompatible");
        }

        double vocalA = ca.vocalEnd() >= 0 ? ca.vocalEnd() : Math.max(0, a.durationMillis() - MAX_CUE);
        double vocalB = cb.vocalStart() >= 0 ? cb.vocalStart() : MAX_CUE;
        double keyA = aligned(a, b, vocalA, true), keyB = aligned(b, a, vocalB, false);
        double drumA = Math.max(0, Math.min(ca.drumEnd(), a.point("silenceTime", "e")) - vocalA);
        double drumB = Math.max(0, vocalB - Math.max(cb.drumStart(), b.point("silenceTime", "s")));
        double reference = Math.max(Math.max(0, a.point("silenceTime", "e") - vocalA),
                Math.max(0, vocalB - b.point("silenceTime", "s")));
        double durationA, durationB, ratioA, ratioB;
        AutoMixPlan.Curve out, in;
        String branch;
        boolean bpmMatched = Math.abs(bpm.a - bpm.b) / bpm.b <= .12;
        double energyA = a.energy("endAccEnergy", 0), energyB = b.energy("startAccEnergy", 0);
        if (bpmMatched) {
            branch = "key_compatible_bpm_matched";
            durationA = capBars(reference, a, bpm.a, 4); durationB = capBars(reference, b, bpm.b, 4);
            out = EXP; in = EXP; ratioA = .4; ratioB = .6;
        } else if (drumA > 2_000 || drumB > 2_000) {
            branch = "long_drum_gap_bpm_mismatch";
            durationA = Math.min(capBars(reference, a, bpm.a, 4), capBars(reference, b, bpm.b, 4));
            durationB = durationA; out = EXP; in = LOG; ratioA = .5; ratioB = .5;
        } else if (presentEnergy(energyA, energyB) && energyA - energyB > 5) {
            branch = "a_energy_higher";
            durationA = capBars(reference, a, bpm.a, 4); durationB = capBars(reference, b, bpm.b, 4);
            out = EXP; in = EXP; ratioA = .4; ratioB = .6;
        } else if (presentEnergy(energyA, energyB) && energyB - energyA > 5) {
            branch = "b_energy_higher";
            durationA = capBars(reference, a, bpm.a, 2); durationB = capBars(reference, b, bpm.b, 2);
            out = LINEAR; in = LINEAR; ratioA = .2; ratioB = .8;
        } else {
            branch = "default_quick_cut";
            durationA = capBars(reference, a, bpm.a, 2); durationB = capBars(reference, b, bpm.b, 2);
            out = EXP; in = EXP; ratioA = .4; ratioB = .6;
        }
        double endA = Math.min(a.durationMillis(), Math.floor(keyA + durationA * (1 - ratioA)));
        if (endA - keyA < 1_000) endA = Math.min(a.durationMillis(), keyA + 1_000);
        durationA = Math.max((endA - keyA) / (1 - ratioA), 1_000);
        double startA = Math.floor(keyA - durationA * ratioA);
        double startB = Math.max(0, Math.floor(keyB - durationB * ratioB));
        if (keyB - startB < 1_000) startB = Math.max(0, keyB - 1_000);
        durationB = Math.max((keyB - startB) / ratioB, 1_000);
        double endB = Math.floor(keyB + durationB * (1 - ratioB));
        var filtersA = new ArrayList<AutoMixAutomation.EqSweep>();
        var filtersB = new ArrayList<AutoMixAutomation.EqSweep>();
        if (branch.equals("long_drum_gap_bpm_mismatch")) {
            add(filtersA, a, startA, endA, LOW_PASS, 16_000, 20, false);
            bandEntry(filtersB, b, startB, endB);
        } else if (presentEnergy(energyA, energyB)) {
            double cutoff = -1;
            if (a.energy("endAccEnergy", 1) > -20 && b.energy("startAccEnergy", 1) > -20) cutoff = 1_000;
            if (a.energy("endAccEnergy", 2) > -20 && b.energy("startAccEnergy", 2) > -20) cutoff = 2_000;
            if (cutoff > 0 && endA - keyA > 2_000) add(filtersA, a, keyA, endA, HIGH_PASS, 20, cutoff, true);
            if (cutoff > 0 && keyB - startB > 2_000) add(filtersB, b, startB, keyB, HIGH_PASS, cutoff, 20, true);
        }
        double incomingStartA = Math.max(0, keyA - (keyB - startB));
        return build(a, b, basic, gain(startA, endA, out, false, true), gain(startB, endB, in, true, true),
                filtersA, filtersB, incomingStartA, keyA, branch);
    }

    private static AutoMixPlan accompaniment(SongFeatures a, SongFeatures b, AutoMixPlan basic, Bpms bpm) {
        var ca = AutoMixPlanner.cues(a);
        var cb = AutoMixPlanner.cues(b);
        double barA = Math.floor(AutoMixPlanner.bar(a, bpm.a));
        double minimum = 2 * barA;
        double maxStart = Math.max(Math.floor(a.point("silenceTime", "e")) - minimum, 0);
        double startA;
        if (ca.vocalEnd() >= 0 && ca.vocalEnd() < maxStart) {
            startA = ca.vocalEnd();
            if (startA + barA < maxStart) startA += barA;
        } else if (ca.structureEnd() >= 0 && ca.structureEnd() < maxStart) startA = ca.structureEnd();
        else startA = a.durationMillis() - Math.max(minimum, MAX_CUE);
        startA = aligned(a, b, startA, true);
        double durationA = minimum;
        if (AutoMixPlanner.compatibleKeys(a.text("mode"), b.text("mode"))) {
            durationA = Math.min(a.point("silenceTime", "e") - startA, capBars(MAX_CUE, a, bpm.a, 6));
        }
        double durationB = durationA * .75;
        double startB = Math.floor(b.point("silenceTime", "s"));
        double cueB = first(cb.vocalStart(), cb.structureStart(), cb.musicStart());
        if (cueB >= 0) startB = Math.floor(Math.max(cueB - durationB / 2, startB));
        startB = aligned(b, a, startB, false);
        double endA = startA + durationA, endB = Math.floor(startB + durationB);
        var filtersA = new ArrayList<AutoMixAutomation.EqSweep>();
        var filtersB = new ArrayList<AutoMixAutomation.EqSweep>();
        double bassA = a.energy("endAccEnergy", 1), bassB = b.energy("startAccEnergy", 1);
        if (presentEnergy(bassA, bassB) && bassA - bassB > 6) {
            add(filtersA, a, startA, endA, HIGH_PASS, 20, 16_000, false);
        } else add(filtersA, a, startA, endA, LOW_PASS, 16_000, 20, false);
        bandEntry(filtersB, b, startB, endB);
        return build(a, b, basic, gain(startA, endA, LINEAR, false, false),
                gain(startB, endB, LOG, true, true), filtersA, filtersB, startA + durationA / 4,
                startA + durationA / 2, "accompaniment_v2");
    }

    private static AutoMixPlan sampled(SongFeatures a, SongFeatures b, AutoMixPlan basic, String branch) {
        var filtersA = new ArrayList<AutoMixAutomation.EqSweep>();
        var filtersB = new ArrayList<AutoMixAutomation.EqSweep>();
        if (basic.filter()) {
            if (basic.strategy() == RHYTHM) {
                add(filtersA, a, basic.startAMillis(), basic.endAMillis(), HIGH_PASS, 20, 20_000, false);
                add(filtersB, b, basic.startBMillis(), basic.endBMillis(), LOW_PASS, 500, 20_000, false);
            } else {
                add(filtersA, a, basic.startAMillis(), basic.endAMillis(), LOW_PASS,
                        basic.strategy() == ACCOMPANIMENT ? 16_000 : 20_000, 20, false);
                add(filtersB, b, basic.startBMillis(), basic.endBMillis(), HIGH_PASS,
                        basic.strategy() == ATMOSPHERE ? 20_000 : 1_000, 20, false);
            }
        }
        return build(a, b, basic, gain(basic.startAMillis(), basic.endAMillis(), basic.outgoingCurve(), false, true),
                gain(basic.startBMillis(), basic.endBMillis(), basic.incomingCurve(), true, true),
                filtersA, filtersB, basic.startAMillis(), basic.switchAMillis(), branch);
    }

    private static AutoMixPlan build(SongFeatures a, SongFeatures b, AutoMixPlan basic,
                                     AutoMixAutomation.Gain gainA, AutoMixAutomation.Gain gainB,
                                     List<AutoMixAutomation.EqSweep> filtersA, List<AutoMixAutomation.EqSweep> filtersB,
                                     double incomingStartA, double switchA, String branch) {
        var deckA = new AutoMixAutomation.Deck(gainA, filtersA);
        var deckB = new AutoMixAutomation.Deck(gainB, filtersB);
        double startA = Math.min(deckA.startMillis(), incomingStartA), endA = deckA.endMillis();
        double startB = deckB.startMillis(), endB = deckB.endMillis();
        if (!validWindow(gainA.startMillis(), gainA.endMillis(), a.durationMillis())
                || !validWindow(gainB.startMillis(), gainB.endMillis(), b.durationMillis())
                || !finite(startA, endA, startB, endB, incomingStartA, switchA)
                || startA < 0 || startA < a.durationMillis() - MAX_WINDOW || endA > a.durationMillis()
                || startB < 0 || startB > MAX_CUE || endB > b.durationMillis()
                || incomingStartA < 0 || incomingStartA >= endA
                || endA - startA > MAX_WINDOW || endB - startB > MAX_WINDOW
                || startB + endA - incomingStartA > b.durationMillis()
                || switchA < startA || switchA > endA) {
            throw new IllegalArgumentException("V2 timeline exceeds decoded audio bounds");
        }
        var automation = new AutoMixAutomation(incomingStartA, deckA, deckB, branch);
        return new AutoMixPlan(basic.strategy(), startA, startB, endA, switchA,
                gainA.curve(), gainB.curve(), !filtersA.isEmpty() || !filtersB.isEmpty(), automation);
    }

    private static AutoMixAutomation.Gain gain(double start, double end, AutoMixPlan.Curve curve,
                                                boolean fadeIn, boolean enabled) {
        return new AutoMixAutomation.Gain(start, end, curve, fadeIn, enabled);
    }

    private static void bandEntry(List<AutoMixAutomation.EqSweep> filters, SongFeatures song, double start, double end) {
        add(filters, song, start, end, LOW_PASS, 4_000, 20_000, false);
        add(filters, song, start, end, HIGH_PASS, 4_000, 20, false);
    }

    private static void add(List<AutoMixAutomation.EqSweep> filters, SongFeatures song, double start, double end,
                            AutoMixAutomation.FilterType type, double from, double to, boolean legacy) {
        if (!finite(start, end) || start < 0 || end > song.durationMillis() || end < start) {
            throw new IllegalArgumentException("Invalid V2 filter window");
        }
        if (end - start < 100) return;
        filters.add(legacy ? AutoMixAutomation.EqSweep.legacy(start, end, type, from, to)
                : new AutoMixAutomation.EqSweep(start, end, type, from, to));
    }

    private static double aligned(SongFeatures song, SongFeatures other, double point, boolean later) {
        if (song.downbeats().isEmpty() || other.downbeats().isEmpty()) return point;
        return Math.floor(AutoMixPlanner.align(song.downbeats(), point, later, 4_000));
    }

    private static double capBars(double duration, SongFeatures song, double bpm, int count) {
        return Math.min(duration, Math.floor(count * AutoMixPlanner.bar(song, bpm)));
    }

    private static boolean validWindow(double start, double end, double length) {
        return finite(start, end, length) && start >= 0 && end <= length && end - start >= 500 && end - start <= MAX_WINDOW;
    }

    private static boolean validSilence(SongFeatures song) {
        double start = song.point("silenceTime", "s"), end = song.point("silenceTime", "e");
        return finite(start, end) && start >= 0 && start <= MAX_CUE && end > start && end <= song.durationMillis();
    }

    private static boolean presentEnergy(double a, double b) { return finite(a, b) && a != 0 && b != 0; }
    private static boolean validBpm(double bpm) { return bpm >= 30 && bpm <= 240; }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    private static double first(double... values) {
        for (double value : values) if (value >= 0) return value;
        return -1;
    }
    private record Bpms(double a, double b) { }
    private static Bpms bpms(SongFeatures a, SongFeatures b) {
        double bpmA = AutoMixPlanner.correctedBpm(a), bpmB = AutoMixPlanner.correctedBpm(b);
        if (a.text("genre").contains("电子") && b.text("genre").contains("电子")) {
            if (bpmA < bpmB && Math.abs(bpmA * 2 - bpmB) / bpmB <= .12) bpmA *= 2;
            else if (bpmB < bpmA && Math.abs(bpmA - bpmB * 2) / (bpmB * 2) <= .12) bpmB *= 2;
        }
        return new Bpms(bpmA, bpmB);
    }
}
