package tritium.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.Quality;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DownloadPreferencesTest {
    @TempDir Path directory;

    @Test void persistsDirectoryAndDownloadQualityWithoutChangingPlayback() {
        var preferences = new MusicPreferences();
        preferences.quality().setValue("Higher");
        AtomicInteger changes = new AtomicInteger();
        preferences.setChangeCallback(changes::incrementAndGet);
        preferences.downloadDirectory().setValue(directory.resolve("音乐下载").toString());
        preferences.downloadQuality().setValue("HiRes");
        assertEquals(2, changes.get());
        assertEquals(Quality.HIGHER, CloudMusic.quality);
        var store = new DesktopPreferenceStore(directory.resolve("preferences.json"));
        store.save(preferences);
        var restored = new MusicPreferences();
        store.load(restored);
        assertEquals(preferences.downloadDirectory().getValue(), restored.downloadDirectory().getValue());
        assertEquals(Quality.HIRES, restored.selectedDownloadQuality());
        assertEquals("Higher", restored.quality().getValue());
        assertEquals(Quality.HIGHER, CloudMusic.quality);
        preferences.quality().setValue("Standard");
    }

    @Test void oldOrInvalidSettingsKeepUsableDefaults() {
        var preferences = new MusicPreferences();
        var json = new JsonObject();
        preferences.loadFromJsonObject(json);
        assertEquals(AppPaths.downloadDir().toString(), preferences.downloadDirectory().getValue());
        assertEquals(Quality.STANDARD, preferences.selectedDownloadQuality());
        json.addProperty("download.directory", " ");
        json.addProperty("download.quality", "unknown");
        preferences.loadFromJsonObject(json);
        assertEquals(AppPaths.downloadDir().toString(), preferences.downloadDirectory().getValue());
        assertEquals(Quality.STANDARD, preferences.selectedDownloadQuality());
        preferences.downloadQuality().setValue("Sky");
        assertEquals(Quality.SKY, preferences.selectedDownloadQuality());
    }

    @Test void migratesOldDefaultOnceAndPreservesCustomDownloadFolders() {
        var preferences = new MusicPreferences();
        var json = new JsonObject();
        json.addProperty("download.directory", AppPaths.legacyDownloadDir().toString());
        preferences.loadFromJsonObject(json);
        assertEquals(AppPaths.downloadDir().toString(), preferences.downloadDirectory().getValue());

        json.addProperty("download.directory", directory.toString());
        preferences.loadFromJsonObject(json);
        assertEquals(directory.toString(), preferences.downloadDirectory().getValue());

        // A user may deliberately choose Downloads after upgrading; subsequent loads must keep it.
        preferences.downloadDirectory().setValue(AppPaths.legacyDownloadDir().toString());
        var restored = new MusicPreferences();
        restored.loadFromJsonObject(preferences.toJsonObject());
        assertEquals(AppPaths.legacyDownloadDir().toString(), restored.downloadDirectory().getValue());
    }
}
