package tritium.screens.ncm.panels;

import tritium.desktop.AppTheme;
import tritium.desktop.export.PlaylistExporter;
import tritium.desktop.export.PlaylistExportJob;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.ui.AbstractWidget;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Paged playlist selection keeps large libraries usable within the settings scroll view. */
public final class PlaylistExportWidget extends AbstractWidget<PlaylistExportWidget> {
    private static final int PAGE_SIZE = 4;
    private List<PlayList> playlists = List.of();
    private List<PlayList> source;
    private final Set<Long> selected = new HashSet<>();
    private final EnumSet<PlaylistExporter.Field> fields = EnumSet.allOf(PlaylistExporter.Field.class);
    private PlaylistExporter.Format format = PlaylistExporter.Format.HTML;
    private int page;
    private String status = "选择歌单和信息后导出；多个歌单合并为一个文件";

    public PlaylistExportWidget() {
        setBounds(0, 0, 100, 470);
        setBeforeRenderCallback(() -> {
            setBounds(getParentWidth(), 470);
            if (source != CloudMusic.playLists) {
                source = CloudMusic.playLists;
                playlists = source == null ? List.of() : List.copyOf(source);
                selected.retainAll(playlists.stream().map(PlayList::getId).toList());
                page = 0;
            }
        });
        button(() -> "全选", () -> false, () -> !playlists.isEmpty(),
                () -> playlists.forEach(playlist -> selected.add(playlist.getId())), () -> getWidth() - 136, 12, () -> 56.0, 26);
        button(() -> "清空", () -> false, () -> !selected.isEmpty(), selected::clear,
                () -> getWidth() - 72, 12, () -> 56.0, 26);
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int row = i;
            button(() -> {
                PlayList playlist = playlistAt(row);
                return playlist == null ? "" : playlist.getName()
                        + (playlist.isSubscribed() ? " · 收藏" : " · 创建") + " · " + playlist.getCount() + " 首";
            }, () -> playlistAt(row) != null && selected.contains(playlistAt(row).getId()),
                    () -> playlistAt(row) != null, () -> {
                        long id = playlistAt(row).getId();
                        if (!selected.remove(id)) selected.add(id);
                    }, () -> 16.0, 48 + row * 32, () -> getWidth() - 32, 28, true);
        }
        button(() -> "上一页", () -> false, () -> page > 0, () -> page--,
                () -> getWidth() - 152, 181, () -> 64.0, 26);
        button(() -> "下一页", () -> false, () -> (page + 1) * PAGE_SIZE < playlists.size(), () -> page++,
                () -> getWidth() - 80, 181, () -> 64.0, 26);
        for (PlaylistExporter.Format option : PlaylistExporter.Format.values()) {
            int index = option.ordinal();
            button(() -> option.label, () -> format == option, () -> true, () -> format = option,
                    () -> 16 + index % 3 * columnStep(), 244 + index / 3 * 32, () -> columnStep() - 8, 26);
        }
        for (PlaylistExporter.Field field : PlaylistExporter.Field.values()) {
            int index = field.ordinal();
            button(() -> field.label,
                    () -> fields.contains(field), () -> true, () -> {
                        if (!fields.remove(field)) fields.add(field);
                    }, () -> 16 + index % 3 * columnStep(), 338 + index / 3 * 32, () -> columnStep() - 8, 26, true);
        }
        button(() -> PlaylistExportJob.isBusy() ? "导出中…" : "导出并保存", () -> true,
                () -> !selected.isEmpty() && !fields.isEmpty(), () -> PlaylistExportJob.start(
                        playlists.stream().filter(playlist -> selected.contains(playlist.getId())).toList(),
                        format, fields, message -> status = message), () -> getWidth() - 128, 408, () -> 112.0, 30);
    }

    private double columnStep() { return (getWidth() - 24) / 3; }
    private PlayList playlistAt(int row) {
        int index = page * PAGE_SIZE + row;
        return index < playlists.size() ? playlists.get(index) : null;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        UiMaterials.surface(getX(), getY(), getWidth(), getHeight(), 6, reAlpha(AppTheme.settingsSurface(), getAlpha()));
        label("选择歌单（已选 " + selected.size() + "）", 16, 18, getWidth() - 164);
        if (playlists.isEmpty()) label(CloudMusic.profile == null ? "登录后可选择创建和收藏的歌单" : "暂无歌单，请等待加载或重新登录", 24, 96, getWidth() - 48);
        label("第 " + (page + 1) + " / " + Math.max(1, (playlists.size() + PAGE_SIZE - 1) / PAGE_SIZE) + " 页", 16, 188, getWidth() - 180);
        label("导出格式", 16, 222, getWidth() - 32);
        label("导出信息（可多选）", 16, 316, getWidth() - 32);
        label(fields.isEmpty() ? "请至少选择一项信息" : "已选 " + selected.size() + " 个歌单 · " + fields.size() + " 项信息", 16, 417, getWidth() - 160);
        label(status, 16, 449, getWidth() - 32);
    }

    private void label(String text, double x, double y, double width) {
        FontManager.pf12bold.drawString(shorten(text, width), getX() + x, getY() + y,
                reAlpha(AppTheme.foreground(), getAlpha() * .8f));
    }

    private static String shorten(String text, double width) {
        if (FontManager.pf12bold.getStringWidthD(text) <= width) return text;
        int end = text.length();
        while (end > 0 && FontManager.pf12bold.getStringWidthD(text.substring(0, end) + "…") > width)
            end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + "…";
    }

    private void button(Supplier<String> text, BooleanSupplier active, BooleanSupplier enabled, Runnable action,
                        Supplier<Double> x, double y, Supplier<Double> width, double height) {
        button(text, active, enabled, action, x, y, width, height, false);
    }

    private void button(Supplier<String> text, BooleanSupplier active, BooleanSupplier enabled, Runnable action,
                        Supplier<Double> x, double y, Supplier<Double> width, double height, boolean checkbox) {
        ExportButton button = new ExportButton(text, active, enabled, action, checkbox);
        addChild(button);
        button.setBeforeRenderCallback(() -> button.setBounds(x.get(), y, width.get(), height));
    }

    private static final class ExportButton extends AbstractWidget<ExportButton> {
        private final Supplier<String> text;
        private final BooleanSupplier active, enabled;
        private final boolean checkbox;
        ExportButton(Supplier<String> text, BooleanSupplier active, BooleanSupplier enabled, Runnable action, boolean checkbox) {
            this.text = text;
            this.active = active;
            this.enabled = enabled;
            this.checkbox = checkbox;
            setShouldOverrideMouseCursor(true);
            setOnClickCallback((x, y, button) -> {
                if (button != 0 || !enabled.getAsBoolean() || PlaylistExportJob.isBusy()) return false;
                action.run();
                return true;
            });
        }
        @Override
        public void onRender(double mouseX, double mouseY) {
            boolean available = enabled.getAsBoolean() && !PlaylistExportJob.isBusy();
            String label = text.get();
            if (label.isEmpty()) return;
            float alpha = getAlpha() * (available ? 1f : .4f);
            UiMaterials.control(getX(), getY(), getWidth(), getHeight(), 4,
                    reAlpha(active.getAsBoolean() ? AppTheme.accentMuted()
                            : isHovering() && available ? AppTheme.settingsHover() : AppTheme.settingsSurface(), alpha));
            double textInset = checkbox ? 28 : 8;
            if (checkbox) {
                double boxY = getY() + (getHeight() - 12) / 2;
                roundedRect(getX() + 8, boxY, 12, 12, 2, reAlpha(AppTheme.foreground(), alpha * .6f));
                roundedRect(getX() + 9, boxY + 1, 10, 10, 1, reAlpha(AppTheme.settingsSurface(), alpha));
                if (active.getAsBoolean()) roundedRect(getX() + 11, boxY + 3, 6, 6, 1, reAlpha(AppTheme.accent(), alpha));
            }
            FontManager.pf12bold.drawString(shorten(label, getWidth() - textInset - 8), getX() + textInset,
                    getY() + (getHeight() - FontManager.pf12bold.getHeight()) / 2,
                    reAlpha(AppTheme.foreground(), alpha));
        }
    }
}
