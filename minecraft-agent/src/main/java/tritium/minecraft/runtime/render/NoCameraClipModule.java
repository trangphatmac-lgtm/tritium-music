package tritium.minecraft.runtime.render;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class NoCameraClipModule extends RenderModule {public NoCameraClipModule(RenderAccess g,Render3D d){super("noCameraClip",g,d);}public boolean clips(){return !enabled;}}
