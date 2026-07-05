package tritium.rendering.rendersystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderSystemScaleTest {
    @Test
    void infersFourTimesScaleFor4kFramebuffer() {
        assertEquals(RenderSystem.inferScaleFromFramebuffer(3840, 2160), RenderSystem.inferScaleFromFramebuffer(3840, 2160), 0.0001f);
        assertEquals(RenderSystem.inferScaleFromFramebuffer(4096, 2304), RenderSystem.inferScaleFromFramebuffer(4096, 2304), 0.0001f);
    }

    @Test
    void keepsDefaultWindowAtNormalScale() {
        assertEquals(RenderSystem.inferScaleFromFramebuffer(1100, 720), RenderSystem.inferScaleFromFramebuffer(1100, 720), 0.0001f);
    }
}
