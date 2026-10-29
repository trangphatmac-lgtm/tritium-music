package tritium.minecraft.runtime;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import tritium.minecraft.protocol.PlaybackState.*;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.*;
import javax.imageio.ImageIO;

/** Game-side adaptation of the original MusicInfo/Lyrics/SpectrumWidget geometry and animation.
 * No desktop renderer or player dependencies enter the game. See THIRD_PARTY_NOTICES.md. */
public final class HudPainter {
    private final Font regular = font("sfregular.otf"), bold = font("sfbold.otf"), fallback = font("pf_normal.ttf");
    private final Font boldFallback = font("pf_middleblack.ttf");
    private final WidgetText widgetFont = new WidgetText();
    private final WidgetLyrics widgetLyrics = new WidgetLyrics(widgetFont);
    private final WidgetSpectrum widgetSpectrum = new WidgetSpectrum();
    private final WidgetMarquee titleScroll = new WidgetMarquee(widgetFont), artistScroll = new WidgetMarquee(widgetFont);
    private final Map<String, BufferedImage> surfaces = new HashMap<String, BufferedImage>();
    private final BufferedImage toastBackground = image("now_playing.png"), notes = image("music_notes.png");
    private String toastText;
    private double toastNaturalWidth = 120;
    private long toastStarted, infoAt;
    private double infoAlpha, downloadHeight, downloadAlpha, artAlpha = 1;
    private HudArtwork artwork, previousArtwork;

