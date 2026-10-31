package tritium.minecraft.runtime.render;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class TracersModule extends RenderModule {
 private boolean bob;
 public TracersModule(RenderAccess g,Render3D d){super("tracers",g,d);}
 public void enable(){bob=RenderAccess.b(g.r(g.settings(),"viewBobbing"));g.w(g.settings(),"viewBobbing",false);}
 public void tick(){g.w(g.settings(),"viewBobbing",false);}
 public void disable(){g.w(g.settings(),"viewBobbing",bob);}
 public void world(float pt){int c=rgb();for(Object p:g.players()){if(p==g.player()||g.integerField(p,"deathTime")!=0||!on("Show invis")&&g.invisible(p)||g.bot(p))continue;draw.tracer(p,(float)n("Line width"),c,pt);}}
}
