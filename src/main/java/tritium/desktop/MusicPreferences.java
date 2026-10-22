package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.Quality;
import tritium.settings.ClientSettings;
import tritium.i18n.Language;

public class MusicPreferences {
    // The slider's final position disables the frame-rate limit.
    public static final int UNLIMITED_FPS = 241;
    static final String VERSION_KEY = "version";
    static final String QUALITY_KEY = "music.quality";
    static final String MUSIC_TOAST_KEY = "music.toast";
    static final String SHOW_WIDGET_BOUNDARY_KEY = "ui.showWidgetBoundary";
    static final String SHOW_FPS_KEY = "ui.showFps";
    static final String MAX_FPS_KEY = "ui.maxFps";
    static final String ACCENT_COLOR_KEY = "ui.accentColor";
    static final String APPEARANCE_KEY = "ui.appearance";
    static final String LANGUAGE_KEY = "ui.language";
    static final String INTERFACE_STYLE_KEY = "ui.interfaceStyle";
    static final String LAYOUT_KEY = "ui.layout";
    static final String COVERFLOW_COVER_REFLECTION_KEY = "ui.coverflowCoverReflection";
    static final String SONG_ROW_OPACITY_KEY = "ui.songRowOpacity";
    static final String LYRIC_HOVER_OPACITY_KEY = "ui.lyricHoverOpacity";
    static final String LYRIC_DEBUG_KEY = "lyrics.debug";
    static final String AUTO_MIX_DEBUG_KEY = "player.autoMixDebug";
    static final String VOLUME_KEY = "player.volume";
    static final String AUTO_MIX_KEY = "player.autoMix";
    static final String AUTO_MIX_FULL_MODE_KEY = "player.autoMixFullMode";
    static final String AUTO_USE_BEST_LYRIC_KEY = "lyrics.autoUseBest";
    static final String PREFERRED_LYRIC_SOURCE_KEY = "lyrics.preferredSource";
    static final String SHOW_TRANSLATION_KEY = "lyrics.showTranslation";
    static final String SHOW_ROMAN_KEY = "lyrics.showRoman";
    static final String LYRIC_HEIGHT_KEY = "lyrics.height";
    static final String SPECTRUM_ABSOLUTE_VOLUME_KEY = "spectrum.absoluteVolume";

    private final ModePreference quality = new ModePreference("Music Quality", "Standard",
            new String[]{"Standard", "Higher", "ExHigh", "LossLess", "HiRes", "JyEffect", "Sky", "JyMaster"});
    private final PreferenceValue<Boolean> musicToast = new PreferenceValue<>("Music Toast", true);
    private final PreferenceValue<Boolean> showWidgetBoundary = new PreferenceValue<>("Show UI Widget Boundary", false);
    private final PreferenceValue<Boolean> showFps = new PreferenceValue<>("Show FPS", false);
    private final NumberPreference maxFps = new NumberPreference("Max FPS", 60, 30, UNLIMITED_FPS, 1);
    private final PreferenceValue<Integer> accentColor = new PreferenceValue<>("Accent Color", AppTheme.DEFAULT_ACCENT);
    private final ModePreference appearance = new ModePreference("Appearance", "Dark", new String[]{"Dark", "Light"});
    private final ModePreference language = new ModePreference("Language", "zh-CN", Language.tags());
    private final ModePreference interfaceStyle = new ModePreference("Interface Style", "Static",
            new String[]{"Static", "Blur", "LiquidGlass"});
    private final ModePreference layout = new ModePreference("Layout", "Sidebar",
            new String[]{"Sidebar", "TopBar"});
    private final PreferenceValue<Boolean> coverflowCoverReflection = new PreferenceValue<>("Coverflow Cover Reflection", true);
    private final NumberPreference songRowOpacity = new NumberPreference("Song Row Opacity", .28, 0, 1, .01);
    private final NumberPreference lyricHoverOpacity = new NumberPreference("Lyric Hover Opacity", .5, 0, 1, .01);
    private final PreferenceValue<Boolean> lyricDebug = new PreferenceValue<>("Per-word lyrics debug", false);
    private final PreferenceValue<Boolean> autoMixDebug = new PreferenceValue<>("AutoMix debug", false);
    private final NumberPreference volume = new NumberPreference("Volume", 0.1, 0.0, 1.0, 0.01);
    private final PreferenceValue<Boolean> autoMix = new PreferenceValue<>("AutoMix", false);
    private final PreferenceValue<Boolean> autoMixFullMode = new PreferenceValue<>("AutoMix Full Mode", false);
    private final PreferenceValue<Boolean> autoUseBestLyric = new PreferenceValue<>("Automatically use best lyrics", true);
    private final ModePreference preferredLyricSource = new ModePreference("Lyric matching priority", "netease",
            new String[]{"netease", "amll", "qq", "kugou"});
    private Runnable lyricSourceChangeCallback = () -> {};
    private final PreferenceValue<Boolean> showTranslation = new PreferenceValue<>("Show Translation", true);
    private final PreferenceValue<Boolean> showRoman = new PreferenceValue<>("Show Romanization in Japanese songs", false);
    private final NumberPreference lyricHeight = new NumberPreference("Lyric Height", 20.0, 14.0, 50.0, 0.5);
    private final PreferenceValue<Boolean> spectrumAbsoluteVolume = new PreferenceValue<>("Absolute Volume", true);
    private final HudPreferences hud = new HudPreferences(spectrumAbsoluteVolume);
    private final BackgroundPreferences background = new BackgroundPreferences();
    private Runnable changeCallback = () -> {
    };
    private boolean loading;

