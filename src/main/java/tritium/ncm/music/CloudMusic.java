package tritium.ncm.music;

import tritium.i18n.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import repackage.com.jsyn.util.SampleLoader;
import repackage.javazoom.jl.converter.Converter;
import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.PCMProcessor;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.util.WavWriter;
import tritium.desktop.AppPaths;
import tritium.desktop.DesktopCookieStore;
import tritium.desktop.DesktopAppState;
import tritium.desktop.DesktopChatColor;
import tritium.desktop.hud.HudArtworkCache;
import tritium.interfaces.SharedConstants;
import tritium.ncm.OptionsUtil;
import tritium.ncm.RequestUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.stream.StreamingAudioSample;
import tritium.desktop.DesktopCacheManager;
import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.automix.AutoMixDebugSnapshot;
import tritium.ncm.music.automix.SongFeatures;
import tritium.ncm.music.automix.AutoMixPlanner;
import tritium.ncm.music.automix.SongFeatureService;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.GaussianKernel;
import tritium.rendering.MusicToast;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.Textures;
import tritium.screens.ncm.LyricLine;
import tritium.ncm.music.lyrics.BestLyricService;
import tritium.ncm.music.lyrics.LyricLoader;
import tritium.ncm.music.lyrics.LyricSong;
import tritium.ncm.music.lyrics.LyricSource;
import tritium.ncm.music.lyrics.OnlineLyricProviders;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.Location;
import tritium.utils.Tuple;
import tritium.utils.json.JsonUtils;
import tritium.utils.network.HttpUtils;
import tritium.utils.other.StringUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static tritium.ncm.music.automix.AutoMixDebugSnapshot.Status.*;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 9:34 AM
 */
public class CloudMusic implements SharedConstants {

    /** Internal result consumed by controls; never displayed or compared as translated text. */
    public static final String HEARTBEAT_CANCELLED = "heartbeat.cancelled";

    @Getter
    private static final Map<String, String> headers = new HashMap<>();
    public static volatile boolean loadingSession = false;
    public static volatile String sessionStatusText = "";
    public static volatile long loadingSessionStartedAt = 0L;
    public static volatile AudioPlayer player;
    // 当前播放列表
    public static volatile List<Music> playList = new ArrayList<>();
    public static volatile int curIdx = 0;
    public static volatile Music currentlyPlaying;
    public static volatile Thread playThread;

    public static volatile User profile;
    public static List<PlayList> playLists;
    public static volatile List<Long> likeList;
    private static final Object favoritesLock = new Object();
    private static final Map<Long, Object> pendingFavorites = new HashMap<>();

    public static boolean isLiked(long songId) {
        List<Long> favorites = likeList;
        return profile != null && favorites != null && favorites.contains(songId);
    }

    public static boolean isLikePending(long songId) {
        synchronized (favoritesLock) {
            return pendingFavorites.containsKey(songId);
        }
    }

    public static CompletableFuture<String> toggleLike(Music song) {
        return toggleLike(song, CloudMusicApi::like, MultiThreadingUtil::runAsync);
    }

