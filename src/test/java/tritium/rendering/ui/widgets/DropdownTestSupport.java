package tritium.rendering.ui.widgets;

import org.lwjgl.input.Keyboard;
import tritium.desktop.ModePreference;
import tritium.rendering.ui.AbstractWidget;
import tritium.screens.ncm.NCMPanel;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real popup input path without depending on its screen-edge placement. */
public final class DropdownTestSupport {
    private DropdownTestSupport() { }

    public static void select(NCMPanel panel, AbstractWidget<?> row, ModePreference preference, String value) {
        String before = preference.getValue();
        double x = row.getX() + 20, y = row.getY() + row.getHeight() / 2;
        panel.onMouseClickReceived(x, y, 0);
        panel.onMouseReleaseReceived(x, y, 0);
        assertTrue(panel.hasOpenDropdown(), "Click should open a dropdown");
        assertEquals(before, preference.getValue(), "Opening must not cycle the setting");
        int index = preference.getModes().indexOf(value);
        assertTrue(index >= 0, "Unknown option: " + value);
        panel.onKeyTypedReceived('\0', Keyboard.KEY_HOME);
        for (int i = 0; i < index; i++) panel.onKeyTypedReceived('\0', Keyboard.KEY_DOWN);
        panel.onKeyTypedReceived('\n', Keyboard.KEY_RETURN);
        assertFalse(panel.hasOpenDropdown());
        assertEquals(value, preference.getValue());
    }
}
