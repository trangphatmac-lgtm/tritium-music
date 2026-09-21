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
