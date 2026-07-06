package tritium.desktop.hud;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class HudArtworkCacheTest {
    @Test
    void storesIndependentImageCopy() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);

        HudArtworkCache.put(42L, HudArtworkCache.ArtworkType.COVER, image);
        image.setRGB(0, 0, 0xFF0000FF);

        BufferedImage cached = HudArtworkCache.get(42L, HudArtworkCache.ArtworkType.COVER);
        assertNotSame(image, cached);
        assertEquals(0xFFFF0000, cached.getRGB(0, 0));
    }
}
