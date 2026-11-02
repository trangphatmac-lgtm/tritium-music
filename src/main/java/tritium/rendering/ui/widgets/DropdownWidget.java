package tritium.rendering.ui.widgets;

import org.lwjgl.input.Keyboard;
import tritium.desktop.AppTheme;
import tritium.desktop.ModePreference;
import tritium.management.FontManager;
import tritium.rendering.material.UiMaterials;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.StrokeIcons;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.utils.cursor.CursorUtils;

import java.util.function.Function;

/** A single-choice control whose host draws the menu above scrolling content. */
public final class DropdownWidget extends AbstractWidget<DropdownWidget> {
    public interface Host {
        void openDropdown(DropdownWidget dropdown);
        boolean isDropdownOpen(DropdownWidget dropdown);
    }

    private static final double ROW_HEIGHT = 32, PADDING = 6, GAP = 6;
    private static final int MAX_VISIBLE = 7;
    private final ModePreference preference;
    private final Function<String, String> formatter;
    private int focused, firstVisible, visibleCount;
    private double menuX, menuY, menuWidth, menuHeight;
    private double textWidth;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;

    public DropdownWidget(ModePreference preference, Function<String, String> formatter) {
        this.preference = preference;
        this.formatter = formatter;
        setShouldOverrideMouseCursor(true);
    }

