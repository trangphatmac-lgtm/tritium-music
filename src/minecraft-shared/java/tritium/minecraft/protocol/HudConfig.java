package tritium.minecraft.protocol;

/** Java 8 wire model. No desktop or game classes may enter this package. */
public final class HudConfig {
    public long revision;
    public int accent = 0xffd60017;
    public int uninjectKey;
    public int editKey;
    public boolean hidden;
    public String editorHint = "Drag to move / Scroll to scale / Esc to finish";
    public Module[] modules = defaults();

    public static Module[] defaults() {
        return new Module[] {
            new Module("musicInfo", .02, .03, 230, 56),
            new Module("musicLyrics", .5, .72, 450, 120),
            new Module("musicSpectrum", .5, 1, 640, 85),
            new Module("musicToast", .98, .03, 120, 24),
            worldLyricsDefaults(),
            new Module("aimAssist", 0, 0, 100, 24),
            new Module("autoBlock", 0, 0, 100, 24),
            new Module("autoClicker", 0, 0, 100, 24),
            new Module("sprint", 0, 0, 100, 24),
            new Module("stasis", 0, 0, 100, 24)
        };
    }

    private static Module worldLyricsDefaults() {
        Module module = new Module("worldLyrics", .5, .5, 200, 24);
        module.color = 0xfff7e1d7;
        return module;
    }

    public Module find(String id) {
        if (modules != null) for (Module module : modules)
            if (module != null && id != null && id.equals(module.id)) return module;
        return null;
    }

    public void normalize() {
        if (editorHint == null) editorHint = "Drag to move / Scroll to scale / Esc to finish";
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
        public WorldLyrics world = new WorldLyrics();
        public CombatSettings combat = new CombatSettings();
        public MovementSettings movement = new MovementSettings();
        public transient double textWidth = 100;

        public Module() { }
        public Module(String id, double x, double y, double width, double height) {
            this.id = id; this.x = x; this.y = y; this.width = width; this.height = height;
            combat.normalize(id);
        }

        public void normalize() {
            key = Math.max(0, Math.min(255, key));
            x = clamp(x, 0, 1); y = clamp(y, 0, 1);
            width = clamp(width, 100, 1920); height = clamp(height, 24, 720);
            scale = clamp(scale, .5, isWorld() ? 3 : 2); opacity = clamp(opacity, .05, 1);
            lyricHeight = clamp(lyricHeight, 14, 50);
            multiplier = clamp(multiplier, .1, 3); holdSeconds = clamp(holdSeconds, 1, 15);
            if (!"FadeIn".equals(scrollEffect) && !"SlideIn".equals(scrollEffect)) scrollEffect = "Scroll";
            if (!"Left".equals(align) && !"Right".equals(align)) align = "Center";
            if (!"Line".equals(spectrumStyle)) spectrumStyle = "Rect";
            if (world == null) world = new WorldLyrics();
            world.normalize();
            if (combat == null) combat = new CombatSettings();
            combat.normalize(id);
            if (movement == null) movement = new MovementSettings();
            movement.normalize();
        }

        public boolean isCombat() { return CombatSettings.isCombat(id); }
        public boolean isMovement() { return MovementSettings.isMovement(id); }
        public ModuleCategory category() { return ModuleCategory.forModule(id); }
        public boolean isHidden() { return category().isHidden(); }
        public boolean isHud() { return !isWorld() && !isHidden(); }

        public boolean isWorld() { return "worldLyrics".equals(id); }

        /** HUD positions use available-travel fractions; Sprint retains Raven's GUI pixel coordinates. */
        public double[] bounds(double screenWidth, double screenHeight) {
            if ("sprint".equals(id)) return new double[]{
                Math.min(movement.textX, Math.max(0, screenWidth - textWidth)),
                Math.min(movement.textY, Math.max(0, screenHeight - 9)), textWidth, 9};
            double w = Math.min(width * scale, Math.max(1, screenWidth));
            double h = Math.min(height * scale, Math.max(1, screenHeight));
            return new double[] {x * Math.max(0, screenWidth - w), y * Math.max(0, screenHeight - h), w, h};
        }
    }

    /** Gale-style world effects; position is generated in the game, never edited as a HUD rectangle. */
    public static final class WorldLyrics {
        public double offset;
        public double distance = 10;
        public boolean glow = true;
        public String glowColor = "Text";
        public double glowStrength = .3, glowRadius = .5;
        public boolean smoothFont = true;
        public boolean wiggle = true;
        public double wiggleFps = 8, wiggleAmount = .8;
        public boolean wordPop = true, facePlayer = true, keepAway = true;
        public boolean stayOnScreen;
        public double spread = 30;
        public boolean splitLines = true;

        public void normalize() {
            offset = finite(offset, 0, -2000, 2000);
            distance = finite(distance, 10, 3, 40);
            glowStrength = finite(glowStrength, .3, 0, 3);
            glowRadius = finite(glowRadius, .5, 0, 1);
            wiggleFps = Math.rint(finite(wiggleFps, 8, 4, 24));
            wiggleAmount = finite(wiggleAmount, .8, 0, 2);
            spread = finite(spread, 30, 0, 60);
            if (!"Pink".equals(glowColor) && !"Blue".equals(glowColor) && !"Gold".equals(glowColor)) glowColor = "Text";
        }

        private static double finite(double value, double fallback, double min, double max) {
            return Double.isNaN(value) || Double.isInfinite(value) ? fallback : clamp(value, min, max);
        }
    }
}
