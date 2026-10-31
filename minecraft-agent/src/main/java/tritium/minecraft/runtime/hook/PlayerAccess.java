package tritium.minecraft.runtime.hook;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL14;
import tritium.minecraft.runtime.util.CombatMath;
import tritium.minecraft.runtime.util.GameReflection;
import tritium.minecraft.runtime.util.PlayerMappings;
import tritium.minecraft.runtime.lyric.NativeFontCache;
import tritium.minecraft.runtime.util.render.RenderState;
import tritium.minecraft.runtime.util.render.Render3D;
import java.util.List;
import java.util.Random;

/** OpenB4 PlayerUtils, ItemUtils, BlockUtils and rotation operations through MCP/SRG reflection. */
public class PlayerAccess extends GameReflection {
    private final Object mc;
    private NativeFontCache nativeCache;
    private Render3D renderer;
    private final Random random = new Random();
    public PlayerAccess(Object mc) { super(mc == null ? PlayerAccess.class.getClassLoader() : mc.getClass().getClassLoader()); this.mc=mc; }
    @Override protected Object get(Object object,String name) {return super.get(object,PlayerMappings.name(name));}
    @Override protected void set(Object object,String name,Object value) {super.set(object,PlayerMappings.name(name),value);}
    @Override protected Object call(Object object,String name,Object... values) {return super.call(object,PlayerMappings.name(name),values);}
    /** Reflection boundary used by the detached RavenBs physics simulation. */
    public Object minecraftRead(Object owner,String name){return get(owner,name);}
    public Object minecraftCall(Object owner,String name,Object... args){return call(owner,name,args);}
    public Object minecraftNew(String name,Object... args){return construct(name,args);}
    public Class<?> minecraftType(String name){return type(name);}
    public boolean minecraftIs(Object object,String name){return is(object,name);}
    public boolean inGameFocus(){return bool(get(mc,"inGameHasFocus"));}
    public float inputForward(Object in){return number(get(in,"moveForward")).floatValue();}
    public float inputStrafe(Object in){return number(get(in,"moveStrafe")).floatValue();}
    public boolean inputJump(Object in){return bool(get(in,"jump"));}
    public boolean inputSneak(Object in){return bool(get(in,"sneak"));}
    public void inputSneak(Object in,boolean value){set(in,"sneak",value);}
    public double[] playerBounds(){return bounds(box());}
    public double[] bounds(Object b){return new double[]{number(get(b,"minX/field_72340_a")).doubleValue(),number(get(b,"minY/field_72338_b")).doubleValue(),number(get(b,"minZ/field_72339_c")).doubleValue(),number(get(b,"maxX/field_72336_d")).doubleValue(),number(get(b,"maxY/field_72337_e")).doubleValue(),number(get(b,"maxZ/field_72334_f")).doubleValue()};}
    public java.util.List<double[]> collisions(double[] bounds){Object b=construct("util.AxisAlignedBB",bounds[0],bounds[1],bounds[2],bounds[3],bounds[4],bounds[5]);java.util.List<double[]> out=new java.util.ArrayList<double[]>();for(Object hit:(List<?>)call(world(),"getCollidingBoundingBoxes",player(),b))out.add(bounds(hit));return out;}
    public double[] simulateUnsneaked(Object input){
        tritium.minecraft.runtime.player.simulation.SimulatedPlayer sim=tritium.minecraft.runtime.player.simulation.SimulatedPlayer.fromClientPlayer(this,input);
        sim.movementInput.field_78899_d=false;sim.tick();tritium.minecraft.runtime.player.simulation.SimulationWorld.AxisAlignedBB b=sim.getEntityBoundingBox();
        return new double[]{b.field_72340_a,b.field_72338_b,b.field_72339_c,b.field_72336_d,b.field_72337_e,b.field_72334_f};
    }
    public boolean ravenReplaceable(Object pos){return bool(call(block(pos),"isReplaceable",world(),pos));}
    public boolean blockPlacement(Object p){return is(p,"network.play.client.C08PacketPlayerBlockPlacement")&&integer(call(p,"getPlacedBlockDirection"))!=255;}
    public Object player() { return get(mc,"thePlayer"); }
    public Object world() { return get(mc,"theWorld"); }
    public Object screen() { return get(mc,"currentScreen"); }
    public boolean inWorld() { return player()!=null && world()!=null; }
    public boolean focused() { return inWorld() && screen()==null && bool(get(mc,"inGameHasFocus")); }
    public boolean windowActive() { return Display.isCreated() && Display.isActive(); }
    public boolean screenOpen() { Object s=screen();return s!=null && !is(s,"client.gui.GuiChat") && !s.getClass().getName().equals("tritium.minecraft.runtime.lyric.GeneratedHudEditor"); }
    public boolean canRenderOverlay() { Object s=screen();return inWorld()&&!bool(get(get(mc,"gameSettings"),"showDebugInfo"))&&(s==null||is(s,"client.gui.GuiChat")); }
    public boolean paused() { return bool(call(mc,"isGamePaused")); }
    public long now() { return System.currentTimeMillis(); }
    public double random() { return random.nextDouble(); }
    public boolean mouse(int button) { return Mouse.isCreated() && Mouse.isButtonDown(button); }
    public Object controller() { return get(mc,"playerController"); }
    public Object input() { return get(player(),"movementInput"); }
    public Object inventory() { return get(player(),"inventory"); }
    public Object stack(int slot) { return call(inventory(),"getStackInSlot",slot); }
    public Object held() { return call(player(),"getHeldItem"); }
    public Object item(Object stack) { return stack==null ? null : call(stack,"getItem"); }
    public int count(Object stack) { return stack==null ? 0 : integer(get(stack,"stackSize")); }
    public int slot() { return integer(get(inventory(),"currentItem")); }
    public boolean slot(int slot) { if(slot<0)return false; set(inventory(),"currentItem",slot);return true; }
    public void clearEmptyStacks() { for(int i=0;i<9;i++) {Object s=stack(i);if(s!=null && count(s)<=0)call(inventory(),"setInventorySlotContents",i,null);} }
    public boolean blockItem(Object stack) { return is(item(stack),"item.ItemBlock"); }
    public Object itemBlock(Object stack) { return blockItem(stack)?call(item(stack),"getBlock"):null; }
    public Object blockConstant(String name) { return get(type("init.Blocks"),name); }
    public Object itemConstant(String name) { return get(type("init.Items"),name); }
    public boolean holding(String name) { return item(held())==itemConstant(name); }
    public boolean ladder(Object s) { return itemBlock(s)==blockConstant("ladder"); }
    public boolean scaffoldBlock(Object s) {
        Object b=itemBlock(s);if(b==null)return false;
        if(b==blockConstant("glass"))return true;
        if(!bool(call(b,"isFullBlock")))return false;
        for(String n:new String[]{"gravel","sand","soul_sand","tnt","crafting_table","furnace","dispenser","dropper","noteblock","command_block","pumpkin"})if(b==blockConstant(n))return false;
        return true;
    }
    public boolean scaffoldBlock() { return scaffoldBlock(held()); }
    public boolean tool() { Object i=item(held());for(String n:new String[]{"ItemPickaxe","ItemAxe","ItemSpade","ItemHoe","ItemShears"})if(is(i,"item."+n))return true;return false; }
    public boolean creative() { return bool(get(get(player(),"capabilities"),"isCreativeMode")); }
    public boolean flying() { return bool(get(get(player(),"capabilities"),"isFlying")); }
    public boolean adventure() { return !bool(get(get(player(),"capabilities"),"allowEdit")); }
    public boolean ground() { return bool(get(player(),"onGround")); }
    public boolean sneaking() { return bool(call(player(),"isSneaking")); }
    public boolean sprinting() { return bool(call(player(),"isSprinting")); }
    public boolean onLadder() { return bool(call(player(),"isOnLadder")); }
    public boolean liquid() { return bool(call(player(),"isInWater")) || bool(call(player(),"isInLava")); }
    public float fallDistance() { return number(get(player(),"fallDistance")).floatValue(); }
    public float yaw() { return number(get(player(),"rotationYaw")).floatValue(); }
    public float pitch() { return number(get(player(),"rotationPitch")).floatValue(); }
    public int ticks() { return integer(get(player(),"ticksExisted")); }
    public float forward() { return number(get(input(),"moveForward")).floatValue(); }
    public float strafe() { return number(get(input(),"moveStrafe")).floatValue(); }
    public double x() { return number(get(player(),"posX")).doubleValue(); }
    public double y() { return number(get(player(),"posY")).doubleValue(); }
    public double z() { return number(get(player(),"posZ")).doubleValue(); }
    public double motionX() { return number(get(player(),"motionX")).doubleValue(); }
    public double motionY() { return number(get(player(),"motionY")).doubleValue(); }
    public double motionZ() { return number(get(player(),"motionZ")).doubleValue(); }
    public void multiplyMotion(double amount) { set(player(),"motionX",motionX()*amount);set(player(),"motionZ",motionZ()*amount); }
    public double eyeHeight() { return number(call(player(),"getEyeHeight")).doubleValue(); }
    public Object box() { return call(player(),"getEntityBoundingBox"); }
    public double boxMinY() { return number(get(box(),"minY")).doubleValue(); }
    public boolean noCollision(double dx,double dy,double dz) { return ((List<?>)call(world(),"getCollidingBoundingBoxes",player(),call(box(),"offset",dx,dy,dz))).isEmpty(); }
    public Object binding(String name) { return get(get(mc,"gameSettings"),"keyBind"+name); }
    public boolean keyDown(String name) { return bool(call(binding(name),"isKeyDown")); }
    public boolean physicalKey(String name) { return bool(call(type("client.settings.GameSettings"),"isKeyDown",binding(name))); }
    public void key(String name,boolean down) { call(type("client.settings.KeyBinding"),"setKeyBindState",call(binding(name),"getKeyCode"),down); }
    public void release(String name) { key(name,false);set(binding(name),"pressTime",0); }
    public void restoreKey(String name) { key(name,physicalKey(name)); }
    public void sneakState(boolean value) {Object in=input();boolean was=bool(get(in,"sneak"));set(in,"sneak",value);if(!was&&value){set(in,"moveForward",(float)(forward()*.3));set(in,"moveStrafe",(float)(strafe()*.3));} }
    public void jumpState(boolean value) { set(input(),"jump",value); }
    public float sin(float value){return number(call(type("util.MathHelper"),"sin",value)).floatValue();}
    public float cos(float value){return number(call(type("util.MathHelper"),"cos",value)).floatValue();}
    public float sqrt(float value){return number(call(type("util.MathHelper"),"sqrt_float",value)).floatValue();}
    public float moveSpeed() { return number(call(player(),"getAIMoveSpeed")).floatValue(); }
    public Object hit() { return get(mc,"objectMouseOver"); }
    public boolean blockHit(Object hit) { return hit!=null && "BLOCK".equals(String.valueOf(get(hit,"typeOfHit"))); }
    public Object hitPos(Object hit) { return call(hit,"getBlockPos"); }
    public Object hitFace(Object hit) { return get(hit,"sideHit"); }
    public Object hitVec(Object hit) { return get(hit,"hitVec"); }
    public Object pos(int x,int y,int z) { return construct("util.BlockPos",x,y,z); }
    public int posX(Object p) { return integer(call(p,"getX")); }
    public int posY(Object p) { return integer(call(p,"getY")); }
    public int posZ(Object p) { return integer(call(p,"getZ")); }
    public Object up(Object p) { return call(p,"up"); }
    public Object down(Object p) { return call(p,"down"); }
    public Object offset(Object p,Object facing) { return call(p,"offset",facing); }
    public Object state(Object p) { return call(world(),"getBlockState",p); }
    public Object block(Object p) { return call(state(p),"getBlock"); }
    public boolean full(Object p) { return bool(call(block(p),"isFullBlock")); }
    public boolean replaceable(Object p) { Object b=block(p);return b==blockConstant("air")||b==blockConstant("fire")||is(b,"block.BlockLiquid")||bool(call(call(b,"getMaterial"),"isReplaceable")); }
    public boolean solid(int x,int y,int z) { return bool(call(call(block(pos(x,y,z)),"getMaterial"),"isSolid")); }
    public boolean airBelow() { return bool(call(world(),"isAirBlock",construct("util.BlockPos",x(),y()-(y()%1.0==0?1:0),z()))); }
    public boolean interactable(Object b) {
        if(is(b,"block.BlockContainer")||b==blockConstant("crafting_table")||b==blockConstant("anvil")||b==blockConstant("bed")||b==blockConstant("lever"))return true;
        if(is(b,"block.BlockTrapDoor")||is(b,"block.BlockFence")||is(b,"block.BlockFenceGate"))return true;
        return is(b,"block.BlockDoor")&&b!=blockConstant("iron_door");
    }
    @SuppressWarnings({"unchecked","rawtypes"}) public Object facing(String name) { return Enum.valueOf((Class)type("util.EnumFacing"),name); }
    public Object horizontalFacing() { return call(player(),"getHorizontalFacing"); }
    public Object opposite(Object f) { return call(f,"getOpposite"); }
    public Object clockwise(Object f) { return call(f,"rotateY"); }
    public Object counterClockwise(Object f) { return call(f,"rotateYCCW"); }
    public boolean topFace(Object hit) { return blockHit(hit)&&hitFace(hit)==facing("UP"); }
    public Object vector(double x,double y,double z) { return construct("util.Vec3",x,y,z); }
    public double reach() { return number(call(controller(),"getBlockReachDistance")).doubleValue(); }
    public Object ray(double range,float yaw,float pitch) {
        float yr=yaw*(-((float)Math.PI/180)),pr=pitch*(-((float)Math.PI/180));float horizontal=-cos(pr);double[] d={sin(yr-(float)Math.PI)*horizontal,sin(pr),cos(yr-(float)Math.PI)*horizontal};double ex=x(),ey=y()+eyeHeight(),ez=z();
        return call(world(),"rayTraceBlocks",vector(ex,ey,ez),vector(ex+d[0]*range,ey+d[1]*range,ez+d[2]*range),false,false,false);
    }
    public double heightAboveGround() {
        Object h=ray(100,0,90);if(!blockHit(h))return -1;
        Object p=hitPos(h),b=block(p);double y=y();
        if(bool(call(call(b,"getMaterial"),"isSolid"))||b==blockConstant("carpet")||b==blockConstant("waterlily"))y-=number(call(b,"getBlockBoundsMaxY")).doubleValue();
        return Math.max(y-posY(p),0);
    }
    public int potionLevel(String name) {Object p=get(type("potion.Potion"),name),e=call(player(),"getActivePotionEffect",p);return e==null?0:integer(call(e,"getAmplifier"))+1;}
    public Object randomizedHitVec(Object p,Object face) {
        double x=posX(p)+.1+random()*.8,y=posY(p)+.05+random()*.9,z=posZ(p)+.1+random()*.8,top=1;
        Object st=state(p),b=call(st,"getBlock");
        if(!bool(call(b,"isFullBlock"))&&is(b,"block.BlockSlab")) {
            Object half=call(st,"getValue",get(type("block.BlockSlab"),"HALF"));
            if("TOP".equals(String.valueOf(half)))y=posY(p)+.505+random()*(.95-.505);
            else if(face==facing("UP"))top=.5;else y=posY(p)+.05+random()*(.495-.05);
        }
        String f=((Enum<?>)face).name();if("EAST".equals(f))x=posX(p)+1;else if("WEST".equals(f))x=posX(p);
        else if("NORTH".equals(f))z=posZ(p);else if("SOUTH".equals(f))z=posZ(p)+1;else if("UP".equals(f))y=posY(p)+top;else if("DOWN".equals(f))y=posY(p);
        return vector(x,y,z);
    }
    public boolean place(Object p,Object face,Object vec) { return bool(call(controller(),"onPlayerRightClick",player(),world(),held(),p,face,vec)); }
    public boolean place(Object hit) { return place(hitPos(hit),hitFace(hit),hitVec(hit)); }
    public void use(Object hit) {place(hit);call(controller(),"sendUseItem",player(),world(),held());}
    public void swing() {call(player(),"swingItem");call(call(mc,"getItemRenderer"),"resetEquippedProgress");}
    public float[] blockRotations(Object p,Object face,float lastYaw,float lastPitch) {
        Object v=call(face,"getDirectionVec");double dx=posX(p)+.45-x()+posX(v)*.5,dy=posY(p)+.45-y()-eyeHeight()+posY(v)*.5,dz=posZ(p)+.45-z()+posZ(v)*.5;
        float y=yaw()+CombatMath.wrap((float)(Math.atan2(dz,dx)*(double)57.29578f)-90-yaw());
        float pitch=CombatMath.pitch(this.pitch()+CombatMath.wrap((float)-(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz))*(double)57.29578f)-this.pitch()));
        return sensitivity(y,pitch,lastYaw,lastPitch);
    }
    public float[] sensitivity(float y,float p,float lastY,float lastP) {return CombatMath.sensitivity(y,p,lastY,lastP,number(get(get(mc,"gameSettings"),"mouseSensitivity")).floatValue(),random.nextInt(7)-3);}
    private int efficiency(Object stack) {return integer(call(type("enchantment.EnchantmentHelper"),"getEnchantmentLevel",integer(get(get(type("enchantment.Enchantment"),"efficiency"),"effectId")),stack));}
    public int bestTool(Object block) {
        float best=1;int slot=-1;for(int i=0;i<9;i++){Object s=stack(i);if(s==null)continue;float speed=number(call(s,"getStrVsBlock",block)).floatValue();int level;if(speed>1&&(level=efficiency(s))>0)speed+=level*level+1;if(speed>best){best=speed;slot=i;}}return slot;
    }
    public float breakSpeed(Object block,Object stack) {
        if(block==null)return 0;float hardness=number(call(block,"getBlockHardness",world(),call(player(),"getPosition"))).floatValue();if(hardness<0)return 0;
        boolean harvest=bool(call(call(block,"getMaterial"),"isToolNotRequired"))||stack!=null&&bool(call(stack,"canHarvestBlock",block));
        float speed=stack==null?1:number(call(item(stack),"getStrVsBlock",stack,block)).floatValue();int level;
        if(speed>1&&(level=efficiency(stack))>0)speed+=level*level+1;
        if((level=potionLevel("digSpeed"))>0)speed*=1+level*.2f;
        if((level=potionLevel("digSlowdown"))>0)speed*=level==1?.3f:level==2?.09f:level==3?.0027f:8.1E-4f;
        if(bool(call(player(),"isInsideOfMaterial",get(type("block.material.Material"),"water")))&&!bool(call(type("enchantment.EnchantmentHelper"),"getAquaAffinityModifier",player())))speed/=5;
        if(!ground())speed/=5;return speed/hardness/(harvest?30f:100f);
    }
    public int blockHitDelay() {return integer(get(controller(),"blockHitDelay"));}
    public void blockHitDelay(int v) {set(controller(),"blockHitDelay",v);}
    public float blockDamage() {return number(get(controller(),"curBlockDamageMP")).floatValue();}
    public void blockDamage(float v) {set(controller(),"curBlockDamageMP",v);}
    public int rightClickDelay() {return integer(get(mc,"rightClickDelayTimer"));}
    public void rightClickDelay(int v) {set(mc,"rightClickDelayTimer",v);}
    public void leftClickDelay(int v) {set(mc,"leftClickCounter",v);}
    public int jumpTicks() {return integer(get(player(),"jumpTicks"));}
    public void jumpTicks(int v) {set(player(),"jumpTicks",v);}
    public boolean airBlockPlacement(Object p) {return is(p,"network.play.client.C08PacketPlayerBlockPlacement")&&integer(call(p,"getPlacedBlockDirection"))==255&&blockItem(call(p,"getStack"));}
    public void blockCount(String text,int alpha) {
        Object resolution=construct("client.gui.ScaledResolution",mc);float x=number(call(resolution,"getScaledWidth")).floatValue()/2+11,y=number(call(resolution,"getScaledHeight")).floatValue()/2-3;
        try(RenderState ignored=renderState()) {
            nativeCache.prepare();GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(770,771,1,0);
            call(get(mc,"fontRendererObj"),"drawString",text,x,y,(Math.max(0,Math.min(255,alpha))<<24)|0xffffff,true);
        } catch(ReflectiveOperationException e){throw failure(e);}
    }
    public RenderState renderState() {
        if(nativeCache==null)try{nativeCache=new NativeFontCache(loader);}catch(ReflectiveOperationException e){throw failure(e);}
        return new RenderState(nativeCache);
    }
    public void highlight(Object pos,int color) {if(renderer==null)renderer=new Render3D(new RenderAccess(mc));renderer.block(pos,color,true,false);}
    public boolean moving(){return forward()!=0||strafe()!=0;}
    public double distanceToPos(Object p){return square(x()-posX(p))+square(y()-posY(p))+square(z()-posZ(p));}
    public double vecX(Object p){return number(get(p,"xCoord")).doubleValue();}
    public double vecY(Object p){return number(get(p,"yCoord")).doubleValue();}
    public double vecZ(Object p){return number(get(p,"zCoord")).doubleValue();}
    public boolean samePos(Object a,Object b){return a==b||a!=null&&b!=null&&posX(a)==posX(b)&&posY(a)==posY(b)&&posZ(a)==posZ(b);}
    public int packetKind(Object p){
        if(is(p,"network.play.client.C03PacketPlayer"))return 1;
        if(is(p,"network.play.client.C02PacketUseEntity")||is(p,"network.play.client.C07PacketPlayerDigging"))return 2;
        if(is(p,"network.play.client.C0DPacketCloseWindow"))return 3;return 0;
    }
    public void airPlacement(){call(controller(),"syncCurrentPlayItem");call(call(mc,"getNetHandler"),"addToSendQueue",construct("network.play.client.C08PacketPlayerBlockPlacement",held()));}
    public float[] sensitivityNoJitter(float y,float p,float lastY,float lastP){return CombatMath.sensitivity(y,p,lastY,lastP,number(get(get(mc,"gameSettings"),"mouseSensitivity")).floatValue(),0);}

}
