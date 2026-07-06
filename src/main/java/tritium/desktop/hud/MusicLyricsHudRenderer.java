package tritium.desktop.hud;

import tritium.desktop.HudElementPreferences;
import tritium.desktop.HudPreferences;
import tritium.desktop.MusicPreferences;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

public final class MusicLyricsHudRenderer implements HudRenderer {
    private double scrollOffset;
    private float[] lineAlpha = new float[0];
    private double[] lineOffsetY = new double[0];
    private double[] lineTargetOffsetX = new double[0];

    @Override
    public String id() {
        return "musicLyrics";
    }

    @Override
    public String displayName() {
        return "musicLyrics";
    }

    @Override
    public HudElementPreferences layout(MusicPreferences preferences) {
        return preferences.hud().musicLyrics();
    }

    @Override
    public void render(Graphics2D graphics, HudRenderContext context) {
        HudStateSnapshot snapshot = context.snapshot;
        HudPreferences.MusicLyricsPreferences preferences = context.preferences.hud().musicLyrics();
        Rectangle2D bounds = context.bounds;

        if (snapshot.lyrics.isEmpty()) {
            if (context.editMode) {
                renderPlaceholder(graphics, bounds, preferences);
            }
            return;
        }

        ensureArrays(snapshot.lyrics.size());
        Font primaryFont = HudRenderUtil.fontBold(28);
        Font secondaryFont = HudRenderUtil.fontBold(18);
        FontMetrics primaryMetrics = HudRenderUtil.metrics(graphics, primaryFont);
        FontMetrics secondaryMetrics = HudRenderUtil.metrics(graphics, secondaryFont);
        boolean hasSecondary = snapshot.lyrics.stream().anyMatch(line -> !line.secondaryText().isEmpty());
        double lyricHeight = Math.max(primaryMetrics.getHeight() + 4,
                primaryMetrics.getHeight() + (hasSecondary ? 0 : -secondaryMetrics.getHeight() - 4)
                        + context.preferences.lyricHeight().getValue());

        int currentIndex = snapshot.currentLyricIndex < 0 ? 0 : Math.min(snapshot.currentLyricIndex, snapshot.lyrics.size() - 1);
        boolean singleLine = preferences.singleLine().getValue();
        if (!singleLine) {
            scrollOffset = HudRenderUtil.smooth((float) scrollOffset, (float) (currentIndex * lyricHeight), .2f);
        }

        Shape oldClip = graphics.getClip();
        graphics.clip(new Rectangle2D.Double(bounds.getX() - 2, bounds.getY(), bounds.getWidth() + 4, bounds.getHeight()));

        double offsetY = bounds.getY() + bounds.getHeight() / 2.0 - primaryMetrics.getHeight() / 2.0 - scrollOffset;
        for (int i = 0; i < snapshot.lyrics.size(); i++) {
            if (singleLine && i != currentIndex) {
                continue;
            }

            HudStateSnapshot.LyricSnapshot line = snapshot.lyrics.get(i);
            boolean isCurrent = i == currentIndex;
            lineAlpha[i] = HudRenderUtil.smooth(lineAlpha[i], isCurrent ? 1f : .25f, .1f);
            double y = calculateY(bounds, primaryMetrics, lyricHeight, i, currentIndex, offsetY, singleLine, preferences.graceScroll().getValue());
            if (y + lyricHeight < bounds.getY() || y > bounds.getY() + bounds.getHeight()) {
                continue;
            }

            renderLine(graphics, line, bounds, y, isCurrent, i, currentIndex, snapshot.currentTimeMillis,
                    primaryFont, secondaryFont, primaryMetrics, secondaryMetrics, preferences);
        }

        graphics.setClip(oldClip);
    }

