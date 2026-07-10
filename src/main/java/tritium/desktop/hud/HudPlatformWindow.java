package tritium.desktop.hud;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class HudPlatformWindow {
    private static boolean macWarningPrinted;
    private static boolean windowsWarningPrinted;

    private HudPlatformWindow() {
    }

    static boolean apply(Window window, boolean passThrough) {
        window.setAlwaysOnTop(true);
        window.setFocusable(false);
        window.setFocusableWindowState(false);

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return applyWindows(window, passThrough);
        } else if (os.contains("mac")) {
            return applyMac(window, passThrough);
        }
        return !passThrough;
    }

    private static boolean applyWindows(Window window, boolean passThrough) {
        try {
            WinDef.HWND hwnd = new WinDef.HWND(Native.getWindowPointer(window));
            int exStyle = User32Ext.INSTANCE.GetWindowLongW(hwnd, User32Ext.GWL_EXSTYLE);
            exStyle |= User32Ext.WS_EX_LAYERED | User32Ext.WS_EX_TOOLWINDOW | User32Ext.WS_EX_NOACTIVATE;
            if (passThrough) {
                exStyle |= User32Ext.WS_EX_TRANSPARENT;
            } else {
                exStyle &= ~User32Ext.WS_EX_TRANSPARENT;
            }
            User32Ext.INSTANCE.SetWindowLongW(hwnd, User32Ext.GWL_EXSTYLE, exStyle);
            User32Ext.INSTANCE.SetWindowPos(hwnd, User32Ext.HWND_TOPMOST, 0, 0, 0, 0,
                    User32Ext.SWP_NOMOVE | User32Ext.SWP_NOSIZE | User32Ext.SWP_NOACTIVATE | User32Ext.SWP_FRAMECHANGED);
            return true;
        } catch (Throwable t) {
            if (!windowsWarningPrinted) {
                System.err.println("[HUD] Failed to apply Windows overlay flags: " + t.getMessage());
                windowsWarningPrinted = true;
            }
            return !passThrough;
        }
    }

    private static boolean applyMac(Window window, boolean passThrough) {
        try {
            MacObjC objc = MacObjC.INSTANCE;
            Pointer view = Native.getComponentPointer(window);
            Pointer nsWindow = view == null ? null : objc.msgPointer(view, "window");
            if (isNull(nsWindow)) {
                nsWindow = MacWindowHandle.resolve(window);
            }
            if (isNull(nsWindow)) {
                return !passThrough;
            }

            objc.msgVoid(nsWindow, "setIgnoresMouseEvents:", (byte) (passThrough ? 1 : 0));
            return objc.msgBoolean(nsWindow, "ignoresMouseEvents") == passThrough;
        } catch (Throwable t) {
            if (!macWarningPrinted) {
                System.err.println("[HUD] Failed to apply macOS overlay flags, using Java fallback: " + t.getMessage());
                macWarningPrinted = true;
            }
            return !passThrough;
        }
    }

    private static boolean isNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0;
    }

    /**
     * JNA's JAWT bridge returns a Cocoa view for heavyweight drawing surfaces,
     * but a top-level transparent JWindow has no such surface on recent JDKs.
     * Read the NSWindow pointer already held by the macOS AWT peer instead.
     */
    private static final class MacWindowHandle {
        private static final Object UNSAFE;
        private static final Method GET_OBJECT;
        private static final Method GET_LONG_VOLATILE;
        private static final long COMPONENT_PEER_OFFSET;
        private static final long PLATFORM_WINDOW_OFFSET;
        private static final long NATIVE_POINTER_OFFSET;

        static {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                UNSAFE = theUnsafe.get(null);

                Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
                GET_OBJECT = unsafeClass.getMethod("getObject", Object.class, long.class);
                GET_LONG_VOLATILE = unsafeClass.getMethod("getLongVolatile", Object.class, long.class);

                COMPONENT_PEER_OFFSET = offset(objectFieldOffset, Component.class.getDeclaredField("peer"));
                Class<?> windowPeerClass = Class.forName("sun.lwawt.LWWindowPeer");
                PLATFORM_WINDOW_OFFSET = offset(objectFieldOffset,
                        windowPeerClass.getDeclaredField("platformWindow"));
                Class<?> retainedResourceClass = Class.forName("sun.lwawt.macosx.CFRetainedResource");
                NATIVE_POINTER_OFFSET = offset(objectFieldOffset,
                        retainedResourceClass.getDeclaredField("ptr"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private MacWindowHandle() {
        }

        static Pointer resolve(Window window) throws ReflectiveOperationException {
            Object peer = GET_OBJECT.invoke(UNSAFE, window, COMPONENT_PEER_OFFSET);
            if (peer == null) {
                return null;
            }
            Object platformWindow = GET_OBJECT.invoke(UNSAFE, peer, PLATFORM_WINDOW_OFFSET);
            if (platformWindow == null) {
                return null;
            }
            long pointer = (long) GET_LONG_VOLATILE.invoke(UNSAFE, platformWindow, NATIVE_POINTER_OFFSET);
            return pointer == 0 ? null : new Pointer(pointer);
        }

        private static long offset(Method objectFieldOffset, Field field) throws ReflectiveOperationException {
            return (long) objectFieldOffset.invoke(UNSAFE, field);
        }
    }

    private interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);
        int GWL_EXSTYLE = -20;
        int WS_EX_TRANSPARENT = 0x00000020;
        int WS_EX_TOOLWINDOW = 0x00000080;
        int WS_EX_LAYERED = 0x00080000;
        int WS_EX_NOACTIVATE = 0x08000000;
        int SWP_NOSIZE = 0x0001;
        int SWP_NOMOVE = 0x0002;
        int SWP_NOACTIVATE = 0x0010;
        int SWP_FRAMECHANGED = 0x0020;
        WinDef.HWND HWND_TOPMOST = new WinDef.HWND(Pointer.createConstant(-1));

        int GetWindowLongW(WinDef.HWND hwnd, int index);

        int SetWindowLongW(WinDef.HWND hwnd, int index, int value);

        boolean SetWindowPos(WinDef.HWND hwnd, WinDef.HWND hwndInsertAfter, int x, int y, int cx, int cy, int flags);
    }

    private static final class MacObjC {
        static final MacObjC INSTANCE = new MacObjC();
        private final Function objcGetClass;
        private final Function selRegisterName;
        private final Function objcMsgSend;

        private MacObjC() {
            NativeLibrary library = NativeLibrary.getInstance("objc");
            objcGetClass = library.getFunction("objc_getClass");
            selRegisterName = library.getFunction("sel_registerName");
            objcMsgSend = library.getFunction("objc_msgSend");
        }

        Pointer msgPointer(Pointer receiver, String selector) {
            return objcMsgSend.invokePointer(new Object[]{receiver, selector(selector)});
        }

        boolean msgBoolean(Pointer receiver, String selector) {
            Object value = objcMsgSend.invoke(Byte.class, new Object[]{receiver, selector(selector)});
            if (value instanceof Number number) {
                return number.byteValue() != 0;
            }
            return Boolean.TRUE.equals(value);
        }

        void msgVoid(Pointer receiver, String selector, Object... args) {
            Object[] callArgs = new Object[2 + args.length];
            callArgs[0] = receiver;
            callArgs[1] = selector(selector);
            System.arraycopy(args, 0, callArgs, 2, args.length);
            objcMsgSend.invoke(Void.class, callArgs);
        }

        @SuppressWarnings("unused")
        Pointer cls(String name) {
            return objcGetClass.invokePointer(new Object[]{name});
        }

        Pointer selector(String name) {
            return selRegisterName.invokePointer(new Object[]{name});
        }
    }
}
