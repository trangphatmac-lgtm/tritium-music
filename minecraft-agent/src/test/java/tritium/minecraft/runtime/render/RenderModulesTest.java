package tritium.minecraft.runtime.render;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.OpenB4Settings;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RenderModulesTest {
 @Test void debuffsOnlyCancelConfiguredEffectsForTheLocalPlayer(){Fake g=new Fake();AntiDebuffModule m=new AntiDebuffModule(g,new Render3D(g));OpenB4Settings s=settings("antiDebuff");s.toggles.put("Alert in chat",false);s.toggles.put("Remove blindness",true);s.toggles.put("Remove nausea",false);m.configure(s,false);assertTrue(m.receive(new Packet(12,15,2,200)));assertFalse(m.receive(new Packet(99,15,2,200)));assertFalse(m.receive(new Packet(12,9,2,200)));assertFalse(m.receive(new Packet(12,1,2,200)));assertFalse(m.receive(new Object()));}
 @Test void indicatorsHonorArrowRangeAndOwnProjectileGraceAtSpawn()throws Exception{Fake g=new Fake();IndicatorsModule m=new IndicatorsModule(g,new Render3D(g));OpenB4Settings s=settings("indicators");s.toggles.put("Render arrows",true);s.toggles.put("Render ender pearls",true);s.toggles.put("Render fireballs",true);m.configure(s,true);Entity nearArrow=new Entity("arrow",3),farArrow=new Entity("arrow",8),pearl=new Entity("pearl",1);m.join(nearArrow);m.join(farArrow);assertEquals(1,list(m,"tracked").size());m.send(new Placement("ender_pearl"));m.join(pearl);assertEquals(1,list(m,"tracked").size());Entity distantPearl=new Entity("pearl",4);m.join(distantPearl);assertEquals(2,list(m,"tracked").size());m.configure(s,false);assertTrue(list(m,"tracked").isEmpty());}
 @Test void trajectoriesPreserveUnchargedBowSwitchAndOnlyTrackNewEnabledSpawns()throws Exception{Fake g=new Fake();TrajectoriesModule m=new TrajectoriesModule(g,new Render3D(g));OpenB4Settings s=settings("trajectories");s.toggles.put("Bow",true);s.toggles.put("Disable uncharged bow",true);s.toggles.put("Ender pearl (thrown)",true);g.held="bow";m.configure(s,true);m.tick();assertEquals(0,field(m,"held"));g.using=true;m.tick();assertEquals(2,field(m,"held"));m.join(new Entity("pearl",5));assertEquals(1,list(m,"thrown").size());m.disable();assertEquals(0,field(m,"held"));assertTrue(list(m,"thrown").isEmpty());}
 @Test void tracerLifecycleRestoresOriginalViewBobbing(){Fake g=new Fake();g.bob=true;TracersModule m=new TracersModule(g,new Render3D(g));OpenB4Settings s=settings("tracers");m.configure(s,true);assertFalse(g.bob);g.bob=true;m.tick();assertFalse(g.bob);m.configure(s,false);assertTrue(g.bob);g.bob=false;m.configure(s,true);m.configure(s,false);assertFalse(g.bob);}
 @Test void hurtCameraUsesIntegerMultiplierAndVanillaWhenDisabled(){Fake g=new Fake();NoHurtCamModule m=new NoHurtCamModule(g,new Render3D(g));OpenB4Settings s=settings("noHurtCam");s.numbers.put("Multiplier",6.9);m.configure(s,true);assertEquals(6,m.multiplier());m.configure(s,false);assertEquals(14,m.multiplier());}
 @Test void itemGroupingPreservesOriginalHashAndVerticalBucketing(){assertEquals(ItemESPModule.hash(0,1,2.1,3),ItemESPModule.hash(0,1,2.9,3));assertNotEquals(ItemESPModule.hash(0,1,2.1,3),ItemESPModule.hash(1,1,2.1,3));assertEquals(.0174*Double.hashCode(1)+.023*Double.hashCode(2)+.039*Double.hashCode(3)+.155,ItemESPModule.hash(0,1,2.1,3));}
 private static OpenB4Settings settings(String id){OpenB4Settings s=new OpenB4Settings();s.normalize(id);return s;}
 private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static List<?> list(Object o,String name)throws Exception{return (List<?>)field(o,name);}
 static final class Packet{final int entity,effect,amp,duration;Packet(int e,int id,int a,int d){entity=e;effect=id;amp=a;duration=d;}}
 static final class Placement{final String item;Placement(String item){this.item=item;}}
 static final class Entity{final String kind;final double x;Entity(String k,double x){kind=k;this.x=x;}}
 static final class Fake extends RenderAccess{
  final Object player=new Object(),world=new Object(),settings=new Object();boolean bob,using;String held;
  Fake(){super(null);}public Object player(){return player;}public Object world(){return world;}public Object settings(){return settings;}public boolean inWorld(){return true;}public int id(Object o){return o==player?12:1;}
  public boolean instance(Object o,String name){if(o instanceof Packet)return name.equals("network.play.server.S1DPacketEntityEffect");if(o instanceof Placement)return name.equals("network.play.client.C08PacketPlayerBlockPlacement");if(o instanceof Entity){String k=((Entity)o).kind;return k.equals("arrow")&&name.equals("entity.projectile.EntityArrow")||k.equals("pearl")&&name.equals("entity.item.EntityEnderPearl")||k.equals("fireball")&&name.equals("entity.projectile.EntityLargeFireball");}return false;}
  public Object item(String name){return name.intern();}public Object item(Object stack){return stack;}public Object held(Object e){return held==null?null:held.intern();}public List<?> players(){return Collections.emptyList();}
  public double distanceSq(Object a,Object b){return ((Entity)b).x*((Entity)b).x;}
  public Object invoke(Object o,String name,Object... args){if(o instanceof Packet){Packet p=(Packet)o;if(name.equals("getEntityId"))return p.entity;if(name.equals("getEffectId"))return p.effect;if(name.equals("getAmplifier"))return p.amp;if(name.equals("getDuration"))return p.duration;}if(o instanceof Placement&&name.equals("getStack"))return ((Placement)o).item.intern();if(o instanceof Placement&&name.equals("getPlacedBlockDirection"))return 255;if(o==player&&name.equals("isUsingItem"))return using;if(o==world&&name.equals("getEntityByID"))return new Object();throw new AssertionError(name);}
  public Object r(Object o,String name){if(o==settings&&name.equals("viewBobbing"))return bob;throw new AssertionError(name);}public void w(Object o,String name,Object value){if(o==settings&&name.equals("viewBobbing")){bob=(Boolean)value;return;}throw new AssertionError(name);}
 }
}
