package tritium.desktop.minecraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tritium.minecraft.protocol.Wire;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static tritium.desktop.minecraft.MinecraftConnectionManager.Stage.*;

class MinecraftConnectionTimeoutTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final MinecraftConnectionManager.Candidate TARGET =
            new MinecraftConnectionManager.Candidate("123", "Minecraft / Forge");
    private final AtomicLong now = new AtomicLong();
    private final QueuedExecutor workers = new QueuedExecutor();
    private final MinecraftConnectionManager manager = new MinecraftConnectionManager(now::get, workers);

    @AfterEach void close() throws Exception {
        manager.close();
        // Invalidate this fixture's queued UI callbacks if another test later pumps the main thread.
        ((AtomicLong) field(manager, "generation").get(manager)).incrementAndGet();
    }

    @Test void longSelectionWaitLeavesAFullConnectionBudget() throws Exception {
        selecting(1);
        now.set(46 * SECOND);
        watchdog();
        assertEquals(SELECTING, manager.status().stage());
        assertTrue(workers.tasks.isEmpty());

        manager.select(TARGET);
        assertEquals(1, workers.tasks.size());
        now.set(47 * SECOND);
        watchdog();
        assertEquals(ATTACHING, manager.status().stage());

        now.set(91 * SECOND); // Exactly 45 seconds after selection: preserve the strict > boundary.
        watchdog();
        assertEquals(ATTACHING, manager.status().stage());
        now.incrementAndGet();
        watchdog();
        assertEquals(FAILED, manager.status().stage());
        assertTrue(manager.status().detail().contains("READY"));
        assertNull(field(manager, "session").get(manager));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100000000000L})
    void zeroAndNegativeMonotonicClockOriginsStillTimeOut(long start) throws Exception {
        now.set(start);
        selecting(1);
        manager.select(TARGET);
        now.addAndGet(45 * SECOND);
        watchdog();
        assertEquals(ATTACHING, manager.status().stage());
        now.incrementAndGet();
        watchdog();
        assertEquals(FAILED, manager.status().stage());
    }

    @Test void repeatedSelectionDoesNotExtendTheDeadline() throws Exception {
        Object session = selecting(1);
        manager.select(TARGET);
        now.set(44 * SECOND);
        manager.select(new MinecraftConnectionManager.Candidate("456", "Lunar"));
        assertSame(TARGET, field(session, "target").get(session));
        assertEquals(1, workers.tasks.size());
        now.set(45 * SECOND + 1);
        watchdog();
        assertEquals(FAILED, manager.status().stage());
    }

    @Test void readyAfterLongSelectionWaitUsesTheFiveSecondHeartbeatBudget() throws Exception {
        Object session = selecting(1);
        now.set(46 * SECOND);
        manager.select(TARGET);
        now.set(47 * SECOND);
        // Model the state committed by the main-thread READY callback, without starting a game or UI.
        set(session, "ready", true);
        set(manager, "connected", true);
        set(manager, "status", new MinecraftConnectionManager.Status(READY, 1, "", List.of()));
        set(session, "wire", new Wire(InputStream.nullInputStream(), OutputStream.nullOutputStream()));

        now.set(100 * SECOND); // The original connection deadline no longer applies after READY.
        set(session, "lastReceive", now.get());
        watchdog();
        assertEquals(READY, manager.status().stage());
        assertTrue(manager.connected());
        now.addAndGet(5 * SECOND);
        watchdog();
        assertEquals(READY, manager.status().stage());
        now.incrementAndGet();
        watchdog();
        assertEquals(FAILED, manager.status().stage());
        assertTrue(manager.status().detail().contains("心跳"));
        assertFalse(manager.connected());
    }

    @Test void stoppingKeepsItsOwnTenSecondCleanupBudget() throws Exception {
        selecting(1);
        now.set(46 * SECOND);
        manager.select(TARGET);
        now.set(90 * SECOND);
        assertFalse(manager.settingsControlsHidden());
        manager.uninject();
        assertTrue(manager.settingsControlsHidden());
        now.set(100 * SECOND); // Already past the connection deadline, but exactly 10 seconds into stop.
        watchdog();
        assertEquals(STOPPING, manager.status().stage());
        now.incrementAndGet();
        watchdog();
        assertEquals(FAILED, manager.status().stage());
        assertTrue(manager.status().detail().contains("Hook"));
    }

    @Test void finishedSessionCannotBeSelectedOrTimedOutAgain() throws Exception {
        Object session = selecting(1);
        now.set(46 * SECOND);
        manager.select(TARGET);
        finish(session, "");
        now.set(200 * SECOND);
        watchdog();
        manager.select(TARGET);
        assertEquals(IDLE, manager.status().stage());
        assertNull(field(manager, "session").get(manager));
        assertEquals(1, workers.tasks.size());
    }

    @Test void staleSessionCompletionCannotFailAReplacementSession() throws Exception {
        Object oldSession = selecting(1);
        manager.select(TARGET);
        finish(oldSession, "");
        Object currentSession = selecting(2);
        now.set(100 * SECOND);
        manager.select(TARGET);
        finish(oldSession, "late Attach failure");
        now.set(101 * SECOND);
        watchdog();
        assertSame(currentSession, field(manager, "session").get(manager));
        assertEquals(ATTACHING, manager.status().stage());
    }

    @Test void closingWhileSelectingPreventsLaterAttachAndTimeout() throws Exception {
        selecting(1);
        manager.close();
        now.set(100 * SECOND);
        manager.select(TARGET);
        watchdog();
        assertNull(field(manager, "session").get(manager));
        assertTrue(workers.tasks.isEmpty());
        assertFalse(manager.connected());
        assertNotEquals(FAILED, manager.status().stage());
    }

    // Install the state produced by multi-process discovery; never run discovery, Attach or native UI.
    private Object selecting(long id) throws Exception {
        Class<?> type = Class.forName(MinecraftConnectionManager.class.getName() + "$Session");
        var constructor = type.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(id);
        set(manager, "session", session);
        ((AtomicLong) field(manager, "generation").get(manager)).set(id);
        set(manager, "status", new MinecraftConnectionManager.Status(SELECTING, .12, "",
                List.of(TARGET, new MinecraftConnectionManager.Candidate("456", "Lunar"))));
        return session;
    }

    private void watchdog() throws Exception {
        var method = MinecraftConnectionManager.class.getDeclaredMethod("watchdog");
        method.setAccessible(true);
        method.invoke(manager);
    }

    private void finish(Object session, String error) throws Exception {
        var method = MinecraftConnectionManager.class.getDeclaredMethod("finish", session.getClass(), String.class);
        method.setAccessible(true);
        method.invoke(manager, session, error);
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        field(object, name).set(object, value);
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;
        @Override public void execute(Runnable command) { tasks.add(command); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown(); return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
