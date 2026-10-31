package tritium.desktop.minecraft;

import tritium.desktop.MinecraftUninjectPreferences;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The marker is UI state, separate from the user's settings and caches. */
final class InjectButtonVisibility {
    private final Path marker;
    private final String bootId;
    private volatile boolean hidden;

    InjectButtonVisibility(Path marker, String bootId) {
        this.marker = marker; this.bootId = bootId;
        try {
            if (Files.isRegularFile(marker) && bootId != null) {
                hidden = bootId.equals(Files.readString(marker));
                if (!hidden) Files.deleteIfExists(marker);
            }
        } catch (IOException e) { report(e); }
    }
    boolean hidden() { return hidden; }
    synchronized void onUninject(String mode) {
        hidden = !MinecraftUninjectPreferences.OFF.equals(mode);
        try {
            if (MinecraftUninjectPreferences.NEXT_REBOOT.equals(mode) && bootId != null) {
                Files.createDirectories(marker.getParent());
                Path temp = Files.createTempFile(marker.getParent(), "minecraft-visibility-", ".tmp");
                try {
                    Files.writeString(temp, bootId);
                    Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temp); }
            } else Files.deleteIfExists(marker);
        } catch (IOException e) { report(e); }
    }
    static InjectButtonVisibility create(Path marker) {
        String bootId = null;
        try { bootId = SystemBootId.read(); }
        catch (IOException | RuntimeException | LinkageError e) {
            System.err.println("Cannot persist Minecraft button visibility until reboot: " + e.getMessage());
        }
        return new InjectButtonVisibility(marker, bootId);
    }
    private static void report(IOException e) {
        System.err.println("Cannot save Minecraft button visibility: " + e.getMessage());
    }
}
