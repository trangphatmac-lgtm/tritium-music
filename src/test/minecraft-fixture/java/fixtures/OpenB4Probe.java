package fixtures;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Installs temporary counting callbacks, so stale bytecode is visible even after callbacks were cleared. */
public final class OpenB4Probe {
    public static String modules() throws Exception {
        Class<?> bridge=Class.forName("tritium.minecraft.bridge.HookBridge",true,null);
        Field event=bridge.getField("callback"),module=bridge.getField("moduleCallback");
        Object oldEvent=event.get(null),oldModule=module.get(null);
        AtomicInteger calls=new AtomicInteger();Set<Integer> kinds=new HashSet<Integer>();
        boolean exceptionPreserved=false;
        event.set(null,(IntPredicate)kind->{if(kind==16)calls.incrementAndGet();return false;});
        module.set(null,(BiFunction<Integer,Object[],Object>)(kind,args)->{
            calls.incrementAndGet();kinds.add(kind);
            switch(kind){case 0:case 1:case 12:return args[1];case 3:case 8:case 9:return args[0];case 4:return true;case 7:return false;default:return null;}
        });
        try {
            net.minecraft.entity.Entity e=new net.minecraft.entity.Entity();e.moveEntity(1,2,3);e.moveFlying(1,2,3);
            net.minecraft.client.entity.EntityPlayerSP p=new net.minecraft.client.entity.EntityPlayerSP();p.jump();p.attackTargetEntityWithCurrentItem(e);p.updateEntityActionState();
            net.minecraft.client.renderer.EntityRenderer camera=new net.minecraft.client.renderer.EntityRenderer();camera.hurtCameraEffect(.5f);camera.orientCamera(.5f);
            new net.minecraft.client.renderer.ItemRenderer().renderItemInFirstPerson(.5f);
            net.minecraft.client.renderer.entity.RenderPlayer renderer=new net.minecraft.client.renderer.entity.RenderPlayer();
            renderer.doRender(p,0,0,0,0,0);renderer.setModelVisibilities(p);renderer.renderName(p,0,0,0);
            new net.minecraft.client.renderer.entity.RendererLivingEntity().doRender(p,0,0,0,0,.5f);
            renderer.throwOnRender=true;
            try {renderer.doRender(p,0,0,0,0,0);}catch(IllegalStateException expected){exceptionPreserved="fixture render failure".equals(expected.getMessage());}
            new net.minecraft.client.multiplayer.WorldClient().onEntityAdded(e);
        } finally {event.set(null,oldEvent);module.set(null,oldModule);}
        return "OPENB4 "+calls.get()+" "+kinds.size()+" "+exceptionPreserved;
    }
    public static String incoming() throws Exception {
        Class<?> bridge=Class.forName("tritium.minecraft.bridge.HookBridge",true,null);
        Field field=bridge.getField("incomingCallback");Object previous=field.get(null);AtomicInteger calls=new AtomicInteger();
        field.set(null,(BiPredicate<Object,Object>)(handler,packet)->{calls.incrementAndGet();return false;});
        try {
            net.minecraft.client.network.NetHandlerPlayClient handler=new net.minecraft.client.network.NetHandlerPlayClient();
            handler.handleKeepAlive(new net.minecraft.network.play.server.S00PacketKeepAlive());
            handler.handleEntityMovement(new net.minecraft.network.play.server.S14PacketEntity());
            handler.handleEntityVelocity(new net.minecraft.network.play.server.S12PacketEntityVelocity());
            handler.handlePlayerPosLook(new net.minecraft.network.play.server.S08PacketPlayerPosLook());
            handler.handleExplosion(new net.minecraft.network.play.server.S27PacketExplosion());
        } finally {field.set(null,previous);}
        return "INCOMING "+calls.get();
    }
    private OpenB4Probe() { }
}
