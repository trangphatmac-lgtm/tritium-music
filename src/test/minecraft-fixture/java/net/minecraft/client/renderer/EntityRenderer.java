package net.minecraft.client.renderer;

/** Method shapes mirror 1.8.9; this fixture never creates an OpenGL context. */
public class EntityRenderer {
    private final Profiler profiler = new Profiler();
    private boolean renderHand = true;
    public void updateCameraAndRender(float partial, long deadline) { }
    public void getMouseOver(float partialTicks) { }
    public int marker() { return 1; }
    private void renderWorldPass(int pass, float partialTicks, long deadline) {
        profiler.endStartSection("hand");
        if (renderHand) renderHand(partialTicks, pass);
    }
    private void renderHand(float partialTicks, int pass) { }
    private static final class Profiler {
        void endStartSection(String section) { }
    }
}
