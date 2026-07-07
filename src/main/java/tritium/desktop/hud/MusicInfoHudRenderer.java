package tritium.desktop.hud;

import tritium.desktop.HudElementPreferences;
import tritium.desktop.HudPreferences;
import tritium.desktop.MusicPreferences;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

public final class MusicInfoHudRenderer implements HudRenderer {
    private float alpha;
    private float downloadHeight;
    private float downloadAlpha;
    private float backgroundAlpha = 1f;
    private long previousMusicId = Long.MIN_VALUE;
    private BufferedImage previousSmallCover;
    private BufferedImage previousBlurredCover;

    @Override
    public String id() {
        return "musicInfo";
    }

    @Override
    public String displayName() {
        return "musicInfo";
    }

    @Override
    public HudElementPreferences layout(MusicPreferences preferences) {
        return preferences.hud().musicInfo();
    }

    @Override
    public void render(Graphics2D graphics, HudRenderContext context) {
        HudStateSnapshot snapshot = context.snapshot;
        Rectangle2D bounds = context.bounds;
        HudPreferences.MusicInfoPreferences preferences = context.preferences.hud().musicInfo();
        boolean active = preferences.enabled().getValue() || snapshot.playing || snapshot.smoke || context.editMode;
        alpha = HudRenderUtil.smooth(alpha, active ? 1f : 0f, active ? .15f : .2f);
        if (alpha <= .01f) {
            return;
        }

        double opacity = preferences.opacity().getValue();
        double width = bounds.getWidth();
        double baseHeight = bounds.getHeight();
        boolean showDownload = snapshot.downloading || snapshot.smoke;
        downloadHeight = HudRenderUtil.smooth(downloadHeight, showDownload ? 26f : 0f, .2f);
        downloadAlpha = HudRenderUtil.smooth(downloadAlpha, showDownload ? 1f : 0f, .35f);
        double totalHeight = baseHeight + downloadHeight;

        if (snapshot.musicId != previousMusicId) {
            previousSmallCover = snapshot.smallCover;
            previousBlurredCover = snapshot.blurredCover;
            previousMusicId = snapshot.musicId;
            backgroundAlpha = 0f;
        }
        backgroundAlpha = HudRenderUtil.smooth(backgroundAlpha, 1f, .18f);

        double x = bounds.getX();
        double y = bounds.getY() + downloadHeight;
        double radius = Math.min(14, Math.max(10, baseHeight * .12));

        HudRenderUtil.fillRound(graphics, x, bounds.getY(), width, totalHeight, radius, HudRenderUtil.withAlpha(0xCC000000, alpha * opacity * .45));

        Rectangle2D backgroundBounds = new Rectangle2D.Double(x, bounds.getY(), width, totalHeight);
        if (previousBlurredCover != null && backgroundAlpha < .99f) {
            HudRenderUtil.drawImageCover(graphics, previousBlurredCover, backgroundBounds, radius, alpha * opacity * (1f - backgroundAlpha));
        }
        HudRenderUtil.drawImageCover(graphics, snapshot.blurredCover, backgroundBounds, radius, alpha * opacity * backgroundAlpha);
        HudRenderUtil.fillRound(graphics, x, bounds.getY(), width, totalHeight, radius, HudRenderUtil.withAlpha(0x66000000, alpha * opacity));

        double padding = HudRenderUtil.clamp(baseHeight * .09, 8, 12);
        double gap = Math.max(10, padding);
        double coverSize = Math.max(1, baseHeight - padding * 2);
        double coverX = x + padding;
        double coverY = y + padding;
        BufferedImage currentCover = snapshot.smallCover == null ? snapshot.cover : snapshot.smallCover;
        BufferedImage previousCover = previousSmallCover == null ? snapshot.cover : previousSmallCover;
        if (previousCover != null && backgroundAlpha < .99f) {
            HudRenderUtil.drawImageRound(graphics, previousCover, coverX, coverY, coverSize, coverSize, 8, alpha * opacity * (1f - backgroundAlpha));
        }
        HudRenderUtil.drawImageRound(graphics, currentCover, coverX, coverY, coverSize, coverSize, 8, alpha * opacity * backgroundAlpha);

        if (showDownload) {
            renderDownloadPanel(graphics, snapshot, x, bounds.getY(), width, padding, alpha * opacity);
        }

        double textX = coverX + coverSize + gap;
        double textWidth = Math.max(1, width - coverSize - padding * 2 - gap);
        Font nameFont = HudRenderUtil.fontBold((int) Math.round(HudRenderUtil.clamp(baseHeight * .27, 24, 32)));
        Font secondaryFont = HudRenderUtil.fontRegular((int) Math.round(HudRenderUtil.clamp(baseHeight * .18, 16, 22)));
        Font timeFont = HudRenderUtil.fontBold((int) Math.round(HudRenderUtil.clamp(baseHeight * .13, 13, 16)));
        FontMetrics nameMetrics = HudRenderUtil.metrics(graphics, nameFont);
        FontMetrics secondaryMetrics = HudRenderUtil.metrics(graphics, secondaryFont);
        FontMetrics timeMetrics = HudRenderUtil.metrics(graphics, timeFont);

        double progressY = y + baseHeight - padding - timeMetrics.getHeight() - 8;
        double nameBaseline = y + padding + nameMetrics.getAscent();
        HudRenderUtil.drawScrollingText(graphics, snapshot.musicName, nameFont, textX, nameBaseline, textWidth,
                HudRenderUtil.withAlpha(0xFFFFFFFF, alpha * opacity), snapshot.nowMillis);

        String secondary = snapshot.artistsName;
        if (preferences.turnComposerIntoLyric().getValue() && snapshot.currentLyricIndex >= 0
                && snapshot.currentLyricIndex < snapshot.lyrics.size()) {
            secondary = snapshot.lyrics.get(snapshot.currentLyricIndex).text();
        }

        double secondaryBaseline = nameBaseline + nameMetrics.getDescent() + 4 + secondaryMetrics.getAscent();
        secondaryBaseline = Math.min(secondaryBaseline, progressY - 5 - secondaryMetrics.getDescent());
        HudRenderUtil.drawScrollingText(graphics, secondary, secondaryFont, textX, secondaryBaseline, textWidth,
                HudRenderUtil.withAlpha(0xCCFFFFFF, alpha * opacity), snapshot.nowMillis + 700L);

        renderProgress(graphics, snapshot, textX, progressY, textWidth, alpha * opacity, timeFont);
    }

