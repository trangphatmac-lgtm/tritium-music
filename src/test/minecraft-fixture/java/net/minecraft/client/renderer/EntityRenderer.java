package net.minecraft.client.renderer;

/** Method shapes mirror 1.8.9; this fixture never creates an OpenGL context. */
public class EntityRenderer {
    private final Profiler profiler = new Profiler();
    private boolean renderHand = true;
    public void updateCameraAndRender(float partial, long deadline) { }
    public boolean throwOnMouseOver;
    public static int mouseOvers;
    public void getMouseOver(float partialTicks) { mouseOvers++; if (throwOnMouseOver) throw new IllegalStateException("fixture mouse-over failure"); }
    public float hurt;
    public void hurtCameraEffect(float partial) { hurt = partial * 14.0f; }
    public void orientCamera(float partial) {
        new net.minecraft.client.multiplayer.WorldClient().rayTraceBlocks(new net.minecraft.util.Vec3(), new net.minecraft.util.Vec3());
    }
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
