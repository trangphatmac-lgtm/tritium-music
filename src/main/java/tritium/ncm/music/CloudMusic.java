package tritium.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
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
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.GaussianKernel;
import tritium.rendering.MusicToast;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.Textures;
import tritium.screens.ncm.LyricLine;
import tritium.screens.ncm.LyricParser;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.Location;
import tritium.utils.Tuple;
import tritium.utils.json.JsonUtils;
import tritium.utils.network.HttpUtils;
import tritium.utils.other.StringUtils;
import tritium.utils.other.WrappedInputStream;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 9:34 AM
 */
public class CloudMusic implements SharedConstants {

    @Getter
    private static final Map<String, String> headers = new HashMap<>();
    public static volatile boolean loadingSession = false;
    public static volatile String sessionStatusText = "";
    public static volatile long loadingSessionStartedAt = 0L;
    public static AudioPlayer player;
    // 当前播放列表
    public static volatile List<Music> playList = new ArrayList<>();
    public static volatile int curIdx = 0;
    public static volatile Music currentlyPlaying;
    public static Thread playThread;

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
            return CompletableFuture.completedFuture("未在播放");
        }
        long songId = song.getId();
        synchronized (favoritesLock) {
            user = profile;
            if (user == null) {
                return CompletableFuture.completedFuture("请先登录后收藏歌曲");
            }
            if (likeList == null) {
                return CompletableFuture.completedFuture("收藏列表尚未加载，请稍后重试");
            }
            if (pendingFavorites.containsKey(songId)) {
                return CompletableFuture.completedFuture("正在更新收藏…");
            }
            liked = !likeList.contains(songId);
            pendingFavorites.put(songId, token);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        Runnable update = () -> {
            String message;
            try {
                if (profile != user) {
                    message = "登录状态已变化，请重试";
                } else {
                    RequestUtil.RequestAnswer answer = request.apply(songId, liked);
                    JsonObject body = answer.toJsonObject();
                    if (answer.getStatus() != 200 || body == null || !body.has("code")
                            || body.get("code").getAsInt() != 200) {
                        message = "收藏更新失败，请重试";
                    } else {
                        synchronized (favoritesLock) {
                            if (profile != user || likeList == null) {
                                message = "登录状态已变化，请重试";
                            } else {
                                List<Long> updated = new ArrayList<>(likeList);
                                updated.removeIf(id -> id == songId);
                                if (liked) updated.add(songId);
                                likeList = List.copyOf(updated);
                                if (playLists != null) {
                                    playLists.stream().filter(CloudMusic::isLikedPlaylist)
                                            .forEach(PlayList::invalidateMusics);
                                }
                                message = liked ? "已收藏" : "已取消收藏";
                            }
                        }
                    }
                }
            } catch (Exception e) {
                message = "收藏更新失败，请重试";
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
            result.complete("收藏更新失败，请重试");
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
                return CompletableFuture.completedFuture("已退出心动模式");
            }
            if (profile == null) return CompletableFuture.completedFuture("请先登录后开启心动模式");
            if (likeList == null || playLists == null)
                return CompletableFuture.completedFuture("喜欢列表尚未加载，请稍后重试");
            if (likeList.isEmpty()) return CompletableFuture.completedFuture("先收藏一些喜欢的歌曲，再开启心动模式");
            PlayList source = playLists.stream().filter(CloudMusic::isLikedPlaylist).findFirst().orElse(null);
            if (source == null) return CompletableFuture.completedFuture("未找到我喜欢的音乐，请刷新登录状态");
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
                        result.complete("心动模式请求已取消");
                        return;
                    }
                    if (profile != session.user || failure != null) {
                        exitHeartbeat();
                        result.complete(profile != session.user ? "登录状态已变化，请重试"
                                : heartbeatFailureMessage(failure));
                        return;
                    }
                    session.consumed();
                    session.songs.addAll(songs);
                    modeBeforeHeartbeat = playMode;
                    playMode = PlayMode.Heartbeat;
                    heartbeatLoading = false;
                    try {
                        startPlayback.accept(session);
                        playedFrom = session.source;
                        result.complete("已开启心动模式 · 红心歌曲与相似推荐交替播放");
                    } catch (RuntimeException e) {
                        exitHeartbeat();
                        result.complete("心动模式启动失败，请重试");
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (playbackLock) {
                if (heartbeatSession == session) exitHeartbeat();
            }
            result.complete("心动推荐加载失败，请重试");
        }
        return result;
    }

    private static String heartbeatFailureMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure instanceof IllegalStateException ? failure.getMessage() : "心动推荐加载失败，请检查网络后重试";
    }

    public static void exitHeartbeat() {
        synchronized (playbackLock) {
            heartbeatSession = null;
            heartbeatLoading = false;
            if (playMode == PlayMode.Heartbeat) playMode = modeBeforeHeartbeat;
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
            playMode = next;
            return CompletableFuture.completedFuture("已切换为" + next.getDisplayName());
        }
    }

    public static Quality quality = Quality.STANDARD;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static LyricLine currentLyric = null;
    public static boolean hasTransLyrics = false;
    public static boolean hasRomanization = false;
    public static boolean haveNoWords = false;

    public static final File COOKIE_FILE = AppPaths.cookieFile().toFile();

    public static void initLyrics(JsonObject rawLyricData, Music music, List<LyricLine> parsedLyrics) {
        resetLyricFlags();
        detectTranslations(rawLyricData);
        
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
            lyrics.add(new LyricLine(0L, "暂无歌词"));
        }
    }

    private static void detectTranslations(JsonObject lyric) {
        if (hasLyricsType(lyric, "tlyric") || hasLyricsType(lyric, "ytlrc")) hasTransLyrics = true;
        if (hasLyricsType(lyric, "romalrc") || hasLyricsType(lyric, "yromalrc")) hasRomanization = true;
    }

    private static boolean hasLyricsType(JsonObject lyric, String type) {
        if (lyric.has(type) && lyric.get(type).isJsonObject()) {
            JsonObject lyricTypeObj = lyric.get(type).getAsJsonObject();
            return lyricTypeObj.has("lyric") && !lyricTypeObj.get("lyric").getAsString().isEmpty();
        }
        return false;
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
        sessionStatusText = "正在验证本地 Cookie";
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
        sessionStatusText = "正在加载用户信息";
        // 获取用户信息
        profile = getUserProfile();

        if (profile == null) {
            sessionStatusText = "Cookie 无效或登录已过期";
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        sessionStatusText = "正在加载歌单";
        CloudMusic.playLists = loadUserPlaylists();
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        sessionStatusText = "正在加载喜欢列表";
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
        Random("F", "随机播放"),
        LoopInList("I", "列表循环"),
        LoopSingle("L", "单曲循环"),
        Sequential("G", "顺序播放"),
        Heartbeat("D", "心动模式");

        private final String icon;
        private final String displayName;

        PlayMode(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
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
        updatePlayCountIfNeeded();
        
        if (!canPlayPrevious()) {
            return;
        }
        
        if (player != null && !playList.isEmpty()) {
            prepareForTrackChange();
            curIdx--;
            stopCurrentPlayback();
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
        if (!canPlayNext()) {
            return;
        }
        
        if (player != null && !playList.isEmpty()) {
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }
    }
    
    private static boolean canPlayNext() {
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
    
    private static void stopCurrentPlayback() {
        player.close();
        playing.set(false);
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

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

        public PlayThread(List<Music> songs, int startIdx) {
            this.songs = songs;
            this.setName("Play Thread");
            this.startIdx = startIdx;
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
            pendingHeartbeat = session;
            return true;
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
            final HeartbeatSession pending;
            synchronized (this) {
                acceptingHeartbeat = false;
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
                prepareForPlayback();
                
                boolean started;
                try {
                    started = playSong(currentSong);
                } catch (RuntimeException e) {
                    if (!isHeartbeat()) throw e;
                    started = false;
                }
                if (!started) {
                    if (isHeartbeat()) {
                        curIdx++;
                        if (++failedSongs < 5) continue;
                        heartbeat.error = "连续多首推荐无法播放，点击下一首重试";
                    }
                    break;
                }
                failedSongs = 0;
                if (isHeartbeat() && songs.size() - curIdx <= 3) {
                    try {
                        heartbeat.requestMore(songs.getLast().getId());
                    } catch (RuntimeException e) {
                        heartbeat.error = "心动推荐加载失败，点击下一首重试";
                    }
                }
                
                preloadNextCover();
                waitForPlaybackCompletion();
                handlePlaybackCompletion();
                if (!adoptHeartbeat(songIndex)) updateCurrentIndex();
            }
        }

        private boolean isHeartbeat() {
            return isCurrentHeartbeat(heartbeat);
        }

        private boolean ensureNextHeartbeatSong() {
            if (curIdx < songs.size() || !isHeartbeat()) return true;
            try {
                List<Music> batch = heartbeat.requestMore(songs.getLast().getId()).get();
                if (!isHeartbeat() || doBreak || isInterrupted()) return false;
                List<Music> fresh = heartbeat.freshSongs(batch);
                if (fresh.isEmpty()) throw new IllegalStateException("暂无新的心动推荐");
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
                heartbeat.error = heartbeatFailureMessage(e) + " · 点击下一首重试";
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
            loadLyric(song);
            currentlyPlaying = song;
            
            Tuple<String, String> playUrl = song.getPlayUrl();
            
            if (playUrl == null) {
                handleUnplayableSong(song);
                return false;
            }
            
            return initializeAndPlaySong(song, playUrl);
        }
        
        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl) {
            setDownloading(false);
            File musicFile;
            
            try {
                musicFile = getMusicFile(playUrl, song);
                player = initializePlayer(musicFile);
            } catch (Exception e) {
                handlePlayerInitializationError(e);
                return false;
            }
            
            notifySongStart(song);
            startPlayback(song, playUrl, musicFile);
            return true;
        }
        
        private void waitForPlaybackCompletion() {
            while (playing.get()) {
                if (this.isInterrupted() || doBreak) {
                    break;
                }
                
                CloudMusic.updateCurrentLyric(player.getCurrentTimeMillis());
                
                try {
                    Thread.sleep(10L);
                } catch (Exception e) {
                    // Ignore interruption exceptions during playback
                }
            }
        }
        
        private void handlePlaybackCompletion() {
            if (!dontAdd && playedFrom != null && currentlyPlaying != null) {
                currentlyPlaying.updPlayCount(playedFrom, player.getCurrentTimeSeconds());
            }
            
            player.close();
        }
        
        private void stopPreviousPlayer() {
            if (player != null && !player.isFinished()) {
                player.close();
                sleep(250);
            }
        }
        
        private void handleUnplayableSong(Music song) {
            api.printMessage(DesktopChatColor.RED + "无法播放: " + song.getName() + " - " + song.getArtistsName());

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
        
        private void startPlayback(Music song, Tuple<String, String> playUrl, File musicFile) {
            try {
                player.play();
            } catch (ChannelMismatchException e) {
                player.player.cleanUp();
                musicFile.delete();
                player = initializePlayer(getMusicFile(playUrl, song));
            }
            playing.set(true);
            
            player.setAfterPlayed(() -> {
                this.notifyWaitLock();
                playing.set(false);
            });
        }
        
        private void preloadNextCover() {
            if (curIdx + 1 < playList.size()) {
                loadMusicCover(playList.get(curIdx + 1));
            }
        }
        
        private void updateCurrentIndex() {
            updateCurIdx();
        }

        private File getMusicFile(Tuple<String, String> playUrl, Music song) {

            String url = playUrl.getA();
            String type = playUrl.getB().toLowerCase(Locale.ROOT);

            if (SampleLoader.isSupportedFileType(type)) {
                return getCachedOrTempFile(url, type, song);
            }
            throw new IllegalArgumentException("Unsupported music format, url: " + url + ", type: " + type);
        }

        private File getCachedOrTempFile(String playUrl, String type, Music song) {
            File musicCacheDir = AppPaths.musicCacheDir().toFile();

            if (!musicCacheDir.exists()) {
                musicCacheDir.mkdirs();
            }

            String extension = "_" + quality.getQuality() + "." + type;

            File music = new File(musicCacheDir, song.getId() + extension);

            if (!music.exists()) {
                downloadMusic(playUrl, music);

                // delete all other qualities

                MultiThreadingUtil.runAsync(() -> {

                    File[] files = musicCacheDir.listFiles();
                    if (files == null) {
                        return;
                    }

                    for (File file : files) {

                        if (file.getName().startsWith(String.valueOf(song.getId())) && !file.getName().startsWith(song.getId() + "_" + quality.getQuality())) {
                            file.delete();
                        }

                    }

                });
            }

            return music;
        }

        private AudioPlayer initializePlayer(File musicFile) {
            AudioPlayer player = CloudMusic.player;
            if (player == null) {
                player = new AudioPlayer(musicFile);
//                player.volume = 0.25f;
                player.setVolume(DesktopAppState.preferences().volume().getValue().floatValue());
                CloudMusic.player = player;
            } else {
                player.setAudio(musicFile);
            }
            return player;
        }

        private void notifyWaitLock() {
            playing.set(false);
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
    private static void downloadMusic(String playUrl, File music) {

        setDownloading(true);
        setDownloadProgress(0);
        setDownloadSpeed("0 b/s");

        try {
            InputStream stream = new WrappedInputStream(HttpUtils.get(playUrl, null), new WrappedInputStream.ProgressListener() {

                tritium.utils.timing.Timer timer = new tritium.utils.timing.Timer();

                @Override
                public void onProgress(double progress) {
                    if (progress >= 1) {
                        setDownloading(false);
                    }

                    setDownloadProgress(progress);
                }

                final long kilo = 1024;
                final long mega = kilo * kilo;
                final long giga = mega * kilo;
                final long tera = giga * kilo;

                String getSize(long size) {
                    String s;
                    double kb = (double)size / kilo;
                    double mb = kb / kilo;
                    double gb = mb / kilo;
                    double tb = gb / kilo;
                    if(size < kilo) {
                        s = size + " Bytes";
                    } else if(size < mega) {
                        s =  String.format("%.2f", kb) + " KB";
                    } else if(size < giga) {
                        s = String.format("%.2f", mb) + " MB";
                    } else if(size < tera) {
                        s = String.format("%.2f", gb) + " GB";
                    } else {
                        s = String.format("%.2f", tb) + " TB";
                    }
                    return s;
                }

                int lastBytesRead = 0;

                @Override
                public void bytesRead(int bytesRead) {

                    int checkDelay = 500;

                    if (timer.isDelayed(checkDelay)) {
                        timer.reset();

                        int diff = (bytesRead - lastBytesRead) * (1000 / checkDelay);

                        setDownloadSpeed(this.getSize(diff) + "/s");

                        lastBytesRead = bytesRead;
                    }

                }
            });

            OutputStream os = Files.newOutputStream(music.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            writeTo(stream, os);

            os.close();

        } catch (Throwable t) {
            t.printStackTrace();

            setDownloading(false);

            music.delete();
        }

//        NotificationManager.show("Cloud Music", "Decoded flac => wav", Notification.Type.INFO, 2000);
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

    public static void loadLyric(Music music) {
        MultiThreadingUtil.runAsync(() -> {

            String string = CloudMusicApi.lyricNew(music.getId()).toString();

            string = string.replaceAll("[ - ]", " ");

            JsonObject json = JsonUtils.toJsonObject(string);

            List<LyricLine> parsed = LyricParser.parse(json);

            InputStream stream = CloudMusic.class.getResourceAsStream("/tritium/yrc/" + music.getId() + ".yrc");
            if (stream != null) {
                try {
                    String s = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    List<LyricLine> newLines = new ArrayList<>();
                    LyricParser.parseYrc(s, newLines);

                    for (int i = 0; i < newLines.size(); i++) {
                        LyricLine newLine = newLines.get(i);
                        LyricLine oldLine = parsed.get(i);
                        oldLine.words.clear();
                        oldLine.words.addAll(newLine.words);
                        oldLine.timestamp = newLine.timestamp;
                        oldLine.lyric = newLine.lyric;
                        oldLine.duration = newLine.duration;
                    }

                    stream.close();
                } catch (IOException ignored) {
                }
            }

            // 使用集中式歌词管理
            initLyrics(json, music, parsed);

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
