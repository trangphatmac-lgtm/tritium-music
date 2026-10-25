package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class HudPainterTest {
    @Test void nonFiniteIncomingStateIsSafeForAllHudRenderers() {
        PlaybackState s = new com.google.gson.Gson().fromJson(
                "{\"positionMillis\":\"NaN\",\"durationMillis\":\"Infinity\",\"volume\":\"NaN\","
                        + "\"downloadProgress\":\"-Infinity\",\"spectrum\":[\"NaN\",\"Infinity\",0.5]}", PlaybackState.class).normalize();
        assertEquals(0, s.positionMillis); assertEquals(0, s.durationMillis);
        assertEquals(0, s.volume); assertEquals(0, s.downloadProgress);
        assertArrayEquals(new float[]{0, 0, .5f}, s.spectrum);
        HudConfig config = new HudConfig(); HudPainter painter = new HudPainter();
        try {
            for (HudConfig.Module module : config.modules) {
                assertNotNull(painter.paint(module, new PlaybackState.Track(), s, s.positionMillis,
                        module.width, module.height, 1, 1000, true, config.accent));
            }
        } finally { painter.close(); }
    }

    @Test void allModulesRenderUnicodeWithoutDesktopDependencies() {
        HudConfig c = new HudConfig(); PlaybackState s = new PlaybackState(); PlaybackState.Track t = new PlaybackState.Track();
        t.title = "月光 · Moon 🌙"; t.artist = "艺术家";
        PlaybackState.Line line = new PlaybackState.Line(); line.text = "夜空に輝く星"; line.translation = "夜空中闪耀的星"; line.romanization = "yozora ni kagayaku hoshi"; line.duration = 5000; t.lyrics.add(line);
        HudPainter painter = new HudPainter();
        for (HudConfig.Module module : c.modules) {
            BufferedImage image = painter.paint(module, t, s, 2000, module.width, module.height, 2, 1000, true, c.accent);
            assertEquals((int)(module.width + HudPainter.padding(module) * 2) * 2, image.getWidth());
            boolean painted = false;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x,y) >>> 24) != 0) painted = true;
            assertTrue(painted, module.id);
        }
        painter.toast("Test",1000); assertTrue(painter.toastVisible(2000,5)); assertFalse(painter.toastVisible(8000,5)); painter.close();
    }
}
