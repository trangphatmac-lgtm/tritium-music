package tritium.utils;

import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;

/**
 * @author IzumiiKonata
 * Date: 2025/3/15 15:29
 */
public class KeyboardUtils {
    private static final ThreadLocal<Integer> INPUT_MODIFIERS = new ThreadLocal<>();

    /** AWT and the GL loop run on different threads; use modifiers from the event. */
    public static void withModifiers(int modifiers, Runnable action) {
        Integer previous = INPUT_MODIFIERS.get();
        INPUT_MODIFIERS.set(modifiers);
        try {
            action.run();
        } finally {
            if (previous == null) INPUT_MODIFIERS.remove();
            else INPUT_MODIFIERS.set(previous);
        }
    }

    /**
     * Returns a string stored in the system clipboard.
     */
    public static String getClipboardString() {
        try {
            Transferable transferable = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);

            if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) transferable.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception var1) {
        }

        return "";
    }

    /**
     * Stores the given string in the system clipboard
     */
    public static void setClipboardString(String copyText) {
        if (!StringUtils.isEmpty(copyText)) {
            try {
                StringSelection stringselection = new StringSelection(copyText);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringselection, null);
            } catch (Exception var2) {
            }
        }
    }

    /**
     * Returns true if either windows ctrl key is down or if either mac meta key is down
     */
    public static boolean isCtrlKeyDown() {
        Integer modifiers = INPUT_MODIFIERS.get();
        if (modifiers != null) {
            return (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
        }
        return Keyboard.isCreated() && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
                || (System.getProperty("os.name", "").startsWith("Mac")
                && (Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA))));
    }

    /**
     * Returns true if either shift key is down
     */
    public static boolean isShiftKeyDown() {
        Integer modifiers = INPUT_MODIFIERS.get();
        if (modifiers != null) return (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        return Keyboard.isCreated() && (Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54));
    }

    /**
     * Returns true if either alt key is down
     */
    public static boolean isAltKeyDown() {
        Integer modifiers = INPUT_MODIFIERS.get();
        if (modifiers != null) return (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
        return Keyboard.isCreated() && (Keyboard.isKeyDown(56) || Keyboard.isKeyDown(184));
    }

    public static boolean isKeyComboCtrlX(int keyID) {
        return keyID == 45 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlV(int keyID) {
        return keyID == 47 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlC(int keyID) {
        return keyID == 46 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlA(int keyID) {
        return keyID == 30 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrl(int pressedKey, int comboKey) {
        return pressedKey == comboKey && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

}
