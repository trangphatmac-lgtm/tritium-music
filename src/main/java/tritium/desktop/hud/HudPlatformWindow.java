package tritium.desktop.hud;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Window;

final class HudPlatformWindow {
    private static boolean macWarningPrinted;
    private static boolean windowsWarningPrinted;

    private HudPlatformWindow() {
    }

    static void apply(Window window, boolean passThrough) {
        window.setAlwaysOnTop(true);
        window.setFocusable(false);
        window.setFocusableWindowState(false);

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            applyWindows(window, passThrough);
        } else if (os.contains("mac")) {
            applyMac(window, passThrough);
        }
    }

    private static void applyWindows(Window window, boolean passThrough) {
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
        } catch (Throwable t) {
            if (!windowsWarningPrinted) {
                System.err.println("[HUD] Failed to apply Windows overlay flags: " + t.getMessage());
                windowsWarningPrinted = true;
            }
        }
    }

    private static void applyMac(Window window, boolean passThrough) {
        try {
            MacObjC objc = MacObjC.INSTANCE;
            Pointer view = Native.getComponentPointer(window);
            Pointer nsWindow = view == null ? null : objc.msgPointer(view, "window");
            if (isNull(nsWindow)) {
                nsWindow = Native.getWindowPointer(window);
            }
            if (isNull(nsWindow)) {
                return;
            }

            objc.msgVoid(nsWindow, "setIgnoresMouseEvents:", passThrough);
            objc.msgVoid(nsWindow, "setLevel:", 25);
            objc.msgVoid(nsWindow, "setCollectionBehavior:", 1 | 16 | 128 | 256);
        } catch (Throwable t) {
            if (!macWarningPrinted) {
                System.err.println("[HUD] Failed to apply macOS overlay flags, using Java fallback: " + t.getMessage());
                macWarningPrinted = true;
            }
        }
    }

    private static boolean isNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0;
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