    public MusicPreferences() {
        quality.setValueCallback(str -> {
            applyQuality(str);
            fireChanged();
        });
        musicToast.setValueCallback(value -> fireChanged());
        showFps.setValueCallback(value -> fireChanged());
        maxFps.setValueCallback(value -> fireChanged());
        accentColor.setValueCallback(value -> fireChanged());
        appearance.setValueCallback(value -> fireChanged());
        language.setValueCallback(value -> fireChanged());
        interfaceStyle.setValueCallback(value -> fireChanged());
        layout.setValueCallback(value -> fireChanged());
        coverflowCoverReflection.setValueCallback(value -> fireChanged());
        songRowOpacity.setValueCallback(value -> fireChanged());
        lyricHoverOpacity.setValueCallback(value -> fireChanged());
        showWidgetBoundary.setValueCallback(value -> {
            ClientSettings.SHOW_WIDGET_BOUNDARY = value;
            fireChanged();
        });
        lyricDebug.setValueCallback(value -> {
            ClientSettings.DEBUG_MODE = value;
            fireChanged();
        });
        autoMixDebug.setValueCallback(value -> fireChanged());
        volume.setValueCallback(value -> {
            CloudMusic.setVolume(value.floatValue());
            fireChanged();
        });
        autoMix.setValueCallback(value -> {
            CloudMusic.autoMixSettingChanged();
            fireChanged();
        });
        autoMixFullMode.setValueCallback(value -> {
            CloudMusic.autoMixSettingChanged();
            fireChanged();
        });
        autoUseBestLyric.setValueCallback(value -> lyricSourceChanged());
        preferredLyricSource.setValueCallback(value -> lyricSourceChanged());
        showTranslation.setValueCallback(value -> fireChanged());
        showRoman.setValueCallback(value -> fireChanged());
        lyricHeight.setValueCallback(value -> fireChanged());
        spectrumAbsoluteVolume.setValueCallback(value -> fireChanged());
        hud.setChangeCallback(this::fireChanged);
        background.setChangeCallback(this::fireChanged);

        applyQuality(quality.getValue());
        ClientSettings.SHOW_WIDGET_BOUNDARY = showWidgetBoundary.getValue();
        ClientSettings.DEBUG_MODE = lyricDebug.getValue();
    }

    public void setChangeCallback(Runnable changeCallback) {
        this.changeCallback = changeCallback == null ? () -> {
        } : changeCallback;
    }

    public void setLyricSourceChangeCallback(Runnable callback) {
        lyricSourceChangeCallback = callback == null ? () -> {} : callback;
    }

    private void lyricSourceChanged() {
        if (!loading) {
            lyricSourceChangeCallback.run();
            fireChanged();
        }
    }

    private void fireChanged() {
        if (!loading) {
            changeCallback.run();
        }
    }

