package tritium.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.PlaybackSession;
import tritium.ncm.music.dto.Music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Playback state is separate from preferences so frequent setting changes cannot overwrite it. */
public final class DesktopPlaybackStore {
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public DesktopPlaybackStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    public PlaybackSession load() {
        if (!Files.isRegularFile(file)) return null;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) return null;
            CloudMusic.PlayMode mode;
            try { mode = CloudMusic.PlayMode.valueOf(json.get("mode").getAsString()); }
            catch (IllegalArgumentException e) { mode = CloudMusic.PlayMode.Sequential; }
            return new PlaybackSession(readSongs(json.get("songs")), json.get("index").getAsInt(),
                    json.get("positionMillis").getAsLong(), json.get("paused").getAsBoolean(), mode,
                    json.has("nextSongs") ? readSongs(json.get("nextSongs")) : List.of());
        } catch (IOException | RuntimeException e) {
            System.err.println("Failed to load playback state from " + file + ": " + e.getMessage());
            return null;
        }
    }

    private List<Music> readSongs(JsonElement json) {
        List<Music> songs = new ArrayList<>();
        for (JsonElement entry : json.getAsJsonArray()) {
            Music song = gson.fromJson(entry, Music.class);
            if (song == null || song.getId() <= 0 || song.getName() == null || song.getArtists() == null
                    || song.getArtists().stream().anyMatch(artist -> artist == null || artist.getName() == null)) {
                throw new IllegalArgumentException("Invalid saved song");
            }
            songs.add(song);
        }
        return songs;
    }

    public void save(PlaybackSession session) {
        // Closing before startup finishes must not erase the previous session.
        if (session == null) return;
        Path temporary = null;
        try {
            JsonObject json = new JsonObject();
            json.addProperty("version", 1);
            json.add("songs", gson.toJsonTree(session.songs()));
            json.addProperty("index", session.index());
            json.addProperty("positionMillis", session.positionMillis());
            json.addProperty("paused", session.paused());
            json.addProperty("mode", session.mode().name());
            json.add("nextSongs", gson.toJsonTree(session.nextSongs()));
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "playback-", ".tmp");
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(json, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Failed to save playback state to " + file + ": " + e.getMessage());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { System.err.println("Failed to remove playback temporary file: " + e.getMessage()); }
            }
        }
    }
}
