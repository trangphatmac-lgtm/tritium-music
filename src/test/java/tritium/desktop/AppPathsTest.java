package tritium.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {
    @Test
    void resolvesWindowsAppDataPath() {
        assertEquals(
                Path.of("C:/Users/test/AppData/Roaming", "Tritium Music"),
                AppPaths.appDataDirFor("Windows 11", "C:/Users/test", "C:/Users/test/AppData/Roaming", null)
        );
    }

    @Test
    void resolvesPreferencesFileBelowAppData() {
        assertEquals(
                AppPaths.appDataDir().resolve("preferences.json"),
                AppPaths.preferencesFile()
        );
    }

    @Test
    void resolvesMacApplicationSupportPath() {
        assertEquals(
                Path.of("/Users/test", "Library", "Application Support", "Tritium Music"),
                AppPaths.appDataDirFor("Mac OS X", "/Users/test", null, null)
        );
        assertEquals(
                Path.of("/Users/test", "Library", "Caches", "Tritium Music"),
                AppPaths.cacheDirFor("Mac OS X", "/Users/test", Path.of("/unused"))
        );
    }
}
