package tritium.minecraft.runtime.hook;
import java.util.*;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.render.*;
import tritium.minecraft.runtime.util.render.*;
/** Session-owned OpenB4 render dispatcher, module lifecycles and camera/packet hooks. */
public final class RenderRuntime implements AutoCloseable {
 private final RenderAccess g;private final Render3D draw;private final List<RenderModule> modules;
 private final AntiDebuffModule debuff;private final ChamsModule chams;private final NoCameraClipModule clip;private final NoHurtCamModule hurt;
 private final PlayerESPModule esp;private final NametagsModule tags;private final IndicatorsModule indicators;private final TargetHudModule hud;private final RadarModule radar;
 private HudConfig config;private Object world,player;private boolean editing;
 public RenderRuntime(Object minecraft){g=new RenderAccess(minecraft);draw=new Render3D(g);debuff=new AntiDebuffModule(g,draw);chams=new ChamsModule(g,draw);clip=new NoCameraClipModule(g,draw);hurt=new NoHurtCamModule(g,draw);esp=new PlayerESPModule(g,draw);tags=new NametagsModule(g,draw);indicators=new IndicatorsModule(g,draw);hud=new TargetHudModule(g,draw);
  radar=new RadarModule(g,draw);
  modules=Arrays.<RenderModule>asList(debuff,new BedESPModule(g,draw),chams,new ChestESPModule(g,draw),indicators,new ItemESPModule(g,draw),tags,clip,hurt,esp,radar,hud,new TracersModule(g,draw),new TrajectoriesModule(g,draw),new XrayModule(g,draw));}
 public void radarFrames(java.util.function.Consumer<tritium.minecraft.protocol.RadarFrame> frames){radar.frames(frames);}
 public void antiBot(AntiBotRuntime antiBot){g.antiBot(antiBot);}
 public void configure(HudConfig next){config=next;tags.friends(next.openB4Friends,next.openB4Enemies);Object w=g.world(),p=g.player();if(w!=world||p!=player){for(RenderModule m:modules)if(m.enabled){m.enabled=false;m.disable();}world=w;player=p;}boolean active=next.hidden&&w!=null&&p!=null;for(RenderModule m:modules)m.configure(next.find(m.id).openB4,active&&next.find(m.id).enabled);hud.editor(editing&&next.hidden);}
 private boolean current(){if(config!=null&&(world!=g.world()||player!=g.player()))configure(config);return world!=null&&player!=null;}
 public void tickEnd(){if(!current())return;g.refreshServer();for(RenderModule m:modules)if(m.enabled)m.tick();}
 public void renderWorld(float pt){if(!current())return;boolean any=false;for(RenderModule m:modules)if(m.enabled){any=true;break;}if(!any)return;try(RenderState state=new RenderState(g.nativeCache())){draw.update(pt);for(RenderModule m:modules)if(m.enabled)try(RenderState isolated=new RenderState(g.nativeCache())){m.world(pt);}}}
 public void renderOverlay(float pt){if(!current())return;for(RenderModule m:modules)if(m.enabled||m==hud&&editing&&config.hidden)try(RenderState state=new RenderState(g.nativeCache())){m.overlay(pt);}}
 public boolean receive(Object packet){return current()&&debuff.enabled&&debuff.receive(packet);}
 public void send(Object packet){indicators.send(packet);}
 public void entityJoin(Object entity){if(!current())return;for(RenderModule m:modules)if(m.enabled)m.join(entity);}
 public float hurtCameraMultiplier(){return hurt.multiplier();}
 public boolean cameraClip(){return clip.clips();}
 public void playerPre(Object entity){if(chams.enabled&&g.instance(entity,"entity.player.EntityPlayer"))chams.pre(entity);}
 public void playerPost(Object entity){chams.post(entity);}
 public boolean suppressName(Object entity,double x,double y,double z){return current()&&config.hidden&&(esp.enabled||tags.enabled)&&(esp.shaderPass||(x==0&&y==0&&z==0)||tags.active()&&tags.visible(entity));}
 public void editor(boolean editing){this.editing=editing;hud.editor(editing&&config!=null&&config.hidden);}
 public double[] hudBounds(double width,double height){return hud.hudBounds(width,height);}
 public void close(){for(RenderModule m:modules)if(m.enabled){m.enabled=false;m.disable();}esp.close();hud.close();}
}
