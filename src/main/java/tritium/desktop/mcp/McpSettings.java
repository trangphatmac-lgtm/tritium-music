package tritium.desktop.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import tritium.desktop.*;
import tritium.desktop.background.latent.LatentPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the same values and callbacks as the settings widgets. Keys are stable across UI languages. */
public final class McpSettings {
    private static final Gson JSON = new Gson();
    private final Map<String, PreferenceValue<?>> values = new LinkedHashMap<>();

    public McpSettings(MusicPreferences p) {
        add("music.quality", p.quality());
        add("cache.musicLimitGb", p.musicCacheLimitGb());
        add("download.quality", p.downloadQuality());
        add("download.directory", p.downloadDirectory());
        add("music.toast", p.musicToast());
        add("minecraftHidden", p.minecrafthidden());
        add("ui.showWidgetBoundary", p.showWidgetBoundary());
        add("ui.showFps", p.showFps());
        add("ui.maxFps", p.maxFps());
        add("ui.accentColor", p.accentColor());
        add("ui.appearance", p.appearance());
        add("ui.language", p.language());
        add("ui.interfaceStyle", p.interfaceStyle());
        add("ui.layout", p.layout());
        add("ui.settingsLayout", p.settingsLayout());
        add("ui.playerControls", p.playerControls());
        add("ui.miniAutoCollapse", p.miniAutoCollapse());
        add("ui.coverflowCoverReflection", p.coverflowCoverReflection());
        add("ui.songRowOpacity", p.songRowOpacity());
        add("ui.lyricHoverOpacity", p.lyricHoverOpacity());
        add("player.volume", p.volume());
        add("player.autoMix", p.autoMix());
        add("player.autoMixFullMode", p.autoMixFullMode());
        add("player.autoMixDebug", p.autoMixDebug());
        add("player.audioEffectDebug", p.audioEffectDebug());
        add("lyrics.debug", p.lyricDebug());
        add("lyrics.autoUseBest", p.autoUseBestLyric());
        add("lyrics.preferredSource", p.preferredLyricSource());
        add("lyrics.showTranslation", p.showTranslation());
        add("lyrics.showRoman", p.showRoman());
        add("lyrics.showBottomLyrics", p.showBottomLyrics());
        add("lyrics.height", p.lyricHeight());
        add("lyrics.style", p.lyricStyle());
        add("spectrum.absoluteVolume", p.spectrumAbsoluteVolume());
        add("mcp.enabled", p.mcpEnabled());
        add("mcp.port", p.mcpPort());

        var n = p.navigation();
        add("navigation.mode", n.mode());
        add("navigation.coverflow", n.coverflow());
        add("navigation.cardStyle", n.cardStyle());
        add("navigation.rememberPosition", n.rememberPosition());
        add("navigation.fullBleed", n.fullBleed());
        add("navigation.squareCards", n.squareCards());
        add("navigation.minScale", n.minScale());
        add("navigation.minOpacity", n.minOpacity());
        add("navigation.wallLook", n.wallLook());
        add("navigation.wallWindows", n.wallWindows());
        add("navigation.stackEdges", n.stackEdges());
        add("navigation.backdropLyrics", n.backdropLyrics());
        add("navigation.backdropBlur", n.backdropBlur());

        var b = p.background();
        add("background.mode", b.mode());
        add("background.coverMode", b.coverMode());
        add("background.nomandEffect", b.nomandEffect());
        add("background.monetLayout", b.monetLayout());
        add("background.applyToLyrics", b.applyToLyrics());
        add("background.lyricsTransparency", b.lyricsTransparency());
        add("background.pictureSource", b.pictureSource());
        add("background.pictureFile", b.pictureFile());
        add("background.pictureFolder", b.pictureFolder());
        add("background.videoFile", b.videoFile());
        add("background.interval", b.interval());
        add("background.shuffle", b.shuffle());
        add("background.fit", b.fit());
        add("background.strength", b.strength());
        add("background.panelOpacity", b.panelOpacity());
        add("background.shader", b.shader());
        add("background.shaderSpeed", b.shaderSpeed());
        var latent = b.latent();
        add("background.latent.colorSource", latent.colorSource());
        add("background.latent.layers", latent.layers());
        add("background.latent.beat", latent.beat());
        add("background.latent.speed", latent.speed());
        for (boolean light : new boolean[]{false, true}) {
            for (var color : LatentPreferences.Color.values()) {
                add("background.latent." + (light ? "light." : "dark.") + color.name(), latent.color(light, color));
            }
        }

        var effects = p.audioEffects();
        add("audioEffects.mode", effects.mode());
        add("audioEffects.masterTone", effects.masterTone());
        add("audioEffects.manual.bass", effects.bass());
        add("audioEffects.manual.treble", effects.treble());
        add("audioEffects.manual.vocal", effects.vocal());
        add("audioEffects.manual.vibe", effects.vibe());
        add("audioEffects.manual.live", effects.live());

        var hud = p.hud();
        add("hud.editMode", hud.editMode());
        addHud("hud.musicInfo", hud.musicInfo());
        addHud("hud.musicLyrics", hud.musicLyrics());
        addHud("hud.musicSpectrum", hud.musicSpectrum());
        add("hud.musicInfo.turnComposerIntoLyric", hud.musicInfo().turnComposerIntoLyric());
        var lyrics = hud.musicLyrics();
        add("hud.musicLyrics.scrollEffect", lyrics.scrollEffect());
        add("hud.musicLyrics.align", lyrics.align());
        add("hud.musicLyrics.singleLine", lyrics.singleLine());
        add("hud.musicLyrics.shadow", lyrics.shadow());
        add("hud.musicLyrics.graceScroll", lyrics.graceScroll());
        var spectrum = hud.musicSpectrum();
        add("hud.musicSpectrum.style", spectrum.style());
        add("hud.musicSpectrum.compact", spectrum.compact());
        add("hud.musicSpectrum.indicator", spectrum.indicator());
        add("hud.musicSpectrum.absoluteVolume", spectrum.absoluteVolume());
        add("hud.musicSpectrum.multiplier", spectrum.multiplier());
        add("hud.musicSpectrum.color", spectrum.color());
    }

