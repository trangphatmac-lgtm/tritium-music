package tritium.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopPreferenceStore {
    private final Path preferencesFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public DesktopPreferenceStore(Path preferencesFile) {
        this.preferencesFile = preferencesFile;
    }

    public static DesktopPreferenceStore defaultStore() {
        return new DesktopPreferenceStore(AppPaths.preferencesFile());
    }

    public Path getPreferencesFile() {
        return preferencesFile;
    }

    public void load(MusicPreferences preferences) {
        if (!Files.isRegularFile(preferencesFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(preferencesFile, StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root != null && root.isJsonObject()) {
                preferences.loadFromJsonObject(root.getAsJsonObject());
            }
        } catch (RuntimeException | IOException e) {
            System.err.println("Failed to load preferences from " + preferencesFile + ": " + e.getMessage());
        }
    }

    public void save(MusicPreferences preferences) {
        JsonObject json = preferences.toJsonObject();
        try {
            Path parent = preferencesFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Writer writer = Files.newBufferedWriter(preferencesFile, StandardCharsets.UTF_8)) {
                gson.toJson(json, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save preferences to " + preferencesFile + ": " + e.getMessage());
        }
    }
}
