package tritium.ncm.music.dto;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MusicDownloadUrlTest {
    @Test void refusesPreviewClipsForDownloadsWithoutChangingPlayback() {
        var response = new JsonParser().parse("""
                {"code":200,"url":"https://example.test/audio","type":"flac","freeTrialInfo":{"start":0,"end":30}}
                """).getAsJsonObject();
        assertNotNull(Music.audioUrl(response, false));
        assertNull(Music.audioUrl(response, true));
        response.remove("freeTrialInfo");
        assertEquals("flac", Music.audioUrl(response, true).getB());
        response.addProperty("url", " ");
        assertNull(Music.audioUrl(response, true));
    }
}
