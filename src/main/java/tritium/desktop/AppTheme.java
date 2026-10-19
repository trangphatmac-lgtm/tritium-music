package tritium.desktop;

import tritium.desktop.background.latent.LatentPreferences;

/** Main-window palette, sampled once per frame so all controls transition together. */
public final class AppTheme {
    public static final int DEFAULT_ACCENT = 0xFFD60017;
    // Prefer white text; reserve black for very bright accents.
    private static final double DARK_TEXT_LUMINANCE_THRESHOLD = .70;
    private static final Transition TRANSITION = new Transition();
    private static float lightAmount;

    private AppTheme() {
    }

    public static void beginFrame() {
        lightAmount = TRANSITION.update(DesktopAppState.preferences().appearance().getValue().equals("Light"), System.nanoTime());
    }

    public static int color(int dark, int light) {
        return mix(dark, light, lightAmount);
    }

    public static int background() {
        return latentEnabled() ? latentColor(LatentPreferences.Color.BACKGROUND) : color(0xFF1E1E1E, 0xFFFFFFFF);
    }

    public static int foreground() {
        return latentEnabled() ? latentColor(LatentPreferences.Color.PRIMARY) : color(0xFFFFFFFF, 0xFF000000);
    }

    private static boolean latentEnabled() {
        return DesktopAppState.preferences().background().mode().getValue().equals("latent");
    }

    public static int latentColor(LatentPreferences.Color part) {
        var palette = DesktopAppState.preferences().background().latent();
        return color(palette.color(false, part).getValue(), palette.color(true, part).getValue());
    }

    public static int secondary() {
        return latentEnabled() ? latentColor(LatentPreferences.Color.SECONDARY) : color(0xFFAAAAAA, 0xFF6B6B6B);
    }

    /** Used only by main-window panels; lyric and HUD palettes do not consult it. */
    public static float backgroundPanelOpacity() {
        return DesktopAppState.background().isActive()
                ? DesktopAppState.preferences().background().panelOpacity().getValue().floatValue() : 1f;
    }

    public static int surface() {
        return latentEnabled() ? mix(background(), foreground(), .04f) : color(0xFF232323, 0xFFF7F7F7);
    }

    public static int hover() {
        return latentEnabled() ? mix(background(), foreground(), .10f) : color(0xFF353535, 0xFFE8E8E8);
    }

    public static int settingsSurface() {
        return latentEnabled() ? surface() : color(0xFF242424, 0xFFF5F5F5);
    }

    public static int settingsHover() {
        return latentEnabled() ? hover() : color(0xFF303030, 0xFFEBEBEB);
    }

    public static int inputBackground() {
        return color(0xFF000000, 0xFFFFFFFF);
    }

    public static int accent() {
        if (latentEnabled()) return latentColor(LatentPreferences.Color.ACCENT);
        return DesktopAppState.preferences().accentColor().getValue() | 0xFF000000;
    }

    public static int accentHover() {
        return mix(accent(), 0xFFFFFFFF, .2f);
    }

    public static int accentMuted() {
        return mix(settingsSurface(), accent(), .25f);
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
        int a = Math.round((from >>> 24) * (1 - amount) + (to >>> 24) * amount);
        int r = Math.round(((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int g = Math.round(((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int b = Math.round((from & 255) * (1 - amount) + (to & 255) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Critically damped motion preserves both position and velocity when reversed. */
    static final class Transition {
        private static final double SPEED = 18;
        private boolean initialized;
        private long lastFrame;
        private double position;
        private double velocity;

        float update(boolean light, long now) {
            double target = light ? 1 : 0;
            if (!initialized) {
                initialized = true;
                position = target; // Restore the saved mode without a startup flash.
            } else {
                double elapsed = Math.max(0, (now - lastFrame) / 1_000_000_000.0);
                double offset = position - target;
                double spring = velocity + SPEED * offset;
                double decay = Math.exp(-SPEED * elapsed);
                position = target + (offset + spring * elapsed) * decay;
                velocity = (velocity - SPEED * spring * elapsed) * decay;
                if (Math.abs(position - target) < .0001 && Math.abs(velocity) < .001) {
                    position = target;
                    velocity = 0;
                }
            }
            lastFrame = now;
            return (float) Math.max(0, Math.min(1, position));
        }
    }
}
