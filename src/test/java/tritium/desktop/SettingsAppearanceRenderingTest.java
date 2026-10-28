package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.User;
import tritium.rendering.Framebuffer;
import tritium.rendering.Rect;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.ColorPickerWidget;
import tritium.screens.ncm.panels.SettingsPanel;
import tritium.screens.ncm.panels.PlaylistExportWidget;
import tritium.ncm.music.dto.PlayList;
import tritium.desktop.export.PlaylistExporter;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.panels.ControlsBar;
import tritium.screens.ncm.panels.PlayModeButton;
import tritium.screens.ncm.panels.FavoriteButton;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real settings tree, input dispatch, gradients and scroll clipping. */
@EnabledIfSystemProperty(named = "tritium.testRendering", matches = "true")
class SettingsAppearanceRenderingTest {
    @Test
    void rendersAndInteractsWithThemeAndSpectrumPickers() throws Exception {
        DesktopNativeLoader.configureLibraryPath();
        int originalAccent = DesktopAppState.preferences().accentColor().getValue();
        int originalSpectrum = DesktopAppState.preferences().hud().musicSpectrum().color().getValue();
        boolean originalFps = DesktopAppState.preferences().showFps().getValue();
        boolean originalAutoMixDebug = DesktopAppState.preferences().autoMixDebug().getValue();
        boolean originalBestLyrics = DesktopAppState.preferences().autoUseBestLyric().getValue();
        String originalLyricPriority = DesktopAppState.preferences().preferredLyricSource().getValue();
        boolean originalFullMode = DesktopAppState.preferences().autoMixFullMode().getValue();
        boolean originalAutoMix = DesktopAppState.preferences().autoMix().getValue();
        String originalLanguage = DesktopAppState.preferences().language().getValue();
        String originalAppearance = DesktopAppState.preferences().appearance().getValue();
        try {
            DesktopAppState.preferences().language().setValue("zh-CN");
            DesktopAppState.preferences().appearance().setValue("Dark");
            invokeApp("createDisplay");
            Display.update();
            invokeApp("initializeOpenGL");
            Framebuffer.updateMcFramebuffer();
            DesktopAppState.markMainThread();
            new FontManager().init();
            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, Math.min(720, RenderSystem.getWidth()), RenderSystem.getHeight());
            settings.onInit();
            renderStable(settings);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            AbstractWidget<?> autoMix = scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).filter(row -> {
                try {
                    var label = row.getClass().getSuperclass().getDeclaredField("label");
                    label.setAccessible(true);
                    return "settings.autoMix".equals(label.get(row));
                } catch (ReflectiveOperationException ignored) { return false; }
            }).findFirst().orElseThrow();
            scroll.actualScrollOffset = scroll.targetScrollOffset = (autoMix.getY() - scroll.getY()) - 160;
            DesktopAppState.preferences().autoMix().setValue(false);
            renderStable(settings);
            click(settings, autoMix.getX() + autoMix.getWidth() - 35, autoMix.getY() + 26);
            assertTrue(DesktopAppState.preferences().autoMix().getValue());
            renderStable(settings);
            savePreview("settings-autoMix-enabled");
            click(settings, autoMix.getX() + autoMix.getWidth() - 35, autoMix.getY() + 26);
            assertFalse(DesktopAppState.preferences().autoMix().getValue());
            AbstractWidget<?> fullMode = settingRow(scroll, "settings.autoMixFullMode");
            DesktopAppState.preferences().autoMixFullMode().setValue(false);
            click(settings, fullMode.getX() + fullMode.getWidth() - 35, fullMode.getY() + 26);
            assertTrue(DesktopAppState.preferences().autoMixFullMode().getValue());
            assertFalse(DesktopAppState.preferences().autoMix().getValue(), "Full mode does not enable AutoMix itself");
            renderStable(settings);
            savePreview("settings-autoMix-fullMode");
            click(settings, fullMode.getX() + fullMode.getWidth() - 35, fullMode.getY() + 26);
            assertFalse(DesktopAppState.preferences().autoMixFullMode().getValue());
            AbstractWidget<?> bestLyrics = settingRow(scroll, "settings.autoUseBestLyric");
            AbstractWidget<?> lyricPriority = settingRow(scroll, "settings.lyricMatchPriority");
            DesktopAppState.preferences().autoUseBestLyric().setValue(true);
            DesktopAppState.preferences().preferredLyricSource().setValue("netease");
            scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset + (bestLyrics.getY() - scroll.getY()) - 80;
            renderStable(settings);
            savePreview("settings-best-lyrics-netease");
            click(settings, bestLyrics.getX() + bestLyrics.getWidth() - 35, bestLyrics.getY() + 26);
            assertFalse(DesktopAppState.preferences().autoUseBestLyric().getValue());
            click(settings, bestLyrics.getX() + bestLyrics.getWidth() - 35, bestLyrics.getY() + 26);
            assertTrue(DesktopAppState.preferences().autoUseBestLyric().getValue());
            for (String source : new String[]{"amll", "qq", "kugou", "netease"}) {
                click(settings, lyricPriority.getX() + 20, lyricPriority.getY() + 26);
                assertEquals(source, DesktopAppState.preferences().preferredLyricSource().getValue());
            }
            settings.setWidth(Math.min(440, RenderSystem.getWidth()));
            renderStable(settings);
            savePreview("settings-best-lyrics-narrow");
            settings.setWidth(Math.min(720, RenderSystem.getWidth()));
            scroll.actualScrollOffset = scroll.targetScrollOffset = 0;
            renderStable(settings);
            AbstractWidget<?> language = settingRow(scroll, "settings.language");
            var title = (tritium.rendering.ui.widgets.LabelWidget) settings.getChildren().getFirst();
            assertEquals("设置", title.getLabel());
            savePreview("settings-locale-zh-CN");
            click(settings, language.getX() + 20, language.getY() + 20);
            renderStable(settings);
            assertEquals("zh-TW", DesktopAppState.preferences().language().getValue());
            assertEquals("設定", title.getLabel());
            savePreview("settings-locale-zh-TW");
            click(settings, language.getX() + 20, language.getY() + 20);
            settings.setWidth(Math.min(440, RenderSystem.getWidth()));
            renderStable(settings);
            assertEquals("en", DesktopAppState.preferences().language().getValue());
            assertEquals("Settings", title.getLabel());
            savePreview("settings-locale-en");
            click(settings, language.getX() + 20, language.getY() + 20);
            settings.setWidth(Math.min(720, RenderSystem.getWidth()));
            renderStable(settings);
            assertEquals("zh-CN", DesktopAppState.preferences().language().getValue());
            AbstractWidget<?> mode = settingRow(scroll, "settings.displayMode");
            assertRgb(8, 8, 30, 30, 30, 1);
            savePreview("settings-dark");
            click(settings, mode.getX() + 20, mode.getY() + 20);
            assertEquals("Light", DesktopAppState.preferences().appearance().getValue());
            Thread.sleep(90);
            render(settings);
            int transitionBackground = AppTheme.background() & 255;
            assertTrue(transitionBackground > 30 && transitionBackground < 255);
            savePreview("settings-transition");
            renderStable(settings);
            assertRgb(8, 8, 255, 255, 255, 1);
            assertEquals(0xFF000000, AppTheme.foreground());
            assertEquals(0x6B6B6B, NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            assertRgb(mode.getX() + 8, mode.getY() + 30, 245, 245, 245, 1);
            assertContainsBlackText(24, 24, 60, 30);
            savePreview("settings-light");

            ControlsBar controls = new ControlsBar();
            controls.setBounds(0, RenderSystem.getHeight() - 75, RenderSystem.getWidth(), 75);
            controls.onInit();
            for (int i = 0; i < 40; i++) {
                render(settings);
                controls.renderWidget(-1, -1, 0);
                Display.processMessages();
                Thread.sleep(10);
            }
            assertRgb(4, RenderSystem.getHeight() - 4, 255, 255, 255, 1);
            PlayModeButton mainMode = controls.getChildren().stream().filter(PlayModeButton.class::isInstance)
                    .map(PlayModeButton.class::cast).findFirst().orElseThrow();
            assertEquals(0, mainMode.getHexColor() & 0xFFFFFF);
            // Lyrics reuse these controls, but explicitly retain their original palette.
            PlayModeButton lyricMode = new PlayModeButton(32).setFollowTheme(false);
            FavoriteButton lyricFavorite = new FavoriteButton(32).setFollowTheme(false);
            lyricMode.onRender(-1, -1);
            lyricFavorite.onRender(-1, -1);
            assertEquals(0xFFFFFF, lyricMode.getHexColor() & 0xFFFFFF);
            assertEquals(0xFFFFFF, lyricFavorite.getHexColor() & 0xFFFFFF);
            savePreview("settings-light-controls");
            verifyMainWindow();

            click(settings, mode.getX() + 20, mode.getY() + 20);
            renderStable(settings);
            assertEquals("Dark", DesktopAppState.preferences().appearance().getValue());
            assertRgb(8, 8, 30, 30, 30, 1);
            AbstractWidget<?> theme = settingRow(scroll, "settings.accent");
            // Keep the expanded picker within the viewport below the new General section.
            scroll.actualScrollOffset = scroll.targetScrollOffset = theme.getY() - scroll.getY() - 92;
            renderStable(settings);
            click(settings, theme.getX() + 68, theme.getY() + 61);
            assertEquals(0xFF58A6FF, DesktopAppState.preferences().accentColor().getValue());
            click(settings, theme.getX() + theme.getWidth() - 40, theme.getY() + 20);
            renderStable(settings);
            ColorPickerWidget themePicker = (ColorPickerWidget) theme.getChildren().getFirst();
            assertFalse(themePicker.isHidden());
            // Sample the interior, away from rounded corners and the selection ring.
            assertRgb(themePicker.getX() + 8, themePicker.getY() + 30, 235, 239, 240, 12);
            assertRgb(themePicker.getX() + themePicker.getWidth() - 8, themePicker.getY() + 134, 3, 9, 17, 12);
            savePreview("settings-theme");

            // Narrow content still lays out the palette and expands the picker inside the row.
            settings.setBounds(0, 0, Math.min(440, RenderSystem.getWidth()), RenderSystem.getHeight());
            renderStable(settings);
            assertTrue(themePicker.getWidth() > 0);
            assertTrue(themePicker.getX() + themePicker.getWidth() < theme.getX() + theme.getWidth());
            savePreview("settings-narrow");

            AbstractWidget<?> spectrum = scroll.getChildren().stream()
                    .flatMap(section -> section.getChildren().stream())
                    .filter(row -> row.getChildren().stream().anyMatch(ColorPickerWidget.class::isInstance))
                    .reduce((first, second) -> second).orElseThrow();
            // Expand and scroll the spectrum section to the top of the visible viewport.
            spectrum.onMouseClicked(30, 20, 0);
            scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset + (spectrum.getY() - scroll.getY()) - 12;
            renderStable(settings);
            ColorPickerWidget spectrumPicker = (ColorPickerWidget) spectrum.getChildren().getFirst();
            assertFalse(spectrumPicker.isHidden());
            double alphaX = spectrumPicker.getX() + spectrumPicker.getWidth() / 2;
            double alphaY = spectrumPicker.getY() + 259;
            click(settings, alphaX, alphaY);
            renderStable(settings);
            assertEquals(128, DesktopAppState.preferences().hud().musicSpectrum().color().getValue() >>> 24);
            savePreview("settings-spectrum");

            // Hidden controls cannot consume a click outside the scrolling viewport.
            int before = DesktopAppState.preferences().accentColor().getValue();
            click(settings, theme.getX() + 28, theme.getY() + 61);
            assertEquals(before, DesktopAppState.preferences().accentColor().getValue());
            scroll.scrollToEnd();
            scroll.actualScrollOffset = scroll.targetScrollOffset;
            renderStable(settings);
            AbstractWidget<?> fpsToggle = settingRow(scroll, "settings.showFps");
            click(settings, fpsToggle.getX() + 20, fpsToggle.getY() + 20);
            assertTrue(DesktopAppState.preferences().showFps().getValue());
            for (int i = 0; i < 40; i++) {
                render(settings);
                invokeApp("renderFpsOverlay");
                Thread.sleep(10);
            }
            assertRgb(RenderSystem.getWidth() - 12, 12, 25, 25, 25, 4);
            savePreview("settings-fps");
            click(settings, fpsToggle.getX() + 20, fpsToggle.getY() + 20);
            assertFalse(DesktopAppState.preferences().showFps().getValue());

            AbstractWidget<?> autoMixDebug = settingRow(scroll, "settings.autoMixDebug");
            DesktopAppState.preferences().autoMixDebug().setValue(false);
            boolean mixBeforeDebug = DesktopAppState.preferences().autoMix().getValue();
            click(settings, autoMixDebug.getX() + 20, autoMixDebug.getY() + 20);
            assertTrue(DesktopAppState.preferences().autoMixDebug().getValue());
            assertEquals(mixBeforeDebug, DesktopAppState.preferences().autoMix().getValue());
            for (int i = 0; i < 40; i++) {
                render(settings);
                AutoMixDebugOverlay.render();
                Display.processMessages();
                Thread.sleep(10);
            }
            savePreview("settings-autoMix-debug-enabled");
            click(settings, autoMixDebug.getX() + 20, autoMixDebug.getY() + 20);
            assertFalse(DesktopAppState.preferences().autoMixDebug().getValue());
            var example = AutoMixDebugOverlayTest.fullExample();
            for (String locale : new String[]{"zh-CN", "zh-TW", "en"}) {
                DesktopAppState.preferences().language().setValue(locale);
                for (int i = 0; i < 40; i++) {
                    render(settings);
                    AutoMixDebugOverlay.render(example, true, RenderSystem.getWidth(), RenderSystem.getHeight());
                    Display.processMessages();
                    Thread.sleep(10);
                }
                savePreview("autoMix-debug-" + locale);
            }
            for (int i = 0; i < 40; i++) {
                render(settings);
                AutoMixDebugOverlay.render(example, true, Math.min(440, RenderSystem.getWidth()),
                        Math.min(600, RenderSystem.getHeight()));
                Display.processMessages();
                Thread.sleep(10);
            }
            savePreview("autoMix-debug-narrow");
            verifyAudioEffects();
            verifyPlaylistExport(settings, scroll);
            verifySettingsLayout();
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            DesktopAppState.preferences().accentColor().setValue(originalAccent);
            DesktopAppState.preferences().hud().musicSpectrum().color().setValue(originalSpectrum);
            DesktopAppState.preferences().showFps().setValue(originalFps);
            DesktopAppState.preferences().autoUseBestLyric().setValue(originalBestLyrics);
            DesktopAppState.preferences().preferredLyricSource().setValue(originalLyricPriority);
            DesktopAppState.preferences().autoMixFullMode().setValue(originalFullMode);
            DesktopAppState.preferences().autoMix().setValue(originalAutoMix);
            DesktopAppState.preferences().autoMixDebug().setValue(originalAutoMixDebug);
            DesktopAppState.preferences().language().setValue(originalLanguage);
            DesktopAppState.preferences().appearance().setValue(originalAppearance);
            if (Display.isCreated()) {
                FontManager.deleteLoadedTextures();
                Display.destroy();
            }
        }
    }

    private static AbstractWidget<?> settingRow(ScrollPanel scroll, String key) {
        return scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).filter(row -> {
            try {
                var label = row.getClass().getSuperclass().getDeclaredField("label");
                label.setAccessible(true);
                return key.equals(label.get(row));
            } catch (ReflectiveOperationException ignored) { return false; }
        }).findFirst().orElseThrow();
    }

    private static void verifyAudioEffects() throws Exception {
        var preferences = DesktopAppState.preferences();
        var saved = preferences.audioEffects().toJsonObject();
        String language = preferences.language().getValue();
        String layout = preferences.settingsLayout().getValue();
        try {
            preferences.language().setValue("zh-CN");
            preferences.settingsLayout().setValue("SingleColumn");
            var effects = preferences.audioEffects();
            effects.mode().setValue("Off");
            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, 720, RenderSystem.getHeight());
            settings.onInit(); renderStable(settings);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            var mode = settingRow(scroll, "settings.audioEffectMode");
            var tone = settingRow(scroll, "settings.audioEffectTone");
            assertTrue(tone.isHidden());
            scroll.actualScrollOffset = scroll.targetScrollOffset = mode.getY() - scroll.getY() - 50;
            renderStable(settings);
            click(settings, mode.getX() + 30, mode.getY() + 26);
            assertEquals("Smart", effects.mode().getValue()); assertTrue(tone.isHidden());
            renderStable(settings); savePreview("settings-audio-effects-smart");
            click(settings, mode.getX() + 30, mode.getY() + 26);
            assertEquals("Master", effects.mode().getValue()); assertFalse(tone.isHidden());
            renderStable(settings);
            click(settings, tone.getX() + 30, tone.getY() + 26);
            assertEquals("key_bright", effects.masterTone().getValue());
            for (String key : java.util.List.of("Bass", "Treble", "Vocal", "Vibe", "Live")) {
                assertThrows(java.util.NoSuchElementException.class,
                        () -> settingRow(scroll, "settings.audioEffect" + key));
            }
            for (String locale : java.util.List.of("zh-CN", "zh-TW", "en")) {
                preferences.language().setValue(locale);
                settings.setWidth(locale.equals("en") ? 440 : 720);
                renderStable(settings);
                scroll.actualScrollOffset = scroll.targetScrollOffset = scroll.actualScrollOffset + mode.getY() - scroll.getY() - 50;
                renderStable(settings); assertSettingsDoNotOverlap(scroll);
                for (var section : scroll.getChildren()) for (var row : section.getChildren()) {
                    if (row.isHidden() || !row.getClass().getSimpleName().equals("HintRow")) continue;
                    var wrapped = row.getClass().getDeclaredField("lines"); wrapped.setAccessible(true);
                    for (String line : (String[]) wrapped.get(row))
                        assertTrue(FontManager.pf12bold.getStringWidthD(line) <= row.getWidth() - 4,
                                "Hint must fit after a locale/width change: " + line);
                }
                savePreview("settings-audio-effects-master-" + locale);
            }
        } finally {
            preferences.audioEffects().loadFromJsonObject(saved);
            preferences.language().setValue(language);
            preferences.settingsLayout().setValue(layout);
        }
    }

    private static void verifySettingsLayout() throws Exception {
        var preferences = DesktopAppState.preferences();
        String originalLayout = preferences.settingsLayout().getValue();
        String originalBackground = preferences.background().mode().getValue();
        String originalAppearance = preferences.appearance().getValue();
        String originalLanguage = preferences.language().getValue();
        String originalScale = System.getProperty("tritium.uiScale");
        DisplayMode originalDisplayMode = Display.getDisplayMode();
        try {
            System.setProperty("tritium.uiScale", "1");
            Display.setDisplayMode(new DisplayMode(1100, 850));
            Display.update();
            Framebuffer.updateMcFramebuffer();
            preferences.settingsLayout().setValue("SingleColumn");
            preferences.background().mode().setValue("static");
            preferences.appearance().setValue("Dark");
            preferences.language().setValue("zh-CN");
            SettingsPanel settings = new SettingsPanel();
            settings.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
            settings.onInit();
            renderStable(settings);
            ScrollPanel scroll = (ScrollPanel) settings.getChildren().get(2);
            var layout = settingRow(scroll, "settings.settingsLayout");
            click(settings, layout.getX() + 20, layout.getY() + 26);
            renderStable(settings);
            assertEquals("TwoColumns", preferences.settingsLayout().getValue());
            var general = scroll.getChildren().get(0);
            var appearance = scroll.getChildren().get(1);
            assertEquals(general.getY(), appearance.getY());
            assertEquals(general.getX() + general.getWidth() + 24, appearance.getX());
            assertSettingsDoNotOverlap(scroll);
            savePreview("settings-two-columns-dark");

            // Exercise real input dispatch in the right column, including an expanding row.
            var mode = settingRow(scroll, "settings.displayMode");
            click(settings, mode.getX() + 20, mode.getY() + 26);
            assertEquals("Light", preferences.appearance().getValue());
            var accent = settingRow(scroll, "settings.accent");
            click(settings, accent.getX() + 20, accent.getY() + 20);
            renderStable(settings);
            assertFalse(accent.getChildren().getFirst().isHidden());
            assertSettingsDoNotOverlap(scroll);
            savePreview("settings-two-columns-light-picker");

            preferences.background().mode().setValue("picture");
            preferences.language().setValue("en");
            renderStable(settings);
            assertSettingsDoNotOverlap(scroll);
            savePreview("settings-two-columns-english");
            scroll.scrollToEndImmediately();
            renderStable(settings);
            double bottom = scroll.getChildren().stream().mapToDouble(section -> section.getRelativeY() + section.getHeight())
                    .max().orElseThrow();
            assertEquals(scroll.getHeight(), bottom, .01);
            savePreview("settings-two-columns-bottom");

            settings.setWidth(712);
            scroll.actualScrollOffset = scroll.targetScrollOffset = 0;
            renderStable(settings);
            assertEquals(320, general.getWidth());
            assertSettingsDoNotOverlap(scroll);
            savePreview("settings-two-columns-compact");
            settings.setWidth(711);
            renderStable(settings);
            assertEquals("TwoColumns", preferences.settingsLayout().getValue());
            assertTrue(scroll.getChildren().stream().allMatch(section -> section.getRelativeX() == 0));
            assertSettingsDoNotOverlap(scroll);
            scroll.actualScrollOffset = scroll.targetScrollOffset = 0;
            settings.setWidth(440);
            renderStable(settings);
            savePreview("settings-two-columns-narrow-fallback");
            settings.setWidth(1100);
            renderStable(settings);
            assertTrue(appearance.getRelativeX() > 0);
            click(settings, layout.getX() + 20, layout.getY() + 26);
            renderStable(settings);
            assertEquals("SingleColumn", preferences.settingsLayout().getValue());
            assertEquals(0, appearance.getRelativeX());
            assertSettingsDoNotOverlap(scroll);
        } finally {
            preferences.settingsLayout().setValue(originalLayout);
            preferences.background().mode().setValue(originalBackground);
            preferences.appearance().setValue(originalAppearance);
            preferences.language().setValue(originalLanguage);
            if (originalScale == null) System.clearProperty("tritium.uiScale");
            else System.setProperty("tritium.uiScale", originalScale);
            Display.setDisplayMode(originalDisplayMode);
        }
    }

    private static void assertSettingsDoNotOverlap(ScrollPanel scroll) {
        var sections = scroll.getChildren();
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            double bottom = 0;
            for (var row : section.getChildren()) {
                if (row.isHidden()) continue;
                assertEquals(section.getWidth(), row.getWidth());
                assertTrue(row.getRelativeY() >= bottom);
                bottom = row.getRelativeY() + row.getHeight();
            }
            assertEquals(section.getHeight(), bottom);
            for (int j = i + 1; j < sections.size(); j++) {
                var other = sections.get(j);
                assertTrue(section.getX() + section.getWidth() <= other.getX()
                        || other.getX() + other.getWidth() <= section.getX()
                        || section.getY() + section.getHeight() <= other.getY()
                        || other.getY() + other.getHeight() <= section.getY());
            }
        }
    }

    private static void click(SettingsPanel settings, double x, double y) throws Exception {
        render(settings, x, y);
        settings.onMouseClickReceived(x, y, 0);
        settings.onMouseReleaseReceived(x, y, 0);
        render(settings);
    }

    private static void verifyPlaylistExport(SettingsPanel settings, ScrollPanel scroll) throws Exception {
        var original = CloudMusic.playLists;
        try {
            CloudMusic.playLists = java.util.stream.IntStream.range(0, 7).mapToObj(i ->
                    new PlayList(i + 1, "测试歌单 " + (i + 1) + " · 中文与特别长的歌单名称", "", 20 + i,
                            0, null, "", i > 3, 0, 0)).toList();
            PlaylistExportWidget export = scroll.getChildren().stream().flatMap(section -> section.getChildren().stream()).filter(PlaylistExportWidget.class::isInstance)
                    .map(PlaylistExportWidget.class::cast).findFirst().orElseThrow();
            scroll.targetScrollOffset = scroll.actualScrollOffset = scroll.actualScrollOffset + (export.getY() - scroll.getY()) - 8;
            renderStable(settings);
            // Use actual input dispatch: select a playlist, move pages, then select another.
            click(settings, export.getX() + 30, export.getY() + 60);
            click(settings, export.getX() + export.getWidth() - 45, export.getY() + 194);
            click(settings, export.getX() + 30, export.getY() + 60);
            var selectedField = PlaylistExportWidget.class.getDeclaredField("selected");
            selectedField.setAccessible(true);
            assertEquals(java.util.Set.of(1L, 5L), selectedField.get(export));
            // Change format to JSON and deselect song title, retaining the other four fields.
            click(settings, export.getX() + 30, export.getY() + 289);
            click(settings, export.getX() + 30, export.getY() + 351);
            var formatField = PlaylistExportWidget.class.getDeclaredField("format");
            formatField.setAccessible(true);
            assertEquals(PlaylistExporter.Format.JSON, formatField.get(export));
            var fieldsField = PlaylistExportWidget.class.getDeclaredField("fields");
            fieldsField.setAccessible(true);
            assertFalse(((java.util.Set<?>) fieldsField.get(export)).contains(PlaylistExporter.Field.NAME));
            renderStable(settings);
            savePreview("settings-playlist-export-dark-narrow");
            DesktopAppState.preferences().appearance().setValue("Light");
            settings.setBounds(0, 0, Math.min(720, RenderSystem.getWidth()), RenderSystem.getHeight());
            renderStable(settings);
            savePreview("settings-playlist-export-light");
            scroll.targetScrollOffset += 115;
            scroll.actualScrollOffset = scroll.targetScrollOffset;
            renderStable(settings);
            savePreview("settings-playlist-export-options");
            scroll.targetScrollOffset -= 115;
            scroll.actualScrollOffset = scroll.targetScrollOffset;
            renderStable(settings);
            click(settings, export.getX() + export.getWidth() - 110, export.getY() + 25);
            assertEquals(7, ((java.util.Set<?>) selectedField.get(export)).size());
            click(settings, export.getX() + export.getWidth() - 45, export.getY() + 25);
            assertTrue(((java.util.Set<?>) selectedField.get(export)).isEmpty());
        } finally {
            CloudMusic.playLists = original;
        }
    }

    private static void verifyMainWindow() throws Exception {
        User originalProfile = CloudMusic.profile;
        NCMScreen screen = new NCMScreen();
        try {
            // Build without an account to avoid network requests in the render test.
            CloudMusic.profile = null;
            screen.initGui();
            CloudMusic.profile = new User(1, "Tritium Music", "", 0, "");
            screen.setCurrentPanel(new SettingsPanel());
            for (int i = 0; i < 45; i++) {
                DesktopAppState.pumpMainThreadTasks();
                Interpolations.calcFrameDelta();
                AppTheme.beginFrame();
                invokeApp("prepareFrameState");
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
                screen.drawScreen(-1, -1);
                Display.processMessages();
                Thread.sleep(10);
            }
            assertRgb(4, 200, 255, 255, 255, 1);
            assertRgb(RenderSystem.getWidth() - 4, RenderSystem.getHeight() - 4, 255, 255, 255, 1);
            savePreview("main-window-light");
        } finally {
            CloudMusic.profile = originalProfile;
            screen.onGuiClosed();
        }
    }

    private static void render(SettingsPanel settings) throws Exception {
        render(settings, -1, -1);
    }

    private static void renderStable(SettingsPanel settings) throws Exception {
        // Glyph generation is asynchronous, just as in the normal application loop.
        for (int i = 0; i < 40; i++) {
            render(settings);
            Display.processMessages();
            Thread.sleep(10);
        }
    }

    private static void render(SettingsPanel settings, double x, double y) throws Exception {
        DesktopAppState.pumpMainThreadTasks();
        Interpolations.calcFrameDelta();
        AppTheme.beginFrame();
        invokeApp("prepareFrameState");
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), AppTheme.background());
        settings.renderWidget(x, y, 0);
    }

    private static void assertRgb(double x, double y, int r, int g, int b, int tolerance) {
        float scale = RenderSystem.getUiScaleFactor();
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) (y * scale) - 1,
                1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(r, pixel.get(0) & 255, tolerance, "red at " + x + "," + y);
        assertEquals(g, pixel.get(1) & 255, tolerance, "green at " + x + "," + y);
        assertEquals(b, pixel.get(2) & 255, tolerance, "blue at " + x + "," + y);
    }

    private static void assertContainsBlackText(int x, int y, int width, int height) {
        float scale = RenderSystem.getUiScaleFactor();
        int w = (int) (width * scale), h = (int) (height * scale);
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels((int) (x * scale), RenderSystem.getFramebufferHeight() - (int) ((y + height) * scale),
                w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int black = 0;
        for (int i = 0; i < w * h; i++) {
            if ((pixels.get(i * 4) & 255) < 10 && (pixels.get(i * 4 + 1) & 255) < 10
                    && (pixels.get(i * 4 + 2) & 255) < 10) black++;
        }
        assertTrue(black > 20, "Expected actual black glyph pixels in the light-mode title");
    }

    private static void savePreview(String name) throws Exception {
        int width = RenderSystem.getFramebufferWidth(), height = RenderSystem.getFramebufferHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = ((height - y - 1) * width + x) * 4;
                image.setRGB(x, y, ((pixels.get(offset) & 255) << 16)
                        | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255));
            }
        }
        Path output = Path.of("target", "ui-preview", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void invokeApp(String method) throws Exception {
        var target = TritiumMusicDesktopApp.class.getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(null);
    }
}
