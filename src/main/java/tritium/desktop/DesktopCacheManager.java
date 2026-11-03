package tritium.desktop;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DesktopCacheManager {
    private static final Object FILE_LOCK = new Object();
    private static final Object CLEANUP_LOCK = new Object();
    private static final Map<Path, Integer> ACTIVE_FILES = new HashMap<>();
    private static final AtomicBoolean CLEANUP_QUEUED = new AtomicBoolean();
    private static volatile long musicCacheLimitBytes;
    private static volatile ScheduledExecutorService cleanupExecutor;

    private DesktopCacheManager() {
    }

    /** Zero disables automatic cleanup. Disk work always runs on a background worker. */
    public static synchronized void setMusicCacheLimit(long bytes) {
        long limit = Math.max(0L, bytes);
        if (musicCacheLimitBytes == limit) return;
        musicCacheLimitBytes = limit;
        if (cleanupExecutor == null && limit > 0) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "music-cache-cleaner");
                thread.setDaemon(true);
                return thread;
            });
            // Downloads and PCM buffers can grow while their files remain in use.
            cleanupExecutor.scheduleWithFixedDelay(DesktopCacheManager::requestMusicCacheCleanup,
                    5, 5, TimeUnit.SECONDS);
        }
        requestMusicCacheCleanup();
    }

    private static void requestMusicCacheCleanup() {
        ScheduledExecutorService executor = cleanupExecutor;
        if (executor == null || musicCacheLimitBytes <= 0 || !CLEANUP_QUEUED.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                clearIfLimitReached(AppPaths.musicCacheDir(), musicCacheLimitBytes);
            } catch (IOException | RuntimeException e) {
                System.err.println("Cannot automatically clear music cache: " + e.getMessage());
            } finally {
                CLEANUP_QUEUED.set(false);
            }
        });
    }

    static void clearIfLimitReached(Path directory, long limitBytes) throws IOException {
        synchronized (CLEANUP_LOCK) {
            if (limitBytes > 0 && directorySize(directory) >= limitBytes) {
                clearDirectoryContents(directory);
            }
        }
    }

    /** Register before checking/opening a cache file so cleanup cannot race its readers or writers. */
    public static CacheAccess useMusicCacheFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        synchronized (FILE_LOCK) {
            ACTIVE_FILES.merge(normalized, 1, Integer::sum);
        }
        return new CacheAccess(normalized);
    }

    /** Creation and registration are atomic with respect to cleanup, including temporary PCM files. */
    public static CacheAccess createTemporaryMusicCacheFile(Path directory, String prefix) throws IOException {
        synchronized (FILE_LOCK) {
            Files.createDirectories(directory);
            return useMusicCacheFile(Files.createTempFile(directory, prefix, ".part"));
        }
    }

    public static final class CacheAccess implements AutoCloseable {
        private final Path path;
        private boolean closed;

        private CacheAccess(Path path) { this.path = path; }

        public Path path() { return path; }

        @Override
        public void close() {
            synchronized (FILE_LOCK) {
                if (closed) return;
                closed = true;
                ACTIVE_FILES.computeIfPresent(path, (key, count) -> count == 1 ? null : count - 1);
            }
            requestMusicCacheCleanup();
        }
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

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                throw exc;
            }
        });
        return size.get();
    }

    public static ClearResult clearMusicCache() {
        return clearDirectoryContents(AppPaths.musicCacheDir());
    }

    static ClearResult clearDirectoryContents(Path directory) {
        synchronized (CLEANUP_LOCK) {
            return clearDirectoryContentsLocked(directory);
        }
    }

    private static ClearResult clearDirectoryContentsLocked(Path directory) {
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
                        synchronized (FILE_LOCK) {
                            if (!ACTIVE_FILES.containsKey(file.toAbsolutePath().normalize()) && Files.deleteIfExists(file)) {
                                deletedFiles.incrementAndGet();
                            }
                        }
                    } catch (IOException e) {
                        failedDeletes.incrementAndGet();
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (!(exc instanceof NoSuchFileException)) failedDeletes.incrementAndGet();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        failedDeletes.incrementAndGet();
                    }
                    try {
                        synchronized (FILE_LOCK) {
                            Path normalized = dir.toAbsolutePath().normalize();
                            if (ACTIVE_FILES.keySet().stream().noneMatch(path -> path.startsWith(normalized))
                                    && Files.deleteIfExists(dir)) {
                                deletedDirectories.incrementAndGet();
                            }
                        }
                    } catch (DirectoryNotEmptyException ignored) {
                        // A concurrent download may have created another file in this directory.
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
