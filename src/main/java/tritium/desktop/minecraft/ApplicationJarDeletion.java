package tritium.desktop.minecraft;

import tritium.desktop.TritiumMusicDesktopApp;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ApplicationJarDeletion {
    static void schedule() throws IOException {
        var source = TritiumMusicDesktopApp.class.getProtectionDomain().getCodeSource();
        Path jar = runningJar(source == null ? null : source.getLocation());
        if (jar == null) {
            System.err.println("Self Destruct: application is not running from a JAR; closing without deleting files.");
            return;
        }
        start(jar, ProcessHandle.current().pid());
    }
    static Path runningJar(URL location) throws IOException {
        if (location == null || !"file".equals(location.getProtocol())) return null;
        try {
            Path file = Path.of(location.toURI()).toRealPath();
            return Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") ? file : null;
        } catch (URISyntaxException e) { throw new IOException(e); }
    }
    static Process start(Path jar, long parentPid) throws IOException {
        Path directory = Files.createTempDirectory("tritium-jar-delete-");
        String resource = "tritium/desktop/minecraft/JarDeletionWorker.class";
        Path worker = directory.resolve(resource);
        Process process = null;
        try {
            Files.createDirectories(worker.getParent());
            try (var in = JarDeletionWorker.class.getResourceAsStream("/" + resource)) {
                if (in == null) throw new IOException("JAR deletion helper missing");
                Files.copy(in, worker);
            }
            BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
            String executable = System.getProperty("os.name", "").startsWith("Windows") ? "javaw.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            process = new ProcessBuilder(java.toString(), "-cp", directory.toString(), JarDeletionWorker.class.getName(),
                    Long.toString(parentPid), jar.toAbsolutePath().toString(), Long.toString(attributes.size()),
                    Long.toString(attributes.lastModifiedTime().toMillis()))
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            Process helper = process;
            String ready = CompletableFuture.supplyAsync(() -> {
                try { return helper.inputReader().readLine(); }
                catch (IOException e) { return null; }
            }).get(5, TimeUnit.SECONDS);
            if (!"READY".equals(ready)) throw new IOException("JAR deletion helper failed to start");
            return process;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            Path path = worker;
            for (int i = 0; i < 5; i++, path = path.getParent()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
            throw new IOException("Cannot schedule JAR deletion", e);
        }
    }
    private ApplicationJarDeletion() { }
}
