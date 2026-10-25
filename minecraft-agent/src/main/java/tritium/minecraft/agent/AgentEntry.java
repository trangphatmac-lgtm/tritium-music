package tritium.minecraft.agent;

import java.lang.instrument.Instrumentation;
import tritium.minecraft.protocol.AgentDiagnostics;

/** Keep the manifest entry small enough to report failures linking/initializing the actual agent. */
public final class AgentEntry {
    public static synchronized void agentmain(String options, Instrumentation instrumentation) throws Exception {
        try { AgentDiagnostics.clear(); } catch (SecurityException ignored) { }
        try { HudAgent.agentmain(options, instrumentation); }
        catch (Throwable failure) {
            try { AgentDiagnostics.report(options, failure); } catch (Throwable ignored) { }
            if (failure instanceof Exception) throw (Exception)failure;
            if (failure instanceof Error) throw (Error)failure;
            throw new IllegalStateException(failure);
        }
    }
    private AgentEntry() { }
}
