package tritium.desktop.minecraft;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** Stable within one OS boot, independent of the application's launch time. */
final class SystemBootId {
    private enum Property { LASTBOOTUPTIME }

    static String read() throws IOException {
        if (Platform.isWindows()) return windows();
        if (Platform.isMac()) {
            Process process = new ProcessBuilder("/usr/sbin/sysctl", "-n", "kern.bootsessionuuid")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Boot ID query timed out");
                String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0 || value.isEmpty()) throw new IOException("Boot ID unavailable");
                return "mac:" + value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IOException(e);
            } finally { process.destroy(); }
        }
        if (Platform.isLinux()) return "linux:" + Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        throw new IOException("Boot ID unsupported on this OS");
    }
    private static String windows() throws IOException {
        int initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).intValue();
        boolean owned = initialized >= 0;
        if (!owned && initialized != 0x80010106) throw new IOException("Cannot initialize boot time query");
        try {
            // Win32_OperatingSystem.LastBootUpTime is an OS timestamp, not a wall-clock/uptime estimate.
            var rows = new WbemcliUtil.WmiQuery<>("Win32_OperatingSystem", Property.class).execute(3000);
            if (rows.getResultCount() > 0 && rows.getValue(Property.LASTBOOTUPTIME, 0) instanceof String value && !value.isBlank())
                return windowsBootId(value);
            throw new IOException("Boot time unavailable");
        } catch (Exception failure) { throw new IOException("Cannot read Windows boot time", failure); }
        finally { if (owned) Ole32.INSTANCE.CoUninitialize(); }
    }
    static String windowsBootId(String value) {
        // WMI uses a local DMTF timestamp with an offset in minutes. Normalize it so
        // changing time zones does not look like a system reboot.
        LocalDateTime time = LocalDateTime.parse(value.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .withNano(Integer.parseInt(value.substring(15, 21)) * 1000);
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(Integer.parseInt(value.substring(21)) * 60);
        return "windows:" + time.toInstant(offset);
    }
    private SystemBootId() { }
}
