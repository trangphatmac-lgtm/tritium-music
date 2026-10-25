package fixtures;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import tritium.minecraft.internal.asm.*;
import tritium.minecraft.internal.asm.tree.*;

public final class OtherAgent {
    public static void agentmain(String options, Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (!"net/minecraft/client/renderer/EntityRenderer".equals(name)) return null;
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
                for (MethodNode method : node.methods) if (method.name.equals("marker")) {
                    method.instructions.clear(); method.instructions.add(new LdcInsnNode(31)); method.instructions.add(new InsnNode(Opcodes.IRETURN));
                }
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
            }
        }, true);
        instrumentation.retransformClasses(Class.forName("net.minecraft.client.renderer.EntityRenderer"));
    }
}
