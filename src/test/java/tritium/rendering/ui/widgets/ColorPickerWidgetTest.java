package tritium.rendering.ui.widgets;

import org.junit.jupiter.api.Test;
import tritium.rendering.ui.container.Panel;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ColorPickerWidgetTest {
    @Test
    void planeAndHueProduceFullColorRangeWithoutChangingAlpha() {
        AtomicInteger value = new AtomicInteger();
        ColorPickerWidget picker = new ColorPickerWidget(0x80FF0000, true, value::set);
        picker.setBounds(0, 0, 300, 282);
        assertTrue(picker.onMouseClicked(100, 180, 0));
        picker.onMouseReleased(100, 180, 0);
        assertEquals(0x8000FF00, value.get());
        picker.onMouseClicked(0, 22, 0);
        picker.onMouseReleased(0, 22, 0);
        assertEquals(0x80FFFFFF, value.get());
        picker.onMouseClicked(300, 142, 0);
        picker.onMouseReleased(300, 142, 0);
        assertEquals(0x80000000, value.get());
        picker.onMouseClicked(300, 22, 0);
        picker.onMouseReleased(300, 22, 0);
        assertEquals(0x8000FF00, value.get()); // Hue survives both white and black.
    }

    @Test
    void releaseOutsideClipClampsAndEndsDragEvenWhenHidden() {
        AtomicInteger value = new AtomicInteger();
        Panel panel = new Panel();
        panel.setBounds(10, 20, 200, 200);
        ColorPickerWidget picker = new ColorPickerWidget(0xFFFF0000, true, value::set);
        picker.setBounds(16, 30, 100, 282);
        panel.addChild(picker);
        picker.onMouseClicked(50, 255, 0);
        assertEquals(128, value.get() >>> 24);
        picker.setHidden(true);
        panel.onMouseReleaseReceived(-100, -100, 0);
        assertEquals(0x00FF0000, value.get());
        panel.onMouseReleaseReceived(1000, 1000, 0);
        assertEquals(0x00FF0000, value.get()); // A second release cannot continue the drag.
    }

    @Test
    void themePickerAlwaysEmitsOpaqueColorsAndIgnoresBlankSpace() {
        AtomicInteger value = new AtomicInteger();
        ColorPickerWidget picker = new ColorPickerWidget(0x40112233, false, value::set);
        assertFalse(picker.onMouseClicked(50, 255, 0));
        assertFalse(picker.onMouseClicked(50, 180, 1));
        picker.onMouseClicked(100, 22, 0);
        picker.onMouseReleased(100, 22, 0);
        assertEquals(255, value.get() >>> 24);
    }

    @Test
    void preservesAchromaticHueAndRoundTripsArgb() {
        HsvColor color = new HsvColor(0x800000FF);
        color.setArgb(0x80808080);
        color.setSaturationAndBrightness(1, 1);
        assertEquals(0x800000FF, color.argb());
        color.setArgb(0x80000000);
        color.setSaturationAndBrightness(1, 1);
        assertEquals(0x800000FF, color.argb());
        for (int argb : new int[]{0x003B719A, 0x7F81642A, 0xC87D7D7D, 0xFFD60017, 0xFFFFFFFF}) {
            assertEquals(argb, new HsvColor(argb).argb());
        }
    }
}