    private void applyQuality(String qualityName) {
        for (Quality value : Quality.values()) {
            if (value.getQuality().equalsIgnoreCase(qualityName)) {
                CloudMusic.quality = value;
                break;
            }
        }
    }

    JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty(VERSION_KEY, 1);
        json.addProperty(QUALITY_KEY, quality.getValue());
        json.addProperty(MUSIC_TOAST_KEY, musicToast.getValue());
        json.addProperty(SHOW_WIDGET_BOUNDARY_KEY, showWidgetBoundary.getValue());
        json.addProperty(SHOW_FPS_KEY, showFps.getValue());
        json.addProperty(MAX_FPS_KEY, maxFps.getValue());
        json.addProperty(ACCENT_COLOR_KEY, accentColor.getValue());
        json.addProperty(APPEARANCE_KEY, appearance.getValue());
        json.addProperty(LANGUAGE_KEY, language.getValue());
        json.addProperty(INTERFACE_STYLE_KEY, interfaceStyle.getValue());
        json.addProperty(LAYOUT_KEY, layout.getValue());
        json.addProperty(COVERFLOW_COVER_REFLECTION_KEY, coverflowCoverReflection.getValue());
        json.addProperty(SONG_ROW_OPACITY_KEY, songRowOpacity.getValue());
        json.addProperty(LYRIC_HOVER_OPACITY_KEY, lyricHoverOpacity.getValue());
        json.addProperty(LYRIC_DEBUG_KEY, lyricDebug.getValue());
        json.addProperty(AUTO_MIX_DEBUG_KEY, autoMixDebug.getValue());
        json.addProperty(VOLUME_KEY, volume.getValue());
        json.addProperty(AUTO_MIX_KEY, autoMix.getValue());
        json.addProperty(AUTO_MIX_FULL_MODE_KEY, autoMixFullMode.getValue());
        json.addProperty(AUTO_USE_BEST_LYRIC_KEY, autoUseBestLyric.getValue());
        json.addProperty(PREFERRED_LYRIC_SOURCE_KEY, preferredLyricSource.getValue());
        json.addProperty(SHOW_TRANSLATION_KEY, showTranslation.getValue());
        json.addProperty(SHOW_ROMAN_KEY, showRoman.getValue());
        json.addProperty(LYRIC_HEIGHT_KEY, lyricHeight.getValue());
        json.addProperty(SPECTRUM_ABSOLUTE_VOLUME_KEY, spectrumAbsoluteVolume.getValue());
        json.add(HudPreferences.HUD_KEY, hud.toJsonObject());
        json.add("background", background.toJsonObject());
        return json;
    }

    void loadFromJsonObject(JsonObject json) {
        if (json == null) {
            return;
        }

        loading = true;
        try {
            applyMode(json, QUALITY_KEY, quality);
            applyBoolean(json, MUSIC_TOAST_KEY, musicToast);
            applyBoolean(json, SHOW_WIDGET_BOUNDARY_KEY, showWidgetBoundary);
            applyBoolean(json, SHOW_FPS_KEY, showFps);
            applyNumber(json, MAX_FPS_KEY, maxFps);
            applyAccentColor(json);
            applyMode(json, APPEARANCE_KEY, appearance);
            applyMode(json, LANGUAGE_KEY, language);
            applyMode(json, INTERFACE_STYLE_KEY, interfaceStyle);
            applyMode(json, LAYOUT_KEY, layout);
            applyBoolean(json, COVERFLOW_COVER_REFLECTION_KEY, coverflowCoverReflection);
            applyOpacity(json, SONG_ROW_OPACITY_KEY, songRowOpacity);
            applyOpacity(json, LYRIC_HOVER_OPACITY_KEY, lyricHoverOpacity);
            JsonElement backgroundElement = json.get("background");
            if (backgroundElement != null && backgroundElement.isJsonObject()) {
                background.loadFromJsonObject(backgroundElement.getAsJsonObject());
            }
            applyBoolean(json, LYRIC_DEBUG_KEY, lyricDebug);
            applyBoolean(json, AUTO_MIX_DEBUG_KEY, autoMixDebug);
            applyNumber(json, VOLUME_KEY, volume);
            applyBoolean(json, AUTO_MIX_KEY, autoMix);
            applyBoolean(json, AUTO_MIX_FULL_MODE_KEY, autoMixFullMode);
            applyBoolean(json, AUTO_USE_BEST_LYRIC_KEY, autoUseBestLyric);
            applyMode(json, PREFERRED_LYRIC_SOURCE_KEY, preferredLyricSource);
            applyBoolean(json, SHOW_TRANSLATION_KEY, showTranslation);
            applyBoolean(json, SHOW_ROMAN_KEY, showRoman);
            applyNumber(json, LYRIC_HEIGHT_KEY, lyricHeight);
            applyBoolean(json, SPECTRUM_ABSOLUTE_VOLUME_KEY, spectrumAbsoluteVolume);
            JsonElement hudElement = json.get(HudPreferences.HUD_KEY);
            if (hudElement != null && hudElement.isJsonObject()) {
                hud.loadFromJsonObject(hudElement.getAsJsonObject());
            }
        } finally {
            loading = false;
        }
    }

    private void applyAccentColor(JsonObject json) {
        JsonElement element = json.get(ACCENT_COLOR_KEY);
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }
        try {
            long color = Long.parseLong(element.getAsString());
            if (color >= Integer.MIN_VALUE && color <= 0xFFFFFFFFL) {
                accentColor.setValue((int) color | 0xFF000000);
            }
        } catch (NumberFormatException ignored) {
            // Keep the default and continue loading the other settings.
        }
    }

    private void applyMode(JsonObject json, String key, ModePreference preference) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        String value = element.getAsString();
        if (preference.getModes().contains(value)) {
            preference.setValue(value);
        }
    }

    private void applyBoolean(JsonObject json, String key, PreferenceValue<Boolean> preference) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            preference.setValue(element.getAsBoolean());
        }
    }

    private void applyNumber(JsonObject json, String key, NumberPreference preference) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            preference.setValue(element.getAsDouble());
        }
    }

    private void applyOpacity(JsonObject json, String key, NumberPreference preference) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return;
        try {
            double value = element.getAsDouble();
            if (Double.isFinite(value)) preference.setValue(value);
        } catch (NumberFormatException ignored) {
            // Keep the default when an opacity entry is malformed.
        }
    }

    public ModePreference quality() {
        return quality;
    }

    public BackgroundPreferences background() {
        return background;
    }

    public PreferenceValue<Boolean> musicToast() {
        return musicToast;
    }

    public PreferenceValue<Boolean> showWidgetBoundary() {
        return showWidgetBoundary;
    }

    public PreferenceValue<Boolean> showFps() {
        return showFps;
    }

    public NumberPreference maxFps() {
        return maxFps;
    }

    public PreferenceValue<Integer> accentColor() {
        return accentColor;
    }

    public ModePreference appearance() {
        return appearance;
    }

    public ModePreference language() {
        return language;
    }

    public ModePreference interfaceStyle() {
        return interfaceStyle;
    }

    public ModePreference layout() {
        return layout;
    }

    public PreferenceValue<Boolean> coverflowCoverReflection() {
        return coverflowCoverReflection;
    }

    public NumberPreference songRowOpacity() {
        return songRowOpacity;
    }

    public NumberPreference lyricHoverOpacity() {
        return lyricHoverOpacity;
    }

    public PreferenceValue<Boolean> lyricDebug() {
        return lyricDebug;
    }

    public PreferenceValue<Boolean> autoMixDebug() {
        return autoMixDebug;
    }

    public NumberPreference volume() {
        return volume;
    }

    public PreferenceValue<Boolean> autoMix() {
        return autoMix;
    }

    public PreferenceValue<Boolean> autoMixFullMode() {
        return autoMixFullMode;
    }

    public PreferenceValue<Boolean> autoUseBestLyric() { return autoUseBestLyric; }

    public ModePreference preferredLyricSource() { return preferredLyricSource; }

    public PreferenceValue<Boolean> showTranslation() {
        return showTranslation;
    }

    public PreferenceValue<Boolean> showRoman() {
        return showRoman;
    }

    public NumberPreference lyricHeight() {
        return lyricHeight;
    }

    public PreferenceValue<Boolean> spectrumAbsoluteVolume() {
        return spectrumAbsoluteVolume;
    }

    public HudPreferences hud() {
        return hud;
    }
}