    private Host host() {
        for (AbstractWidget<?> parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof Host host) return host;
        }
        return null;
    }

    public boolean isOpen() {
        Host host = host();
        return host != null && host.isDropdownOpen(this);
    }

    public void open() {
        Host host = host();
        if (host == null || preference.getModes().isEmpty()) return;
        focused = Math.max(0, preference.getModes().indexOf(preference.getValue()));
        firstVisible = Math.max(0, focused - MAX_VISIBLE + 1);
        visibleCount = 0;
        lastMouseX = lastMouseY = Double.NaN;
        host.openDropdown(this);
    }

    private void close() {
        Host host = host();
        if (host != null) host.openDropdown(null);
    }

    @Override
    public boolean onMouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        if (isOpen()) close(); else open();
        return true;
    }

    public double preferredWidth() {
        textWidth = 0;
        for (String mode : preference.getModes()) {
            String label = formatter.apply(mode);
            FontManager.pf14bold.prepareGlyphs(label);
            textWidth = Math.max(textWidth, FontManager.pf14bold.getStringWidthD(label));
        }
        return Math.max(112, textWidth + 44);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        boolean open = isOpen();
        boolean material = UiMaterials.control(getX(), getY(), getWidth(), getHeight(), 7,
                reAlpha(open || isHovering() ? AppTheme.accentMuted() : AppTheme.settingsHover(), getAlpha()));
        if (!material) {
            roundedOutline(getX(), getY(), getWidth(), getHeight(), 7, 1,
                    new java.awt.Color(reAlpha(open ? AppTheme.accent() : AppTheme.foreground(),
                            getAlpha() * (open ? .65f : .12f)), true));
        }
        drawLabel(formatter.apply(preference.getValue()), getX() + 10,
                getY() + getHeight() / 2, getWidth() - 36, AppTheme.foreground());
        double cx = getX() + getWidth() - 15, cy = getY() + getHeight() / 2;
        int color = reAlpha(AppTheme.foreground(), getAlpha() * .65f);
        StrokeIcons.chevron(cx, cy, 10, 4, open, 1.5, color);
    }

    /** Returns false when the anchor was removed, hidden, or scrolled out of view. */
    public boolean layoutMenu(AbstractWidget<?> viewport) {
        double top = viewport.getY(), bottom = top + viewport.getHeight();
        double left = viewport.getX(), right = left + viewport.getWidth();
        AbstractWidget<?> child = this;
        for (; child != viewport; child = child.getParent()) {
            if (child == null || child.isHidden() || child.getParent() == null
                    || !child.getParent().getChildren().contains(child)) return false;
            if (child instanceof ScrollPanel) {
                top = Math.max(top, child.getY());
                bottom = Math.min(bottom, child.getY() + child.getHeight());
                left = Math.max(left, child.getX());
                right = Math.min(right, child.getX() + child.getWidth());
            }
        }
        if (viewport.isHidden() || getY() < top || getY() + getHeight() > bottom
                || getX() < left || getX() + getWidth() > right) return false;

        // Escape the scroll clip, but stay inside the host panel and its screen clip.
        top = viewport.getY() + 8;
        bottom = viewport.getY() + viewport.getHeight() - 8;
        double below = bottom - getY() - getHeight() - GAP;
        double above = getY() - GAP - top;
        double desired = Math.min(MAX_VISIBLE, preference.getModes().size()) * ROW_HEIGHT + PADDING * 2;
        boolean upwards = below < desired && above > below;
        int previousCount = visibleCount;
        visibleCount = Math.min(Math.min(MAX_VISIBLE, preference.getModes().size()),
                (int) (((upwards ? above : below) - PADDING * 2) / ROW_HEIGHT));
        if (visibleCount < 1) return false;
        menuHeight = visibleCount * ROW_HEIGHT + PADDING * 2;
        menuWidth = Math.min(Math.max(getWidth(), textWidth + 44), Math.max(0, viewport.getWidth() - 16));
        menuX = Math.max(viewport.getX() + 8,
                Math.min(getX() + getWidth() - menuWidth, viewport.getX() + viewport.getWidth() - menuWidth - 8));
        menuY = upwards ? getY() - GAP - menuHeight : getY() + getHeight() + GAP;
        if (previousCount != visibleCount) firstVisible = Math.max(0, focused - visibleCount + 1);
        firstVisible = Math.max(0, Math.min(firstVisible, preference.getModes().size() - visibleCount));
        return true;
    }

    public void renderMenu(double mouseX, double mouseY, int wheel) {
        int hovered = optionAt(mouseX, mouseY);
        if (wheel != 0 && containsMenu(mouseX, mouseY)) {
            firstVisible = Math.max(0, Math.min(preference.getModes().size() - visibleCount,
                    firstVisible + (wheel > 0 ? -1 : 1)));
            hovered = optionAt(mouseX, mouseY);
        }
        if (hovered >= 0 && (mouseX != lastMouseX || mouseY != lastMouseY || wheel != 0)) focused = hovered;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        // An opaque surface keeps text legible over both glass and neighboring settings rows.
        roundedRect(menuX - 3, menuY + 2, menuWidth + 6, menuHeight + 4, 11,
                reAlpha(0xFF000000, getAlpha() * .12f));
        roundedRect(menuX, menuY, menuWidth, menuHeight, 9, reAlpha(AppTheme.settingsSurface(), getAlpha()));
        roundedOutline(menuX, menuY, menuWidth, menuHeight, 9, 1,
                new java.awt.Color(reAlpha(AppTheme.foreground(), getAlpha() * .16f), true));
        for (int row = 0; row < visibleCount; row++) {
            int index = firstVisible + row;
            String mode = preference.getModes().get(index);
            boolean selected = mode.equals(preference.getValue());
            double y = menuY + PADDING + row * ROW_HEIGHT;
            if (selected || index == focused) {
                roundedRect(menuX + PADDING, y, menuWidth - PADDING * 2, ROW_HEIGHT - 2, 5,
                        reAlpha(selected ? AppTheme.accentMuted() : AppTheme.settingsHover(), getAlpha()));
            }
            drawLabel(formatter.apply(mode), menuX + 14, y + (ROW_HEIGHT - 2) / 2,
                    menuWidth - 44, AppTheme.foreground());
            if (selected) {
                double x = menuX + menuWidth - 23, cy = y + 15;
                int color = reAlpha(AppTheme.accent(), getAlpha());
                StrokeIcons.checkmark(x, cy, 10, 7, 1.5, color);
            }
        }
        if (preference.getModes().size() > visibleCount) {
            double track = menuHeight - PADDING * 2;
            double thumb = Math.max(16, track * visibleCount / preference.getModes().size());
            double offset = (track - thumb) * firstVisible / (preference.getModes().size() - visibleCount);
            roundedRect(menuX + menuWidth - 5, menuY + PADDING + offset, 2, thumb, 1,
                    reAlpha(AppTheme.foreground(), getAlpha() * .3f));
        }
        CursorUtils.setOverride(hovered >= 0 ? CursorUtils.HAND : CursorUtils.ARROW);
    }

    /** All clicks are consumed, including dismissal, so covered controls cannot change. */
    public void clickMenu(double x, double y, int button) {
        int option = optionAt(x, y);
        if (button == 0 && option >= 0) {
            String value = preference.getModes().get(option);
            close();
            if (!value.equals(preference.getValue())) preference.setValue(value);
        } else if (!containsMenu(x, y)) close();
    }

    public void keyMenu(int key) {
        switch (key) {
            case Keyboard.KEY_ESCAPE, Keyboard.KEY_TAB -> close();
            case Keyboard.KEY_UP -> focused = Math.max(0, focused - 1);
            case Keyboard.KEY_DOWN -> focused = Math.min(preference.getModes().size() - 1, focused + 1);
            case Keyboard.KEY_HOME -> focused = 0;
            case Keyboard.KEY_END -> focused = preference.getModes().size() - 1;
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER, Keyboard.KEY_SPACE -> {
                String value = preference.getModes().get(focused);
                close();
                if (!value.equals(preference.getValue())) preference.setValue(value);
            }
            default -> { return; }
        }
        firstVisible = Math.max(0, Math.min(firstVisible, focused));
        if (focused >= firstVisible + Math.max(1, visibleCount))
            firstVisible = focused - Math.max(1, visibleCount) + 1;
    }

    private boolean containsMenu(double x, double y) {
        return isHovered(x, y, menuX, menuY, menuWidth, menuHeight);
    }

    private int optionAt(double x, double y) {
        if (x < menuX + PADDING || x >= menuX + menuWidth - PADDING
                || y < menuY + PADDING || y >= menuY + menuHeight - PADDING) return -1;
        int index = firstVisible + (int) ((y - menuY - PADDING) / ROW_HEIGHT);
        return index < preference.getModes().size() ? index : -1;
    }

    private void drawLabel(String text, double x, double centerY, double width, int color) {
        FontManager.pf14bold.prepareGlyphs(text);
        if (FontManager.pf14bold.getStringWidthD(text) > width) {
            int end = text.length();
            while (end > 0 && FontManager.pf14bold.getStringWidthD(text.substring(0, end) + "…") > width)
                end = text.offsetByCodePoints(end, -1);
            text = text.substring(0, end) + "…";
        }
        FontManager.pf14bold.drawCenteredStringVertical(text, x, centerY, reAlpha(color, getAlpha()));
    }

}
