package tritium.desktop;

import java.nio.file.Path;

public final class AppPaths {
    private static final String APP_NAME = "Tritium Music";

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

    public static Path musicCacheDir() {
        return cacheDir().resolve("MusicCache");
    }

    public static Path nativeDir() {
        return cacheDir().resolve("natives");
    }
}
