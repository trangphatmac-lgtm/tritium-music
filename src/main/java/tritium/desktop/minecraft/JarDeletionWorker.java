package tritium.desktop.minecraft;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Standalone JDK-only helper, copied out of the running JAR before launch. */
public final class JarDeletionWorker {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(JarDeletionWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(directory) || !directory.getFileName().toString().startsWith("tritium-jar-delete-"))
            throw new IllegalStateException("Worker must run from its private temporary directory");
        Path ownClass = directory.resolve("tritium/desktop/minecraft/JarDeletionWorker.class");
        try {
            if (args.length != 4) throw new IllegalArgumentException("Expected parent PID, JAR, size and timestamp");
            long pid = Long.parseLong(args[0]), size = Long.parseLong(args[2]), modified = Long.parseLong(args[3]);
            Path jar = Path.of(args[1]);
            if (!jar.isAbsolute() || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                throw new IllegalArgumentException("Expected an absolute JAR path");
            var parent = ProcessHandle.of(pid);
            System.out.println("READY"); System.out.flush();
            if (parent.isPresent()) parent.get().onExit().join();
            // Windows can briefly keep the file locked after the parent's process has exited.
            for (int attempt = 0; attempt < 120; attempt++) {
                if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) return;
                BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() != size || attributes.lastModifiedTime().toMillis() != modified)
                    throw new IllegalStateException("Running JAR was replaced; leaving the new file intact");
                try { Files.delete(jar); return; }
                catch (java.io.IOException locked) {
                    if (attempt == 119) throw locked;
                    Thread.sleep(250);
                }
            }
        } finally {
            // Only this helper's class and its four empty temporary directories are removed.
            Path path = ownClass;
            for (int i = 0; i < 5; i++, path = path.getParent()) {
                try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { break; }
            }
        }
    }
    private JarDeletionWorker() { }
}
