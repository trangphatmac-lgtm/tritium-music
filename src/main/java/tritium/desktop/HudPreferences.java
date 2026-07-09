package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Rectangle;

public class HudPreferences {
    static final String HUD_KEY = "hud";
    private static final String EDIT_MODE_KEY = "editMode";
    private static final String LAYOUT_INITIALIZED_KEY = "layoutInitialized";
    private static final String MUSIC_INFO_KEY = "musicInfo";
    private static final String MUSIC_LYRICS_KEY = "musicLyrics";
    private static final String MUSIC_SPECTRUM_KEY = "musicSpectrum";

    private final PreferenceValue<Boolean> editMode = new PreferenceValue<>("HUD Layout Editing", false);
    private final PreferenceValue<Boolean> layoutInitialized = new PreferenceValue<>("HUD Layout Initialized", false);
    private final MusicInfoPreferences musicInfo = new MusicInfoPreferences();
    private final MusicLyricsPreferences musicLyrics = new MusicLyricsPreferences();
    private final MusicSpectrumPreferences musicSpectrum;

    public HudPreferences(PreferenceValue<Boolean> sharedSpectrumAbsoluteVolume) {
        this.musicSpectrum = new MusicSpectrumPreferences(sharedSpectrumAbsoluteVolume);
    }

    void setChangeCallback(Runnable callback) {
        editMode.setValueCallback(value -> callback.run());
        layoutInitialized.setValueCallback(value -> callback.run());
        musicInfo.setChangeCallback(callback);
        musicLyrics.setChangeCallback(callback);
        musicSpectrum.setChangeCallback(callback);
    }

    JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty(EDIT_MODE_KEY, editMode.getValue());
        json.addProperty(LAYOUT_INITIALIZED_KEY, layoutInitialized.getValue());
        json.add(MUSIC_INFO_KEY, musicInfo.toJsonObject());
        json.add(MUSIC_LYRICS_KEY, musicLyrics.toJsonObject());
        json.add(MUSIC_SPECTRUM_KEY, musicSpectrum.toJsonObject());
        return json;
    }

    void loadFromJsonObject(JsonObject json) {
        if (json == null) {
            return;
        }

        HudElementPreferences.applyBoolean(json, EDIT_MODE_KEY, editMode);
        HudElementPreferences.applyBoolean(json, LAYOUT_INITIALIZED_KEY, layoutInitialized);
        loadElement(json, MUSIC_INFO_KEY, musicInfo);
        loadElement(json, MUSIC_LYRICS_KEY, musicLyrics);
        loadElement(json, MUSIC_SPECTRUM_KEY, musicSpectrum);
    }

    private static void loadElement(JsonObject json, String key, HudElementPreferences preferences) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonObject()) {
            preferences.loadFromJsonObject(element.getAsJsonObject());
        }
    }

    public void ensureLayoutInitialized(Rectangle screenBounds) {
        if (!layoutInitialized.getValue()) {
            resetLayout(screenBounds);
            layoutInitialized.setValue(true);
        } else {
            clampTo(screenBounds);
        }
    }

    public void resetLayout(Rectangle screenBounds) {
        double screenX = screenBounds.getX();
        double screenY = screenBounds.getY();
        double screenWidth = screenBounds.getWidth();
        double screenHeight = screenBounds.getHeight();

        musicInfo.setBounds(screenX + 24, screenY + 24, 450, 110);
        musicLyrics.setBounds(screenX + Math.max(0, (screenWidth - 450) * .5), screenY + Math.max(0, screenHeight * .72), 450, 120);
        musicSpectrum.setBounds(screenX, screenY + Math.max(0, screenHeight - 170), screenWidth, 170);
        clampTo(screenBounds);
    }

    public void clampTo(Rectangle screenBounds) {
        musicInfo.clampTo(screenBounds);
        musicLyrics.clampTo(screenBounds);
        musicSpectrum.clampTo(screenBounds);
    }

    public PreferenceValue<Boolean> editMode() {
        return editMode;
    }

    public PreferenceValue<Boolean> layoutInitialized() {
        return layoutInitialized;
    }

    public MusicInfoPreferences musicInfo() {
        return musicInfo;
    }

    public MusicLyricsPreferences musicLyrics() {
        return musicLyrics;
    }

    public MusicSpectrumPreferences musicSpectrum() {
        return musicSpectrum;
    }

    public static final class MusicInfoPreferences extends HudElementPreferences {
        private static final String TURN_COMPOSER_INTO_LYRIC_KEY = "turnComposerIntoLyric";
        private final PreferenceValue<Boolean> turnComposerIntoLyric = new PreferenceValue<>("Turn Composer Into Lyric", false);

        private MusicInfoPreferences() {
            super("musicInfo", false, 24, 24, 450, 110, 1.0, 1.0, 440, 108);
        }

        @Override
        void setChangeCallback(Runnable callback) {
            super.setChangeCallback(callback);
            turnComposerIntoLyric.setValueCallback(value -> callback.run());
        }

        @Override
        JsonObject toJsonObject() {
            JsonObject json = super.toJsonObject();
            json.addProperty(TURN_COMPOSER_INTO_LYRIC_KEY, turnComposerIntoLyric.getValue());
            return json;
        }

        @Override
        void loadFromJsonObject(JsonObject json) {
            super.loadFromJsonObject(json);
            applyBoolean(json, TURN_COMPOSER_INTO_LYRIC_KEY, turnComposerIntoLyric);
        }

        public PreferenceValue<Boolean> turnComposerIntoLyric() {
            return turnComposerIntoLyric;
        }
    }

    public static final class MusicLyricsPreferences extends HudElementPreferences {
        private static final String SCROLL_EFFECT_KEY = "scrollEffect";
        private static final String ALIGN_KEY = "align";
        private static final String SINGLE_LINE_KEY = "singleLine";
        private static final String SHADOW_KEY = "shadow";
        private static final String GRACE_SCROLL_KEY = "graceScroll";

        private final ModePreference scrollEffect = new ModePreference("Lyrics Scroll Effect", "Scroll",
                new String[]{"Scroll", "FadeIn", "SlideIn"});
        private final ModePreference align = new ModePreference("Lyrics Align", "Center",
                new String[]{"Left", "Center", "Right"});
        private final PreferenceValue<Boolean> singleLine = new PreferenceValue<>("Single Line Lyrics", false);
        private final PreferenceValue<Boolean> shadow = new PreferenceValue<>("Lyrics Shadow", false);
        private final PreferenceValue<Boolean> graceScroll = new PreferenceValue<>("Elegant Scrolling", true);

        private MusicLyricsPreferences() {
            super("musicLyrics", false, 735, 780, 450, 120, 1.0, 1.0, 225, 60);
        }

        @Override
        void setChangeCallback(Runnable callback) {
            super.setChangeCallback(callback);
            scrollEffect.setValueCallback(value -> callback.run());
            align.setValueCallback(value -> callback.run());
            singleLine.setValueCallback(value -> callback.run());
            shadow.setValueCallback(value -> callback.run());
            graceScroll.setValueCallback(value -> callback.run());
        }

        @Override
        JsonObject toJsonObject() {
            JsonObject json = super.toJsonObject();
            json.addProperty(SCROLL_EFFECT_KEY, scrollEffect.getValue());
            json.addProperty(ALIGN_KEY, align.getValue());
            json.addProperty(SINGLE_LINE_KEY, singleLine.getValue());
            json.addProperty(SHADOW_KEY, shadow.getValue());
            json.addProperty(GRACE_SCROLL_KEY, graceScroll.getValue());
            return json;
        }

        @Override
        void loadFromJsonObject(JsonObject json) {
            super.loadFromJsonObject(json);
            applyMode(json, SCROLL_EFFECT_KEY, scrollEffect);
            applyMode(json, ALIGN_KEY, align);
            applyBoolean(json, SINGLE_LINE_KEY, singleLine);
            applyBoolean(json, SHADOW_KEY, shadow);
            applyBoolean(json, GRACE_SCROLL_KEY, graceScroll);
        }

        public ModePreference scrollEffect() {
            return scrollEffect;
        }

        public ModePreference align() {
            return align;
        }

        public PreferenceValue<Boolean> singleLine() {
            return singleLine;
        }

        public PreferenceValue<Boolean> shadow() {
            return shadow;
        }

        public PreferenceValue<Boolean> graceScroll() {
            return graceScroll;
        }
    }

    public static final class MusicSpectrumPreferences extends HudElementPreferences {
        private static final String STYLE_KEY = "style";
        private static final String COMPACT_KEY = "compact";
        private static final String INDICATOR_KEY = "indicator";
        private static final String ABSOLUTE_VOLUME_KEY = "absoluteVolume";
        private static final String MULTIPLIER_KEY = "multiplier";
        private static final String COLOR_KEY = "color";

        private final ModePreference style = new ModePreference("Spectrum Style", "Rect", new String[]{"Rect", "Line"});
        private final PreferenceValue<Boolean> compact = new PreferenceValue<>("Compact Spectrum", false);
        private final PreferenceValue<Boolean> indicator = new PreferenceValue<>("Spectrum Indicator", true);
        private final PreferenceValue<Boolean> absoluteVolume;
        private final NumberPreference multiplier = new NumberPreference("Spectrum Multiplier", 1.0, 0.1, 3.0, 0.1);
        private final PreferenceValue<Integer> color = new PreferenceValue<>("Spectrum Color", 0xC87D7D7D);

        private MusicSpectrumPreferences(PreferenceValue<Boolean> sharedAbsoluteVolume) {
            super("musicSpectrum", false, 0, 910, 1920, 170, 1.0, 1.0, 160, 40);
            this.absoluteVolume = sharedAbsoluteVolume;
        }

        @Override
        void setChangeCallback(Runnable callback) {
            super.setChangeCallback(callback);
            style.setValueCallback(value -> callback.run());
            compact.setValueCallback(value -> callback.run());
            indicator.setValueCallback(value -> callback.run());
            absoluteVolume.setValueCallback(value -> callback.run());
            multiplier.setValueCallback(value -> callback.run());
            color.setValueCallback(value -> callback.run());
        }

        @Override
        JsonObject toJsonObject() {
            JsonObject json = super.toJsonObject();
            json.addProperty(STYLE_KEY, style.getValue());
            json.addProperty(COMPACT_KEY, compact.getValue());
            json.addProperty(INDICATOR_KEY, indicator.getValue());
            json.addProperty(ABSOLUTE_VOLUME_KEY, absoluteVolume.getValue());
            json.addProperty(MULTIPLIER_KEY, multiplier.getValue());
            json.addProperty(COLOR_KEY, String.format("#%08X", color.getValue()));
            return json;
        }

        @Override
        void loadFromJsonObject(JsonObject json) {
            super.loadFromJsonObject(json);
            applyMode(json, STYLE_KEY, style);
            applyBoolean(json, COMPACT_KEY, compact);
            applyBoolean(json, INDICATOR_KEY, indicator);
            applyBoolean(json, ABSOLUTE_VOLUME_KEY, absoluteVolume);
            applyNumber(json, MULTIPLIER_KEY, multiplier);
            applyColor(json, COLOR_KEY, color);
        }

        private static void applyColor(JsonObject json, String key, PreferenceValue<Integer> preference) {
            JsonElement element = json.get(key);
            if (element == null || !element.isJsonPrimitive()) {
                return;
            }

            try {
                if (element.getAsJsonPrimitive().isNumber()) {
                    preference.setValue(element.getAsInt());
                } else {
                    String value = element.getAsString().trim();
                    if (value.startsWith("#")) {
                        value = value.substring(1);
                    }
                    preference.setValue((int) Long.parseLong(value, 16));
                }
            } catch (RuntimeException ignored) {
            }
        }

        public ModePreference style() {
            return style;
        }

        public PreferenceValue<Boolean> compact() {
            return compact;
        }

        public PreferenceValue<Boolean> indicator() {
            return indicator;
        }

        public PreferenceValue<Boolean> absoluteVolume() {
            return absoluteVolume;
        }

        public NumberPreference multiplier() {
            return multiplier;
        }

        public PreferenceValue<Integer> color() {
            return color;
        }
    }
}
