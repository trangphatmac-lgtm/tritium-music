package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationJarDeletionTest {
    @TempDir Path directory;

    @Test void resolvesOnlyAJarAndNeverAClassDirectory() throws Exception {
        assertNull(ApplicationJarDeletion.runningJar(directory.toUri().toURL()));
        Path jar = Files.writeString(directory.resolve("播放器 with spaces.JAR"), "fixture");
        assertEquals(jar.toRealPath(), ApplicationJarDeletion.runningJar(jar.toUri().toURL()));
        Path config = Files.writeString(directory.resolve("preferences.json"), "{}");
        assertNull(ApplicationJarDeletion.runningJar(config.toUri().toURL()));
        assertNull(ApplicationJarDeletion.runningJar(null));
    }

    @Test void helperWaitsForExitAndDeletesOnlyTheJar() throws Exception {
        Path jar = Files.writeString(directory.resolve("播放器 with spaces.jar"), "disposable JAR fixture");
        Path config = Files.writeString(directory.resolve("preferences.json"), "settings");
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path audio = Files.writeString(cache.resolve("song.dat"), "cached audio");
        Process parent = parent(); Process helper = null;
        try {
            helper = ApplicationJarDeletion.start(jar, parent.pid());
            assertTrue(parent.isAlive()); assertTrue(helper.isAlive()); assertTrue(Files.exists(jar));
            parent.getOutputStream().close(); assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, helper.exitValue());
            assertFalse(Files.exists(jar));
            assertEquals("settings", Files.readString(config)); assertEquals("cached audio", Files.readString(audio));
        } finally { parent.destroyForcibly(); if (helper != null) helper.destroyForcibly(); }
    }

    @Test void replacedJarIsNotDeleted() throws Exception {
        Path jar = Files.writeString(directory.resolve("app.jar"), "old");
        Process parent = parent(); Process helper = null;
        try {
            helper = ApplicationJarDeletion.start(jar, parent.pid());
            Files.writeString(jar, "replacement application");
            parent.getOutputStream().close(); assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS)); assertNotEquals(0, helper.exitValue());
            assertEquals("replacement application", Files.readString(jar));
        } finally { parent.destroyForcibly(); if (helper != null) helper.destroyForcibly(); }
    }

    private Process parent() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
        Path classes = Path.of(Parent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ProcessBuilder(java, "-cp", classes.toString(), Parent.class.getName()).start();
    }
    public static final class Parent {
        public static void main(String[] args) throws Exception { while (System.in.read() != -1) { } }
    }
}
