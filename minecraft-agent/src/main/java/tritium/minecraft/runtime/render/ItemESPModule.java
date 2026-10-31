package tritium.minecraft.runtime.render;
import java.util.*;
import org.lwjgl.opengl.GL11;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class ItemESPModule extends RenderModule {
 private static final String[] ITEMS={"iron_ingot","gold_ingot","diamond","emerald"},OPTIONS={"Disable iron","Disable gold","Disable diamond","Disable emerald"};
 private static final int[] BOX={-1,-331703,-10362113,-15216030},TEXT={-1,-152,-7667713,-14614644};
 public ItemESPModule(RenderAccess g,Render3D d){super("itemESP",g,d);}
 public static double hash(int kind,double x,double y,double z){y=y<1?1:Math.floor(y);return .0174*Double.hashCode(x)+.023*Double.hashCode(y)+.039*Double.hashCode(z)+new double[]{.155,.255,.355,.455}[kind];}
 public void world(float pt){Map<Double,Object> first=new LinkedHashMap<Double,Object>();Map<Double,Integer> counts=new HashMap<Double,Integer>(),kinds=new HashMap<Double,Integer>();for(Object e:g.entities()){if(!g.instance(e,"entity.item.EntityItem")||g.integerField(e,"ticksExisted")<3)continue;Object stack=g.invoke(e,"getEntityItem");int size=g.integerField(stack,"stackSize");if(size==0)continue;Object item=g.item(stack);for(int j=0;j<4;j++){if(on(OPTIONS[j])||item!=g.item(ITEMS[j]))continue;double key=hash(j,g.pos(e,0),g.pos(e,1),g.pos(e,2));if(!first.containsKey(key)){first.put(key,e);counts.put(key,0);kinds.put(key,j);}counts.put(key,counts.get(key)+size);}}
  for(Map.Entry<Double,Object> entry:first.entrySet()){Object e=entry.getValue();double x=g.interp(e,0,pt),y=g.interp(e,1,pt),z=g.interp(e,2,pt);double dx=g.interp(g.player(),0,pt)-x,dy=g.interp(g.player(),1,pt)-y,dz=g.interp(g.player(),2,pt)-z,dist=Math.sqrt(dx*dx+dy*dy+dz*dz);int kind=kinds.get(entry.getKey());x-=g.view(0);y-=g.view(1);z-=g.view(2);float r=Math.min(Math.max(.2f,(float)(.01f*dist)),.4f);Render3D.filled(new double[]{x-r,y,z-r,x+r,y+r*2,z+r},BOX[kind],.56f);GL11.glPushMatrix();try{GL11.glTranslated((float)x,(float)y+.3,(float)z);GL11.glRotatef(-g.floating(g.manager(),"playerViewY"),0,1,0);GL11.glRotatef(g.floating(g.manager(),"playerViewX"),1,0,0);float sc=Math.min(Math.max(.02266667f,(float)(.0015f*dist)),.07f);GL11.glScalef(-sc,-sc,-sc);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_TEXTURE_2D);String text=String.valueOf(counts.get(entry.getKey()));g.text(text,-g.textWidth(text)/2+sc*3.5f,-(123.805f*sc-2.47494f),TEXT[kind],true);}finally{GL11.glPopMatrix();}}
 }
}
