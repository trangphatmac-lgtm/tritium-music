package tritium.minecraft.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import tritium.minecraft.bridge.HookBridge;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatTransformerTest {
    @Test void mcpAndSrgHooksVerifyAndKeepClassStructure() throws Exception {
        for(boolean srg:new boolean[]{false,true}) {
            FixtureLoader loader=new FixtureLoader(); CombatTransformer transformer=new CombatTransformer(loader);
            for(int kind=0;kind<CombatTransformer.TARGETS.length;kind++) {
                String name=CombatTransformer.TARGETS[kind].replace('.','/'); byte[] before=fixture(name,kind,srg,false);
                byte[] after=transformer.transform(loader,name,null,null,before); assertNotNull(after,transformer.error());
                ClassNode a=node(before),b=node(after); assertEquals(a.fields.size(),b.fields.size()); assertEquals(a.methods.size(),b.methods.size());
                StringWriter errors=new StringWriter(); CheckClassAdapter.verify(new ClassReader(after),loader,false,new PrintWriter(errors)); assertEquals("",errors.toString());
                assertNull(transformer.transform(new ClassLoader(){},name,null,null,before));
                loader.define(name.replace('/','.'),after);
            }
            assertTrue(transformer.complete(),transformer.error());
            transformer.deactivate(); assertNull(transformer.transform(loader,CombatTransformer.TARGETS[0].replace('.','/'),null,null,fixture(CombatTransformer.TARGETS[0].replace('.','/'),0,srg,false)));
        }
    }
    @Test void silentMotionAlwaysRestoresIncludingExceptionPath() throws Exception {
        for(boolean throwsError:new boolean[]{false,true}) {
            FixtureLoader loader=new FixtureLoader(); String name=CombatTransformer.TARGETS[2].replace('.','/');
            byte[] bytes=new CombatTransformer(loader).transform(loader,name,null,null,fixture(name,2,false,throwsError));
            Class<?> type=loader.define(name.replace('/','.'),bytes); Object instance=type.newInstance(); List<Integer> events=new ArrayList<Integer>();
            HookBridge.callback=kind->{events.add(kind);return false;};
            try {
                if(throwsError) assertThrows(java.lang.reflect.InvocationTargetException.class,()->type.getMethod("onUpdateWalkingPlayer").invoke(instance));
                else type.getMethod("onUpdateWalkingPlayer").invoke(instance);
                assertEquals(Arrays.asList(6,throwsError?11:5),events);
            } finally { HookBridge.callback=null; }
        }
    }
    @Test void duplicateAndMissingMappingsAreRejected() {
        FixtureLoader loader=new FixtureLoader(); String name=CombatTransformer.TARGETS[2].replace('.','/');
        CombatTransformer transformer=new CombatTransformer(loader); byte[] once=transformer.transform(loader,name,null,null,fixture(name,2,false,false)); assertNotNull(once);
        assertNull(transformer.transform(loader,name,null,null,once)); assertTrue(transformer.error().contains("already installed"));
        CombatTransformer missing=new CombatTransformer(loader); assertNull(missing.transform(loader,name,null,null,fixture(name,0,false,false))); assertTrue(missing.error().contains("Missing"));
    }
    @Test void bridgeCallbacksAreInertWhenDetachedAndFailOpen() {
        HookBridge.callback=null; HookBridge.packetCallback=null; HookBridge.blockingCallback=null; HookBridge.velocityCallback=null;
        assertTrue(HookBridge.mouseState(true)); assertFalse(HookBridge.mouseState(false)); assertFalse(HookBridge.packet(this,this)); assertFalse(HookBridge.blocking(this));
        HookBridge.packetCallback=(m,p)->{throw new IllegalStateException();};
        try { assertFalse(HookBridge.packet(this,this)); } finally { HookBridge.packetCallback=null; }
    }
    private static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static class FixtureLoader extends ClassLoader {
        FixtureLoader(){super(CombatTransformerTest.class.getClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
            if(!name.startsWith("net.minecraft.network.")) throw new ClassNotFoundException(name);
            ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name.replace('.','/'),null,"java/lang/Object",null);w.visitEnd();return define(name,w.toByteArray());
        }
    }
    private static byte[] fixture(String name,int kind,boolean srg,boolean fail) {
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        MethodVisitor ctor=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        String[][][] methods={{{"runTick","func_71407_l","()V"}},{{"updateCameraAndRender","func_181560_a","(FJ)V"},{"getMouseOver","func_78473_a","(F)V"}},
            {{"onUpdate","func_70071_h_","()V"},{"onUpdateWalkingPlayer","func_175161_p","()V"}},{{"sendPacket","func_179290_a","(Lnet/minecraft/network/Packet;)V"}},
            {{"handleEntityVelocity","func_147244_a","(Lnet/minecraft/network/play/server/S12PacketEntityVelocity;)V"}},{{"isBlocking","func_70632_aY","()Z"},{"getItemInUseCount","func_71052_bv","()I"}}};
        for(String[] m:methods[kind]) {
            MethodVisitor v=w.visitMethod(Opcodes.ACC_PUBLIC,m[srg?1:0],m[2],null,null);v.visitCode();
            if(kind==0){
                v.visitMethodInsn(Opcodes.INVOKESTATIC,"org/lwjgl/input/Mouse","getEventButtonState","()Z",false);v.visitInsn(Opcodes.POP);
                v.visitInsn(Opcodes.ACONST_NULL);v.visitInsn(Opcodes.FCONST_1);
                v.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"net/minecraft/client/renderer/EntityRenderer",srg?"func_78473_a":"getMouseOver","(F)V",false);
            }
            if(kind==2 && fail && m[0].equals("onUpdateWalkingPlayer")) {
                v.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException");v.visitInsn(Opcodes.DUP);v.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","()V",false);v.visitInsn(Opcodes.ATHROW);
            } else { if(kind==5)v.visitInsn(Opcodes.ICONST_0);v.visitInsn(kind==5?Opcodes.IRETURN:Opcodes.RETURN); }
            v.visitMaxs(0,0);v.visitEnd();
        }
        w.visitEnd();return w.toByteArray();
    }
}
