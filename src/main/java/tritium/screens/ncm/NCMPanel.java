package tritium.screens.ncm;

import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.DropdownWidget;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:51
 */
public abstract class NCMPanel extends Panel implements DropdownWidget.Host {

    private DropdownWidget dropdown;

    @Override
    public void openDropdown(DropdownWidget dropdown) {
        this.dropdown = dropdown;
    }

    @Override
    public boolean isDropdownOpen(DropdownWidget dropdown) {
        return this.dropdown == dropdown;
    }

    public boolean hasOpenDropdown() {
        if (dropdown != null && !dropdown.layoutMenu(this)) dropdown = null;
        return dropdown != null;
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        boolean open = hasOpenDropdown();
        super.renderWidget(open ? -1e6 : mouseX, open ? -1e6 : mouseY, open ? 0 : dWheel);
        if (hasOpenDropdown()) dropdown.renderMenu(mouseX, mouseY, dWheel);
    }

    public boolean clickDropdown(double mouseX, double mouseY, int button) {
        if (!hasOpenDropdown()) return false;
        dropdown.clickMenu(mouseX, mouseY, button);
        return true;
    }

    @Override
    protected boolean onMouseClickBeforeChildren(double mouseX, double mouseY, int button) {
        return clickDropdown(mouseX, mouseY, button);
    }

    @Override
    public boolean onKeyTypedReceived(char character, int key) {
        if (hasOpenDropdown()) {
            dropdown.keyMenu(key);
            return true;
        }
        return super.onKeyTypedReceived(character, key);
    }

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
