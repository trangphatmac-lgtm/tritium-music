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
            HudTransformer hud=new HudTransformer(loader); CombatTransformer combat=new CombatTransformer(loader); MovementTransformer movement=new MovementTransformer(loader);
            Set<String> names=new LinkedHashSet<String>(Arrays.asList(HudTransformer.TARGETS));names.addAll(Arrays.asList(CombatTransformer.TARGETS));
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
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
                for (MethodNode method : node.methods) {
                    boolean hooked = false;
                    for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode && ((MethodInsnNode)insn).owner.equals("tritium/minecraft/bridge/HookBridge")) hooked = true;
                    if (hooked) new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                }
            }
            assertTrue(hud.complete(),hud.error());assertTrue(combat.complete(),combat.error());assertTrue(movement.complete(),movement.error());
        }
    }
}
