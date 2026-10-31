package tritium.minecraft.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional validation against locally supplied 1.8.9 MCP/SRG jars, without starting a game. */
@EnabledIfSystemProperty(named="minecraft.mappingClasspathFile",matches=".+")
class CombatMappingsTest {
    @Test void transformsActualMinecraftClassFilesAlongsideHudHooks() throws Exception {
        List<URL> urls=new ArrayList<URL>();
        for(String path:Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile")))) if(!path.trim().isEmpty()) urls.add(Paths.get(path).toUri().toURL());
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),getClass().getClassLoader())) {
            HudTransformer hud=new HudTransformer(loader); CombatTransformer combat=new CombatTransformer(loader); MovementTransformer movement=new MovementTransformer(loader); OpenB4Transformer openB4=new OpenB4Transformer(loader);
            Set<String> names=new LinkedHashSet<String>(Arrays.asList(HudTransformer.TARGETS));names.addAll(Arrays.asList(CombatTransformer.TARGETS));names.addAll(Arrays.asList(MovementTransformer.TARGETS));names.addAll(Arrays.asList(OpenB4Transformer.TARGETS));
            for(String name:names) {
                String internal=name.replace('.','/');byte[] bytes;
                try(InputStream in=loader.getResourceAsStream(internal+".class")) {
                    assertNotNull(in,name); ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                    while((count=in.read(buffer))!=-1)out.write(buffer,0,count);bytes=out.toByteArray();
                }
                byte[] rendered=hud.transform(loader,internal,null,null,bytes); if(rendered!=null)bytes=rendered;
                byte[] transformed=combat.transform(loader,internal,null,null,bytes);
                if(Arrays.asList(CombatTransformer.TARGETS).contains(name))assertNotNull(transformed,combat.error());
                if(transformed!=null)bytes=transformed;
                byte[] moved=movement.transform(loader,internal,null,null,bytes);
                if(Arrays.asList(MovementTransformer.TARGETS).contains(name))assertNotNull(moved,movement.error());
                if(moved!=null)bytes=moved;
                byte[] extended=openB4.transform(loader,internal,null,null,bytes);
                if(Arrays.asList(OpenB4Transformer.TARGETS).contains(name))assertNotNull(extended,openB4.error());
                if(extended!=null)bytes=extended;
                ClassNode original=new ClassNode();
                try(InputStream in=loader.getResourceAsStream(internal+".class")){new ClassReader(in).accept(original,0);}
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
                assertEquals(original.fields.size(),node.fields.size());assertEquals(original.methods.size(),node.methods.size());
                verifyRavenHooks(node);
                for (MethodNode method : node.methods) {
                    boolean hooked = false;
                    for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode && ((MethodInsnNode)insn).owner.equals("tritium/minecraft/bridge/HookBridge")) hooked = true;
                    if (hooked) new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                }
            }
            assertTrue(hud.complete(),hud.error());assertTrue(combat.complete(),combat.error());assertTrue(movement.complete(),movement.error());assertTrue(openB4.complete(),openB4.error());
        }
    }
    private static void verifyRavenHooks(ClassNode node) {
        if (node.name.equals("net/minecraft/client/Minecraft")) {
            MethodNode tick = method(node, "runTick", "func_71407_l", "()V");
            MethodInsnNode rotate = singleHook(tick, "clientRotation"), scroll = singleHook(tick, "slotScroll");
            assertTrue(rotate.getNext() instanceof IntInsnNode);
            assertEquals(7, ((IntInsnNode) rotate.getNext()).operand, "Client rotation must run before the crosshair override decision");
            assertEquals(Opcodes.DUP2, scroll.getPrevious().getOpcode(), "Only the receiver and delta of the actual scroll invocation are intercepted");
            singleHook(method(node, "rightClickMouse", "func_147121_ag", "()V"), "rightClick");
        } else if (node.name.equals("net/minecraft/util/MovementInputFromOptions")) {
            MethodInsnNode hook = singleHook(method(node, "updatePlayerMoveState", "func_78898_a", "()V"), "preInput");
            AbstractInsnNode assignment = hook.getPrevious().getPrevious();
            assertEquals(Opcodes.PUTFIELD, assignment.getOpcode());
            assertTrue(Arrays.asList("sneak", "field_78899_d").contains(((FieldInsnNode) assignment).name));
            assertEquals(Opcodes.ALOAD, hook.getPrevious().getOpcode());
            assertEquals(0, ((VarInsnNode) hook.getPrevious()).var);
        } else if (node.name.equals("net/minecraft/client/network/NetHandlerPlayClient")) {
            MethodInsnNode hook = singleHook(method(node, "handleChat", "func_147251_a", "(Lnet/minecraft/network/play/server/S02PacketChat;)V"), "chat");
            assertTrue(hook.getNext() instanceof MethodInsnNode);
            assertTrue(Arrays.asList("printChatMessage", "func_146227_a").contains(((MethodInsnNode) hook.getNext()).name));
            assertEquals("()B", ((MethodInsnNode) hook.getPrevious()).desc, "Forward the original packet's chat type");
        } else if (node.name.equals("net/minecraft/client/renderer/EntityRenderer")) {
            MethodNode mouse = method(node, "getMouseOver", "func_78473_a", "(F)V");
            singleHook(mouse, "beforeMouseOver");
            int returns = 0, cleanup = 0;
            for (AbstractInsnNode insn : mouse.instructions) {
                if (insn.getOpcode() == Opcodes.RETURN) { returns++; assertEquals("afterMouseOver", ((MethodInsnNode) insn.getPrevious()).name); }
                if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("afterMouseOver")) cleanup++;
            }
            assertEquals(returns + 1, cleanup, "Every normal exit plus the Throwable handler restores rotations");
            assertNull(mouse.tryCatchBlocks.get(mouse.tryCatchBlocks.size() - 1).type);
        }
    }
    private static MethodNode method(ClassNode node, String mcp, String srg, String desc) {
        for (MethodNode method : node.methods) if ((method.name.equals(mcp) || method.name.equals(srg)) && method.desc.equals(desc)) return method;
        throw new AssertionError("Missing method " + node.name + "." + mcp);
    }
    private static MethodInsnNode singleHook(MethodNode method, String name) {
        MethodInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.owner.equals("tritium/minecraft/bridge/HookBridge") && call.name.equals(name)) { assertNull(found, "Duplicate " + name); found = call; }
        }
        assertNotNull(found, "Missing " + name + " in " + method.name); return found;
    }
}