    // Keep transport and scheduling injectable so failures and in-flight clicks can be tested offline.
    static CompletableFuture<String> toggleLike(Music song,
                                                BiFunction<Long, Boolean, RequestUtil.RequestAnswer> request,
                                                Executor executor) {
        final User user;
        final boolean liked;
        final Object token = new Object();
        if (song == null) {
            return CompletableFuture.completedFuture(I18n.text("player.idle"));
        }
        long songId = song.getId();
        synchronized (favoritesLock) {
            user = profile;
            if (user == null) {
                return CompletableFuture.completedFuture(I18n.text("favorites.signIn"));
            }
            if (likeList == null) {
                return CompletableFuture.completedFuture(I18n.text("favorites.loading"));
            }
            if (pendingFavorites.containsKey(songId)) {
                return CompletableFuture.completedFuture(I18n.text("favorites.updating"));
            }
            liked = !likeList.contains(songId);
            pendingFavorites.put(songId, token);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        Runnable update = () -> {
            String message;
            try {
                if (profile != user) {
                    message = I18n.text("login.changed");
                } else {
                    RequestUtil.RequestAnswer answer = request.apply(songId, liked);
                    JsonObject body = answer.toJsonObject();
                    if (answer.getStatus() != 200 || body == null || !body.has("code")
                            || body.get("code").getAsInt() != 200) {
                        message = I18n.text("favorites.failed");
                    } else {
                        synchronized (favoritesLock) {
                            if (profile != user || likeList == null) {
                                message = I18n.text("login.changed");
                            } else {
                                List<Long> updated = new ArrayList<>(likeList);
                                updated.removeIf(id -> id == songId);
                                if (liked) updated.add(songId);
                                likeList = List.copyOf(updated);
                                if (playLists != null) {
                                    playLists.stream().filter(CloudMusic::isLikedPlaylist)
                                            .forEach(PlayList::invalidateMusics);
                                }
                                message = liked ? I18n.text("favorites.added") : I18n.text("favorites.removed");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                message = I18n.text("favorites.failed");
            } finally {
                synchronized (favoritesLock) {
                    pendingFavorites.remove(songId, token);
                }
            }
            result.complete(message);
        };
        try {
            executor.execute(update);
        } catch (RuntimeException e) {
            synchronized (favoritesLock) {
                pendingFavorites.remove(songId, token);
            }
            result.complete(I18n.text("favorites.failed"));
        }
        return result;
    }

    public static volatile PlayMode playMode = PlayMode.Sequential;

    private static final Object playbackLock = new Object();
    private static volatile HeartbeatSession heartbeatSession;
    private static volatile boolean heartbeatLoading;
    private static PlayMode modeBeforeHeartbeat = PlayMode.Sequential;

    public static boolean isHeartbeatLoading() {
        return heartbeatLoading;
    }

    public static String heartbeatError() {
        HeartbeatSession session = heartbeatSession;
        return session == null ? null : session.error;
    }

    public static boolean isLikedPlaylist(PlayList playlist) {
        User user = profile;
        return playlist != null && user != null && playlist.getSpecialType() == 5
                && playlist.getCreator() != null && playlist.getCreator().getId() == user.getId();
    }

    public static CompletableFuture<String> toggleHeartbeat() {
        return toggleHeartbeat(CloudMusicApi::intelligenceList, MultiThreadingUtil::runAsync,
                CloudMusic::startHeartbeatPlayback);
    }

    static void startHeartbeatPlayback(HeartbeatSession session) {
        if (playThread instanceof PlayThread thread && thread.queueHeartbeat(session)) return;
        playInternal(session.songs, 0);
    }

    private static boolean isCurrentHeartbeat(HeartbeatSession session) {
        return session != null && heartbeatSession == session && profile == session.user
                && playMode == PlayMode.Heartbeat;
    }

    static CompletableFuture<String> toggleHeartbeat(HeartbeatSession.Request request, Executor executor,
                                                       Consumer<HeartbeatSession> startPlayback) {
        final HeartbeatSession session;
        synchronized (playbackLock) {
            if (heartbeatSession != null) {
                exitHeartbeat();
                return CompletableFuture.completedFuture(I18n.text("heartbeat.exited"));
            }
            if (profile == null) return CompletableFuture.completedFuture(I18n.text("heartbeat.signIn"));
            if (likeList == null || playLists == null)
                return CompletableFuture.completedFuture(I18n.text("heartbeat.likesLoading"));
            if (likeList.isEmpty()) return CompletableFuture.completedFuture(I18n.text("heartbeat.noLikes"));
            PlayList source = playLists.stream().filter(CloudMusic::isLikedPlaylist).findFirst().orElse(null);
            if (source == null) return CompletableFuture.completedFuture(I18n.text("heartbeat.noPlaylist"));
            Music current = currentlyPlaying;
            long seed = current != null && likeList.contains(current.getId()) ? current.getId()
                    : likeList.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(likeList.size()));
            session = new HeartbeatSession(profile, source, seed, request, executor);
            heartbeatSession = session;
            heartbeatLoading = true;
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            session.requestMore(session.seed).whenComplete((songs, failure) -> {
                synchronized (playbackLock) {
                    if (heartbeatSession != session) {
                        result.complete(HEARTBEAT_CANCELLED);
                        return;
                    }
                    if (profile != session.user || failure != null) {
                        exitHeartbeat();
                        result.complete(profile != session.user ? I18n.text("login.changed")
                                : heartbeatFailureMessage(failure));
                        return;
                    }
                    session.consumed();
                    session.songs.addAll(songs);
                    modeBeforeHeartbeat = playMode;
                    changePlayMode(PlayMode.Heartbeat);
                    heartbeatLoading = false;
                    try {
                        startPlayback.accept(session);
                        playedFrom = session.source;
                        result.complete(I18n.text("heartbeat.enabled"));
                    } catch (RuntimeException e) {
                        exitHeartbeat();
                        result.complete(I18n.text("heartbeat.startFailed"));
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (playbackLock) {
                if (heartbeatSession == session) exitHeartbeat();
            }
            result.complete(I18n.text("heartbeat.failed"));
        }
        return result;
    }

    private static String heartbeatFailureMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure instanceof IllegalStateException ? failure.getMessage() : I18n.text("heartbeat.network");
    }

    public static void exitHeartbeat() {
        synchronized (playbackLock) {
            heartbeatSession = null;
            heartbeatLoading = false;
            if (playMode == PlayMode.Heartbeat) changePlayMode(modeBeforeHeartbeat);
        }
    }

    public static CompletableFuture<String> cyclePlayMode() {
        return cyclePlayMode(CloudMusic::toggleHeartbeat);
    }

    static CompletableFuture<String> cyclePlayMode(Supplier<CompletableFuture<String>> startHeartbeat) {
        synchronized (playbackLock) {
            // A second click while loading advances past Heartbeat and invalidates its response.
            PlayMode next = heartbeatLoading ? PlayMode.Heartbeat.nextMode() : playMode.nextMode();
            if (next == PlayMode.Heartbeat) return startHeartbeat.get();
            exitHeartbeat();
            changePlayMode(next);
            return CompletableFuture.completedFuture(I18n.text("playMode.changed", next.getDisplayName()));
        }
    }

    private static void changePlayMode(PlayMode mode) {
        // Share the boundary lock with AutoMix promotion; a concurrent shuffle must not select C for B's audio.
        synchronized (playThread == null ? playbackLock : playThread) {
            if (playThread instanceof PlayThread thread) thread.cancelAutoMix(MODE_CHANGED);
            playMode = mode;
        }
    }

    public static volatile Quality quality = Quality.STANDARD;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static LyricLine currentLyric = null;
    public static boolean hasTransLyrics = false;
    public static boolean hasRomanization = false;
    public static boolean haveNoWords = false;

    public static final File COOKIE_FILE = AppPaths.cookieFile().toFile();

    public static void initLyrics(JsonObject rawLyricData, Music music, List<LyricLine> parsedLyrics) {
        resetLyricFlags();
        hasTransLyrics = parsedLyrics.stream().anyMatch(line -> line.translationText != null && !line.translationText.isBlank());
        hasRomanization = parsedLyrics.stream().anyMatch(line -> line.romanizationText != null && !line.romanizationText.isBlank());
        
        synchronized (lyrics) {
            updateLyricsList(parsedLyrics);
            currentLyric = lyrics.getFirst();
            haveNoWords = lyricsHaveNoWords();
            addLongBreaks();
        }

        MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
    }
    
    private static void resetLyricFlags() {
        hasTransLyrics = false;
        hasRomanization = false;
    }
    
    private static void updateLyricsList(List<LyricLine> parsedLyrics) {
        lyrics.clear();
        lyrics.addAll(parsedLyrics);
        
        if (lyrics.isEmpty()) {
            lyrics.add(LyricLine.placeholder());
        }
    }

    /**
     * 为歌词添加长间隔时的 "● ● ●"
     */
    private static void addLongBreaks() {
        final long longBreaksDuration = 3000L;
        
        if (haveNoWords) {
            // 如果不为逐字歌词的话只在开头添加长间隔
            addInitialBreakIfNeeded(longBreaksDuration);
            return;
        }
        
        addBreaksBetweenLyrics(longBreaksDuration);
    }

    /**
     * 歌词是否不为逐字歌词
     * @return true 表示不为逐字歌词
     */
    private static boolean lyricsHaveNoWords() {
        return lyrics.stream().allMatch(l -> l.words.isEmpty());
    }

    private static void addInitialBreakIfNeeded(long duration) {
        long firstTimestamp = lyrics.getFirst().getTimestamp();
        if (firstTimestamp >= duration) {
            addBreakLine(0L, firstTimestamp);
        }
    }
    
    private static void addBreaksBetweenLyrics(long duration) {
        long lastTimestamp = 0L;
        List<LyricLine> breaksToAdd = new ArrayList<>();

        for (LyricLine line : lyrics) {
            long lineDuration = line.duration;
            long gap = line.getTimestamp() - lastTimestamp;
            
            if (gap >= duration) {
                breaksToAdd.add(createBreakLine(lastTimestamp, gap));
            }
            
            lastTimestamp = line.getTimestamp() + lineDuration;
        }

        addAndSortBreaks(breaksToAdd);
    }
    
    private static LyricLine createBreakLine(long timestamp, long duration) {
        LyricLine line = new LyricLine(timestamp, "● ● ●");
        line.isBreakLine = true;
        line.words.add(new LyricLine.Word("● ● ●", timestamp, duration));
        return line;
    }
    
    private static void addBreakLine(long timestamp, long duration) {
        lyrics.add(createBreakLine(timestamp, duration));
        lyrics.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }
    
    private static void addAndSortBreaks(List<LyricLine> breaks) {
        lyrics.addAll(breaks);
        lyrics.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }

    private static long getLyricDuration(LyricLine line) {
        return line.duration;
    }

    /**
     * 更新当前歌词行
     * @param songProgress 歌曲进度 (ms)
     */
    public static void updateCurrentLyric(float songProgress) {
        LyricLine previousLyric = currentLyric;
        currentLyric = findCurrentLyric(songProgress);
        
        if (previousLyric != currentLyric) {
            resetLyricPositionUpdate();
        }
    }

    static final float JUMP_TO_NEXT_MILLIS = 300.0f;

    static boolean canJumpToNextEarly(double songProgress, LyricLine lyric) {
        if (lyric == null || lyric.words.isEmpty())
            return false;

//        LyricLine.Word last = lyric.words.getLast();
//
//        double lineStart = lyric.getTimestamp();
//        double lineEnd = lineStart + lyric.duration;
//
//        double lastWordStart = last.timestamp;
//        double lastWordEnd = lastWordStart + last.duration;
//
//        double tailGap = lineEnd - lastWordEnd;
//
//        double available = Math.max(last.duration, tailGap);
//
//        if (available < JUMP_TO_NEXT_MILLIS)
//            return false;
//
//        if (songProgress < lineEnd - JUMP_TO_NEXT_MILLIS)
//            return false;

        if (lyric.duration < JUMP_TO_NEXT_MILLIS)
            return false;

        return true;
    }
    
    public static LyricLine findCurrentLyric(double songProgress) {
        for (int i = 0; i < lyrics.size(); i++) {
            LyricLine lyric = lyrics.get(i);
            LyricLine prev = i > 0 ? lyrics.get(i - 1) : null;

            if (!haveNoWords
                    && !lyric.isBreakLine
                    && lyric.getTimestamp() > songProgress
                    && lyric.getTimestamp() - songProgress <= JUMP_TO_NEXT_MILLIS
                    && canJumpToNextEarly(songProgress, prev)) {
                return lyric;
            }

            if (lyric.getTimestamp() > songProgress) {
                return i > 0 ? lyrics.get(i - 1) : currentLyric;
            }

            if (i == lyrics.size() - 1) {
                return lyric;
            }
        }
        return currentLyric;
    }

    public static void resetLyricPositionUpdate() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();
        });
    }

    public static void resetLyricStatus() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();
            
            for (LyricLine.Word word : l.words) {
                Arrays.fill(word.emphasizes, 0);
            }
            
            l.markDirty();
        });
    }

    public static void setLyricsProgress(float progress) {
        if (lyrics.isEmpty()) return;
        
        try {
            resetLyricDisplayStates();
            updateCurrentLyric(progress);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void resetLyricDisplayStates() {
        resetAllLyricsState();
        resetWordStates();
    }

    private static void resetAllLyricsState() {
        for (LyricLine lyric : lyrics) {
            lyric.scrollWidth = 0;
            lyric.offsetX = 0;
            lyric.offsetY = Double.MIN_VALUE;
            lyric.targetOffsetX = 0;
        }
    }

    private static void resetWordStates() {
        for (LyricLine lyric : lyrics) {
            for (LyricLine.Word word : lyric.words) {
                word.alpha = 0.0f;
                word.progress = 0.0;
            }
        }
    }

    public static String getSecondaryLyrics(LyricLine lyricLine) {
        if (!DesktopAppState.preferences().showTranslation().getValue()) {
            return "";
        }

        if (hasTransLyrics) {
            return getTranslationOrRomanizationText(lyricLine);
        }
        
        if (hasRomanization) {
            return getRomanizationTextIfEnabled(lyricLine);
        }
        
        return "";
    }
    
    private static String getTranslationOrRomanizationText(LyricLine lyricLine) {
        boolean showRoman = DesktopAppState.preferences().showRoman().getValue();
        
        if (!showRoman) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
        }
        
        if (hasRomanization) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }
        
        return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
    }
    
    private static String getRomanizationTextIfEnabled(LyricLine lyricLine) {
        if (DesktopAppState.preferences().showRoman().getValue()) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }
        return "";
    }

