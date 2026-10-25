package tritium.desktop.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsJvmProcessesTest {
    @Test void parsesQuotedWindowsLaunchArgumentsAndExactJvmFlags() {
        String[] args = WindowsJvmProcesses.parseArguments("\"C:\\Program Files\\Zulu\\bin\\javaw.exe\" \"-Dpath=C:\\Game Data\" -XX:+DisableAttachMechanism -XX:-DisableAttachMechanism net.minecraft.launchwrapper.Launch --accessToken private-test-token");
        assertEquals("C:\\Program Files\\Zulu\\bin\\javaw.exe", args[0]);
        assertEquals("-Dpath=C:\\Game Data", args[1]);
        assertFalse(MinecraftConnectionManager.flagEnabled(args,"DisableAttachMechanism",false));
        assertEquals("Minecraft / Forge", MinecraftConnectionManager.clientName(String.join(" ",args)));
        assertFalse(new WindowsJvmProcesses.JvmProcess(42,args).toString().contains("private-test-token"));
    }
    @Test void readsCurrentProcessWithoutDependingOnProcessHandleArguments() throws Exception {
        long pid = ProcessHandle.current().pid();
        var process = WindowsJvmProcesses.list().stream().filter(p -> p.pid()==pid).findFirst().orElseThrow();
        assertTrue(process.arguments().length > 1);
        assertArrayEquals(process.arguments(), WindowsJvmProcesses.arguments(pid));
    }
}
