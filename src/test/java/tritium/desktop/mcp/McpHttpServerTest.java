package tritium.desktop.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpServerTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final McpHttpServer.Handler handler = new McpHttpServer.Handler() {
        @Override public JsonArray list() {
            return new JsonParser().parse("[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]").getAsJsonArray();
        }
        @Override public JsonObject call(String name, JsonObject arguments) {
            if (!name.equals("echo")) throw new IllegalArgumentException("Unknown tool");
            return McpTools.content(arguments.toString(), false);
        }
    };

    @Test void initializesNegotiatesVersionsAndServesToolsWithoutSessions() throws Exception {
        try (var server = new McpHttpServer(0, handler)) {
            for (String version : new String[]{"2025-03-26", "2025-06-18", "2025-11-25", "future"}) {
                var result = json(post(server, """
                        {"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"%s"}}
                        """.formatted(version)));
                assertEquals(version.equals("future") ? McpHttpServer.PROTOCOL_VERSION : version,
                        result.getAsJsonObject("result").get("protocolVersion").getAsString());
                assertEquals("init", result.get("id").getAsString());
            }
            var notification = post(server, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertEquals(202, notification.statusCode());
            assertTrue(notification.body().isEmpty());
            assertEquals("echo", json(post(server, rpc("tools/list", "{}")))
                    .getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
            var echo = post(server, rpc("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"中文\"}}"));
            assertFalse(json(echo).getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(echo.body().contains("中文"));
            assertEquals("application/json; charset=utf-8", echo.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(echo.headers().firstValue("Mcp-Session-Id").isEmpty());
        }
    }

    @Test void rejectsInvalidMessagesAndReportsRpcErrors() throws Exception {
        try (var server = new McpHttpServer(0, handler)) {
            assertError(post(server, "{broken"), 400, -32700);
            assertError(post(server, "[]"), 400, -32600);
            assertError(post(server, "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}"), 400, -32600);
            assertError(post(server, "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}"), 400, -32600);
            assertError(post(server, rpc("tools/call", "{\"name\":\"echo\",\"arguments\":[]}")), 200, -32602);
            assertError(post(server, rpc("tools/call", "{\"name\":\"missing\"}")), 200, -32602);
            assertError(post(server, rpc("missing", "{}")), 200, -32601);
            assertError(send(server, "POST", rpc("ping", "{}"), "application/json", "MCP-Protocol-Version", "invalid"), 400, -32600);
            assertEquals(413, post(server, " ".repeat(1_048_577)).statusCode());
        }
    }

    @Test void validatesOriginContentTypeAndEndpoint() throws Exception {
        try (var server = new McpHttpServer(0, handler)) {
            assertEquals(403, send(server, "POST", rpc("ping", "{}"), "application/json", "Origin", "https://example.com").statusCode());
            assertEquals(403, send(server, "POST", rpc("ping", "{}"), "application/json", "Origin", "null").statusCode());
            assertEquals(200, send(server, "POST", rpc("ping", "{}"), "application/json", "Origin", "http://localhost:" + server.port()).statusCode());
            assertEquals(415, send(server, "POST", rpc("ping", "{}"), "text/plain").statusCode());
            var get = send(server, "GET", "", "application/json");
            assertEquals(405, get.statusCode());
            assertEquals("POST", get.headers().firstValue("Allow").orElseThrow());
            var wrongPath = HttpRequest.newBuilder(URI.create(server.url() + "/other")).GET().build();
            assertEquals(404, client.send(wrongPath, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void closeReleasesPort() throws Exception {
        var server = new McpHttpServer(0, handler);
        int port = server.port();
        server.close();
        try (var reopened = new McpHttpServer(port, handler)) {
            assertEquals(200, post(reopened, rpc("ping", "{}")).statusCode());
        }
    }

    @Test void rejectsReboundHostAndLetsShutdownRequestReceiveItsResult() throws Exception {
        AtomicReference<McpHttpServer> reference = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();
        var server = new McpHttpServer(0, new McpHttpServer.Handler() {
            @Override public JsonArray list() { return new JsonArray(); }
            @Override public JsonObject call(String name, JsonObject arguments) {
                shutdown.set(CompletableFuture.runAsync(() -> reference.get().close()));
                return McpTools.content("disabled", false);
            }
        });
        reference.set(server);
        try {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("GET /mcp HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 403"), response);
            }
            var response = post(server, rpc("tools/call", "{\"name\":\"shutdown\"}"));
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("disabled"));
            shutdown.get().join();
        } finally { server.close(); }
    }

    private HttpResponse<String> post(McpHttpServer server, String body) throws Exception {
        return send(server, "POST", body, "application/json");
    }

    private HttpResponse<String> send(McpHttpServer server, String method, String body, String contentType, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.url())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType).header("Accept", "application/json, text/event-stream")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }
    private static JsonObject json(HttpResponse<String> response) { return new JsonParser().parse(response.body()).getAsJsonObject(); }
    private static void assertError(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).getAsJsonObject("error").get("code").getAsInt());
    }
}
