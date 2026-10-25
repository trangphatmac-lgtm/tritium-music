package tritium.minecraft.runtime;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL13;
import static org.junit.jupiter.api.Assertions.*;

class NativeFontCacheTest {
    @Test void restoresPrimitiveAndNestedArrayCacheValuesAfterNativeFontPass() throws Exception {
        FakeState.active = GL13.GL_TEXTURE1;
        FakeState.textures[0].name = 42; FakeState.textures[0].enabled = false;
        FakeState.textures[1].name = 73; FakeState.textures[1].enabled = true;
        FakeState.alpha = true; FakeState.color.r = .25f;
        NativeFontCache cache = new NativeFontCache(FakeState.class);
        try (NativeFontCache.Snapshot saved = cache.save()) {
            cache.prepare();
            assertEquals(GL13.GL_TEXTURE0, FakeState.active);
            assertEquals(0, FakeState.textures[0].name);
            assertTrue(FakeState.textures[0].enabled); assertFalse(FakeState.alpha);
            assertEquals(-1, FakeState.color.r);
            FakeState.bindTexture(991); FakeState.color.r = .9f; FakeState.alpha = true;
        }
        assertEquals(GL13.GL_TEXTURE1, FakeState.active);
        assertEquals(42, FakeState.textures[0].name); assertFalse(FakeState.textures[0].enabled);
        assertEquals(73, FakeState.textures[1].name); assertTrue(FakeState.textures[1].enabled);
        assertTrue(FakeState.alpha); assertEquals(.25f, FakeState.color.r);
    }

    private static final class FakeState {
        static int active;
        static boolean alpha;
        static final Texture[] textures = {new Texture(), new Texture()};
        static final Color color = new Color();
        static void setActiveTexture(int unit) { active = unit; }
        static void bindTexture(int name) { textures[active - GL13.GL_TEXTURE0].name = name; }
        static void resetColor() { color.r = -1; }
        static void enableTexture2D() { textures[active - GL13.GL_TEXTURE0].enabled = true; }
        static void disableTexture2D() { textures[active - GL13.GL_TEXTURE0].enabled = false; }
        static void enableAlpha() { alpha = true; }
        static void disableAlpha() { alpha = false; }
        static final class Texture { int name; boolean enabled; }
        static final class Color { float r; }
    }
}