    public static boolean hasSecondaryLyrics() {
        boolean hasAvailableLyrics = hasTransLyrics || hasRomanization;
        boolean showTranslationEnabled = DesktopAppState.preferences().showTranslation().getValue();
        return hasAvailableLyrics && showTranslationEnabled;
    }

    @SneakyThrows
    public static void initNCM() {
        String cookie = getCookieFromFileOrOptions();
        
        if (cookie.isEmpty()) {
            System.out.println("[NCM] Not logged in.");
        } else {
            loadNCMFromStoredCookie(cookie);
        }
    }

    private static void loadNCMFromStoredCookie(String cookie) {
        loadingSession = true;
        loadingSessionStartedAt = System.currentTimeMillis();
        sessionStatusText = I18n.text("login.checkingCookie");
        try {
            loadNCM(cookie);
        } finally {
            loadingSession = false;
            loadingSessionStartedAt = 0L;
            sessionStatusText = "";
        }
    }

    @SneakyThrows
    private static String loadCookie() {
        return DesktopCookieStore.load(COOKIE_FILE.toPath());
    }
    
    private static String getCookieFromFileOrOptions() {
        String cookie = loadCookie();
        return cookie.isEmpty() ? OptionsUtil.getCookie() : cookie;
    }

    public static void loadNCM(String cookie) {
        exitHeartbeat();
        playLists = null;
        synchronized (favoritesLock) {
            profile = null;
            likeList = null;
            pendingFavorites.clear();
        }
        OptionsUtil.setCookie(cookie);
        sessionStatusText = I18n.text("login.loadingUser");
        // 获取用户信息
        profile = getUserProfile();

        if (profile == null) {
            sessionStatusText = I18n.text("login.expired");
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        sessionStatusText = I18n.text("login.loadingPlaylists");
        CloudMusic.playLists = loadUserPlaylists();
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        sessionStatusText = I18n.text("login.loadingLikes");
        likeList = List.copyOf(likeList());
        NCMScreen.getInstance().markDirty();
    }
    
    private static List<PlayList> loadUserPlaylists() {
        List<PlayList> userPlaylists = new ArrayList<>();
        int page = 0;

        while (true) {
            List<PlayList> pagePlaylists = fetchPlaylistsPage(page);
            
            if (pagePlaylists.isEmpty()) {
                break;
            }
            
            userPlaylists.addAll(pagePlaylists);
            page++;
        }
        
        return userPlaylists;
    }
    
    private static List<PlayList> fetchPlaylistsPage(int page) {
        try {
            return profile.playLists(page, 30);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SneakyThrows
    public static void onStop() {
        DesktopCookieStore.save(COOKIE_FILE.toPath(), OptionsUtil.getCookie());

    }

    public static void shutdown() {
        exitHeartbeat();
        doBreak = true;
        playing.set(false);

        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playThread = null;
        }

        if (player != null) {
            try {
                player.close();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private static void setDownloading(boolean downloading) {
        DesktopAppState.downloadStatus().downloading = downloading;
        NCMScreen.getInstance().downloading = downloading;
    }

    private static void setDownloadProgress(double progress) {
        DesktopAppState.downloadStatus().downloadProgress = progress;
        NCMScreen.getInstance().downloadProgress = progress;
    }

    private static void setDownloadSpeed(String speed) {
        DesktopAppState.downloadStatus().downloadSpeed = speed;
        NCMScreen.getInstance().downloadSpeed = speed;
    }

    @Getter
    public enum PlayMode {
        Random("F", "playMode.Random"),
        LoopInList("I", "playMode.LoopInList"),
        LoopSingle("L", "playMode.LoopSingle"),
        Sequential("G", "playMode.Sequential"),
        Heartbeat("D", "playMode.Heartbeat");

        private final String icon;
        private final String displayName;

        PlayMode(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return I18n.text(displayName);
        }

        public PlayMode nextMode() {
            return switch (this) {
                case Sequential -> LoopInList;
                case LoopInList -> LoopSingle;
                case LoopSingle -> Random;
                case Random -> Heartbeat;
                case Heartbeat -> Sequential;
            };
        }
    }

    public static volatile boolean dontAdd = false;

    public static void prev() {
        synchronized (playbackLock) {
            HeartbeatSession session = heartbeatSession;
            if (playMode == PlayMode.Heartbeat && session != null && session.error != null
                    && (playThread == null || !playThread.isAlive())) {
                session.error = null;
                startNewPlayThread(playList, Math.max(0, Math.min(curIdx - 1, playList.size() - 1)));
                return;
            }
        }
        synchronized (playThread == null ? playbackLock : playThread) {
            updatePlayCountIfNeeded();

            if (!canPlayPrevious()) return;

            if (player != null && !playList.isEmpty()) {
                prepareForTrackChange();
                if (playThread instanceof PlayThread thread) thread.markPrevious();
                curIdx--;
                stopCurrentPlayback();
            }
        }
    }
    
    private static boolean canPlayPrevious() {
        if (curIdx - 1 >= 0) {
            return true;
        }
        
        if (playMode == PlayMode.LoopInList) {
            curIdx = playList.size();
            return true;
        } else if (playMode == PlayMode.LoopSingle) {
            curIdx++;
            return true;
        }
        
        return false;
    }

    public static void next() {
        HeartbeatSession session = heartbeatSession;
        if (playMode == PlayMode.Heartbeat && session != null && session.error != null
                && (playThread == null || !playThread.isAlive())) {
            synchronized (playbackLock) {
                if (heartbeatSession == session) {
                    session.error = null;
                    startNewPlayThread(playList, Math.max(0, Math.min(curIdx, playList.size())));
                }
            }
            return;
        }
        synchronized (playThread == null ? playbackLock : playThread) {
            if (!canPlayNext()) return;

            if (player != null && !playList.isEmpty()) {
                updatePlayCountIfNeeded();
                prepareForTrackChange();
                if (playThread instanceof PlayThread thread) thread.markNext();
                curIdx++;
                stopCurrentPlayback();
            }
        }
    }
    
    private static boolean canPlayNext() {
        if (playThread instanceof PlayThread thread && thread.hasQueuedNext()) return true;
        if (curIdx >= playList.size()) return false;
        if (curIdx + 1 > playList.size() - 1 && playMode == PlayMode.Sequential) {
            return false;
        }
        return true;
    }

    /**
     * 给网易云发送当前歌曲的播放时长
     */
    private static void updatePlayCountIfNeeded() {
        if (playedFrom != null && player != null && currentlyPlaying != null) {
            currentlyPlaying.updPlayCount(playedFrom, player.getCurrentTimeSeconds());
        }
    }
    
    private static void prepareForTrackChange() {
        dontAdd = true;
    }
    
    public static void setVolume(float volume) {
        synchronized (playThread == null ? playbackLock : playThread) {
            if (playThread instanceof PlayThread thread && thread.transition != null) {
                thread.transition.setVolume(volume);
            } else if (player != null) player.setVolume(volume);
        }
    }

    /** Rendering only reads a volatile immutable snapshot and never waits on playback/network work. */
    public static AutoMixDebugSnapshot autoMixDebugSnapshot() {
        return playThread instanceof PlayThread thread ? thread.debugSnapshot : AutoMixDebugSnapshot.idle();
    }

    public static void autoMixSettingChanged() {
        if (playThread instanceof PlayThread thread) thread.invalidatePreparation();
    }

    public static void setPaused(boolean paused) {
        synchronized (playThread == null ? playbackLock : playThread) {
            if (playThread instanceof PlayThread thread && thread.transition != null) {
                if (paused) thread.transition.pause(); else thread.transition.resume();
            } else if (player != null) {
                if (paused) player.pause(); else player.unpause();
            }
        }
    }

    public static void seek(float millis) {
        synchronized (playThread == null ? playbackLock : playThread) {
            if (playThread instanceof PlayThread thread) thread.cancelAutoMix(SEEK);
            if (player != null) player.setPlaybackTime(millis);
        }
    }

    private static void stopCurrentPlayback() {
        if (playThread instanceof PlayThread thread) thread.cancelAutoMix(STOPPED);
        player.close();
        playing.set(false);
        if (playThread instanceof PlayThread thread) thread.refreshAutoMixDebug();
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

    /** Queue a song without touching the current audio, including while it is paused/loading. */
    public static String playNext(Music song) {
        return playNext(song, selected -> play(List.of(selected), 0));
    }

    static String playNext(Music song, Consumer<Music> startPlayback) {
        if (song == null) return I18n.text("player.select");
        synchronized (playbackLock) {
            if (playThread instanceof PlayThread thread) {
                String result = thread.queueNext(song);
                if (result != null) return result;
            }
            startPlayback.accept(song);
            return I18n.text("player.start");
        }
    }

    /**
     * 播放给定的列表中的所有歌曲
     * @param songs 歌曲列表
     * @param startIdx 第一首播放的索引
     */
    @SneakyThrows
    public static void play(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) return;
        synchronized (playbackLock) {
            exitHeartbeat();
            playedFrom = null;
            playInternal(new ArrayList<>(songs), startIdx);
        }
    }

    @SneakyThrows
    private static void playInternal(List<Music> safeSongList, int startIdx) {
        stopExistingPlayThread();
        
        if (playMode == PlayMode.Random) {
            // 打乱列表以及开始索引
            startIdx = handleRandomPlayMode(safeSongList, startIdx);
        }
        
        startIdx = Math.min(normalizeStartIndex(startIdx), safeSongList.size() - 1);
        loadMusicCover(safeSongList.get(startIdx));
        
        playList = safeSongList;
        startNewPlayThread(safeSongList, startIdx);
    }
    
    private static void stopExistingPlayThread() throws InterruptedException {
        if (playThread != null) {
            doBreak = true;
            playing.set(false);
            playThread.interrupt();
            playThread.join();
        }
    }
    
    private static int handleRandomPlayMode(List<Music> songs, int startIdx) {
        if (startIdx == -1) {
            Collections.shuffle(songs);
        } else {
            Music selectedMusic = songs.get(startIdx);
            Collections.shuffle(songs);
            startIdx = songs.indexOf(selectedMusic);
        }
        return startIdx;
    }
    
    private static int normalizeStartIndex(int startIdx) {
        return startIdx == -1 ? 0 : startIdx;
    }
    
    private static void startNewPlayThread(List<Music> songs, int startIdx) {
        playThread = new PlayThread(songs, startIdx);
        doBreak = false;
        dontAdd = false;
        playing.set(false);
        playThread.start();
    }

    static volatile boolean doBreak = false;

    static AtomicBoolean playing = new AtomicBoolean(true);

    static class PlayThread extends Thread {
        private List<Music> songs;
        private final int startIdx;
        private HeartbeatSession heartbeat;
        private HeartbeatSession pendingHeartbeat;
        private boolean acceptingHeartbeat = true;
        private boolean acceptingNext = true;
        private final List<Music> nextSongs = new ArrayList<>();
        private Music activeSong;
        private boolean previousRequested;
        private boolean queuedTrack;
        private long mixRevision;
        private Preparation preparation;
        private volatile AutoMixTransition transition;
        private Music transitionSong;
        private PlayMode transitionMode;
        private final SongFeatureService songFeatures = new SongFeatureService();
        private Preparation transitionPreparation;
        private AutoMixDebugSnapshot.Status debugStatus = IDLE;
        private String debugDetail = "";
        private final Deque<AutoMixDebugSnapshot.Event> debugEvents = new ArrayDeque<>();
        private volatile AutoMixDebugSnapshot debugSnapshot = AutoMixDebugSnapshot.idle();
        private long lastDebugPublishNanos;

        private void debugState(AutoMixDebugSnapshot.Status status) { debugState(status, ""); }

        private synchronized void debugState(AutoMixDebugSnapshot.Status status, String detail) {
            if (status == debugStatus && detail.equals(debugDetail)) return;
            debugStatus = status;
            debugDetail = detail;
            debugEvents.addFirst(new AutoMixDebugSnapshot.Event(System.currentTimeMillis(), status, detail));
            while (debugEvents.size() > 5) debugEvents.removeLast();
            lastDebugPublishNanos = 0;
        }

        synchronized void publishAutoMixDebug() {
            if (!DesktopAppState.preferences().autoMixDebug().getValue()) {
                if (debugSnapshot.capturedAtMillis() != 0) debugSnapshot = AutoMixDebugSnapshot.idle();
                lastDebugPublishNanos = 0;
                return;
            }
            long now = System.nanoTime();
            if (lastDebugPublishNanos != 0 && now - lastDebugPublishNanos < 100_000_000L) return;
            Preparation request = transition == null ? preparation : transitionPreparation;
            AudioPlayer a = request == null ? player : request.outgoing;
            AudioPlayer b = transition != null ? (AudioPlayer) transition.incoming
                    : request == null ? null : request.incoming;
            debugSnapshot = new AutoMixDebugSnapshot(System.currentTimeMillis(), debugStatus, debugDetail,
                    playMode.name(), quality.name(), mixRevision,
                    debugTrack(request == null ? activeSong : request.sourceSong),
                    debugTrack(request == null ? autoMixNextSong() : request.song),
                    debugVoice(a), debugVoice(b), request == null ? null : request.featuresA,
                    request == null ? null : request.featuresB, request == null ? null : request.plan,
                    transition != null, transition != null && transition.hasSwitched(), List.copyOf(debugEvents));
            lastDebugPublishNanos = now;
        }

        private static AutoMixDebugSnapshot.Track debugTrack(Music song) {
            return song == null ? null : new AutoMixDebugSnapshot.Track(song.getId(), song.getName());
        }

        private static AutoMixDebugSnapshot.Voice debugVoice(AudioPlayer source) {
            if (source == null) return null;
            try {
                return new AutoMixDebugSnapshot.Voice(source.getCurrentTimeMillis(), source.getTotalTimeMillis(),
                        source.getVolume(), source.getMixGain(), source.getMixLowPassHz(), source.getMixHighPassHz(),
                        source.isPausing(), source.isFinished(), source.getMixEqualizer());
            } catch (RuntimeException unavailable) {
                // A decoder may already have disposed its sample. Diagnostics cannot interrupt playback.
                return null;
            }
        }

        synchronized void refreshAutoMixDebug() {
            lastDebugPublishNanos = 0;
            publishAutoMixDebug();
        }

        /** Background work has explicit ownership: late/cancelled results close their own deck. */
        static final class Preparation {
            final Music song;
            final Music sourceSong;
            final AudioPlayer outgoing;
            final long revision;
            final PlayMode mode;
            final Quality quality;
            final boolean fullMode;
            volatile boolean cancelled;
            volatile boolean done;
            volatile AudioPlayer incoming;
            volatile AutoMixPlan plan;
            volatile SongFeatures.Summary featuresA, featuresB;
            volatile AutoMixDebugSnapshot.Status stage = FEATURES;
            volatile String failureType = "";

            Preparation(Music song, AudioPlayer outgoing, long revision) {
                this.song = song;
                this.sourceSong = currentlyPlaying;
                this.outgoing = outgoing;
                this.revision = revision;
                this.mode = playMode;
                this.quality = CloudMusic.quality;
                this.fullMode = DesktopAppState.preferences().autoMixFullMode().getValue();
            }

            synchronized void finish(AudioPlayer incoming, AutoMixPlan plan) {
                if (cancelled) { if (incoming != null) incoming.close(); }
                else {
                    this.incoming = incoming;
                    this.plan = plan;
                    if (incoming != null && plan != null) stage = READY;
                }
                done = true;
            }

            synchronized AudioPlayer take() {
                AudioPlayer result = incoming;
                incoming = null;
                return result;
            }

            synchronized void cancel() {
                cancelled = true;
                if (incoming != null) { incoming.close(); incoming = null; }
            }
        }

        synchronized void invalidatePreparation() { invalidatePreparation(CANCELLED); }

        private synchronized void invalidatePreparation(AutoMixDebugSnapshot.Status reason) {
            mixRevision++;
            if (preparation != null) {
                preparation.cancel(); preparation = null;
                debugState(reason);
            }
        }

        synchronized void cancelAutoMix() { cancelAutoMix(CANCELLED); }

        synchronized void cancelAutoMix(AutoMixDebugSnapshot.Status reason) {
            boolean hadWork = preparation != null || transition != null;
            invalidatePreparation(reason);
            if (transition != null) { transition.cancel(); transition = null; }
            transitionSong = null;
            transitionPreparation = null;
            // A's EOF callback is suppressed during overlap. Cancellation must release that wait.
            if (player != null && player.isFinished()) playing.set(false);
            if (hadWork || reason == STOPPED) debugState(reason);
            publishAutoMixDebug();
        }

        /** Peek without consuming an explicit next request or reshuffling the queue. */
        synchronized Music autoMixNextSong() {
            if (pendingHeartbeat != null || playMode == PlayMode.LoopSingle || lastMode != playMode
                    || songs != playList || curIdx < 0 || curIdx >= songs.size()) return null;
            Music next;
            if (!nextSongs.isEmpty()) next = nextSongs.getFirst();
            else if (curIdx + 1 < songs.size()) next = songs.get(curIdx + 1);
            else if ((playMode == PlayMode.LoopInList || playMode == PlayMode.Random) && songs.size() > 1)
                next = songs.getFirst();
            else return null;
            return activeSong != null && activeSong.getId() == next.getId() ? null : next;
        }

        synchronized void tickAutoMix() {
            try { updateAutoMix(); }
            finally { publishAutoMixDebug(); }
        }

        private void updateAutoMix() {
            if (dontAdd || doBreak || isInterrupted() || !playing.get()) { debugState(IDLE); return; }
            if (transition != null) {
                if (transitionMode != playMode || songs != playList) { cancelAutoMix(CONTEXT_CHANGED); return; }
                if (transition.incoming.isFinished()) {
                    cancelAutoMix(INCOMING_ENDED);
                    if (player.isFinished()) playing.set(false);
                    return;
                }
                transition.tick(this::promoteAutoMix);
                debugState(transition.current().isPausing() ? PAUSED : transition.hasSwitched() ? SWITCHED : MIXING);
                if (transition.isComplete()) {
                    debugState(COMPLETED, transition.plan.strategy().name());
                    transition = null; transitionSong = null; transitionPreparation = null;
                }
                return;
            }
            boolean enabled = DesktopAppState.preferences().autoMix().getValue();
            Music next = enabled ? autoMixNextSong() : null;
            if (preparation != null && (next == null || !next.equals(preparation.song)
                    || preparation.outgoing != player || preparation.revision != mixRevision
                    || preparation.mode != playMode || preparation.quality != quality
                    || preparation.fullMode != DesktopAppState.preferences().autoMixFullMode().getValue())) invalidatePreparation();
            if (!enabled) { debugState(DISABLED); return; }
            if (player == null || player.isFinished()) { debugState(IDLE); return; }
            if (next == null) {
                debugState(playMode == PlayMode.LoopSingle ? SINGLE_REPEAT
                        : pendingHeartbeat != null || lastMode != playMode || songs != playList ? CONTEXT_CHANGED : NO_NEXT);
                return;
            }
            if (preparation == null) {
                if (!(player.getTotalTimeMillis() >= 50_000)) { debugState(SHORT_TRACK); return; }
                if (player.isPausing()) { debugState(PAUSED); return; }
                if (!(player.getTotalTimeMillis() - player.getCurrentTimeMillis() <= 60_000)) {
                    debugState(WAITING_WINDOW); return;
                }
                // Bound the speculative work to the last minute, including a 20 s CUE window.
                Preparation request = new Preparation(next, player, mixRevision);
                preparation = request;
                Music current = activeSong;
                double duration = player.getTotalTimeMillis();
                MultiThreadingUtil.runAsync(() -> prepareAutoMix(request, current, duration));
            }
            Preparation ready = preparation;
            boolean playable = ready.done && ready.plan != null && ready.incoming != null;
            if (playable && player.isPausing()) { debugState(PAUSED); return; }
            debugState(ready.stage, ready.failureType);
            if (!playable) return;
            double now = player.getCurrentTimeMillis();
            if (now < ready.plan.startAMillis()) return;
            // A seek or slow network/decode must not jump into the middle of an expired plan.
            if (now > ready.plan.startAMillis() + 500) {
                ready.stage = LATE; ready.cancel(); debugState(LATE); return;
            }
            AudioPlayer incoming = ready.take();
            transitionSong = ready.song;
            transitionMode = playMode;
            transitionPreparation = ready;
            transition = new AutoMixTransition(player, incoming, ready.plan);
            try {
                incoming.setAfterPlayed(() -> {
                    if (playThread == this && CloudMusic.player == incoming && transition == null) playing.set(false);
                });
                transition.start(player.getVolume());
                preparation = null;
                debugState(MIXING);
            } catch (RuntimeException failure) {
                transition.cancel();
                transition = null;
                transitionSong = null;
                transitionPreparation = null;
                // Leave the failed request consumed; ordinary playback remains available.
                ready.stage = START_FAILED;
                ready.failureType = failure.getClass().getSimpleName();
                ready.cancel();
                debugState(ready.stage, ready.failureType);
            }
        }

        private void prepareAutoMix(Preparation request, Music current, double duration) {
            AudioPlayer incoming = null;
            AutoMixPlan plan = null;
            try {
                var features = songFeatures.loadPair(current.getId(), duration,
                        request.song.getId(), request.song.getDuration());
                if (request.cancelled) return;
                request.featuresA = features.a().summary();
                request.featuresB = features.b().summary();
                request.stage = PLANNING;
                plan = AutoMixPlanner.plan(features.a(), features.b(), request.fullMode);
                request.plan = plan;
                if (plan == null) { request.stage = NO_PLAN; return; }
                request.stage = URL;
                Tuple<String, String> url = request.song.getPlayUrl(request.quality);
                if (request.cancelled) return;
                if (url == null) { request.stage = NO_URL; return; }
                request.stage = AUDIO;
                File file = getPreloadedMusicFile(url, request.song, request.quality, request);
                if (request.cancelled) return;
                request.stage = DECODING;
                incoming = new AudioPlayer(file, false);
                request.stage = PLANNING;
                request.featuresB = features.b().withDuration(incoming.getTotalTimeMillis()).summary();
                // The actual decoder duration takes precedence over metadata (e.g. previews).
                plan = AutoMixPlanner.plan(features.a(), features.b().withDuration(incoming.getTotalTimeMillis()), request.fullMode);
                if (plan == null) { incoming.close(); incoming = null; request.stage = NO_PLAN; }
                else if (!request.cancelled) {
                    request.stage = WARMING;
                    incoming.prepareAutoMix(plan, () -> request.cancelled || Thread.currentThread().isInterrupted());
                }
            } catch (Exception failure) {
                if (incoming != null) { incoming.close(); incoming = null; }
                plan = null; // Network/format errors degrade to normal playback, not a stopped queue.
                if (!request.cancelled) {
                    request.stage = PREPARATION_FAILED;
                    request.failureType = failure.getClass().getSimpleName();
                    System.err.println("[AutoMix] Preparation unavailable: " + failure.getClass().getSimpleName());
                }
            } finally {
                request.finish(incoming, plan);
            }
        }

        private void promoteAutoMix() {
            AudioPlayer incoming = (AudioPlayer) transition.incoming;
            // Validate before mutating the queue. Selection changes before this point cancel the overlap.
            if (!transitionSong.equals(autoMixNextSong()))
                throw new IllegalStateException("AutoMix queue changed before promotion");
            advanceAfterTrack(curIdx);
            if (activeSong == null || !activeSong.equals(transitionSong))
                throw new IllegalStateException("AutoMix queue changed during overlap");
            player = incoming;
            currentlyPlaying = transitionSong;
            playing.set(true);
            lyrics.clear();
            currentLyric = null;
            resetLyricFlags();
            try {
                loadLyric(transitionSong);
                loadMusicCover(transitionSong);
                notifySongStart(transitionSong);
                preloadNextCover();
            } catch (RuntimeException failure) {
                // The audio/queue commit above is complete; optional UI work cannot roll it back.
                System.err.println("[AutoMix] Metadata notification unavailable: " + failure.getClass().getSimpleName());
            }
        }


        public PlayThread(List<Music> songs, int startIdx) {
            this.songs = songs;
            this.setName("Play Thread");
            this.startIdx = startIdx;
            this.activeSong = startIdx < songs.size() ? songs.get(startIdx) : null;
            this.heartbeat = heartbeatSession != null && heartbeatSession.songs == songs ? heartbeatSession : null;
        }

        @Override
        public void run() {
            try {
                runPlaylist();
            } finally {
                retire();
            }
        }

        synchronized boolean queueHeartbeat(HeartbeatSession session) {
            if (!acceptingHeartbeat || doBreak || isInterrupted()) return false;
            if (transition != null && !transition.hasSwitched()) cancelAutoMix(QUEUE_CHANGED);
            pendingHeartbeat = session;
            invalidatePreparation(QUEUE_CHANGED);
            return true;
        }

        synchronized String queueNext(Music song) {
            if (!acceptingNext || doBreak || isInterrupted()) return null;
            if (activeSong != null && activeSong.getId() == song.getId()) return I18n.text("player.playing");
            if (transition != null && !transition.hasSwitched()) cancelAutoMix(QUEUE_CHANGED);
            nextSongs.removeIf(queued -> queued.getId() == song.getId());
            nextSongs.addFirst(song);
            invalidatePreparation(QUEUE_CHANGED);
            return I18n.text("player.queued");
        }

        synchronized boolean hasQueuedNext() {
            return acceptingNext && !nextSongs.isEmpty();
        }

        synchronized void markPrevious() {
            previousRequested = true;
        }

        synchronized void markNext() {
            previousRequested = false;
        }

        /** Only the playback thread mutates its list; UI requests wait here until a track boundary. */
        synchronized void advanceAfterTrack(int completedIndex) {
            boolean previous = previousRequested;
            previousRequested = false;
            boolean manual = dontAdd;
            boolean adopted = adoptHeartbeat(completedIndex);
            if (!adopted) updateCurrentIndex();
            queuedTrack = false;
            if (!previous && !nextSongs.isEmpty()) {
                // Natural completion in single-repeat mode still honors the explicit next song.
                if (!adopted && !manual && playMode == PlayMode.LoopSingle) curIdx++;
                insertNextSong();
            }
            activeSong = curIdx < songs.size() ? songs.get(curIdx) : null;
            // Close the enqueue/EOF race: a later click starts playback through playNext instead.
            if (curIdx >= songs.size() && !isHeartbeat()) acceptingNext = false;
        }

        private void insertNextSong() {
            curIdx = Math.max(0, Math.min(curIdx, songs.size()));
            Music next = nextSongs.removeFirst();
            songs.subList(curIdx, songs.size()).removeIf(song -> song.getId() == next.getId());
            songs.add(curIdx, next);
            queuedTrack = true;
            activeSong = next;
        }

        /** Called only between tracks; never closes, seeks, or resumes the current audio player. */
        synchronized boolean adoptHeartbeat(int completedIndex) {
            HeartbeatSession session = pendingHeartbeat;
            pendingHeartbeat = null;
            if (!isCurrentHeartbeat(session) || doBreak || isInterrupted()) return false;

            int historyStart = Math.max(0, completedIndex - 99);
            List<Music> history = new ArrayList<>(songs.subList(historyStart, completedIndex + 1));
            Set<Long> heard = new HashSet<>();
            history.forEach(song -> heard.add(song.getId()));
            List<Music> upcoming = session.songs.stream().filter(song -> heard.add(song.getId())).toList();
            session.songs.clear();
            session.songs.addAll(history);
            session.songs.addAll(upcoming);

            // Preserve a manual previous/next action, otherwise advance to the first recommendation.
            int nextIndex = dontAdd ? Math.max(0, Math.min(curIdx - historyStart, history.size())) : history.size();
            heartbeat = session;
            songs = session.songs;
            playList = songs;
            curIdx = nextIndex;
            dontAdd = false;
            lastMode = PlayMode.Heartbeat;
            return true;
        }

        private void retire() {
            cancelAutoMix(STOPPED);
            if (playThread == this && player != null) {
                player.close();
                setDownloading(false);
                playing.set(false);
                refreshAutoMixDebug();
            }
            final HeartbeatSession pending;
            synchronized (this) {
                acceptingHeartbeat = false;
                acceptingNext = false;
                pending = pendingHeartbeat;
                pendingHeartbeat = null;
            }
            if (!isCurrentHeartbeat(pending)) return;
            // The request may have arrived just as this thread exhausted its old queue.
            // Start only after releasing the thread monitor so joining it cannot deadlock.
            MultiThreadingUtil.runAsync(() -> {
                synchronized (playbackLock) {
                    if (playThread == this && isCurrentHeartbeat(pending) && !doBreak) {
                        playInternal(pending.songs, 0);
                    }
                }
            });
        }

        private void runPlaylist() {
            curIdx = startIdx;

            int failedSongs = 0;
            while (!doBreak && !isInterrupted()) {
                if (playListChanged()) {
                    break;
                }
                if (!ensureNextHeartbeatSong() || !shouldContinuePlayback()) break;

                int songIndex = curIdx;
                Music currentSong = playList.get(songIndex);
                synchronized (this) { activeSong = currentSong; }
                prepareForPlayback();
                
                boolean started;
                try {
                    started = playSong(currentSong);
                } catch (RuntimeException e) {
                    if (!isHeartbeat() && !queuedTrack && !hasQueuedNext()) throw e;
                    started = false;
                }
                if (!started) {
                    synchronized (this) {
                        if (doBreak || isInterrupted()) break;
                        if (dontAdd) {
                            advanceAfterTrack(songIndex);
                            continue;
                        }
                        if (queuedTrack || hasQueuedNext()) {
                            skipUnavailableTrack(songIndex);
                            continue;
                        }
                        if (isHeartbeat()) {
                            curIdx++;
                            if (++failedSongs < 5) continue;
                            heartbeat.error = I18n.text("heartbeat.unplayable");
                        }
                        acceptingNext = false;
                    }
                    break;
                }
                failedSongs = 0;
                if (isHeartbeat() && songs.size() - curIdx <= 3) {
                    try {
                        heartbeat.requestMore(songs.getLast().getId());
                    } catch (RuntimeException e) {
                        heartbeat.error = I18n.text("heartbeat.retry");
                    }
                }
                
                preloadNextCover();
                waitForPlaybackCompletion();
                handlePlaybackCompletion();
                advanceAfterTrack(curIdx);
            }
        }

        private boolean isHeartbeat() {
            return isCurrentHeartbeat(heartbeat);
        }

        synchronized void skipUnavailableTrack(int songIndex) {
            // An unavailable inserted song must not stop the original queue or repeat forever.
            curIdx = songIndex + 1;
            dontAdd = true;
            advanceAfterTrack(songIndex);
        }

        private boolean ensureNextHeartbeatSong() {
            if (curIdx < songs.size() || !isHeartbeat()) return true;
            synchronized (this) {
                if (!nextSongs.isEmpty()) {
                    insertNextSong();
                    return true;
                }
            }
            try {
                List<Music> batch = heartbeat.requestMore(songs.getLast().getId()).get();
                if (!isHeartbeat() || doBreak || isInterrupted()) return false;
                List<Music> fresh = heartbeat.freshSongs(batch);
                if (fresh.isEmpty()) throw new IllegalStateException(I18n.text("heartbeat.noNew"));
                songs.addAll(fresh);
                // Preserve a useful previous-track history without growing forever.
                int trim = Math.max(0, curIdx - 100);
                if (trim > 0) {
                    songs.subList(0, trim).clear();
                    curIdx -= trim;
                }
                return true;
            } catch (InterruptedException e) {
                interrupt();
                return false;
            } catch (Exception e) {
                heartbeat.error = heartbeatFailureMessage(e) + I18n.text("heartbeat.nextHint");
                synchronized (this) {
                    if (!nextSongs.isEmpty()) {
                        insertNextSong();
                        return true;
                    }
                    acceptingNext = false;
                }
                return false;
            } finally {
                heartbeat.consumed();
            }
        }
        
        private boolean shouldContinuePlayback() {
            return curIdx < playList.size() && !doBreak && !this.isInterrupted();
        }
        
        private boolean playListChanged() {
            return playList != songs;
        }
        
        private void prepareForPlayback() {
            stopPreviousPlayer();
            loadMusicCover(playList.get(curIdx));
        }
        
        private boolean playSong(Music song) {
            currentlyPlaying = song;
            loadLyric(song);
            
            Tuple<String, String> playUrl = song.getPlayUrl();
            
            if (playUrl == null) {
                handleUnplayableSong(song);
                return false;
            }
            
            return initializeAndPlaySong(song, playUrl);
        }
        
        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl) {
            setDownloading(false);
            int songIndex = curIdx;
            AudioPlayer prepared = null;
            try {
                if (dontAdd || doBreak || isInterrupted()) return false;
                String type = playUrl.getB().toLowerCase(Locale.ROOT);
                if (!SampleLoader.isSupportedFileType(type)) throw new IOException("Unsupported audio format: " + type);
                Quality selectedQuality = quality;
                var cache = AppPaths.musicCacheDir().resolve(song.getId() + "_" + selectedQuality.getQuality() + "." + type);
                if (Files.isRegularFile(cache) && Files.size(cache) > 0) {
                    prepared = new AudioPlayer(cache.toFile());
                } else {
                    setDownloading(true);
                    setDownloadProgress(0);
                    setDownloadSpeed("0 B/s");
                    StreamingAudioSample stream = StreamingAudioSample.open(playUrl.getA(), cache, song.getDuration(),
                            () -> doBreak || isInterrupted() || dontAdd || curIdx != songIndex);
                    try { prepared = new AudioPlayer(stream); }
                    catch (RuntimeException | LinkageError e) { stream.cleanUp(); throw e; }
                }
                synchronized (this) {
                    if (doBreak || isInterrupted() || dontAdd || curIdx != songIndex) {
                        prepared.close();
                        setDownloading(false);
                        return false;
                    }
                    prepared.setVolume(DesktopAppState.preferences().volume().getValue().floatValue());
                    player = prepared;
                    notifySongStart(song);
                    startPlayback();
                }
            } catch (Exception e) {
                if (prepared != null) prepared.close();
                setDownloading(false);
                if (e instanceof InterruptedException) interrupt();
                if (!doBreak && !isInterrupted() && !dontAdd) handlePlayerInitializationError(e);
                return false;
            }
            return true;
        }
        
        private void waitForPlaybackCompletion() {
            long nextDownloadUpdate = 0;
            while (playing.get()) {
                if (this.isInterrupted() || doBreak) {
                    break;
                }
                
                try {
                    if (System.nanoTime() >= nextDownloadUpdate) {
                        setDownloading(player.isDownloading());
                        setDownloadProgress(player.downloadProgress());
                        setDownloadSpeed(DesktopCacheManager.formatSize((long) player.downloadBytesPerSecond()) + "/s");
                        nextDownloadUpdate = System.nanoTime() + 500_000_000L;
                    }
                    IOException streamingFailure = player.streamingFailure();
                    if (streamingFailure != null) {
                        handlePlayerInitializationError(streamingFailure);
                        api.printMessage(DesktopChatColor.RED + I18n.text("player.streamFailed"));
                        playing.set(false);
                        break;
                    }
                    tickAutoMix();
                    if (transition == null && player.isFinished()) playing.set(false);
                } catch (RuntimeException failure) {
                    cancelAutoMix(TRANSITION_FAILED);
                    debugState(TRANSITION_FAILED, failure.getClass().getSimpleName());
                    publishAutoMixDebug();
                    System.err.println("[AutoMix] Transition cancelled: " + failure.getClass().getSimpleName());
                }
                CloudMusic.updateCurrentLyric(player.getCurrentTimeMillis());
                
                try {
                    Thread.sleep(10L);
                } catch (Exception e) {
                    interrupt();
                    break;
                }
            }
        }
        
        private void handlePlaybackCompletion() {
            cancelAutoMix();
            if (!dontAdd && playedFrom != null && currentlyPlaying != null) {
                currentlyPlaying.updPlayCount(playedFrom, player.getCurrentTimeSeconds());
            }
            
            player.close();
            setDownloading(false);
        }
        
        private void stopPreviousPlayer() {
            if (player != null && !player.isFinished()) {
                player.close();
                sleep(250);
            }
        }
        
        private void handleUnplayableSong(Music song) {
            api.printMessage(DesktopChatColor.RED + I18n.text("player.unavailable", song.getName(), song.getArtistsName()));

            System.err.printf("%s无法播放: %s - %s, 可能因为该歌曲没有版权\n", DesktopChatColor.RED, song.getName(), song.getArtistsName());
        }
        
        private void handlePlayerInitializationError(Exception e) {
            e.printStackTrace();
            System.err.printf(DesktopChatColor.RED + "[NCM] Failed to initiate audio player! Error: %s\n", e.getMessage());
        }
        
        private void notifySongStart(Music song) {
            MusicToast.pushMusicToast(song.getArtistsName() + " - " + song.getName());
            System.out.printf("[NCM] Now playing: %s, id %d\n", song.getName(), song.getId());
        }
        
        private void startPlayback() {
            AudioPlayer source = player;
            playing.set(true);
            source.setAfterPlayed(() -> {
                // A may reach EOF while B is already audible; only the active solo voice ends a track.
                // Never take the playlist monitor on JSyn's real-time thread.
                if (playThread == this && CloudMusic.player == source && transition == null) playing.set(false);
            });
            try {
                source.play();
            } catch (RuntimeException failure) {
                playing.set(false);
                throw failure;
            }
        }

        private void preloadNextCover() {
            if (curIdx + 1 < playList.size()) {
                loadMusicCover(playList.get(curIdx + 1));
            }
        }
        
        private void updateCurrentIndex() {
            updateCurIdx();
        }

        private File getPreloadedMusicFile(Tuple<String, String> url, Music song, Quality selectedQuality,
                                          Preparation request) throws IOException {
            String type = url.getB().toLowerCase(Locale.ROOT);
            if (!SampleLoader.isSupportedFileType(type)) throw new IOException("Unsupported audio format");
            var directory = AppPaths.musicCacheDir();
            Files.createDirectories(directory);
            var destination = directory.resolve(song.getId() + "_" + selectedQuality.getQuality() + "." + type);
            if (Files.isRegularFile(destination) && Files.size(destination) > 0) return destination.toFile();
            var temporary = Files.createTempFile(directory, "automix-", ".part");
            try {
                try (InputStream input = HttpUtils.get(url.getA(), null);
                     OutputStream output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (request.cancelled) throw new java.io.InterruptedIOException("AutoMix cancelled");
                        output.write(buffer, 0, count);
                    }
                }
                if (request.cancelled || Files.size(temporary) == 0) throw new IOException("Empty/cancelled preload");
                try { Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return destination.toFile();
            } finally { Files.deleteIfExists(temporary); }
        }

        private void loadMusicCover(Music song) {
            CloudMusic.loadMusicCover(song);
        }

        PlayMode lastMode = playMode;

        private void updateCurIdx() {

            if (lastMode != playMode) {

                if (playMode == PlayMode.Random) {
                    Collections.shuffle(songs);
                    playList = songs;
                }

                lastMode = playMode;
            }

            if (playMode == PlayMode.LoopSingle) {
                if (dontAdd) {
                    dontAdd = false;
                }

                if (curIdx < 0) {
                    curIdx = 0;
                }
            } else if (playMode == PlayMode.LoopInList || playMode == PlayMode.Random) {
                if (!dontAdd) {
                    curIdx++;
                } else {
                    dontAdd = false;
                }

                if (curIdx == playList.size()) {
                    curIdx = 0;
                }
            } else {
                if (!dontAdd) {
                    curIdx++;
                } else {
                    dontAdd = false;
                }
            }
        }

        private void sleep(int millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void loadMusicCover(Music music) {
        loadMusicCover(music, false);
    }

    public static void loadMusicCover(Music music, boolean forceReload) {
        if (music.getCoverUrl(320).isEmpty()) return;
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }

        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }
    
    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation, boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }
    
    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(music.getCoverUrl(320), 5);
                byte[] imageData = IOUtils.toByteArray(coverStream);
                
                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));
                
                if (coverImage != null) {
                    loadCoverTextures(music.getId(), coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
    
    private static void loadCoverTextures(long musicId, BufferedImage coverImage, Location musicCover, Location musicCoverBlur) {
        HudArtworkCache.put(musicId, HudArtworkCache.ArtworkType.COVER, coverImage);
        HudArtworkCache.put(musicId, HudArtworkCache.ArtworkType.SMALL, scaleCover(coverImage, 128));
        Textures.loadTexture(musicCover, coverImage);
        
        MultiThreadingUtil.runAsync(() -> {
            BufferedImage inputImage = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            inputImage.setRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), 
                             coverImage.getRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), null, 0, coverImage.getWidth()), 
                             0, coverImage.getWidth());

            // 创建高斯模糊之后的歌曲封面, 目前仅在播放器的歌词界面使用
            BufferedImage blurredImage = gaussianBlur(inputImage, 31);
            HudArtworkCache.put(musicId, HudArtworkCache.ArtworkType.BLURRED, blurredImage);
            Textures.loadTexture(musicCoverBlur, blurredImage);
        });
    }

