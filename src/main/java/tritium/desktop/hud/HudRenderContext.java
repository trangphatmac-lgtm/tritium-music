package tritium.desktop.hud;

import tritium.desktop.MusicPreferences;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

public final class HudRenderContext {
    public final MusicPreferences preferences;
    public final HudStateSnapshot snapshot;
    public final Rectangle screenBounds;
    public final Rectangle2D bounds;
    public final boolean editMode;

    public HudRenderContext(MusicPreferences preferences, HudStateSnapshot snapshot, Rectangle screenBounds,
                            Rectangle2D bounds, boolean editMode) {
        this.preferences = preferences;
        this.snapshot = snapshot;
        this.screenBounds = screenBounds;
        this.bounds = bounds;
        this.editMode = editMode;
    }
}
