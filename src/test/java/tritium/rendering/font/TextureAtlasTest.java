package tritium.rendering.font;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextureAtlasTest {
    @Test
    void keepsEveryGlyphAfterManyPagesFillUp() {
        RecordingBackend backend = new RecordingBackend();
        TextureAtlas atlas = new TextureAtlas(32, backend);
        List<TextureAtlas.AtlasRegion> regions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            BufferedImage image = new BufferedImage(13, 13, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xff000000 | i);
            regions.add(atlas.upload(image));
        }
        assertEquals(250, backend.pages.size());
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            assertNotNull(region);
            assertEquals(0xff000000 | i, backend.pages.get(region.textureId)
                    .getRGB(Math.round(region.u0 * 32), Math.round(region.v0 * 32)));
            Glyph glyph = new Glyph(13, 13, '字');
            glyph.setAtlasRegion(region);
            assertEquals(region.textureId, glyph.textureId);
            assertTrue(glyph.uploaded);
        }
    }

    @Test
    void deletesAllPagesOnceAndIgnoresLateUploadsAfterReset() {
        RecordingBackend backend = new RecordingBackend();
        TextureAtlas oldAtlas = new TextureAtlas(32, backend);
        BufferedImage image = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 3; i++) assertNotNull(oldAtlas.upload(image));
        oldAtlas.destroy();
        oldAtlas.destroy();
        TextureAtlas newAtlas = new TextureAtlas(32, backend);
        var fresh = newAtlas.upload(image);
        assertNull(oldAtlas.upload(image));
        assertEquals(List.of(1, 2, 3), backend.deleted);
        assertEquals(4, fresh.textureId);
        assertEquals(2f / 32, fresh.u0);
        assertEquals(2f / 32, fresh.v0);
        assertEquals(1, backend.pages.size());
    }

    @Test
    void rejectsOversizedGlyphWithoutConsumingSpace() {
        RecordingBackend backend = new RecordingBackend();
        TextureAtlas atlas = new TextureAtlas(32, backend);
        assertThrows(IllegalArgumentException.class,
                () -> atlas.upload(new BufferedImage(29, 1, BufferedImage.TYPE_INT_ARGB)));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.upload(new BufferedImage(1, 29, BufferedImage.TYPE_INT_ARGB)));
        assertTrue(backend.pages.isEmpty());
        var region = atlas.upload(new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB));
        assertEquals(2f / 32, region.u0);
        assertEquals(2f / 32, region.v0);
    }

    private static class RecordingBackend implements TextureAtlas.TextureBackend {
        int nextId;
        final Map<Integer, BufferedImage> pages = new HashMap<>();
        final List<Integer> deleted = new ArrayList<>();

        public int create(int size) {
            pages.put(++nextId, new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
            return nextId;
        }

        public void upload(int textureId, int x, int y, BufferedImage image) {
            BufferedImage page = pages.get(textureId);
            assertTrue(x + image.getWidth() <= page.getWidth() - 2);
            assertTrue(y + image.getHeight() <= page.getHeight() - 2);
            for (int row = 0; row < image.getHeight(); row++) {
                for (int col = 0; col < image.getWidth(); col++) {
                    assertEquals(0, page.getRGB(x + col, y + row), "Glyphs must never overwrite each other");
                    page.setRGB(x + col, y + row, image.getRGB(col, row));
                }
            }
        }

        public void delete(int textureId) {
            deleted.add(textureId);
            assertNotNull(pages.remove(textureId));
        }
    }
}
