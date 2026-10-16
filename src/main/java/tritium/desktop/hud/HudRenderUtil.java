package tritium.desktop.hud;

import tritium.management.FontManager;
import tritium.rendering.font.CFontRenderer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class HudRenderUtil {
    private static final Map<String, Long> SCROLL_START_TIMES = new ConcurrentHashMap<>();
    private static final Map<Font, Font> FALLBACK_FONTS = new ConcurrentHashMap<>();

    private HudRenderUtil() {
    }

    public static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static Font font(int size, boolean bold) {
        // Match the lyric page: SF Pro with the bundled Chinese fallback.
        // The resources already carry their weight, so do not synthesize bold.
        Font primary = FontManager.readFont("/tritium/fonts/" + (bold ? "sfbold.otf" : "sfregular.otf"))
                .deriveFont((float) size);
        FALLBACK_FONTS.computeIfAbsent(primary, ignored ->
                FontManager.readFont("/tritium/fonts/" + (bold ? "pf_middleblack.ttf" : "pf_normal.ttf"))
                        .deriveFont((float) size));
        return primary;
    }

    public static Font fontRegular(int size) {
        return font(size, false);
    }

    public static Font fontBold(int size) {
        return font(size, true);
    }

    public static int withAlpha(int argb, double alphaMultiplier) {
        int alpha = (int) Math.round(((argb >>> 24) & 0xff) * clamp(alphaMultiplier, 0, 1));
        return (argb & 0x00ffffff) | (alpha << 24);
    }

    public static Color color(int argb) {
        return new Color(argb, true);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float smooth(float current, float target, float factor) {
        factor = Math.max(0, Math.min(1, factor));
        return current + (target - current) * factor;
    }

    public static void fillRound(Graphics2D graphics, double x, double y, double width, double height,
                                 double radius, int argb) {
        graphics.setColor(color(argb));
        graphics.fill(new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2));
    }

    public static void strokeRound(Graphics2D graphics, double x, double y, double width, double height,
                                   double radius, int argb, float strokeWidth) {
        graphics.setColor(color(argb));
        graphics.setStroke(new BasicStroke(strokeWidth));
        graphics.draw(new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2));
    }

    public static void drawImageRound(Graphics2D graphics, BufferedImage image, double x, double y,
                                      double width, double height, double radius, double alpha) {
        if (image == null) {
            drawPlaceholderCover(graphics, x, y, width, height, radius, alpha);
            return;
        }

        Composite oldComposite = graphics.getComposite();
        Shape oldClip = graphics.getClip();
        graphics.setComposite(AlphaComposite.SrcOver.derive((float) clamp(alpha, 0, 1)));
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2);
        graphics.clip(shape);
        graphics.drawImage(image, (int) Math.round(x), (int) Math.round(y),
                (int) Math.round(width), (int) Math.round(height), null);
        graphics.setClip(oldClip);
        graphics.setComposite(oldComposite);
    }

    public static void drawImageCover(Graphics2D graphics, BufferedImage image, Rectangle2D bounds,
                                      double radius, double alpha) {
        if (image == null) {
            drawPlaceholderCover(graphics, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), radius, alpha);
            return;
        }

        double imageRatio = image.getWidth() / (double) image.getHeight();
        double targetRatio = bounds.getWidth() / bounds.getHeight();
        double drawWidth;
        double drawHeight;
        if (imageRatio > targetRatio) {
            drawHeight = bounds.getHeight();
            drawWidth = drawHeight * imageRatio;
        } else {
            drawWidth = bounds.getWidth();
            drawHeight = drawWidth / imageRatio;
        }
        double drawX = bounds.getX() + (bounds.getWidth() - drawWidth) * .5;
        double drawY = bounds.getY() + (bounds.getHeight() - drawHeight) * .5;

        Composite oldComposite = graphics.getComposite();
        Shape oldClip = graphics.getClip();
        graphics.setComposite(AlphaComposite.SrcOver.derive((float) clamp(alpha, 0, 1)));
        RoundRectangle2D shape = new RoundRectangle2D.Double(bounds.getX(), bounds.getY(),
                bounds.getWidth(), bounds.getHeight(), radius * 2, radius * 2);
        graphics.clip(shape);
        graphics.drawImage(image, (int) Math.round(drawX), (int) Math.round(drawY),
                (int) Math.round(drawWidth), (int) Math.round(drawHeight), null);
        graphics.setClip(oldClip);
        graphics.setComposite(oldComposite);
    }

    public static void drawPlaceholderCover(Graphics2D graphics, double x, double y, double width,
                                            double height, double radius, double alpha) {
        Composite oldComposite = graphics.getComposite();
        graphics.setComposite(AlphaComposite.SrcOver.derive((float) clamp(alpha, 0, 1)));
        Shape oldClip = graphics.getClip();
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2);
        graphics.clip(shape);
        graphics.setPaint(new GradientPaint((float) x, (float) y, new Color(0xFF4A4A4A, true),
                (float) (x + width), (float) (y + height), new Color(0xFF1F1F1F, true)));
        graphics.fill(shape);
        graphics.setClip(oldClip);
        graphics.setComposite(oldComposite);
    }

    public static void drawText(Graphics2D graphics, String text, Font font, double x, double baseline, int argb) {
        TextLayout layout = textLayout(graphics.getFontRenderContext(), font, text);
        if (layout == null) {
            return;
        }

        graphics.setFont(font);
        graphics.setColor(color(argb));
        layout.draw(graphics, (float) x, (float) baseline);
    }

    public static void drawTextWithShadow(Graphics2D graphics, String text, Font font, double x, double baseline, int argb) {
        int alpha = (argb >>> 24) & 0xff;
        drawText(graphics, text, font, x + 1, baseline + 1, (alpha << 24));
        drawText(graphics, text, font, x, baseline, argb);
    }

    public static void drawAlignedText(Graphics2D graphics, String text, Font font, double x, double baseline,
                                       double width, String align, int argb, boolean shadow) {
        double textWidth = measure(graphics, font, text);
        double drawX = switch (align) {
            case "Left" -> x;
            case "Right" -> x + width - textWidth;
            default -> x + width * .5 - textWidth * .5;
        };
        if (shadow) {
            drawTextWithShadow(graphics, text, font, drawX, baseline, argb);
        } else {
            drawText(graphics, text, font, drawX, baseline, argb);
        }
    }

    public static void drawTextClipped(Graphics2D graphics, String text, Font font, double x, double baseline,
                                       double width, int argb, boolean shadow) {
        drawTextClipped(graphics, text, font, x, baseline, x, width, argb, shadow);
    }

    public static void drawTextClipped(Graphics2D graphics, String text, Font font, double drawX, double baseline,
                                       double clipX, double width, int argb, boolean shadow) {
        Shape oldClip = graphics.getClip();
        FontMetrics metrics = metrics(graphics, font);
        graphics.clip(new Rectangle2D.Double(clipX, baseline - metrics.getAscent() - 4, width, metrics.getHeight() + 8));
        if (shadow) {
            drawTextWithShadow(graphics, text, font, drawX, baseline, argb);
        } else {
            drawText(graphics, text, font, drawX, baseline, argb);
        }
        graphics.setClip(oldClip);
    }

    public static void drawScrollingText(Graphics2D graphics, String text, Font font, double x, double baseline,
                                         double width, int argb, long nowMillis) {
        text = text == null ? "" : text;
        double textWidth = measure(graphics, font, text);
        if (textWidth <= width) {
            drawText(graphics, text, font, x, baseline, argb);
            return;
        }

        double overflow = textWidth - width;
        long period = Math.max(2600L, (long) (overflow * 42L) + 2600L);
        long wait = 1200L;
        String key = scrollKey(text, font, width);
        if (SCROLL_START_TIMES.size() > 256) {
            SCROLL_START_TIMES.clear();
        }
        long startMillis = SCROLL_START_TIMES.computeIfAbsent(key, ignored -> nowMillis);
        long t = Math.floorMod(nowMillis - startMillis, period + wait * 2);
        double offset;
        if (t < wait) {
            offset = 0;
        } else if (t > wait + period) {
            offset = overflow;
        } else {
            offset = overflow * ((t - wait) / (double) period);
        }
        drawTextClipped(graphics, text, font, x - offset, baseline, x, width, argb, false);
    }

    private static String scrollKey(String text, Font font, double width) {
        return text + "|" + font.getFontName() + "|" + font.getStyle() + "|"
                + Math.round(font.getSize2D() * 10) + "|" + Math.round(width);
    }

    public static double measure(Graphics2D graphics, Font font, String text) {
        graphics.setFont(font);
        return measure(graphics.getFontRenderContext(), font, text);
    }

    private static double measure(FontRenderContext context, Font font, String text) {
        TextLayout layout = textLayout(context, font, text);
        return layout == null ? 0 : layout.getAdvance();
    }

    private static TextLayout textLayout(FontRenderContext context, Font font, String text) {
        String plain = text == null ? "" : CFontRenderer.stripControlCodes(text);
        if (plain.isEmpty()) {
            return null;
        }
        Font fallback = FALLBACK_FONTS.get(font);
        if (fallback == null || font.canDisplayUpTo(plain) == -1) {
            return new TextLayout(plain, font, context);
        }

        AttributedString attributed = new AttributedString(plain);
        attributed.addAttribute(TextAttribute.FONT, font);
        int runStart = -1;
        for (int offset = 0; offset < plain.length();) {
            int codePoint = plain.codePointAt(offset);
            boolean useFallback = !font.canDisplay(codePoint) && fallback.canDisplay(codePoint);
            if (useFallback && runStart < 0) {
                runStart = offset;
            } else if (!useFallback && runStart >= 0) {
                attributed.addAttribute(TextAttribute.FONT, fallback, runStart, offset);
                runStart = -1;
            }
            offset += Character.charCount(codePoint);
        }
        if (runStart >= 0) {
            attributed.addAttribute(TextAttribute.FONT, fallback, runStart, plain.length());
        }
        return new TextLayout(attributed.getIterator(), context);
    }

    public static FontMetrics metrics(Graphics2D graphics, Font font) {
        graphics.setFont(font);
        return metrics(graphics.getFontMetrics(font));
    }

    public static FontMetrics metrics(FontMetrics primaryMetrics) {
        Font font = primaryMetrics.getFont();
        Font fallback = FALLBACK_FONTS.get(font);
        if (fallback == null) {
            return primaryMetrics;
        }
        // Swing component metrics may disable fractional advances; match configure()
        // so sizing a toast before painting uses the same widths as its text layout.
        FontRenderContext context = new FontRenderContext(primaryMetrics.getFontRenderContext().getTransform(),
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        LineMetrics fallbackMetrics = fallback.getLineMetrics("Ag国", context);
        return new FontMetrics(font) {
            @Override
            public FontRenderContext getFontRenderContext() {
                return context;
            }

            @Override
            public int getAscent() {
                return Math.max(primaryMetrics.getAscent(), (int) Math.ceil(fallbackMetrics.getAscent()));
            }

            @Override
            public int getDescent() {
                return Math.max(primaryMetrics.getDescent(), (int) Math.ceil(fallbackMetrics.getDescent()));
            }

            @Override
            public int getLeading() {
                return Math.max(primaryMetrics.getLeading(), (int) Math.ceil(fallbackMetrics.getLeading()));
            }

            @Override
            public int stringWidth(String text) {
                return (int) Math.ceil(measure(context, font, text));
            }
        };
    }

    public static String formatTime(float millis) {
        int totalSeconds = Math.max(0, (int) (millis / 1000f));
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds - minutes * 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }
}
