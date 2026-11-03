package tritium.desktop;

import java.io.IOException;
import java.nio.file.Path;

/** Run the production cleanup against an isolated playback-test directory. */
public final class CacheCleanupTestSupport {
    private CacheCleanupTestSupport() { }

    public static void clearAtLimit(Path directory, long limit) throws IOException {
        DesktopCacheManager.clearIfLimitReached(directory, limit);
    }
}