    private double calculateY(Rectangle2D bounds, FontMetrics metrics, double lyricHeight, int index,
                              int currentIndex, double offsetY, boolean singleLine, boolean graceScroll) {
        if (singleLine) {
            double y = bounds.getY() + bounds.getHeight() / 2.0 - metrics.getHeight() / 2.0;
            lineOffsetY[index] = y;
            return y;
        }

        double destination = bounds.getY() + bounds.getHeight() / 2.0 - metrics.getHeight() / 2.0
                + index * lyricHeight - currentIndex * lyricHeight;
        if (lineOffsetY[index] == Double.MIN_VALUE || Math.abs(lineOffsetY[index] - destination) > 100) {
            lineOffsetY[index] = destination;
        }
        if (graceScroll) {
            lineOffsetY[index] = HudRenderUtil.smooth((float) lineOffsetY[index], (float) destination, .15f);
            return lineOffsetY[index];
        }
        return offsetY + index * lyricHeight;
    }

    private void renderLine(Graphics2D graphics, HudStateSnapshot.LyricSnapshot line, Rectangle2D bounds, double y,
                            boolean isCurrent, int index, int currentIndex, float progressMillis,
                            Font primaryFont, Font secondaryFont, FontMetrics primaryMetrics,
                            FontMetrics secondaryMetrics, HudPreferences.MusicLyricsPreferences preferences) {
        boolean hasWords = !line.words().isEmpty();
        String effect = preferences.scrollEffect().getValue();
        String align = preferences.align().getValue();
        boolean shadow = preferences.shadow().getValue();
        int alpha = hasWords && isCurrent ? 80 : (int) (lineAlpha[index] * 255);
        int activeAlpha = index <= currentIndex ? (int) (lineAlpha[index] * 255) : 100;
        double baseline = y + primaryMetrics.getAscent();

        boolean shouldRenderBase = !hasWords || !"SlideIn".equals(effect) || !isCurrent || "Left".equals(align);
        if (shouldRenderBase) {
            HudRenderUtil.drawAlignedText(graphics, line.text(), primaryFont, bounds.getX(), baseline,
                    bounds.getWidth(), align, (alpha << 24) | 0x00ffffff, shadow);
        }

        if (isCurrent && hasWords) {
            WordInfo wordInfo = calculateWordInfo(graphics, primaryFont, line, progressMillis);
            switch (effect) {
                case "FadeIn" -> renderFadeIn(graphics, line, bounds, baseline, primaryFont, wordInfo, align, shadow);
                case "SlideIn" -> renderSlideIn(graphics, line, bounds, baseline, primaryFont, wordInfo, index, align, shadow);
                default -> renderScroll(graphics, line, bounds, baseline, primaryFont, wordInfo, align, shadow);
            }
        }

        if (!line.secondaryText().isEmpty()) {
            double secondaryBaseline = baseline + primaryMetrics.getDescent() + 2 + secondaryMetrics.getAscent();
            HudRenderUtil.drawAlignedText(graphics, line.secondaryText(), secondaryFont, bounds.getX(), secondaryBaseline,
                    bounds.getWidth(), align, (Math.min(255, activeAlpha) << 24) | 0x00ffffff, shadow);
        }
    }

    private void renderScroll(Graphics2D graphics, HudStateSnapshot.LyricSnapshot line, Rectangle2D bounds,
                              double baseline, Font font, WordInfo wordInfo, String align, boolean shadow) {
        double x = alignmentX(graphics, font, line.text(), bounds, align);
        Shape oldClip = graphics.getClip();
        FontMetrics metrics = HudRenderUtil.metrics(graphics, font);
        graphics.clip(new Rectangle2D.Double(x, baseline - metrics.getAscent() - 3,
                wordInfo.progressWidth + 1, metrics.getHeight() + 6));
        if (shadow) {
            HudRenderUtil.drawTextWithShadow(graphics, line.text(), font, x, baseline, 0xFFFFFFFF);
        } else {
            HudRenderUtil.drawText(graphics, line.text(), font, x, baseline, 0xFFFFFFFF);
        }
        graphics.setClip(oldClip);
    }

