package tritium.minecraft.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Method-body-only combat hooks for 1.8.9 MCP/SRG; no fields or methods are added. */
public final class CombatTransformer implements ClassFileTransformer {
    private static final String BRIDGE="tritium/minecraft/bridge/HookBridge";
    public static final String[] TARGETS={"net.minecraft.client.Minecraft", "net.minecraft.client.renderer.EntityRenderer",
            "net.minecraft.client.entity.EntityPlayerSP", "net.minecraft.network.NetworkManager",
            "net.minecraft.client.network.NetHandlerPlayClient", "net.minecraft.entity.player.EntityPlayer"};
    private final ClassLoader loader;
    private final Set<String> applied=new HashSet<String>();
    private boolean active=true;
    private String error;
    public CombatTransformer(ClassLoader loader) { this.loader=loader; }
    public synchronized void deactivate() { active=false; }
    public synchronized boolean complete() { return applied.size()==TARGETS.length && error==null; }
    public synchronized String error() { return error; }
    @Override public synchronized byte[] transform(ClassLoader defining,String name,Class<?> redefined,ProtectionDomain domain,byte[] bytes) {
        if (!active || defining!=loader || name==null) return null;
        int kind=-1; for (int i=0;i<TARGETS.length;i++) if (TARGETS[i].replace('.','/').equals(name)) kind=i;
        if (kind<0) return null;
        try {
            ClassReader reader=new ClassReader(bytes); ClassNode node=new ClassNode(); reader.accept(node,0);
            if (kind==0) {
                MethodNode tick=method(node,"runTick","func_71407_l","()V");
                // Filter the event's state, not Mouse.next(): cancellation must not discard later mouse events.
                int matches=0, raycasts=0;
                for (AbstractInsnNode insn:tick.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if (call.owner.equals("org/lwjgl/input/Mouse") && call.name.equals("getEventButtonState") && call.desc.equals("()Z")) {
                        tick.instructions.insert(insn,new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"mouseState","(Z)Z",false)); matches++;
                    }
                }
                for (AbstractInsnNode insn:tick.instructions.toArray()) {
                    if (insn.getOpcode()==Opcodes.RETURN) tick.instructions.insertBefore(insn,event(9,false));
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if (call.owner.equals("net/minecraft/client/renderer/EntityRenderer") && (call.name.equals("getMouseOver") || call.name.equals("func_78473_a")) && call.desc.equals("(F)V")) {
                        // Match Raven's tick-only redirect, preserving the render frame's normal crosshair.
                        InsnList code=eventResult(7); LabelNode vanilla=new LabelNode(), end=new LabelNode();
                        code.add(new JumpInsnNode(Opcodes.IFEQ,vanilla));
                        code.add(new InsnNode(Opcodes.POP)); code.add(new InsnNode(Opcodes.POP));
                        code.add(new JumpInsnNode(Opcodes.GOTO,end)); code.add(vanilla);
                        tick.instructions.insertBefore(insn,code); tick.instructions.insert(insn,end); raycasts++;
                    }
                }
                if (matches==0 || raycasts!=1) throw new IllegalStateException("Expected mouse events and one tick raycast; found " + matches + "/" + raycasts);
            } else if (kind==1) {
                MethodNode render=method(node,"updateCameraAndRender","func_181560_a","(FJ)V"); render.instructions.insert(event(4,false));
            } else if (kind==2) {
                method(node,"onUpdate","func_70071_h_","()V").instructions.insert(event(8,false));
                MethodNode motion=method(node,"onUpdateWalkingPlayer","func_175161_p","()V");
                InsnList start=event(6,false); LabelNode begin=new LabelNode(); start.add(begin); motion.instructions.insert(start);
                for (AbstractInsnNode insn:motion.instructions.toArray()) if (insn.getOpcode()==Opcodes.RETURN) motion.instructions.insertBefore(insn,event(5,false));
                LabelNode end=new LabelNode(),handler=new LabelNode(); motion.instructions.add(end); motion.instructions.add(handler);
                // Keep the thrown exception on the stack while restoring the visible rotation.
                motion.instructions.add(event(11,false)); motion.instructions.add(new InsnNode(Opcodes.ATHROW));
                motion.tryCatchBlocks.add(new TryCatchBlockNode(begin,end,handler,null));
            } else if (kind==3) {
                MethodNode send=method(node,"sendPacket","func_179290_a","(Lnet/minecraft/network/Packet;)V");
                InsnList code=new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD,0)); code.add(new VarInsnNode(Opcodes.ALOAD,1));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"packet","(Ljava/lang/Object;Ljava/lang/Object;)Z",false));
                cancel(code); send.instructions.insert(code);
            } else if (kind==4) {
                MethodNode velocity=method(node,"handleEntityVelocity","func_147244_a","(Lnet/minecraft/network/play/server/S12PacketEntityVelocity;)V");
                for (AbstractInsnNode insn:velocity.instructions.toArray()) if (insn.getOpcode()==Opcodes.RETURN) {
                    InsnList code=new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD,1));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"velocity","(Ljava/lang/Object;)V",false)); velocity.instructions.insertBefore(insn,code);
                }
            } else {
                for (String[] m:new String[][]{{"isBlocking","func_70632_aY","()Z"},{"getItemInUseCount","func_71052_bv","()I"}}) {
                    MethodNode target=method(node,m[0],m[1],m[2]); InsnList code=new InsnList();
                    code.add(new VarInsnNode(Opcodes.ALOAD,0)); code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"blocking","(Ljava/lang/Object;)Z",false));
                    LabelNode next=new LabelNode(); code.add(new JumpInsnNode(Opcodes.IFEQ,next));
                    code.add(new InsnNode(Opcodes.ICONST_1)); code.add(new InsnNode(Opcodes.IRETURN)); code.add(next); target.instructions.insert(code);
                }
            }
            ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a,String b) {
                    try {
                        Class<?> ca=Class.forName(a.replace('/','.'),false,loader),cb=Class.forName(b.replace('/','.'),false,loader);
                        if (ca.isAssignableFrom(cb)) return a; if (cb.isAssignableFrom(ca)) return b;
                        if (ca.isInterface() || cb.isInterface()) return "java/lang/Object";
                        do { ca=ca.getSuperclass(); } while (!ca.isAssignableFrom(cb)); return ca.getName().replace('.','/');
                    } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
                }
            };
            node.accept(writer); byte[] result=writer.toByteArray(); applied.add(name); return result;
        } catch (Throwable e) { error=name+": "+e.getMessage(); return null; }
    }
    private static MethodNode method(ClassNode node,String mcp,String srg,String desc) {
        MethodNode found=null;
        for (MethodNode method:node.methods) if ((method.name.equals(mcp)||method.name.equals(srg)) && method.desc.equals(desc)) {
            if (found!=null || (method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))!=0) throw new IllegalStateException("Unexpected "+mcp);
            for (AbstractInsnNode i:method.instructions) if (i instanceof MethodInsnNode && BRIDGE.equals(((MethodInsnNode)i).owner)
                    && !((MethodInsnNode)i).name.equals("event") && !((MethodInsnNode)i).name.equals("world")) throw new IllegalStateException("Combat hook already installed");
            // HUD event(0/1) hooks can share these methods; combat hooks cannot.
            for (AbstractInsnNode i:method.instructions) if (i instanceof MethodInsnNode && BRIDGE.equals(((MethodInsnNode)i).owner) && ((MethodInsnNode)i).name.equals("event")) {
                AbstractInsnNode previous=i.getPrevious();
                if (previous instanceof IntInsnNode || previous!=null && previous.getOpcode()>=Opcodes.ICONST_4 && previous.getOpcode()<=Opcodes.ICONST_5) throw new IllegalStateException("Combat hook already installed");
            }
            found=method;
        }
        if (found==null) throw new IllegalStateException("Missing "+mcp+desc); return found;
    }
    private static InsnList event(int kind,boolean cancel) {
        InsnList code=eventResult(kind);
        if (cancel) cancel(code); else code.add(new InsnNode(Opcodes.POP)); return code;
    }
    private static InsnList eventResult(int kind) {
        InsnList code=new InsnList(); code.add(new IntInsnNode(Opcodes.BIPUSH,kind));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"event","(I)Z",false)); return code;
    }
    private static void cancel(InsnList code) { LabelNode next=new LabelNode(); code.add(new JumpInsnNode(Opcodes.IFEQ,next)); code.add(new InsnNode(Opcodes.RETURN)); code.add(next); }
}
