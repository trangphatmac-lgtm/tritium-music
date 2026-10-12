package tritium.desktop;

/** Colors derived from the user's accent, read at render time for live updates. */
public final class AppTheme {
    public static final int DEFAULT_ACCENT = 0xFFD60017;
    // Prefer white text; reserve black for very bright accents.
    private static final double DARK_TEXT_LUMINANCE_THRESHOLD = .70;

    private AppTheme() {
    }

    public static int accent() {
        return DesktopAppState.preferences().accentColor().getValue() | 0xFF000000;
    }

    public static int accentHover() {
        return mix(accent(), 0xFFFFFFFF, .2f);
    }

    public static int accentMuted() {
        return mix(0xFF242424, accent(), .25f);
    }

    public static int onAccent() {
        return contrastingText(accent());
    }

    public static int contrastingText(int color) {
        double luminance = .2126 * linear((color >> 16) & 255)
                + .7152 * linear((color >> 8) & 255) + .0722 * linear(color & 255);
        return luminance > DARK_TEXT_LUMINANCE_THRESHOLD ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static int mix(int from, int to, float amount) {
        int r = Math.round(((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int g = Math.round(((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int b = Math.round((from & 255) * (1 - amount) + (to & 255) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
