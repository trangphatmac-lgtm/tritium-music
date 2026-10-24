package tritium.screens.ncm;

import tritium.rendering.ui.container.Panel;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:51
 */
public abstract class NCMPanel extends Panel {

    protected tritium.screens.ncm.navigation.NavigationContent navigationContent;
    private tritium.screens.ncm.navigation.CoverNavigationView coverNavigation;

    public tritium.screens.ncm.navigation.CoverNavigationView getCoverNavigation() {
        if (navigationContent == null) return null;
        if (coverNavigation == null) coverNavigation = new tritium.screens.ncm.navigation.CoverNavigationView(navigationContent);
        return coverNavigation;
    }

    public abstract void onInit();

    /** Floating controls overlay the scroll viewport instead of leaving a bottom gutter. */
    protected double scrollBottomInset(double standardInset) {
        return tritium.desktop.DesktopAppState.preferences().playerControls().getValue().equals("FoliaMini")
                ? 0 : standardInset;
    }

    protected int getColor(NCMScreen.ColorType type) {
        return 0xFF000000 | NCMScreen.getColor(type);
    }

}
