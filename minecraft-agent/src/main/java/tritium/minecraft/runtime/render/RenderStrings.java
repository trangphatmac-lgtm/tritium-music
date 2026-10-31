package tritium.minecraft.runtime.render;
import java.util.*;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.RenderAccess;
/** Raven Strings helpers with the unchanged defaults of its global Settings module. */
public final class RenderStrings {
 private final RenderAccess g;
 public RenderStrings(RenderAccess g){this.g=g;}
 public static String format(double d){return d==(long)d?String.valueOf((long)d):String.valueOf(d);}
 public static String health(double ratio,double hp){return "§"+(ratio<.3?'c':ratio<.5?'6':ratio<.7?'e':'a')+format(Math.round(hp*10)/10.0);}
 public static String difference(float d){return "§"+(d>=8?'2':d>2?'a':d>-2?'e':d>-6?'6':'c')+(d>=0?"+":"")+format(Math.round(d*10)/10.0);}
 public static char distanceColor(double d){return d<=10?'c':d<=25?'6':d<=50?'e':d<=100?'a':'2';}
 public int enchant(Object stack,int id){return RenderAccess.i(g.invoke(g.cls("enchantment.EnchantmentHelper"),"getEnchantmentLevel",id,stack));}
 public double attackDamage(Object stack){double damage=0;Object attributes=g.invoke(stack,"getAttributeModifiers");for(Object entry:(Iterable<?>)g.invoke(attributes,"entries")){Map.Entry<?,?> e=(Map.Entry<?,?>)entry;if("generic.attackDamage".equals(e.getKey())){damage=RenderAccess.d(g.invoke(e.getValue(),"getAmount"));break;}}int sharp=enchant(stack,16),fire=enchant(stack,20);return damage+sharp*1.25+(fire>0?fire*4-1:0);}
 public double hitsToKill(Object p,Object held){double damage=1;if(held!=null&&(g.instance(g.item(held),"item.ItemSword")||g.instance(g.item(held),"item.ItemAxe")))damage+=attackDamage(held);double armor=0,protection=0;for(Object s:g.armor(p)){if(s==null||!g.instance(g.item(s),"item.ItemArmor"))continue;armor+=g.value(g.item(s),"damageReduceAmount")*.04;int level=enchant(s,0);if(level!=0)protection+=Math.floor(.75*(6+level*level)/3.0);}protection=.04*Math.min(Math.ceil(Math.min(protection,25)*.75),20);double reduction=armor+protection*(1-armor);return Math.round(g.health(p)/(damage*(1-reduction))*10)/10.0;}
 public String nametag(Object p,OpenB4Settings s){if(!g.instance(p,"entity.player.EntityPlayer"))return g.displayName(p,true);StringBuilder sb=new StringBuilder();if(s.on("Show distance")&&p!=g.player()){double d=Math.sqrt(g.distanceSq(p,g.player()));sb.append('§').append(distanceColor(d)).append((int)d).append('m');}if(sb.length()>0)sb.append("§r ");if(s.on("Only render name")){Object team=g.invoke(p,"getTeam");if(team!=null){String prefix=String.valueOf(g.invoke(g.cls("client.gui.FontRenderer"),"getFormatFromString",g.invoke(team,"getColorPrefix")));if(prefix.length()>=2)sb.append('§').append(prefix.charAt(1));}sb.append(g.invoke(p,"getName"));}else sb.append(g.displayName(p,true));if(s.on("Sneak suffix")&&g.sneaking(p))sb.append("§r §eS");if(s.on("Show health"))sb.append("§r ").append(health(Math.min(g.health(p)/g.maxHealth(p),1),g.health(p)));if(s.on("Show hits to kill")){int hits=(int)Math.ceil(hitsToKill(p,g.held(g.player())));sb.append("§r §").append(hits<=1?'c':hits<=3?'6':hits<=5?'e':'a').append(hits);}return sb.toString();}
}
