package tritium.screens.ncm.panels;

import org.junit.jupiter.api.Test;
import tritium.desktop.ModePreference;
import tritium.rendering.ui.container.Panel;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SettingsLayoutTest {
    private final ModePreference layout = new ModePreference("Settings Layout", "TwoColumns",
            new String[]{"SingleColumn", "TwoColumns"});

    @Test
    void keepsSectionsTogetherAndRestoresColumnsAfterNarrowResize() {
        var scroll = content();
        var first = scroll.getChildren().get(0);
        var second = scroll.getChildren().get(1);
        var third = scroll.getChildren().get(2);
        assertEquals(first.getRelativeY(), second.getRelativeY());
        assertTrue(second.getRelativeX() >= first.getWidth() + 24);
        assertEquals(second.getRelativeX(), third.getRelativeX(), "Use the shorter column");
        assertTrue(third.getRelativeY() >= second.getRelativeY() + second.getHeight() + 24);
        assertEquals(second.getWidth(), second.getChildren().getLast().getWidth());

        scroll.setWidth(663);
        scroll.alignChildren();
        assertEquals("TwoColumns", layout.getValue(), "Resizing must keep the saved choice");
        for (var section : scroll.getChildren()) {
            assertEquals(0, section.getRelativeX());
            assertEquals(663, section.getWidth());
        }
        assertEquals(first.getHeight() + 8, second.getRelativeY());
        scroll.setWidth(664);
        scroll.alignChildren();
        assertEquals(320, first.getWidth());
        assertEquals(344, second.getRelativeX());

        layout.setValue("SingleColumn");
        scroll.alignChildren();
        assertEquals(664, first.getWidth());
        assertEquals(0, second.getRelativeX());
    }

    @Test
    void reflowsExpandedAndHiddenRowsAndClampsToTheTallestColumn() {
        var scroll = content();
        var first = scroll.getChildren().getFirst();
        var row = first.getChildren().getLast();
        row.setHeight(900);
        scroll.scrollToEndImmediately();
        scroll.alignChildren();
        assertEquals(scroll.getHeight(), first.getRelativeY() + first.getHeight());
        double expandedEnd = scroll.actualScrollOffset;
        row.setHidden(true);
        scroll.alignChildren();
        assertTrue(scroll.actualScrollOffset < expandedEnd);
        assertEquals(scroll.targetScrollOffset, scroll.actualScrollOffset);
        double bottom = scroll.getChildren().stream()
                .mapToDouble(section -> section.getRelativeY() + section.getHeight()).max().orElseThrow();
        assertEquals(scroll.getHeight(), bottom);
    }

    @Test
    void dispatchesRightColumnClicksAndClipsRowsOutsideTheViewport() {
        var scroll = content();
        AtomicInteger clicks = new AtomicInteger();
        var row = scroll.getChildren().get(1).getChildren().getLast();
        row.setOnClickCallback((x, y, button) -> { clicks.incrementAndGet(); return true; });
        scroll.onMouseClickReceived(row.getX() + 20, row.getY() + 20, 0);
        assertEquals(1, clicks.get());
        scroll.onDWheel(20, 20, -120);
        assertTrue(scroll.targetScrollOffset > 0);
        scroll.scrollToEndImmediately();
        scroll.alignChildren();
        scroll.onMouseClickReceived(row.getX() + 20, row.getY() + 20, 0);
        assertEquals(1, clicks.get());
    }

    private SettingsPanel.SettingsScrollPanel content() {
        var scroll = new SettingsPanel.SettingsScrollPanel(layout);
        scroll.setBounds(20, 30, 1000, 100);
        for (double height : new double[]{400, 100, 200}) {
            scroll.addSection("settings.general").addChild(new Panel().setBounds(100, height));
        }
        scroll.alignChildren();
        return scroll;
    }
}
