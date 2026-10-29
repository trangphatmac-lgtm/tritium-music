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
    public static Minecraft getMinecraft() { return INSTANCE; }
    public void runTick() { org.lwjgl.input.Mouse.getEventButtonState(); new EntityRenderer().getMouseOver(1); }
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
            if (command.equals("bridge")) {
                Class<?> bridge = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
                boolean cleared = true;
                for (String field : new String[]{"callback", "packetCallback", "velocityCallback", "blockingCallback", "receiveCallback"}) cleared &= bridge.getField(field).get(null) == null;
                System.out.println("BRIDGE " + cleared);
            }
            if (command.equals("combat")) {
                java.lang.reflect.Field callback = Class.forName("tritium.minecraft.bridge.HookBridge", true, null).getField("callback");
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                callback.set(null, (java.util.function.IntPredicate) kind -> { calls.incrementAndGet(); return false; });
                try { new net.minecraft.client.entity.EntityPlayerSP().onUpdateWalkingPlayer(); }
                finally { callback.set(null, null); }
                System.out.println("COMBAT " + calls.get());
            }
            if (command.equals("movement")) {
                Class<?> bridge = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                bridge.getField("callback").set(null, (java.util.function.IntPredicate) kind -> { calls.incrementAndGet(); return false; });
                bridge.getField("receiveCallback").set(null, (java.util.function.Predicate<Object>) packet -> { calls.incrementAndGet(); return false; });
                try {
                    net.minecraft.client.entity.EntityPlayerSP p = new net.minecraft.client.entity.EntityPlayerSP(); p.onUpdate(); p.onLivingUpdate();
                    net.minecraft.client.network.NetHandlerPlayClient h = new net.minecraft.client.network.NetHandlerPlayClient();
                    h.handleEntityVelocity(new net.minecraft.network.play.server.S12PacketEntityVelocity());
                    h.handlePlayerPosLook(new net.minecraft.network.play.server.S08PacketPlayerPosLook());
                    h.handleExplosion(new net.minecraft.network.play.server.S27PacketExplosion());
                } finally { bridge.getField("callback").set(null,null); bridge.getField("receiveCallback").set(null,null); }
                System.out.println("MOVEMENT " + calls.get());
            }
            System.out.flush();
        }
    }
}