    private void renderDownloadPanel(Graphics2D graphics, HudStateSnapshot snapshot, double x, double y,
                                     double width, double spacing, double alpha) {
        Font font = HudRenderUtil.fontBold(18);
        FontMetrics metrics = HudRenderUtil.metrics(graphics, font);
        double textY = y + spacing + metrics.getAscent();
        HudRenderUtil.drawText(graphics, "Downloading...", font, x + spacing, textY, HudRenderUtil.withAlpha(0xFFFFFFFF, alpha * downloadAlpha));
        String speed = snapshot.downloadSpeed == null ? "0 b/s" : snapshot.downloadSpeed;
        double speedWidth = HudRenderUtil.measure(graphics, font, speed);
        HudRenderUtil.drawText(graphics, speed, font, x + width - spacing * 2 - speedWidth, textY,
                HudRenderUtil.withAlpha(0xFFFFFFFF, alpha * downloadAlpha));

        double barX = x + spacing;
        double barY = y + spacing + metrics.getHeight() + 4;
        double barWidth = width - spacing * 2;
        HudRenderUtil.fillRound(graphics, barX, barY, barWidth, 6, 2, HudRenderUtil.withAlpha(0x40FFFFFF, alpha * downloadAlpha));
        HudRenderUtil.fillRound(graphics, barX, barY, barWidth * HudRenderUtil.clamp(snapshot.downloadProgress, 0, 1), 6, 2,
                HudRenderUtil.withAlpha(0xFFFFFFFF, alpha * downloadAlpha));
    }

    private void renderProgress(Graphics2D graphics, HudStateSnapshot snapshot, double x, double y,
                                double width, double alpha, Font timeFont) {
        HudRenderUtil.fillRound(graphics, x, y, width, 5, 1, HudRenderUtil.withAlpha(0x4DFFFFFF, alpha));
        double progress = HudRenderUtil.clamp(snapshot.currentTimeMillis / Math.max(1, snapshot.totalTimeMillis), 0, 1);
        HudRenderUtil.fillRound(graphics, x, y, width * progress, 5, 1, HudRenderUtil.withAlpha(0xFFE9E9E9, alpha));

        FontMetrics metrics = HudRenderUtil.metrics(graphics, timeFont);
        double timeY = y + 9 + metrics.getAscent();
        String current = HudRenderUtil.formatTime(snapshot.currentTimeMillis);
        String total = HudRenderUtil.formatTime(snapshot.totalTimeMillis);
        int textColor = HudRenderUtil.withAlpha(0x80FFFFFF, alpha);
        HudRenderUtil.drawText(graphics, current, timeFont, x, timeY, textColor);
        HudRenderUtil.drawText(graphics, total, timeFont, x + width - HudRenderUtil.measure(graphics, timeFont, total), timeY, textColor);
    }
}
