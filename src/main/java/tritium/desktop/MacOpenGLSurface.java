package tritium.desktop;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.util.concurrent.atomic.AtomicReference;

/** Enables the backing surface, not just LWJGL's reported screen scale. */
final class MacOpenGLSurface {
    private MacOpenGLSurface() {
    }

    public interface MainThreadAction extends Callback {
        void invoke(Pointer ignored);
    }

    static void enableHighResolution() {
        if (!Platform.isMac()) {
            return;
        }

        NativeLibrary objc = NativeLibrary.getInstance("objc");
        Function send = objc.getFunction("objc_msgSend");
        Function registerSelector = objc.getFunction("sel_registerName");
        Pointer contextClass = objc.getFunction("objc_getClass").invokePointer(new Object[]{"NSOpenGLContext"});
        Pointer context = send.invokePointer(new Object[]{contextClass, selector(registerSelector, "currentContext")});
        Pointer view = send.invokePointer(new Object[]{context, selector(registerSelector, "view")});
        if (context == null || view == null) {
            throw new IllegalStateException("LWJGL did not create a macOS OpenGL view");
        }

        // The bundled LWJGL 2 arm64 runtime reports a 2x screen scale while leaving
        // wantsBestResolutionOpenGLSurface disabled. Both AppKit calls must run on
        // the Cocoa main thread; the Java rendering thread is not that thread.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MainThreadAction action = ignored -> {
            try {
                send.invokeVoid(new Object[]{view, selector(registerSelector, "setWantsBestResolutionOpenGLSurface:"), (byte) 1});
                send.invokeVoid(new Object[]{context, selector(registerSelector, "update")});
            } catch (Throwable error) {
                failure.set(error);
            }
        };
        Pointer threadClass = objc.getFunction("objc_getClass").invokePointer(new Object[]{"NSThread"});
        boolean isMainThread = (Byte) send.invoke(Byte.class,
                new Object[]{threadClass, selector(registerSelector, "isMainThread")}) != 0;
        if (isMainThread) {
            action.invoke(null);
        } else {
            NativeLibrary system = NativeLibrary.getInstance("System");
            system.getFunction("dispatch_sync_f").invokeVoid(new Object[]{
                    system.getGlobalVariableAddress("_dispatch_main_q"), null, action});
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Unable to enable the macOS high-resolution surface", failure.get());
        }
    }

    private static Pointer selector(Function registerSelector, String name) {
        return registerSelector.invokePointer(new Object[]{name});
    }
}
