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
    public void runTick() { }
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
            if (command.equals("bridge")) System.out.println("BRIDGE " + (Class.forName("tritium.minecraft.bridge.HookBridge", true, null).getField("callback").get(null) == null));
            System.out.flush();
        }
    }
}
