package tritium.desktop;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public final class DesktopNativeLoader {
    private DesktopNativeLoader() {
    }

    public static void configureLibraryPath() {
        NativeBundle bundle = detectNativeBundle();
        Path nativeDir = AppPaths.nativeDir().resolve(bundle.platform());

        try {
            Files.createDirectories(nativeDir);
            for (String nativeJar : bundle.nativeJars()) {
                extractNativeJar(bundle.platform(), nativeJar, nativeDir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create native library directory", e);
        }

        String nativePath = nativeDir.toAbsolutePath().toString();
        System.setProperty("org.lwjgl.librarypath", nativePath);
        System.setProperty("net.java.games.input.librarypath", nativePath);
        System.setProperty("java.library.path", nativePath);
    }

    private static NativeBundle detectNativeBundle() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return new NativeBundle("windows-x64", List.of(
                    "lwjgl-platform-2.9.3-natives-windows.jar",
                    "jinput-platform-2.0.5-natives-windows.jar"
            ));
        }

        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return new NativeBundle("macos-arm64", List.of(
                    "lwjgl-platform-2.9.4-nightly-20150209-natives-osx-arm64.jar"
            ));
        }

        throw new IllegalStateException("Unsupported desktop native platform: os=" + os + ", arch=" + arch);
    }

    private static void extractNativeJar(String platform, String nativeJar, Path nativeDir) throws IOException {
        String resource = "/tritium/natives/" + platform + "/" + nativeJar;
        try (InputStream inputStream = DesktopNativeLoader.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IOException("Missing bundled native resource: " + resource);
            }

            try (JarInputStream jarInputStream = new JarInputStream(inputStream)) {
                JarEntry entry;
                while ((entry = jarInputStream.getNextJarEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    Path fileName = Path.of(entry.getName()).getFileName();
                    if (fileName == null) {
                        continue;
                    }

                    Path target = nativeDir.resolve(fileName.toString());
                    Files.copy(jarInputStream, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setReadable(true, false);
                    target.toFile().setExecutable(true, false);
                }
            }
        }
    }

    private record NativeBundle(String platform, List<String> nativeJars) {
    }
}
