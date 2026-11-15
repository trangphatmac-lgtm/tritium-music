package tritium.minecraft.runtime.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tritium.minecraft.protocol.Wire;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSessionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schedulingNeverHoldsSessionWhileWaitingForGameTaskQueue(boolean legacyReceive) throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService threads = Executors.newFixedThreadPool(2);
            Future<Boolean> network = null;
            try {
                // Minecraft 1.8.9 runs queued callbacks while holding this queue's monitor.
                synchronized (f.client.tasks) {
                    network = threads.submit(() -> legacyReceive
                            ? f.session.receiveCallback().test(new Object())
                            : f.session.incomingCallback().test(f.client.handler, new Object()));
                    assertTrue(f.client.scheduling.await(5, TimeUnit.SECONDS));
                    Future<Boolean> game = threads.submit(() -> {
                        f.client.gameThread = Thread.currentThread();
                        return f.session.incomingCallback().test(f.client.handler, new Object());
                    });
                    assertFalse(game.get(5, TimeUnit.SECONDS),
                            "Game callbacks must finish while Netty waits for the task queue");
                }
                assertTrue(network.get(5, TimeUnit.SECONDS));
                assertEquals(1, f.client.tasks.size());
                assertFalse(f.client.scheduledWithSessionLock);
            } finally {
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void scheduledPacketsReenterOnGameThreadOnceAndInOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            S47PacketPlayerListHeaderFooter first = new S47PacketPlayerListHeaderFooter(f.session);
            S47PacketPlayerListHeaderFooter second = new S47PacketPlayerListHeaderFooter(f.session);
            assertTrue(f.session.incoming(f.client.handler, first));
            assertTrue(f.session.receive(second));
            assertEquals(0, first.processed);
            f.client.gameThread = Thread.currentThread();
            f.client.tasks.remove().run();
            assertEquals(1, first.processed);
            assertEquals(0, second.processed);
            f.client.tasks.remove().run();
            assertEquals(1, second.processed);
            assertSame(Thread.currentThread(), first.thread);
            assertTrue(f.client.tasks.isEmpty());
        }
    }

    @Test
    void queuedPacketsAreDiscardedAfterHandlerChangesOrSessionStops() throws Exception {
        try (Fixture f = new Fixture()) {
            S47PacketPlayerListHeaderFooter old = new S47PacketPlayerListHeaderFooter(f.session);
            assertTrue(f.session.incoming(f.client.handler, old));
            f.client.handler = new Object();
            f.client.gameThread = Thread.currentThread();
            f.client.tasks.remove().run();
            assertEquals(0, old.processed);

            f.client.gameThread = null;
            S47PacketPlayerListHeaderFooter stopped = new S47PacketPlayerListHeaderFooter(f.session);
            assertTrue(f.session.receive(stopped));
            set(f.session, "stop", true);
            set(f.session, "hooks", null);
            f.client.gameThread = Thread.currentThread();
            f.client.tasks.remove().run();
            assertEquals(0, stopped.processed);
            assertFalse(f.session.receive(new Object()));
        }
    }

    @Test
    void vanillaHandoffRemainsAvailableWhenReceiveModulesAreDisabled() throws Exception {
        try (Fixture f = new Fixture()) {
            f.combat.runtime.disable("hitSelect");
            assertFalse(f.session.incoming(f.client.handler, new Object()));
            assertFalse(f.session.receive(new Object()));
            assertTrue(f.client.tasks.isEmpty());
        }
    }

    private static void set(RuntimeSession session, String name, Object value) throws Exception {
        Field field = RuntimeSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static final class Fixture implements AutoCloseable {
        final ExtendedCombatRuntimeTest.Fixture combat = new ExtendedCombatRuntimeTest.Fixture();
        final RuntimeSession session = new RuntimeSession(Wire.VERSION + ":1:"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                getClass().getClassLoader());
        final Client client = new Client(session);

        Fixture() throws Exception {
            combat.enable("hitSelect");
            set(session, "hooks", new RuntimeHooksAccess(client));
            set(session, "extended", combat.runtime);
        }

        @Override public void close() { combat.close(); }
    }

    static final class Client {
        final RuntimeSession session;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
        final CountDownLatch scheduling = new CountDownLatch(1);
        volatile Thread gameThread;
        Object handler = new Object();
        boolean scheduledWithSessionLock;

        Client(RuntimeSession session) { this.session = session; }
        public boolean isCallingFromMinecraftThread() { return Thread.currentThread() == gameThread; }
        public Object getNetHandler() { return handler; }
        public void addScheduledTask(Runnable action) {
            scheduledWithSessionLock = Thread.holdsLock(session);
            scheduling.countDown();
            synchronized (tasks) { tasks.add(action); }
        }
    }

    static final class S47PacketPlayerListHeaderFooter {
        final RuntimeSession session;
        int processed;
        Thread thread;

        S47PacketPlayerListHeaderFooter(RuntimeSession session) { this.session = session; }
        public void processPacket(Object handler) {
            if (!session.incomingCallback().test(handler, this)) {
                processed++;
                thread = Thread.currentThread();
            }
        }
    }
}
