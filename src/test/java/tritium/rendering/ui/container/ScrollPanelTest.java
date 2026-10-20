package tritium.rendering.ui.container;

import org.junit.jupiter.api.Test;
import tritium.rendering.rendersystem.RenderSystem;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScrollPanelTest {
    @Test
    void nestedScrollbarCapturesClicksBeforeSongActionsAndDragsOutsideTheClip() {
        AtomicInteger clicks = new AtomicInteger();
        Panel root = new Panel().setBounds(30, 40, 500, 500);
        ScrollPanel scroll = verticalList(clicks);
        root.addChild(scroll);
        scroll.alignChildren();

        double x = scroll.getX() + scroll.getWidth() - 5;
        double y = scroll.getY() + 12;
        root.onMouseClickReceived(x, y, 0);
        assertEquals(0, clicks.get());
        scroll.onRender(x + 100, scroll.getY() + 1000);
        assertEquals(900, scroll.actualScrollOffset);
        assertEquals(scroll.actualScrollOffset, scroll.targetScrollOffset);
        root.onMouseReleaseReceived(x + 100, scroll.getY() + 1000, 0);
        scroll.onRender(x, scroll.getY());
        assertEquals(900, scroll.actualScrollOffset, "Release outside the clip must end the drag");

        root.onMouseClickReceived(scroll.getX() + 20, scroll.getY() + 10, 0);
        assertEquals(1, clicks.get(), "Content away from the scrollbar still handles clicks");
    }

    @Test
    void standalonePanelTrackClickJumpsAndRightClickDoesNotStartDragging() {
        ScrollPanel scroll = verticalList(new AtomicInteger());
        scroll.alignChildren();
        scroll.onMouseClickReceived(scroll.getX() + 195, scroll.getY() + 95, 1);
        assertEquals(0, scroll.actualScrollOffset);
        scroll.onMouseClickReceived(scroll.getX() + 195, scroll.getY() + 95, 0);
        assertEquals(900, scroll.actualScrollOffset);
        scroll.onMouseReleaseReceived(scroll.getX() + 195, scroll.getY() + 95, 0);
    }

    @Test
    void filteringOrResizingClampsBothOffsetsAndCancelsAnObsoleteDrag() {
        ScrollPanel scroll = verticalList(new AtomicInteger());
        scroll.scrollToEndImmediately();
        scroll.onMouseClickReceived(scroll.getX() + 195, scroll.getY() + 90, 0);
        scroll.getChildren().stream().skip(1).forEach(child -> child.setHidden(true));
        scroll.onRender(scroll.getX() + 195, scroll.getY() + 90);
        assertEquals(0, scroll.actualScrollOffset);
        assertEquals(0, scroll.targetScrollOffset);
        scroll.getChildren().forEach(child -> child.setHidden(false));
        scroll.onRender(scroll.getX() + 195, scroll.getY() + 90);
        assertEquals(0, scroll.actualScrollOffset, "Reappearing rows must not resume the old drag");
        scroll.scrollToEndImmediately();
        scroll.setHeight(2000);
        scroll.alignChildren();
        assertEquals(0, scroll.actualScrollOffset);
        assertEquals(0, scroll.targetScrollOffset);
    }

    @Test
    void wheelAndProgrammaticScrollingShareTheSameBounds() {
        double originalDelta = RenderSystem.getFrameDeltaTime();
        try {
            RenderSystem.setFrameDeltaTime(1);
            ScrollPanel scroll = verticalList(new AtomicInteger());
            scroll.onDWheel(20, 20, -120);
            assertEquals(12, scroll.targetScrollOffset);
            scroll.onRender(-1, -1);
            assertTrue(scroll.actualScrollOffset > 0 && scroll.actualScrollOffset < 12);
            scroll.scrollToEndImmediately();
            assertEquals(900, scroll.actualScrollOffset);
            scroll.onDWheel(20, 20, -120);
            assertEquals(900, scroll.targetScrollOffset);
            assertTrue(scroll.isScrolledToEnd());
        } finally {
            RenderSystem.setFrameDeltaTime(originalDelta);
        }
    }

    @Test
    void horizontalAndWrappedLayoutsUseTheirOwnContentExtent() {
        ScrollPanel horizontal = new ScrollPanel().setAlignment(ScrollPanel.Alignment.HORIZONTAL).setBounds(200, 100);
        horizontal.addChild(new Panel().setBounds(1000, 100));
        horizontal.onMouseClickReceived(190, 95, 0);
        horizontal.onMouseReleaseReceived(400, 95, 0);
        assertEquals(800, horizontal.actualScrollOffset);

        ScrollPanel grid = new ScrollPanel().setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
                .setBounds(200, 100).setSpacing(10);
        for (int i = 0; i < 10; i++) grid.addChild(new Panel().setBounds(90, 40));
        grid.scrollToEndImmediately();
        grid.alignChildren();
        assertEquals(140, grid.actualScrollOffset);
        var last = grid.getChildren().getLast();
        assertEquals(grid.getHeight(), last.getRelativeY() + last.getHeight());
    }

    @Test
    void nonOverflowingOrHiddenPanelDoesNotCaptureTheEdge() {
        AtomicInteger clicks = new AtomicInteger();
        Panel root = new Panel().setBounds(500, 500);
        ScrollPanel scroll = verticalList(clicks);
        scroll.setHeight(1000);
        scroll.alignChildren();
        root.addChild(scroll);
        root.onMouseClickReceived(scroll.getX() + 195, scroll.getY() + 10, 0);
        assertEquals(1, clicks.get());
        scroll.setHidden(true);
        root.onMouseClickReceived(scroll.getX() + 195, scroll.getY() + 10, 0);
        assertEquals(1, clicks.get());
    }

    private static ScrollPanel verticalList(AtomicInteger clicks) {
        ScrollPanel scroll = new ScrollPanel().setBounds(20, 30, 200, 100);
        for (int i = 0; i < 20; i++) {
            scroll.addChild(new Panel().setBounds(200, 50).setOnClickCallback((x, y, button) -> {
                clicks.incrementAndGet();
                return true;
            }));
        }
        return scroll;
    }
}
