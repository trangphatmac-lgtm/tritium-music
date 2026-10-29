package tritium.minecraft.runtime.util;

/** Scope for replacement packets that must not be intercepted again by any injected module. */
public final class PacketBypass {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<Boolean>();
    public static boolean active() { return Boolean.TRUE.equals(ACTIVE.get()); }
    public static void run(Runnable send) {
        Boolean previous = ACTIVE.get(); ACTIVE.set(true);
        try { send.run(); } finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    private PacketBypass() { }
}
