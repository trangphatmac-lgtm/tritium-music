package tritium.minecraft.bridge;

import java.util.function.IntPredicate;

/** The only class appended to bootstrap search. Never references a game or agent type. */
public final class HookBridge {
    public static volatile IntPredicate callback;
    /** Set and consumed synchronously by the game render thread. */
    public static volatile float partialTicks;
    public static boolean event(int kind) {
        IntPredicate current = callback;
        if (current == null) return false;
        try { return current.test(kind); }
        catch (Throwable ignored) { return false; }
    }
    public static void world(float partial) {
        partialTicks = partial;
        event(3);
    }
    private HookBridge() { }
}
