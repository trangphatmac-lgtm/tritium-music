package tritium.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class DesktopCookieStore {
    private DesktopCookieStore() {
    }

    public static String load(Path cookieFile) throws IOException {
        if (!Files.exists(cookieFile)) {
            return "";
        }

        List<String> cookieLines = Files.readAllLines(cookieFile, StandardCharsets.UTF_8);
        if (cookieLines.isEmpty()) {
            return "";
        }
        return cookieLines.getFirst().trim();
    }

    public static void save(Path cookieFile, String cookie) throws IOException {
        if (cookie == null || cookie.isBlank()) {
            return;
        }

        Path parent = cookieFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(cookieFile, cookie.trim(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
