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
    private Method renderViewEntity;
    private Field fov, fontRenderer;
    private Method fontWidth, fontDraw;
    private EntityAccess entityAccess;

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

    Camera camera(float partialTicks) throws ReflectiveOperationException {
        Object currentWorld = world.get(minecraft);
        if (currentWorld == null) return null;
        if (renderViewEntity == null) renderViewEntity = method(minecraft.getClass(), "getRenderViewEntity", "func_175606_aa");
        Object entity = renderViewEntity.invoke(minecraft);
        if (entity == null) return null;
        if (entityAccess == null || !entityAccess.type.isInstance(entity)) entityAccess = new EntityAccess(entity.getClass());
        if (fov == null) fov = field(settings.getType(), "fovSetting", "field_74334_X");
        double partial = Math.max(0, Math.min(1, Float.isFinite(partialTicks) ? partialTicks : 0));
        double x = entityAccess.interpolate(entity, entityAccess.lastX, entityAccess.x, partial);
        double y = entityAccess.interpolate(entity, entityAccess.lastY, entityAccess.y, partial);
        double z = entityAccess.interpolate(entity, entityAccess.lastZ, entityAccess.z, partial);
        double eye = ((Number) entityAccess.eyeHeight.invoke(entity)).doubleValue();
        float yaw = entityAccess.yaw.getFloat(entity), pitch = entityAccess.pitch.getFloat(entity);
        float fieldOfView = ((Number) fov.get(settings.get(minecraft))).floatValue();
        return new Camera(currentWorld, x, y, z, x, y + eye, z, yaw, pitch,
                Math.max(1, Math.min(179, fieldOfView)), pixelWidth(), pixelHeight());
    }

    float nativeWidth(String text) throws ReflectiveOperationException {
        Object font = nativeFont();
        if (fontWidth == null) fontWidth = method(font.getClass(), "getStringWidth", "func_78256_a", String.class);
        return ((Number) fontWidth.invoke(font, text)).floatValue();
    }

    void drawNative(String text, float x, float y, int color, boolean shadow) throws ReflectiveOperationException {
        Object font = nativeFont();
        if (fontDraw == null) fontDraw = method(font.getClass(), "drawString", "func_175065_a",
                String.class, float.class, float.class, int.class, boolean.class);
        fontDraw.invoke(font, text, x, y, color, shadow);
    }

    private Object nativeFont() throws ReflectiveOperationException {
        if (fontRenderer == null) fontRenderer = field(minecraft.getClass(), "fontRendererObj", "field_71466_p");
        Object font = fontRenderer.get(minecraft);
        if (font == null) throw new IllegalStateException("Minecraft font renderer unavailable");
        return font;
    }

    static final class Camera {
        final Object worldIdentity;
        final double originX, originY, originZ, eyeX, eyeY, eyeZ;
        final float yaw, pitch, fov;
        final int pixelWidth, pixelHeight;
        Camera(Object worldIdentity, double originX, double originY, double originZ,
               double eyeX, double eyeY, double eyeZ, float yaw, float pitch, float fov, int pixelWidth, int pixelHeight) {
            this.worldIdentity = worldIdentity;
            this.originX = originX; this.originY = originY; this.originZ = originZ;
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
            this.yaw = yaw; this.pitch = pitch; this.fov = fov;
            this.pixelWidth = pixelWidth; this.pixelHeight = pixelHeight;
        }
    }

    private static final class EntityAccess {
        final Class<?> type;
        final Field x, y, z, lastX, lastY, lastZ, yaw, pitch;
        final Method eyeHeight;
        EntityAccess(Class<?> type) throws ReflectiveOperationException {
            this.type = type;
            x = field(type, "posX", "field_70165_t"); y = field(type, "posY", "field_70163_u"); z = field(type, "posZ", "field_70161_v");
            lastX = field(type, "lastTickPosX", "field_70142_S"); lastY = field(type, "lastTickPosY", "field_70137_T"); lastZ = field(type, "lastTickPosZ", "field_70136_U");
            yaw = field(type, "rotationYaw", "field_70177_z"); pitch = field(type, "rotationPitch", "field_70125_A");
            eyeHeight = method(type, "getEyeHeight", "func_70047_e");
        }
        double interpolate(Object entity, Field previous, Field current, double partial) throws IllegalAccessException {
            double start = previous.getDouble(entity);
            return start + (current.getDouble(entity) - start) * partial;
        }
    }

    private static Field field(Class<?> type, String mcp, String srg) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (String name : new String[]{mcp, srg}) {
                try { Field field = c.getDeclaredField(name); field.setAccessible(true); return field; }
                catch (NoSuchFieldException ignored) { }
            }
        throw new NoSuchFieldException(type.getName() + "." + mcp);
    }
    private static Method method(Class<?> type, String mcp, String srg, Class<?>... arguments) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (String name : new String[]{mcp, srg}) {
                try { Method method = c.getDeclaredMethod(name, arguments); method.setAccessible(true); return method; }
                catch (NoSuchMethodException ignored) { }
            }
        throw new NoSuchMethodException(type.getName() + "." + mcp);
    }
}
