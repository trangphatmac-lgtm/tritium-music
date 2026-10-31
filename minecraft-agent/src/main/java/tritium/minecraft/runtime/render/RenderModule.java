package tritium.minecraft.runtime.render;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public abstract class RenderModule {
 public final String id;protected final RenderAccess g;protected final Render3D draw;protected volatile OpenB4Settings s;public volatile boolean enabled;
 protected RenderModule(String id,RenderAccess g,Render3D draw){this.id=id;this.g=g;this.draw=draw;}
 public void configure(OpenB4Settings next,boolean on){boolean previous=enabled;s=next;enabled=on;if(previous&&!on)disable();if(!previous&&on)enable();}
 protected double n(String key){return s.number(key);}protected boolean on(String key){return s.on(key);}
 public void enable(){}public void disable(){}public void tick(){}public void world(float pt){}public void overlay(float pt){}public void join(Object e){}
 protected int rgb(){return on("Rainbow")?Render3D.rainbow(2):0xff000000|(int)n("Red")<<16|(int)n("Green")<<8|(int)n("Blue");}
}
