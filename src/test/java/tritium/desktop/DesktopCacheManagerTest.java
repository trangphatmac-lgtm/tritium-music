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

    @Test
    void clearsAtOrAboveTheLimitButNotBelowOrWhenUnlimited() throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("MusicCache"));
        Path song = cache.resolve("song.mp3");
        Files.write(song, new byte[512]);
        DesktopCacheManager.clearIfLimitReached(cache, 0);
        assertTrue(Files.exists(song));
        DesktopCacheManager.clearIfLimitReached(cache, 513);
        assertTrue(Files.exists(song));
        DesktopCacheManager.clearIfLimitReached(cache, 512);
        assertFalse(Files.exists(song), "Reaching the limit exactly must trigger cleanup");
        Files.write(song, new byte[512]);
        DesktopCacheManager.clearIfLimitReached(cache, 128);
        assertFalse(Files.exists(song), "Lowering the limit must clean an existing cache");
        assertTrue(Files.isDirectory(cache));
    }

    @Test
    void keepsActiveFilesUntilEveryReaderReleasesThem() throws Exception {
        Path cache = Files.createDirectories(tempDir.resolve("MusicCache/nested"));
        Path song = Files.write(cache.resolve("playing.wav"), new byte[512]);
        Files.write(cache.resolve("old.mp3"), new byte[128]);
        var first = DesktopCacheManager.useMusicCacheFile(song);
        try (var second = DesktopCacheManager.useMusicCacheFile(cache.resolve("../nested/playing.wav"))) {
            try {
                DesktopCacheManager.clearIfLimitReached(cache.getParent(), 256);
                assertTrue(Files.exists(song));
                assertFalse(Files.exists(cache.resolve("old.mp3")));
            } finally { first.close(); }
            first.close(); // A repeated release must not drop another reader's protection.
            var result = DesktopCacheManager.clearDirectoryContents(cache.getParent());
            assertTrue(result.successful());
            assertEquals(512, result.sizeAfter());
            assertTrue(Files.exists(song));
        }
        DesktopCacheManager.clearIfLimitReached(cache.getParent(), 256);
        assertFalse(Files.exists(song));
        assertTrue(Files.isDirectory(cache.getParent()));
    }

    @Test
    void protectsGrowingDownloadsAndPcmButRemovesAbandonedTemporaryFiles() throws Exception {
        Path cache = tempDir.resolve("MusicCache");
        Path temporary;
        try (var access = DesktopCacheManager.createTemporaryMusicCacheFile(cache, "stream-pcm-")) {
            temporary = access.path();
            Files.write(temporary, new byte[512]);
            Path abandoned = Files.write(cache.resolve("stream-abandoned.part"), new byte[128]);
            Path outside = Files.write(tempDir.resolve("download.flac"), new byte[128]);
            DesktopCacheManager.clearIfLimitReached(cache, 256);
            assertTrue(Files.exists(temporary));
            assertFalse(Files.exists(abandoned));
            assertTrue(Files.exists(outside), "Cleanup must stay inside the music cache");
            Files.write(temporary, new byte[1024]);
            DesktopCacheManager.clearIfLimitReached(cache, 256);
            assertEquals(1024, Files.size(temporary));
        }
        DesktopCacheManager.clearIfLimitReached(cache, 256);
        assertFalse(Files.exists(temporary));
    }
}
