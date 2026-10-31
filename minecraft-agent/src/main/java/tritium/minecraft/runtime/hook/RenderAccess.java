package tritium.minecraft.runtime.hook;

import java.util.*;
import java.lang.reflect.Array;
import tritium.minecraft.runtime.util.GameReflection;
import tritium.minecraft.runtime.util.render.RenderMappings;

/** Session-scoped MCP/SRG adapter shared by the OpenB4 rendering modules. */
public class RenderAccess extends GameReflection {
    public final Object mc;
    private AntiBotRuntime antiBot;
    public void antiBot(AntiBotRuntime antiBot) { this.antiBot=antiBot; }
    public boolean aboveSpawn(Object entity) { return antiBot!=null && antiBot.aboveSpawn(entity); }
    private tritium.minecraft.runtime.lyric.NativeFontCache nativeCache;
    public tritium.minecraft.runtime.lyric.NativeFontCache nativeCache(){if(nativeCache==null)try{nativeCache=new tritium.minecraft.runtime.lyric.NativeFontCache(mc.getClass().getClassLoader());}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}return nativeCache;}
    private void prepareNative(){try{nativeCache().prepare();}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public RenderAccess(Object minecraft) { super(minecraft==null?RenderAccess.class.getClassLoader():minecraft.getClass().getClassLoader()); mc=minecraft; }
    public Object r(Object o,String name) { return get(o,RenderMappings.name(name)); }
    public void w(Object o,String name,Object v) { set(o,RenderMappings.name(name),v); }
    public Object state(String name,Object... a){return invoke(cls("client.renderer.GlStateManager"),name,a);}
    public Object invoke(Object o,String name,Object... a) { return call(o,RenderMappings.name(name),a); }
    public Object make(String name,Object... a) { return construct(name,a); }
    public Class<?> cls(String name) { return type(name); }
    public boolean instance(Object o,String name) { return is(o,name); }
    public static double d(Object o) { return ((Number)o).doubleValue(); }
    public static float f(Object o) { return ((Number)o).floatValue(); }
    public static int i(Object o) { return ((Number)o).intValue(); }
    public static boolean b(Object o) { return Boolean.TRUE.equals(o); }
    public double value(Object o,String n) { return d(r(o,n)); }
    public float floating(Object o,String n) { return f(r(o,n)); }
    public int integerField(Object o,String n) { return i(r(o,n)); }
    public Object player() { return r(mc,"thePlayer"); }
    public Object world() { return r(mc,"theWorld"); }
    public Object screen() { return r(mc,"currentScreen"); }
    public Object settings() { return r(mc,"gameSettings"); }
    public boolean inWorld() { return player()!=null&&world()!=null; }
    public boolean overlay() { return inWorld() && (screen()==null || instance(screen(),"client.gui.GuiChat")) && !b(r(settings(),"showDebugInfo")); }
    public boolean screenOpen(){Object s=screen();return s!=null&&!instance(s,"client.gui.GuiChat")&&!s.getClass().getName().equals("tritium.minecraft.runtime.lyric.GeneratedHudEditor");}
    private Object zoomKey;private boolean zoomSearched;
    public boolean zooming(){if(!zoomSearched){zoomSearched=true;for(Object key:(Object[])r(settings(),"keyBindings"))if("of.key.zoom".equals(invoke(key,"getKeyDescription")))zoomKey=key;}return screen()==null&&zoomKey!=null&&b(invoke(cls("client.settings.GameSettings"),"isKeyDown",zoomKey));}
    public Object manager() { return invoke(mc,"getRenderManager"); }
    public Object font() { return r(mc,"fontRendererObj"); }
    public Object renderer() { return r(mc,"entityRenderer"); }
    public List<?> players() { return (List<?>)r(world(),"playerEntities"); }
    public List<?> entities() { return (List<?>)r(world(),"loadedEntityList"); }
    public double pos(Object e,int axis) { return value(e,new String[]{"posX","posY","posZ"}[axis]); }
    public double prev(Object e,int axis) { return value(e,new String[]{"lastTickPosX","lastTickPosY","lastTickPosZ"}[axis]); }
    public double interp(Object e,int axis,float pt) { return prev(e,axis)+(pos(e,axis)-prev(e,axis))*pt; }
    public double view(int axis) { return value(manager(),new String[]{"viewerPosX","viewerPosY","viewerPosZ"}[axis]); }
    public float eye(Object e) { return f(invoke(e,"getEyeHeight")); }
    public double distanceSq(Object a,Object c) { return distanceSq(a,pos(c,0),pos(c,1),pos(c,2)); }
    public double distanceSq(Object a,double x,double y,double z) { x-=pos(a,0);y-=pos(a,1);z-=pos(a,2);return x*x+y*y+z*z; }
    public int id(Object e) { return i(invoke(e,"getEntityId")); }
    public boolean bot(Object e) { return bot(e,true); }
    public boolean bot(Object e,boolean useSpawnDelay) { return antiBot!=null ? antiBot.bot(e,useSpawnDelay) : !instance(e,"entity.player.EntityPlayer")||b(r(e,"isDead"))||id(e)==-170; }
    public boolean invisible(Object e) { return b(invoke(e,"isInvisible")); }
    public boolean sneaking(Object e) { return b(invoke(e,"isSneaking")); }
    public Object held(Object e) { return invoke(e,"getHeldItem"); }
    public Object item(Object stack) { return stack==null?null:invoke(stack,"getItem"); }
    public Object item(String name) { return r(cls("init.Items"),name); }
    public Object block(String name) { return r(cls("init.Blocks"),name); }
    public Object stack(Object item) { return item==null?null:make("item.ItemStack",item); }
    public Object pos(int x,int y,int z) { return make("util.BlockPos",x,y,z); }
    public Object pos(double x,double y,double z) { return pos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)); }
    public int coord(Object pos,int axis) { return i(invoke(pos,new String[]{"getX","getY","getZ"}[axis])); }
    // Keep a distinct name: state(Object) would capture zero-argument GL calls such as state("pushMatrix").
    public Object blockState(Object pos) { return invoke(world(),"getBlockState",pos); }
    public Object blockAt(Object pos) { return invoke(blockState(pos),"getBlock"); }
    public Object vec(double x,double y,double z) { return make("util.Vec3",x,y,z); }
    public double[] vector(Object vec) { return new double[]{value(vec,"xCoord"),value(vec,"yCoord"),value(vec,"zCoord")}; }
    public Object box(double a,double c,double d,double e,double f,double h) { return make("util.AxisAlignedBB",a,c,d,e,f,h); }
    public Object entityBox(Object e) { return invoke(e,"getEntityBoundingBox"); }
    public double[] bounds(Object bb) { return new double[]{value(bb,"minX"),value(bb,"minY"),value(bb,"minZ"),value(bb,"maxX"),value(bb,"maxY"),value(bb,"maxZ")}; }
    public Object ray(double[] a,double[] z,boolean full) { Object av=vec(a[0],a[1],a[2]),zv=vec(z[0],z[1],z[2]); return full?invoke(world(),"rayTraceBlocks",av,zv,false,true,false):invoke(world(),"rayTraceBlocks",av,zv); }
    public Object scaled() { return make("client.gui.ScaledResolution",mc); }
    public int scale() { return i(invoke(scaled(),"getScaleFactor")); }
    public int width() { return i(invoke(scaled(),"getScaledWidth")); }
    public int height() { return i(invoke(scaled(),"getScaledHeight")); }
    public int displayWidth() { return integerField(mc,"displayWidth"); }
    public int displayHeight() { return integerField(mc,"displayHeight"); }
    public int textWidth(String text) { return i(invoke(font(),"getStringWidth",text)); }
    public int fontHeight() { return integerField(font(),"FONT_HEIGHT"); }
    public void text(String text,float x,float y,int color,boolean shadow) { prepareNative();org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);org.lwjgl.opengl.GL11.glBlendFunc(770,771);invoke(font(),"drawString",text,x,y,color,shadow); }
    private Class<?> forgeHooks;private boolean forgeSearched;
    public void drawItem(Object stack,float x,float y) {
        if(stack==null||item(stack)==null)return;
        prepareNative();invoke(cls("client.renderer.RenderHelper"),"enableGUIStandardItemLighting");
        Object renderItem=invoke(mc,"getRenderItem"),textures=invoke(mc,"getTextureManager"),atlas=r(cls("client.renderer.texture.TextureMap"),"locationBlocksTexture");
        float oldZ=floating(renderItem,"zLevel");w(renderItem,"zLevel",-100f);
        Object model=invoke(invoke(renderItem,"getItemModelMesher"),"getItemModel",stack);
        state("pushMatrix");Object texture=null;
        try {
            invoke(textures,"bindTexture",atlas);texture=invoke(textures,"getTexture",atlas);invoke(texture,"setBlurMipmap",false,false);
            state("enableRescaleNormal");state("enableBlend");state("blendFunc",770,771);state("color",1f,1f,1f,1f);
            state("translate",x,y,0f);state("translate",8f,8f,0f);state("scale",1f,1f,-1f);state("scale",.5f,.5f,.5f);
            if(b(invoke(model,"isGui3d"))){state("scale",40f,40f,40f);state("rotate",210f,1f,0f,0f);state("rotate",-135f,0f,1f,0f);state("enableLighting");}
            else {state("scale",64f,64f,64f);state("rotate",180f,1f,0f,0f);state("disableLighting");}
            Object transform=r(cls("client.renderer.block.model.ItemCameraTransforms$TransformType"),"GUI");
            if(!forgeSearched){forgeSearched=true;try{forgeHooks=Class.forName("net.minecraftforge.client.ForgeHooksClient",false,loader);}catch(ClassNotFoundException optional){}}
            if(forgeHooks!=null)model=invoke(forgeHooks,"handleCameraTransforms",model,transform);
            else invoke(invoke(model,"getItemCameraTransforms"),"applyTransform",transform);
            invoke(renderItem,"renderItem",stack,model);
        } finally {
            state("disableBlend");state("disableRescaleNormal");state("disableLighting");state("popMatrix");
            invoke(textures,"bindTexture",atlas);if(texture!=null)invoke(texture,"restoreLastBlurMipmap");w(renderItem,"zLevel",oldZ);
            invoke(cls("client.renderer.RenderHelper"),"disableStandardItemLighting");
        }
    }
    public float health(Object e) { return f(invoke(e,"getHealth"))+f(invoke(e,"getAbsorptionAmount")); }
    public float maxHealth(Object e) { return f(invoke(e,"getMaxHealth")); }
    public String displayName(Object e,boolean formatted) { return String.valueOf(invoke(invoke(e,"getDisplayName"),formatted?"getFormattedText":"getUnformattedText")); }
    public Object[] armor(Object e) { Object a=r(r(e,"inventory"),"armorInventory");Object[] out=new Object[Array.getLength(a)];for(int j=0;j<out.length;j++)out[j]=Array.get(a,j);return out; }
    public boolean lobby, bridgePractice, bedwars, hypixel;
    public void refreshServer() {
        lobby=bridgePractice=bedwars=hypixel=false;
        Object server=invoke(mc,"getCurrentServerData");
        if (server==null||b(invoke(mc,"isSingleplayer"))) return;
        String host=String.valueOf(r(server,"serverIP")).toLowerCase(Locale.ROOT);
        hypixel=host.endsWith("hypixel.net");
        if(!hypixel){Object overlay=invoke(r(mc,"ingameGUI"),"getTabList");Object head=r(overlay,"header");hypixel=head!=null&&String.valueOf(invoke(head,"getUnformattedText")).toLowerCase(Locale.ROOT).contains("hypixel.net");}
        if(!hypixel)return;
        Object sb=invoke(world(),"getScoreboard"),objective=invoke(sb,"getObjectiveInDisplaySlot",1);if(objective==null)return;
        String title=String.valueOf(invoke(objective,"getDisplayName")).replaceAll("§.","");
        bridgePractice=title.equals("BED WARS PRACTICE");
        List<?> scores=new ArrayList<Object>((Collection<?>)invoke(sb,"getSortedScores",objective));int line=0;
        for(int j=scores.size()-1;j>=0&&line<15;j--){Object score=scores.get(j);if(score==null)continue;String name=(String)invoke(score,"getPlayerName");if(name==null||name.startsWith("#"))continue;line++;Object team=invoke(sb,"getPlayersTeam",name);String s=String.valueOf(invoke(cls("scoreboard.ScorePlayerTeam"),"formatPlayerName",team,name));if(line==1&&s.contains("  §8L")){lobby=true;return;}if(title.equals("BED WARS")&&line>=5){if(line==8&&s.startsWith("Mode"))return;if(s.endsWith(" YOU")){bedwars=true;return;}}}
    }
}
