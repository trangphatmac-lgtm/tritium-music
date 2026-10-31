package tritium.desktop;

/** Shared bounds keep both read-only diagnostics visible above the player controls. */
final class DebugOverlayLayout {
    record Area(double x, double y, double width, double height) { }

    static Area area(double width, double height, boolean both, boolean audioEffects) {
        double availableHeight = Math.max(1, height - 150);
        double panelWidth = Math.min(520, width - 24);
        if (!both) return new Area(width - panelWidth - 12, 44, panelWidth, availableHeight);
        if (width >= 760) {
            panelWidth = Math.min(520, (width - 36) / 2);
            return new Area(audioEffects ? width - panelWidth - 12 : width - panelWidth * 2 - 24,
                    44, panelWidth, availableHeight);
        }
        double panelHeight = Math.max(1, (availableHeight - 12) / 2);
        return new Area(width - panelWidth - 12, audioEffects ? 56 + panelHeight : 44, panelWidth, panelHeight);
    }
}