    private static BufferedImage scaleCover(BufferedImage source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }
    
    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            InputStream smallCoverStream = HttpUtils.downloadStream(music.getCoverUrl(128), 5);
            BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
            HudArtworkCache.put(music.getId(), HudArtworkCache.ArtworkType.SMALL, smallCoverImage);
            Textures.loadTexture(musicCoverSmall, smallCoverImage);
        });
    }

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));

    public static BufferedImage gaussianBlur(BufferedImage imgIn, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp op = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);

        BufferedImage filtered = op.filter(imgIn, null);

        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        graphics.setRenderingHints(map);
        graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2, filtered.getHeight() + blur * 2, null);

        return output;
    }

    @SneakyThrows
    private static File convertFlacToWav(File flacIn, File destFile) {

        @Cleanup
        FileOutputStream os = new FileOutputStream(destFile);

        WavWriter ww = new WavWriter(os);

        FLACDecoder fd = new FLACDecoder(Files.newInputStream(flacIn.toPath()));
        fd.addPCMProcessor(new PCMProcessor() {
            @Override
            public void processStreamInfo(StreamInfo info) {
                try {
                    ww.writeHeader(info);
                } catch (IOException e) {
                    e.printStackTrace();
                    setDownloading(false);
                    destFile.delete();
                }
            }

            @Override
            public void processPCM(ByteData pcm) {
                try {
                    ww.writePCM(pcm);
                } catch (IOException e) {
                    e.printStackTrace();
                    setDownloading(false);
                    destFile.delete();
                }
            }
        });
        fd.decode();

        return destFile;
    }

    @SneakyThrows
    private static File convertMp3ToWav(File mp3In, File destFile) {

        Converter converter = new Converter();
        converter.convert(Files.newInputStream(mp3In.toPath()), destFile.getAbsolutePath(), null, null);

        return destFile;
    }

    @SneakyThrows
    public static void writeTo(InputStream src, OutputStream dest) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = src.read(buffer)) != -1) {
            dest.write(buffer, 0, len);
        }
        dest.flush();
    }

    private static final LyricLoader LYRIC_LOADER = new LyricLoader(
            new BestLyricService(new OnlineLyricProviders()), DesktopAppState::runOnMainThread);
    private static Music lyricOwner;

    public static void reloadLyrics() {
        Music music = currentlyPlaying;
        if (music != null) loadLyric(music);
    }

    public static void loadLyric(Music music) {
        if (music == null) return;
        // Requests and UI publication share the render thread. Playback only queues work, so
        // acquiring its transition lock below cannot invert the loader's generation lock.
        DesktopAppState.runOnMainThread(() -> {
            synchronized (playThread == null ? playbackLock : playThread) {
                if (currentlyPlaying != music) return;
                if (lyricOwner != music) {
                    lyricOwner = music;
                    lyrics.clear();
                    currentLyric = null;
                    resetLyricFlags();
                    haveNoWords = true;
                }
            }
            var preferences = DesktopAppState.preferences();
            LYRIC_LOADER.load(LyricSong.fromMusic(music), preferences.autoUseBestLyric().getValue(),
                    LyricSource.fromId(preferences.preferredLyricSource().getValue()),
                    () -> currentlyPlaying == music,
                    result -> {
                        synchronized (playThread == null ? playbackLock : playThread) {
                            if (currentlyPlaying != music) return;
                            initLyrics(new JsonObject(), music, result.document().toLines());
                            AudioPlayer active = player;
                            if (active != null) setLyricsProgress(active.getCurrentTimeMillis());
                        }
                    });
        });
    }

    public static String qrCodeLogin() {
        String key = CloudMusic.qrKey();

        QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);

        while (true) {

            if (Thread.currentThread().isInterrupted()) {
                return "";
            }

//            HttpClient.HttpResult result = qrCheck(key);
            JsonObject json = CloudMusicApi.loginQrCheck(key).toJsonObject();

            int code = json.get("code").getAsInt();
            if (code == 800) {
                key = CloudMusic.qrKey();

                QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);
            }

            if (code == 802) {
                if (json.has("nickname")) {
                    NCMScreen.getInstance().loginRenderer.tempUsername = json.get("nickname").getAsString();
                }

                if (json.has("avatarUrl")) {
                    String url = json.get("avatarUrl").getAsString();

                    if (!NCMScreen.getInstance().loginRenderer.avatarLoaded) {
                        NCMScreen.getInstance().loginRenderer.avatarLoaded = true;
                        MultiThreadingUtil.runAsync(() -> {
                            try (InputStream is = HttpUtils.get(url, null)) {
                                BufferedImage img = DynamicTexture.readImage(is);

                                Textures.loadTextureAsyncly(NCMScreen.getInstance().loginRenderer.tempAvatar, img);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }

            if (code == 803) {

                String cookie = json.get("cookie").getAsString();

                String[] split = cookie.split(";");
                StringBuilder sb = new StringBuilder();
                for (String s : split) {
                    if (s.contains("MUSIC_U") || s.contains("__csrf")) {
                        sb.append(s).append("; ");
                    }
                }

                return sb.substring(0, sb.length() - 2);
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static User getUserProfile() {
        JsonObject jsonObject = CloudMusicApi.loginStatus().toJsonObject();

        JsonObject d = jsonObject.getAsJsonObject("data");

        if ((!d.has("account") || d.get("account") instanceof JsonNull) || (!d.has("profile") || d.get("profile") instanceof JsonNull)) {
            OptionsUtil.setCookie("");
            return null;
        }

        JsonObject profile = d.getAsJsonObject("profile");

        return JsonUtils.parse(profile, User.class);
    }


    public static List<Music> search(String keyWord) {
        List<Music> searchResults = new ArrayList<>();
        JsonObject searchResponse = CloudMusicApi.cloudSearch(keyWord, CloudMusicApi.SearchType.Single).toJsonObject();
        
        JsonArray songs = extractSongsFromResponse(searchResponse);
        
        if (songs != null) {
            for (JsonElement song : songs) {
                searchResults.add(JsonUtils.parse(song.getAsJsonObject(), Music.class));
            }
        }
        
        return searchResults;
    }
    
    private static JsonArray extractSongsFromResponse(JsonObject searchResponse) {
        try {
            JsonObject result = searchResponse.getAsJsonObject("result");
            return result != null ? result.getAsJsonArray("songs") : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse search response", e);
        }
    }

    public static List<Long> likeList() {
        List<Long> list = new ArrayList<>();

        JsonObject json = CloudMusicApi.likeList(profile.getId()).toJsonObject();

        JsonArray ids = json.getAsJsonArray("ids");
        for (JsonElement id : ids) {
            list.add(id.getAsLong());
        }

        return list;
    }

    public static String qrKey() {
        JsonObject json = CloudMusicApi.loginQrKey().toJsonObject();
        return json.getAsJsonObject("data").get("unikey").getAsString();
    }

}
