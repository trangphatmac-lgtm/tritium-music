package tritium.screens.ncm.panels;

import org.lwjgl.input.Mouse;
import tritium.desktop.DesktopCacheManager;
import tritium.desktop.DesktopAppState;
import tritium.desktop.ModePreference;
import tritium.desktop.MusicPreferences;
import tritium.desktop.NumberPreference;
import tritium.desktop.PreferenceValue;
import tritium.management.FontManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

public class SettingsPanel extends NCMPanel {
    private static final int MARGIN = 24;
    private final MusicPreferences preferences = DesktopAppState.preferences();

    @Override
    public void onInit() {
        this.getChildren().clear();

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
            double top = subtitle.getRelativeY() + subtitle.getHeight() + 22;
            scrollPanel.setBounds(MARGIN, top, this.getWidth() - MARGIN * 2.0, this.getHeight() - top - MARGIN);
        });

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
        scrollPanel.addChild(new ColorRow("频谱颜色", preferences.hud().musicSpectrum().color()));

        scrollPanel.addChild(new SectionHeader("调试"));
        scrollPanel.addChild(new ToggleRow("UI 边界", preferences.showWidgetBoundary()));
        scrollPanel.addChild(new ToggleRow("逐字歌词", preferences.lyricDebug()));
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
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha() * .48f));
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
            int bg = blend(0xFF242424, 0xFF303030, hoverAnimation);
            roundedRect(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 6, RenderSystem.reAlpha(bg, this.getAlpha()));
            FontManager.pf16bold.drawString(label, this.getX() + 16, this.getY() + 13,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));
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
            int trackColor = blend(0xFF555555, 0xFFC30218, toggleAnimation);
            roundedRect(trackX, trackY, trackWidth, trackHeight, 10, RenderSystem.reAlpha(trackColor, this.getAlpha()));

            double knobSize = 16;
            double knobX = trackX + 2 + (trackWidth - knobSize - 4) * toggleAnimation;
            roundedRect(knobX, trackY + 2, knobSize, knobSize, 8, RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));
        }
    }

    private static final class ChoiceRow extends SettingsRow<ChoiceRow> {
        private final ModePreference preference;

        private ChoiceRow(String label, ModePreference preference) {
            super(label, 52);
            this.preference = preference;
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

            String value = preference.getValue();
            double valueWidth = Math.max(92, FontManager.pf14bold.getStringWidthD(value) + 28);
            double valueHeight = 24;
            double valueX = rightInset(valueWidth);
            double valueY = this.getY() + this.getHeight() * .5 - valueHeight * .5;
            roundedRect(valueX, valueY, valueWidth, valueHeight, 5, RenderSystem.reAlpha(0xFF3A3030, this.getAlpha()));
            FontManager.pf14bold.drawCenteredString(value, valueX + valueWidth * .5,
                    valueY + valueHeight * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));
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
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha() * .72f));

            double barX = this.getX() + 16;
            double barY = this.getY() + 44;
            double barWidth = this.getWidth() - 32;
            double barHeight = 6;
            double progress = (preference.getValue() - preference.getMin()) / (preference.getMax() - preference.getMin());
            progress = Math.max(0, Math.min(1, progress));

            roundedRect(barX, barY, barWidth, barHeight, 3, RenderSystem.reAlpha(0xFF555555, this.getAlpha()));
            roundedRect(barX, barY, barWidth * progress, barHeight, 3, RenderSystem.reAlpha(0xFFC30218, this.getAlpha()));

            double knobSize = dragging || this.isHovering() ? 12 : 10;
            roundedRect(barX + barWidth * progress - knobSize * .5,
                    barY + barHeight * .5 - knobSize * .5,
                    knobSize,
                    knobSize,
                    knobSize * .5,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));
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
            int bg = blend(0xFFC30218, 0xFFE4434F, pressAnimation);
            roundedRect(x, y, width, height, 5, RenderSystem.reAlpha(bg, this.getAlpha()));
            FontManager.pf14bold.drawCenteredString(label, x + width * .5,
                    y + height * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));
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
            int buttonBg = blend(clearing ? 0xFF555555 : 0xFFC30218, clearing ? 0xFF666666 : 0xFFE4434F, pressAnimation);
            roundedRect(buttonX, buttonY, buttonWidth, buttonHeight, 5, RenderSystem.reAlpha(buttonBg, this.getAlpha()));

            String buttonLabel = clearing ? "清理中" : "清除";
            FontManager.pf14bold.drawCenteredString(buttonLabel, buttonX + buttonWidth * .5,
                    buttonY + buttonHeight * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha()));

            String currentSize = clearing ? "清理中..." : sizeText;
            double sizeWidth = FontManager.pf14bold.getStringWidthD(currentSize);
            double sizeX = buttonX - 14 - sizeWidth;
            FontManager.pf14bold.drawString(currentSize, sizeX, this.getY() + 14,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha() * .78f));

            if (!statusText.isBlank()) {
                FontManager.pf12bold.drawString(statusText,
                        this.getX() + 16,
                        this.getY() + 40,
                        RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha() * .46f));
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
        private static final int[] COLORS = {
                0xC87D7D7D,
                0xD0C30218,
                0xD0FFFFFF,
                0xC858A6FF,
                0xC871E096
        };
        private final PreferenceValue<Integer> preference;

        private ColorRow(String label, PreferenceValue<Integer> preference) {
            super(label, 52);
            this.preference = preference;
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
            int color = preference.getValue();
            String hex = String.format(Locale.ROOT, "#%08X", color);
            double swatchSize = 24;
            double textWidth = FontManager.pf14bold.getStringWidthD(hex);
            double x = rightInset(swatchSize + 10 + textWidth);
            double y = this.getY() + this.getHeight() * .5 - swatchSize * .5;
            roundedRect(x, y, swatchSize, swatchSize, 5, RenderSystem.reAlpha(color, this.getAlpha()));
            roundedRect(x, y, swatchSize, swatchSize, 5, RenderSystem.reAlpha(0x33FFFFFF, this.getAlpha()));
            FontManager.pf14bold.drawString(hex, x + swatchSize + 10,
                    this.getY() + this.getHeight() * .5 - FontManager.pf14bold.getHeight() * .5,
                    RenderSystem.reAlpha(0xFFFFFFFF, this.getAlpha() * .72f));
        }

        private void cycle(int direction) {
            int index = 0;
            for (int i = 0; i < COLORS.length; i++) {
                if (COLORS[i] == preference.getValue()) {
                    index = i;
                    break;
                }
            }
            int next = (index + direction) % COLORS.length;
            if (next < 0) {
                next += COLORS.length;
            }
            preference.setValue(COLORS[next]);
        }
    }
}
