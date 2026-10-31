package tritium.minecraft.runtime.player;

import java.util.*;
import org.junit.jupiter.api.Test;
import tritium.minecraft.runtime.hook.PlayerAccess;
import tritium.minecraft.runtime.player.simulation.SimulatedPlayer;
import tritium.minecraft.runtime.player.simulation.SimulationWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the complete multi-tick simulator through its reflection boundary, with a deterministic voxel world. */
class SimulationPhysicsTest {
    @Test void fullTickAppliesInputFrictionAndGravityWithoutMutatingLivePlayerOrInput(){
        WorldFake g=new WorldFake();g.input.put("moveForward",1f);SimulatedPlayer sim=g.sim();sim.movementInput.field_78899_d=false;sim.tick();
        assertEquals(.59800001,sim.getPos().field_72449_c,1e-7);assertTrue(sim.onGround);assertEquals(-.0784000015,sim.motionY,1e-8);
        assertEquals(.5,g.player.get("posZ"));assertEquals(-.08,g.player.get("motionY"));assertEquals(1f,g.input.get("moveForward"));
        for(int i=0;i<10;i++)sim.tick();assertTrue(sim.getPos().field_72449_c>2);assertTrue(sim.onGround);assertEquals(64,sim.box.field_72338_b);
    }
    @Test void jumpingFollowsBallisticArcAndLandsAfterMultipleTicks(){
        WorldFake g=new WorldFake();g.input.put("jump",true);SimulatedPlayer sim=g.sim();sim.tick();assertEquals(64+(double).42f,sim.box.field_72338_b,1e-8);assertFalse(sim.onGround);
        sim.movementInput.field_78901_c=false;double peak=sim.box.field_72338_b;for(int i=0;i<20;i++){sim.tick();peak=Math.max(peak,sim.box.field_72338_b);}assertTrue(peak>65.2&&peak<65.3);assertTrue(sim.onGround);assertEquals(64,sim.box.field_72338_b);
    }
    @Test void collisionResolvesAllAxesAndChoosesTheFullStepUpPath(){
        WorldFake g=new WorldFake();g.block(1,64,0,"stone",1);SimulatedPlayer sim=g.sim();sim.moveEntity(1,-.08,0);assertEquals(.7,sim.getPos().field_72450_a,1e-7);assertTrue(sim.isCollidedHorizontally);
        g.block(1,64,0,"stone",.5);sim=g.sim();sim.moveEntity(1,-.08,0);assertEquals(1.5,sim.getPos().field_72450_a,1e-7);assertEquals(64.5,sim.box.field_72338_b,1e-7);assertFalse(sim.isCollidedHorizontally);
    }
    @Test void worldBorderCollisionUsesTheSimulatedPositionAndOutsideBorderHysteresis(){
        WorldFake g=new WorldFake();g.border.put("maxX",1d);SimulatedPlayer sim=g.sim();sim.moveEntity(1,-.08,0);assertEquals(.7,sim.getPos().field_72450_a,1e-7);assertTrue(sim.isCollidedHorizontally);
    }
    @Test void liquidCurrentAndDepthStriderAreAppliedBeforeWaterDrag(){
        WorldFake g=new WorldFake();g.block(0,64,0,"water",0);g.current=new Vec3(1,0,0);g.input.put("moveForward",1f);SimulatedPlayer sim=g.sim();sim.tick();assertTrue(sim.isInWater());assertTrue(sim.motionX>0);assertEquals(.5+.0196,sim.getPos().field_72449_c,1e-7);assertEquals(-.02,sim.motionY,1e-8);
        g.depthStrider=3;SimulatedPlayer fast=g.sim();fast.tick();assertTrue(fast.getPos().field_72449_c>sim.getPos().field_72449_c);
    }
    @Test void websSlowTheFollowingMovementAndSoulSandChangesHorizontalMomentum(){
        WorldFake g=new WorldFake();g.block(0,64,0,"web",0);SimulatedPlayer sim=g.sim();sim.doBlockCollisions();sim.motionX=.4;sim.motionY=-.2;sim.moveEntity(.4,-.2,0);assertEquals(.6,sim.getPos().field_72450_a,1e-8);assertEquals(0,sim.motionX);
        g.block(0,64,0,"soul_sand",0);sim=g.sim();sim.motionX=.5;sim.motionZ=.25;sim.doBlockCollisions();assertEquals(.2,sim.motionX,1e-8);assertEquals(.1,sim.motionZ,1e-8);
    }
    @Test void slimeBounceAndLadderClampingRetainOriginalSneakDifferences(){
        WorldFake g=new WorldFake();g.block(0,63,0,"slime",1);SimulatedPlayer sim=g.sim();sim.motionY=-.3;sim.moveEntity(0,-.3,0);assertEquals(.3,sim.motionY,1e-8);
        sim=g.sim();sim.movementInput.field_78899_d=true;sim.motionY=-.3;sim.moveEntity(0,-.3,0);assertEquals(0,sim.motionY);
        g.block(0,64,0,"ladder",0);g.player.put("motionY",-.4);g.player.put("motionX",.4);sim=g.sim();sim.tick();assertEquals(.65,sim.getPos().field_72450_a,1e-7);assertTrue(sim.isOnLadder());
    }
    @Test void blockOverridesAndEntityCollisionStayLocalToTheSimulation(){
        WorldFake g=new WorldFake();SimulatedPlayer sim=g.sim();Object raw=g.blocks.get("ladder");g.terrain.put(new BlockPos(1,64,0),(Node)raw);
        // A virtual ladder is solid only in the simulation's override map, exactly as in RavenBs.
        sim.setBlockState(new BlockPos(1,64,0),sim.getBlockState(new BlockPos(1,64,0)));g.terrain.remove(new BlockPos(1,64,0));
        sim.moveEntity(1,-.08,0);assertEquals(.7,sim.getPos().field_72450_a,1e-7);assertTrue(g.terrain.isEmpty());
        sim.resetSimulationState(.5,64,.5,true);assertEquals(0,sim.motionX);assertFalse(sim.movementInput.field_78899_d);
        g.entities.add(g.entity("entity.item.EntityBoat",new AxisAlignedBB(1,64,0,2,65,1)));sim=g.sim();sim.moveEntity(1,-.08,0);assertEquals(.7,sim.getPos().field_72450_a,1e-7);
    }
    static final class Node extends HashMap<String,Object>{final String kind;Node(String kind){this.kind=kind;}Node value(String n,Object v){put(n,v);return this;}}
    static final class WorldFake extends PlayerAccess {
        final Node player=new Node("client.entity.EntityPlayerSP"),world=new Node("world.World"),input=new Node("util.MovementInput"),caps=new Node("entity.player.PlayerCapabilities"),food=new Node("util.FoodStats"),controller=new Node("client.multiplayer.PlayerControllerMP"),border=new Node("world.border.WorldBorder"),chunks=new Node("world.chunk.IChunkProvider"),chunk=new Node("world.chunk.Chunk"),attributeMap=new Node("entity.ai.attributes.BaseAttributeMap"),attribute=new Node("entity.ai.attributes.IAttributeInstance");
        final Map<BlockPos,Node> terrain=new HashMap<BlockPos,Node>();final Map<String,Node> materials=new HashMap<String,Node>(),blocks=new HashMap<String,Node>();final List<Node> entities=new ArrayList<Node>();final Object ladderFacing=new Object(),level=new Object();Vec3 current=new Vec3(0,0,0);int depthStrider;
        WorldFake(){super(null);
            for(String n:new String[]{"air","water","lava","rock"})materials.put(n,new Node("block.material.Material"));
            for(String n:new String[]{"air","stone","ladder","vine","water","slime","web","soul_sand"})blocks.put(n,makeBlock(n,n.equals("stone")||n.equals("slime")?1:0));
            for(String n:new String[]{"motionX","motionZ"})player.put(n,0d);player.put("motionY",-.08);player.put("posX",.5);player.put("posY",64d);player.put("posZ",.5);
            for(String n:new String[]{"onGround"})player.put(n,true);for(String n:new String[]{"isAirBorne","isCollidedHorizontally","isCollidedVertically","isCollided","noClip","isInWeb","inWater","sprinting","spectator","riding","usingItem","sleeping","outsideBorder"})player.put(n,false);
            for(String n:new String[]{"rotationYaw","rotationPitch","fallDistance","distanceWalkedModified","distanceWalkedOnStepModified"})player.put(n,0f);player.put("jumpMovementFactor",.02f);player.put("stepHeight",.6f);player.put("height",1.8f);player.put("width",.6f);player.put("health",20f);
            for(String n:new String[]{"fireResistance","jumpTicks","fire","nextStepDistance"})player.put(n,0);player.put("capabilities",caps);player.put("box",new AxisAlignedBB(.2,64,.2,.8,65.8,.8));player.put("ridingEntity",null);player.put("riddenByEntity",null);
            caps.put("isFlying",false);caps.put("allowFlying",false);caps.put("flySpeed",.05f);food.put("foodLevel",20);input.put("moveForward",0f);input.put("moveStrafe",0f);input.put("jump",false);input.put("sneak",false);
            world.put("isRemote",true);border.put("minX",-3e7);border.put("minZ",-3e7);border.put("maxX",3e7);border.put("maxZ",3e7);attribute.put("speed",.1);
        }
        SimulatedPlayer sim(){return SimulatedPlayer.fromClientPlayer(this,input);}void block(int x,int y,int z,String kind,double h){terrain.put(new BlockPos(x,y,z),kind.equals("ladder")?blocks.get("ladder"):makeBlock(kind,h));}
        Node makeBlock(String name,double height){String type=name.equals("slime")?"BlockSlime":name.equals("web")?"BlockWeb":name.equals("soul_sand")?"BlockSoulSand":"Block";return new Node("block."+type).value("name",name).value("height",height).value("slipperiness",name.equals("slime")?.8f:.6f).value("material",materials.get(name.equals("air")?"air":name.equals("water")?"water":"rock"));}
        Node entity(String kind,AxisAlignedBB box){return new Node(kind).value("box",box);}
        public Object player(){return player;}public Object world(){return world;}public Object controller(){return controller;}public Object input(){return input;}
        public Class<?> minecraftType(String n){if(n.equals("init.Blocks"))return BlocksMarker.class;if(n.equals("block.material.Material"))return MaterialMarker.class;if(n.equals("potion.Potion"))return PotionMarker.class;if(n.equals("entity.SharedMonsterAttributes"))return AttributeMarker.class;if(n.equals("util.EntitySelectors"))return SelectorMarker.class;if(n.equals("block.BlockLadder"))return LadderMarker.class;if(n.equals("block.BlockLiquid"))return LiquidMarker.class;if(n.equals("enchantment.EnchantmentHelper"))return EnchantMarker.class;if(n.equals("enchantment.EnchantmentProtection"))return ProtectionMarker.class;throw new AssertionError(n);}
        public boolean minecraftIs(Object o,String name){return o instanceof Node&&((Node)o).kind.equals(name);}
        public Object minecraftRead(Object o,String names){String n=names.split("/")[0];
            if(o==BlocksMarker.class)return blocks.get(n);if(o==MaterialMarker.class)return materials.get(n);if(o==PotionMarker.class||o==AttributeMarker.class)return new Node(n);if(o==SelectorMarker.class)return this;if(o==LadderMarker.class)return ladderFacing;if(o==LiquidMarker.class)return level;
            if(o instanceof Node){Node node=(Node)o;if(!node.containsKey(n))throw new AssertionError(node.kind+"."+n);return node.get(n);}
            if(o instanceof AxisAlignedBB){AxisAlignedBB b=(AxisAlignedBB)o;switch(n){case "minX":return b.field_72340_a;case "minY":return b.field_72338_b;case "minZ":return b.field_72339_c;case "maxX":return b.field_72336_d;case "maxY":return b.field_72337_e;case "maxZ":return b.field_72334_f;}}
            if(o instanceof Vec3){Vec3 v=(Vec3)o;if(n.equals("xCoord"))return v.field_72450_a;if(n.equals("yCoord"))return v.field_72448_b;if(n.equals("zCoord"))return v.field_72449_c;}throw new AssertionError(n);
        }
        public Object minecraftNew(String name,Object... args){if(name.equals("util.AxisAlignedBB"))return new AxisAlignedBB(d(args[0]),d(args[1]),d(args[2]),d(args[3]),d(args[4]),d(args[5]));if(name.equals("entity.player.PlayerCapabilities"))return new Node(name).value("isFlying",false).value("allowFlying",false).value("flySpeed",.05f);return new Node(name);}
        public Object pos(int x,int y,int z){return new BlockPos(x,y,z);}public Object vector(double x,double y,double z){return new Vec3(x,y,z);}public Object facing(String name){return EnumFacing.valueOf(name);}
        @SuppressWarnings("unchecked") public Object minecraftCall(Object o,String names,Object... a){String n=names.split("/")[0];
            if(o==EnchantMarker.class)return depthStrider;if(o==ProtectionMarker.class)return a[1];
            if(o instanceof BlockPos){BlockPos p=(BlockPos)o;if(n.equals("getX"))return p.func_177958_n();if(n.equals("getY"))return p.func_177956_o();if(n.equals("getZ"))return p.func_177952_p();}
            Node node=(Node)o;
            switch(n){
                case "getEntityBoundingBox":case "getCollisionBoundingBox":return node.get("box");case "getCollisionBox":return ((Node)a[0]).get("box");
                case "isInWater":return node.get("inWater");case "isOutsideBorder":return node.get("outsideBorder");case "getAttributeMap":return attributeMap;case "isSpectator":return node.get("spectator");case "isSprinting":return node.get("sprinting");case "getFoodStats":return food;case "isRiding":return node.get("riding");case "isUsingItem":return node.get("usingItem");case "getActivePotionEffect":return null;case "getHealth":return node.get("health");case "isPlayerSleeping":return node.get("sleeping");
                case "writeNBT":case "writeCapabilitiesToNBT":((Node)a[0]).putAll(node);return null;case "readNBT":case "readCapabilitiesFromNBT":node.putAll((Node)a[0]);return null;case "getFoodLevel":return node.get("foodLevel");case "getFlySpeed":return node.get("flySpeed");case "isSpectatorMode":return false;
                case "getWorldBorder":return border;case "getChunkProvider":return chunks;case "isBlockLoaded":return true;case "getChunkFromBlockCoords":case "provideChunk":return chunk;
                case "isFlammableWithin":case "isMaterialInBB":case "isAnyLiquid":return false;case "getBlockState":return state((BlockPos)a[0]);case "getRainStrength":return 0f;
                case "chunkExists":case "isLoaded":return true;case "isEmpty":return false;case "getEntitiesWithinAABBForEntity":for(Node entity:entities)if(((AxisAlignedBB)entity.get("box")).func_72326_a((AxisAlignedBB)a[1]))((List<Object>)a[2]).add(entity);return null;
                case "minX":case "minZ":case "maxX":case "maxZ":return node.get(n);
                case "contains":BlockPos p=(BlockPos)a[0];return p.func_177958_n()+1>d(node.get("minX"))&&p.func_177958_n()<d(node.get("maxX"))&&p.func_177952_p()+1>d(node.get("minZ"))&&p.func_177952_p()<d(node.get("maxZ"));
                case "getBlock":case "getDefaultState":return node;case "getValue":return a[0]==level?0:EnumFacing.EAST;case "getMaterial":return node.get("material");case "isNormalCube":return d(node.get("height"))>=1;case "modifyAcceleration":return current;
                case "addCollisionBoxesToList":BlockPos bp=(BlockPos)a[1];double height=d(node.get("height"));if(height>0){AxisAlignedBB b=new AxisAlignedBB(bp.func_177958_n(),bp.func_177956_o(),bp.func_177952_p(),bp.func_177958_n()+1,bp.func_177956_o()+height,bp.func_177952_p()+1);if(b.func_72326_a((AxisAlignedBB)a[3]))((List<Object>)a[4]).add(b);}return null;
                case "getAttributeInstance":return attribute;case "getAttributeValue":return node.get("speed");
            }
            throw new AssertionError(node.kind+"."+n);
        }
        Node state(BlockPos p){Node b=terrain.get(p);return b!=null?b:blocks.get(p.func_177956_o()<64?"stone":"air");}
        static double d(Object n){return ((Number)n).doubleValue();}
    }
    static class BlocksMarker{}static class MaterialMarker{}static class PotionMarker{}static class AttributeMarker{}static class SelectorMarker{}static class LadderMarker{}static class LiquidMarker{}static class EnchantMarker{}static class ProtectionMarker{}
}
