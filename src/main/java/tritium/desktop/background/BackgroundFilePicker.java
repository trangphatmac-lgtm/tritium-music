package tritium.desktop.background;

import tritium.desktop.DesktopAppState;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keep the Swing dialog off the render thread, and preference changes on it. */
public final class BackgroundFilePicker {
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private BackgroundFilePicker() { }

    public static boolean isOpen() { return OPEN.get(); }

    public static void choose(String title, String currentPath, boolean folder, boolean video,
                              Consumer<Path> selected, Consumer<String> error) {
        if (!OPEN.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                chooser.setMultiSelectionEnabled(false);
                if (!folder) {
                    chooser.setAcceptAllFileFilterUsed(false);
                    chooser.setFileFilter(video
                            ? new FileNameExtensionFilter("视频 (MP4, M4V, MOV, WebM, MKV, AVI)", "mp4", "m4v", "mov", "webm", "mkv", "avi")
                            : new FileNameExtensionFilter("图片 (JPG, PNG, BMP, GIF)", "jpg", "jpeg", "png", "bmp", "gif"));
                }
                if (!currentPath.isBlank()) chooser.setSelectedFile(new File(currentPath));
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                    DesktopAppState.runOnMainThread(() -> selected.accept(path));
                }
            } catch (RuntimeException e) {
                DesktopAppState.runOnMainThread(() -> error.accept("无法打开文件选择器，请重试"));
            } finally {
                OPEN.set(false);
            }
        });
    }
}
