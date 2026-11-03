package tritium.desktop;

import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AppPaths {
    private static final String APP_NAME = "Tritium Music";
    private static final Pattern XDG_MUSIC = Pattern.compile("^\\s*XDG_MUSIC_DIR\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*(?:#.*)?$");

    private AppPaths() {
    }

    public static Path appDataDir() {
        return appDataDirFor(
                System.getProperty("os.name", ""),
                System.getProperty("user.home"),
                System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME")
        );
    }

    static Path appDataDirFor(String osName, String userHome, String appData, String xdgDataHome) {
        String os = osName == null ? "" : osName.toLowerCase();
        if (os.contains("win")) {
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, APP_NAME);
            }
        }

        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", APP_NAME);
        }

        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "tritium-music");
        }
        return Path.of(userHome, ".local", "share", "tritium-music");
    }

    public static Path cacheDir() {
        return cacheDirFor(System.getProperty("os.name", ""), System.getProperty("user.home"), appDataDir());
    }

    static Path cacheDirFor(String osName, String userHome, Path appDataDir) {
        String os = osName == null ? "" : osName.toLowerCase();
        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Caches", APP_NAME);
        }
        return appDataDir.resolve("cache");
    }

    public static Path cookieFile() {
        return appDataDir().resolve("NCMCookie.txt");
    }

    public static Path preferencesFile() {
        return appDataDir().resolve("preferences.json");
    }

    public static Path playbackFile() {
        return appDataDir().resolve("playback.json");
    }

    public static Path musicCacheDir() {
        return cacheDir().resolve("MusicCache");
    }

    public static Path downloadDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String windowsMusic = null;
        if (os.startsWith("windows")) {
            try {
                windowsMusic = Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Music);
            } catch (RuntimeException | LinkageError ignored) {
                // Fall back to the user's Music folder if the Shell API is unavailable.
            }
        }
        return downloadDirFor(os, System.getProperty("user.home"), windowsMusic, System.getenv("XDG_CONFIG_HOME"));
    }

    static Path downloadDirFor(String osName, String userHome, String windowsMusic, String xdgConfigHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path home = Path.of(userHome);
        Path music = home.resolve("Music");
        if (os.startsWith("windows")) {
            if (windowsMusic != null && !windowsMusic.isBlank()) music = Path.of(windowsMusic);
        } else if (!os.contains("mac") && !os.equals("darwin")) {
            music = xdgMusicDir(home, xdgConfigHome, music);
        }
        return music.resolve(APP_NAME);
    }

    private static Path xdgMusicDir(Path home, String xdgConfigHome, Path fallback) {
        try {
            Path config = home.resolve(".config");
            if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
                Path configured = Path.of(xdgConfigHome);
                if (configured.isAbsolute()) config = configured;
            }
            for (String line : Files.readAllLines(config.resolve("user-dirs.dirs"))) {
                var match = XDG_MUSIC.matcher(line);
                if (!match.matches()) continue;
                String value = match.group(1);
                // Expand only the home prefix, never evaluate shell expressions in the file.
                if (value.equals("$HOME") || value.startsWith("$HOME/"))
                    value = home + value.substring(5);
                else if (value.equals("${HOME}") || value.startsWith("${HOME}/"))
                    value = home + value.substring(7);
                value = value.replaceAll("\\\\([\\\\\"$`])", "$1");
                Path directory = Path.of(value);
                if (directory.isAbsolute()) return directory.normalize();
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            // Missing or malformed desktop configuration must not prevent startup.
        }
        return fallback;
    }

    static Path legacyDownloadDir() {
        return Path.of(System.getProperty("user.home"), "Downloads", APP_NAME);
    }

    public static Path nativeDir() {
        return cacheDir().resolve("natives");
    }
}
