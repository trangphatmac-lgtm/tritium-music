package tritium.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {
    @TempDir Path directory;

    @Test
    void resolvesMusicDownloadsForMacAndWindowsIncludingRedirectedMusicFolders() {
        assertEquals(Path.of("/Users/test/Music/Tritium Music"),
                AppPaths.downloadDirFor("Mac OS X", "/Users/test", null, directory.toString()));
        assertEquals(Path.of("C:/Users/test/Music/Tritium Music"),
                AppPaths.downloadDirFor("Windows 11", "C:/Users/test", null, null));
        assertEquals(Path.of("D:/媒体/音乐/Tritium Music"),
                AppPaths.downloadDirFor("Windows 11", "C:/Users/test", "D:/媒体/音乐", null));
    }

    @Test
    void followsLocalizedAndRelocatedLinuxMusicDirectories() throws Exception {
        Path home = directory.resolve("user home");
        Path config = Files.createDirectories(home.resolve(".config"));
        Files.writeString(config.resolve("user-dirs.dirs"), """
                # XDG_MUSIC_DIR="/ignored"
                XDG_DOWNLOAD_DIR="$HOME/Downloads"
                XDG_MUSIC_DIR="$HOME/音乐"
                """);
        assertEquals(home.resolve("音乐/Tritium Music"),
                AppPaths.downloadDirFor("Linux", home.toString(), null, null));
        assertEquals(home.resolve("音乐/Tritium Music"),
                AppPaths.downloadDirFor("Linux", home.toString(), null, "relative-config"));
        Path customConfig = Files.createDirectories(directory.resolve("custom-config"));
        Files.writeString(customConfig.resolve("user-dirs.dirs"), "XDG_MUSIC_DIR=\"/media/音乐合集\"\n");
        assertEquals(Path.of("/media/音乐合集/Tritium Music"),
                AppPaths.downloadDirFor("Linux", home.toString(), null, customConfig.toString()));
        Files.writeString(customConfig.resolve("user-dirs.dirs"), "XDG_MUSIC_DIR=\"${HOME}/My \\\"Music\\\"\"\n");
        assertEquals(home.resolve("My \"Music\"/Tritium Music"),
                AppPaths.downloadDirFor("Linux", home.toString(), null, customConfig.toString()));
    }

    @Test
    void fallsBackToMusicForMissingOrMalformedLinuxConfiguration() throws Exception {
        Path home = directory.resolve("home");
        Path expected = home.resolve("Music/Tritium Music");
        assertEquals(expected, AppPaths.downloadDirFor("Linux", home.toString(), null, null));
        Path config = Files.createDirectories(home.resolve(".config"));
        for (String value : new String[]{"", "relative/music", "$(not-a-command)", "$HOMEbroken/Music", "\0"}) {
            Files.writeString(config.resolve("user-dirs.dirs"), "XDG_MUSIC_DIR=\"" + value + "\"\n");
            assertEquals(expected, AppPaths.downloadDirFor("Linux", home.toString(), null, null));
        }
    }

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
