package tritium.minecraft.runtime.render;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.Render3D;
public final class ChestESPModule extends RenderModule {
 public ChestESPModule(RenderAccess g,Render3D d){super("chestESP",g,d);}
 public void world(float pt){int c=rgb();for(Object te:(Iterable<?>)g.r(g.world(),"loadedTileEntityList")){if(!g.instance(te,"tileentity.TileEntityChest")&&!g.instance(te,"tileentity.TileEntityEnderChest"))continue;if(on("Disable if opened")&&g.value(te,"lidAngle")>0)continue;Object p=g.invoke(te,"getPos");draw.blockBox(g.coord(p,0),g.coord(p,1),g.coord(p,2),.0625,0,.0625,.9375,.875,.9375,c,on("Outline"),on("Shade"));}}
}
