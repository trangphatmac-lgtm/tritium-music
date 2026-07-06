package tritium.desktop.hud;

import tritium.desktop.HudElementPreferences;
import tritium.desktop.HudPreferences;
import tritium.desktop.MusicPreferences;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class MusicSpectrumHudRenderer implements HudRenderer {
    private float[] renderSpectrum = new float[1];
    private float[] renderSpectrumIndicator = new float[1];
    private final Map<Integer, Long> indicatorTimestamps = new HashMap<>();

    @Override
    public String id() {
        return "musicSpectrum";
    }

    @Override
    public String displayName() {
        return "musicSpectrum";
    }

    @Override
    public HudElementPreferences layout(MusicPreferences preferences) {
        return preferences.hud().musicSpectrum();
    }

    @Override
    public void render(Graphics2D graphics, HudRenderContext context) {
        HudStateSnapshot snapshot = context.snapshot;
        HudPreferences.MusicSpectrumPreferences preferences = context.preferences.hud().musicSpectrum();
        Rectangle2D bounds = context.bounds;
        boolean compact = preferences.compact().getValue();

        int length = Math.max(1, snapshot.spectrum.length / 2);
        if (renderSpectrum.length != length) {
            renderSpectrum = Arrays.copyOf(renderSpectrum, length);
            renderSpectrumIndicator = new float[length];
            indicatorTimestamps.clear();
        }

        double multiplier = preferences.multiplier().getValue();
        for (int i = 0; i < length; i++) {
            float source = i < snapshot.spectrum.length ? snapshot.spectrum[i] : 0;
            float target = source * (compact ? 8f : 16f);
            if (!Float.isFinite(target) || snapshot.paused || (!snapshot.playing && !snapshot.smoke)) {
                target = 0;
            }
            float volumeFactor = preferences.absoluteVolume().getValue() ? 1f : (float) (.75f + snapshot.volume * .25f);
            renderSpectrum[i] = HudRenderUtil.smooth(renderSpectrum[i], target, .45f * volumeFactor);
        }

        int color = preferences.color().getValue();
        double opacity = preferences.opacity().getValue();
        if (compact || context.editMode) {
            HudRenderUtil.fillRound(graphics, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(),
                    6, HudRenderUtil.withAlpha(0x66000000, opacity));
        }

        if ("Line".equals(preferences.style().getValue())) {
            renderLine(graphics, bounds, color, opacity, multiplier, compact);
        } else {
            renderRect(graphics, bounds, color, opacity, multiplier, compact, preferences.indicator().getValue(), snapshot.nowMillis);
        }
    }

    private void renderRect(Graphics2D graphics, Rectangle2D bounds, int color, double opacity, double multiplier,
                            boolean compact, boolean indicator, long nowMillis) {
        int step = compact ? 8 : 3;
        double shrink = compact ? 4 : 0;
        double spectrumWidth = Math.max(1, (bounds.getWidth() - shrink * 2) / Math.max(1, renderSpectrum.length / (double) step));
        double baseY = bounds.getY() + bounds.getHeight() - shrink;

        graphics.setColor(new Color(HudRenderUtil.withAlpha(color, opacity), true));
        for (int i = 0; i < renderSpectrum.length; i += step) {
            double height = -renderSpectrum[i] * multiplier * 10;
            if (compact) {
                height = Math.max(height, -bounds.getHeight() + shrink * 2);
            }

            if (indicator) {
                updateIndicator(i, (float) height, nowMillis);
            }

            double x = bounds.getX() + shrink + spectrumWidth * i / step;
            double top = Math.min(baseY, baseY + height);
            double rectHeight = Math.max(1, Math.abs(height));
            graphics.fill(new Rectangle2D.Double(x, top, Math.max(1, spectrumWidth), rectHeight));

            if (indicator) {
                double indicatorY = baseY + renderSpectrumIndicator[i] - 2;
                graphics.fill(new Rectangle2D.Double(x, indicatorY, Math.max(1, spectrumWidth), 1));
            }
        }
    }

    private void renderLine(Graphics2D graphics, Rectangle2D bounds, int color, double opacity,
                            double multiplier, boolean compact) {
        double shrink = compact ? 4 : 0;
        int step = compact ? 2 : 1;
        double spectrumWidth = Math.max(1, (bounds.getWidth() - shrink * 2) / Math.max(1, renderSpectrum.length / (double) step));
        double baseY = bounds.getY() + bounds.getHeight() - shrink;

        Path2D path = new Path2D.Double();
        path.moveTo(bounds.getX() + shrink, baseY);
        for (int i = 0; i < renderSpectrum.length; i += step) {
            double height = -renderSpectrum[i] * multiplier * 10;
            if (compact) {
                height = Math.max(height, -bounds.getHeight() + shrink * 2);
            }
            path.lineTo(bounds.getX() + shrink + spectrumWidth * (i + 1) / step, baseY + height);
        }
        path.lineTo(bounds.getX() + bounds.getWidth() - shrink, baseY);

        graphics.setStroke(new BasicStroke(compact ? .75f : 1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(HudRenderUtil.withAlpha(color, opacity), true));
        graphics.draw(path);
    }

    private void updateIndicator(int index, float height, long nowMillis) {
        if (height < renderSpectrumIndicator[index]) {
            renderSpectrumIndicator[index] = height;
            indicatorTimestamps.put(index, nowMillis);
            return;
        }

        long timestamp = indicatorTimestamps.computeIfAbsent(index, ignored -> nowMillis);
        if (nowMillis - timestamp > 200) {
            renderSpectrumIndicator[index] = HudRenderUtil.smooth(renderSpectrumIndicator[index], 6, .18f);
        }
    }
}
