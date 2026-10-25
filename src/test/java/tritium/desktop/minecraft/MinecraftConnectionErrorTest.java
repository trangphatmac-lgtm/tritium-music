package tritium.desktop.minecraft;

import com.sun.tools.attach.AttachNotSupportedException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftConnectionErrorTest {
    @Test void java25Java8SuccessResponseDoesNotHideRealFailures() {
        assertTrue(AttachLoader.isJava8SuccessResponse("0", "1.8"));
        assertTrue(AttachLoader.isJava8SuccessResponse("Failed to load agent library: 0", "1.8"));
        assertFalse(AttachLoader.isJava8SuccessResponse("Failed to load agent library: 102", "1.8"));
        assertFalse(AttachLoader.isJava8SuccessResponse("Failed to load agent library: 0", "25"));
        assertFalse(AttachLoader.isJava8SuccessResponse("other error 0", "1.8"));
        String error = MinecraftConnectionManager.explainAttach(new com.sun.tools.attach.AgentLoadException("Failed to load agent library: 102"));
        assertTrue(error.contains("初始化失败")); assertFalse(error.contains("-XX:+DisableAttachMechanism"));
    }

    @Test void startupDiagnosticsAreBoundedAndBelongOnlyToTheirSession() {
        String property = tritium.minecraft.protocol.AgentDiagnostics.PROPERTY;
        String previous = System.getProperty(property);
        String token = "a".repeat(64), options = "1:12345:" + token;
        try {
            tritium.minecraft.protocol.AgentDiagnostics.report(options, new IllegalStateException("wrapper " + token, new ClassNotFoundException("missing.Runtime")));
            String detail = tritium.minecraft.protocol.AgentDiagnostics.read(System.getProperties(), options);
            assertTrue(detail.contains("missing.Runtime")); assertFalse(detail.contains(token));
            assertTrue(tritium.minecraft.protocol.AgentDiagnostics.read(System.getProperties(), "1:12345:" + "b".repeat(64)).isEmpty());
            assertTrue(MinecraftConnectionManager.explainAttach(new AttachLoader.StartupException(detail)).contains("missing.Runtime"));
            tritium.minecraft.protocol.AgentDiagnostics.clear();
            assertTrue(tritium.minecraft.protocol.AgentDiagnostics.read(System.getProperties(), options).isEmpty());
        } finally { if (previous == null) System.clearProperty(property); else System.setProperty(property, previous); }
    }

    @Test void runtimeErrorsDoNotRecommendChangingAttachPermissions() {
        String error = MinecraftConnectionManager.explain(new RuntimeException(new IllegalArgumentException("NaN")));
        assertEquals("IllegalArgumentException: NaN", error);
        assertFalse(MinecraftConnectionManager.explain(new NoClassDefFoundError("some/OtherClass")).contains("jdk.attach"));
    }

    @Test void attachFailuresStillProvideRelevantRuntimeHelp() {
        assertTrue(MinecraftConnectionManager.explain(new ClassNotFoundException("com.sun.tools.attach.VirtualMachine")).contains("jdk.attach"));
        String error = MinecraftConnectionManager.explainAttach(new AttachNotSupportedException("Attach disabled"));
        assertTrue(error.contains("-XX:+DisableAttachMechanism"));
        assertTrue(error.contains("同一系统用户"));
        assertFalse(error.contains("同一 Windows 用户"));
    }
}
