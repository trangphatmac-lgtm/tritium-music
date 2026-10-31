package tritium.minecraft.bridge;

import java.util.function.*;

/** The only class appended to bootstrap search. Never references a game or agent type. */
public final class HookBridge {
    public static volatile IntPredicate callback;
    public static volatile BiPredicate<Object,Object> packetCallback;
    public static volatile Consumer<Object> velocityCallback;
    public static volatile Predicate<Object> blockingCallback;
    public static volatile Predicate<Object> receiveCallback;
    public static volatile BiPredicate<Object,Object> incomingCallback;
    public static volatile BiFunction<Integer,Object[],Object> moduleCallback;
    public static boolean incoming(Object handler, Object packet) {
        BiPredicate<Object,Object> current = incomingCallback;
        try { return current != null ? current.test(handler, packet) : receive(packet); }
        catch (Throwable ignored) { return false; }
    }
    private static Object module(int kind, Object... args) {
        BiFunction<Integer,Object[],Object> current = moduleCallback;
        try { return current == null ? null : current.apply(kind, args); }
        catch (Throwable ignored) { return null; }
    }
    public static boolean edge(boolean vanilla, Object entity) {
        Object result = module(0, entity, vanilla); return result instanceof Boolean ? (Boolean) result : vanilla;
    }
    public static float movementYaw(Object entity, float vanilla) {
        Object result = module(1, entity, vanilla); return result instanceof Number ? ((Number) result).floatValue() : vanilla;
    }
    public static void attack(Object player, Object target) { module(2, player, target); }
    public static float hurtCamera(float vanilla) {
        Object result = module(3, vanilla); return result instanceof Number ? ((Number) result).floatValue() : vanilla;
    }
    public static boolean cameraClip() { return !Boolean.FALSE.equals(module(4)); }
    public static void playerPre(Object player) { module(5, player); }
    public static void playerPost(Object player) { module(6, player); }
    public static boolean suppressName(Object entity, double x, double y, double z) { return Boolean.TRUE.equals(module(7, entity, x, y, z)); }
    public static Object itemToRender(Object original) {
        // null is a meaningful empty hand, so retain the original only when detached.
        BiFunction<Integer,Object[],Object> current = moduleCallback;
        try { return current == null ? original : current.apply(8, new Object[]{original}); }
        catch (Throwable ignored) { return original; }
    }
    public static float animationProgress(float vanilla) {
        Object result = module(9, vanilla); return result instanceof Number ? ((Number) result).floatValue() : vanilla;
    }
    public static void modelVisibility(Object player, Object renderer) { module(10, player, renderer); }
    public static void entityJoined(Object entity) { module(11, entity); }
    public static float headPitch(Object entity, float vanilla, float partial) {
        Object result = module(12, entity, vanilla, partial); return result instanceof Number ? ((Number) result).floatValue() : vanilla;
    }
    /** Input fields have been read, but vanilla's sneak multiplier has not been applied. */
    public static void preInput(Object input) { module(13, input); }
    public static boolean rightClick() { return Boolean.TRUE.equals(module(14)); }
    public static boolean slotScroll(Object inventory, int wheelDelta) { return Boolean.TRUE.equals(module(15, inventory, wheelDelta)); }
    public static void clientRotation() { module(16); }
    public static void chat(Object component, int type) { module(17, component, type); }
    public static void beforeMouseOver() { module(18); }
    public static void afterMouseOver() { module(19); }
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
    public static boolean mouseState(boolean down) { boolean cancel = event(10); return down && !cancel; }
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
