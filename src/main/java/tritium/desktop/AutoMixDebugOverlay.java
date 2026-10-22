package tritium.desktop;

import org.lwjgl.opengl.GL11;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.automix.AutoMixDebugSnapshot;
import tritium.ncm.music.automix.AutoMixAutomation;
import tritium.ncm.music.automix.SongFeatures;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only overlay, drawn after the screen so it also works over lyrics. It never takes input focus. */
final class AutoMixDebugOverlay {
    private static final int TEXT = 0xFFE3E8EF, MUTED = 0xFFA2ADBD, ACCENT = 0xFF78D8DF;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static AutoMixDebugSnapshot lastSnapshot;
    private static String lastLanguage;
    private static boolean lastEnabled;
    private static double lastWidth;
    private static List<Line> wrapped = List.of();

    record Line(String text, int color) { }

    static void render() {
        if (!DesktopAppState.preferences().autoMixDebug().getValue()) return;
        render(CloudMusic.autoMixDebugSnapshot(), DesktopAppState.preferences().autoMix().getValue(),
                RenderSystem.getWidth(), RenderSystem.getHeight());
    }

    static void render(AutoMixDebugSnapshot snapshot, boolean enabled, double viewportWidth, double viewportHeight) {
        if (viewportWidth < 100 || viewportHeight < 180) return;
        double width = Math.min(520, viewportWidth - 24);
        String language = I18n.language().tag();
        if (snapshot != lastSnapshot || !language.equals(lastLanguage) || enabled != lastEnabled || width != lastWidth) {
            List<Line> lines = new ArrayList<>();
            for (Line line : describe(snapshot, enabled)) {
                for (String part : FontManager.pf14.fitWidth(line.text(), width - 28)) {
                    lines.add(new Line(part, line.color()));
                }
            }
            wrapped = List.copyOf(lines);
            lastSnapshot = snapshot;
            lastLanguage = language;
            lastEnabled = enabled;
            lastWidth = width;
        }
        double height = 51 + wrapped.size() * 18;
        // Leave room for the player and the bottom settings toggles, also in a small window.
        double scale = Math.min(1, (viewportHeight - 150) / height);
        double x = viewportWidth - width * scale - 12;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, 44, 0);
            GL11.glScaled(scale, scale, 1);
            Rect.draw(0, 0, width, height, 0xED151A22);
            Rect.draw(0, 0, 3, height, ACCENT);
            FontManager.pf16bold.drawString(I18n.text("settings.autoMixDebug"), 14, 12, 0xFFFFFFFF);
            double y = 38;
            for (Line line : wrapped) {
                FontManager.pf14.drawString(line.text(), 14, y, line.color());
                y += 18;
            }
            // Separate bars show the actual fade multipliers, after headroom normalization.
            double barWidth = (width - 38) / 2;
            gainBar(14, height - 10, barWidth, snapshot.voiceA(), ACCENT);
            gainBar(24 + barWidth, height - 10, barWidth, snapshot.voiceB(), 0xFFDEA2DA);
        } finally { GL11.glPopMatrix(); }
    }

    private static void gainBar(double x, double y, double width, AutoMixDebugSnapshot.Voice voice, int color) {
        Rect.draw(x, y, width, 3, 0xFF394251);
        if (voice != null && !voice.finished()) Rect.draw(x, y, width * Math.clamp(voice.gain(), 0, 1), 3, color);
    }

    /** Kept independent of GL so diagnostics, units, localization and empty states can be verified. */
    static List<Line> describe(AutoMixDebugSnapshot s, boolean enabled) {
        List<Line> rows = new ArrayList<>();
        add(rows, "state", I18n.text("autoMixDebug." + (enabled ? "enabled" : "disabled")), status(s.status()), detail(s.status(), s.detail()));
        add(rows, "context", s.mode().isEmpty() ? "-" : I18n.text("playMode." + s.mode()),
                clean(s.quality()), Long.toString(s.revision()), clock(s.capturedAtMillis()));
        rows.add(new Line("A  " + track(s.a()), ACCENT));
        rows.add(new Line("B  " + track(s.b()), 0xFFDEA2DA));
        if (s.plan() != null) {
            var p = s.plan();
            add(rows, "plan", I18n.text("autoMixDebug.strategy." + p.strategy()), p.outgoingCurve().name(),
                    p.incomingCurve().name(), I18n.text("autoMixDebug." + (p.filter() ? "filterOn" : "bypass")));
            add(rows, "timelineA", seconds(p.startAMillis()), seconds(p.switchAMillis()), seconds(p.endAMillis()));
            add(rows, "timelineB", seconds(p.startBMillis()), seconds(p.endBMillis()), seconds(p.totalDurationMillis()));
            if (p.fullMode()) {
                add(rows, "fullMode", p.automation().branch(), seconds(p.incomingStartAMillis()));
                automation(rows, "A", p.automation().a(), s.voiceA());
                automation(rows, "B", p.automation().b(), s.voiceB());
            } else rows.add(new Line(I18n.text("autoMixDebug.basicMode"), MUTED));
            double position = s.voiceA() == null ? Double.NaN : s.voiceA().positionMillis();
            double remainingA = s.voiceA() != null && s.voiceA().finished() ? 0 : p.endAMillis() - position;
            double remainingB = s.voiceB() == null ? 0 : p.endBMillis() - s.voiceB().positionMillis();
            if (p.fullMode() && position < p.incomingStartAMillis())
                remainingB = p.incomingStartAMillis() - position + p.endBMillis() - p.startBMillis();
            double remaining = p.fullMode() ? Math.max(remainingA, remainingB) : remainingA;
            add(rows, "handoff", s.switched() ? "B" : "A", s.mixing() ? seconds(Math.max(0, remaining))
                    : seconds(Math.max(0, p.startAMillis() - position)), I18n.text("autoMixDebug." + (s.mixing() ? "untilEnd" : "untilStart")));
        } else rows.add(new Line(I18n.text("autoMixDebug.noPlan"), MUTED));
        voice(rows, "A", s.voiceA());
        voice(rows, "B", s.voiceB());
        features(rows, "A", s.featuresA());
        features(rows, "B", s.featuresB());
        rows.add(new Line(I18n.text("autoMixDebug.history"), ACCENT));
        if (s.events().isEmpty()) rows.add(new Line("-", MUTED));
        for (var e : s.events()) {
            rows.add(new Line(clock(e.timeMillis()) + "  " + status(e.status()) + detail(e.status(), e.detail()), MUTED));
        }
        rows.add(new Line(I18n.text("autoMixDebug.footer"), MUTED));
        return List.copyOf(rows);
    }

    private static void automation(List<Line> rows, String label, AutoMixAutomation.Deck deck, AutoMixDebugSnapshot.Voice voice) {
        var gain = deck.gain();
        add(rows, "fade", label, seconds(gain.startMillis()), seconds(gain.endMillis()),
                gain.enabled() ? gain.curve().name() : I18n.text("autoMixDebug.hold"), Integer.toString(gain.sampleCount()));
        for (int index = 0; index < deck.filters().size(); index++) {
            var eq = deck.filters().get(index);
            double actual = voice != null && index < voice.equalizer().size() ? voice.equalizer().get(index).frequencyHz() : 0;
            add(rows, "eqStage", label, Integer.toString(index + 1), eq.type() == AutoMixAutomation.FilterType.LOW_PASS ? "LP" : "HP",
                    seconds(eq.startMillis()), seconds(eq.endMillis()), hz(eq.fromHz()), hz(eq.toHz()),
                    Integer.toString(eq.sampleMillis()), hz(actual));
        }
    }

    private static void voice(List<Line> rows, String label, AutoMixDebugSnapshot.Voice v) {
        if (v == null) { add(rows, "voiceMissing", label); return; }
        add(rows, "voice", label, seconds(v.positionMillis()), seconds(v.durationMillis()),
                I18n.text("autoMixDebug." + (v.finished() ? "ended" : v.paused() ? "paused" : "playing")),
                percent(v.volume()), percent(v.gain()), percent(v.outputLevel()));
        add(rows, "filters", label, hz(v.lowPassHz()), hz(v.highPassHz()));
    }

    private static void features(List<Line> rows, String label, SongFeatures.Summary f) {
        if (f == null) { add(rows, "featuresPending", label); return; }
        add(rows, "features", label, I18n.text("autoMixDebug." + (f.available() ? "available" : "missing")),
                number(f.bpm()), clean(f.key()), Integer.toString(f.downbeats()), clean(f.genre()));
        add(rows, "cues", label, seconds(f.durationMillis()), seconds(f.silenceStart()), seconds(f.silenceEnd()),
                seconds(f.vocalStart()), seconds(f.vocalEnd()));
        if (f.excludedGenre()) add(rows, "excludedGenre", label);
    }

    private static void add(List<Line> rows, String key, String... values) {
        rows.add(new Line(I18n.text("autoMixDebug." + key, (Object[]) values), TEXT));
    }
    private static String status(AutoMixDebugSnapshot.Status value) { return I18n.text("autoMixDebug.status." + value); }
    private static String detail(AutoMixDebugSnapshot.Status status, String value) {
        if (value == null || value.isBlank()) return "";
        return " / " + (status == AutoMixDebugSnapshot.Status.COMPLETED ? I18n.text("autoMixDebug.strategy." + value) : clean(value));
    }
    private static String track(AutoMixDebugSnapshot.Track track) { return track == null ? "-" : track.id() + "  " + clean(track.name()); }
    private static String clock(long time) { return time <= 0 ? "-" : CLOCK.format(Instant.ofEpochMilli(time)); }
    private static String seconds(double millis) { return Double.isFinite(millis) ? String.format(Locale.ROOT, "%.3fs", millis / 1000) : "-"; }
    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f", value) : "-"; }
    private static String percent(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f%%", value * 100) : "-"; }
    private static String hz(double value) { return value > 0 ? String.format(Locale.ROOT, "%.0f Hz", value) : I18n.text("autoMixDebug.bypass"); }
    private static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        String text = value.replaceAll("[\\p{Cntrl}§]", " ");
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
