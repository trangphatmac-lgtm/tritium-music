package tritium.desktop.hud;

import tritium.desktop.DesktopAppState;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.screens.ncm.LyricLine;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class HudStateSnapshot {
    public final long nowMillis;
    public final boolean smoke;
    public final boolean playing;
    public final boolean paused;
    public final boolean downloading;
    public final double downloadProgress;
    public final String downloadSpeed;
    public final long musicId;
    public final String musicName;
    public final String artistsName;
    public final float currentTimeMillis;
    public final float totalTimeMillis;
    public final double volume;
    public final float[] spectrum;
    public final List<LyricSnapshot> lyrics;
    public final int currentLyricIndex;
    public final boolean haveNoWords;
    public final BufferedImage cover;
    public final BufferedImage smallCover;
    public final BufferedImage blurredCover;

    private HudStateSnapshot(long nowMillis, boolean smoke, boolean playing, boolean paused, boolean downloading,
                             double downloadProgress, String downloadSpeed, long musicId, String musicName,
                             String artistsName, float currentTimeMillis, float totalTimeMillis, double volume,
                             float[] spectrum, List<LyricSnapshot> lyrics, int currentLyricIndex,
                             boolean haveNoWords, BufferedImage cover, BufferedImage smallCover,
                             BufferedImage blurredCover) {
        this.nowMillis = nowMillis;
        this.smoke = smoke;
        this.playing = playing;
        this.paused = paused;
        this.downloading = downloading;
        this.downloadProgress = downloadProgress;
        this.downloadSpeed = downloadSpeed;
        this.musicId = musicId;
        this.musicName = musicName;
        this.artistsName = artistsName;
        this.currentTimeMillis = currentTimeMillis;
        this.totalTimeMillis = totalTimeMillis;
        this.volume = volume;
        this.spectrum = spectrum;
        this.lyrics = lyrics;
        this.currentLyricIndex = currentLyricIndex;
        this.haveNoWords = haveNoWords;
        this.cover = cover;
        this.smallCover = smallCover;
        this.blurredCover = blurredCover;
    }

    public static HudStateSnapshot capture(boolean smoke) {
        if (smoke) {
            return synthetic();
        }

        long now = System.currentTimeMillis();
        AudioPlayer player = CloudMusic.player;
        Music music = CloudMusic.currentlyPlaying;
        boolean hasPlayer = player != null && music != null && !safeFinished(player);
        boolean paused = hasPlayer && safePaused(player);
        float current = hasPlayer ? safeCurrentMillis(player) : 0;
        float total = hasPlayer ? Math.max(1, safeTotalMillis(player)) : 1;
        long musicId = music == null ? -1 : music.getId();

        List<LyricSnapshot> lyricSnapshots = new ArrayList<>();
        int currentIndex = -1;
        LyricLine currentLyric = CloudMusic.currentLyric;
        synchronized (CloudMusic.lyrics) {
            for (int i = 0; i < CloudMusic.lyrics.size(); i++) {
                LyricLine line = CloudMusic.lyrics.get(i);
                if (line == currentLyric) {
                    currentIndex = i;
                }
                lyricSnapshots.add(copyLyric(line));
            }
        }

        return new HudStateSnapshot(
                now,
                false,
                hasPlayer,
                paused,
                DesktopAppState.downloadStatus().downloading,
                DesktopAppState.downloadStatus().downloadProgress,
                DesktopAppState.downloadStatus().downloadSpeed,
                musicId,
                music == null ? "未在播放" : music.getName(),
                music == null ? "" : music.getArtistsName(),
                current,
                total,
                DesktopAppState.preferences().volume().getValue(),
                AudioPlayer.bandValues == null ? new float[0] : Arrays.copyOf(AudioPlayer.bandValues, AudioPlayer.bandValues.length),
                lyricSnapshots,
                currentIndex,
                CloudMusic.haveNoWords,
                musicId < 0 ? null : HudArtworkCache.get(musicId, HudArtworkCache.ArtworkType.COVER),
                musicId < 0 ? null : HudArtworkCache.get(musicId, HudArtworkCache.ArtworkType.SMALL),
                musicId < 0 ? null : HudArtworkCache.get(musicId, HudArtworkCache.ArtworkType.BLURRED)
        );
    }

    private static LyricSnapshot copyLyric(LyricLine line) {
        List<WordSnapshot> words = new ArrayList<>(line.words.size());
        for (LyricLine.Word word : line.words) {
            words.add(new WordSnapshot(word.word, word.timestamp, word.duration));
        }
        return new LyricSnapshot(
                line.getTimestamp(),
                line.duration,
                line.getLyric(),
                CloudMusic.getSecondaryLyrics(line),
                line.isBreakLine,
                words
        );
    }

    private static HudStateSnapshot synthetic() {
        long now = System.currentTimeMillis();
        float progress = now % 24000;
        List<LyricSnapshot> lyrics = new ArrayList<>();
        lyrics.add(line(0, 4500, "Tritium Music", "desktop HUD preview"));
        lyrics.add(line(4500, 5200, "Follow the rhythm", "系统级歌词浮窗"));
        lyrics.add(line(9700, 5200, "Spectrum in motion", "频谱正在渲染"));
        lyrics.add(line(14900, 5200, "Now playing everywhere", "信息浮窗已就绪"));
        lyrics.add(line(20100, 3900, "Ready", "点击设置可编辑布局"));

        int currentIndex = 0;
        for (int i = 0; i < lyrics.size(); i++) {
            LyricSnapshot lyric = lyrics.get(i);
            if (progress >= lyric.timestamp) {
                currentIndex = i;
            }
        }

        float[] spectrum = new float[512];
        for (int i = 0; i < spectrum.length; i++) {
            double phase = now / 180.0 + i * 0.09;
            spectrum[i] = (float) Math.max(0, Math.sin(phase) * .5 + Math.sin(phase * .37) * .35 + .35);
        }

        return new HudStateSnapshot(
                now,
                true,
                true,
                false,
                true,
                (now % 3000) / 3000.0,
                "1.8 MB/s",
                -1,
                "Tritium Music",
                "HUD Smoke Preview",
                progress,
                24000,
                DesktopAppState.preferences().volume().getValue(),
                spectrum,
                lyrics,
                currentIndex,
                false,
                null,
                null,
                null
        );
    }

    private static LyricSnapshot line(long timestamp, long duration, String text, String secondary) {
        List<WordSnapshot> words = new ArrayList<>();
        String[] pieces = text.split(" ");
        long wordDuration = Math.max(1, duration / Math.max(1, pieces.length));
        long cursor = timestamp;
        for (int i = 0; i < pieces.length; i++) {
            String word = pieces[i] + (i == pieces.length - 1 ? "" : " ");
            words.add(new WordSnapshot(word, cursor, wordDuration));
            cursor += wordDuration;
        }
        return new LyricSnapshot(timestamp, duration, text, secondary, false, words);
    }

    private static boolean safeFinished(AudioPlayer player) {
        try {
            return player.isFinished();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean safePaused(AudioPlayer player) {
        try {
            return player.isPausing();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static float safeCurrentMillis(AudioPlayer player) {
        try {
            return player.getCurrentTimeMillis();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static float safeTotalMillis(AudioPlayer player) {
        try {
            return player.getTotalTimeMillis();
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    public record LyricSnapshot(long timestamp, long duration, String text, String secondaryText,
                                boolean breakLine, List<WordSnapshot> words) {
    }

    public record WordSnapshot(String text, long timestamp, long duration) {
    }
}
