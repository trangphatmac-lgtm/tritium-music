package tritium.minecraft.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Additional OpenB4 method-body hooks; safe to remove by retransformation. */
public final class OpenB4Transformer implements ClassFileTransformer {
    private static final String BRIDGE = "tritium/minecraft/bridge/HookBridge";
    public static final String[] TARGETS = {
        "net.minecraft.entity.Entity", "net.minecraft.entity.EntityLivingBase",
        "net.minecraft.entity.player.EntityPlayer", "net.minecraft.client.entity.EntityPlayerSP",
        "net.minecraft.client.renderer.EntityRenderer", "net.minecraft.client.renderer.ItemRenderer",
        "net.minecraft.client.renderer.entity.RenderPlayer", "net.minecraft.client.renderer.entity.RendererLivingEntity",
        "net.minecraft.client.multiplayer.WorldClient", "net.minecraft.client.Minecraft",
        "net.minecraft.util.MovementInputFromOptions", "net.minecraft.client.network.NetHandlerPlayClient"
    };
    private final ClassLoader loader;
    private final Set<String> applied = new HashSet<String>();
    private boolean active = true;
    private String error;
    public OpenB4Transformer(ClassLoader loader) { this.loader = loader; }
    public synchronized void deactivate() { active = false; }
    public synchronized boolean complete() { return applied.size() == TARGETS.length && error == null; }
    public synchronized String error() { return error; }
    @Override public synchronized byte[] transform(ClassLoader defining, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (!active || defining != loader || name == null) return null;
        int kind = -1;
        for (int i = 0; i < TARGETS.length; i++) if (TARGETS[i].replace('.', '/').equals(name)) kind = i;
        if (kind < 0) return null;
        try {
            ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(); reader.accept(node, 0);
            for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!BRIDGE.equals(call.owner)) continue;
                if (Arrays.asList("edge", "movementYaw", "attack", "hurtCamera", "cameraClip", "itemToRender", "playerPre", "suppressName", "entityJoined", "preInput", "rightClick", "slotScroll", "chat", "beforeMouseOver").contains(call.name)
                        || call.name.equals("event") && insn.getPrevious() instanceof IntInsnNode && ((IntInsnNode) insn.getPrevious()).operand >= 16)
                    throw new IllegalStateException("OpenB4 hook already installed");
            }
            switch (kind) {
                case 0: {
                    MethodNode move = method(node, "moveEntity", "func_70091_d", "(DDD)V"); int matches = 0;
                    for (AbstractInsnNode insn : move.instructions.toArray()) if (insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.ISTORE && ((VarInsnNode) insn).var == 19) {
                        InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(call("edge", "(ZLjava/lang/Object;)Z"));
                        move.instructions.insertBefore(insn, code); matches++;
                    }
                    require(matches == 1, "Expected one edge flag store, found " + matches);
                    movementYaw(method(node, "moveFlying", "func_70060_a", "(FFF)V")); break;
                }
                case 1: movementYaw(method(node, "jump", "func_70664_aZ", "()V")); break;
                case 2: {
                    MethodNode attack = method(node, "attackTargetEntityWithCurrentItem", "func_71059_n", "(Lnet/minecraft/entity/Entity;)V");
                    InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(call("attack", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    AbstractInsnNode allowed = null;
                    for (AbstractInsnNode insn : attack.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode forge = (MethodInsnNode) insn;
                        if (!forge.owner.equals("net/minecraftforge/common/ForgeHooks") || !forge.name.equals("onPlayerAttackTarget")) continue;
                        AbstractInsnNode branch = insn.getNext(); while (branch != null && branch.getOpcode() < 0) branch = branch.getNext();
                        require(branch instanceof JumpInsnNode, "Missing Forge attack cancellation branch");
                        if (branch.getOpcode() == Opcodes.IFNE) allowed = ((JumpInsnNode) branch).label;
                        else if (branch.getOpcode() == Opcodes.IFEQ) allowed = branch;
                        else throw new IllegalStateException("Unexpected Forge attack cancellation branch");
                    }
                    if (allowed == null) attack.instructions.insert(code); else attack.instructions.insert(allowed, code);
                    break;
                }
                case 3: {
                    MethodNode update = method(node, "updateEntityActionState", "func_70626_be", "()V");
                    for (AbstractInsnNode insn : update.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) update.instructions.insertBefore(insn, event(16, false));
                    break;
                }
                case 4: {
                    MethodNode hurt = method(node, "hurtCameraEffect", "func_78482_e", "(F)V"); int matches = 0;
                    for (AbstractInsnNode insn : hurt.instructions.toArray()) if (insn instanceof LdcInsnNode && Float.valueOf(14).equals(((LdcInsnNode) insn).cst)) {
                        hurt.instructions.insert(insn, call("hurtCamera", "(F)F")); matches++;
                    }
                    require(matches == 1, "Expected one hurt-camera multiplier, found " + matches);
                    MethodNode camera = method(node, "orientCamera", "func_78467_g", "(F)V"); matches = 0;
                    for (AbstractInsnNode insn : camera.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode ray = (MethodInsnNode) insn;
                        if ((ray.name.equals("rayTraceBlocks") || ray.name.equals("func_72933_a")) && ray.desc.equals("(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;")) {
                            InsnList code = new InsnList(); LabelNode vanilla = new LabelNode(), end = new LabelNode();
                            code.add(call("cameraClip", "()Z")); code.add(new JumpInsnNode(Opcodes.IFNE, vanilla));
                            code.add(new InsnNode(Opcodes.POP)); code.add(new InsnNode(Opcodes.POP)); code.add(new InsnNode(Opcodes.POP));
                            code.add(new InsnNode(Opcodes.ACONST_NULL)); code.add(new JumpInsnNode(Opcodes.GOTO, end)); code.add(vanilla);
                            camera.instructions.insertBefore(insn, code); camera.instructions.insert(insn, end); matches++;
                        }
                    }
                    require(matches == 1, "Expected one camera block raycast, found " + matches);
                    // All crosshair raycasts use the silent rotation, including render-frame calls.
                    MethodNode mouse = method(node, "getMouseOver", "func_78473_a", "(F)V");
                    InsnList start = new InsnList(); start.add(call("beforeMouseOver", "()V"));
                    LabelNode begin = new LabelNode(); start.add(begin); mouse.instructions.insert(start);
                    for (AbstractInsnNode insn : mouse.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) mouse.instructions.insertBefore(insn, call("afterMouseOver", "()V"));
                    LabelNode end = new LabelNode(), handler = new LabelNode(); mouse.instructions.add(end); mouse.instructions.add(handler);
                    mouse.instructions.add(call("afterMouseOver", "()V")); mouse.instructions.add(new InsnNode(Opcodes.ATHROW));
                    mouse.tryCatchBlocks.add(new TryCatchBlockNode(begin, end, handler, null));
                    break;
                }
                case 5: {
                    MethodNode render = method(node, "renderItemInFirstPerson", "func_78440_a", "(F)V"); boolean found = false;
                    for (AbstractInsnNode insn : render.instructions.toArray()) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode field = (FieldInsnNode) insn;
                        if (!(field.name.equals("itemToRender") || field.name.equals("field_78453_b"))) continue;
                        InsnList code = new InsnList(); code.add(new InsnNode(Opcodes.DUP)); code.add(new InsnNode(Opcodes.DUP));
                        code.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
                        code.add(call("itemToRender", "(Ljava/lang/Object;)Ljava/lang/Object;")); code.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/item/ItemStack"));
                        code.add(new FieldInsnNode(Opcodes.PUTFIELD, field.owner, field.name, field.desc));
                        code.add(new VarInsnNode(Opcodes.FLOAD, 2)); code.add(call("animationProgress", "(F)F")); code.add(new VarInsnNode(Opcodes.FSTORE, 2));
                        render.instructions.insertBefore(insn, code); found = true; break;
                    }
                    require(found, "Missing first-person item field"); break;
                }
                case 6: {
                    MethodNode render = method(node, "doRender", "func_76986_a", "(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V");
                    InsnList start = objectCall("playerPre", 1); LabelNode begin = new LabelNode(); start.add(begin); render.instructions.insert(start);
                    for (AbstractInsnNode insn : render.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) render.instructions.insertBefore(insn, objectCall("playerPost", 1));
                    LabelNode end = new LabelNode(), handler = new LabelNode(); render.instructions.add(end); render.instructions.add(handler);
                    render.instructions.add(objectCall("playerPost", 1)); render.instructions.add(new InsnNode(Opcodes.ATHROW));
                    render.tryCatchBlocks.add(new TryCatchBlockNode(begin, end, handler, null));
                    MethodNode model = method(node, "setModelVisibilities", "func_177137_d", "(Lnet/minecraft/client/entity/AbstractClientPlayer;)V");
                    for (AbstractInsnNode insn : model.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        code.add(call("modelVisibility", "(Ljava/lang/Object;Ljava/lang/Object;)V")); model.instructions.insertBefore(insn, code);
                    }
                    break;
                }
                case 7: {
                    MethodNode render = method(node, "renderName", "func_177067_a", "(Lnet/minecraft/entity/EntityLivingBase;DDD)V");
                    InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    for (int i = 2; i <= 6; i += 2) code.add(new VarInsnNode(Opcodes.DLOAD, i));
                    code.add(call("suppressName", "(Ljava/lang/Object;DDD)Z")); cancel(code); render.instructions.insert(code);
                    MethodNode body = method(node, "doRender", "func_76986_a", "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V"); int pitches = 0;
                    for (AbstractInsnNode insn : body.instructions.toArray()) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode field = (FieldInsnNode) insn;
                        if (!field.desc.equals("F") || !Arrays.asList("rotationPitch", "prevRotationPitch", "field_70125_A", "field_70127_C").contains(field.name)) continue;
                        body.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP));
                        InsnList pitch = new InsnList(); pitch.add(new VarInsnNode(Opcodes.FLOAD, 9)); pitch.add(call("headPitch", "(Ljava/lang/Object;FF)F"));
                        body.instructions.insert(insn, pitch); pitches++;
                    }
                    require(pitches >= 2, "Missing render head-pitch fields"); break;
                }
                case 8: {
                    MethodNode join = method(node, "onEntityAdded", "func_72923_a", "(Lnet/minecraft/entity/Entity;)V");
                    for (AbstractInsnNode insn : join.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) join.instructions.insertBefore(insn, objectCall("entityJoined", 1));
                    break;
                }
                case 9: {
                    MethodNode click = method(node, "rightClickMouse", "func_147121_ag", "()V");
                    InsnList start = new InsnList(); start.add(call("rightClick", "()Z")); cancel(start); click.instructions.insert(start);
                    MethodNode tick = method(node, "runTick", "func_71407_l", "()V"); int matches = 0;
                    for (AbstractInsnNode insn : tick.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode scroll = (MethodInsnNode) insn;
                        if (!scroll.owner.equals("net/minecraft/entity/player/InventoryPlayer") || !(scroll.name.equals("changeCurrentItem") || scroll.name.equals("func_70453_c")) || !scroll.desc.equals("(I)V")) continue;
                        InsnList code = new InsnList(); LabelNode vanilla = new LabelNode(), end = new LabelNode();
                        code.add(new InsnNode(Opcodes.DUP2)); code.add(call("slotScroll", "(Ljava/lang/Object;I)Z"));
                        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla)); code.add(new InsnNode(Opcodes.POP2));
                        code.add(new JumpInsnNode(Opcodes.GOTO, end)); code.add(vanilla);
                        tick.instructions.insertBefore(insn, code); tick.instructions.insert(insn, end); matches++;
                    }
                    require(matches == 1, "Expected one hotbar scroll call, found " + matches); break;
                }
                case 10: {
                    MethodNode input = method(node, "updatePlayerMoveState", "func_78898_a", "()V"); int matches = 0;
                    for (AbstractInsnNode insn : input.instructions.toArray()) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode field = (FieldInsnNode) insn;
                        if (field.desc.equals("Z") && (field.name.equals("sneak") || field.name.equals("field_78899_d"))) {
                            input.instructions.insert(insn, objectCall("preInput", 0)); matches++;
                        }
                    }
                    require(matches == 1, "Expected one raw sneak assignment, found " + matches); break;
                }
                case 11: {
                    MethodNode chat = method(node, "handleChat", "func_147251_a", "(Lnet/minecraft/network/play/server/S02PacketChat;)V");
                    MethodInsnNode type = null; int matches = 0;
                    for (AbstractInsnNode insn : chat.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode packet = (MethodInsnNode) insn;
                        if (packet.owner.equals("net/minecraft/network/play/server/S02PacketChat") && (packet.name.equals("getType") || packet.name.equals("func_179841_c")) && packet.desc.equals("()B")) type = packet;
                    }
                    require(type != null, "Missing chat packet type accessor");
                    for (AbstractInsnNode insn : chat.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode print = (MethodInsnNode) insn;
                        if (!print.owner.equals("net/minecraft/client/gui/GuiNewChat") || !(print.name.equals("printChatMessage") || print.name.equals("func_146227_a")) || !print.desc.equals("(Lnet/minecraft/util/IChatComponent;)V")) continue;
                        // The stack contains the final component after Forge's cancellable chat event.
                        InsnList code = new InsnList(); code.add(new InsnNode(Opcodes.DUP)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        code.add(new MethodInsnNode(type.getOpcode(), type.owner, type.name, type.desc, type.itf));
                        code.add(call("chat", "(Ljava/lang/Object;I)V")); chat.instructions.insertBefore(insn, code); matches++;
                    }
                    require(matches == 1, "Expected one received chat display call, found " + matches); break;
                }
                default: throw new AssertionError(kind);
            }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a, String b) {
                    try {
                        Class<?> ca = Class.forName(a.replace('/', '.'), false, loader), cb = Class.forName(b.replace('/', '.'), false, loader);
                        if (ca.isAssignableFrom(cb)) return a; if (cb.isAssignableFrom(ca)) return b;
                        if (ca.isInterface() || cb.isInterface()) return "java/lang/Object";
                        do { ca = ca.getSuperclass(); } while (!ca.isAssignableFrom(cb));
                        return ca.getName().replace('.', '/');
                    } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
                }
            };
            node.accept(writer); byte[] result = writer.toByteArray(); applied.add(name); return result;
        } catch (Throwable failure) { error = name + ": " + failure.getMessage(); return null; }
    }
    private static void movementYaw(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETFIELD) {
            FieldInsnNode field = (FieldInsnNode) insn;
            if ((field.name.equals("rotationYaw") || field.name.equals("field_70177_z")) && field.desc.equals("F")) {
                method.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP)); method.instructions.insert(insn, call("movementYaw", "(Ljava/lang/Object;F)F")); count++;
            }
        }
        require(count > 0, "Missing movement yaw in " + method.name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static MethodNode method(ClassNode node, String mcp, String srg, String desc) {
        for (MethodNode method : node.methods) if ((method.name.equals(mcp) || method.name.equals(srg)) && method.desc.equals(desc)) return method;
        throw new IllegalStateException("Missing " + mcp + desc);
    }
    private static MethodInsnNode call(String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, name, descriptor, false); }
    private static InsnList event(int kind, boolean result) {
        InsnList code = new InsnList(); code.add(new IntInsnNode(Opcodes.BIPUSH, kind)); code.add(call("event", "(I)Z"));
        if (!result) code.add(new InsnNode(Opcodes.POP)); return code;
    }
    private static InsnList objectCall(String name, int local) {
        InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, local)); code.add(call(name, "(Ljava/lang/Object;)V")); return code;
    }
    private static void cancel(InsnList code) { LabelNode next = new LabelNode(); code.add(new JumpInsnNode(Opcodes.IFEQ, next)); code.add(new InsnNode(Opcodes.RETURN)); code.add(next); }
}
