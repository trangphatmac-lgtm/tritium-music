package tritium.desktop.mcp;

import org.junit.jupiter.api.Test;
import tritium.desktop.MusicPreferences;
import tritium.i18n.I18n;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class McpServiceTest {
    @Test void handlesRapidTogglesPortChangesAndShutdown() throws Exception {
        int first = freePort();
        var preferences = new MusicPreferences();
        var service = new McpService();
        preferences.mcpPort().setValue((double) first);
        preferences.setChangeCallback(() -> service.reconcile(preferences));
        try {
            service.reconcile(preferences);
            assertEquals(I18n.text("mcp.stopped"), service.status());
            preferences.mcpEnabled().setValue(true);
            await(() -> service.status().contains("127.0.0.1:" + first));
            int second = freePort();
            preferences.mcpEnabled().setValue(false);
            preferences.mcpEnabled().setValue(true);
            await(() -> service.status().contains("127.0.0.1:" + first));
            preferences.mcpPort().setValue((double) second);
            await(() -> service.status().contains("127.0.0.1:" + second));
            await(() -> canBind(first));
            preferences.mcpEnabled().setValue(false);
            await(() -> canBind(second));
            assertEquals(I18n.text("mcp.stopped"), service.status());
        } finally { service.close(); }
    }

    @Test void reportsOccupiedPortAndRecoversWhenReenabled() throws Exception {
        var preferences = new MusicPreferences();
        var service = new McpService();
        int port;
        try (var occupied = socket(0)) {
            port = occupied.getLocalPort();
            preferences.mcpPort().setValue((double) port);
            preferences.mcpEnabled().setValue(true);
            service.reconcile(preferences);
            await(() -> service.status().startsWith(I18n.text("mcp.failed", "")));
        }
        try {
            preferences.mcpEnabled().setValue(false);
            service.reconcile(preferences);
            preferences.mcpEnabled().setValue(true);
            service.reconcile(preferences);
            await(() -> service.status().contains("127.0.0.1:" + port));
        } finally {
            service.close();
            await(() -> canBind(port));
        }
    }

    private static ServerSocket socket(int port) throws Exception {
        var socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        return socket;
    }
    private static int freePort() throws Exception {
        try (var socket = socket(0)) { return socket.getLocalPort(); }
    }
    private static boolean canBind(int port) {
        try (var socket = socket(port)) { return true; }
        catch (Exception exception) { return false; }
    }
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(5_000_000);
        assertTrue(condition.getAsBoolean(), "Service did not reach the expected state");
    }
}
