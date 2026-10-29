package tritium.minecraft.runtime.lyric;

import org.lwjgl.opengl.GL13;
import java.lang.reflect.*;
import java.util.*;

/** FontRenderer uses GlStateManager; preserve its Java cache as well as the real GL state. */
public final class NativeFontCache {
    private final List<Slot> slots = new ArrayList<Slot>();
    private final Method activeTexture, bindTexture, resetColor, enableTexture, disableTexture, disableAlpha, enableAlpha;

    public NativeFontCache(ClassLoader loader) throws ReflectiveOperationException {
        this(Class.forName("net.minecraft.client.renderer.GlStateManager", true, loader));
    }
    NativeFontCache(Class<?> type) throws ReflectiveOperationException {
        activeTexture = method(type, "setActiveTexture", "func_179138_g", int.class);
        bindTexture = method(type, "bindTexture", "func_179144_i", int.class);
        resetColor = method(type, "resetColor", "func_179117_G");
        enableTexture = method(type, "enableTexture2D", "func_179098_w");
        disableTexture = method(type, "disableTexture2D", "func_179090_x");
        disableAlpha = method(type, "disableAlpha", "func_179118_c");
        enableAlpha = method(type, "enableAlpha", "func_179141_d");
        // Walk the small primitive cache records, not game objects. This also covers SRG-renamed fields.
        collect(type, null, type.getName() + "$", Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    }
    private void collect(Class<?> type, Object owner, String prefix, Set<Object> visited) throws IllegalAccessException {
        if (owner != null && !visited.add(owner)) return;
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || (owner == null) != Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType().isPrimitive()) {
                if (!Modifier.isFinal(field.getModifiers())) slots.add(new Slot(field, owner));
            } else {
                Object child = field.get(owner); if (child == null) continue;
                if (child.getClass().isArray() && !child.getClass().getComponentType().isPrimitive()) {
                    for (Object item : (Object[])child)
                        if (item != null && item.getClass().getName().startsWith(prefix)) collect(item.getClass(), item, prefix, visited);
                } else if (child.getClass().getName().startsWith(prefix)) collect(child.getClass(), child, prefix, visited);
            }
        }
    }
    public Snapshot save() throws IllegalAccessException {
        Object[] values = new Object[slots.size()];
        for (int i = 0; i < values.length; i++) values[i] = slots.get(i).field.get(slots.get(i).owner);
        return new Snapshot(values);
    }
    public void prepare() throws ReflectiveOperationException {
        // Force cache and GL to agree after the raw-GL mask/composite pass, including cached texture bindings.
        activeTexture.invoke(null, GL13.GL_TEXTURE1); activeTexture.invoke(null, GL13.GL_TEXTURE0);
        bindTexture.invoke(null, 0); disableTexture.invoke(null); enableTexture.invoke(null);
        enableAlpha.invoke(null); disableAlpha.invoke(null); resetColor.invoke(null);
    }
    public final class Snapshot implements AutoCloseable {
        private final Object[] values;
        Snapshot(Object[] values) { this.values = values; }
        @Override public void close() throws IllegalAccessException {
            for (int i = 0; i < values.length; i++) slots.get(i).field.set(slots.get(i).owner, values[i]);
        }
    }
    private static Method method(Class<?> type, String mcp, String srg, Class<?>... args) throws NoSuchMethodException {
        for (String name : new String[]{mcp, srg}) try {
            Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(type.getName() + "." + mcp);
    }
    private static final class Slot {
        final Field field; final Object owner;
        Slot(Field field, Object owner) { this.field = field; this.owner = owner; }
    }
}
