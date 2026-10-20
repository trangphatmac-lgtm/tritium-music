package tritium.rendering.ui;

import org.junit.jupiter.api.Test;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.container.ScrollPanel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractWidgetMouseInputTest {
    private final List<String> actions = new ArrayList<>();

    @Test
    void movingFromFavoriteToSongBeforeNextFrameOnlyPlaysTheSong() {
        Panel row = action("play", 0, 0, 300, 30);
        Panel favorite = action("favorite", 260, 5, 20, 20);
        row.addChild(favorite);
        row.renderWidget(270, 15, 0);
        assertTrue(favorite.isHovering());
        assertFalse(row.isHovering());

        row.onMouseClickReceived(100, 15, 0);

        assertEquals(List.of("play"), actions);
    }

    @Test
    void clickingFavoriteUsesCurrentPositionAndDoesNotAlsoPlay() {
        Panel row = action("play", 0, 0, 300, 30);
        Panel favorite = action("favorite", 260, 5, 20, 20);
        row.addChild(favorite);
        row.renderWidget(100, 15, 0);
        assertFalse(favorite.isHovering());
        assertTrue(row.isHovering());

        row.onMouseClickReceived(270, 15, 0);

        assertEquals(List.of("favorite"), actions);
    }

    @Test
    void scrollingHoveredFavoriteOutOfViewCannotUnfavoriteWhenAnotherSongIsClicked() {
        Panel oldRow = action("old song", 0, 0, 300, 30);
        Panel favorite = action("favorite", 260, 5, 20, 20);
        oldRow.addChild(favorite);
        oldRow.renderWidget(270, 15, 0);
        assertTrue(favorite.isHovering());

        ScrollPanel songs = new ScrollPanel();
        songs.setBounds(20, 40, 300, 90);
        songs.addChild(oldRow);
        oldRow.setPosition(0, -60);
        // Culled rows are not rendered again, so their hover state remains stale.
        Panel visibleRow = action("play visible song", 0, 0, 300, 30);
        songs.addChild(visibleRow);

        songs.onMouseClickReceived(120, 55, 0);

        assertEquals(List.of("play visible song"), actions);
    }

    @Test
    void clickOutsideScrollClipDoesNotReachSongActions() {
        ScrollPanel songs = new ScrollPanel();
        songs.setBounds(20, 40, 300, 90);
        songs.addChild(action("play", 0, -30, 300, 30));

        songs.onMouseClickReceived(120, 25, 0);

        assertTrue(actions.isEmpty());
    }

    private Panel action(String name, double x, double y, double width, double height) {
        return new Panel().setBounds(x, y, width, height).setOnClickCallback((relativeX, relativeY, button) -> {
            actions.add(name);
            return true;
        });
    }
}
