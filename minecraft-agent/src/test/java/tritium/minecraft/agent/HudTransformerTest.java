package tritium.minecraft.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import tritium.minecraft.bridge.HookBridge;
import static org.junit.jupiter.api.Assertions.*;

class HudTransformerTest {
    @Test void exactMcpAndSrgDescriptorsAndNoStructuralChanges() {
        for (boolean srg : new boolean[]{false,true}) {
            ClassLoader loader = getClass().getClassLoader(); HudTransformer transformer = new HudTransformer(loader);
            for (int i = 0; i < 3; i++) {
                String name = HudTransformer.TARGETS[i].replace('.', '/'); byte[] original = fixture(name, i, srg);
                byte[] output = transformer.transform(loader, name, null, null, original); assertNotNull(output);
                ClassNode before = node(original), after = node(output);
                assertEquals(before.fields.size(), after.fields.size()); assertEquals(before.methods.size(), after.methods.size());
                StringWriter errors = new StringWriter(); CheckClassAdapter.verify(new ClassReader(output), false, new PrintWriter(errors));
                assertEquals("", errors.toString());
                assertNull(transformer.transform(new ClassLoader(){}, name, null, null, original));
            }
            assertTrue(transformer.complete(), transformer.error()); transformer.deactivate();
            assertNull(transformer.transform(loader, HudTransformer.TARGETS[0].replace('.', '/'), null, null, fixture(HudTransformer.TARGETS[0].replace('.', '/'), 0, srg)));
        }
    }
    @Test void refusesMissingMappingAndDoesNotInjectTwice() {
        ClassLoader loader = getClass().getClassLoader(); String name = HudTransformer.TARGETS[0].replace('.', '/');
        HudTransformer transformer = new HudTransformer(loader);
        byte[] output = transformer.transform(loader, name, null, null, fixture(name, 0, false)); assertNotNull(output);
        assertNull(transformer.transform(loader, name, null, null, output)); assertNotNull(transformer.error());
        assertFalse(transformer.complete());
        assertNull(new HudTransformer(loader).transform(loader, name, null, null, fixture(name, 1, false)));
    }
    @Test void worldCallbackKeepsPartialTicksAndRunsWithHandSuppressed() throws Exception {
        for (boolean srg : new boolean[]{false, true}) {
            String name = HudTransformer.TARGETS[0].replace('.', '/');
            ClassLoader loader = getClass().getClassLoader();
            byte[] output = new HudTransformer(loader).transform(loader, name, null, null, fixture(name, 0, srg));
            assertNotNull(output);
            Class<?> type = new FixtureLoader(loader).define(name.replace('/', '.'), output);
            Object renderer = type.newInstance();
            AtomicInteger calls = new AtomicInteger();
            HookBridge.callback = kind -> { assertEquals(3, kind); calls.incrementAndGet(); return false; };
            try {
                java.lang.reflect.Method render = type.getMethod(srg ? "func_175068_a" : "renderWorldPass", int.class, float.class, long.class);
                render.invoke(renderer, 0, .25f, 0L);
                assertEquals(1, calls.get()); assertEquals(.25f, HookBridge.partialTicks);
                render.invoke(renderer, 1, .75f, 0L);
                assertEquals(2, calls.get()); assertEquals(.75f, HookBridge.partialTicks);
            } finally { HookBridge.callback = null; }
        }
    }
    @Test void refusesUnknownWorldBoundaryRatherThanDrawingAfterProjectionChanges() {
        ClassLoader loader = getClass().getClassLoader();
        String name = HudTransformer.TARGETS[0].replace('.', '/');
        ClassNode changed = node(fixture(name, 0, false));
        for (MethodNode m : changed.methods) for (AbstractInsnNode i : m.instructions)
            if (i instanceof LdcInsnNode && "hand".equals(((LdcInsnNode)i).cst)) ((LdcInsnNode)i).cst = "unknown";
        ClassWriter writer = new ClassWriter(0); changed.accept(writer);
        HudTransformer transformer = new HudTransformer(loader);
        assertNull(transformer.transform(loader, name, null, null, writer.toByteArray()));
        assertTrue(transformer.error().contains("world-to-hand"));
    }
    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
    private static ClassNode node(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    private static byte[] fixture(String name, int kind, boolean srg) {
        ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        String method = kind == 0 ? (srg ? "func_181560_a" : "updateCameraAndRender") : kind == 1 ? (srg ? "func_71407_l" : "runTick") : (srg ? "func_146274_d" : "handleMouseInput");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, method, kind == 0 ? "(FJ)V" : "()V", null, null);
        mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, kind == 0 ? 4 : 1); mv.visitEnd();
        if (kind == 0) {
            MethodVisitor section = w.visitMethod(Opcodes.ACC_PUBLIC, srg ? "func_76318_c" : "endStartSection", "(Ljava/lang/String;)V", null, null);
            section.visitCode(); section.visitInsn(Opcodes.RETURN); section.visitMaxs(0, 2); section.visitEnd();
            MethodVisitor hand = w.visitMethod(Opcodes.ACC_PUBLIC, srg ? "func_78476_b" : "renderHand", "(FI)V", null, null);
            hand.visitCode(); hand.visitInsn(Opcodes.RETURN); hand.visitMaxs(0, 3); hand.visitEnd();
            MethodVisitor world = w.visitMethod(Opcodes.ACC_PUBLIC, srg ? "func_175068_a" : "renderWorldPass", "(IFJ)V", null, null);
            world.visitCode(); world.visitVarInsn(Opcodes.ALOAD, 0); world.visitLdcInsn("hand");
            world.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, srg ? "func_76318_c" : "endStartSection", "(Ljava/lang/String;)V", false);
            Label end = new Label(); world.visitVarInsn(Opcodes.ILOAD, 1); world.visitJumpInsn(Opcodes.IFEQ, end);
            world.visitVarInsn(Opcodes.ALOAD, 0); world.visitVarInsn(Opcodes.FLOAD, 2); world.visitVarInsn(Opcodes.ILOAD, 1);
            world.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, srg ? "func_78476_b" : "renderHand", "(FI)V", false);
            world.visitLabel(end); world.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            world.visitInsn(Opcodes.RETURN); world.visitMaxs(3, 5); world.visitEnd();
        }
        w.visitEnd(); return w.toByteArray();
    }
}
