package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopCacheManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void calculatesDirectorySizeAndClearsContents() throws Exception {
        Path cache = tempDir.resolve("MusicCache");
        Files.createDirectories(cache.resolve("nested"));
        Files.write(cache.resolve("a.mp3"), new byte[128]);
        Files.write(cache.resolve("nested").resolve("b.flac"), new byte[384]);

        assertEquals(512, DesktopCacheManager.directorySize(cache));

        DesktopCacheManager.ClearResult result = DesktopCacheManager.clearDirectoryContents(cache);

        assertEquals(512, result.sizeBefore());
        assertEquals(0, result.sizeAfter());
        assertEquals(2, result.deletedFiles());
        assertTrue(result.successful());
        assertTrue(Files.isDirectory(cache));
        try (var children = Files.list(cache)) {
            assertFalse(children.findAny().isPresent());
        }
    }

    @Test
    void formatsSizes() {
        assertEquals("0 B", DesktopCacheManager.formatSize(0));
        assertEquals("512 B", DesktopCacheManager.formatSize(512));
        assertEquals("1.00 KB", DesktopCacheManager.formatSize(1024));
        assertEquals("1.50 MB", DesktopCacheManager.formatSize(1024L * 1024L + 512L * 1024L));
    }
}
