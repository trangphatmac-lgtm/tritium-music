package tritium.rendering.ui.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverlayScrollbarTest {
    @Test
    void thumbTracksTheViewportRatioWithAMinimumLengthAndExactEndpoints() {
        var proportional = OverlayScrollbar.geometry(200, 400, 0);
        assertEquals(97, proportional.length());
        var top = OverlayScrollbar.geometry(200, 100_000, 0);
        var bottom = OverlayScrollbar.geometry(200, 100_000, 99_800);
        assertEquals(32, top.length());
        assertEquals(3, top.start());
        assertEquals(197, bottom.start() + bottom.length());
        assertEquals(bottom, OverlayScrollbar.geometry(200, 100_000, 200_000));
        assertEquals(top, OverlayScrollbar.geometry(200, 100_000, -20));
    }

    @Test
    void emptyAndSmallViewportsNeverProduceAnInvalidTrack() {
        for (double viewport : new double[]{0, 4, 24, 200}) {
            var empty = OverlayScrollbar.geometry(viewport, 0, 0);
            assertFalse(empty.scrollable());
            assertTrue(Double.isFinite(empty.start()));
            assertTrue(empty.length() >= 0 && empty.travel() >= 0);
        }
        assertFalse(OverlayScrollbar.geometry(200, 200, 0).scrollable());
        assertFalse(OverlayScrollbar.geometry(24, 100_000, 0).scrollable());
    }

    @Test
    void scrollingRevealsAThinThumbThenFadesItOut() {
        OverlayScrollbar bar = new OverlayScrollbar();
        var geometry = OverlayScrollbar.geometry(200, 2000, 0);
        bar.update(geometry, 0, false, false, false, 1);
        assertEquals(0, bar.alpha());
        bar.update(geometry, 12, true, false, false, 160_000_001);
        assertEquals(.65f, bar.alpha(), .0001);
        assertEquals(3, bar.width());
        bar.update(geometry, 12, false, false, false, 700_000_001);
        assertEquals(.65f, bar.alpha(), .0001);
        bar.update(geometry, 12, false, false, false, 780_000_001);
        assertTrue(bar.alpha() > 0 && bar.alpha() < .65);
        bar.update(geometry, 12, false, false, false, 900_000_001);
        assertEquals(0, bar.alpha());
    }

    @Test
    void edgeHoverRevealsTheThumbAndThumbHoverSmoothlyWidensIt() {
        OverlayScrollbar bar = new OverlayScrollbar();
        var geometry = OverlayScrollbar.geometry(200, 2000, 0);
        bar.update(geometry, 0, false, false, false, 1);
        bar.update(geometry, 0, false, true, false, 160_000_001);
        assertEquals(.65f, bar.alpha(), .0001);
        assertEquals(3, bar.width());
        bar.update(geometry, 0, false, true, true, 176_000_001);
        assertTrue(bar.width() > 3 && bar.width() < 6);
        bar.update(geometry, 0, false, true, true, 600_000_001);
        assertEquals(6, bar.width());
        bar.update(geometry, 0, false, true, false, 616_000_001);
        assertTrue(bar.width() > 3 && bar.width() < 6);
    }

    @Test
    void draggingPreservesTheGrabPointAndClampsOutsideTheTrack() {
        OverlayScrollbar bar = new OverlayScrollbar();
        var geometry = OverlayScrollbar.geometry(200, 2000, 900);
        double pointer = geometry.start() + geometry.length() * .75;
        bar.beginDrag(geometry, pointer);
        assertEquals(900, bar.dragOffset(geometry, pointer), .0001);
        assertEquals(0, bar.dragOffset(geometry, -100));
        assertEquals(1800, bar.dragOffset(geometry, 400));
        bar.update(geometry, 900, false, false, false, 1);
        bar.update(geometry, 900, false, false, false, 1_000_000_001);
        assertEquals(.65f, bar.alpha(), .0001);
        assertEquals(6, bar.width());
        bar.endDrag();
        assertFalse(bar.isDragging());
    }

    @Test
    void contentThatFitsCancelsDraggingAndHidesTheThumbImmediately() {
        OverlayScrollbar bar = new OverlayScrollbar();
        var longPage = OverlayScrollbar.geometry(200, 2000, 0);
        bar.beginDrag(longPage, 100);
        assertEquals(900, bar.dragOffset(longPage, 100), .0001);
        bar.update(longPage, 900, false, false, false, 1);
        bar.update(longPage, 900, false, false, false, 160_000_001);
        bar.update(OverlayScrollbar.geometry(200, 100, 0), 0, false, true, true, 176_000_001);
        assertFalse(bar.isDragging());
        assertEquals(0, bar.alpha());
    }
}