    public void artwork(BufferedImage image) { artwork(new HudArtwork(image)); }
    void artwork(HudArtwork next) {
        if (artwork != null && artwork.cover != null) previousArtwork = artwork;
        artwork = next;
        artAlpha = previousArtwork == null ? 1 : 0;
    }
    public void toast(String text, long now) {
        toastText = text; toastStarted = now;
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = measure.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            toastNaturalWidth = Math.max(240, Math.ceil(measure(g, text, 14, true)) + 64) / 2;
        } finally { g.dispose(); }
    }
    public boolean toastVisible(long now, double hold) { return toastText != null && now - toastStarted < (hold + 1.5) * 1000; }

    /** Downloading adds a panel above the unchanged 230x56 card, rather than shrinking its cover. */
    public double[] bounds(HudConfig.Module m, PlaybackState state, int width, int height, long now, boolean preview) {
        double[] bounds = m.bounds(width, height);
        if (m.id.equals("musicInfo")) {
            updateInfo(state, now, preview);
            bounds[3] = Math.min(height, bounds[3] + downloadHeight * m.height / 56 * m.scale);
            bounds[1] = Math.min(bounds[1], height - bounds[3]);
        } else if (m.id.equals("musicToast") && !preview) {
            // A configured width is the minimum. Like the desktop toast, grow for the song name.
            bounds[2] = Math.min(width, Math.max(m.width, toastNaturalWidth * m.height / 24) * m.scale);
            bounds[0] = m.x * Math.max(0, width - bounds[2]);
        }
        return bounds;
    }
    public static double padding(HudConfig.Module module) { return module.id.equals("musicInfo") ? 8 * module.scale : 0; }
    public static double[] textureBounds(HudConfig.Module module, double[] bounds) {
        double pad = padding(module);
        return new double[]{bounds[0] - pad, bounds[1] - pad, bounds[2] + pad * 2, bounds[3] + pad * 2};
    }

    public BufferedImage paint(HudConfig.Module module, Track track, PlaybackState state, double position,
                               double width, double height, int density, long now, boolean preview, int accent) {
        double pad = padding(module);
        return paintAtResolution(module, track, state, position, width, height,
                Math.max(1, Math.min(4096, (int)Math.ceil((width + pad * 2) * density))),
                Math.max(1, Math.min(4096, (int)Math.ceil((height + pad * 2) * density))), now, preview, accent);
    }

    /** Raster dimensions include padding and come from the actual GL viewport, not GUI scale. */
    public BufferedImage paintAtResolution(HudConfig.Module module, Track track, PlaybackState state, double position,
                               double width, double height, int pixelWidth, int pixelHeight, long now, boolean preview, int accent) {
        double pad = padding(module), fullWidth = width + pad * 2, fullHeight = height + pad * 2;
        int w = Math.max(1, pixelWidth), h = Math.max(1, pixelHeight);
        BufferedImage image = surfaces.get(module.id);
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            surfaces.put(module.id, image);
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear); g.fillRect(0, 0, w, h);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.scale(w / fullWidth, h / fullHeight); g.translate(pad, pad);
            g.scale(module.scale, module.scale);
            double lw = width / module.scale, lh = height / module.scale;
            if (module.id.equals("musicInfo")) {
                updateInfo(state, now, preview);
                info(g, module, track, state, position, lw, lh, now, preview);
            } else if (module.id.equals("musicLyrics")) widgetLyrics.paint(g, module, track, state, position, lw, lh, now, preview);
            else if (module.id.equals("musicSpectrum")) widgetSpectrum.paint(g, module, state, lw, lh, now, preview);
            else toast(g, module, lw, lh, now, preview, accent);
        } finally { g.dispose(); }
        if (module.opacity < 1) {
            Graphics2D opacity = image.createGraphics();
            opacity.setComposite(AlphaComposite.DstIn); opacity.setColor(new Color(1f, 1f, 1f, (float)module.opacity));
            opacity.fillRect(0, 0, w, h); opacity.dispose();
        }
        return image;
    }

    private void updateInfo(PlaybackState state, long now, boolean preview) {
        double delta = infoAt == 0 ? 16.667 : Math.max(0, Math.min(100, now - infoAt)); infoAt = now;
        boolean active = state.playing || state.downloading;
        infoAlpha = preview ? 1 : WidgetLyrics.approach(infoAlpha, active ? 1 : 0, active ? .15 : .2, delta);
        downloadHeight = preview ? 0 : WidgetLyrics.approach(downloadHeight, state.downloading ? 26 : 0, .2, delta);
        downloadAlpha = WidgetLyrics.approach(downloadAlpha, state.downloading && !preview ? 1 : 0, .4, delta);
        if (artwork != null && artwork.cover != null) artAlpha = WidgetLyrics.approach(artAlpha, 1, .3, delta);
    }

    private void info(Graphics2D g, HudConfig.Module m, Track track, PlaybackState s, double time,
                      double width, double height, long now, boolean preview) {
        if (infoAlpha < .001) return;
        // Canonical widget units are exactly 230x56, with 4px padding and a 48px cover.
        double fullHeight = 56 + downloadHeight;
        g.scale(width / 230, height / fullHeight);
        g.translate(115, fullHeight / 2);
        double scale = .98 + infoAlpha * .02; g.scale(scale, scale);
        g.translate(-115, -fullHeight / 2);
        g.setComposite(AlphaComposite.SrcOver.derive((float)infoAlpha));
        for (int i = 8; i > 0; i--) round(g, -i, -i, 230 + i * 2, fullHeight + i * 2, 10.5 + i, 0x07000000);
        round(g, 0, 0, 230, fullHeight, 10.5, 0xb3000000);
        if (previousArtwork != null) background(g, previousArtwork.blurred, fullHeight, 1);
        if (artwork != null) background(g, artwork.blurred, fullHeight, previousArtwork == null ? 1 : artAlpha);
        round(g, 0, 0, 230, fullHeight, 10.5, 0x40000000);
        double coverY = 4 + downloadHeight;
        if (previousArtwork != null) cover(g, previousArtwork.cover, 4, coverY, 1);
        if (artwork != null && artwork.cover != null) cover(g, artwork.cover, 4, coverY, previousArtwork == null ? 1 : artAlpha);
        if ((artwork == null || artwork.cover == null) && previousArtwork == null) round(g, 4, coverY, 48, 48, 6, 0xff44444b);

        if (downloadAlpha > .001) {
            int white = (int)(255 * downloadAlpha) << 24 | 0xffffff;
            widgetFont.draw(g, "Downloading...", 4, 4, 18, true, white, false);
            String speed = s.downloadSpeed == null ? "" : s.downloadSpeed;
            widgetFont.draw(g, speed, 226 - widgetFont.width(speed, 18, true), 4, 18, true, white, false);
            double barY = 8 + (int)widgetFont.height(18, true);
            round(g, 4, barY, 222, 6, 2, (int)(64 * downloadAlpha) << 24 | 0xffffff);
            Shape clip = g.getClip(); g.clip(new Rectangle2D.Double(4, barY, 222 * HudConfig.clamp(s.downloadProgress, 0, 1), 6));
            round(g, 4, barY, 222, 6, 2, white); g.setClip(clip);
        }

        double x = 56, textWidth = 169, titleY = downloadHeight + 7;
        titleScroll.draw(g, track.title.isEmpty() ? "Tritium Music" : track.title, x, titleY, textWidth, 25, true, -1, now, 2500, 0);
        int current = WidgetLyrics.current(track, time);
        String secondary = track.artist;
        long wait = 2000, duration = 0;
        if (m.artistLyric && current >= 0) {
            Line line = track.lyrics.get(current); secondary = line.text; wait = 100;
            double end = current + 1 < track.lyrics.size() ? track.lyrics.get(current + 1).timestamp : s.durationMillis;
            duration = (long)Math.max(1, end - line.timestamp - 500);
        }
        double barY = downloadHeight + 56 - 4 - 3 - widgetFont.height(14, true) - 8;
        double titleEnd = titleY + widgetFont.height(25, true);
        double artistY = titleEnd + (barY - titleEnd) / 2 - widgetFont.height(20, false) / 2;
        artistScroll.draw(g, secondary, x, artistY, textWidth, 20, false, 0xccffffff, now, wait, duration);
        round(g, x, barY, textWidth, 5, 1, 0x4dffffff);
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(x, barY, textWidth * HudConfig.clamp(time / Math.max(1, s.durationMillis), 0, 1), 6));
        round(g, x, barY, textWidth, 5, 1, 0xffe9e9e9); g.setClip(clip);
        widgetFont.draw(g, time(time), x, barY + 9, 14, true, 0x80ffffff, false);
        String end = time(s.durationMillis);
        widgetFont.draw(g, end, x + textWidth - widgetFont.width(end, 14, true), barY + 9, 14, true, 0x80ffffff, false);
    }

    private static void cover(Graphics2D g, BufferedImage image, double x, double y, double alpha) {
        if (image == null || alpha <= 0) return;
        Graphics2D layer = (Graphics2D)g.create();
        try {
            layer.setComposite(AlphaComposite.SrcOver.derive(((AlphaComposite)g.getComposite()).getAlpha() * (float)alpha));
            layer.clip(new RoundRectangle2D.Double(x, y, 48, 48, 12, 12));
            AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
            transform.scale(48.0 / image.getWidth(), 48.0 / image.getHeight()); layer.drawImage(image, transform, null);
        } finally { layer.dispose(); }
    }
    private static void background(Graphics2D g, BufferedImage image, double height, double alpha) {
        if (image == null || alpha <= 0) return;
        Graphics2D layer = (Graphics2D)g.create();
        try {
            layer.setComposite(AlphaComposite.SrcOver.derive(((AlphaComposite)g.getComposite()).getAlpha() * (float)alpha));
            layer.clip(new RoundRectangle2D.Double(0, 0, 230, height, 21, 21));
            double slice = image.getHeight() * 56.0 / 230, y = (image.getHeight() - slice) / 2;
            layer.drawImage(image, 0, 0, 230, (int)Math.ceil(height), 0, (int)y, image.getWidth(), (int)(y + slice), null);
        } finally { layer.dispose(); }
    }

    private void toast(Graphics2D g, HudConfig.Module m, double w, double h, long now, boolean preview, int accent) {
        if (!preview && !toastVisible(now, m.holdSeconds)) return;
        // Desktop canonical units: height 48, font 14, icon 32, text inset 52.
        // Other Minecraft HUDs also use half-sized GUI units; scaling changes the whole toast.
        double unit = h / 48;
        g.scale(unit, unit); w /= unit; h = 48;
        double elapsed = now - toastStarted;
        double visible = preview ? 1 : elapsed < 750 ? 1 - Math.pow(1 - elapsed / 750, 4)
                : elapsed > (m.holdSeconds * 1000 + 750) ? Math.pow(Math.max(0, 1 - (elapsed - m.holdSeconds * 1000 - 750) / 750), 4) : 1;
        g.translate(-(1 - visible) * w, 0);
        if (toastBackground != null) {
            int sw = toastBackground.getWidth(), sh = toastBackground.getHeight(), edge = Math.min(8, (int) w / 4);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(toastBackground, 0, 0, edge, (int) h, 0, 0, 5, sh, null);
            g.drawImage(toastBackground, edge, 0, (int) w - edge, (int) h, 5, 0, sw - 5, sh, null);
            g.drawImage(toastBackground, (int) w - edge, 0, (int) w, (int) h, sw - 5, 0, sw, sh, null);
        } else round(g, 0, 0, w, h, 4, 0xf0202020);
        if (notes != null) {
            int size = notes.getWidth(), frame = (int) (now / 100 % Math.max(1, notes.getHeight() / size));
            BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = icon.createGraphics();
            ig.drawImage(notes, 0, 0, size, size, 0, frame * size, size, (frame + 1) * size, null);
            ig.setComposite(AlphaComposite.SrcIn);
            ig.setColor(Color.getHSBColor((now % 12000) / 12000f, .5f, 1)); ig.fillRect(0, 0, size, size); ig.dispose();
            g.drawImage(icon, 12, 8, 32, 32, null);
        }
        FontMetrics metrics = g.getFontMetrics(bold.deriveFont(14f));
        java.awt.font.LineMetrics backup = boldFallback.deriveFont(14f).getLineMetrics("Ag国", g.getFontRenderContext());
        int ascent = Math.max(metrics.getAscent(), (int)Math.ceil(backup.getAscent()));
        int descent = Math.max(metrics.getDescent(), (int)Math.ceil(backup.getDescent()));
        int leading = Math.max(metrics.getLeading(), (int)Math.ceil(backup.getLeading()));
        String label = fitToast(g, preview ? "Now playing · Tritium Music" : toastText, Math.max(0, w - 64));
        text(g, label, 52, (48 - ascent - descent - leading) / 2 + ascent, 14, true, 0xffffffff, false);
    }
    private String fitToast(Graphics2D g, String value, double available) {
        if (value == null || value.isEmpty()) return "";
        if (Math.ceil(measure(g, value, 14, true)) <= available) return value;
        if (Math.ceil(measure(g, "…", 14, true)) > available) return "";
        int end = value.length();
        while (end > 0 && Math.ceil(measure(g, value.substring(0, end) + "…", 14, true)) > available)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end) + "…";
    }
    private TextLayout layout(Graphics2D g, String value, float size, boolean weight) {
        if (value == null || value.isEmpty()) return null;
        Font primary = (weight ? bold : regular).deriveFont(size), backup = (weight ? boldFallback : fallback).deriveFont(size);
        AttributedString attributed = new AttributedString(value);
        attributed.addAttribute(TextAttribute.FONT, primary);
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i), end = i + Character.charCount(cp);
            if (!primary.canDisplay(cp)) attributed.addAttribute(TextAttribute.FONT, backup, i, end);
            i = end;
        }
        return new TextLayout(attributed.getIterator(), g.getFontRenderContext());
    }
    private double measure(Graphics2D g, String text, float size, boolean bold) {
        TextLayout layout = layout(g, text, size, bold); return layout == null ? 0 : layout.getAdvance();
    }
    private void text(Graphics2D g, String value, double x, double y, float size, boolean bold, int color, boolean shadow) {
        TextLayout layout = layout(g, value, size, bold); if (layout == null) return;
        if (shadow) { g.setColor(new Color(color & 0xff000000, true)); layout.draw(g, (float) x + 1, (float) y + 1); }
        g.setColor(new Color(color, true)); layout.draw(g, (float) x, (float) y);
    }
    private static void round(Graphics2D g, double x, double y, double w, double h, double r, int color) {
        g.setColor(new Color(color, true)); g.fill(new RoundRectangle2D.Double(x, y, Math.max(0, w), Math.max(0, h), r * 2, r * 2));
    }
    private static String time(double millis) {
        long seconds = Math.max(0, (long) millis / 1000); return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private static Font font(String name) {
        try (InputStream in = HudPainter.class.getResourceAsStream("/tritium/fonts/" + name)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) { return new Font("Dialog", Font.PLAIN, 14); }
    }
    private static BufferedImage image(String name) {
        try (InputStream in = HudPainter.class.getResourceAsStream("/tritium/textures/hud/" + name)) { return ImageIO.read(in); }
        catch (Exception ignored) { return null; }
    }
    public void close() { surfaces.clear(); artwork = null; previousArtwork = null; }
}
