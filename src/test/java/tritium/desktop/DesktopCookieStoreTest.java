package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DesktopCookieStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndReadsCookie() throws Exception {
        Path cookieFile = tempDir.resolve("NCMCookie.txt");

        DesktopCookieStore.save(cookieFile, " MUSIC_U=abc; __csrf=def; ");

        assertEquals("MUSIC_U=abc; __csrf=def;", DesktopCookieStore.load(cookieFile));
    }

    @Test
    void ignoresEmptyCookie() throws Exception {
        Path cookieFile = tempDir.resolve("NCMCookie.txt");

        DesktopCookieStore.save(cookieFile, "");

        assertFalse(cookieFile.toFile().exists());
        assertEquals("", DesktopCookieStore.load(cookieFile));
    }
}
