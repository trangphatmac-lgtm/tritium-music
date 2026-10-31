package tritium.minecraft.runtime.render;
import java.util.*;
import org.lwjgl.opengl.GL11;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class ChamsModule extends RenderModule {
 private final Set<Object> rendered=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
 public ChamsModule(RenderAccess g,Render3D d){super("chams",g,d);}
 public void pre(Object e){if(on("Hide players")||!on("Render self")&&e==g.player()||on("Ignore bots")&&g.bot(e))return;GL11.glPushAttrib(GL11.GL_POLYGON_BIT|GL11.GL_ENABLE_BIT);rendered.add(e);GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);GL11.glPolygonOffset(1,-3800000);}
 public void post(Object e){if(rendered.remove(e))GL11.glPopAttrib();}
 public void disable(){while(!rendered.isEmpty()){rendered.remove(rendered.iterator().next());GL11.glPopAttrib();}}
}
