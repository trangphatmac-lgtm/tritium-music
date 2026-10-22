package tritium.ncm.music.automix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Server material, with decoded audio duration taking precedence over catalogue duration. */
public final class SongFeatures {
    private final long songId;
    private final double durationMillis;
    private final JsonObject data;

    private SongFeatures(long songId, double durationMillis, JsonObject data) {
        this.songId = songId;
        this.durationMillis = durationMillis;
        this.data = data;
    }

    public static SongFeatures empty(long songId, double durationMillis) {
        return new SongFeatures(songId, durationMillis, new JsonObject());
    }

    public static SongFeatures fromJson(long songId, double durationMillis, JsonObject data) {
        return new SongFeatures(songId, durationMillis, data == null ? new JsonObject()
                : new JsonParser().parse(data.toString()).getAsJsonObject());
    }

    public long songId() { return songId; }
    public double durationMillis() { return durationMillis; }

    /** Only diagnostic values; callers cannot mutate cached server material. Times are milliseconds. */
    public record Summary(boolean available, double durationMillis, double bpm, String key, String genre,
                          int downbeats, double silenceStart, double silenceEnd,
                          double vocalStart, double vocalEnd, boolean excludedGenre) { }

    public Summary summary() {
        return new Summary(!data.entrySet().isEmpty(), durationMillis, bpm(), text("mode"), text("genre"),
                downbeats().size(), point("silenceTime", "s"), point("silenceTime", "e"),
                point("voiceTime", "s"), point("voiceTime", "e"), unsupportedGenre());
    }
    public SongFeatures withDuration(double actualDurationMillis) {
        return new SongFeatures(songId, actualDurationMillis, data);
    }

    double number(String field) { return number(data.get(field)); }
    String text(String field) { return text(data.get(field)); }

    double point(String field, String side) {
        JsonElement value = data.get(field);
        return value != null && value.isJsonObject() ? number(value.getAsJsonObject().get(side)) : Double.NaN;
    }

    double energy(String field, int index) {
        JsonElement value = data.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() <= index) return Double.NaN;
        double number = number(value.getAsJsonArray().get(index));
        return number >= -100 && number <= 0 ? number : Double.NaN;
    }

    boolean has(String field) { return data.has(field) && !data.get(field).isJsonNull(); }

    double bpm() {
        double bpm = number("bpm"), rep = number("repBpm");
        if (!(bpm > 0)) return rep;
        if (!(rep > 0)) return bpm;
        double doubled = Math.min(bpm, rep) * 2, larger = Math.max(bpm, rep);
        return Math.abs(doubled - larger) / Math.min(doubled, larger) <= .02 ? larger : bpm;
    }

    int beatsPerBar() {
        double value = point("beat", "beatsPerBar");
        return value >= 1 && value <= 16 && value == Math.rint(value) ? (int) value : 4;
    }

    List<Double> downbeats() {
        List<Double> beats = new ArrayList<>();
        JsonElement beat = data.get("beat");
        if (beat == null || !beat.isJsonObject()) return beats;
        String compressed = text(beat.getAsJsonObject().get("downbeatCompressed"));
        if (compressed.length() > 1_000_000) return beats;
        int perBar = beatsPerBar();
        double firstValue = point("beat", "beatFirst");
        int first = firstValue >= 1 && firstValue <= perBar ? (int) firstValue : 1;
        String[] positions = compressed.split(",");
        double previous = -1;
        for (int i = 0; i < positions.length; i++) {
            try {
                double value = Double.parseDouble(positions[i].trim());
                if (!Double.isFinite(value) || value < 0 || value > durationMillis || value <= previous) continue;
                previous = value;
                if ((first - 1 + i) % perBar == 0) beats.add(value);
            } catch (NumberFormatException ignored) { }
        }
        return beats;
    }

    double paragraphBoundary(boolean start) {
        JsonElement value = data.get("paragraph");
        if (value == null || !value.isJsonArray()) return Double.NaN;
        JsonArray paragraphs = value.getAsJsonArray();
        if (paragraphs.size() == 0) return Double.NaN;
        JsonElement paragraph = paragraphs.get(start ? 0 : paragraphs.size() - 1);
        return paragraph.isJsonObject() ? number(paragraph.getAsJsonObject().get(start ? "e" : "s")) : Double.NaN;
    }

    boolean unsupportedGenre() {
        for (String genre : text("genre").split(",")) {
            if (List.of("宗教", "古典", "儿歌", "红色经典").contains(genre.trim())) return true;
        }
        JsonElement value = data.get("genreIdList");
        if (value != null && value.isJsonArray()) {
            for (JsonElement id : value.getAsJsonArray()) {
                if (List.of(1044d, 1022d, 1221d, 1243d, 1026d, 1138d).contains(number(id))) return true;
            }
        }
        return false;
    }

    static double number(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive()) return Double.NaN;
            double result = element.getAsDouble();
            return Double.isFinite(result) ? result : Double.NaN;
        } catch (RuntimeException ignored) { return Double.NaN; }
    }

    static String text(JsonElement element) {
        try { return element != null && element.isJsonPrimitive() ? element.getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }
}
