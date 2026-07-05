package tritium.desktop;

import tritium.rendering.shader.Shaders;

import java.util.Collections;

public class DesktopShaderUtil {
    public void drawWithBloom(Runnable runnable) {
        Shaders.BLOOM_SHADER.run(Collections.singletonList(runnable));
    }
}
