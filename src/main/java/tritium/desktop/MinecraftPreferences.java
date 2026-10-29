package tritium.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.protocol.PlaybackState;
import java.util.function.Consumer;

/** Persisted separately from the system HUD; connection state is deliberately never serialized. */
public final class MinecraftPreferences {
    private static final Gson JSON = new Gson();
    private HudConfig config = new HudConfig();
    private boolean initialized;
    private Runnable changed = () -> {};

    void setChangeCallback(Runnable callback) { changed = callback; }
    public synchronized HudConfig snapshot() { return JSON.fromJson(JSON.toJson(config), HudConfig.class); }
    public synchronized void update(Consumer<HudConfig> edit) {
        edit.accept(config); config.normalize(); config.revision++; changed.run();
    }
    public synchronized void ensureInitialized(MusicPreferences preferences) {
        if (initialized) return;
        HudPreferences hud = preferences.hud();
        copy(hud.musicInfo(), config.find("musicInfo"));
        copy(hud.musicLyrics(), config.find("musicLyrics"));
        copy(hud.musicSpectrum(), config.find("musicSpectrum"));
        config.find("musicInfo").artistLyric = hud.musicInfo().turnComposerIntoLyric().getValue();
        var lyrics = config.find("musicLyrics");
        lyrics.scrollEffect = hud.musicLyrics().scrollEffect().getValue(); lyrics.align = hud.musicLyrics().align().getValue();
        lyrics.singleLine = hud.musicLyrics().singleLine().getValue(); lyrics.shadow = hud.musicLyrics().shadow().getValue();
        lyrics.graceScroll = hud.musicLyrics().graceScroll().getValue();
        lyrics.translation = preferences.showTranslation().getValue(); lyrics.romanization = preferences.showRoman().getValue();
        lyrics.lyricHeight = preferences.lyricHeight().getValue();
        var spectrum = config.find("musicSpectrum");
        spectrum.spectrumStyle = hud.musicSpectrum().style().getValue(); spectrum.compact = hud.musicSpectrum().compact().getValue();
        spectrum.indicator = hud.musicSpectrum().indicator().getValue(); spectrum.absoluteVolume = hud.musicSpectrum().absoluteVolume().getValue();
        spectrum.multiplier = hud.musicSpectrum().multiplier().getValue(); spectrum.color = hud.musicSpectrum().color().getValue();
        config.find("musicToast").enabled = preferences.musicToast().getValue();
        config.accent = preferences.accentColor().getValue();
        initialized = true; config.normalize(); config.revision++; changed.run();
    }
    private static void copy(HudElementPreferences source, HudConfig.Module target) {
        target.enabled = source.enabled().getValue(); target.scale = source.scale().getValue(); target.opacity = source.opacity().getValue();
        // The original widgets use a nominal two-pixel GUI unit; retain proportions without copying desktop positions.
        target.width = source.width().getValue() / 2; target.height = source.height().getValue() / 2;
    }
    public void resetLayout() {
        update(c -> { for (var defaults : HudConfig.defaults()) {
            if ("sprint".equals(defaults.id)) { c.find("sprint").movement.textX = 5; c.find("sprint").movement.textY = 5; }
            if (!defaults.isHud()) continue;
            var m = c.find(defaults.id); m.x = defaults.x; m.y = defaults.y;
            m.width = defaults.width; m.height = defaults.height; m.scale = 1;
        }});
    }
    public void apply(PlaybackState.Event event) {
        if (event == null || event.id == null) return;
        update(c -> {
            var m = c.find(event.id); if (m == null) return;
            if ("toggle".equals(event.action)) m.enabled = event.enabled;
            if ("layout".equals(event.action) && "sprint".equals(m.id)) {
                m.movement.textX = event.x; m.movement.textY = event.y;
            }
            if ("layout".equals(event.action) && m.isHud()) {
                m.x = event.x; m.y = event.y; m.width = event.width; m.height = event.height; m.scale = event.scale;
            }
        });
    }
    synchronized JsonObject toJsonObject() {
        JsonObject result = JSON.toJsonTree(config).getAsJsonObject(); result.addProperty("initialized", initialized);
        result.addProperty("toastLayoutVersion", 1); return result;
    }
    synchronized void loadFromJsonObject(JsonObject object) {
        try {
            HudConfig loaded = JSON.fromJson(object, HudConfig.class); loaded.normalize();
            HudConfig.Module toast = loaded.find("musicToast");
            if (!object.has("toastLayoutVersion") && toast.width == 240 && toast.height == 32) {
                toast.width = 120; toast.height = 24;
            }
            config = loaded;
            initialized = object.has("initialized") && object.get("initialized").getAsBoolean();
        } catch (RuntimeException ignored) { config = new HudConfig(); initialized = false; }
    }
}
