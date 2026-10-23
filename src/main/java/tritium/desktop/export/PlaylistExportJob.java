package tritium.desktop.export;

import tritium.desktop.DesktopAppState;
import tritium.ncm.music.dto.PlayList;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PlaylistExportJob {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private PlaylistExportJob() { }
    public static boolean isBusy() { return BUSY.get(); }

    public static void start(List<PlayList> playlists, PlaylistExporter.Format format,
                             Set<PlaylistExporter.Field> fields, Consumer<String> status) {
        if (!BUSY.compareAndSet(false, true)) return;
        List<PlayList> snapshot = List.copyOf(playlists);
        Set<PlaylistExporter.Field> selected = Set.copyOf(fields);
        status.accept("请选择保存位置…");
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("导出歌单列表");
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileFilter(new FileNameExtensionFilter(format.label + " (*." + format.extension + ")", format.extension));
                chooser.setSelectedFile(new java.io.File("歌单导出." + format.extension));
                if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
                    finish(status, "已取消导出");
                    return;
                }
                Path path = withExtension(chooser.getSelectedFile().toPath(), format).toAbsolutePath().normalize();
                boolean replace = Files.exists(path);
                if (replace && JOptionPane.showConfirmDialog(null, "文件已存在，是否覆盖？\n" + path,
                        "确认覆盖", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                    finish(status, "已取消导出");
                    return;
                }
                MultiThreadingUtil.runAsync(() -> export(snapshot, format, selected, path, replace, status));
            } catch (RuntimeException e) {
                finish(status, "无法打开保存窗口，请重试");
            }
        });
    }

    static Path withExtension(Path path, PlaylistExporter.Format format) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith("." + format.extension)
                ? path : path.resolveSibling(name + "." + format.extension);
    }

    private static void export(List<PlayList> playlists, PlaylistExporter.Format format,
                               Set<PlaylistExporter.Field> fields, Path path, boolean replace, Consumer<String> status) {
        List<PlaylistExporter.Playlist> loaded = new ArrayList<>();
        for (int i = 0; i < playlists.size(); i++) {
            PlayList playlist = playlists.get(i);
            String progress = "正在读取歌单 " + (i + 1) + "/" + playlists.size() + "：" + playlist.getName();
            DesktopAppState.runOnMainThread(() -> status.accept(progress));
            try {
                loaded.add(new PlaylistExporter.Playlist(playlist.getName(),
                        playlist.loadMusicsAsync().get(120, TimeUnit.SECONDS)));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                finish(status, "导出失败：无法读取「" + playlist.getName() + "」，请检查网络或登录状态后重试");
                return;
            }
        }
        try {
            PlaylistExporter.write(path, PlaylistExporter.serialize(loaded, format, fields), replace);
            int songs = loaded.stream().mapToInt(playlist -> playlist.songs().size()).sum();
            finish(status, "已导出 " + loaded.size() + " 个歌单、" + songs + " 首歌曲至 " + path);
        } catch (Exception e) {
            finish(status, "保存失败，请检查文件权限、磁盘空间或重新选择文件名");
        }
    }

    private static void finish(Consumer<String> status, String message) {
        DesktopAppState.runOnMainThread(() -> {
            BUSY.set(false);
            status.accept(message);
        });
    }
}
