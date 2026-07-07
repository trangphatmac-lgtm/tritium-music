package tritium.desktop;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DesktopCacheManager {
    private DesktopCacheManager() {
    }

    public static long musicCacheSize() throws IOException {
        return directorySize(AppPaths.musicCacheDir());
    }

    static long directorySize(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0L;
        }

        AtomicLong size = new AtomicLong();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    size.addAndGet(attrs.size());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return size.get();
    }

    public static ClearResult clearMusicCache() {
        return clearDirectoryContents(AppPaths.musicCacheDir());
    }

    static ClearResult clearDirectoryContents(Path directory) {
        long sizeBefore = 0L;
        AtomicInteger deletedFiles = new AtomicInteger();
        AtomicInteger deletedDirectories = new AtomicInteger();
        AtomicInteger failedDeletes = new AtomicInteger();

        try {
            sizeBefore = directorySize(directory);
        } catch (IOException ignored) {
        }

        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException ignored) {
            }
            return new ClearResult(sizeBefore, 0L, 0, 0, 0);
        }

        if (!Files.isDirectory(directory)) {
            try {
                Files.deleteIfExists(directory);
                Files.createDirectories(directory);
            } catch (IOException e) {
                failedDeletes.incrementAndGet();
            }
            return new ClearResult(sizeBefore, safeSize(directory), 0, 0, failedDeletes.get());
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                deleteRecursively(child, deletedFiles, deletedDirectories, failedDeletes);
            }
        } catch (IOException e) {
            failedDeletes.incrementAndGet();
        }

        try {
            Files.createDirectories(directory);
        } catch (IOException ignored) {
        }

        return new ClearResult(sizeBefore, safeSize(directory), deletedFiles.get(), deletedDirectories.get(), failedDeletes.get());
    }

    private static void deleteRecursively(Path path, AtomicInteger deletedFiles, AtomicInteger deletedDirectories,
                                          AtomicInteger failedDeletes) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                        deletedFiles.incrementAndGet();
                    } catch (IOException e) {
                        failedDeletes.incrementAndGet();
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failedDeletes.incrementAndGet();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        failedDeletes.incrementAndGet();
                    }
                    try {
                        Files.deleteIfExists(dir);
                        deletedDirectories.incrementAndGet();
                    } catch (IOException e) {
                        failedDeletes.incrementAndGet();
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failedDeletes.incrementAndGet();
        }
    }

    private static long safeSize(Path directory) {
        try {
            return directorySize(directory);
        } catch (IOException e) {
            return 0L;
        }
    }

    public static String formatSize(long bytes) {
        long safeBytes = Math.max(0L, bytes);
        if (safeBytes < 1024L) {
            return safeBytes + " B";
        }

        double value = safeBytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    public record ClearResult(long sizeBefore, long sizeAfter, int deletedFiles, int deletedDirectories,
                              int failedDeletes) {
        public boolean successful() {
            return failedDeletes == 0;
        }
    }
}
