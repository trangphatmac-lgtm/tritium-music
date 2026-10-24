package tritium.desktop.media;

import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Minimal WinRT ABI support using the project's existing JNA dependency. */
final class WinRt {
    private WinRt() { }

    interface Runtime extends StdCallLibrary {
        Runtime API = Native.load("combase", Runtime.class);
        int RoInitialize(int type);
        void RoUninitialize();
        int RoGetActivationFactory(Pointer classId, Guid.GUID iid, PointerByReference result);
        int RoActivateInstance(Pointer classId, PointerByReference result);
        int WindowsCreateString(WString text, int length, PointerByReference result);
        int WindowsDeleteString(Pointer text);
    }

    static void check(int result) {
        if (result < 0) throw new IllegalStateException("WinRT HRESULT 0x" + Integer.toHexString(result));
    }

    static int invoke(Pointer object, int slot, Object... args) {
        Object[] parameters = new Object[args.length + 1];
        parameters[0] = object;
        System.arraycopy(args, 0, parameters, 1, args.length);
        return Function.getFunction(object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE),
                Function.ALT_CONVENTION).invokeInt(parameters);
    }

    static ComObject factory(String name, String iid) {
        try (HString text = new HString(name)) {
            PointerByReference result = new PointerByReference();
            check(Runtime.API.RoGetActivationFactory(text.pointer, new Guid.GUID(iid), result));
            return new ComObject(result.getValue());
        }
    }

    static ComObject activate(String name) {
        try (HString text = new HString(name)) {
            PointerByReference result = new PointerByReference();
            check(Runtime.API.RoActivateInstance(text.pointer, result));
            return new ComObject(result.getValue());
        }
    }

    static final class HString implements AutoCloseable {
        final Pointer pointer;
        HString(String value) {
            PointerByReference result = new PointerByReference();
            check(Runtime.API.WindowsCreateString(new WString(value), value.length(), result));
            pointer = result.getValue();
        }
        @Override public void close() { Runtime.API.WindowsDeleteString(pointer); }
    }

    static final class ComObject implements AutoCloseable {
        final Pointer pointer;
        private boolean closed;
        ComObject(Pointer pointer) { this.pointer = pointer; }
        void call(int slot, Object... args) { check(invoke(pointer, slot, args)); }
        ComObject object(int slot, Object... args) {
            PointerByReference result = new PointerByReference();
            Object[] parameters = java.util.Arrays.copyOf(args, args.length + 1);
            parameters[args.length] = result;
            call(slot, parameters);
            return new ComObject(result.getValue());
        }
        ComObject query(String iid) { return object(0, new Guid.GUID(iid)); }
        void text(int slot, String value) {
            try (HString text = new HString(value)) { call(slot, text.pointer); }
        }
        @Override public void close() {
            if (!closed) { closed = true; invoke(pointer, 2); }
        }
    }

    /** IUnknown + ITypedEventHandler.Invoke. WinRT may call and release it on arbitrary threads. */
    static final class EventHandler implements AutoCloseable {
        // Native references alone cannot keep JNA callbacks alive. Retain until the last Release.
        private static final Set<EventHandler> LIVE = ConcurrentHashMap.newKeySet();
        private static final Guid.GUID IUNKNOWN = new Guid.GUID("00000000-0000-0000-c000-000000000046");
        private static final Guid.GUID IAGILE = new Guid.GUID("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90");
        private final AtomicInteger references = new AtomicInteger(1);
        private final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
        final Memory pointer = new Memory(Native.POINTER_SIZE);
        private volatile boolean closed;

        interface Query extends StdCallLibrary.StdCallCallback {
            int invoke(Pointer self, Pointer iid, PointerByReference result);
        }
        interface Ref extends StdCallLibrary.StdCallCallback { int invoke(Pointer self); }
        interface Invoke extends StdCallLibrary.StdCallCallback {
            int invoke(Pointer self, Pointer sender, Pointer args);
        }

        private final Query query;
        private final Ref addRef;
        private final Ref release;
        private final Invoke handler;

        EventHandler(String iid, Consumer<Pointer> action) {
            Guid.GUID eventIid = new Guid.GUID(iid);
            query = (self, requested, result) -> {
                Guid.GUID value = new Guid.GUID(requested);
                if (value.equals(IUNKNOWN) || value.equals(IAGILE) || value.equals(eventIid)) {
                    result.setValue(pointer);
                    references.incrementAndGet();
                    return 0;
                }
                result.setValue(null);
                return 0x80004002; // E_NOINTERFACE
            };
            addRef = self -> references.incrementAndGet();
            release = self -> releaseReference();
            handler = (self, sender, args) -> {
                try {
                    if (!closed) action.accept(args);
                    return 0;
                } catch (RuntimeException | LinkageError failure) {
                    // Never unwind a Java exception through a COM callback.
                    return 0x80004005; // E_FAIL
                }
            };
            vtable.setPointer(0, CallbackReference.getFunctionPointer(query));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(handler));
            pointer.setPointer(0, vtable);
            LIVE.add(this);
        }

        private int releaseReference() {
            int count = references.decrementAndGet();
            if (count == 0) LIVE.remove(this);
            return count;
        }

        @Override public void close() {
            if (!closed) { closed = true; releaseReference(); }
        }
    }
}
