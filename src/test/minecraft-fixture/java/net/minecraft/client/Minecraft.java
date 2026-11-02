package net.minecraft.client;

import net.minecraft.client.gui.*;
import net.minecraft.client.renderer.EntityRenderer;
import java.io.*;

/** Test fixture, NOT a Minecraft client. Does not produce renderer READY. */
public final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public Object theWorld;
    public GuiScreen currentScreen;
    public Settings gameSettings = new Settings();
    public boolean inGameHasFocus;
    public int displayWidth = 800, displayHeight = 600;
    public int rightClicks;
    public net.minecraft.entity.player.InventoryPlayer inventory = new net.minecraft.entity.player.InventoryPlayer();
    public static Minecraft getMinecraft() { return INSTANCE; }
    public boolean isCallingFromMinecraftThread() { return true; }
    public void runTick() { org.lwjgl.input.Mouse.getEventButtonState(); new EntityRenderer().getMouseOver(1); inventory.changeCurrentItem(1); }
    public void rightClickMouse() { rightClicks++; }
    public boolean isUnicode() { return gameSettings.forceUnicodeFont; }
    public static final class Settings { public boolean hideGUI, forceUnicodeFont; public int guiScale; }
    public static void main(String[] args) throws Exception {
        EntityRenderer renderer = new EntityRenderer(); new GuiChat();
        System.out.println("FIXTURE " + System.getProperty("java.version")); System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String command;
        while ((command = reader.readLine()) != null) {
            if (command.equals("exit")) return;
            if (command.equals("marker")) System.out.println("MARKER " + renderer.marker());
            if (command.equals("transformers")) {
                Class<?> agent = Class.forName("tritium.minecraft.agent.HudAgent");
                synchronized (agent) {
                    int count = 0;
                    for (String name : new String[]{"transformer", "combatTransformer", "movementTransformer", "openB4Transformer"}) {
                        java.lang.reflect.Field field = agent.getDeclaredField(name); field.setAccessible(true);
                        if (field.get(null) != null) count++;
                    }
                    java.lang.reflect.Field targets = agent.getDeclaredField("targets"); targets.setAccessible(true);
                    Class<?>[] classes = (Class<?>[]) targets.get(null);
                    System.out.println("TRANSFORMERS " + count + " " + (classes == null ? 0 : classes.length));
                }
            }
            if (command.equals("hud")) {
                java.lang.reflect.Field callback = Class.forName("tritium.minecraft.bridge.HookBridge", true, null).getField("callback");
                Object previous = callback.get(null);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                callback.set(null, (java.util.function.IntPredicate) kind -> { if (kind == 0 || kind == 1 || kind == 3) calls.incrementAndGet(); return false; });
                try {
                    renderer.updateCameraAndRender(1, 0); INSTANCE.runTick();
                    java.lang.reflect.Method world = EntityRenderer.class.getDeclaredMethod("renderWorldPass", int.class, float.class, long.class);
                    world.setAccessible(true); world.invoke(renderer, 0, 1f, 0L);
                } finally { callback.set(null, previous); }
                System.out.println("HUD " + calls.get());
            }
            if (command.equals("bridge")) {
                Class<?> bridge = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
                boolean cleared = true;
                for (String field : new String[]{"callback", "packetCallback", "velocityCallback", "blockingCallback", "receiveCallback", "incomingCallback", "moduleCallback"}) cleared &= bridge.getField(field).get(null) == null;
                System.out.println("BRIDGE " + cleared);
            }
            if (command.equals("openb4")) System.out.println(fixtures.OpenB4Probe.modules());
            if (command.equals("incoming")) System.out.println(fixtures.OpenB4Probe.incoming());
            if (command.equals("bridgeassist")) System.out.println(fixtures.BridgeAssistProbe.run());
            if (command.equals("combat")) {
                java.lang.reflect.Field callback = Class.forName("tritium.minecraft.bridge.HookBridge", true, null).getField("callback");
                Object previous = callback.get(null);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                callback.set(null, (java.util.function.IntPredicate) kind -> { calls.incrementAndGet(); return false; });
                try { new net.minecraft.client.entity.EntityPlayerSP().onUpdateWalkingPlayer(); }
                finally { callback.set(null, previous); }
                System.out.println("COMBAT " + calls.get());
            }
            if (command.equals("movement")) {
                Class<?> bridge = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
                Object previousEvent = bridge.getField("callback").get(null), previousReceive = bridge.getField("receiveCallback").get(null);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                bridge.getField("callback").set(null, (java.util.function.IntPredicate) kind -> { calls.incrementAndGet(); return false; });
                bridge.getField("receiveCallback").set(null, (java.util.function.Predicate<Object>) packet -> { calls.incrementAndGet(); return false; });
                try {
                    net.minecraft.client.entity.EntityPlayerSP p = new net.minecraft.client.entity.EntityPlayerSP(); p.onUpdate(); p.onLivingUpdate();
                    net.minecraft.client.network.NetHandlerPlayClient h = new net.minecraft.client.network.NetHandlerPlayClient();
                    h.handleEntityVelocity(new net.minecraft.network.play.server.S12PacketEntityVelocity());
                    h.handlePlayerPosLook(new net.minecraft.network.play.server.S08PacketPlayerPosLook());
                    h.handleExplosion(new net.minecraft.network.play.server.S27PacketExplosion());
                } finally { bridge.getField("callback").set(null,previousEvent); bridge.getField("receiveCallback").set(null,previousReceive); }
                System.out.println("MOVEMENT " + calls.get());
            }
            System.out.flush();
        }
    }
}
