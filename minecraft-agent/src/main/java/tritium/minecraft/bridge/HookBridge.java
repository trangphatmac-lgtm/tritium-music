package tritium.minecraft.bridge;

import java.util.function.*;

/** The only class appended to bootstrap search. Never references a game or agent type. */
public final class HookBridge {
    public static volatile IntPredicate callback;
    public static volatile BiPredicate<Object,Object> packetCallback;
    public static volatile Consumer<Object> velocityCallback;
    public static volatile Predicate<Object> blockingCallback;
    public static volatile Predicate<Object> receiveCallback;
    public static boolean receive(Object packet) {
        Predicate<Object> current = receiveCallback;
        try { return current != null && current.test(packet); } catch (Throwable ignored) { return false; }
    }
    public static boolean packet(Object manager, Object packet) {
        BiPredicate<Object,Object> current = packetCallback;
        try { return current != null && current.test(manager, packet); } catch (Throwable ignored) { return false; }
    }
    public static void velocity(Object packet) {
        Consumer<Object> current = velocityCallback;
        try { if (current != null) current.accept(packet); } catch (Throwable ignored) { }
    }
    public static boolean blocking(Object player) {
        Predicate<Object> current = blockingCallback;
        try { return current != null && current.test(player); } catch (Throwable ignored) { return false; }
    }
    public static boolean mouseState(boolean down) { return down && !event(10); }
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
