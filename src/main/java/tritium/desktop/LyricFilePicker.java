package tritium.desktop;

import tritium.i18n.I18n;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.lyrics.LocalLyricStore;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LyricFilePicker {
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private LyricFilePicker() { }

    public static void choose(Music song) {
        if (song == null || !OPEN.compareAndSet(false, true)) return;
        if (usesNativePicker()) {
            MultiThreadingUtil.runAsync(() -> {
                try {
                    String extensions = "{\"" + String.join("\", \"", LocalLyricStore.EXTENSIONS) + "\"}";
                    String script = """
                            on run argv
                                try
                                    set selectedFile to choose file with prompt (item 1 of argv) of type %s
                                    return POSIX path of selectedFile
                                on error number -128
                                    return ""
                                end try
                            end run
                            """.formatted(extensions);
                    String file = appleScript(script, I18n.text("lyrics.chooseFile"));
                    if (!file.isBlank()) CloudMusic.importLyrics(song, Path.of(file));
                } catch (Exception e) {
                    showError();
                } finally {
                    OPEN.set(false);
                }
            });
            return;
        }
        MultiThreadingUtil.runAsync(() -> SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(I18n.text("lyrics.chooseFile"));
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setMultiSelectionEnabled(false);
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileFilter(new FileNameExtensionFilter(I18n.text("lyrics.fileFilter"),
                        LocalLyricStore.EXTENSIONS.toArray(String[]::new)));
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                    OPEN.set(false);
                    return;
                }
                Path file = chooser.getSelectedFile().toPath();
                MultiThreadingUtil.runAsync(() -> {
                    try {
                        CloudMusic.importLyrics(song, file);
                    } catch (Exception e) {
                        showError();
                    } finally {
                        OPEN.set(false);
                    }
                });
            } catch (RuntimeException e) {
                OPEN.set(false);
                showError();
            }
        }));
    }

    private static boolean usesNativePicker() {
        // Swing's AppKit callbacks cannot run while LWJGL owns macOS's first thread.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                && "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid()));
    }

    private static String appleScript(String script, String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("/usr/bin/osascript", "-e", script));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IOException("Unable to open lyric dialog");
            return output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
        } finally {
            process.destroy();
        }
    }

    private static void showError() {
        if (usesNativePicker()) {
            try {
                appleScript("on run argv\ndisplay alert (item 1 of argv) message (item 2 of argv) as critical\nend run",
                        I18n.text("lyrics.chooseFile"), I18n.text("lyrics.importFailed"));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("[LocalLyrics] " + I18n.text("lyrics.importFailed"));
            }
            return;
        }
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                I18n.text("lyrics.importFailed"), I18n.text("lyrics.chooseFile"), JOptionPane.ERROR_MESSAGE));
    }
}
