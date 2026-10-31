package tritium.minecraft.runtime.player;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import tritium.minecraft.runtime.util.PlayerMappings;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the player adapter against actual MCP or SRG class files without loading Minecraft. */
@EnabledIfSystemProperty(named="minecraft.mappingClasspathFile",matches=".+")
class PlayerMappingsTest {
    @Test void completePlayerReflectionSurfaceExists() throws Exception {
        List<URL> urls=new ArrayList<URL>();for(String p:Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile"))))if(!p.trim().isEmpty())urls.add(Paths.get(p).toUri().toURL());
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),null)) {
            check(loader,"client/Minecraft",true,"thePlayer theWorld currentScreen inGameHasFocus playerController objectMouseOver gameSettings fontRendererObj leftClickCounter rightClickDelayTimer");
            check(loader,"client/Minecraft",false,"isGamePaused getItemRenderer getNetHandler");
            check(loader,"client/entity/EntityPlayerSP",true,"movementInput inventory capabilities onGround fallDistance rotationYaw rotationPitch ticksExisted posX posY posZ motionX motionY motionZ jumpTicks");
            check(loader,"client/entity/EntityPlayerSP",false,"getHeldItem isSneaking isSprinting isOnLadder isInWater isInLava getEyeHeight getEntityBoundingBox getAIMoveSpeed getHorizontalFacing getActivePotionEffect getPosition isInsideOfMaterial swingItem");
            check(loader,"entity/player/PlayerCapabilities",true,"isCreativeMode isFlying allowEdit");
            check(loader,"util/MovementInput",true,"moveForward moveStrafe jump sneak");
            check(loader,"entity/player/InventoryPlayer",true,"currentItem");check(loader,"entity/player/InventoryPlayer",false,"getStackInSlot setInventorySlotContents");
            check(loader,"client/settings/GameSettings",true,"keyBindJump keyBindSneak keyBindForward keyBindBack keyBindLeft keyBindRight keyBindUseItem keyBindAttack mouseSensitivity showDebugInfo");
            check(loader,"client/settings/GameSettings",false,"isKeyDown");check(loader,"client/settings/KeyBinding",false,"isKeyDown getKeyCode setKeyBindState");check(loader,"client/settings/KeyBinding",true,"pressTime");
            check(loader,"item/ItemStack",false,"getItem getStrVsBlock canHarvestBlock");check(loader,"item/ItemStack",true,"stackSize");check(loader,"item/ItemBlock",false,"getBlock");check(loader,"item/Item",false,"getStrVsBlock");
            check(loader,"init/Blocks",true,"ladder glass gravel sand soul_sand tnt crafting_table furnace dispenser dropper noteblock command_block pumpkin anvil bed lever iron_door carpet waterlily air fire");
            check(loader,"init/Items",true,"shears bucket water_bucket");
            check(loader,"block/Block",false,"isFullBlock getMaterial getBlockBoundsMaxY getBlockHardness");check(loader,"block/material/Material",true,"water");check(loader,"block/material/Material",false,"isReplaceable isSolid isToolNotRequired");
            check(loader,"client/multiplayer/WorldClient",false,"getCollidingBoundingBoxes getBlockState isAirBlock rayTraceBlocks");check(loader,"util/AxisAlignedBB",false,"offset");check(loader,"util/AxisAlignedBB",true,"minY");
            check(loader,"util/MovingObjectPosition",true,"typeOfHit sideHit hitVec");check(loader,"util/MovingObjectPosition",false,"getBlockPos");check(loader,"util/Vec3",true,"xCoord yCoord zCoord");
            check(loader,"util/BlockPos",false,"getX getY getZ up down offset");check(loader,"util/EnumFacing",false,"getOpposite rotateY rotateYCCW getDirectionVec");
            check(loader,"block/state/IBlockState",false,"getBlock getValue");check(loader,"block/BlockSlab",true,"HALF");
            check(loader,"client/multiplayer/PlayerControllerMP",false,"getBlockReachDistance onPlayerRightClick sendUseItem syncCurrentPlayItem");check(loader,"client/multiplayer/PlayerControllerMP",true,"blockHitDelay curBlockDamageMP");
            check(loader,"client/renderer/ItemRenderer",false,"resetEquippedProgress");check(loader,"potion/Potion",true,"jump digSpeed digSlowdown");check(loader,"potion/PotionEffect",false,"getAmplifier");
            check(loader,"enchantment/Enchantment",true,"efficiency effectId");check(loader,"enchantment/EnchantmentHelper",false,"getEnchantmentLevel getAquaAffinityModifier");
            check(loader,"network/play/client/C08PacketPlayerBlockPlacement",false,"getPlacedBlockDirection getStack");check(loader,"client/network/NetHandlerPlayClient",false,"addToSendQueue");
            check(loader,"client/gui/ScaledResolution",false,"getScaledWidth getScaledHeight");check(loader,"client/gui/FontRenderer",false,"drawString");check(loader,"util/MathHelper",false,"sin cos sqrt_float");
        }
    }
    private static void check(ClassLoader loader,String owner,boolean field,String names)throws Exception{for(String name:names.split(" "))assertTrue(contains(loader,"net/minecraft/"+owner,field,new HashSet<String>(Arrays.asList(PlayerMappings.name(name).split("/"))),new HashSet<String>()),owner+"."+name+" -> "+PlayerMappings.name(name));}
    private static boolean contains(ClassLoader loader,String owner,boolean field,Set<String> names,Set<String> seen)throws Exception{
        if(owner==null||!seen.add(owner))return false;try(InputStream in=loader.getResourceAsStream(owner+".class")){if(in==null)return false;ClassNode c=new ClassNode();new ClassReader(in).accept(c,ClassReader.SKIP_CODE);
            if(field){for(FieldNode f:c.fields)if(names.contains(f.name))return true;}else{for(MethodNode m:c.methods)if(names.contains(m.name))return true;}
            if(contains(loader,c.superName,field,names,seen))return true;for(String i:c.interfaces)if(contains(loader,i,field,names,seen))return true;return false;
        }
    }
}
