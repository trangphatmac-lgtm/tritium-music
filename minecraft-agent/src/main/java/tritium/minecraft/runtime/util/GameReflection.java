package tritium.minecraft.runtime.util;

import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reusable, thread-safe MCP/SRG reflection cache owned by an injected session. */
public class GameReflection {
    private final Map<String, Field> fields = new ConcurrentHashMap<String, Field>();
    private final Map<String, Method> methods = new ConcurrentHashMap<String, Method>();
    private final Map<String, Class<?>> types = new ConcurrentHashMap<String, Class<?>>();
    protected final ClassLoader loader;
    protected GameReflection(ClassLoader loader) { this.loader = loader; }
    protected boolean is(Object object, String name) { return object != null && type(name).isInstance(object); }
    protected Class<?> type(String name) {
        String full = "net.minecraft." + name; Class<?> type = types.get(full);
        if (type == null) try { type = Class.forName(full, false, loader); types.put(full, type); }
        catch (ClassNotFoundException e) { throw failure(e); }
        return type;
    }
    protected Object get(Object object, String names) { try { return field(object, names).get(object instanceof Class ? null : object); } catch (IllegalAccessException e) { throw failure(e); } }
    protected void set(Object object, String names, Object value) { try { field(object, names).set(object instanceof Class ? null : object, value); } catch (IllegalAccessException e) { throw failure(e); } }
    private Field field(Object object, String names) {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass(); String key = type.getName() + ":" + names;
        Field cached = fields.get(key); if (cached != null) return cached;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (String name : names.split("/")) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); fields.put(key, f); return f;
        } catch (NoSuchFieldException ignored) { }
        throw new IllegalStateException("Missing Minecraft field " + key);
    }
    protected Object call(Object object, String names, Object... args) {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass(); StringBuilder key = new StringBuilder(type.getName()).append(':').append(names);
        for (Object arg : args) key.append(':').append(arg == null ? "null" : arg.getClass().getName());
        Method found = methods.get(key.toString());
        if (found == null) {
            outer: for (Class<?> c = type; c != null; c = c.getSuperclass()) for (String name : names.split("/")) for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) { found = m; break outer; }
            }
            if (found == null) throw new IllegalStateException("Missing Minecraft method " + key);
            found.setAccessible(true); methods.put(key.toString(), found);
        }
        try { return found.invoke(object instanceof Class ? null : object, args); }
        catch (ReflectiveOperationException e) { throw failure(e); }
    }
    protected Object construct(String name, Object... args) {
        try { for (Constructor<?> c : type(name).getConstructors()) if (compatible(c.getParameterTypes(), args)) return c.newInstance(args); }
        catch (ReflectiveOperationException e) { throw failure(e); }
        throw new IllegalStateException("Missing Minecraft constructor " + name);
    }
    private static boolean compatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (args[i] == null && t.isPrimitive()) return false;
            if (t == int.class) t = Integer.class; if (t == float.class) t = Float.class;
            if (t == double.class) t = Double.class; if (t == boolean.class) t = Boolean.class;
            if (t == long.class) t = Long.class; if (t == char.class) t = Character.class;
            if (t == short.class) t = Short.class; if (t == byte.class) t = Byte.class;
            if (args[i] != null && !t.isInstance(args[i])) return false;
        }
        return true;
    }
    protected static IllegalStateException failure(Exception e) { return new IllegalStateException("Minecraft access failed", e); }
    protected static boolean bool(Object v) { return Boolean.TRUE.equals(v); }
    protected static Number number(Object v) { return (Number) v; }
    protected static int integer(Object v) { return number(v).intValue(); }
    protected static double square(double v) { return v*v; }
}
