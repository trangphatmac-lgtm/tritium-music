package tritium.desktop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopNativeLoaderTest {
    @Test
    void detectsLinuxX64NativeBundle() {
        DesktopNativeLoader.NativeBundle bundle =
                DesktopNativeLoader.detectNativeBundle("Linux", "amd64");

        assertEquals("linux-x64", bundle.platform());
        assertEquals(List.of("lwjgl-platform-2.9.3-natives-linux.jar"), bundle.nativeJars());
    }

    @Test
    void acceptsLinuxX8664ArchitectureAlias() {
        assertEquals(
                "linux-x64",
                DesktopNativeLoader.detectNativeBundle("linux", "x86_64").platform()
        );
    }

    @Test
    void rejectsUnsupportedLinuxArchitecture() {
        assertThrows(
                IllegalStateException.class,
                () -> DesktopNativeLoader.detectNativeBundle("Linux", "aarch64")
        );
    }
}
