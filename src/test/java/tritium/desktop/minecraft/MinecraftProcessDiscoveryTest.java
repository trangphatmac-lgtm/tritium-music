package tritium.desktop.minecraft;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftProcessDiscoveryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "/usr/lib/jvm/java-21-openjdk/bin/java",
            "/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home/bin/java",
            "/home/player/Game Runtimes/jre/bin/java",
            "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
            "C:\\Game Runtimes\\jre\\bin\\JAVAW.EXE",
            "java"
    })
    void recognizesJvmExecutablesAcrossDesktopPlatforms(String command) {
        assertTrue(MinecraftConnectionManager.isJavaExecutable(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/usr/bin/javac", "/usr/bin/notjava", "C:\\bin\\notjava.exe", "/opt/java/launcher", "/usr/bin/java-helper"})
    void ignoresNonJvmExecutables(String command) {
        assertFalse(MinecraftConnectionManager.isJavaExecutable(command));
    }
}
