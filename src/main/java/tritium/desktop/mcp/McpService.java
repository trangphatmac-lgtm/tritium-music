package tritium.desktop.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import tritium.desktop.MusicPreferences;
import tritium.i18n.I18n;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owned by the desktop app. Preference callbacks and lifecycle calls run on its main thread. */
public final class McpService implements AutoCloseable {
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mcp-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private McpHttpServer server;
    private volatile String failure;
    private volatile String runningUrl;
    private volatile long revision;
    private boolean requestedEnabled;
    private int requestedPort = -1;
    private String clipboardStatus;
    private boolean closed;

    public void reconcile(MusicPreferences preferences) {
        request(preferences.mcpEnabled().getValue(), preferences.mcpPort().getValue().intValue());
    }

    private synchronized void request(boolean enabled, int port) {
        if (closed) return;
        if (requestedEnabled == enabled && requestedPort == port) return;
        requestedEnabled = enabled;
        requestedPort = port;
        long request = ++revision;
        failure = null;
        runningUrl = null;
        lifecycle.execute(() -> apply(request, enabled, port));
    }

    private void apply(long request, boolean enabled, int port) {
        if (revision != request) return;
        if (server != null) server.close();
        server = null;
        if (revision != request || !enabled) return;
        try {
            server = new McpHttpServer(port, new McpTools());
            if (revision == request) runningUrl = server.url();
        } catch (IOException | RuntimeException error) {
            if (revision == request) failure = java.util.Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            System.err.println("[MCP] Failed to start: " + error.getMessage());
        }
    }

    public synchronized String status() {
        if (failure != null) return I18n.text("mcp.failed", failure);
        if (runningUrl != null) return I18n.text("mcp.running", runningUrl);
        if (requestedEnabled) return I18n.text("mcp.starting");
        return I18n.text("mcp.stopped");
    }

    public static String clientConfiguration(int port) {
        JsonObject connection = new JsonObject();
        connection.addProperty("type", "http");
        connection.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
        JsonObject servers = new JsonObject();
        servers.add("tritium-music", connection);
        JsonObject config = new JsonObject();
        config.add("mcpServers", servers);
        return new GsonBuilder().setPrettyPrinting().create().toJson(config);
    }

    public void copyConfiguration(MusicPreferences preferences) {
        try {
            String config = clientConfiguration(preferences.mcpPort().getValue().intValue());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(config), null);
            clipboardStatus = "mcp.copied";
        } catch (RuntimeException error) { clipboardStatus = "mcp.copyFailed"; }
    }

    public String clipboardStatus() { return clipboardStatus == null ? I18n.text("mcp.hint") : I18n.text(clipboardStatus); }

    @Override
    public synchronized void close() {
        request(false, -1);
        closed = true;
        lifecycle.shutdown();
    }
}
