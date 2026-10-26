package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Small stateless Streamable HTTP transport for the MCP 2025 tool subset.
 * JSON responses only; GET/SSE and DELETE sessions are deliberately unsupported (405).
 * No servlet framework or model credentials are needed in the desktop application.
 */
public final class EditorMcpServer implements AutoCloseable {
    public static final String PROTOCOL = "2025-11-25";
    private static final Set<String> VERSIONS = Set.of(PROTOCOL, "2025-06-18", "2025-03-26");
    private static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpServer server;
    private final ExecutorService executor;
    private final EditorMcpTools tools;
    private final byte[] authorization;

    public EditorMcpServer(int port, String token, EditorMcpTools tools) throws IOException {
        if (token != null && !token.isEmpty() && !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("MCP token must contain 32 to 256 ASCII letters, digits, underscores or hyphens.");
        this.tools = Objects.requireNonNull(tools);
        // Local clients are trusted by default; only an explicitly supplied token enables authentication.
        authorization = token == null || token.isEmpty() ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            var t = new Thread(runnable, "openbone-mcp"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/mcp") || exchange.getRequestURI().getRawQuery() != null) { send(exchange, 404, null); return; }
            int port = server.getAddress().getPort();
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!Set.of("127.0.0.1:" + port, "localhost:" + port).contains(Objects.toString(host, ""))) { send(exchange, 403, null); return; }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !Set.of("http://127.0.0.1:" + port, "http://localhost:" + port).contains(origin)) { send(exchange, 403, null); return; }
            byte[] supplied = Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "").getBytes(StandardCharsets.UTF_8);
            if (authorization != null && !MessageDigest.isEqual(authorization, supplied)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer"); send(exchange, 401, null); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST"); send(exchange, 405, null); return;
            }
            String contentType = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "");
            if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) { send(exchange, 415, null); return; }
            String accept = String.join(",", exchange.getRequestHeaders().getOrDefault("Accept", List.of()));
            if (!accept.contains("application/json") || !accept.contains("text/event-stream")) { send(exchange, 406, null); return; }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !VERSIONS.contains(version)) { send(exchange, 400, error(null, -32600, "Unsupported MCP-Protocol-Version")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { send(exchange, 413, null); return; }
            JsonNode request;
            try { request = json.readTree(bytes); }
            catch (IOException invalid) { send(exchange, 400, error(null, -32700, "Parse error")); return; }
            if (request == null || !request.isObject() || !request.path("jsonrpc").asText().equals("2.0") || !request.path("method").isTextual()
                    || request.has("id") && !(request.get("id").isTextual() || request.get("id").isIntegralNumber())) {
                send(exchange, 400, error(null, -32600, "Expected a single JSON-RPC 2.0 request with a string or integer id")); return;
            }
            JsonNode id = request.get("id");
            String method = request.get("method").textValue();
            if (id == null) {
                // Notifications never execute tools or mutate editor state.
                send(exchange, 202, null); return;
            }
            JsonNode params = request.has("params") ? request.get("params") : json.createObjectNode();
            if (!params.isObject()) { send(exchange, 200, error(id, -32602, "params must be an object")); return; }
            JsonNode result;
            switch (method) {
                case "initialize" -> {
                    if (!params.path("protocolVersion").isTextual() || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()) {
                        send(exchange, 200, error(id, -32602, "initialize requires protocolVersion, capabilities and clientInfo")); return;
                    }
                    var initialized = json.createObjectNode().put("protocolVersion", VERSIONS.contains(params.get("protocolVersion").asText()) ? params.get("protocolVersion").asText() : PROTOCOL);
                    initialized.putObject("serverInfo").put("name", "openbone").put("version", io.github.samlinb.openbone.EditorApplication.version());
                    initialized.putObject("capabilities").putObject("tools").put("listChanged", false);
                    initialized.put("instructions", "Operate the currently open OpenBone editor. Read context for mcpApiVersion and capabilities, then query scene before editing. Build bones, slots, PNG regions and animations with apply_edits. Use exact UUIDs, expectedRevision and a unique requestId; retain identical arguments on retries. Batches are atomic and undoable. Capture several times, optionally fit and hide overlays. Project save/load and playback are available; loading dirty projects requires explicit dirtyPolicy. Export is not exposed.");
                    result = initialized;
                }
                case "ping" -> result = json.createObjectNode();
                case "tools/list" -> {
                    if (params.has("cursor")) { send(exchange, 200, error(id, -32602, "This tool list has one page; omit cursor")); return; }
                    var list = json.createObjectNode(); list.set("tools", tools.definitions()); result = list;
                }
                case "tools/call" -> {
                    if (!params.path("name").isTextual()) { send(exchange, 200, error(id, -32602, "Tool name is required")); return; }
                    String name = params.get("name").asText();
                    boolean known = false;
                    for (JsonNode tool : tools.definitions()) if (tool.path("name").asText().equals(name)) known = true;
                    if (!known) { send(exchange, 200, error(id, -32602, "Unknown tool: " + name)); return; }
                    result = tools.call(name, params.has("arguments") ? params.get("arguments") : json.createObjectNode());
                }
                default -> { send(exchange, 200, error(id, -32601, "Method not found")); return; }
            }
            var response = json.createObjectNode().put("jsonrpc", "2.0"); response.set("id", id); response.set("result", result);
            send(exchange, 200, response);
        }
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        var response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? NullNode.instance : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (body == null) { exchange.sendResponseHeaders(status, -1); return; }
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
