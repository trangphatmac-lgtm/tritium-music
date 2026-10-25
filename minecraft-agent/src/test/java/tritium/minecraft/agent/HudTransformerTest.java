package tritium.minecraft.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import java.io.*;
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
    private static ClassNode node(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    private static byte[] fixture(String name, int kind, boolean srg) {
        ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        String method = kind == 0 ? (srg ? "func_181560_a" : "updateCameraAndRender") : kind == 1 ? (srg ? "func_71407_l" : "runTick") : (srg ? "func_146274_d" : "handleMouseInput");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, method, kind == 0 ? "(FJ)V" : "()V", null, null);
        mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, kind == 0 ? 4 : 1); mv.visitEnd(); w.visitEnd(); return w.toByteArray();
    }
}
