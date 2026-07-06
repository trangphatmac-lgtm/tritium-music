package tritium.desktop.hud;

import tritium.desktop.HudElementPreferences;
import tritium.desktop.MusicPreferences;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

public interface HudRenderer {
    String id();

    String displayName();

    HudElementPreferences layout(MusicPreferences preferences);

    void render(Graphics2D graphics, HudRenderContext context);

    default boolean shouldRender(HudStateSnapshot snapshot, MusicPreferences preferences, boolean editMode) {
        return snapshot.smoke || editMode || layout(preferences).enabled().getValue();
    }

    default Rectangle2D bounds(MusicPreferences preferences, Rectangle2D screenBounds) {
        HudElementPreferences layout = layout(preferences);
        double scale = layout.scale().getValue();
        return new Rectangle2D.Double(
                layout.x().getValue() - screenBounds.getX(),
                layout.y().getValue() - screenBounds.getY(),
                layout.width().getValue() * scale,
                layout.height().getValue() * scale
        );
    }
}
