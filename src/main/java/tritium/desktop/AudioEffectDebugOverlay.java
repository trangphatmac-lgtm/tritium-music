package tritium.desktop;

import org.lwjgl.opengl.GL11;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.effects.AudioEffectDebugSnapshot;
import tritium.ncm.music.effects.AudioEffectService;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Same presentation as AutoMix diagnostics; never takes input focus or starts effect lookups. */
final class AudioEffectDebugOverlay {
    private static final int TEXT = 0xFFE3E8EF, MUTED = 0xFFA2ADBD, ACCENT = 0xFFDEA2DA;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static long lastPoll;
    private static AudioEffectDebugSnapshot polled, lastSnapshot;
    private static String lastLanguage;
    private static double lastWidth;
    private static List<Line> wrapped = List.of();

    record Line(String text, int color) { }

    static void render() {
        if (!DesktopAppState.preferences().audioEffectDebug().getValue()) { lastPoll = 0; return; }
        long now = System.nanoTime();
        if (lastPoll == 0 || now - lastPoll >= 100_000_000L) {
            polled = CloudMusic.audioEffectDebugSnapshot();
            lastPoll = now;
        }
        render(polled, RenderSystem.getWidth(), RenderSystem.getHeight(), DesktopAppState.preferences().autoMixDebug().getValue());
    }

    static void render(AudioEffectDebugSnapshot snapshot, double viewportWidth, double viewportHeight, boolean both) {
        if (viewportWidth < 100 || viewportHeight < 180) return;
        var area = DebugOverlayLayout.area(viewportWidth, viewportHeight, both, true);
        double width = area.width();
        String language = I18n.language().tag();
        if (!Objects.equals(snapshot, lastSnapshot) || !language.equals(lastLanguage) || width != lastWidth) {
            List<Line> lines = new ArrayList<>();
            for (Line line : describe(snapshot)) {
                FontManager.pf14.prepareGlyphs(line.text());
                for (String part : FontManager.pf14.fitWidth(line.text(), width - 28)) lines.add(new Line(part, line.color()));
            }
            wrapped = List.copyOf(lines);
            lastSnapshot = snapshot; lastLanguage = language; lastWidth = width;
        }
        double height = 48 + wrapped.size() * 18;
        double scale = Math.min(1, area.height() / height);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(area.x() + width * (1 - scale), area.y(), 0);
            GL11.glScaled(scale, scale, 1);
            Rect.draw(0, 0, width, height, 0xED151A22);
            Rect.draw(0, 0, 3, height, ACCENT);
            FontManager.pf16bold.drawString(I18n.text("settings.audioEffectDebug"), 14, 12, 0xFFFFFFFF);
            double y = 38;
            for (Line line : wrapped) {
                FontManager.pf14.drawString(line.text(), 14, y, line.color());
                y += 18;
            }
        } finally { GL11.glPopMatrix(); }
    }

    static List<Line> describe(AudioEffectDebugSnapshot snapshot) {
        List<Line> rows = new ArrayList<>();
        var selected = snapshot.selection();
        add(rows, "mode", I18n.text("audioEffect.mode." + selected.mode()));
        if ("Master".equals(selected.mode())) add(rows, "requestedTone", tone(selected.mode(), selected.tone()));
        if ("Manual".equals(selected.mode())) add(rows, "requestedEnhancements", enhancements(selected.diy()));
        if (snapshot.decks().isEmpty()) rows.add(new Line(I18n.text("audioEffect.status." +
                ("Off".equals(selected.mode()) ? "off" : "ready")), MUTED));
        for (var deck : snapshot.decks()) {
            rows.add(new Line(I18n.text("audioEffectDebug.deck", I18n.text("audioEffectDebug." +
                    (deck.current() ? "current" : "other")), deck.songId()), ACCENT));
            add(rows, "binding", I18n.text("audioEffect.mode." + deck.selection().mode()), deck.revision(),
                    deck.updatedAtMillis() <= 0 ? "-" : CLOCK.format(Instant.ofEpochMilli(deck.updatedAtMillis())));
            AudioEffectService.Result result = deck.result();
            if (result == null) { add(rows, "pending"); continue; }
            rows.add(new Line(I18n.text("audioEffect.status." + result.status(), tone(deck.selection().mode(), result.tone())), TEXT));
            switch (deck.selection().mode()) {
                case "Smart" -> add(rows, "smart", clean(result.diagnostics().get("soundKey")));
                case "Master" -> {
                    add(rows, "resolvedTone", tone("Master", result.tone()), clean(result.tone()));
                    add(rows, "usedEnhancements", enhancements(result.usedDiy()));
                }
                case "Emotion" -> {
                    add(rows, "emotion", tone("Emotion", result.tone()), clean(result.diagnostics().get("template")));
                    add(rows, "model", clean(result.diagnostics().get("emotionIndex")), clean(result.diagnostics().get("genreIndex")),
                            I18n.text("audioEffectDebug." + ("true".equals(result.diagnostics().get("inferenceEligible")) ? "eligible" : "ineligible")));
                    String reason = result.diagnostics().get("reason");
                    if (reason != null && !reason.isBlank()) add(rows, "reason", clean(reason));
                }
                case "Manual" -> add(rows, "usedEnhancements", enhancements(result.usedDiy()));
                default -> { }
            }
            var dsp = deck.dsp();
            if (dsp == null) { add(rows, "pending"); continue; }
            add(rows, "dsp", number(dsp.sampleRate()), I18n.text("audioEffectDebug." + (dsp.active() ? "active" : "bypass")));
            if (!dsp.modules().isEmpty()) {
                add(rows, "chain", dsp.modules().stream().map(module -> module.name().toUpperCase(Locale.ROOT)).collect(Collectors.joining(" → ")));
                for (var module : dsp.modules()) {
                    String parameters = module.parameters().stream().map(p -> p.name() + "=" + number(p.value()) +
                            (p.unit().isEmpty() ? "" : " " + p.unit())).collect(Collectors.joining(" · "));
                    rows.add(new Line(module.name().toUpperCase(Locale.ROOT) + "  " + parameters, TEXT));
                }
            }
        }
        rows.add(new Line(I18n.text("audioEffectDebug.footer"), MUTED));
        return List.copyOf(rows);
    }

    private static void add(List<Line> rows, String key, Object... values) {
        rows.add(new Line(I18n.text("audioEffectDebug." + key, values), TEXT));
    }
    private static String tone(String mode, String value) {
        if (value == null || value.isBlank()) return "-";
        return I18n.text(("Emotion".equals(mode) ? "audioEffect.emotion." : "audioEffect.tone.") + value);
    }
    private static String enhancements(List<String> values) {
        if (values.isEmpty()) return I18n.text("audioEffect.status.noneSelected");
        return values.stream().map(value -> switch (value) {
            case "en_bass" -> I18n.text("settings.audioEffectBass");
            case "en_treble" -> I18n.text("settings.audioEffectTreble");
            case "en_vocal" -> I18n.text("settings.audioEffectVocal");
            case "en_vibe" -> I18n.text("settings.audioEffectVibe");
            case "en_live" -> I18n.text("settings.audioEffectLive");
            default -> clean(value);
        }).collect(Collectors.joining(" / "));
    }
    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "-";
    }
    private static String clean(String value) {
        if (value == null || value.isBlank()) return "-";
        String text = value.replaceAll("[\\p{Cntrl}§]", " ");
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
    }
}
