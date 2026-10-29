package tritium.minecraft.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.jar.*;

/** Loaded by Attach into the target JVM. The runtime itself lives under the game's loader. */
public final class HudAgent {
    private static Object running;
    private static URLClassLoader runtimeLoader;
    private static HudTransformer transformer;
    private static CombatTransformer combatTransformer;
    private static MovementTransformer movementTransformer;
    private static Class<?>[] targets;
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
                        LinkedHashSet<String> names = new LinkedHashSet<String>(Arrays.asList(HudTransformer.TARGETS));
                        names.addAll(Arrays.asList(CombatTransformer.TARGETS));
                        names.addAll(Arrays.asList(MovementTransformer.TARGETS));
                        String[] targetNames = names.toArray(new String[0]);
                        targets = new Class<?>[targetNames.length];
                        for (int i = 0; i < targets.length; i++) {
                            targets[i] = Class.forName(targetNames[i], false, chosen);
                            if (!instrumentation.isModifiableClass(targets[i])) throw new IllegalStateException("Class is not modifiable: " + targets[i].getName());
                        }
                        transformer = new HudTransformer(chosen);
                        bridge.set(null, runtime);
                        for (String field : new String[]{"packetCallback", "velocityCallback", "blockingCallback", "receiveCallback"}) bridge.getDeclaringClass().getField(field).set(null, runtime.getClass().getMethod(field).invoke(runtime));
                        combatTransformer = new CombatTransformer(chosen);
                        movementTransformer = new MovementTransformer(chosen);
                        instrumentation.addTransformer(transformer, true);
                        instrumentation.addTransformer(combatTransformer, true);
                        instrumentation.addTransformer(movementTransformer, true);
                        instrumentation.retransformClasses(targets);
                        if (!movementTransformer.complete()) throw new IllegalStateException("Movement hook installation failed: " + movementTransformer.error());
                        if (!combatTransformer.complete()) throw new IllegalStateException("Combat hook installation failed: " + combatTransformer.error());
                        if (!transformer.complete()) throw new IllegalStateException("Hook installation failed: " + transformer.error());
                    }
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            Runnable uninstall = () -> uninstall(instrumentation);
            runtime.getClass().getMethod("start", Runnable.class, Runnable.class).invoke(runtime, install, uninstall);
        } catch (Throwable failure) {
            uninstall(instrumentation);
            throw new IllegalStateException("Cannot initialize HUD agent", failure);
        }
    }

    private static synchronized void uninstall(Instrumentation instrumentation) {
        dirty = false;
        try {
            if (bridge != null) {
                bridge.set(null, null);
                for (String field : new String[]{"packetCallback", "velocityCallback", "blockingCallback", "receiveCallback"}) bridge.getDeclaringClass().getField(field).set(null, null);
            }
            if (movementTransformer != null) {
                movementTransformer.deactivate(); instrumentation.removeTransformer(movementTransformer);
            }
            if (combatTransformer != null) {
                combatTransformer.deactivate(); instrumentation.removeTransformer(combatTransformer);
            }
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
            transformer = null; combatTransformer = null; movementTransformer = null; targets = null; running = null;
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
        } catch (ReflectiveOperationException oldBridge) {
            throw new IllegalStateException("Restart Minecraft before using this agent version; its previous callback bridge does not support the current module hooks", oldBridge);
        }
        bridge = bridgeClass.getField("callback");
    }
    private HudAgent() { }
}
