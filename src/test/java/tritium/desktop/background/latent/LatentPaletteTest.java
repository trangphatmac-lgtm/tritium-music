package tritium.desktop.background.latent;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class LatentPaletteTest {
    @Test
    void representsLargeNeutralAreasAndIgnoresTransparentPixels() {
        BufferedImage cover = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 50; y++) for (int x = 0; x < 50; x++)
            cover.setRGB(x, y, x < 40 ? 0xFFC0C0C0 : x < 45 ? 0xFFEA580C : 0x001122FF);
        int[] palette = LatentPalette.extract(cover);
        assertArrayEquals(new int[]{0xFFC0C0C0, 0xFFEA580C}, palette);
        assertEquals(0, LatentPalette.extract(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)).length);
    }

    @Test
    void sourcesKeepTheirColorRolesAndMissingArtFallsBackToTheTheme() {
        int[] cover = {10, 20, 30, 40, 50, 60};
        assertArrayEquals(new int[]{1, 1, 10, 10, 20, 30, 40, 1, 3}, LatentPalette.resolve(cover, 1, 2, 3, 4, "cover-theme"));
        assertArrayEquals(new int[]{1, 30, 10, 10, 20, 30, 40, 50, 60}, LatentPalette.resolve(cover, 1, 2, 3, 4, "cover-only"));
        int[] custom = {1, 1, 4, 4, 2, 4, 2, 1, 3};
        assertArrayEquals(custom, LatentPalette.resolve(cover, 1, 2, 3, 4, "theme"));
        assertArrayEquals(custom, LatentPalette.resolve(new int[0], 1, 2, 3, 4, "cover-only"));
    }
}
