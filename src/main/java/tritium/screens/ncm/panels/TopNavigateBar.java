package tritium.screens.ncm.panels;

import tritium.desktop.AppTheme;
import tritium.i18n.I18n;
import tritium.management.FontManager;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;

import java.util.ArrayList;
import java.util.List;

/** A centered segmented navigation bar, with a second row on narrow windows. */
public final class TopNavigateBar extends NCMPanel {
    private static final double CONTROL_HEIGHT = 22;
    private final NCMScreen screen;
    private final MusicSearchBar search;
    private final LabelWidget title = new LabelWidget("Tritium Music", FontManager.pf25bold);
    private final List<Tab> tabs = new ArrayList<>();
    private double tabsX, tabsY, tabsWidth;

    public TopNavigateBar(NCMScreen screen, MusicSearchBar search) {
        this.screen = screen;
        this.search = search;
        title.setClickable(false);
        title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        addChild(title);
        addChild(search);
        addTab(NCMScreen.NavigationSection.EXPLORE, "nav.explore", () -> screen.setCurrentPanel(new HomePanel()));
        addTab(NCMScreen.NavigationSection.MY_PLAYLISTS, "nav.playlists",
                () -> screen.setCurrentPanel(new PlaylistLibraryPanel(screen, false)));
        addTab(NCMScreen.NavigationSection.SAVED_PLAYLISTS, "nav.subscribed",
                () -> screen.setCurrentPanel(new PlaylistLibraryPanel(screen, true)));
        if (tritium.desktop.DesktopAppState.minecraft().connected())
            addTab(NCMScreen.NavigationSection.MINECRAFT, "minecraft.title", () -> screen.setCurrentPanel(new MinecraftPanel()));
        addTab(NCMScreen.NavigationSection.SETTINGS, "settings.title", () -> screen.setCurrentPanel(new SettingsPanel()));
        setBeforeRenderCallback(this::arrange);
    }

    private void addTab(NCMScreen.NavigationSection section, String key, Runnable action) {
        Tab tab = new Tab(section, key, action);
        tabs.add(tab);
        addChild(tab);
    }

    public void arrange() {
        double width = screen.getPanelWidth();
        double margin = width < 600 ? 16 : 24;
        double searchWidth = Math.min(210, Math.max(120, width * .23));
        double titleWidth = FontManager.pf25bold.getStringWidthD("Tritium Music");
        double naturalTabsWidth = 0;
        for (Tab tab : tabs) naturalTabsWidth += tab.naturalWidth();
        double sideWidth = Math.max(titleWidth, searchWidth) + margin + 20;
        boolean stacked = width < naturalTabsWidth + sideWidth * 2;
        setBounds(0, 0, width, stacked ? 80 : 44);
        search.setBounds(width - margin - searchWidth, 11, searchWidth, CONTROL_HEIGHT);
        title.setPosition(margin, 22 - FontManager.pf25bold.getHeight() * .5);
        title.setMaxWidth(Math.max(0, search.getRelativeX() - margin - 16));
        title.setColor(AppTheme.foreground());
        tabsWidth = Math.min(naturalTabsWidth, Math.max(0, width - margin * 2));
        tabsX = (width - tabsWidth) * .5;
        tabsY = stacked ? 47 : 11;
        double x = tabsX;
        double ratio = tabsWidth / naturalTabsWidth;
        for (Tab tab : tabs) {
            double tabWidth = tab.naturalWidth() * ratio;
            tab.setBounds(x, tabsY, tabWidth, CONTROL_HEIGHT);
            x += tabWidth;
        }
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        UiMaterials.control(getX() + tabsX, getY() + tabsY, tabsWidth, CONTROL_HEIGHT, CONTROL_HEIGHT * .5,
                reAlpha(AppTheme.hover(), getAlpha() * .55f));
    }

    @Override
    public void onInit() { }

    private final class Tab extends AbstractWidget<Tab> {
        private final NCMScreen.NavigationSection section;
        private final String key;

        private Tab(NCMScreen.NavigationSection section, String key, Runnable action) {
            this.section = section;
            this.key = key;
            setShouldOverrideMouseCursor(true);
            setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                action.run();
                return true;
            });
        }

        private double naturalWidth() {
            return FontManager.pf16bold.getStringWidthD(I18n.text(key)) + 24;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            boolean selected = screen.getNavigationSection() == section;
            if (selected) {
                // Keep the selected pill opaque and high contrast in all material modes.
                UiMaterials.withoutMaterials(() -> UiMaterials.control(getX(), getY(), getWidth(), getHeight(), getHeight() * .5,
                        reAlpha(AppTheme.foreground(), getAlpha())));
            } else if (isHovering()) {
                UiMaterials.control(getX(), getY(), getWidth(), getHeight(), getHeight() * .5,
                        reAlpha(AppTheme.foreground(), getAlpha() * .08f));
            }
            String label = FontManager.pf16bold.trim(I18n.text(key), Math.max(0, getWidth() - 8));
            FontManager.pf16bold.drawCenteredString(label, getX() + getWidth() * .5,
                    getY() + (getHeight() - FontManager.pf16bold.getHeight()) * .5,
                    reAlpha(selected ? AppTheme.background() : AppTheme.foreground(), getAlpha() * (selected ? 1 : .65f)));
        }
    }
}
