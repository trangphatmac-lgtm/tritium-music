package tritium.desktop.minecraft;

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import tritium.minecraft.protocol.*;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Attach across platforms/JDKs; the fixture never substitutes for Forge/Lunar acceptance. */
@EnabledIfSystemProperty(named = "minecraft.testAttach", matches = "true")
class AgentAttachFixtureTest {
    @Test void attachesCurrentJavaAndJava8RepeatsAndPreservesOtherTransformer() throws Exception {
        Path agent = Path.of("minecraft-agent/target/minecraft-hud-agent.jar").toAbsolutePath();
        Path fixture = compileFixture(agent);
        String java8 = System.getenv("JAVA8_HOME"); assertNotNull(java8, "Set JAVA8_HOME to a Java 8 installation");
        for (Path home : List.of(Path.of(System.getProperty("java.home")), Path.of(java8))) {
            runTarget(home, agent, fixture, List.of(), false);
            runTarget(home, agent, fixture, List.of("-XX:+DisableAttachMechanism"), true);
        }
        runTarget(Path.of(System.getProperty("java.home")), agent, fixture, List.of("-XX:-EnableDynamicAgentLoading"), true);
    }
    private static Path compileFixture(Path agent) throws Exception {
        Path classes = Path.of("target/minecraft-fixture/classes"); Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("--release", "8", "-proc:none", "-encoding", "UTF-8", "-cp", agent + File.pathSeparator + System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var paths = Files.walk(Path.of("src/test/minecraft-fixture/java"))) { paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString())); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Path jar = Path.of("target/minecraft-fixture/fixture.jar"); Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Agent-Class", "fixtures.OtherAgent");
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest); var paths = Files.walk(classes)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) { out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/'))); Files.copy(p,out); out.closeEntry(); }
        }
        return jar.toAbsolutePath();
    }
    private static void runTarget(Path home, Path agent, Path fixture, List<String> flags, boolean rejected) throws Exception {
        Path lwjgl = Path.of(System.getProperty("user.home"), ".m2/repository/org/lwjgl/lwjgl/2.9.4-nightly/lwjgl-2.9.4-nightly.jar");
        Path java = home.resolve("bin").resolve(com.sun.jna.Platform.isWindows() ? "java.exe" : "java");
        assertTrue(Files.isRegularFile(java), "Missing target JVM: " + java);
        List<String> command = new ArrayList<>(); command.add(java.toString()); command.addAll(flags);
        command.add("-XX:-UsePerfData");
        command.addAll(List.of("-cp", fixture + File.pathSeparator + agent + File.pathSeparator + lwjgl, "net.minecraft.launchwrapper.Launch"));
        Path errorLog = Path.of("target/minecraft-fixture/" + home.getFileName() + "-" + Math.abs(flags.hashCode()) + ".log");
        Process process = new ProcessBuilder(command).redirectError(errorLog.toFile()).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        BufferedReader stdout = process.inputReader(); BufferedWriter stdin = process.outputWriter();
        try {
            assertTrue(reader.submit(stdout::readLine).get(15, TimeUnit.SECONDS).startsWith("FIXTURE"));
            assertFalse(VirtualMachine.list().stream().anyMatch(vm -> vm.id().equals(Long.toString(process.pid()))), "Fixture must reproduce the missing Attach-list entry");
            assertTrue(MinecraftConnectionManager.discover().stream().anyMatch(c -> c.pid().equals(Long.toString(process.pid()))), "Discover the JVM even with unavailable perf data");
            if (rejected) {
                Throwable failure = assertThrows(Throwable.class, () -> load(process.pid(), agent, "1:1:" + "a".repeat(64)));
                assertTrue(failure instanceof com.sun.tools.attach.AttachNotSupportedException
                        || failure instanceof com.sun.tools.attach.AgentLoadException
                        || failure instanceof com.sun.tools.attach.AgentInitializationException
                        || failure instanceof IOException
                        || failure instanceof InternalError && failure.getMessage().startsWith("Remote thread failed"), failure.toString());
                assertTrue(process.isAlive(), "A rejected Attach must not terminate the target");
                return;
            }
            load(process.pid(), fixture, "");
            assertThrows(Exception.class, () -> load(process.pid(), agent.resolveSibling("missing-agent.jar"), ""));
            assertThrows(Exception.class, () -> load(process.pid(), agent, "invalid session options"));
            for (int cycle = 0; cycle < 3; cycle++) {
                try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    server.setSoTimeout(10000); String token = "a".repeat(64);
                    load(process.pid(), agent, "1:" + server.getLocalPort() + ":" + token);
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(7000); Wire wire = new Wire(socket.getInputStream(),socket.getOutputStream());
                        assertEquals(Wire.HELLO, wire.read().type);
                        boolean installed = false;
                        for (int i = 0; i < 30 && !installed; i++) {
                            Wire.Frame frame = wire.read(); assertNotEquals(Wire.ERROR, frame.type, frame.text());
                            assertNotEquals(Wire.READY, frame.type, "No rendering callback means no READY");
                            installed = frame.type == Wire.PROGRESS && frame.text().equals("render");
                        }
                        assertTrue(installed);
                        awaitTransformers(wire, stdin, stdout, reader, false);
                        wire.send(Wire.CONFIG, "{}");
                        assertHudOnlyHooks(stdin, stdout, reader);
                        AttachLoader.StartupException duplicate = assertThrows(AttachLoader.StartupException.class,
                                () -> load(process.pid(), agent, "1:" + server.getLocalPort() + ":" + token));
                        assertTrue(duplicate.getMessage().contains("already attached"), duplicate.getMessage());
                        for (int toggle = 0; toggle < cycle; toggle++) {
                            wire.send(Wire.CONFIG, "{\"hidden\":true}");
                            awaitTransformers(wire, stdin, stdout, reader, true);
                            stdin.write("openb4\nincoming\nbridgeassist\n"); stdin.flush();
                            assertEquals("OPENB4 18 13 true", reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS), "All OpenB4 hooks must be installed, including render exception cleanup");
                            assertEquals("INCOMING 5", reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS), "Every packet handler must intercept, including handlers without a thread handoff");
                            assertEquals("BRIDGEASSIST 12 0.3 0 3 2 true 3 [RGBARGBA] [filtered:normal:0;filtered:system:1;]", reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS), "Input precedes sneak scaling; synthetic clicks and only scroll changes cancel; rotations bracket every raycast; chat follows Forge filtering");
                            // A repeated enabled config must not install duplicate transformations.
                            wire.send(Wire.CONFIG, "{\"hidden\":true,\"accent\":123}");
                            wire.send(Wire.CONFIG, "{\"hidden\":false}");
                            awaitTransformers(wire, stdin, stdout, reader, false);
                            assertHudOnlyHooks(stdin, stdout, reader);
                        }
                        if (cycle == 2) {
                            wire.send(Wire.CONFIG, "{\"hidden\":true}");
                            awaitTransformers(wire, stdin, stdout, reader, true);
                        }
                        wire.send(Wire.UNINJECT, "");
                        boolean stopped = false;
                        for (int i = 0; i < 30 && !stopped; i++) {
                            Wire.Frame frame = wire.read(); assertNotEquals(Wire.READY, frame.type);
                            assertNotEquals(Wire.ERROR, frame.type, frame.text());
                            if (frame.type == Wire.STOPPED) { assertEquals("clean", frame.text()); stopped = true; }
                        }
                        assertTrue(stopped);
                    }
                }
                stdin.write("marker\nbridge\ncombat\nmovement\nopenb4\nincoming\nbridgeassist\n"); stdin.flush();
                assertEquals("MARKER 31", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS));
                assertEquals("BRIDGE true", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS));
                assertEquals("COMBAT 0", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS), "UnInject must remove combat hooks, not only clear their callbacks");
                assertEquals("MOVEMENT 0", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS), "UnInject must also remove movement input and receive hooks");
                assertEquals("OPENB4 0 0 true", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS), "UnInject must restore every new module hook, including exception paths");
                assertEquals("INCOMING 0", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS), "UnInject must restore generic incoming packet handlers");
                assertEquals("BRIDGEASSIST 0 1.0 1 5 3 true 3 [] []", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS), "UnInject must remove input, synthetic click, scroll, rotation and filtered chat hooks");
            }
        } finally {
            stdin.write("exit\n"); stdin.flush(); if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly(); reader.shutdownNow();
        }
    }
    private static void awaitTransformers(Wire wire, BufferedWriter stdin, BufferedReader stdout, ExecutorService reader, boolean hidden) throws Exception {
        String expected = hidden ? "TRANSFORMERS 4 13" : "TRANSFORMERS 1 2";
        String actual;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            wire.send(Wire.HEARTBEAT, "");
            stdin.write("transformers\n"); stdin.flush();
            actual = reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS);
            if (expected.equals(actual)) return;
            if ("TRANSFORMERS 0 0".equals(actual)) {
                for (int i = 0; i < 30; i++) {
                    Wire.Frame frame = wire.read();
                    assertNotEquals(Wire.ERROR, frame.type, frame.text());
                    assertNotEquals(Wire.STOPPED, frame.type, "Session stopped while updating Hidden: " + frame.text());
                }
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual, "Hidden must control transformer instances and the retransformation target set");
    }
    private static void assertHudOnlyHooks(BufferedWriter stdin, BufferedReader stdout, ExecutorService reader) throws Exception {
        stdin.write("hud\ncombat\nmovement\nopenb4\nincoming\nbridgeassist\nmarker\n"); stdin.flush();
        for (String expected : List.of("HUD 3", "COMBAT 0", "MOVEMENT 0", "OPENB4 0 0 true", "INCOMING 0",
                "BRIDGEASSIST 0 1.0 1 5 3 true 3 [] []", "MARKER 31"))
            assertEquals(expected, reader.submit(stdout::readLine).get(5, TimeUnit.SECONDS),
                    "Hidden off must preserve HUD/world lyrics and other agents while removing every optional hook");
    }
    private static void load(long pid, Path agent, String options) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try { AttachLoader.load(vm, agent.toString(), options); } finally { vm.detach(); }
    }
}
