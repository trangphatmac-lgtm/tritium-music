package tritium.screens.ncm.panels;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.User;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.utils.json.JsonUtils;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CloudStoragePanelTest {
    private final User previous = CloudMusic.profile;
    private final Music song = JsonUtils.parse("""
            {"id":123,"name":"Cloud song","ar":[{"name":"Artist"}],"al":{"name":"Album"}}
            """, Music.class);

    @AfterEach
    void restore() {
        CloudMusic.profile = previous;
    }

    @Test
    void loggedOutViewDoesNotRequestPrivateLibrary() {
        CloudMusic.profile = null;
        var panel = new CloudStoragePanel(() -> { fail("Must not query before login"); return List.of(); }, Runnable::run, Runnable::run);
        panel.onInit();
        assertTrue(labels(panel).anyMatch(s -> s.contains("请先登录")));
    }

    @Test
    void loadsOffThreadPublishesOnUiAndSharesAnAlreadyLoadedQueue() {
        CloudMusic.profile = user(1);
        Queue<Runnable> work = new ArrayDeque<>(), ui = new ArrayDeque<>();
        var panel = new CloudStoragePanel(() -> List.of(song), work::add, ui::add);
        panel.onInit();
        panel.refresh();
        assertEquals(1, work.size(), "Repeated refresh must not duplicate the request");
        work.remove().run();
        assertFalse(widgets(panel).anyMatch(MusicWidget.class::isInstance));
        ui.remove().run();
        MusicWidget row = widgets(panel).filter(MusicWidget.class::isInstance).map(MusicWidget.class::cast).findFirst().orElseThrow();
        assertEquals(List.of(song), row.playList.getMusics());
        assertTrue(row.playList.musicsLoaded);
        assertEquals(-1, row.playList.getId());
        assertTrue(labels(panel).anyMatch(s -> s.equals("1 首歌曲")));
    }

    @Test
    void failureCanBeRetriedAndRefreshDoesNotMutateOldPlaybackSnapshot() {
        CloudMusic.profile = user(1);
        AtomicInteger calls = new AtomicInteger();
        var panel = new CloudStoragePanel(() -> {
            int call = calls.getAndIncrement();
            if (call == 0) throw new IllegalStateException("音乐网盘加载失败，请点击刷新重试");
            return call == 1 ? List.of(song) : List.of();
        }, Runnable::run, Runnable::run);
        panel.onInit();
        assertTrue(labels(panel).anyMatch(s -> s.contains("加载失败")));
        panel.refresh();
        MusicWidget row = widgets(panel).filter(MusicWidget.class::isInstance).map(MusicWidget.class::cast).findFirst().orElseThrow();
        panel.refresh();
        assertEquals(List.of(song), row.playList.getMusics());
        assertTrue(labels(panel).anyMatch(s -> s.contains("暂无歌曲")));
    }

    @Test
    void accountSwitchDiscardsInFlightPrivateResults() {
        CloudMusic.profile = user(1);
        Queue<Runnable> work = new ArrayDeque<>();
        var panel = new CloudStoragePanel(() -> List.of(song), work::add, Runnable::run);
        panel.onInit();
        CloudMusic.profile = user(2);
        work.remove().run();
        assertFalse(widgets(panel).anyMatch(MusicWidget.class::isInstance));
        assertTrue(labels(panel).anyMatch(s -> s.contains("登录状态已变化")));
    }

    @Test
    void languageSwitchUpdatesExistingViewWithoutReloadingOrTranslatingSongData() {
        var preferences = tritium.desktop.DesktopAppState.preferences();
        String original = preferences.language().getValue();
        CloudMusic.profile = user(1);
        AtomicInteger calls = new AtomicInteger();
        Music namedLikeUi = JsonUtils.parse("""
                {"id":124,"name":"设置","ar":[{"name":"Artist"}],"al":{"name":"Album"}}
                """, Music.class);
        var placeholder = tritium.screens.ncm.LyricLine.placeholder();
        var userLyric = new tritium.screens.ncm.LyricLine(0, "暂无歌词");
        try {
            preferences.language().setValue("zh-CN");
            var panel = new CloudStoragePanel(() -> {
                calls.incrementAndGet();
                return List.of(namedLikeUi);
            }, Runnable::run, Runnable::run);
            panel.onInit();
            assertTrue(labels(panel).anyMatch("音乐网盘"::equals));
            preferences.language().setValue("en");
            assertTrue(labels(panel).anyMatch("Cloud library"::equals));
            assertTrue(labels(panel).anyMatch("1 song"::equals));
            assertEquals("No lyrics available", placeholder.getLyric());
            assertEquals("暂无歌词", userLyric.getLyric());
            assertEquals("Repeat all", CloudMusic.PlayMode.LoopInList.getDisplayName());
            preferences.language().setValue("zh-TW");
            assertTrue(labels(panel).anyMatch("音樂雲端硬碟"::equals));
            assertTrue(labels(panel).anyMatch("1 首歌曲"::equals));
            assertEquals("暫無歌詞", placeholder.getLyric());
            assertEquals("清單循環", CloudMusic.PlayMode.LoopInList.getDisplayName());
            assertTrue(labels(panel).anyMatch("设置"::equals));
            assertEquals(1, calls.get(), "Language switching must not reload the library");
            var failedPanel = new CloudStoragePanel(() -> {
                throw new tritium.i18n.LocalizedException("cloud.expired");
            }, Runnable::run, Runnable::run);
            failedPanel.onInit();
            assertTrue(labels(failedPanel).anyMatch(s -> s.contains("登入已失效")));
            preferences.language().setValue("en");
            assertTrue(labels(failedPanel).anyMatch(s -> s.startsWith("Session expired.")));
        } finally {
            preferences.language().setValue(original);
        }
    }

    private User user(int id) {
        return JsonUtils.parse("{\"userId\":" + id + "}", User.class);
    }

    private Stream<AbstractWidget<?>> widgets(AbstractWidget<?> widget) {
        return Stream.concat(Stream.of(widget), widget.getChildren().stream().flatMap(this::widgets));
    }

    private Stream<String> labels(AbstractWidget<?> widget) {
        return widgets(widget).filter(LabelWidget.class::isInstance).map(LabelWidget.class::cast).map(LabelWidget::getLabel);
    }
}
