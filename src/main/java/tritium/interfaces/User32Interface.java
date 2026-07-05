package tritium.interfaces;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

/**
 * @author IzumiiKonata
 * Date: 2025/6/8 21:48
 */
public interface User32Interface extends StdCallLibrary {
    User32Interface INSTANCE = Native.load("user32", User32Interface.class);

    int GCLP_HCURSOR = -12;

    WinDef.HCURSOR LoadCursorW(WinDef.HINSTANCE instance, int cursorId);

    Pointer SetClassLongPtrW(WinDef.HWND hWnd, int nIndex, WinNT.HANDLE dwNewLong);

    WinDef.HCURSOR SetCursor(WinDef.HCURSOR cursor);
}
