package tritium.minecraft.runtime.render;
import java.util.*;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
/** OpenB4 Xray is an ore scanner/ESP, not a transparent-terrain renderer. */
public final class XrayModule extends RenderModule {
 private static final String[] NAMES={"Coal","Diamond","Emerald","Gold","Iron","Lapis","Obsidian","Redstone","Spawner"};
 private static final String[][] BLOCKS={{"coal_ore","coal_block"},{"diamond_ore","diamond_block"},{"emerald_ore","emerald_block"},{"gold_ore","gold_block"},{"iron_ore","iron_block"},{"lapis_ore","lapis_block"},{"obsidian"},{"redstone_ore","redstone_block"},{"mob_spawner"}};
 private static final int[] COLORS={-16777216,-16711681,-16711936,-256,-1,-16776961,-7273744,-65536,-43213};
 private final Map<Object,Integer> ores=new IdentityHashMap<Object,Integer>();private final Map<Object,Integer> found=new LinkedHashMap<Object,Integer>();private Timer timer;private volatile RuntimeException scanFailure;private volatile long epoch;
 public XrayModule(RenderAccess g,Render3D d){super("xray",g,d);}
 public void enable(){ores.clear();for(int j=0;j<BLOCKS.length;j++)for(String b:BLOCKS[j])ores.put(g.block(b),j);final long generation=++epoch;timer=new Timer("Tritium Xray scan",true);timer.scheduleAtFixedRate(new TimerTask(){long last;public void run(){long now=System.currentTimeMillis();if(!enabled||generation!=epoch||!g.inWorld()||Math.abs(now-last)<n("Rate")*1000-10)return;Object scannedWorld=g.world();try{scan(generation);}catch(RuntimeException error){if(enabled&&generation==epoch&&scannedWorld==g.world()&&g.inWorld())scanFailure=error;}last=now;}},0,200);}
 public void disable(){epoch++;scanFailure=null;if(timer!=null){timer.cancel();timer.purge();timer=null;}synchronized(found){found.clear();}}
 private boolean inRange(Object p){return g.distanceSq(g.player(),g.coord(p,0)+.5,g.coord(p,1)+.5,g.coord(p,2)+.5)<=n("Range")*n("Range")+2;}
 private void scan(long generation){Object world=g.world();int radius=(int)n("Range");for(int y=radius;y>=-radius;y--)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){if(!enabled||epoch!=generation||world!=g.world()||!g.inWorld())return;Object p=g.pos(g.pos(g.player(),0)+x,g.pos(g.player(),1)+y,g.pos(g.player(),2)+z);if(!inRange(p))continue;Integer type=ores.get(g.blockAt(p));if(type!=null&&on(NAMES[type]))synchronized(found){if(generation==epoch)found.put(p,type);}}}
 public void tick(){if(scanFailure!=null)throw new IllegalStateException("OpenB4 Xray scan failed",scanFailure);synchronized(found){Iterator<Map.Entry<Object,Integer>> it=found.entrySet().iterator();while(it.hasNext()){Map.Entry<Object,Integer> e=it.next();Integer type=ores.get(g.blockAt(e.getKey()));if(!inRange(e.getKey())||type==null||!on(NAMES[type]))it.remove();}}}
 public void world(float pt){synchronized(found){for(Map.Entry<Object,Integer> e:found.entrySet()){Integer type=ores.get(g.blockAt(e.getKey()));draw.block(e.getKey(),type==null?-1:COLORS[type],false,true);}}}
}
