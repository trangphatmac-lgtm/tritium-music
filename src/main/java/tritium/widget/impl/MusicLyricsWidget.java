package tritium.widget.impl;

import tritium.desktop.DesktopAppState;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.font.CFontRenderer;
import tritium.screens.ncm.LyricLine;

public final class MusicLyricsWidget {
    private static double scrollOffset = 0;

    private MusicLyricsWidget() {
    }

    public static void resetProgress(float progress) {
        if (CloudMusic.lyrics.isEmpty()) {
            return;
        }

        CloudMusic.setLyricsProgress(progress);
        scrollOffset = CloudMusic.lyrics.indexOf(CloudMusic.currentLyric) * getLyricHeight();
    }

    public static double getScrollOffset() {
        return scrollOffset;
    }

    public static double getLyricHeight() {
        double baseHeight = getFontRenderer().getHeight();
        double adjustment = CloudMusic.hasSecondaryLyrics() ? 0 : -getSmallFontRenderer().getHeight() - 4;
        return baseHeight + adjustment + DesktopAppState.preferences().lyricHeight().getValue();
    }

    public static boolean hasSecondaryLyrics() {
        return CloudMusic.hasSecondaryLyrics();
    }

    public static String getSecondaryLyrics(LyricLine line) {
        return CloudMusic.getSecondaryLyrics(line);
    }

    private static CFontRenderer getFontRenderer() {
        return FontManager.pf25bold;
    }

    private static CFontRenderer getSmallFontRenderer() {
        return FontManager.pf18;
    }
}
