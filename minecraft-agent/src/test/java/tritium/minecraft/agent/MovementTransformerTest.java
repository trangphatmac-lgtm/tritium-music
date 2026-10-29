package tritium.minecraft.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import tritium.minecraft.bridge.HookBridge;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MovementTransformerTest {
    @Test void mcpAndSrgHooksRunAfterInputAndAfterPacketThreadHandoff() throws Exception {
        for (boolean srg:new boolean[]{false,true}) {
            FixtureLoader loader=new FixtureLoader();
            Class<?> input=loader.define("net.minecraft.util.MovementInput",utility("net/minecraft/util/MovementInput",srg?"func_78898_a":"updatePlayerMoveState",false));
            Class<?> handoff=loader.define("net.minecraft.network.PacketThreadUtil",utility("net/minecraft/network/PacketThreadUtil",srg?"func_180031_a":"checkThreadAndEnqueue",true));
            MovementTransformer transformer=new MovementTransformer(loader);
            Class<?> player=transformed(loader,transformer,0,srg,true),handler=transformed(loader,transformer,1,srg,true);
            assertTrue(transformer.complete(),transformer.error());
            List<Integer> events=new ArrayList<Integer>(), inputCalls=new ArrayList<Integer>();
            HookBridge.callback=event->{events.add(event);if(event==13)try{inputCalls.add(input.getField("calls").getInt(null));}catch(Exception e){throw new AssertionError(e);}return false;};
            try {
                Object p=player.newInstance();player.getMethod(srg?"func_70636_d":"onLivingUpdate").invoke(p);player.getMethod(srg?"func_70071_h_":"onUpdate").invoke(p);
                assertEquals(Arrays.asList(13,12),events);assertEquals(Collections.singletonList(1),inputCalls);
                for (String[] method:METHODS) {
                    Class<?> packet=loader.loadClass("net.minecraft.network.play.server."+method[2]);
                    java.lang.reflect.Method receive=handler.getMethod(method[srg?1:0],packet);Object h=handler.newInstance();
                    final int[] callbacks={0};HookBridge.receiveCallback=value->{callbacks[0]++;return true;};
                    handoff.getField("fail").setBoolean(null,true);
                    assertThrows(java.lang.reflect.InvocationTargetException.class,()->receive.invoke(h,packet.newInstance()));assertEquals(0,callbacks[0]);
                    handoff.getField("fail").setBoolean(null,false);handler.getField("processed").setInt(null,0);
                    receive.invoke(h,packet.newInstance());assertEquals(1,callbacks[0]);assertEquals(0,handler.getField("processed").getInt(null));
                    HookBridge.receiveCallback=value->false;receive.invoke(h,packet.newInstance());assertEquals(1,handler.getField("processed").getInt(null));
                }
            } finally { HookBridge.callback=null;HookBridge.receiveCallback=null; }
            transformer.deactivate();assertNull(transformer.transform(loader,MovementTransformer.TARGETS[0].replace('.','/'),null,null,fixture(0,srg,true)));
        }
    }
    @Test void missingAnchorsDuplicatesAndWrongLoadersAreRejected() {
        FixtureLoader loader=new FixtureLoader();String name=MovementTransformer.TARGETS[0].replace('.','/');
        MovementTransformer transformer=new MovementTransformer(loader);byte[] before=fixture(0,false,true);
        assertNull(transformer.transform(new ClassLoader(){},name,null,null,before));
        byte[] once=transformer.transform(loader,name,null,null,before);assertNotNull(once);
        assertNull(transformer.transform(loader,name,null,null,once));assertTrue(transformer.error().contains("already installed"));
        for(int kind=0;kind<2;kind++) {
            MovementTransformer missing=new MovementTransformer(loader);assertNull(missing.transform(loader,MovementTransformer.TARGETS[kind].replace('.','/'),null,null,fixture(kind,false,false)));
            assertTrue(missing.error().contains("Expected one"),missing.error());
        }
    }
    @Test void receiveBridgeFailsOpenAndDoesNotKeepCallbackWhenDetached() {
        HookBridge.receiveCallback=null;assertFalse(HookBridge.receive(this));
        HookBridge.receiveCallback=p->{throw new IllegalStateException();};
        try { assertFalse(HookBridge.receive(this)); } finally { HookBridge.receiveCallback=null; }
    }
    private static Class<?> transformed(FixtureLoader loader,MovementTransformer transformer,int kind,boolean srg,boolean anchor) throws Exception {
        String name=MovementTransformer.TARGETS[kind].replace('.','/');byte[] before=fixture(kind,srg,anchor),after=transformer.transform(loader,name,null,null,before);assertNotNull(after,transformer.error());
        ClassNode original=new ClassNode(),node=new ClassNode();new ClassReader(before).accept(original,0);new ClassReader(after).accept(node,0);
        assertEquals(original.fields.size(),node.fields.size());assertEquals(original.methods.size(),node.methods.size());
        for(MethodNode m:node.methods)new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name,m);
        return loader.define(name.replace('/','.'),after);
    }
    private static final String[][] METHODS={{"handleEntityVelocity","func_147244_a","S12PacketEntityVelocity"},
            {"handlePlayerPosLook","func_147258_a","S08PacketPlayerPosLook"},{"handleExplosion","func_147283_a","S27PacketExplosion"}};
    private static ClassWriter start(String name) {
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        MethodVisitor ctor=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);finish(ctor);return w;
    }
    private static void finish(MethodVisitor v) { v.visitInsn(Opcodes.RETURN);v.visitMaxs(0,0);v.visitEnd(); }
    private static void increment(MethodVisitor v,String owner,String field) {
        v.visitFieldInsn(Opcodes.GETSTATIC,owner,field,"I");v.visitInsn(Opcodes.ICONST_1);v.visitInsn(Opcodes.IADD);v.visitFieldInsn(Opcodes.PUTSTATIC,owner,field,"I");
    }
    private static byte[] utility(String name,String method,boolean handoff) {
        ClassWriter w=start(name);w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"calls","I",null,null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"fail","Z",null,null).visitEnd();
        MethodVisitor v=w.visitMethod(Opcodes.ACC_PUBLIC|(handoff?Opcodes.ACC_STATIC:0),method,handoff?"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V":"()V",null,null);v.visitCode();
        if(handoff){
            Label next=new Label();v.visitFieldInsn(Opcodes.GETSTATIC,name,"fail","Z");v.visitJumpInsn(Opcodes.IFEQ,next);
            v.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException");v.visitInsn(Opcodes.DUP);v.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","()V",false);v.visitInsn(Opcodes.ATHROW);v.visitLabel(next);
        }
        increment(v,name,"calls");finish(v);w.visitEnd();return w.toByteArray();
    }
    private static byte[] fixture(int kind,boolean srg,boolean anchor) {
        String name=MovementTransformer.TARGETS[kind].replace('.','/');ClassWriter w=start(name);
        w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"processed","I",null,null).visitEnd();
        if(kind==0){
            MethodVisitor update=w.visitMethod(Opcodes.ACC_PUBLIC,srg?"func_70071_h_":"onUpdate","()V",null,null);update.visitCode();finish(update);
            MethodVisitor living=w.visitMethod(Opcodes.ACC_PUBLIC,srg?"func_70636_d":"onLivingUpdate","()V",null,null);living.visitCode();
            if(anchor){living.visitTypeInsn(Opcodes.NEW,"net/minecraft/util/MovementInput");living.visitInsn(Opcodes.DUP);living.visitMethodInsn(Opcodes.INVOKESPECIAL,"net/minecraft/util/MovementInput","<init>","()V",false);
                living.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"net/minecraft/util/MovementInput",srg?"func_78898_a":"updatePlayerMoveState","()V",false);}
            finish(living);
        }else for(String[] method:METHODS){
            MethodVisitor v=w.visitMethod(Opcodes.ACC_PUBLIC,method[srg?1:0],"(Lnet/minecraft/network/play/server/"+method[2]+";)V",null,null);v.visitCode();
            if(anchor){v.visitInsn(Opcodes.ACONST_NULL);v.visitInsn(Opcodes.ACONST_NULL);v.visitInsn(Opcodes.ACONST_NULL);v.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/network/PacketThreadUtil",srg?"func_180031_a":"checkThreadAndEnqueue","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false);}
            increment(v,name,"processed");finish(v);
        }
        w.visitEnd();return w.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(){super(MovementTransformerTest.class.getClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name,bytes,0,bytes.length);}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
            if(!name.startsWith("net.minecraft.network.play.server."))throw new ClassNotFoundException(name);
            ClassWriter w=start(name.replace('.','/'));w.visitEnd();return define(name,w.toByteArray());
        }
    }
}
