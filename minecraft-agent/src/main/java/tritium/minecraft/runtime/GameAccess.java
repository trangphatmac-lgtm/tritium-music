package tritium.minecraft.runtime;

import java.lang.reflect.*;
import org.lwjgl.opengl.Display;

/** Small, exact MCP/SRG adapter; no Minecraft classes are compiled into the agent. */
final class GameAccess {
    final Object minecraft;
    final String client;
    private final Field world, screen, settings, focus, displayWidth, displayHeight;
    private final Field hideGui, guiScale;
    private final Method unicode;
    private final Class<?> chat;

    GameAccess(ClassLoader loader) throws Exception {
        Class<?> mc = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Method getter = method(mc, "getMinecraft", "func_71410_x");
        minecraft = getter.invoke(null);
        if (minecraft == null) throw new IllegalStateException("Minecraft is still starting");
        String version;
        try {
            Class<?> constants = Class.forName("net.minecraft.realms.RealmsSharedConstants", false, loader);
            version = String.valueOf(constants.getField("VERSION_STRING").get(null));
        } catch (ReflectiveOperationException absent) {
            version = String.valueOf(method(mc, "getVersion", "func_175600_c").invoke(minecraft));
        }
        if (!version.matches(".*(?<![0-9])1\\.8\\.9(?![0-9]).*"))
            throw new IllegalStateException("Only Minecraft 1.8.9 is supported (found " + version + ")");
        client = getter.getName().equals("func_71410_x") ? "Forge" : "Lunar / MCP";
        world = field(mc, "theWorld", "field_71441_e");
        screen = field(mc, "currentScreen", "field_71462_r");
        settings = field(mc, "gameSettings", "field_71474_y");
        focus = field(mc, "inGameHasFocus", "field_71415_G");
        displayWidth = field(mc, "displayWidth", "field_71443_c");
        displayHeight = field(mc, "displayHeight", "field_71440_d");
        Class<?> gs = settings.getType();
        hideGui = field(gs, "hideGUI", "field_74319_N");
        guiScale = field(gs, "guiScale", "field_74335_Z");
        unicode = method(mc, "isUnicode", "func_152349_b");
        chat = Class.forName("net.minecraft.client.gui.GuiChat", false, loader);
    }

    boolean inWorld() throws IllegalAccessException { return world.get(minecraft) != null; }
    boolean chatOpen() throws IllegalAccessException { return chat.isInstance(screen.get(minecraft)); }
    boolean acceptsKeys() throws IllegalAccessException {
        return inWorld() && screen.get(minecraft) == null && focus.getBoolean(minecraft) && Display.isActive();
    }
    boolean hidden() throws IllegalAccessException { return hideGui.getBoolean(settings.get(minecraft)); }
    int pixelWidth() throws IllegalAccessException { return Math.max(1, displayWidth.getInt(minecraft)); }
    int pixelHeight() throws IllegalAccessException { return Math.max(1, displayHeight.getInt(minecraft)); }
    int scale() throws IllegalAccessException {
        int scale = 1, desired = guiScale.getInt(settings.get(minecraft));
        if (desired == 0) desired = 1000;
        while (scale < desired && pixelWidth() / (scale + 1) >= 320 && pixelHeight() / (scale + 1) >= 240) scale++;
        boolean unicodeFont;
        try { unicodeFont = Boolean.TRUE.equals(unicode.invoke(minecraft)); }
        catch (InvocationTargetException e) { throw new IllegalStateException(e.getCause()); }
        if (unicodeFont && scale % 2 != 0 && scale != 1) scale--;
        return scale;
    }
    int width() throws IllegalAccessException { int s = scale(); return (pixelWidth() + s - 1) / s; }
    int height() throws IllegalAccessException { int s = scale(); return (pixelHeight() + s - 1) / s; }

    private static Field field(Class<?> type, String mcp, String srg) throws NoSuchFieldException {
        for (String name : new String[]{mcp, srg}) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + mcp);
    }
    private static Method method(Class<?> type, String mcp, String srg) throws NoSuchMethodException {
        for (String name : new String[]{mcp, srg}) {
            try { Method method = type.getDeclaredMethod(name); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + mcp);
    }
}