    private void add(String key, PreferenceValue<?> value) { values.put(key, value); }

    private void addHud(String key, HudElementPreferences hud) {
        add(key + ".enabled", hud.enabled());
        add(key + ".x", hud.x());
        add(key + ".y", hud.y());
        add(key + ".width", hud.width());
        add(key + ".height", hud.height());
        add(key + ".opacity", hud.opacity());
        add(key + ".scale", hud.scale());
    }

    public JsonObject snapshot() {
        JsonObject settings = new JsonObject();
        values.forEach((key, preference) -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", preference.getName());
            item.add("value", JSON.toJsonTree(preference.getValue()));
            item.add("schema", schema(preference));
            settings.add(key, item);
        });
        return settings;
    }

    private JsonObject schema(PreferenceValue<?> preference) {
        JsonObject schema = new JsonObject();
        Object value = preference.getValue();
        String type = value instanceof Boolean ? "boolean" : value instanceof Integer ? "integer"
                : value instanceof Number ? "number" : "string";
        schema.addProperty("type", type);
        if (preference instanceof ModePreference mode) schema.add("enum", JSON.toJsonTree(mode.getModes()));
        if (preference instanceof NumberPreference number) {
            schema.addProperty("minimum", number.getMin());
            schema.addProperty("maximum", number.getMax());
            schema.addProperty("step", number.getIncrement());
        }
        if (value instanceof Integer) {
            schema.addProperty("minimum", Integer.MIN_VALUE);
            schema.addProperty("maximum", 0xFFFFFFFFL);
            schema.addProperty("description", "ARGB color as a signed or unsigned 32-bit integer");
        }
        return schema;
    }

    /** Validate the entire patch before running any callback. Unspecified settings retain their values. */
    public JsonObject update(JsonObject changes) {
        if (changes.entrySet().isEmpty()) throw new IllegalArgumentException("changes must contain at least one setting");
        Map<PreferenceValue<?>, Object> updates = new LinkedHashMap<>();
        changes.entrySet().forEach(entry -> {
            PreferenceValue<?> preference = values.get(entry.getKey());
            if (preference == null) throw new IllegalArgumentException("Unknown setting: " + entry.getKey());
            Object value = validate(entry.getKey(), preference, entry.getValue());
            if (updates.containsKey(preference) && !updates.get(preference).equals(value))
                throw new IllegalArgumentException("Conflicting values for shared setting: " + entry.getKey());
            updates.put(preference, value);
        });
        updates.forEach(McpSettings::apply);
        return snapshot();
    }

    private Object validate(String key, PreferenceValue<?> preference, JsonElement element) {
        if (!element.isJsonPrimitive()) throw new IllegalArgumentException(key + " requires a primitive value");
        JsonPrimitive value = element.getAsJsonPrimitive();
        Object current = preference.getValue();
        if (current instanceof Boolean && value.isBoolean()) return value.getAsBoolean();
        if (current instanceof String && value.isString()) {
            String text = value.getAsString();
            if (preference instanceof ModePreference mode && !mode.getModes().contains(text))
                throw new IllegalArgumentException(key + " must be one of " + mode.getModes());
            if (key.equals("download.directory")) {
                if (text.isBlank()) throw new IllegalArgumentException("Download directory must not be blank");
                java.nio.file.Path.of(text);
            }
            return text;
        }
        if (current instanceof Number && value.isNumber()) {
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) throw new IllegalArgumentException(key + " requires a finite number");
            if (preference instanceof NumberPreference range) {
                if (number < range.getMin() || number > range.getMax())
                    throw new IllegalArgumentException(key + " must be between " + range.getMin() + " and " + range.getMax());
                if ((key.equals("mcp.port") || key.equals("navigation.wallWindows")) && number != Math.rint(number))
                    throw new IllegalArgumentException(key + " requires an integer");
                return number;
            }
            if (current instanceof Integer && number == Math.rint(number)
                    && number >= Integer.MIN_VALUE && number <= 0xFFFFFFFFL) {
                int color = (int) value.getAsLong();
                return key.equals("ui.accentColor") || key.startsWith("background.latent.") ? color | 0xFF000000 : color;
            }
        }
        throw new IllegalArgumentException("Invalid value type or range for " + key);
    }

    @SuppressWarnings("unchecked")
    private static <T> void apply(PreferenceValue<T> preference, Object value) {
        preference.setValue((T) value);
    }
}
