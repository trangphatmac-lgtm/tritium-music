package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class HudReferenceTest {
    @Test void spectrumRetainsAbsoluteBarHeightWhenAnotherBandGetsLouder() {
        HudConfig.Module module = new HudConfig().find("musicSpectrum");
        module.color = 0xffffffff; module.indicator = false;
        PlaybackState state = new PlaybackState(); state.playing = true; state.spectrum = new float[12];
        state.spectrum[0] = .1f; state.spectrum[3] = .1f;
        WidgetSpectrum spectrum = new WidgetSpectrum();
        BufferedImage quiet = spectrum(spectrum, module, state, 1000);
        assertEquals(84, firstPixel(quiet, 10), "0.1 * 16 * 10 = 16px, step=3 produces two 60px bars");
        state.spectrum[3] = 10;
        BufferedImage loud = spectrum(spectrum, module, state, 1033);
        assertEquals(84, firstPixel(loud, 10), "Another band's amplitude must not renormalize this bar");
        assertEquals(0, firstPixel(loud, 80));
        state.paused = true;
        assertEquals(-1, firstPixel(spectrum(spectrum, module, state, 1066), 10));
    }

    @Test void peakHoldsForTwoHundredMillisecondsThenFallsLinearly() {
        HudConfig.Module module = new HudConfig().find("musicSpectrum"); module.color = -1;
        PlaybackState state = new PlaybackState(); state.playing = true; state.spectrum = new float[12]; Arrays.fill(state.spectrum, .5f);
        WidgetSpectrum spectrum = new WidgetSpectrum();
        assertEquals(18, firstPixel(spectrum(spectrum, module, state, 1000), 10));
        state.paused = true;
        spectrum(spectrum, module, state, 1100);
        assertEquals(18, firstPixel(spectrum(spectrum, module, state, 1200), 10));
        assertEquals(22, firstPixel(spectrum(spectrum, module, state, 1250), 10));
    }

    @Test void downloadPanelExpandsWithoutShrinkingTheMusicCardAndOpacityStillApplies() {
        HudConfig.Module module = new HudConfig().find("musicInfo");
        PlaybackState state = new PlaybackState(); state.playing = true; state.downloading = true; state.downloadProgress = .5;
        HudPainter painter = new HudPainter();
        double[] bounds = null;
        for (long now = 1000; now < 4000; now += 33) bounds = painter.bounds(module, state, 800, 600, now, false);
        assertEquals(82, bounds[3], .01);
        module.opacity = .4;
        BufferedImage image = painter.paint(module, new PlaybackState.Track(), state, 0, bounds[2], bounds[3], 2, 4000, false, -1);
        assertEquals(492, image.getWidth());
        assertEquals((int)Math.ceil((bounds[3] + 16) * 2), image.getHeight());
        for (int pixel : pixels(image)) assertTrue((pixel >>> 24) <= 102, "Opacity applies once to the composed HUD, including overlapping layers");
        state.downloading = false;
        for (long now = 4033; now < 7000; now += 33) bounds = painter.bounds(module, state, 800, 600, now, false);
        assertEquals(56, bounds[3], .01); painter.close();
    }

    @Test void lyricsHideWhenStoppedAndSeekingRestoresTheSamePositionAsAFreshRenderer() {
        HudConfig.Module module = new HudConfig().find("musicLyrics");
        PlaybackState.Track track = track(); PlaybackState state = new PlaybackState(); state.playing = true;
        HudPainter painter = new HudPainter();
        for (long now = 1000; now < 4000; now += 33) painter.paint(module, track, state, now, 450, 120, 2, now, false, -1);
        BufferedImage seek = painter.paint(module, track, state, 100, 450, 120, 2, 4000, false, -1);
        HudPainter fresh = new HudPainter();
        BufferedImage first = fresh.paint(module, track, state, 100, 450, 120, 2, 4000, false, -1);
        assertArrayEquals(pixels(first), pixels(seek));
        state.playing = false;
        BufferedImage stopped = painter.paint(module, track, state, 100, 450, 120, 2, 4033, false, -1);
        for (int pixel : pixels(stopped)) assertEquals(0, pixel >>> 24);
        painter.close(); fresh.close();
    }

    @Test void changingLongMarqueeTextRestartsItsInitialWait() {
        WidgetText font = new WidgetText(); WidgetMarquee marquee = new WidgetMarquee(font);
        marquee(marquee, "The old song title is much longer than the card", 1000);
        marquee(marquee, "The old song title is much longer than the card", 8000);
        String next = "A completely new song title should start at its beginning";
        assertArrayEquals(pixels(marquee(new WidgetMarquee(font), next, 1000)), pixels(marquee(marquee, next, 8001)));
    }

    @Test void marqueeRetainsNativeTextPixelsWhenVerticalAndHorizontalScalesDiffer() {
        WidgetText font = new WidgetText(); WidgetMarquee marquee = new WidgetMarquee(font);
        String text = "A long title with sharp edges that scrolls beyond the card";
        for (double[] scale : new double[][]{{1, 4}, {1.25, 3.5}, {2, 2}, {1, 1}}) {
            BufferedImage actual = new BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB);
            BufferedImage expected = new BufferedImage(240, 160, BufferedImage.TYPE_INT_ARGB);
            Graphics2D a = actual.createGraphics(), e = expected.createGraphics();
            try {
                for (Graphics2D g : new Graphics2D[]{a, e}) {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.scale(scale[0], scale[1]);
                }
                marquee.draw(a, text, 0, 2, 100, 25, true, -1, 1000, 2500, 0);
                font.draw(e, text, 0, 2, 25, true, -1, false);
            } finally { a.dispose(); e.dispose(); }
            // Exclude the right-edge fade; interior text must match direct vector rendering.
            for (int y = 0; y < 160; y++) for (int x = 0; x < (int)(90 * scale[0]); x++)
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "Native glyph pixel at " + x + "," + y);
        }
    }

    private static BufferedImage marquee(WidgetMarquee marquee, String text, long now) {
        BufferedImage image = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        g.scale(2, 2); marquee.draw(g, text, 0, 2, 100, 25, true, -1, now, 2500, 0); g.dispose(); return image;
    }
    private static BufferedImage spectrum(WidgetSpectrum spectrum, HudConfig.Module module, PlaybackState state, long now) {
        BufferedImage image = new BufferedImage(120, 100, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        spectrum.paint(g, module, state, 120, 100, now, false); g.dispose(); return image;
    }
    private static int firstPixel(BufferedImage image, int x) {
        for (int y = 0; y < image.getHeight(); y++) if ((image.getRGB(x, y) >>> 24) != 0) return y;
        return -1;
    }
    static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()); }
    static PlaybackState.Track track() {
        PlaybackState.Track track = new PlaybackState.Track(); track.id = track.timeline = 77;
        track.title = "夜に駆ける · Into the Night"; track.artist = "YOASOBI";
        String[] lines = {"沈むように溶けてゆくように", "二人だけの空が広がる夜に", "さよならだけだった", "その一言で全てが分かった"};
        for (int i = 0; i < lines.length; i++) {
            PlaybackState.Line line = new PlaybackState.Line(); line.text = lines[i]; line.timestamp = i * 2000; line.duration = 2000;
            line.translation = "在只有我们两人的夜空里"; line.romanization = "futari dake no sora ga hirogaru yoru ni";
            for (int ch = 0; ch < line.text.length(); ch++) {
                PlaybackState.Word word = new PlaybackState.Word(); word.text = line.text.substring(ch, ch + 1);
                word.timestamp = line.timestamp + ch * 2000 / line.text.length(); word.duration = 2000 / line.text.length(); line.words.add(word);
            }
            track.lyrics.add(line);
        }
        return track;
    }
}
