package tritium.rendering.ui.widgets;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;
import tritium.desktop.ModePreference;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.screens.ncm.NCMPanel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DropdownWidgetTest {
    private final NCMPanel panel = new NCMPanel() { @Override public void onInit() { } };
    private final ModePreference preference = new ModePreference("Choice", "0",
            IntStream.range(0, 32).mapToObj(Integer::toString).toArray(String[]::new));
    private final DropdownWidget dropdown = new DropdownWidget(preference, value -> value);

    DropdownWidgetTest() {
        panel.setBounds(10, 20, 500, 400);
        dropdown.setBounds(280, 30, 180, 30);
        panel.addChild(dropdown);
    }

    @Test
    void matchesButtonWidthAndOnlyExpandsForTextThatDoesNotFit() throws Exception {
        dropdown.setWidth(112);
        dropdown.open();
        assertTrue(panel.hasOpenDropdown());
        assertEquals(112, number("menuWidth"));
        var textWidth = DropdownWidget.class.getDeclaredField("textWidth");
        textWidth.setAccessible(true);
        textWidth.setDouble(dropdown, 170);
        assertTrue(panel.hasOpenDropdown());
        assertEquals(214, number("menuWidth"));
        assertEquals(dropdown.getX() + dropdown.getWidth(), number("menuX") + number("menuWidth"));
        textWidth.setDouble(dropdown, 800);
        assertTrue(panel.hasOpenDropdown());
        assertEquals(panel.getWidth() - 16, number("menuWidth"));
    }

    @Test
    void opensWithoutChangingValueAndSelectsDirectlyAboveCoveredControls() {
        AtomicInteger coveredClicks = new AtomicInteger();
        var covered = new Panel().setBounds(0, 90, 500, 300);
        covered.setOnClickCallback((x, y, b) -> { coveredClicks.incrementAndGet(); return true; });
        panel.addChild(covered);
        panel.onMouseClickReceived(dropdown.getX() + 10, dropdown.getY() + 10, 1);
        assertFalse(panel.hasOpenDropdown());
        panel.onMouseClickReceived(dropdown.getX() + 10, dropdown.getY() + 10, 0);
        assertTrue(panel.hasOpenDropdown());
        assertEquals("0", preference.getValue());
        panel.onMouseClickReceived(dropdown.getX() + 20, dropdown.getY() + 30 + 6 + 6 + 3 * 32 + 16, 0);
        assertEquals("3", preference.getValue());
        assertFalse(panel.hasOpenDropdown());
        assertEquals(0, coveredClicks.get());
    }

    @Test
    void keyboardNavigationOnlyCommitsOnConfirmationAndDismissalNeverClicksThrough() {
        AtomicInteger clicks = new AtomicInteger();
        panel.addChild(new Panel().setBounds(0, 320, 500, 50)
                .setOnClickCallback((x, y, b) -> { clicks.incrementAndGet(); return true; }));
        dropdown.open();
        panel.onKeyTypedReceived('\0', Keyboard.KEY_END);
        assertEquals("0", preference.getValue());
        panel.onKeyTypedReceived('\0', Keyboard.KEY_ESCAPE);
        assertFalse(panel.hasOpenDropdown());
        dropdown.open();
        panel.onKeyTypedReceived('\0', Keyboard.KEY_END);
        panel.onKeyTypedReceived('\n', Keyboard.KEY_RETURN);
        assertEquals("31", preference.getValue());
        dropdown.open();
        panel.onMouseClickReceived(panel.getX() + 10, panel.getY() + 340, 0);
        assertEquals(0, clicks.get());
        assertFalse(panel.hasOpenDropdown());
        assertEquals("31", preference.getValue());
    }

    @Test
    void opensUpwardsNearBottomAndKeepsLastSelectionVisible() throws Exception {
        dropdown.setPosition(280, 350);
        preference.setValue("31");
        dropdown.open();
        assertTrue(panel.hasOpenDropdown());
        double x = number("menuX"), y = number("menuY");
        assertTrue(y >= panel.getY());
        assertTrue(y + number("menuHeight") < dropdown.getY());
        assertTrue(x + number("menuWidth") <= panel.getX() + panel.getWidth());
        panel.onMouseClickReceived(x + 20, y + 6 + 6 * 32 + 16, 0);
        assertEquals("31", preference.getValue());
        assertFalse(panel.hasOpenDropdown());
    }

    @Test
    void closesWhenAnchorIsHiddenDetachedOrClippedAndOnlyOneMenuCanOpen() {
        dropdown.open();
        var other = new DropdownWidget(preference, value -> value).setBounds(20, 30, 180, 30);
        panel.addChild(other);
        other.open();
        assertFalse(dropdown.isOpen());
        assertTrue(other.isOpen());
        other.setHidden(true);
        assertFalse(panel.hasOpenDropdown());
        dropdown.open();
        panel.getChildren().remove(dropdown);
        assertFalse(panel.hasOpenDropdown());
        var scroll = new ScrollPanel().setBounds(20, 40, 460, 100);
        panel.addChild(scroll);
        scroll.addChild(dropdown);
        dropdown.setPosition(20, 85);
        dropdown.open();
        assertFalse(panel.hasOpenDropdown(), "Partially clipped controls cannot leave a detached popup");
    }

    private double number(String name) throws Exception {
        var field = DropdownWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(dropdown)).doubleValue();
    }
}
