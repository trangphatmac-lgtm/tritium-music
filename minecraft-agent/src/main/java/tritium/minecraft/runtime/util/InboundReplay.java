package tritium.minecraft.runtime.util;

/** Nested inbound replay retains the owner so velocity is never scaled twice. */
public final class InboundReplay {
    private static final ThreadLocal<String> OWNER = new ThreadLocal<String>();

    public static boolean active() {
        return OWNER.get() != null;
    }

    public static boolean ownedBy(String owner) {
        return owner.equals(OWNER.get());
    }

    public static void run(String owner, Runnable task) {
        String previous = OWNER.get();
        OWNER.set(owner);
        try {
            task.run();
        } finally {
            if (previous == null) OWNER.remove();
            else OWNER.set(previous);
        }
    }

    private InboundReplay() {}
}
