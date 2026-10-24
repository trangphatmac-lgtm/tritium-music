package tritium.desktop.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WindowsAppIdentityTest {
    @Test void shortcutLaunchesTheSpecificJarWithSpacesAndUnicode() {
        Path java = Path.of("JDK with spaces", "bin", "javaw.exe");
        Path jar = Path.of("音乐播放器", "Tritium Music.jar");
        var launch = WindowsAppIdentity.jarLaunch(java, jar);
        assertEquals(java.toAbsolutePath(), launch.executable());
        assertEquals("-jar \"" + jar.toAbsolutePath() + "\"", launch.arguments());
        assertEquals(jar.toAbsolutePath().getParent(), launch.directory());
    }

    @Test void quotesWindowsArgumentsWithoutLosingBackslashes() {
        assertEquals("\"\"", WindowsAppIdentity.quote(""));
        assertEquals("\"C:\\Music Files\\\\\"", WindowsAppIdentity.quote("C:\\Music Files\\"));
        assertEquals("\"a\\\"b\"", WindowsAppIdentity.quote("a\"b"));
        assertEquals("\"C:\\Music\\song.jar\"", WindowsAppIdentity.quote("C:\\Music\\song.jar"));
    }
}
