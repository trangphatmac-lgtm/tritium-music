package tritium.minecraft.runtime.render;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class AntiDebuffModule extends RenderModule {
 public AntiDebuffModule(RenderAccess g,Render3D d){super("antiDebuff",g,d);}
 public void enable(){remove();}
 private boolean blindness,nausea;
 public void configure(tritium.minecraft.protocol.OpenB4Settings next,boolean active){boolean was=enabled;super.configure(next,active);if(was&&active&&(!blindness&&on("Remove blindness")||!nausea&&on("Remove nausea")))remove();blindness=on("Remove blindness");nausea=on("Remove nausea");}
 private void remove(){if(!g.inWorld())return;for(String name:new String[]{"blindness","confusion"}){if(!on(name.equals("blindness")?"Remove blindness":"Remove nausea"))continue;Object potion=g.r(g.cls("potion.Potion"),name);Object e=g.invoke(g.player(),"getActivePotionEffect",potion);if(e!=null){g.invoke(g.player(),"removePotionEffectClient",g.integerField(potion,"id"));if(on("Alert in chat"))alert(RenderAccess.i(g.invoke(e,"getPotionID")),RenderAccess.i(g.invoke(e,"getAmplifier")),RenderAccess.i(g.invoke(e,"getDuration")));}}}
 public boolean receive(Object p){if(!g.instance(p,"network.play.server.S1DPacketEntityEffect"))return false;if(RenderAccess.i(g.invoke(p,"getEntityId"))!=g.id(g.player()))return false;int id=RenderAccess.i(g.invoke(p,"getEffectId"));boolean drop=id==15&&on("Remove blindness")||id==9&&on("Remove nausea");if(drop&&on("Alert in chat"))alert(id,RenderAccess.i(g.invoke(p,"getAmplifier")),RenderAccess.i(g.invoke(p,"getDuration")));return drop;}
 private void alert(int id,int amp,int ticks){Object[] potions=(Object[])g.r(g.cls("potion.Potion"),"potionTypes");String name=String.valueOf(g.invoke(g.cls("util.StatCollector"),"translateToLocal",g.invoke(potions[id],"getName")));String duration=RenderStrings.format(Math.round(ticks/2.0)/10.0);g.invoke(g.player(),"addChatMessage",g.make("util.ChatComponentText","§7[§dR§7]§r §bAntiDebuff§7: §r§7Removing §d"+name+" "+(amp+1)+" §7for §b"+duration+"§7s."));}
}
