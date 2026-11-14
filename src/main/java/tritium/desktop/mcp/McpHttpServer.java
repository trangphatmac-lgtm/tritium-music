package tritium.desktop.mcp;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stateless Streamable HTTP with JSON responses, supporting initialization-based MCP clients. */
public final class McpHttpServer implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", PROTOCOL_VERSION);
    private static final int MAX_BODY_BYTES = 1_048_576;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Handler handler;

    public interface Handler {
        JsonArray list();
        JsonObject call(String name, JsonObject arguments);
    }

    public McpHttpServer(int port, Handler handler) throws IOException {
        this.handler = handler;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        server.createContext("/mcp", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/mcp"; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (!exchange.getRequestURI().getPath().equals("/mcp")) {
                reply(exchange, 404, error(JsonNull.INSTANCE, -32601, "Unknown endpoint"));
                return;
            }
            if (!validAuthority(exchange.getRequestHeaders().getFirst("Host")) || !validOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                reply(exchange, 403, error(JsonNull.INSTANCE, -32600, "Forbidden host or origin"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                reply(exchange, 405, null);
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                reply(exchange, 415, error(JsonNull.INSTANCE, -32600, "Content-Type must be application/json"));
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && !accept.contains("application/json") && !accept.contains("*/*")) {
                reply(exchange, 406, error(JsonNull.INSTANCE, -32600, "Accept must include application/json"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                reply(exchange, 413, error(JsonNull.INSTANCE, -32600, "Request body exceeds 1 MiB"));
                return;
            }
            JsonObject request;
            try {
                JsonElement parsed = new JsonParser().parse(new String(body, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    reply(exchange, 400, error(JsonNull.INSTANCE, -32600, "Expected one JSON-RPC object"));
                    return;
                }
                request = parsed.getAsJsonObject();
            } catch (JsonParseException | IllegalStateException exception) {
                reply(exchange, 400, error(JsonNull.INSTANCE, -32700, "Invalid JSON"));
                return;
            }
            JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
            if (!isString(request, "jsonrpc") || !request.get("jsonrpc").getAsString().equals("2.0")
                    || !isString(request, "method") || !validId(id)
                    || (request.has("params") && !request.get("params").isJsonObject())) {
                reply(exchange, 400, error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request"));
                return;
            }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !VERSIONS.contains(version)) {
                reply(exchange, 400, error(id, -32600, "Unsupported MCP-Protocol-Version. Supported: " + VERSIONS));
                return;
            }
            String method = request.get("method").getAsString();
            if (!request.has("id")) {
                if (!method.startsWith("notifications/")) {
                    reply(exchange, 400, error(JsonNull.INSTANCE, -32600, "Requests require an id"));
                    return;
                }
                reply(exchange, 202, null);
                return;
            }
            JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
            try {
                JsonObject result = switch (method) {
                    case "initialize" -> initialize(params);
                    case "ping" -> new JsonObject();
                    case "tools/list" -> {
                        if (params.has("cursor")) throw new IllegalArgumentException("Invalid cursor");
                        JsonObject list = new JsonObject();
                        list.add("tools", handler.list());
                        yield list;
                    }
                    case "tools/call" -> {
                        if (!isString(params, "name") || (params.has("arguments") && !params.get("arguments").isJsonObject()))
                            throw new IllegalArgumentException("Expected tool name and object arguments");
                        yield handler.call(params.get("name").getAsString(), params.has("arguments")
                                ? params.getAsJsonObject("arguments") : new JsonObject());
                    }
                    default -> null;
                };
                reply(exchange, 200, result == null ? error(id, -32601, "Method not found: " + method) : success(id, result));
            } catch (IllegalArgumentException exception) {
                reply(exchange, 200, error(id, -32602, exception.getMessage()));
            } catch (RuntimeException exception) {
                System.err.println("[MCP] Request failed: " + exception.getClass().getSimpleName());
                reply(exchange, 200, error(id, -32603, "Internal server error"));
            }
        }
    }

    private static JsonObject initialize(JsonObject params) {
        if (!isString(params, "protocolVersion")) throw new IllegalArgumentException("protocolVersion is required");
        String requested = params.get("protocolVersion").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION);
        JsonObject info = new JsonObject();
        info.addProperty("name", "tritium-music");
        info.addProperty("version", "1.0.3");
        result.add("serverInfo", info);
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        result.addProperty("instructions", "Control the running Tritium Music desktop player. Use get_settings before update_settings. Song and playlist IDs come from the list and search tools. Playback starts asynchronously. Use get_playback_state to observe progress.");
        return result;
    }

    private boolean validAuthority(String host) {
        return ("127.0.0.1:" + port()).equalsIgnoreCase(host) || ("localhost:" + port()).equalsIgnoreCase(host);
    }

    private boolean validOrigin(String origin) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            return uri.getScheme().equals("http") && validAuthority(uri.getRawAuthority())
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException exception) { return false; }
    }

    private static boolean isString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString();
    }

    private static boolean validId(JsonElement id) {
        return id.isJsonNull() || (id.isJsonPrimitive() && (id.getAsJsonPrimitive().isNumber() || id.getAsJsonPrimitive().isString()));
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        return response;
    }

    private static void reply(HttpExchange exchange, int status, JsonObject response) throws IOException {
        if (response == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /** Allow the request changing mcp.enabled/port to finish before releasing its worker. */
    @Override
    public void close() {
        server.stop(2);
        executor.shutdownNow();
    }
}
