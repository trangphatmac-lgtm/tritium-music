package tritium.minecraft.runtime.player.simulation;

import java.util.*;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Snapshot and reflection boundary for the unlinked RavenBs physics simulator.
 * Identity caches preserve block/material identity; mutable capability and food data use private NBT copies. */
public final class SimulationWorld {
    private SimulationWorld() { }
    public static final class Minecraft {
        public final PlayerAccess game;
        public final EntityPlayerSP field_71439_g;
        public final World field_71441_e;
        public final PlayerController field_71442_b;
        final IdentityHashMap<Object,Block> blocksByIdentity=new IdentityHashMap<Object,Block>();
        final IdentityHashMap<Object,Material> materialsByIdentity=new IdentityHashMap<Object,Material>();
        final IdentityHashMap<Object,Entity> entitiesByIdentity=new IdentityHashMap<Object,Entity>();
        public final Blocks blocks;
        public final Materials materials;
        public final Potions potions;
        public final Attributes attributes;
        public final Selectors selectors;
        public final Enchantments enchantments;
        public final Object liquidLevel,ladderFacing;
        public Minecraft(PlayerAccess game) {
            this.game=game;field_71441_e=new World(this,game.world());
            field_71442_b=new PlayerController(this,game.controller());
            blocks=new Blocks(this);materials=new Materials(this);potions=new Potions(this);
            attributes=new Attributes(this);selectors=new Selectors(this);enchantments=new Enchantments(this);
            liquidLevel=read(game.minecraftType("block.BlockLiquid"),"field_176367_b");
            ladderFacing=read(game.minecraftType("block.BlockLadder"),"field_176382_a");
            field_71439_g=new EntityPlayerSP(this,game.player());entitiesByIdentity.put(game.player(),field_71439_g);
        }
        Object raw(Object value) {
            if(value instanceof Ref)return ((Ref)value).value;
            if(value instanceof BlockPos){BlockPos p=(BlockPos)value;return game.pos(p.x,p.y,p.z);}
            if(value instanceof AxisAlignedBB){AxisAlignedBB b=(AxisAlignedBB)value;return game.minecraftNew("util.AxisAlignedBB",b.field_72340_a,b.field_72338_b,b.field_72339_c,b.field_72336_d,b.field_72337_e,b.field_72334_f);}
            if(value instanceof Vec3){Vec3 v=(Vec3)value;return game.vector(v.field_72450_a,v.field_72448_b,v.field_72449_c);}
            if(value instanceof EnumFacing)return game.facing(value.toString());
            if(value instanceof Predicate)return ((Predicate<?>)value).value;
            return value;
        }
        Object read(Object receiver,String srg){return game.minecraftRead(raw(receiver),SimulationMappings.name(srg));}
        Object call(Object receiver,String srg,Object... args){Object[] unwrapped=new Object[args.length];for(int i=0;i<args.length;i++)unwrapped[i]=raw(args[i]);return game.minecraftCall(raw(receiver),SimulationMappings.name(srg),unwrapped);}
        Object create(String name){return game.minecraftNew(name);}
        boolean bool(Object value){return Boolean.TRUE.equals(value);}
        int integer(Object value){return ((Number)value).intValue();}
        float floating(Object value){return ((Number)value).floatValue();}
        double decimal(Object value){return ((Number)value).doubleValue();}
        Block block(Object value){if(value==null)return null;Block b=blocksByIdentity.get(value);if(b!=null)return b;
            if(game.minecraftIs(value,"block.BlockSlime"))b=new BlockSlime(this,value);
            else if(game.minecraftIs(value,"block.BlockSoulSand"))b=new BlockSoulSand(this,value);
            else if(game.minecraftIs(value,"block.BlockWeb"))b=new BlockWeb(this,value);
            else if(game.minecraftIs(value,"block.BlockFence"))b=new BlockFence(this,value);
            else if(game.minecraftIs(value,"block.BlockFenceGate"))b=new BlockFenceGate(this,value);
            else if(game.minecraftIs(value,"block.BlockWall"))b=new BlockWall(this,value);
            else b=new Block(this,value);blocksByIdentity.put(value,b);return b;}
        Material material(Object value){Material m=materialsByIdentity.get(value);if(m==null){m=new Material(this,value);materialsByIdentity.put(value,m);}return m;}
        Entity entity(Object value){if(value==null)return null;Entity e=entitiesByIdentity.get(value);if(e==null){e=game.minecraftIs(value,"entity.item.EntityBoat")?new EntityBoat(this,value):game.minecraftIs(value,"entity.item.EntityMinecart")?new EntityMinecart(this,value):new Entity(this,value);entitiesByIdentity.put(value,e);}return e;}
        AxisAlignedBB box(Object value){if(value==null)return null;return new AxisAlignedBB(decimal(read(value,"field_72340_a")),decimal(read(value,"field_72338_b")),decimal(read(value,"field_72339_c")),decimal(read(value,"field_72336_d")),decimal(read(value,"field_72337_e")),decimal(read(value,"field_72334_f")));}
        Vec3 vector(Object value){return new Vec3(decimal(read(value,"field_72450_a")),decimal(read(value,"field_72448_b")),decimal(read(value,"field_72449_c")));}
        BlockPos position(Object value){return new BlockPos(integer(call(value,"func_177958_n")),integer(call(value,"func_177956_o")),integer(call(value,"func_177952_p")));}
    }
    public static class Ref {public final Minecraft mc;final Object value;Ref(Minecraft mc,Object value){this.mc=mc;this.value=value;}Object r(String n){return mc.read(value,n);}Object c(String n,Object... a){return mc.call(value,n,a);}boolean b(String n,Object... a){return mc.bool(c(n,a));}int i(String n,Object... a){return mc.integer(c(n,a));}float f(String n,Object... a){return mc.floating(c(n,a));}double d(String n,Object... a){return mc.decimal(c(n,a));}}
    public static class Entity extends Ref {
        Entity(Minecraft mc,Object o){super(mc,o);}
        public AxisAlignedBB func_174813_aQ(){return mc.box(c("func_174813_aQ"));}
        public AxisAlignedBB func_70046_E(){return mc.box(c("func_70046_E"));}
        public AxisAlignedBB func_70114_g(Entity e){return mc.box(c("func_70114_g",e));}
    }
    public static final class EntityBoat extends Entity {EntityBoat(Minecraft m,Object o){super(m,o);}}
    public static final class EntityMinecart extends Entity {EntityMinecart(Minecraft m,Object o){super(m,o);}}
    public static final class EntityPlayerSP extends Entity {
        public final double field_70179_y,field_70181_x,field_70159_w,field_70165_t,field_70163_u,field_70161_v;
        public final boolean field_70122_E,field_70160_al,field_70123_F,field_70124_G,field_70132_H,field_70145_X;
        public final float field_70177_z,field_70125_A,field_70747_aH,field_70143_R,field_70138_W,field_70140_Q,field_82151_R,field_70131_O,field_70130_N;
        public final int field_70174_ab;
        public final PlayerCapabilities field_71075_bZ;
        public final Entity field_70154_o,field_70153_n;
        public final World field_70170_p;
        EntityPlayerSP(Minecraft m,Object o){super(m,o);
            field_70179_y=m.decimal(r("field_70179_y"));field_70181_x=m.decimal(r("field_70181_x"));field_70159_w=m.decimal(r("field_70159_w"));
            field_70165_t=m.decimal(r("field_70165_t"));field_70163_u=m.decimal(r("field_70163_u"));field_70161_v=m.decimal(r("field_70161_v"));
            field_70122_E=m.bool(r("field_70122_E"));field_70160_al=m.bool(r("field_70160_al"));field_70123_F=m.bool(r("field_70123_F"));field_70124_G=m.bool(r("field_70124_G"));field_70132_H=m.bool(r("field_70132_H"));field_70145_X=m.bool(r("field_70145_X"));
            field_70177_z=m.floating(r("field_70177_z"));field_70125_A=m.floating(r("field_70125_A"));field_70747_aH=m.floating(r("field_70747_aH"));field_70143_R=m.floating(r("field_70143_R"));field_70138_W=m.floating(r("field_70138_W"));field_70140_Q=m.floating(r("field_70140_Q"));field_82151_R=m.floating(r("field_82151_R"));field_70131_O=m.floating(r("field_70131_O"));field_70130_N=m.floating(r("field_70130_N"));
            field_70174_ab=m.integer(r("field_70174_ab"));field_71075_bZ=new PlayerCapabilities(m,r("field_71075_bZ"));field_70170_p=m.field_71441_e;
            field_70154_o=m.entity(r("field_70154_o"));field_70153_n=m.entity(r("field_70153_n"));
        }
        public int getJumpTicks(){return mc.integer(r("field_70773_bE"));}
        public int getFire(){return mc.integer(r("field_70151_c"));}
        public int getNextStepDistance(){return mc.integer(r("field_70150_b"));}
        public boolean getIsInWeb(){return mc.bool(r("field_70134_J"));}
        public boolean func_70090_H(){return b("func_70090_H");}
        public boolean func_174832_aS(){return b("func_174832_aS");}
        public BaseAttributeMap func_110140_aT(){Object a=c("func_110140_aT");return a==null?null:new BaseAttributeMap(mc,a);}
        public boolean func_175149_v(){return b("func_175149_v");}
        public boolean func_70051_ag(){return b("func_70051_ag");}
        public FoodStats func_71024_bL(){return new FoodStats(mc,c("func_71024_bL"));}
        public boolean func_70115_ae(){return b("func_70115_ae");}
        public boolean func_71039_bw(){return b("func_71039_bw");}
        public PotionEffect func_70660_b(Potion p){Object effect=c("func_70660_b",p);return effect==null?null:new PotionEffect(mc,effect);}
        public float func_110143_aJ(){return f("func_110143_aJ");}
        public boolean func_70608_bn(){return b("func_70608_bn");}
    }
    public static final class MovementInput {
        public float field_78900_b,field_78902_a;public boolean field_78901_c,field_78899_d;
        public MovementInput(){}
        MovementInput(PlayerAccess g,Object i){field_78900_b=((Number)g.minecraftRead(i,"moveForward")).floatValue();field_78902_a=((Number)g.minecraftRead(i,"moveStrafe")).floatValue();field_78901_c=Boolean.TRUE.equals(g.minecraftRead(i,"jump"));field_78899_d=Boolean.TRUE.equals(g.minecraftRead(i,"sneak"));}
    }
    public static final class NBTTagCompound extends Ref {NBTTagCompound(Minecraft m){super(m,m.create("nbt.NBTTagCompound"));}}
    public static final class FoodStats extends Ref {FoodStats(Minecraft m){this(m,m.create("util.FoodStats"));}FoodStats(Minecraft m,Object o){super(m,o);}public void func_75117_b(NBTTagCompound n){c("func_75117_b",n);}public void func_75112_a(NBTTagCompound n){c("func_75112_a",n);}public int func_75116_a(){return i("func_75116_a");}}
    public static final class PlayerCapabilities extends Ref {
        public boolean field_75100_b,field_75101_c;
        PlayerCapabilities(Minecraft m){this(m,m.create("entity.player.PlayerCapabilities"));}
        PlayerCapabilities(Minecraft m,Object o){super(m,o);snapshot();}
        void snapshot(){field_75100_b=mc.bool(r("field_75100_b"));field_75101_c=mc.bool(r("field_75101_c"));}
        public void func_75091_a(NBTTagCompound n){c("func_75091_a",n);}
        public void func_75095_b(NBTTagCompound n){c("func_75095_b",n);snapshot();}
        public float func_75093_a(){return f("func_75093_a");}
    }
    public static final class PlayerController extends Ref {PlayerController(Minecraft m,Object o){super(m,o);}public boolean func_178887_k(){return b("func_178887_k");}}
    public static final class Potion extends Ref {Potion(Minecraft m,Object o){super(m,o);}}
    public static final class PotionEffect extends Ref {PotionEffect(Minecraft m,Object o){super(m,o);}public int func_76458_c(){return i("func_76458_c");}}
    public static final class Potions {public final Potion field_76440_q,field_76430_j;Potions(Minecraft m){Object t=m.game.minecraftType("potion.Potion");field_76440_q=new Potion(m,m.read(t,"field_76440_q"));field_76430_j=new Potion(m,m.read(t,"field_76430_j"));}}
    public static final class Enchantments {final Minecraft m;Enchantments(Minecraft m){this.m=m;}public int func_180318_b(EntityPlayerSP p){return m.integer(m.call(m.game.minecraftType("enchantment.EnchantmentHelper"),"func_180318_b",p));}public int func_92093_a(EntityPlayerSP p,int ticks){return m.integer(m.call(m.game.minecraftType("enchantment.EnchantmentProtection"),"func_92093_a",p,ticks));}}
    public static final class IAttribute extends Ref {IAttribute(Minecraft m,Object o){super(m,o);}}
    public static final class IAttributeInstance extends Ref {IAttributeInstance(Minecraft m,Object o){super(m,o);}public double func_111126_e(){return d("func_111126_e");}}
    public static final class BaseAttributeMap extends Ref {BaseAttributeMap(Minecraft m,Object o){super(m,o);}public IAttributeInstance func_111151_a(IAttribute a){return new IAttributeInstance(mc,c("func_111151_a",a));}}
    public static final class Attributes {public final IAttribute field_111263_d;Attributes(Minecraft m){field_111263_d=new IAttribute(m,m.read(m.game.minecraftType("entity.SharedMonsterAttributes"),"field_111263_d"));}}
    public static final class Predicate<T> {final Object value;Predicate(Object v){value=v;}}
    public static final class Selectors {public final Predicate<Entity> field_180132_d;Selectors(Minecraft m){field_180132_d=new Predicate<Entity>(m.read(m.game.minecraftType("util.EntitySelectors"),"field_180132_d"));}}
    public static final class Material extends Ref {Material(Minecraft m,Object o){super(m,o);}}
    public static final class Materials {public final Material field_151579_a,field_151586_h,field_151587_i;Materials(Minecraft m){Object t=m.game.minecraftType("block.material.Material");field_151579_a=m.material(m.read(t,"field_151579_a"));field_151586_h=m.material(m.read(t,"field_151586_h"));field_151587_i=m.material(m.read(t,"field_151587_i"));}}
    public static class Block extends Ref {
        public final float field_149765_K;
        Block(Minecraft m,Object o){super(m,o);field_149765_K=m.floating(r("field_149765_K"));}
        public boolean func_149721_r(){return b("func_149721_r");}
        public Material func_149688_o(){return mc.material(c("func_149688_o"));}
        public IBlockState func_176223_P(){return new IBlockState(mc,c("func_176223_P"));}
        public Vec3 func_176197_a(World world,BlockPos pos,Entity player,Vec3 vec){return mc.vector(c("func_176197_a",world,pos,player,vec));}
        public void func_180638_a(World world,BlockPos pos,IBlockState state,AxisAlignedBB box,List<AxisAlignedBB> out,Entity player){List<Object> raw=new ArrayList<Object>();c("func_180638_a",world,pos,state,box,raw,player);for(Object b:raw)out.add(mc.box(b));}
    }
    public static final class BlockSlime extends Block {BlockSlime(Minecraft m,Object o){super(m,o);}}
    public static final class BlockSoulSand extends Block {BlockSoulSand(Minecraft m,Object o){super(m,o);}}
    public static final class BlockWeb extends Block {BlockWeb(Minecraft m,Object o){super(m,o);}}
    public static final class BlockFence extends Block {BlockFence(Minecraft m,Object o){super(m,o);}}
    public static final class BlockFenceGate extends Block {BlockFenceGate(Minecraft m,Object o){super(m,o);}}
    public static final class BlockWall extends Block {BlockWall(Minecraft m,Object o){super(m,o);}}
    public static final class BlockLiquid {public static float func_149801_b(int level){if(level>=8)level=0;return (level+1)/9.0F;}}
    public static final class IBlockState extends Ref {IBlockState(Minecraft m,Object o){super(m,o);}public Block func_177230_c(){return mc.block(c("func_177230_c"));}public Object func_177229_b(Object property){Object result=c("func_177229_b",property);return property==mc.ladderFacing?EnumFacing.valueOf(((Enum<?>)result).name()):result;}}
    public static final class Blocks {public final Block field_150348_b,field_150468_ap,field_150395_bd;Blocks(Minecraft m){Object t=m.game.minecraftType("init.Blocks");field_150348_b=m.block(m.read(t,"field_150348_b"));field_150468_ap=m.block(m.read(t,"field_150468_ap"));field_150395_bd=m.block(m.read(t,"field_150395_bd"));}}
    public static final class World extends Ref {
        public final boolean field_72995_K;
        World(Minecraft m,Object o){super(m,o);field_72995_K=m.bool(r("field_72995_K"));}
        public WorldBorder func_175723_af(){return new WorldBorder(mc,c("func_175723_af"));}
        public IChunkProvider func_72863_F(){return new IChunkProvider(mc,c("func_72863_F"));}
        public boolean func_175667_e(BlockPos p){return b("func_175667_e",p);}
        public Chunk func_175726_f(BlockPos p){return new Chunk(mc,c("func_175726_f",p));}
        public boolean func_147470_e(AxisAlignedBB box){return b("func_147470_e",box);}
        public boolean func_72875_a(AxisAlignedBB box,Material material){return b("func_72875_a",box,material);}
        public boolean func_72953_d(AxisAlignedBB box){return b("func_72953_d",box);}
        public IBlockState func_180495_p(BlockPos p){Object state=c("func_180495_p",p);return state==null?null:new IBlockState(mc,state);}
        public float func_72867_j(float pt){return f("func_72867_j",pt);}
        public BlockPos func_175725_q(BlockPos p){return mc.position(c("func_175725_q",p));}
        public BiomeGenBase func_180494_b(BlockPos p){return new BiomeGenBase(mc,c("func_180494_b",p));}
        public boolean func_175708_f(BlockPos p,boolean f){return b("func_175708_f",p,f);}
    }
    public static final class WorldBorder extends Ref {WorldBorder(Minecraft m,Object o){super(m,o);}public boolean func_177746_a(BlockPos p){return b("func_177746_a",p);}public double func_177726_b(){return d("func_177726_b");}public double func_177736_c(){return d("func_177736_c");}public double func_177728_d(){return d("func_177728_d");}public double func_177733_e(){return d("func_177733_e");}}
    public static final class IChunkProvider extends Ref {IChunkProvider(Minecraft m,Object o){super(m,o);}public boolean func_73149_a(int x,int z){return b("func_73149_a",x,z);}public Chunk func_73154_d(int x,int z){return new Chunk(mc,c("func_73154_d",x,z));}}
    public static final class Chunk extends Ref {Chunk(Minecraft m,Object o){super(m,o);}public boolean func_177410_o(){return b("func_177410_o");}public boolean func_76621_g(){return b("func_76621_g");}public boolean func_177444_d(BlockPos p){return b("func_177444_d",p);}public void func_177414_a(Entity entity,AxisAlignedBB box,List<Entity> list,Predicate<Entity> predicate){List<Object> raw=new ArrayList<Object>();c("func_177414_a",entity,box,raw,predicate);for(Object e:raw)list.add(mc.entity(e));}}
    public static final class BiomeGenBase extends Ref {BiomeGenBase(Minecraft m,Object o){super(m,o);}public boolean func_76746_c(){return b("func_76746_c");}public boolean func_76738_d(){return b("func_76738_d");}}
    public enum EnumFacing {NORTH,SOUTH,EAST,WEST,UP,DOWN}
    public static class BlockPos {
        protected int x,y,z;
        public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
        public BlockPos(double x,double y,double z){this(MathHelper.func_76128_c(x),MathHelper.func_76128_c(y),MathHelper.func_76128_c(z));}
        public int func_177958_n(){return x;}public int func_177956_o(){return y;}public int func_177952_p(){return z;}
        public BlockPos func_177982_a(int x,int y,int z){return new BlockPos(this.x+x,this.y+y,this.z+z);}
        public BlockPos func_177976_e(){return func_177982_a(-1,0,0);}public BlockPos func_177974_f(){return func_177982_a(1,0,0);}
        public BlockPos func_177978_c(){return func_177982_a(0,0,-1);}public BlockPos func_177968_d(){return func_177982_a(0,0,1);}public BlockPos func_177977_b(){return func_177982_a(0,-1,0);}
        @Override public int hashCode(){return (y+z*31)*31+x;}
        @Override public boolean equals(Object p){return p instanceof BlockPos&&x==((BlockPos)p).x&&y==((BlockPos)p).y&&z==((BlockPos)p).z;}
    }
    public static final class MutableBlockPos extends BlockPos {public MutableBlockPos(){super(0,0,0);}public MutableBlockPos func_181079_c(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}}
    public static final class Vec3 {
        public final double field_72450_a,field_72448_b,field_72449_c;
        public Vec3(double x,double y,double z){field_72450_a=x==0?0:x;field_72448_b=y==0?0:y;field_72449_c=z==0?0:z;}
        public double func_72433_c(){return MathHelper.func_76133_a(field_72450_a*field_72450_a+field_72448_b*field_72448_b+field_72449_c*field_72449_c);}
        public Vec3 func_72432_b(){double n=func_72433_c();return n<1.0E-4?new Vec3(0,0,0):new Vec3(field_72450_a/n,field_72448_b/n,field_72449_c/n);}
    }
    public static final class AxisAlignedBB {
        public final double field_72340_a,field_72338_b,field_72339_c,field_72336_d,field_72337_e,field_72334_f;
        public AxisAlignedBB(double x1,double y1,double z1,double x2,double y2,double z2){field_72340_a=Math.min(x1,x2);field_72338_b=Math.min(y1,y2);field_72339_c=Math.min(z1,z2);field_72336_d=Math.max(x1,x2);field_72337_e=Math.max(y1,y2);field_72334_f=Math.max(z1,z2);}
        public AxisAlignedBB func_72317_d(double x,double y,double z){return new AxisAlignedBB(field_72340_a+x,field_72338_b+y,field_72339_c+z,field_72336_d+x,field_72337_e+y,field_72334_f+z);}
        public AxisAlignedBB func_72314_b(double x,double y,double z){return new AxisAlignedBB(field_72340_a-x,field_72338_b-y,field_72339_c-z,field_72336_d+x,field_72337_e+y,field_72334_f+z);}
        public AxisAlignedBB func_72331_e(double x,double y,double z){return new AxisAlignedBB(field_72340_a+x,field_72338_b+y,field_72339_c+z,field_72336_d-x,field_72337_e-y,field_72334_f-z);}
        public AxisAlignedBB func_72321_a(double x,double y,double z){return new AxisAlignedBB(field_72340_a+Math.min(0,x),field_72338_b+Math.min(0,y),field_72339_c+Math.min(0,z),field_72336_d+Math.max(0,x),field_72337_e+Math.max(0,y),field_72334_f+Math.max(0,z));}
        public boolean func_72326_a(AxisAlignedBB b){return b.field_72336_d>field_72340_a&&b.field_72340_a<field_72336_d&&b.field_72337_e>field_72338_b&&b.field_72338_b<field_72337_e&&b.field_72334_f>field_72339_c&&b.field_72339_c<field_72334_f;}
        public double func_72316_a(AxisAlignedBB b,double d){if(b.field_72337_e>field_72338_b&&b.field_72338_b<field_72337_e&&b.field_72334_f>field_72339_c&&b.field_72339_c<field_72334_f){if(d>0&&b.field_72336_d<=field_72340_a){double gap=field_72340_a-b.field_72336_d;if(gap<d)d=gap;}else if(d<0&&b.field_72340_a>=field_72336_d){double gap=field_72336_d-b.field_72340_a;if(gap>d)d=gap;}}return d;}
        public double func_72323_b(AxisAlignedBB b,double d){if(b.field_72336_d>field_72340_a&&b.field_72340_a<field_72336_d&&b.field_72334_f>field_72339_c&&b.field_72339_c<field_72334_f){if(d>0&&b.field_72337_e<=field_72338_b){double gap=field_72338_b-b.field_72337_e;if(gap<d)d=gap;}else if(d<0&&b.field_72338_b>=field_72337_e){double gap=field_72337_e-b.field_72338_b;if(gap>d)d=gap;}}return d;}
        public double func_72322_c(AxisAlignedBB b,double d){if(b.field_72336_d>field_72340_a&&b.field_72340_a<field_72336_d&&b.field_72337_e>field_72338_b&&b.field_72338_b<field_72337_e){if(d>0&&b.field_72334_f<=field_72339_c){double gap=field_72339_c-b.field_72334_f;if(gap<d)d=gap;}else if(d<0&&b.field_72339_c>=field_72334_f){double gap=field_72334_f-b.field_72339_c;if(gap>d)d=gap;}}return d;}
    }
    public static final class MathHelper {
        private static final float[] SIN=new float[65536];static{for(int i=0;i<SIN.length;i++)SIN[i]=(float)Math.sin(i*Math.PI*2.0/65536.0);}
        public static int func_76128_c(double n){int i=(int)n;return n<i?i-1:i;}
        public static double func_151237_a(double n,double min,double max){return n<min?min:n>max?max:n;}
        public static float func_76129_c(float n){return (float)Math.sqrt(n);}
        public static float func_76133_a(double n){return (float)Math.sqrt(n);}
        public static float func_76126_a(float n){return SIN[(int)(n*10430.378F)&65535];}
        public static float func_76134_b(float n){return SIN[(int)(n*10430.378F+16384.0F)&65535];}
    }
    public static final class Pair<L,R> {final L left;final R right;private Pair(L l,R r){left=l;right=r;}public static <L,R> Pair<L,R> of(L l,R r){return new Pair<L,R>(l,r);}public L getLeft(){return left;}public R getRight(){return right;}}
}
