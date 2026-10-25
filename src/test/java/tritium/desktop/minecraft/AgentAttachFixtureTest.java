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
        List<String> args = new ArrayList<>(List.of("--release", "8", "-encoding", "UTF-8", "-cp", agent.toString(), "-d", classes.toString()));
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
                        AttachLoader.StartupException duplicate = assertThrows(AttachLoader.StartupException.class,
                                () -> load(process.pid(), agent, "1:" + server.getLocalPort() + ":" + token));
                        assertTrue(duplicate.getMessage().contains("already attached"), duplicate.getMessage());
                        wire.send(Wire.CONFIG,"{}"); wire.send(Wire.UNINJECT, "");
                        boolean stopped = false;
                        for (int i = 0; i < 30 && !stopped; i++) {
                            Wire.Frame frame = wire.read(); assertNotEquals(Wire.READY, frame.type);
                            if (frame.type == Wire.STOPPED) { assertEquals("clean", frame.text()); stopped = true; }
                        }
                        assertTrue(stopped);
                    }
                }
                stdin.write("marker\nbridge\n"); stdin.flush();
                assertEquals("MARKER 31", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS));
                assertEquals("BRIDGE true", reader.submit(stdout::readLine).get(5,TimeUnit.SECONDS));
            }
        } finally {
            stdin.write("exit\n"); stdin.flush(); if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly(); reader.shutdownNow();
        }
    }
    private static void load(long pid, Path agent, String options) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try { AttachLoader.load(vm, agent.toString(), options); } finally { vm.detach(); }
    }
}
