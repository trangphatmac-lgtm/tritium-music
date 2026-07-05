package tritium.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.Quality;
import tritium.settings.ClientSettings;

public class MusicPreferences {
    static final String VERSION_KEY = "version";
    static final String QUALITY_KEY = "music.quality";
    static final String MUSIC_TOAST_KEY = "music.toast";
    static final String SHOW_WIDGET_BOUNDARY_KEY = "ui.showWidgetBoundary";
    static final String LYRIC_DEBUG_KEY = "lyrics.debug";
    static final String VOLUME_KEY = "player.volume";
    static final String SHOW_TRANSLATION_KEY = "lyrics.showTranslation";
    static final String SHOW_ROMAN_KEY = "lyrics.showRoman";
    static final String LYRIC_HEIGHT_KEY = "lyrics.height";
    static final String SPECTRUM_ABSOLUTE_VOLUME_KEY = "spectrum.absoluteVolume";

    private final ModePreference quality = new ModePreference("Music Quality", "Standard",
            new String[]{"Standard", "Higher", "ExHigh", "LossLess", "HiRes", "JyEffect", "Sky", "JyMaster"});
    private final PreferenceValue<Boolean> musicToast = new PreferenceValue<>("Music Toast", true);
    private final PreferenceValue<Boolean> showWidgetBoundary = new PreferenceValue<>("Show UI Widget Boundary", false);
    private final PreferenceValue<Boolean> lyricDebug = new PreferenceValue<>("Per-word lyrics debug", false);
    private final NumberPreference volume = new NumberPreference("Volume", 0.1, 0.0, 1.0, 0.01);
    private final PreferenceValue<Boolean> showTranslation = new PreferenceValue<>("Show Translation", true);
    private final PreferenceValue<Boolean> showRoman = new PreferenceValue<>("Show Romanization in Japanese songs", false);
    private final NumberPreference lyricHeight = new NumberPreference("Lyric Height", 20.0, 14.0, 50.0, 0.5);
    private final PreferenceValue<Boolean> spectrumAbsoluteVolume = new PreferenceValue<>("Absolute Volume", true);
    private Runnable changeCallback = () -> {
    };
    private boolean loading;

    public MusicPreferences() {
        quality.setValueCallback(str -> {
            applyQuality(str);
            fireChanged();
        });
        musicToast.setValueCallback(value -> fireChanged());
        showWidgetBoundary.setValueCallback(value -> {
            ClientSettings.SHOW_WIDGET_BOUNDARY = value;
            fireChanged();
        });
        lyricDebug.setValueCallback(value -> {
            ClientSettings.DEBUG_MODE = value;
            fireChanged();
        });
        volume.setValueCallback(value -> {
            if (CloudMusic.player != null) {
                CloudMusic.player.setVolume(value.floatValue());
            }
            fireChanged();
        });
        showTranslation.setValueCallback(value -> fireChanged());
        showRoman.setValueCallback(value -> fireChanged());
        lyricHeight.setValueCallback(value -> fireChanged());
        spectrumAbsoluteVolume.setValueCallback(value -> fireChanged());

        applyQuality(quality.getValue());
        ClientSettings.SHOW_WIDGET_BOUNDARY = showWidgetBoundary.getValue();
        ClientSettings.DEBUG_MODE = lyricDebug.getValue();
    }

    public void setChangeCallback(Runnable changeCallback) {
        this.changeCallback = changeCallback == null ? () -> {
        } : changeCallback;
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
        json.addProperty(LYRIC_DEBUG_KEY, lyricDebug.getValue());
        json.addProperty(VOLUME_KEY, volume.getValue());
        json.addProperty(SHOW_TRANSLATION_KEY, showTranslation.getValue());
        json.addProperty(SHOW_ROMAN_KEY, showRoman.getValue());
        json.addProperty(LYRIC_HEIGHT_KEY, lyricHeight.getValue());
        json.addProperty(SPECTRUM_ABSOLUTE_VOLUME_KEY, spectrumAbsoluteVolume.getValue());
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
            applyBoolean(json, LYRIC_DEBUG_KEY, lyricDebug);
            applyNumber(json, VOLUME_KEY, volume);
            applyBoolean(json, SHOW_TRANSLATION_KEY, showTranslation);
            applyBoolean(json, SHOW_ROMAN_KEY, showRoman);
            applyNumber(json, LYRIC_HEIGHT_KEY, lyricHeight);
            applyBoolean(json, SPECTRUM_ABSOLUTE_VOLUME_KEY, spectrumAbsoluteVolume);
        } finally {
            loading = false;
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

    public ModePreference quality() {
        return quality;
    }

    public PreferenceValue<Boolean> musicToast() {
        return musicToast;
    }

    public PreferenceValue<Boolean> showWidgetBoundary() {
        return showWidgetBoundary;
    }

    public PreferenceValue<Boolean> lyricDebug() {
        return lyricDebug;
    }

    public NumberPreference volume() {
        return volume;
    }

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
}
