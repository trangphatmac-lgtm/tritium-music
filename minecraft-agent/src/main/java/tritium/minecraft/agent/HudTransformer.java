package tritium.minecraft.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Method-body-only hooks. Names/descriptors follow InjectMyau's 1.8.9 MCP/SRG mappings. */
public final class HudTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "tritium/minecraft/bridge/HookBridge";
    public static final String[] TARGETS = {"net.minecraft.client.renderer.EntityRenderer",
            "net.minecraft.client.Minecraft"};
    private final ClassLoader loader;
    private final Set<String> applied = new HashSet<String>();
    private volatile boolean active = true;
    private volatile String error;

    public HudTransformer(ClassLoader loader) { this.loader = loader; }
    public synchronized void deactivate() { active = false; }
    public synchronized boolean complete() { return applied.size() == TARGETS.length && error == null; }
    public String error() { return error; }

    @Override
    public synchronized byte[] transform(ClassLoader definingLoader, String name, Class<?> redefined,
                                         ProtectionDomain domain, byte[] input) {
        if (!active || definingLoader != loader || name == null) return null;
        int kind = -1;
        for (int i = 0; i < TARGETS.length; i++) if (TARGETS[i].replace('.', '/').equals(name)) kind = i;
        if (kind < 0) return null;
        try {
            ClassReader reader = new ClassReader(input);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            String mcp = kind == 0 ? "updateCameraAndRender" : "runTick";
            String srg = kind == 0 ? "func_181560_a" : "func_71407_l";
            String desc = kind == 0 ? "(FJ)V" : "()V";
            int matches = 0;
            for (MethodNode method : node.methods) {
                if (!(method.name.equals(mcp) || method.name.equals(srg)) || !method.desc.equals(desc)) continue;
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0)
                    throw new IllegalStateException("Unexpected target method modifiers");
                matches++;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode && BRIDGE.equals(((MethodInsnNode) insn).owner))
                        throw new IllegalStateException("Existing Tritium hook; restart Minecraft before replacing the agent");
                }
                if (kind == 0) {
                    for (AbstractInsnNode insn : method.instructions.toArray())
                        if (insn.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(insn, callback(kind));
                } else method.instructions.insert(callback(kind));
            }
            if (matches != 1) throw new IllegalStateException("Expected one " + mcp + desc + ", found " + matches);
            if (kind == 0) hookWorld(node);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a, String b) {
                    try {
                        Class<?> ca = Class.forName(a.replace('/', '.'), false, loader);
                        Class<?> cb = Class.forName(b.replace('/', '.'), false, loader);
                        if (ca.isAssignableFrom(cb)) return a;
                        if (cb.isAssignableFrom(ca)) return b;
                        if (ca.isInterface() || cb.isInterface()) return "java/lang/Object";
                        do { ca = ca.getSuperclass(); } while (!ca.isAssignableFrom(cb));
                        return ca.getName().replace('.', '/');
                    } catch (ClassNotFoundException e) { throw new IllegalStateException("Cannot resolve stack frame types", e); }
                }
            };
            node.accept(writer);
            byte[] result = writer.toByteArray();
            applied.add(name);
            return result;
        } catch (Throwable failure) {
            error = name + ": " + failure.getMessage();
            return null;
        }
    }

    private static void hookWorld(ClassNode node) {
        int methods = 0;
        for (MethodNode method : node.methods) {
            if (!(method.name.equals("renderWorldPass") || method.name.equals("func_175068_a"))
                    || !method.desc.equals("(IFJ)V")) continue;
            methods++;
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0)
                throw new IllegalStateException("Unexpected world render method modifiers");
            AbstractInsnNode anchor = null;
            int anchors = 0;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode && BRIDGE.equals(((MethodInsnNode) insn).owner))
                    throw new IllegalStateException("Existing Tritium world hook; restart Minecraft before replacing the agent");
                if (!(insn instanceof LdcInsnNode) || !"hand".equals(((LdcInsnNode) insn).cst)) continue;
                AbstractInsnNode next = insn.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) next;
                if ((call.name.equals("endStartSection") || call.name.equals("func_76318_c"))
                        && call.desc.equals("(Ljava/lang/String;)V")) {
                    anchor = insn; anchors++;
                }
            }
            if (anchors != 1) throw new IllegalStateException("Expected one world-to-hand profiler boundary, found " + anchors);
            // The profiler's receiver is already on the operand stack. The callback consumes
            // only its own float and leaves that receiver untouched. This boundary is outside
            // renderHand's conditional, so F1/third person/hand suppression cannot skip lyrics.
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.FLOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "world", "(F)V", false));
            method.instructions.insertBefore(anchor, code);
        }
        if (methods != 1) throw new IllegalStateException("Expected one renderWorldPass(IFJ)V, found " + methods);
    }

    private static InsnList callback(int kind) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0 + kind));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "event", "(I)Z", false));
        code.add(new InsnNode(Opcodes.POP));
        return code;
    }
}
