package tritium.rendering.rendersystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderSystemScaleTest {
    @Test
    void retinaUsesFullBackingResolutionAndPreservesLogicalSize() {
        float scale = RenderSystem.resolveUiScaleFactor(2.0f, null);
        int width = RenderSystem.toFramebufferSize(1920, 2.0f);
        int height = RenderSystem.toFramebufferSize(1080, 2.0f);
        assertEquals(3840, width);
        assertEquals(2160, height);
        assertEquals(960, Math.round(width / scale));
        assertEquals(540, Math.round(height / scale));
        assertEquals(480, RenderSystem.toLogicalMouse(960, 2.0f, scale));
        assertEquals(270, RenderSystem.toLogicalMouse(540, 2.0f, scale));
    }

    @Test
    void standardDensityPreservesExistingUiLayout() {
        assertEquals(2.0f, RenderSystem.resolveUiScaleFactor(1.0f, null));
        assertEquals(1100, RenderSystem.toFramebufferSize(1100, 1.0f));
        assertEquals(3840, RenderSystem.toFramebufferSize(3840, 1.0f));
        assertEquals(550, RenderSystem.toLogicalMouse(550, 1.0f, 1.0f));
    }

    @Test
    void customScaleKeepsMouseAlignedWithoutReducingBackingResolution() {
        float scale = RenderSystem.resolveUiScaleFactor(2.0f, "1.25");
        assertEquals(2.5f, scale);
        assertEquals(2000, RenderSystem.toFramebufferSize(1000, 2.0f));
        assertEquals(400, RenderSystem.toLogicalMouse(500, 2.0f, scale));
    }

    @Test
    void handlesAutoInvalidScaleAndFractionalBackingSizes() {
        assertEquals(4.0f, RenderSystem.resolveUiScaleFactor(2.0f, "auto"));
        assertEquals(4.0f, RenderSystem.resolveUiScaleFactor(2.0f, "invalid"));
        assertEquals(2.0f, RenderSystem.resolveUiScaleFactor(2.0f, "NaN"));
        assertEquals(16.0f, RenderSystem.resolveUiScaleFactor(2.0f, "20"));
        assertEquals(1502, RenderSystem.toFramebufferSize(1001, 1.5f));
        assertEquals(1, RenderSystem.toFramebufferSize(0, 2.0f));
    }
}
