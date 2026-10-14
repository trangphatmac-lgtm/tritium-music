package tritium.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.desktop.DesktopNativeLoader;
import tritium.desktop.TritiumMusicDesktopApp;
import tritium.management.FontManager;
import tritium.ncm.music.dto.Music;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.rendering.ui.widgets.RoundedRectWidget;
import tritium.screens.ncm.panels.FavoriteButton;
import tritium.screens.ncm.panels.MusicWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudMusicPlayNextTest {
    private final List<Music> oldQueue = CloudMusic.playList;
    private final Music oldSong = CloudMusic.currentlyPlaying;
    private final CloudMusic.PlayMode oldMode = CloudMusic.playMode;
    private final Thread oldThread = CloudMusic.playThread;
    private final AudioPlayer oldPlayer = CloudMusic.player;
    private final int oldIndex = CloudMusic.curIdx;
    private final boolean oldBreak = CloudMusic.doBreak, oldDontAdd = CloudMusic.dontAdd;
    private final boolean oldPlaying = CloudMusic.playing.get();
    private CloudMusic.PlayThread thread;

    @BeforeEach
    void setUp() {
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
        CloudMusic.doBreak = CloudMusic.dontAdd = false;
        CloudMusic.player = null;
        resetQueue(1, 2, 3);
    }

    private void resetQueue(long... ids) {
        CloudMusic.playList = new ArrayList<>();
        for (long id : ids) CloudMusic.playList.add(song(id));
        CloudMusic.curIdx = 0;
        CloudMusic.currentlyPlaying = CloudMusic.playList.getFirst();
        thread = new CloudMusic.PlayThread(CloudMusic.playList, 0);
        CloudMusic.playThread = thread;
    }

    @AfterEach
    void restore() {
        CloudMusic.playList = oldQueue;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.playMode = oldMode;
        CloudMusic.playThread = oldThread;
        CloudMusic.player = oldPlayer;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.doBreak = oldBreak;
        CloudMusic.dontAdd = oldDontAdd;
        CloudMusic.playing.set(oldPlaying);
    }

    @Test
    void queueingPreservesCurrentAudioPositionPauseAndSourceList() {
        var gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        for (boolean paused : List.of(false, true)) {
            AudioProbe audio = gson.fromJson("{\"paused\":" + paused + "}", AudioProbe.class);
            CloudMusic.player = audio;
            CloudMusic.playing.set(true);
            Music current = CloudMusic.currentlyPlaying;
            assertEquals("已加入下一首", CloudMusic.playNext(song(8)));
            assertSame(audio, CloudMusic.player);
            assertSame(current, CloudMusic.currentlyPlaying);
            assertEquals(42000, audio.getCurrentTimeMillis());
            assertEquals(paused, audio.isPausing());
            assertEquals(List.of(1L, 2L, 3L), ids());
            assertEquals(0, CloudMusic.curIdx);
            assertTrue(CloudMusic.playing.get());
        }
    }

    @ParameterizedTest
    @EnumSource(CloudMusic.PlayMode.class)
    void explicitNextWinsInEveryMode(CloudMusic.PlayMode mode) {
        CloudMusic.playMode = mode;
        resetQueue(1, 2, 3);
        CloudMusic.playNext(song(8));
        advance();
        assertEquals(8, selected());
        assertEquals(mode, CloudMusic.playMode);
    }

    @Test
    void latestSelectionWinsAndRepeatsAreDeduplicatedById() {
        CloudMusic.playNext(song(8));
        CloudMusic.playNext(song(9));
        CloudMusic.playNext(song(8));
        advance();
        assertEquals(8, selected());
        advance();
        assertEquals(9, selected());
        advance();
        assertEquals(2, selected());
        assertEquals(List.of(1L, 8L, 9L, 2L, 3L), ids());
    }

    @Test
    void existingUpcomingSongIsMovedAndOriginalOrderResumes() {
        CloudMusic.playNext(song(3));
        advance();
        assertEquals(3, selected());
        advance();
        assertEquals(2, selected());
        assertEquals(List.of(1L, 3L, 2L), ids());
    }

    @Test
    void singleRepeatHonorsAllQueuedRequestsThenRepeatsTheLastSelection() {
        CloudMusic.playMode = CloudMusic.PlayMode.LoopSingle;
        resetQueue(1, 2, 3);
        CloudMusic.playNext(song(8));
        CloudMusic.playNext(song(9));
        advance();
        assertEquals(9, selected());
        advance();
        assertEquals(8, selected());
        advance();
        assertEquals(8, selected());
    }

    @Test
    void switchingToRandomCannotShuffleAwayTheExplicitNextSong() {
        CloudMusic.playNext(song(8));
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        advance();
        assertEquals(8, selected());
    }

    @Test
    void manualNextAtEndOfSequentialListPlaysTheQueuedSong() {
        resetQueue(1);
        CloudMusic.player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                .fromJson("{}", CloseableAudioProbe.class);
        CloudMusic.playNext(song(8));
        CloudMusic.next();
        assertTrue(CloudMusic.dontAdd);
        thread.advanceAfterTrack(0);
        assertEquals(8, selected());
        assertFalse(CloudMusic.dontAdd);
    }

    @Test
    void previousTrackDoesNotConsumeTheNextRequest() {
        CloudMusic.curIdx = 1;
        CloudMusic.currentlyPlaying = CloudMusic.playList.get(1);
        CloudMusic.playNext(song(8));
        CloudMusic.player = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
                .fromJson("{}", CloseableAudioProbe.class);
        CloudMusic.prev();
        thread.advanceAfterTrack(1);
        assertEquals(1, selected());
        assertTrue(thread.hasQueuedNext());
        advance();
        assertEquals(8, selected());
    }

    @Test
    void unavailableInsertedTrackContinuesWithOriginalQueueEvenInSingleRepeat() {
        CloudMusic.playMode = CloudMusic.PlayMode.LoopSingle;
        resetQueue(1, 2, 3);
        CloudMusic.playNext(song(8));
        advance();
        assertEquals(8, selected());
        thread.skipUnavailableTrack(CloudMusic.curIdx);
        assertEquals(2, selected());
    }

    @Test
    void idleAndFinishedPlaybackStartTheSelectedSongAndRejectNull() {
        AtomicReference<Music> started = new AtomicReference<>();
        CloudMusic.playThread = null;
        assertEquals("请选择歌曲", CloudMusic.playNext(null, started::set));
        assertNull(started.get());
        Music selected = song(8);
        assertEquals("开始播放", CloudMusic.playNext(selected, started::set));
        assertSame(selected, started.get());
        resetQueue(1);
        advance();
        assertEquals("开始播放", CloudMusic.playNext(selected, started::set));
    }

    @Test
    void newPlaylistDoesNotInheritTheOldThreadsPendingRequests() {
        CloudMusic.playNext(song(8));
        resetQueue(4, 5);
        advance();
        assertEquals(5, selected());
    }

    @Test
    void currentlyPlayingSongIsNotQueuedAgain() {
        assertEquals("正在播放", CloudMusic.playNext(song(1)));
        assertFalse(thread.hasQueuedNext());
    }

    @Test
    void sharedSongRowButtonQueuesWithoutLoadingOrReplacingTheSourcePlaylist() {
        // A null source would fail immediately if this action accidentally invoked row playback.
        MusicWidget row = new MusicWidget(song(8), null, 0);
        IconWidget button = row.getChildren().stream()
                .filter(child -> child.getClass() == IconWidget.class).map(IconWidget.class::cast)
                .findFirst().orElseThrow();
        assertEquals("j", button.getIcon());
        assertFalse(button.onMouseClicked(0, 0, 1));
        assertFalse(thread.hasQueuedNext());
        assertTrue(button.onMouseClicked(0, 0, 0));
        assertEquals("j", button.getIcon());
        assertEquals(1, selected());
        advance();
        assertEquals(8, selected());
    }

    private void advance() { thread.advanceAfterTrack(CloudMusic.curIdx); }
    private long selected() { return CloudMusic.playList.get(CloudMusic.curIdx).getId(); }
    private List<Long> ids() { return CloudMusic.playList.stream().map(Music::getId).toList(); }
    private Music song(long id) {
        return new Gson().fromJson("{\"id\":" + id
                + ",\"name\":\"Song\",\"ar\":[],\"al\":{\"name\":\"Album\"}}", Music.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
    void renderedButtonFitsBesideMetadataAndConsumesTheClick() throws Exception {
        var preferences = DesktopAppState.preferences();
        String savedAppearance = preferences.appearance().getValue();
        String savedStyle = preferences.interfaceStyle().getValue();
        try {
            DesktopNativeLoader.configureLibraryPath();
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            RoundedRectWidget parent = new RoundedRectWidget(20, 20, 400, 30);
            Music rowSong = new Gson().fromJson("""
                    {"id":8,"name":"一首很长很长的歌曲名称，用来检查按钮不会遮挡歌曲标题",
                     "ar":[{"name":"歌手名称"}],"al":{"name":"专辑名称"},"dt":245000}
                    """, Music.class);
            MusicWidget row = new MusicWidget(rowSong, null, 0);
            parent.addChild(row);
            IconWidget button = row.getChildren().stream()
                    .filter(child -> child.getClass() == IconWidget.class).map(IconWidget.class::cast)
                    .findFirst().orElseThrow();
            FavoriteButton favorite = row.getChildren().stream()
                    .filter(FavoriteButton.class::isInstance).map(FavoriteButton.class::cast)
                    .findFirst().orElseThrow();
            for (String appearance : List.of("Dark", "Light")) {
                preferences.appearance().setValue(appearance);
                for (String style : List.of("Static", "Blur", "LiquidGlass")) {
                    preferences.interfaceStyle().setValue(style);
                    for (int width : List.of(300, 500)) {
                        parent.setWidth(width);
                        for (int frame = 0; frame < 30; frame++) {
                            DesktopAppState.pumpMainThreadTasks();
                            AppTheme.beginFrame();
                            invokeApp("prepareFrameState");
                            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
                            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), AppTheme.background());
                            UiMaterials.beginFrame(-1, -1);
                            parent.renderWidget(-1, -1, 0);
                            Thread.sleep(12);
                        }
                        assertTrue(FontManager.icon30.allGlyphs['j'].uploaded);
                        assertTrue(favorite.getX() + favorite.getWidth() < button.getX());
                        assertEquals(favorite.getY(), button.getY());
                        assertEquals(favorite.getWidth(), button.getWidth());
                        assertEquals(favorite.getHeight(), button.getHeight());
                        for (var child : row.getChildren()) {
                            if (child instanceof LabelWidget label && label.getMaxWidth() >= 0) {
                                assertTrue(label.getX() + label.getWidth() < favorite.getX());
                            }
                            if (child instanceof LabelWidget label && label.getLabel().equals("04:05")) {
                                assertTrue(button.getX() + button.getWidth() < label.getX());
                                assertEquals(button.getX() - favorite.getX() - favorite.getWidth(),
                                        label.getX() - button.getX() - button.getWidth(), .01);
                            }
                        }
                        double x = button.getX() + 10, y = button.getY() + 10;
                        parent.renderWidget(x, y, 0);
                        parent.onMouseClickReceived(x, y, 0);
                        assertTrue(thread.hasQueuedNext());
                        assertEquals(0, CloudMusic.curIdx);
                        assertEquals(List.of(1L, 2L, 3L), ids());
                        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
                        if (appearance.equals("Dark") && style.equals("Static") && width == 500) {
                            saveSongRowPreview();
                        }
                    }
                }
            }
        } finally {
            preferences.appearance().setValue(savedAppearance);
            preferences.interfaceStyle().setValue(savedStyle);
            if (Display.isCreated()) {
                UiMaterials.release();
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static void invokeApp(String name) throws Exception {
        var method = TritiumMusicDesktopApp.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }

    private static void saveSongRowPreview() throws Exception {
        float scale = RenderSystem.getUiScaleFactor();
        int w = (int) (540 * scale), h = (int) (70 * scale);
        var pixels = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, RenderSystem.getFramebufferHeight() - h, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int offset = ((h - y - 1) * w + x) * 4;
            image.setRGB(x, y, ((pixels.get(offset) & 255) << 16)
                    | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255));
        }
        javax.imageio.ImageIO.write(image, "png", new java.io.File("target/play-next-row-preview.png"));
    }

    private static class AudioProbe extends AudioPlayer {
        @Expose private boolean paused;
        private AudioProbe(java.io.File file) { super(file); }
        @Override public float getCurrentTimeMillis() { return 42000; }
        @Override public boolean isPausing() { return paused; }
        @Override public void close() { fail("Queueing must not close audio"); }
        @Override public void play() { fail("Queueing must not restart audio"); }
        @Override public void pause() { fail("Queueing must not pause audio"); }
        @Override public void unpause() { fail("Queueing must not resume audio"); }
        @Override public void setPlaybackTime(float millis) { fail("Queueing must not seek"); }
    }

    private static class CloseableAudioProbe extends AudioProbe {
        private CloseableAudioProbe(java.io.File file) { super(file); }
        @Override public void close() { }
    }
}
