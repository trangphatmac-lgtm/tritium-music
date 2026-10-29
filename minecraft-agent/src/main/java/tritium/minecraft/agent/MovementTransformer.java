package tritium.minecraft.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Player input/tail and game-thread incoming-packet hooks shared by movement modules. */
public final class MovementTransformer implements ClassFileTransformer {
    private static final String BRIDGE="tritium/minecraft/bridge/HookBridge";
    public static final String[] TARGETS={"net.minecraft.client.entity.EntityPlayerSP","net.minecraft.client.network.NetHandlerPlayClient"};
    private final ClassLoader loader;
    private final Set<String> applied=new HashSet<String>();
    private boolean active=true;
    private String error;
    public MovementTransformer(ClassLoader loader) { this.loader=loader; }
    public synchronized void deactivate() { active=false; }
    public synchronized boolean complete() { return applied.size()==TARGETS.length && error==null; }
    public synchronized String error() { return error; }
    @Override public synchronized byte[] transform(ClassLoader defining,String name,Class<?> redefined,ProtectionDomain domain,byte[] bytes) {
        if (!active || defining!=loader || name==null) return null;
        int kind=-1;for (int i=0;i<TARGETS.length;i++) if (TARGETS[i].replace('.','/').equals(name)) kind=i;
        if (kind<0) return null;
        try {
            ClassReader reader=new ClassReader(bytes);ClassNode node=new ClassNode();reader.accept(node,0);
            if (kind==0) {
                MethodNode update=method(node,"onUpdate","func_70071_h_","()V");
                for (AbstractInsnNode insn:update.instructions.toArray()) if (insn.getOpcode()==Opcodes.RETURN) update.instructions.insertBefore(insn,event(12));
                MethodNode living=method(node,"onLivingUpdate","func_70636_d","()V");int inputs=0;
                for (AbstractInsnNode insn:living.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if (call.owner.equals("net/minecraft/util/MovementInput") && (call.name.equals("updatePlayerMoveState") || call.name.equals("func_78898_a")) && call.desc.equals("()V")) {
                        living.instructions.insert(insn,event(13));inputs++;
                    }
                }
                if (inputs!=1) throw new IllegalStateException("Expected one movement input update, found "+inputs);
            } else {
                for (String[] spec:new String[][]{{"handleEntityVelocity","func_147244_a","S12PacketEntityVelocity"},
                        {"handlePlayerPosLook","func_147258_a","S08PacketPlayerPosLook"},{"handleExplosion","func_147283_a","S27PacketExplosion"}}) {
                    MethodNode receive=method(node,spec[0],spec[1],"(Lnet/minecraft/network/play/server/"+spec[2]+";)V");int handoffs=0;
                    for (AbstractInsnNode insn:receive.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call=(MethodInsnNode)insn;
                        if (call.owner.equals("net/minecraft/network/PacketThreadUtil") && (call.name.equals("checkThreadAndEnqueue") || call.name.equals("func_180031_a"))) {
                            InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"receive","(Ljava/lang/Object;)Z",false));
                            LabelNode next=new LabelNode();hook.add(new JumpInsnNode(Opcodes.IFEQ,next));hook.add(new InsnNode(Opcodes.RETURN));hook.add(next);
                            receive.instructions.insert(insn,hook);handoffs++;
                        }
                    }
                    if (handoffs!=1) throw new IllegalStateException("Expected one game-thread handoff in "+spec[0]+", found "+handoffs);
                }
            }
            ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a,String b) {
                    try {
                        Class<?> ca=Class.forName(a.replace('/','.'),false,loader),cb=Class.forName(b.replace('/','.'),false,loader);
                        if (ca.isAssignableFrom(cb)) return a;if (cb.isAssignableFrom(ca)) return b;
                        if (ca.isInterface() || cb.isInterface()) return "java/lang/Object";
                        do { ca=ca.getSuperclass(); } while (!ca.isAssignableFrom(cb));return ca.getName().replace('.','/');
                    } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
                }
            };
            node.accept(writer);byte[] result=writer.toByteArray();applied.add(name);return result;
        } catch (Throwable e) { error=name+": "+e.getMessage();return null; }
    }
    private static MethodNode method(ClassNode node,String mcp,String srg,String desc) {
        MethodNode found=null;
        for (MethodNode method:node.methods) if ((method.name.equals(mcp)||method.name.equals(srg)) && method.desc.equals(desc)) {
            if (found!=null || (method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0) throw new IllegalStateException("Unexpected "+mcp);
            for (AbstractInsnNode insn:method.instructions) if (insn instanceof MethodInsnNode && BRIDGE.equals(((MethodInsnNode)insn).owner)) {
                MethodInsnNode call=(MethodInsnNode)insn;AbstractInsnNode previous=insn.getPrevious();
                if (call.name.equals("receive") || call.name.equals("event") && previous instanceof IntInsnNode && ((IntInsnNode)previous).operand>=12)
                    throw new IllegalStateException("Movement hook already installed");
            }
            found=method;
        }
        if (found==null) throw new IllegalStateException("Missing "+mcp+desc);return found;
    }
    private static InsnList event(int kind) {
        InsnList code=new InsnList();code.add(new IntInsnNode(Opcodes.BIPUSH,kind));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"event","(I)Z",false));code.add(new InsnNode(Opcodes.POP));return code;
    }
}
