package tritium.minecraft.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;

/** Loaded by Attach into the target JVM. The runtime itself lives under the game's loader. */
public final class HudAgent {
    private static Object running;
    private static URLClassLoader runtimeLoader;
    private static HudTransformer transformer;
    private static CombatTransformer combatTransformer;
    private static MovementTransformer movementTransformer;
    private static OpenB4Transformer openB4Transformer;
    private static Class<?>[] targets;
    private static Class<?>[] hudTargets;
    private static Field bridge;
    private static boolean dirty;

    public static synchronized void agentmain(final String options, final Instrumentation instrumentation) throws Exception {
        if (running != null || dirty) throw new IllegalStateException("Tritium is already attached or cleanup is incomplete");
        if (!instrumentation.isRetransformClassesSupported()) throw new IllegalStateException("JVM does not support class retransformation");
        ClassLoader gameLoader = null;
        for (Class<?> type : instrumentation.getAllLoadedClasses())
            if (type.getName().equals("net.minecraft.client.Minecraft")) { gameLoader = type.getClassLoader(); break; }
        if (gameLoader == null) throw new IllegalStateException("Minecraft has not reached its main menu");
        installBridge(instrumentation);
        if (Class.forName("tritium.minecraft.bridge.HookBridge", false, gameLoader) != bridge.getDeclaringClass())
            throw new IllegalStateException("Game class loader cannot see the callback bridge");
        URL jar = HudAgent.class.getProtectionDomain().getCodeSource().getLocation();
        runtimeLoader = new URLClassLoader(new URL[]{jar}, gameLoader) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("tritium.minecraft.runtime.") || name.startsWith("tritium.minecraft.protocol.")
                        || name.startsWith("tritium.minecraft.internal.")) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            final ClassLoader chosen = gameLoader;
            final Object runtime = Class.forName("tritium.minecraft.runtime.hook.RuntimeSession", true, runtimeLoader)
                    .getConstructor(String.class, ClassLoader.class).newInstance(options, gameLoader);
            running = runtime;
            Runnable install = () -> {
                try {
                    synchronized (HudAgent.class) {
                        targets = hudTargets = resolveTargets(instrumentation, chosen, Arrays.asList(HudTransformer.TARGETS));
                        transformer = new HudTransformer(chosen);
                        bridge.set(null, runtime);
                        instrumentation.addTransformer(transformer, true);
                        instrumentation.retransformClasses(targets);
                        if (!transformer.complete()) throw new IllegalStateException("Hook installation failed: " + transformer.error());
                    }
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            Consumer<Boolean> hidden = enabled -> configureHidden(instrumentation, chosen, runtime, enabled);
            Runnable uninstall = () -> uninstall(instrumentation);
            runtime.getClass().getMethod("start", Runnable.class, Consumer.class, Runnable.class).invoke(runtime, install, hidden, uninstall);
        } catch (Throwable failure) {
            uninstall(instrumentation);
            throw new IllegalStateException("Cannot initialize HUD agent", failure);
        }
    }

    private static Class<?>[] resolveTargets(Instrumentation instrumentation, ClassLoader loader, Collection<String> names) throws Exception {
        Class<?>[] resolved = new Class<?>[names.size()];
        int i = 0;
        for (String name : names) {
            Class<?> type = Class.forName(name, false, loader);
            if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("Class is not modifiable: " + name);
            resolved[i++] = type;
        }
        return resolved;
    }

