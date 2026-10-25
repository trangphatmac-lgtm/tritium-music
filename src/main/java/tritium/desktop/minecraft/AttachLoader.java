package tritium.desktop.minecraft;

import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.VirtualMachine;
import tritium.minecraft.protocol.AgentDiagnostics;

final class AttachLoader {
    /** Java 8's bare success code is parsed as an exception by newer Attach clients.
     * Only this exact cross-version case is provisional; the authenticated render READY remains mandatory. */
    static void load(VirtualMachine vm, String jar, String options) throws Exception {
        try { vm.loadAgent(jar, options); }
        catch (Exception response) {
            java.util.Properties properties;
            try { properties = vm.getSystemProperties(); }
            catch (Exception unavailable) { response.addSuppressed(unavailable); throw response; }
            String detail = AgentDiagnostics.read(properties, options);
            if (!detail.isEmpty()) throw new StartupException(detail);
            if (!(response instanceof AgentLoadException) || !isJava8SuccessResponse(response.getMessage(),
                    properties.getProperty("java.specification.version"))) throw response;
        }
    }
    static boolean isJava8SuccessResponse(String message, String targetVersion) {
        return "1.8".equals(targetVersion) && ("0".equals(message) || "Failed to load agent library: 0".equals(message));
    }
    static final class StartupException extends java.io.IOException {
        StartupException(String detail) { super("Minecraft Agent 初始化失败：\n" + detail); }
    }
    private AttachLoader() { }
}
