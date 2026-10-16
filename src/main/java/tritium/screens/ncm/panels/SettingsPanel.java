package tritium.screens.ncm.panels;

import tritium.rendering.material.UiMaterials;
import org.lwjgl.input.Mouse;
import tritium.desktop.DesktopCacheManager;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.desktop.ModePreference;
import tritium.desktop.MusicPreferences;
import tritium.desktop.NumberPreference;
import tritium.desktop.PreferenceValue;
import tritium.desktop.BackgroundPreferences;
import tritium.desktop.background.BackgroundFilePicker;
import tritium.management.FontManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.ColorPickerWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

public class SettingsPanel extends NCMPanel {
    private static final int MARGIN = 24;
    private final MusicPreferences preferences = DesktopAppState.preferences();
    private final Map<AbstractWidget<?>, BooleanSupplier> backgroundRows = new LinkedHashMap<>();

    @Override
    public void onInit() {
        this.getChildren().clear();
        backgroundRows.clear();

        LabelWidget title = new LabelWidget("设置", FontManager.pf32);
        this.addChild(title);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(MARGIN, MARGIN));

        LabelWidget subtitle = new LabelWidget("Tritium Music", FontManager.pf14bold);
        this.addChild(subtitle);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(MARGIN, title.getRelativeY() + title.getHeight() + 4));

        ScrollPanel scrollPanel = new ScrollPanel();
        this.addChild(scrollPanel);
        scrollPanel.setSpacing(8);
        scrollPanel.setBeforeRenderCallback(() -> {
            backgroundRows.forEach((row, visible) -> row.setHidden(!visible.getAsBoolean()));
            double top = subtitle.getRelativeY() + subtitle.getHeight() + 22;
            scrollPanel.setBounds(MARGIN, top, this.getWidth() - MARGIN * 2.0, this.getHeight() - top - MARGIN);
        });

        scrollPanel.addChild(new SectionHeader("外观"));
        scrollPanel.addChild(new ChoiceRow("显示模式", preferences.appearance(),
                mode -> mode.equals("Light") ? "浅色" : "深色"));
        scrollPanel.addChild(new ColorRow("主题色", preferences.accentColor(), false));

        scrollPanel.addChild(new ChoiceRow("界面风格", preferences.interfaceStyle(), mode -> switch (mode) {
            case "Blur" -> "Blur";
            case "LiquidGlass" -> "Liquid Glass";
            default -> "Static";
        }));
        scrollPanel.addChild(new BackgroundStatusRow(UiMaterials::status));

        addBackgroundSettings(scrollPanel);

        scrollPanel.addChild(new SectionHeader("播放"));
        scrollPanel.addChild(new ChoiceRow("音质", preferences.quality()));
        scrollPanel.addChild(new SliderRow("音量", preferences.volume(), value -> Math.round(value * 100) + "%"));

        scrollPanel.addChild(new SectionHeader("缓存"));
        scrollPanel.addChild(new CacheRow());

        scrollPanel.addChild(new SectionHeader("歌词"));
        scrollPanel.addChild(new ToggleRow("显示翻译", preferences.showTranslation()));
        scrollPanel.addChild(new ToggleRow("显示罗马音", preferences.showRoman()));
        scrollPanel.addChild(new SliderRow("歌词高度", preferences.lyricHeight(),
                value -> String.format(Locale.ROOT, "%.1f", value)));

        scrollPanel.addChild(new SectionHeader("系统 HUD"));
        scrollPanel.addChild(new ToggleRow("启用播放提示 HUD", preferences.musicToast()));
        scrollPanel.addChild(new ToggleRow("启用信息 HUD", preferences.hud().musicInfo().enabled()));
        scrollPanel.addChild(new ToggleRow("启用歌词 HUD", preferences.hud().musicLyrics().enabled()));
        scrollPanel.addChild(new ToggleRow("启用频谱 HUD", preferences.hud().musicSpectrum().enabled()));
        scrollPanel.addChild(new ToggleRow("编辑 HUD 布局", preferences.hud().editMode()));
        scrollPanel.addChild(new ActionRow("重置 HUD 布局", () -> {
            Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getBounds();
            preferences.hud().resetLayout(bounds);
            preferences.hud().layoutInitialized().setValue(true);
            DesktopAppState.savePreferences();
        }));

        scrollPanel.addChild(new SectionHeader("信息 HUD"));
        scrollPanel.addChild(new SliderRow("不透明度", preferences.hud().musicInfo().opacity(),
                value -> Math.round(value * 100) + "%"));
        scrollPanel.addChild(new ToggleRow("作者区域显示当前歌词", preferences.hud().musicInfo().turnComposerIntoLyric()));

        scrollPanel.addChild(new SectionHeader("歌词 HUD"));
        scrollPanel.addChild(new ChoiceRow("滚动效果", preferences.hud().musicLyrics().scrollEffect()));
        scrollPanel.addChild(new ChoiceRow("对齐方式", preferences.hud().musicLyrics().align()));
        scrollPanel.addChild(new ToggleRow("单行模式", preferences.hud().musicLyrics().singleLine()));
        scrollPanel.addChild(new ToggleRow("歌词阴影", preferences.hud().musicLyrics().shadow()));
        scrollPanel.addChild(new ToggleRow("优雅滚动", preferences.hud().musicLyrics().graceScroll()));

        scrollPanel.addChild(new SectionHeader("频谱 HUD"));
        scrollPanel.addChild(new ChoiceRow("频谱样式", preferences.hud().musicSpectrum().style()));
        scrollPanel.addChild(new ToggleRow("紧凑模式", preferences.hud().musicSpectrum().compact()));
        scrollPanel.addChild(new ToggleRow("频谱指示线", preferences.hud().musicSpectrum().indicator()));
        scrollPanel.addChild(new ToggleRow("频谱绝对音量", preferences.hud().musicSpectrum().absoluteVolume()));
        scrollPanel.addChild(new SliderRow("频谱倍率", preferences.hud().musicSpectrum().multiplier(),
                value -> String.format(Locale.ROOT, "%.1fx", value)));
        scrollPanel.addChild(new ColorRow("频谱颜色", preferences.hud().musicSpectrum().color(), true));

        scrollPanel.addChild(new SectionHeader("调试"));
        scrollPanel.addChild(new SliderRow("最大 FPS", preferences.maxFps(),
                value -> Math.round(value) >= MusicPreferences.UNLIMITED_FPS ? "无限制" : Math.round(value) + " FPS"));
        scrollPanel.addChild(new ToggleRow("显示 FPS", preferences.showFps()));
        scrollPanel.addChild(new ToggleRow("UI 边界", preferences.showWidgetBoundary()));
        scrollPanel.addChild(new ToggleRow("逐字歌词", preferences.lyricDebug()));
    }

    private void addBackgroundSettings(ScrollPanel scroll) {
        BackgroundPreferences background = preferences.background();
        BooleanSupplier picture = () -> background.mode().getValue().equals("picture");
        BooleanSupplier folder = () -> picture.getAsBoolean() && background.pictureSource().getValue().equals("folder");
        BooleanSupplier video = () -> background.mode().getValue().equals("video");
        BooleanSupplier shader = () -> background.mode().getValue().equals("shader");
        BooleanSupplier custom = () -> !background.mode().getValue().equals("static");
        scroll.addChild(new SectionHeader("主界面背景"));
        scroll.addChild(new ChoiceRow("背景模式", background.mode(), mode -> switch (mode) {
            case "picture" -> "Picture";
            case "video" -> "Video";
            case "shader" -> "Shader";
            default -> "Static";
        }));
        scroll.addChild(new BackgroundStatusRow(DesktopAppState.background()::status));
        backgroundRow(scroll, new ChoiceRow("图片来源", background.pictureSource(),
                value -> value.equals("folder") ? "文件夹轮播" : "单张图片"), picture);
        backgroundRow(scroll, new FileRow("背景图片", background.pictureFile(), false, false),
                () -> picture.getAsBoolean() && !folder.getAsBoolean());
        backgroundRow(scroll, new FileRow("轮播文件夹", background.pictureFolder(), true, false), folder);
        backgroundRow(scroll, new SliderRow("轮播间隔", background.interval(), value -> Math.round(value) + " 秒"), folder);
        backgroundRow(scroll, new ToggleRow("随机轮播", background.shuffle()), folder);
        backgroundRow(scroll, new FileRow("背景视频（静音循环）", background.videoFile(), false, true), video);
        backgroundRow(scroll, new ChoiceRow("缩放方式", background.fit(), value -> switch (value) {
            case "contain" -> "完整显示";
            case "stretch" -> "拉伸";
            default -> "填充裁切";
        }), () -> picture.getAsBoolean() || video.getAsBoolean());
        backgroundRow(scroll, new ChoiceRow("着色器主题", background.shader(), value -> switch (value) {
            case "aurora" -> "Aurora";
            case "waves" -> "Waves";
            case "nebula" -> "Nebula";
            case "liquid" -> "Liquid";
            case "mandala" -> "Mandala";
            case "torus" -> "Torus";
            case "rise" -> "Rise";
            default -> throw new IllegalStateException("Unexpected value: " + value);
        }), shader);
        backgroundRow(scroll, new SliderRow("动画速度", background.shaderSpeed(),
                value -> String.format(Locale.ROOT, "%.1fx", value)), shader);
        backgroundRow(scroll, new SliderRow("背景强度", background.strength(), value -> Math.round(value * 100) + "%"), custom);
        backgroundRow(scroll, new SliderRow("面板不透明度", background.panelOpacity(), value -> Math.round(value * 100) + "%"), custom);
        BooleanSupplier glass = () -> !preferences.interfaceStyle().getValue().equals("Static");
        backgroundRow(scroll, new SliderRow("歌曲行不透明度", preferences.songRowOpacity(),
                value -> Math.round(value * 100) + "%"), glass);
        backgroundRow(scroll, new SliderRow("歌词悬停高亮不透明度", preferences.lyricHoverOpacity(),
                value -> Math.round(value * 100) + "%"), glass);
        backgroundRow(scroll, new ActionRow("重新加载背景 / 刷新文件夹", DesktopAppState.background()::reload), custom);
    }

    private void backgroundRow(ScrollPanel scroll, AbstractWidget<?> row, BooleanSupplier visible) {
        backgroundRows.put(row, visible);
        row.setHidden(!visible.getAsBoolean());
        scroll.addChild(row);
    }

    private static String shorten(String text, double width) {
        if (FontManager.pf12bold.getStringWidthD(text) <= width) return text;
        int end = text.length();
        while (end > 0 && FontManager.pf12bold.getStringWidthD(text.substring(0, end) + "…") > width) end--;
        return text.substring(0, end) + "…";
    }

    private static final class BackgroundStatusRow extends AbstractWidget<BackgroundStatusRow> {
        private final Supplier<String> status;

        private BackgroundStatusRow(Supplier<String> status) {
            this.status = status;
            setBounds(0, 0, 100, 34);
            setClickable(false);
            setBeforeRenderCallback(() -> setBounds(getParentWidth(), 34));
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            FontManager.pf12bold.drawString(shorten(status.get(), getWidth() - 8), getX() + 2, getY() + 8,
                    reAlpha(AppTheme.foreground(), getAlpha() * .65f));
        }
    }

    private static final class FileRow extends SettingsRow<FileRow> {
        private static final double HEADER_HEIGHT = 46;
        private static final double BUTTON_WIDTH = 56;
        private static final double BUTTON_HEIGHT = 26;
        private final PreferenceValue<String> path;
        private String error = "";

        private FileRow(String label, PreferenceValue<String> path, boolean folder, boolean video) {
            super(label, 76);
            this.path = path;
            setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (x >= getWidth() - 130 && x < getWidth() - 80 && y <= 38) {
                    path.setValue("");
                    error = "";
                } else {
                    BackgroundFilePicker.choose(label, path.getValue(), folder, video, selected -> {
                        path.setValue(selected.toString());
                        error = "";
                        DesktopAppState.background().reload();
                    }, message -> error = message);
                }
                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);
            double centerY = getY() + HEADER_HEIGHT * .5;
            double buttonX = rightInset(BUTTON_WIDTH);
            UiMaterials.control(buttonX, centerY - BUTTON_HEIGHT * .5, BUTTON_WIDTH, BUTTON_HEIGHT, 5,
                    reAlpha(AppTheme.accent(), getAlpha()));
            FontManager.pf14bold.drawCenteredString(BackgroundFilePicker.isOpen() ? "选择中" : "选择",
                    buttonX + BUTTON_WIDTH * .5, centerY - FontManager.pf14bold.getFontHeight() * .5,
                    reAlpha(AppTheme.onAccent(), getAlpha()));
            FontManager.pf12bold.drawCenteredStringVertical("清除", rightInset(110), centerY,
                    reAlpha(AppTheme.foreground(), getAlpha() * .65f));
            String value = !error.isEmpty() ? error : path.getValue().isBlank() ? "尚未选择 · 点击打开文件选择器" : path.getValue();
            FontManager.pf12bold.drawString(shorten(value, getWidth() - 32), getX() + 16, getY() + 49,
                    reAlpha(AppTheme.foreground(), getAlpha() * .6f));
        }

        @Override
        protected double labelY() {
            return getY() + (HEADER_HEIGHT - FontManager.pf16bold.getFontHeight()) * .5;
        }
    }

    private static final class SectionHeader extends AbstractWidget<SectionHeader> {
        private final String title;

        private SectionHeader(String title) {
            this.title = title;
            this.setBounds(0, 0, 100, 24);
            this.setClickable(false);
            this.setBeforeRenderCallback(() -> this.setBounds(this.getParentWidth(), 24));
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            FontManager.pf14bold.drawString(title, this.getX() + 2, this.getY() + 6,
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha() * .48f));
        }
    }

    private abstract static class SettingsRow<SELF extends SettingsRow<SELF>> extends AbstractWidget<SELF> {
        private final String label;
        private float hoverAnimation;

        private SettingsRow(String label, double height) {
            this.label = label;
            this.setBounds(0, 0, 100, height);
            this.setShouldOverrideMouseCursor(true);
            this.setBeforeRenderCallback(() -> this.setBounds(this.getParentWidth(), this.getHeight()));
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            hoverAnimation = Interpolations.interpolate(hoverAnimation, this.isHovering() ? 1f : 0f, .25f);
            int bg = blend(AppTheme.settingsSurface(), AppTheme.settingsHover(), hoverAnimation);
            float surfaceAlpha = !UiMaterials.enabled() && DesktopAppState.background().isActive()
                    ? .5f + .5f * AppTheme.backgroundPanelOpacity() : 1f;
            UiMaterials.surface(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6, RenderSystem.reAlpha(bg, this.getAlpha() * surfaceAlpha));
            FontManager.pf16bold.drawString(label, this.getX() + 16, labelY(),
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha()));
        }

        protected double labelY() {
            // Single-line rows share the vertical center of their control; taller rows have a detail area.
            return getY() + (getHeight() == 52 ? (getHeight() - FontManager.pf16bold.getFontHeight()) * .5 : 13);
        }

        protected double rightInset(double width) {
            return this.getX() + this.getWidth() - width - 16;
        }

        protected static int blend(int from, int to, float progress) {
            progress = Math.max(0, Math.min(1, progress));
            int a = (int) (((from >>> 24) & 0xff) + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * progress);
            int r = (int) (((from >>> 16) & 0xff) + (((to >>> 16) & 0xff) - ((from >>> 16) & 0xff)) * progress);
            int g = (int) (((from >>> 8) & 0xff) + (((to >>> 8) & 0xff) - ((from >>> 8) & 0xff)) * progress);
            int b = (int) ((from & 0xff) + ((to & 0xff) - (from & 0xff)) * progress);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static final class ToggleRow extends SettingsRow<ToggleRow> {
        private final PreferenceValue<Boolean> preference;
        private float toggleAnimation;

        private ToggleRow(String label, PreferenceValue<Boolean> preference) {
            super(label, 52);
            this.preference = preference;
            this.toggleAnimation = preference.getValue() ? 1f : 0f;
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0) {
                    preference.setValue(!preference.getValue());
                }

                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);

            toggleAnimation = Interpolations.interpolate(toggleAnimation, preference.getValue() ? 1f : 0f, .35f);
            double trackWidth = 38;
            double trackHeight = 20;
            double trackX = rightInset(trackWidth);
            double trackY = this.getY() + this.getHeight() * .5 - trackHeight * .5;
            int trackColor = blend(0xFF555555, AppTheme.accent(), toggleAnimation);
            UiMaterials.control(trackX, trackY, trackWidth, trackHeight, 10, RenderSystem.reAlpha(trackColor, this.getAlpha()));

            double knobSize = 16;
            double knobX = trackX + 2 + (trackWidth - knobSize - 4) * toggleAnimation;
            UiMaterials.control(knobX, trackY + 2, knobSize, knobSize, 8, RenderSystem.reAlpha(blend(AppTheme.foreground(), AppTheme.onAccent(), toggleAnimation), this.getAlpha()));
        }
    }

    private static final class ChoiceRow extends SettingsRow<ChoiceRow> {
        private final ModePreference preference;
        private final Function<String, String> formatter;

        private ChoiceRow(String label, ModePreference preference) {
            this(label, preference, Function.identity());
        }

        private ChoiceRow(String label, ModePreference preference, Function<String, String> formatter) {
            super(label, 52);
            this.preference = preference;
            this.formatter = formatter;
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0 || mouseButton == 1) {
                    cycle(mouseButton == 0 ? 1 : -1);
                }

                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);

            String value = formatter.apply(preference.getValue());
            double valueWidth = Math.max(92, FontManager.pf14bold.getStringWidthD(value) + 28);
            double valueHeight = 24;
            double valueX = rightInset(valueWidth);
            double valueY = this.getY() + this.getHeight() * .5 - valueHeight * .5;
            UiMaterials.control(valueX, valueY, valueWidth, valueHeight, 5, RenderSystem.reAlpha(AppTheme.accentMuted(), this.getAlpha()));
            FontManager.pf14bold.drawCenteredString(value, valueX + valueWidth * .5,
                    valueY + valueHeight * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha()));
        }

        private void cycle(int direction) {
            List<String> modes = preference.getModes();
            int index = modes.indexOf(preference.getValue());
            if (index < 0) {
                index = 0;
            }

            int next = (index + direction) % modes.size();
            if (next < 0) {
                next += modes.size();
            }
            preference.setValue(modes.get(next));
        }
    }

    private static final class SliderRow extends SettingsRow<SliderRow> {
        private final NumberPreference preference;
        private final Function<Double, String> formatter;
        private boolean dragging;

        private SliderRow(String label, NumberPreference preference, Function<Double, String> formatter) {
            super(label, 64);
            this.preference = preference;
            this.formatter = formatter;
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0) {
                    dragging = true;
                    updateFromRelativeX(relativeX);
                }

                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);

            if (dragging) {
                if (Mouse.isButtonDown(0)) {
                    updateFromRelativeX(mouseX - this.getX());
                } else {
                    dragging = false;
                }
            }

            String value = formatter.apply(preference.getValue());
            FontManager.pf14bold.drawString(value,
                    this.getX() + this.getWidth() - 16 - FontManager.pf14bold.getStringWidthD(value),
                    this.getY() + 14,
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha() * .72f));

            double barX = this.getX() + 16;
            double barY = this.getY() + 44;
            double barWidth = this.getWidth() - 32;
            double barHeight = 6;
            double progress = (preference.getValue() - preference.getMin()) / (preference.getMax() - preference.getMin());
            progress = Math.max(0, Math.min(1, progress));

            UiMaterials.control(barX, barY, barWidth, barHeight, 3, RenderSystem.reAlpha(0xFF555555, this.getAlpha()));
            UiMaterials.control(barX, barY, barWidth * progress, barHeight, 3, RenderSystem.reAlpha(AppTheme.accent(), this.getAlpha()));

            double knobSize = dragging || this.isHovering() ? 12 : 10;
            UiMaterials.control(barX + barWidth * progress - knobSize * .5,
                    barY + barHeight * .5 - knobSize * .5,
                    knobSize,
                    knobSize,
                    knobSize * .5,
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha()));
        }

        private void updateFromRelativeX(double relativeX) {
            double barX = 16;
            double barWidth = Math.max(1, this.getWidth() - 32);
            double progress = Math.max(0, Math.min(1, (relativeX - barX) / barWidth));
            double raw = preference.getMin() + (preference.getMax() - preference.getMin()) * progress;
            double increment = preference.getIncrement();
            if (increment > 0) {
                raw = preference.getMin() + Math.round((raw - preference.getMin()) / increment) * increment;
            }
            preference.setValue(raw);
        }
    }

    private static final class ActionRow extends SettingsRow<ActionRow> {
        private final Runnable action;
        private float pressAnimation;

        private ActionRow(String label, Runnable action) {
            super(label, 52);
            this.action = action;
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0) {
                    action.run();
                    pressAnimation = 1f;
                }
                return true;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);
            pressAnimation = Interpolations.interpolate(pressAnimation, 0f, .25f);
            String label = "执行";
            double width = 56;
            double height = 24;
            double x = rightInset(width);
            double y = this.getY() + this.getHeight() * .5 - height * .5;
            int bg = blend(AppTheme.accent(), AppTheme.accentHover(), pressAnimation);
            UiMaterials.control(x, y, width, height, 5, RenderSystem.reAlpha(bg, this.getAlpha()));
            FontManager.pf14bold.drawCenteredString(label, x + width * .5,
                    y + height * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(AppTheme.onAccent(), this.getAlpha()));
        }
    }

    private static final class CacheRow extends SettingsRow<CacheRow> {
        private volatile String sizeText = "计算中...";
        private volatile String statusText = "";
        private volatile boolean refreshing;
        private volatile boolean clearing;
        private long lastRefreshMillis;
        private float pressAnimation;

        private CacheRow() {
            super("音乐缓存", 64);
            refreshSize();
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0 && isButtonHit(relativeX, relativeY) && !clearing) {
                    clearCache();
                    return true;
                }
                return false;
            });
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);

            long now = System.currentTimeMillis();
            if (!clearing && now - lastRefreshMillis > 5000L) {
                refreshSize();
            }

            pressAnimation = Interpolations.interpolate(pressAnimation, 0f, .25f);
            double buttonWidth = 72;
            double buttonHeight = 26;
            double buttonX = buttonX(buttonWidth);
            double buttonY = this.getY() + this.getHeight() * .5 - buttonHeight * .5;
            int buttonBg = blend(clearing ? 0xFF555555 : AppTheme.accent(), clearing ? 0xFF666666 : AppTheme.accentHover(), pressAnimation);
            UiMaterials.control(buttonX, buttonY, buttonWidth, buttonHeight, 5, RenderSystem.reAlpha(buttonBg, this.getAlpha()));

            String buttonLabel = clearing ? "清理中" : "清除";
            FontManager.pf14bold.drawCenteredString(buttonLabel, buttonX + buttonWidth * .5,
                    buttonY + buttonHeight * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(clearing ? AppTheme.foreground() : AppTheme.onAccent(), this.getAlpha()));

            String currentSize = clearing ? "清理中..." : sizeText;
            double sizeWidth = FontManager.pf14bold.getStringWidthD(currentSize);
            double sizeX = buttonX - 14 - sizeWidth;
            FontManager.pf14bold.drawString(currentSize, sizeX, this.getY() + 14,
                    RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha() * .78f));

            if (!statusText.isBlank()) {
                FontManager.pf12bold.drawString(statusText,
                        this.getX() + 16,
                        this.getY() + 40,
                        RenderSystem.reAlpha(AppTheme.foreground(), this.getAlpha() * .46f));
            }
        }

        private boolean isButtonHit(double relativeX, double relativeY) {
            double width = 72;
            double height = 26;
            double x = this.getWidth() - width - 16;
            double y = this.getHeight() * .5 - height * .5;
            return relativeX >= x && relativeX <= x + width && relativeY >= y && relativeY <= y + height;
        }

        private double buttonX(double width) {
            return rightInset(width);
        }

        private void refreshSize() {
            if (refreshing) {
                return;
            }

            refreshing = true;
            lastRefreshMillis = System.currentTimeMillis();
            MultiThreadingUtil.runAsync(() -> {
                try {
                    sizeText = DesktopCacheManager.formatSize(DesktopCacheManager.musicCacheSize());
                } catch (Exception e) {
                    sizeText = "读取失败";
                } finally {
                    refreshing = false;
                }
            });
        }

        private void clearCache() {
            if (DesktopAppState.downloadStatus().downloading) {
                statusText = "正在下载音乐，稍后再清理";
                refreshSize();
                return;
            }

            clearing = true;
            statusText = "正在清理缓存...";
            pressAnimation = 1f;
            MultiThreadingUtil.runAsync(() -> {
                DesktopCacheManager.ClearResult result = DesktopCacheManager.clearMusicCache();
                long freed = Math.max(0L, result.sizeBefore() - result.sizeAfter());
                sizeText = DesktopCacheManager.formatSize(result.sizeAfter());
                statusText = result.successful()
                        ? "已释放 " + DesktopCacheManager.formatSize(freed)
                        : "部分文件无法删除，已释放 " + DesktopCacheManager.formatSize(freed);
                clearing = false;
                lastRefreshMillis = System.currentTimeMillis();
            });
        }
    }

    private static final class ColorRow extends SettingsRow<ColorRow> {
        private record Preset(String name, int color) { }

        private static final List<Preset> THEME_PRESETS = List.of(
                new Preset("网易云红", AppTheme.DEFAULT_ACCENT),
                new Preset("晴空蓝", 0xFF58A6FF),
                new Preset("鸢尾紫", 0xFFA78BFA),
                new Preset("湖水青", 0xFF2DD4BF),
                new Preset("薄荷绿", 0xFF71E096),
                new Preset("日落橙", 0xFFFFA657),
                new Preset("樱花粉", 0xFFF472B6),
                new Preset("月光白", 0xFFE5E7EB));
        private static final List<Preset> SPECTRUM_PRESETS = List.of(
                new Preset("经典灰", 0xC87D7D7D),
                new Preset("网易云红", 0xD0C30218),
                new Preset("纯白", 0xD0FFFFFF),
                new Preset("晴空蓝", 0xC858A6FF),
                new Preset("薄荷绿", 0xC871E096));
        private final PreferenceValue<Integer> preference;
        private final boolean alphaEnabled;
        private final List<Preset> presets;
        private final ColorPickerWidget picker;
        private boolean expanded;

        private ColorRow(String label, PreferenceValue<Integer> preference, boolean alphaEnabled) {
            super(label, 104);
            this.preference = preference;
            this.alphaEnabled = alphaEnabled;
            this.presets = alphaEnabled ? SPECTRUM_PRESETS : THEME_PRESETS;
            this.picker = new ColorPickerWidget(preference.getValue(), alphaEnabled, preference::setValue);
            this.addChild(picker);
            picker.setHidden(true);
            picker.setBeforeRenderCallback(() -> picker.setBounds(16, 108, Math.max(1, getWidth() - 32), picker.getHeight()));
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                for (int i = 0; i < presets.size(); i++) {
                    if (isHovered(x, y, presetX(i) - 3, 45, 32, 32)) {
                        preference.setValue(presets.get(i).color());
                        picker.setArgb(preference.getValue());
                        return true;
                    }
                }
                if (y <= 40) {
                    expanded = !expanded;
                    picker.setHidden(!expanded);
                    setBounds(getWidth(), expanded ? 124 + picker.getHeight() : 104);
                    return true;
                }
                return false;
            });
        }

        private double presetX(int index) {
            return 16 + index * Math.min(40, Math.max(0, (getWidth() - 58) / (presets.size() - 1)));
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            super.onRender(mouseX, mouseY);
            int color = preference.getValue();
            picker.setArgb(color);
            String hex = alphaEnabled ? String.format(Locale.ROOT, "#%08X", color)
                    : String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
            String value = hex + (expanded ? "  收起" : "  自定义");
            double valueWidth = FontManager.pf14bold.getStringWidthD(value);
            double valueX = rightInset(valueWidth);
            double swatchX = valueX - 32;
            if (alphaEnabled) {
                picker.drawCheckerboard(swatchX, getY() + 13, 20, 20);
            }
            roundedRect(swatchX, getY() + 13, 20, 20, 4,
                    RenderSystem.reAlpha(color, getAlpha() * (alphaEnabled ? (color >>> 24) / 255f : 1)));
            FontManager.pf14bold.drawString(value, valueX, getY() + 14,
                    RenderSystem.reAlpha(AppTheme.foreground(), getAlpha() * .8f));

            String name = "自定义颜色";
            String hoveredName = null;
            for (int i = 0; i < presets.size(); i++) {
                Preset preset = presets.get(i);
                double x = getX() + presetX(i), y = getY() + 48;
                boolean selected = preset.color() == color;
                if (selected) {
                    name = preset.name();
                    roundedRect(x - 3, y - 3, 32, 32, 16, reAlpha(AppTheme.foreground(), getAlpha()));
                    roundedRect(x - 1, y - 1, 28, 28, 14, reAlpha(AppTheme.settingsSurface(), getAlpha()));
                }
                roundedRect(x, y, 26, 26, 13, reAlpha(preset.color(), getAlpha()));
                if (isHovered(mouseX, mouseY, x, y, 26, 26)) hoveredName = preset.name();
            }
            String hint = expanded ? " · 拖动面板和色相条调整颜色" : " · 点击色板应用，点击自定义展开";
            FontManager.pf12bold.drawString((hoveredName == null ? name : hoveredName) + hint, getX() + 16, getY() + 84,
                    RenderSystem.reAlpha(AppTheme.foreground(), getAlpha() * .5f));
        }
    }
}
