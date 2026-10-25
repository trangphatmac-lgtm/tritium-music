package tritium.minecraft.protocol;

/** Java 8 wire model. No desktop or game classes may enter this package. */
public final class HudConfig {
    public long revision;
    public int accent = 0xffd60017;
    public int uninjectKey;
    public int editKey;
    public Module[] modules = defaults();

    public static Module[] defaults() {
        return new Module[] {
            new Module("musicInfo", .02, .03, 230, 56),
            new Module("musicLyrics", .5, .72, 450, 120),
            new Module("musicSpectrum", .5, 1, 640, 85),
            new Module("musicToast", .98, .03, 120, 24)
        };
    }

    public Module find(String id) {
        if (modules != null) for (Module module : modules)
            if (module != null && id != null && id.equals(module.id)) return module;
        return null;
    }

    public void normalize() {
        Module[] normalized = defaults();
        for (int i = 0; i < normalized.length; i++) {
            Module found = find(normalized[i].id);
            if (found != null) normalized[i] = found;
            normalized[i].normalize();
        }
        modules = normalized;
        uninjectKey = Math.max(0, Math.min(255, uninjectKey));
        editKey = Math.max(0, Math.min(255, editKey));
    }

    public static double clamp(double value, double min, double max) {
        return Double.isNaN(value) || Double.isInfinite(value) ? min : Math.max(min, Math.min(max, value));
    }

    public static final class Module {
        public String id;
        public boolean enabled;
        public int key;
        public double x, y, width, height, scale = 1, opacity = 1;
        public boolean artistLyric, singleLine, shadow, compact;
        public boolean graceScroll = true, translation = true, romanization, indicator = true, absoluteVolume = true;
        public String scrollEffect = "Scroll", align = "Center", spectrumStyle = "Rect";
        public double lyricHeight = 20, multiplier = 1, holdSeconds = 5;
        public int color = 0xc87d7d7d;

        public Module() { }
        public Module(String id, double x, double y, double width, double height) {
            this.id = id; this.x = x; this.y = y; this.width = width; this.height = height;
        }

        public void normalize() {
            key = Math.max(0, Math.min(255, key));
            x = clamp(x, 0, 1); y = clamp(y, 0, 1);
            width = clamp(width, 100, 1920); height = clamp(height, 24, 720);
            scale = clamp(scale, .5, 2); opacity = clamp(opacity, .05, 1);
            lyricHeight = clamp(lyricHeight, 14, 50);
            multiplier = clamp(multiplier, .1, 3); holdSeconds = clamp(holdSeconds, 1, 15);
            if (!"FadeIn".equals(scrollEffect) && !"SlideIn".equals(scrollEffect)) scrollEffect = "Scroll";
            if (!"Left".equals(align) && !"Right".equals(align)) align = "Center";
            if (!"Line".equals(spectrumStyle)) spectrumStyle = "Rect";
        }

        /** Position is a fraction of the available travel, so edge anchors survive a resize. */
        public double[] bounds(double screenWidth, double screenHeight) {
            double w = Math.min(width * scale, Math.max(1, screenWidth));
            double h = Math.min(height * scale, Math.max(1, screenHeight));
            return new double[] {x * Math.max(0, screenWidth - w), y * Math.max(0, screenHeight - h), w, h};
        }
    }
}
