package tritium.desktop.minecraft;

import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only Windows process metadata through the application's existing JNA dependency.
 * No shell, helper process, custom native loader, or Attach operation is involved. */
final class WindowsJvmProcesses {
    private enum Property { PROCESSID, COMMANDLINE }
    record JvmProcess(long pid, String[] arguments) {
        // Launcher arguments can contain credentials. Never expose them in diagnostics or UI.
        @Override public String toString() { return "Java process " + pid; }
    }

    static List<JvmProcess> list() throws IOException {
        return query("Win32_Process WHERE Name = 'java.exe' OR Name = 'javaw.exe'");
    }
    static String[] arguments(long pid) throws IOException {
        return query("Win32_Process WHERE ProcessId = " + pid).stream()
                .filter(p -> p.pid() == pid).findFirst().map(JvmProcess::arguments).orElseGet(() -> new String[0]);
    }
    private static List<JvmProcess> query(String query) throws IOException {
        int initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).intValue();
        boolean owned = initialized >= 0;
        // An existing COM apartment is also usable for this synchronous read-only query.
        if (!owned && initialized != 0x80010106)
            throw new IOException("无法初始化 Windows 进程查询 (HRESULT " + Integer.toHexString(initialized) + ")");
        try {
            var rows = new WbemcliUtil.WmiQuery<>(query, Property.class).execute(3000);
            List<JvmProcess> result = new ArrayList<>();
            for (int i = 0; i < rows.getResultCount(); i++) {
                Object rawPid = rows.getValue(Property.PROCESSID, i);
                Object rawCommand = rows.getValue(Property.COMMANDLINE, i);
                if (!(rawPid instanceof Number number) || !(rawCommand instanceof String command) || command.isBlank()) continue;
                long pid = Integer.toUnsignedLong(number.intValue());
                result.add(new JvmProcess(pid, parseArguments(command)));
            }
            return result;
        } catch (Exception failure) {
            throw new IOException("无法读取 Windows JVM 进程信息，请确认游戏与播放器的用户及权限级别一致。", failure);
        } finally { if (owned) Ole32.INSTANCE.CoUninitialize(); }
    }
    static String[] parseArguments(String command) {
        return command == null || command.isBlank() ? new String[0] : Shell32Util.CommandLineToArgv(command);
    }
    private WindowsJvmProcesses() { }
}
