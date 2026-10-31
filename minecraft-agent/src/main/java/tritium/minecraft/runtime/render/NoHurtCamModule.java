package tritium.minecraft.runtime.render;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class NoHurtCamModule extends RenderModule {public NoHurtCamModule(RenderAccess g,Render3D d){super("noHurtCam",g,d);}public float multiplier(){return enabled?(int)n("Multiplier"):14f;}}
