package tritium.desktop.media;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Small Objective-C bridge, loaded only by the macOS backend. */
final class MacObjectiveC {
    static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    static final NativeLibrary SYSTEM = NativeLibrary.getInstance("System");
    static final NativeLibrary FOUNDATION = NativeLibrary.getInstance("Foundation");
    static final NativeLibrary APPKIT = NativeLibrary.getInstance("AppKit");
    static final NativeLibrary MEDIA = NativeLibrary.getInstance("MediaPlayer");
    private static final Function SEND = OBJC.getFunction("objc_msgSend");
    private static final Map<String, Pointer> SELECTORS = new ConcurrentHashMap<>();

    public interface MainAction extends Callback { void invoke(Pointer context); }

    static Pointer selector(String name) {
        return SELECTORS.computeIfAbsent(name, key -> OBJC.getFunction("sel_registerName").invokePointer(new Object[]{key}));
    }

    static Pointer type(String name) {
        Pointer value = OBJC.getFunction("objc_getClass").invokePointer(new Object[]{name});
        if (value == null) throw new IllegalStateException("Objective-C class unavailable: " + name);
        return value;
    }

    private static Object[] args(Pointer object, String selector, Object... values) {
        Object[] args = new Object[values.length + 2];
        args[0] = object;
        args[1] = selector(selector);
        System.arraycopy(values, 0, args, 2, values.length);
        return args;
    }

    static Pointer object(Pointer receiver, String selector, Object... values) {
        return SEND.invokePointer(args(receiver, selector, values));
    }

    static void send(Pointer receiver, String selector, Object... values) {
        SEND.invokeVoid(args(receiver, selector, values));
    }

    static long integer(Pointer receiver, String selector, Object... values) {
        return SEND.invokeLong(args(receiver, selector, values));
    }

    static double number(Pointer receiver, String selector) {
        return SEND.invokeDouble(args(receiver, selector));
    }

    static final class NativeBuffer extends Memory implements AutoCloseable {
        NativeBuffer(long size) { super(size); }
        @Override public void close() { dispose(); }
    }

    static NativeBuffer utf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        NativeBuffer data = new NativeBuffer(bytes.length + 1L);
        data.write(0, bytes, 0, bytes.length);
        data.setByte(bytes.length, (byte) 0);
        return data;
    }

    static Pointer string(String value) {
        try (NativeBuffer bytes = utf8(value)) { return object(type("NSString"), "stringWithUTF8String:", bytes); }
    }

    static Pointer key(String name) { return MEDIA.getGlobalVariableAddress(name).getPointer(0); }
    static void release(Pointer object) { if (object != null) send(object, "release"); }

    static void onMain(Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MainAction action = ignored -> {
            Pointer pool = object(type("NSAutoreleasePool"), "new");
            try { operation.run(); }
            catch (Throwable error) { failure.set(error); }
            finally { release(pool); }
        };
        if ((Byte) SEND.invoke(Byte.class, args(type("NSThread"), "isMainThread")) != 0) action.invoke(null);
        else SYSTEM.getFunction("dispatch_sync_f").invokeVoid(new Object[]{
                SYSTEM.getGlobalVariableAddress("_dispatch_main_q"), null, action});
        if (failure.get() != null) throw new IllegalStateException("macOS media operation failed", failure.get());
    }
}
