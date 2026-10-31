package tritium.minecraft.runtime.render;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import tritium.minecraft.runtime.util.render.RenderMappings;
import static org.junit.jupiter.api.Assertions.*;
/** Checks the actual reflection contracts against separately supplied MCP or SRG Minecraft jars. */
@EnabledIfSystemProperty(named="minecraft.mappingClasspathFile",matches=".+")
class RenderMappingsTest {
 @Test void renderingAndProjectileContractsExistInRealMinecraft()throws Exception{
  List<URL> urls=new ArrayList<URL>();for(String p:Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile"))))if(!p.trim().isEmpty())urls.add(Paths.get(p).toUri().toURL());
  try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),getClass().getClassLoader())){
   String[] methods={
    "client/Minecraft|getRenderManager|()Lnet/minecraft/client/renderer/entity/RenderManager;",
    "client/Minecraft|getFramebuffer|()Lnet/minecraft/client/shader/Framebuffer;",
    "client/Minecraft|getRenderItem|()Lnet/minecraft/client/renderer/entity/RenderItem;",
    "client/gui/FontRenderer|getColorCode|(C)I",
    "client/gui/FontRenderer|drawString|(Ljava/lang/String;FFIZ)I",
    "client/gui/FontRenderer|getFormatFromString|(Ljava/lang/String;)Ljava/lang/String;",
    "client/renderer/entity/RenderManager|isRenderShadow|()Z",
    "client/renderer/entity/RenderManager|setRenderShadow|(Z)V",
    "client/renderer/entity/RenderManager|renderEntityStatic|(Lnet/minecraft/entity/Entity;FZ)Z",
    "client/renderer/entity/RenderItem|getItemModelMesher|()Lnet/minecraft/client/renderer/ItemModelMesher;",
    "client/renderer/entity/RenderItem|renderItem|(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resources/model/IBakedModel;)V",
    "client/renderer/ItemModelMesher|getItemModel|(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/client/resources/model/IBakedModel;",
    "client/renderer/block/model/ItemCameraTransforms|applyTransform|(Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
    "client/renderer/entity/RenderItem|renderItemIntoGUI|(Lnet/minecraft/item/ItemStack;II)V",
    "client/renderer/EntityRenderer|setupCameraTransform|(FI)V",
    "client/renderer/EntityRenderer|disableLightmap|()V",
    "client/renderer/ActiveRenderInfo|getRotationXY|()F",
    "client/renderer/ActiveRenderInfo|getRotationX|()F",
    "client/renderer/ActiveRenderInfo|getRotationXZ|()F",
    "client/renderer/culling/Frustum|setPosition|(DDD)V",
    "client/renderer/culling/Frustum|isBoundingBoxInFrustum|(Lnet/minecraft/util/AxisAlignedBB;)Z",
    "client/renderer/culling/Frustum|isBoxInFrustum|(DDDDDD)Z",
    "client/shader/Framebuffer|framebufferClear|()V",
    "client/shader/Framebuffer|bindFramebuffer|(Z)V",
    "client/shader/Framebuffer|setFramebufferFilter|(I)V",
    "client/shader/Framebuffer|deleteFramebuffer|()V",
    "network/play/server/S1DPacketEntityEffect|getEntityId|()I",
    "network/play/server/S1DPacketEntityEffect|getEffectId|()B",
    "network/play/server/S1DPacketEntityEffect|getAmplifier|()B",
    "network/play/server/S1DPacketEntityEffect|getDuration|()I",
    "network/play/client/C08PacketPlayerBlockPlacement|getStack|()Lnet/minecraft/item/ItemStack;",
    "entity/EntityLivingBase|getActivePotionEffect|(Lnet/minecraft/potion/Potion;)Lnet/minecraft/potion/PotionEffect;",
    "entity/EntityLivingBase|removePotionEffectClient|(I)V",
    "entity/EntityLivingBase|getAbsorptionAmount|()F",
    "item/ItemStack|getAttributeModifiers|()Lcom/google/common/collect/Multimap;",
    "enchantment/EnchantmentHelper|getEnchantmentLevel|(ILnet/minecraft/item/ItemStack;)I",
    "world/World|rayTraceBlocks|(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;ZZZ)Lnet/minecraft/util/MovingObjectPosition;",
    "world/World|getEntitiesWithinAABBExcludingEntity|(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;",
    "util/AxisAlignedBB|calculateIntercept|(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;",
    "util/BlockPos|offset|(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;",
    "block/Block|damageDropped|(Lnet/minecraft/block/state/IBlockState;)I",
    "block/state/IBlockState|getValue|(Lnet/minecraft/block/properties/IProperty;)Ljava/lang/Comparable;"
   };
   String[] fields={"client/shader/Framebuffer|framebufferWidth|I","client/shader/Framebuffer|framebufferHeight|I","client/shader/Framebuffer|framebufferTexture|I","entity/projectile/EntityArrow|inGround|Z","block/BlockBed|PART|Lnet/minecraft/block/properties/PropertyEnum;","block/BlockDirectional|FACING|Lnet/minecraft/block/properties/PropertyDirection;","client/renderer/entity/RenderManager|viewerPosX|D","client/renderer/entity/RenderManager|playerViewY|F"};
   for(String spec:methods)check(loader,spec,false);for(String spec:fields)check(loader,spec,true);
  }
 }
 private void check(ClassLoader loader,String spec,boolean field)throws Exception{String[] p=spec.split("\\|");Set<String> aliases=new HashSet<String>(Arrays.asList(RenderMappings.name(p[1]).split("/")));assertTrue(exists(loader,"net/minecraft/"+p[0],aliases,p[2],field,new HashSet<String>()),spec+" aliases "+aliases);}
 private boolean exists(ClassLoader loader,String name,Set<String> aliases,String desc,boolean field,Set<String> visited)throws Exception{if(name==null||!visited.add(name))return false;try(InputStream in=loader.getResourceAsStream(name+".class")){if(in==null)return false;ClassNode node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_CODE);if(field){for(FieldNode f:node.fields)if(aliases.contains(f.name)&&f.desc.equals(desc))return true;}else for(MethodNode m:node.methods)if(aliases.contains(m.name)&&m.desc.equals(desc))return true;if(exists(loader,node.superName,aliases,desc,field,visited))return true;for(String parent:node.interfaces)if(exists(loader,parent,aliases,desc,field,visited))return true;return false;}}
}