    private void renderFadeIn(Graphics2D graphics, HudStateSnapshot.LyricSnapshot line, Rectangle2D bounds,
                              double baseline, Font font, WordInfo wordInfo, String align, boolean shadow) {
        double x = alignmentX(graphics, font, line.text(), bounds, align);
        for (int i = 0; i <= wordInfo.currentWordIndex && i < line.words().size(); i++) {
            HudStateSnapshot.WordSnapshot word = line.words().get(i);
            double wordAlpha = i < wordInfo.currentWordIndex ? 1 : wordInfo.currentWordProgress;
            int color = ((int) (HudRenderUtil.clamp(wordAlpha * 1.25, 0, 1) * 255) << 24) | 0x00ffffff;
            if (shadow) {
                HudRenderUtil.drawTextWithShadow(graphics, word.text(), font, x, baseline, color);
            } else {
                HudRenderUtil.drawText(graphics, word.text(), font, x, baseline, color);
            }
            x += HudRenderUtil.measure(graphics, font, word.text());
        }
    }

    private void renderSlideIn(Graphics2D graphics, HudStateSnapshot.LyricSnapshot line, Rectangle2D bounds,
                               double baseline, Font font, WordInfo wordInfo, int lineIndex, String align,
                               boolean shadow) {
        lineTargetOffsetX[lineIndex] = HudRenderUtil.smooth((float) lineTargetOffsetX[lineIndex], (float) wordInfo.progressWidth, .25f);
        double x = switch (align) {
            case "Right" -> bounds.getX() + bounds.getWidth() - lineTargetOffsetX[lineIndex];
            case "Center" -> bounds.getX() + bounds.getWidth() / 2.0 - lineTargetOffsetX[lineIndex] / 2.0;
            default -> bounds.getX();
        };
        renderFadeIn(graphics, line, new Rectangle2D.Double(x, bounds.getY(), bounds.getWidth(), bounds.getHeight()),
                baseline, font, wordInfo, "Left", shadow);
    }

    private WordInfo calculateWordInfo(Graphics2D graphics, Font font, HudStateSnapshot.LyricSnapshot line, float progressMillis) {
        int currentWord = 0;
        for (int i = 0; i < line.words().size(); i++) {
            HudStateSnapshot.WordSnapshot word = line.words().get(i);
            if (word.timestamp() > progressMillis) {
                currentWord = Math.max(0, i - 1);
                break;
            }
            currentWord = i;
        }

        double widthBefore = 0;
        for (int i = 0; i < currentWord; i++) {
            widthBefore += HudRenderUtil.measure(graphics, font, line.words().get(i).text());
        }

        HudStateSnapshot.WordSnapshot word = line.words().get(currentWord);
        double progress = HudRenderUtil.clamp((progressMillis - word.timestamp()) / (double) Math.max(1, word.duration()), 0, 1);
        double progressWidth = widthBefore + HudRenderUtil.measure(graphics, font, word.text()) * progress;
        return new WordInfo(currentWord, progress, progressWidth);
    }

    private double alignmentX(Graphics2D graphics, Font font, String text, Rectangle2D bounds, String align) {
        double width = HudRenderUtil.measure(graphics, font, text);
        return switch (align) {
            case "Left" -> bounds.getX();
            case "Right" -> bounds.getX() + bounds.getWidth() - width;
            default -> bounds.getX() + bounds.getWidth() / 2.0 - width / 2.0;
        };
    }

    private void renderPlaceholder(Graphics2D graphics, Rectangle2D bounds, HudPreferences.MusicLyricsPreferences preferences) {
        Font font = HudRenderUtil.fontBold(28);
        FontMetrics metrics = HudRenderUtil.metrics(graphics, font);
        double baseline = bounds.getY() + bounds.getHeight() / 2.0 - metrics.getHeight() / 2.0 + metrics.getAscent();
        HudRenderUtil.drawAlignedText(graphics, "暂无歌词", font, bounds.getX(), baseline, bounds.getWidth(),
                preferences.align().getValue(), 0xAAFFFFFF, preferences.shadow().getValue());
    }

    private void ensureArrays(int size) {
        if (lineAlpha.length == size) {
            return;
        }
        lineAlpha = Arrays.copyOf(lineAlpha, size);
        int oldLength = lineOffsetY.length;
        lineOffsetY = Arrays.copyOf(lineOffsetY, size);
        lineTargetOffsetX = Arrays.copyOf(lineTargetOffsetX, size);
        for (int i = oldLength; i < size; i++) {
            lineOffsetY[i] = Double.MIN_VALUE;
        }
    }

    private record WordInfo(int currentWordIndex, double currentWordProgress, double progressWidth) {
    }
}
