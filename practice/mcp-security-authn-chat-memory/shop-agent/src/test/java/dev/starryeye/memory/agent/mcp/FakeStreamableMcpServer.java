package dev.starryeye.memory.agent.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 만료된 session에 {@code 404}로 답하는 테스트용 Streamable HTTP MCP Server다.
 *
 * <p>실제 MCP Server(Spring AI transport)는 {@code sessionIdleTimeout}이 지나면 session을 지운다.
 * 그 뒤 그 session ID로 온 요청에는 {@code 404}를 준다.
 * 테스트가 30분을 기다릴 수 없으므로 {@link #expireSessions()}가 그 순간을 바로 만든다.
 * 실제 서버처럼 열린 GET stream도 정상으로 닫는다.
 *
 * <p>모든 요청을 기록한다.
 * 그래서 테스트는 다시 연 {@code initialize}에 어느 사용자의 token이 붙었는지 볼 수 있다.
 * token이 없는 요청에는 실제 서버처럼 {@code 401}로 답한다.
 *
 * <p>tool은 넷이다.
 * {@code getStock}은 성공하고, {@code brokenTool}은 {@code isError} 결과를 준다.
 * {@code slowTool}은 답하지 않고 기다리고, {@code updateStock}은 {@code 403 insufficient_scope}로 답한다.
 */
final class FakeStreamableMcpServer implements AutoCloseable {

    /** 서버가 받은 요청 하나다. {@code rpcMethod}는 POST에만 있다. */
    record Recorded(String httpMethod, String rpcMethod, String sessionId, String authorization, int status) {

        boolean isInitialize() {
            return "initialize".equals(this.rpcMethod);
        }

        boolean isToolCall() {
            return "tools/call".equals(this.rpcMethod);
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpServer server;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Set<String> liveSessions = ConcurrentHashMap.newKeySet();

    private final AtomicInteger sessionCounter = new AtomicInteger();

    /** 열려 있는 GET stream이다. 끝낼 때 {@code true}를 주면 연결을 끊고, {@code false}면 정상으로 닫는다. */
    private final List<CompletableFuture<Boolean>> openStreams = new CopyOnWriteArrayList<>();

    private final CompletableFuture<Void> stopping = new CompletableFuture<>();

    private final boolean listeningStream;

    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    /**
     * @param listeningStream {@code true}면 GET에 SSE stream을 열어 둔다.
     *                        {@code false}면 GET에 {@code 405}로 답해 client가 stream 없이 쓰게 한다.
     */
    FakeStreamableMcpServer(boolean listeningStream) throws IOException {
        this.listeningStream = listeningStream;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(this.executor);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    String origin() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    /** 살아 있는 session을 모두 지우고 열린 GET stream을 정상으로 닫는다. Spring AI transport의 만료와 같다. */
    void expireSessions() {
        this.liveSessions.clear();
        this.openStreams.forEach(stream -> stream.complete(false));
    }

    /**
     * 살아 있는 session을 모두 지우고 열린 GET stream의 연결을 끊는다.
     * 다시 연결하는 client라면 옛 session ID로 GET을 보내 {@code 404}를 받는다.
     * SDK 2.1.0 client는 다시 연결하지 않는다(테스트가 확인한다).
     */
    void expireSessionsAndDropStreams() {
        this.liveSessions.clear();
        this.openStreams.forEach(stream -> stream.complete(true));
    }

    int openStreamCount() {
        return (int) this.openStreams.stream().filter(stream -> !stream.isDone()).count();
    }

    List<Recorded> initializes() {
        return this.requests.stream().filter(Recorded::isInitialize).toList();
    }

    List<Recorded> toolCalls() {
        return this.requests.stream().filter(Recorded::isToolCall).toList();
    }

    /** 조건에 맞는 요청이 기록될 때까지 기다린다. client가 뒤에서 보내는 요청을 기다릴 때 쓴다. */
    void awaitRequest(Predicate<Recorded> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.requests.stream().noneMatch(condition)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다린 요청이 오지 않았다: " + this.requests);
            }
            Thread.sleep(10);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        switch (exchange.getRequestMethod()) {
            case "GET" -> handleGet(exchange, sessionId, authorization);
            case "DELETE" -> {
                int status = authorization == null ? 401 : 200;
                if (status == 200) {
                    this.liveSessions.remove(sessionId);
                }
                respond(exchange, new Recorded("DELETE", null, sessionId, authorization, status), Map.of(), null);
            }
            default -> handlePost(exchange, sessionId, authorization);
        }
    }

    private void handleGet(HttpExchange exchange, String sessionId, String authorization) throws IOException {
        if (authorization == null) {
            respond(exchange, new Recorded("GET", null, sessionId, null, 401), Map.of(), null);
            return;
        }
        if (!this.listeningStream) {
            respond(exchange, new Recorded("GET", null, sessionId, authorization, 405), Map.of(), null);
            return;
        }
        if (sessionId == null || !this.liveSessions.contains(sessionId)) {
            respond(exchange, new Recorded("GET", null, sessionId, authorization, 404), Map.of(), null);
            return;
        }
        this.requests.add(new Recorded("GET", null, sessionId, authorization, 200));
        CompletableFuture<Boolean> end = new CompletableFuture<>();
        this.openStreams.add(end);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        body.write(": stream open\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
        if (end.join()) {
            // 마지막 chunk를 쓰지 않고 handler를 예외로 끝내면 HttpServer가 연결을 바로 닫는다.
            // client는 읽던 stream이 중간에 끊긴 것을 오류로 받는다.
            throw new IOException("listening stream dropped");
        }
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private void handlePost(HttpExchange exchange, String sessionId, String authorization) throws IOException {
        Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        String rpcMethod = (String) request.get("method");
        Object id = request.get("id");
        if (authorization == null) {
            respond(exchange, new Recorded("POST", rpcMethod, sessionId, null, 401), Map.of(), null);
            return;
        }
        if ("initialize".equals(rpcMethod)) {
            String newSession = "session-" + this.sessionCounter.incrementAndGet();
            this.liveSessions.add(newSession);
            respondResult(exchange, new Recorded("POST", rpcMethod, sessionId, authorization, 200), id,
                    Map.of("protocolVersion", "2025-11-25",
                            "capabilities", Map.of("tools", Map.of("listChanged", false)),
                            "serverInfo", Map.of("name", "fake-shop-mcp-server", "version", "0.0.1")),
                    newSession);
            return;
        }
        if (sessionId == null || !this.liveSessions.contains(sessionId)) {
            respond(exchange, new Recorded("POST", rpcMethod, sessionId, authorization, 404),
                    Map.of("Content-Type", "application/json"),
                    MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id == null ? "" : id,
                            "error", Map.of("code", -32603, "message", "Session not found: " + sessionId))));
            return;
        }
        Recorded ok = new Recorded("POST", rpcMethod, sessionId, authorization, 200);
        switch (rpcMethod) {
            case "notifications/initialized" ->
                    respond(exchange, new Recorded("POST", rpcMethod, sessionId, authorization, 202), Map.of(), null);
            case "tools/list" -> respondResult(exchange, ok, id, Map.of("tools", List.of(
                    tool("getStock"), tool("brokenTool"), tool("slowTool"), tool("updateStock"))), null);
            case "tools/call" -> {
                String toolName = (String) ((Map<String, Object>) request.get("params")).get("name");
                handleToolCall(exchange, ok, id, toolName, sessionId);
            }
            default -> respond(exchange, new Recorded("POST", rpcMethod, sessionId, authorization, 404), Map.of(),
                    null);
        }
    }

    private void handleToolCall(HttpExchange exchange, Recorded ok, Object id, String toolName, String sessionId)
            throws IOException {
        switch (toolName) {
            case "brokenTool" -> respondResult(exchange, ok, id, Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "재고 저장소에 닿지 못했다"))), null);
            case "slowTool" -> {
                // 답하지 않고 기다린다. client는 제한 시간이 지나 호출을 끝낸다.
                this.requests.add(ok);
                this.stopping.join();
                exchange.close();
            }
            case "updateStock" -> respond(exchange,
                    new Recorded("POST", "tools/call", sessionId, ok.authorization(), 403),
                    Map.of("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"products:write\""), null);
            default -> respondResult(exchange, ok, id,
                    Map.of("content", List.of(Map.of("type", "text", "text", "p1: 3개 (" + sessionId + ")"))), null);
        }
    }

    private static Map<String, Object> tool(String name) {
        return Map.of("name", name, "description", name,
                "inputSchema", Map.of("type", "object", "properties", Map.of()));
    }

    private void respondResult(HttpExchange exchange, Recorded recorded, Object id, Object result, String newSession)
            throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", result);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (newSession != null) {
            headers.put("Mcp-Session-Id", newSession);
        }
        respond(exchange, recorded, headers, MAPPER.writeValueAsString(envelope));
    }

    private void respond(HttpExchange exchange, Recorded recorded, Map<String, String> headers, String body)
            throws IOException {
        this.requests.add(recorded);
        send(exchange, recorded.status(), headers, body);
    }

    private static void send(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        this.openStreams.forEach(stream -> stream.complete(false));
        this.stopping.complete(null);
        this.server.stop(0);
        this.executor.shutdownNow();
    }
}