    /** Invoked by the IPC worker before publishing a config to the game thread. */
    private static synchronized void configureHidden(Instrumentation instrumentation, ClassLoader loader, Object runtime, boolean enabled) {
        if (enabled == (combatTransformer != null)) return;
        try {
            if (enabled) {
                LinkedHashSet<String> names = new LinkedHashSet<String>(Arrays.asList(HudTransformer.TARGETS));
                names.addAll(Arrays.asList(CombatTransformer.TARGETS));
                names.addAll(Arrays.asList(MovementTransformer.TARGETS));
                names.addAll(Arrays.asList(OpenB4Transformer.TARGETS));
                targets = resolveTargets(instrumentation, loader, names);
                for (String field : new String[]{"packetCallback", "velocityCallback", "blockingCallback", "receiveCallback", "incomingCallback", "moduleCallback"})
                    bridge.getDeclaringClass().getField(field).set(null, runtime.getClass().getMethod(field).invoke(runtime));
                combatTransformer = new CombatTransformer(loader);
                movementTransformer = new MovementTransformer(loader);
                openB4Transformer = new OpenB4Transformer(loader);
                instrumentation.addTransformer(combatTransformer, true);
                instrumentation.addTransformer(movementTransformer, true);
                instrumentation.addTransformer(openB4Transformer, true);
                instrumentation.retransformClasses(targets);
                if (!openB4Transformer.complete()) throw new IllegalStateException("OpenB4 hook installation failed: " + openB4Transformer.error());
                if (!movementTransformer.complete()) throw new IllegalStateException("Movement hook installation failed: " + movementTransformer.error());
                if (!combatTransformer.complete()) throw new IllegalStateException("Combat hook installation failed: " + combatTransformer.error());
                if (!transformer.complete()) throw new IllegalStateException("Hook installation failed: " + transformer.error());
            } else {
                removeHiddenTransformers(instrumentation);
                // Keep HUD and other agents' transformations; retain callbacks for in-flight methods.
                instrumentation.retransformClasses(targets);
                if (!transformer.complete()) throw new IllegalStateException("Hook installation failed: " + transformer.error());
                combatTransformer = null; movementTransformer = null; openB4Transformer = null;
                targets = hudTargets;
            }
        } catch (Exception failure) { throw new IllegalStateException("Cannot update Hidden hooks", failure); }
    }

    private static void removeHiddenTransformers(Instrumentation instrumentation) {
        if (openB4Transformer != null) {
            openB4Transformer.deactivate(); instrumentation.removeTransformer(openB4Transformer);
        }
        if (movementTransformer != null) {
            movementTransformer.deactivate(); instrumentation.removeTransformer(movementTransformer);
        }
        if (combatTransformer != null) {
            combatTransformer.deactivate(); instrumentation.removeTransformer(combatTransformer);
        }
    }

    private static synchronized void uninstall(Instrumentation instrumentation) {
        dirty = false;
        try {
            if (bridge != null) {
                bridge.set(null, null);
                for (String field : new String[]{"packetCallback", "velocityCallback", "blockingCallback", "receiveCallback", "incomingCallback", "moduleCallback"}) bridge.getDeclaringClass().getField(field).set(null, null);
            }
            removeHiddenTransformers(instrumentation);
            if (transformer != null) {
                transformer.deactivate();
                instrumentation.removeTransformer(transformer);
                // Replay the remaining transform chain, preserving Forge/Lunar/other agents.
                if (targets != null) instrumentation.retransformClasses(targets);
            }
        } catch (Throwable failure) {
            dirty = true;
            throw new IllegalStateException("HUD stopped; hook rollback incomplete. Restart Minecraft.", failure);
        } finally {
            transformer = null; combatTransformer = null; movementTransformer = null; openB4Transformer = null; targets = null; hudTargets = null; running = null;
            if (runtimeLoader != null) try { runtimeLoader.close(); } catch (IOException ignored) { }
            runtimeLoader = null;
        }
    }

    private static void installBridge(Instrumentation instrumentation) throws Exception {
        if (bridge != null) return;
        String resource = "tritium/minecraft/bridge/HookBridge.class";
        Path jar = Files.createTempFile("tritium-hud-bridge-", ".jar");
        jar.toFile().deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             InputStream in = HudAgent.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing callback bridge");
            out.putNextEntry(new JarEntry(resource));
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.closeEntry();
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar.toFile()));
        Class<?> bridgeClass = Class.forName("tritium.minecraft.bridge.HookBridge", true, null);
        try {
            bridgeClass.getMethod("world", float.class);
            bridgeClass.getField("partialTicks");
            bridgeClass.getMethod("packet", Object.class, Object.class);
            bridgeClass.getField("blockingCallback");
            bridgeClass.getField("receiveCallback");
            bridgeClass.getMethod("receive", Object.class);
            bridgeClass.getField("moduleCallback");
            bridgeClass.getMethod("incoming", Object.class, Object.class);
            bridgeClass.getMethod("headPitch", Object.class, float.class, float.class);
            bridgeClass.getMethod("preInput", Object.class);
            bridgeClass.getMethod("rightClick");
            bridgeClass.getMethod("slotScroll", Object.class, int.class);
            bridgeClass.getMethod("clientRotation");
            bridgeClass.getMethod("chat", Object.class, int.class);
            bridgeClass.getMethod("beforeMouseOver");
            bridgeClass.getMethod("afterMouseOver");
        } catch (ReflectiveOperationException oldBridge) {
            throw new IllegalStateException("Restart Minecraft before using this agent version; its previous callback bridge does not support the current module hooks", oldBridge);
        }
        bridge = bridgeClass.getField("callback");
    }
    private HudAgent() { }
}
