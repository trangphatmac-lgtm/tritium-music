package tritium.minecraft.protocol;

import java.util.Properties;

/** Bounded, session-correlated startup failures, available even before IPC can connect. */
public final class AgentDiagnostics {
    public static final String PROPERTY = "tritium.minecraft.agent.startupError";
    public static void clear() { System.clearProperty(PROPERTY); }
    public static void report(String options, Throwable failure) {
        String session = session(options); if (session.isEmpty()) return;
        StringBuilder detail = new StringBuilder(); Throwable previous = null;
        for (int depth = 0; failure != null && failure != previous && depth < 8; depth++) {
            if (depth != 0) detail.append("\nCaused by: ");
            detail.append(failure.getClass().getName()).append(": ").append(String.valueOf(failure.getMessage()));
            StackTraceElement[] stack = failure.getStackTrace();
            if (stack.length > 0) detail.append("\n  at ").append(stack[0]);
            previous = failure; failure = failure.getCause();
        }
        String text = detail.toString().replace(session, "[session]");
        System.setProperty(PROPERTY, session + "\n" + text.substring(0, Math.min(6000, text.length())));
    }
    public static String read(Properties properties, String options) {
        String session = session(options), report = properties.getProperty(PROPERTY, "");
        return !session.isEmpty() && report.startsWith(session + "\n") ? report.substring(session.length() + 1) : "";
    }
    private static String session(String options) {
        if (options == null) return "";
        String[] parts = options.split(":", -1);
        return parts.length == 3 && parts[2].matches("[a-f0-9]{64}") ? parts[2] : "";
    }
    private AgentDiagnostics() { }
}
