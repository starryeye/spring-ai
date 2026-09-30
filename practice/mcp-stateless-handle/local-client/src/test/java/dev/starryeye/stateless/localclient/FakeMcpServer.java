package dev.starryeye.stateless.localclient;

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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * `/mcp`로 오는 JSON-RPC 호출에 최소한으로 답하는 테스트용 MCP Streamable HTTP server다(McpCallsTest 전용).
 *
 * <p>`updateStock` tool만 `Authorization` header를 본다: `Bearer read-token`이면 403
 * (`insufficient_scope`, `scope="products:write"`)을, 그 밖의 값이면 200을 돌려준다.
 * SSE reconnect(GET)는 다루지 않고 405로 답해 SDK가 "server가 SSE를 지원하지 않는다"로 보고 조용히
 * 건너뛰게 한다({@code HttpClientStreamableHttpTransport#reconnect}).
 */
final class FakeMcpServer implements AutoCloseable {

	record Recorded(String httpMethod, String rpcMethod, String toolName, String authorization) {
	}

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final HttpServer server;

	final List<Recorded> requests = new CopyOnWriteArrayList<>();

	FakeMcpServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		this.server.createContext("/mcp", this::handle);
		this.server.start();
	}

	String origin() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@SuppressWarnings("unchecked")
	private void handle(HttpExchange exchange) throws IOException {
		String httpMethod = exchange.getRequestMethod();
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if ("GET".equals(httpMethod)) {
			this.requests.add(new Recorded(httpMethod, null, null, authorization));
			respond(exchange, 405, Map.of(), null);
			return;
		}
		if ("DELETE".equals(httpMethod)) {
			this.requests.add(new Recorded(httpMethod, null, null, authorization));
			respond(exchange, 200, Map.of(), null);
			return;
		}
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request = Json.object(body);
		Object id = request.get("id");
		String rpcMethod = (String) request.get("method");
		String toolName = null;
		if ("tools/call".equals(rpcMethod)) {
			Map<String, Object> params = (Map<String, Object>) request.get("params");
			toolName = (String) params.get("name");
		}
		this.requests.add(new Recorded(httpMethod, rpcMethod, toolName, authorization));
		switch (rpcMethod) {
			case "initialize" -> respondResult(exchange, id,
					Map.of("protocolVersion", "2025-11-25", "capabilities",
							Map.of("tools", Map.of("listChanged", false)), "serverInfo",
							Map.of("name", "fake-mcp-server", "version", "0.0.1")),
					"sess-1");
			case "notifications/initialized" -> respond(exchange, 202, Map.of(), null);
			case "tools/list" -> respondResult(exchange, id, Map.of("tools", List.of()), null);
			case "tools/call" -> handleToolCall(exchange, id, toolName, authorization);
			default -> respond(exchange, 404, Map.of(), null);
		}
	}

	private void handleToolCall(HttpExchange exchange, Object id, String toolName, String authorization)
			throws IOException {
		if ("updateStock".equals(toolName) && "Bearer read-token".equals(authorization)) {
			respond(exchange, 403,
					Map.of("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"products:write\""), null);
			return;
		}
		String text = "updateStock".equals(toolName) ? "p1 재고를 10으로 바꿨다" : "p1: 3개";
		respondResult(exchange, id, Map.of("content", List.of(Map.of("type", "text", "text", text))), null);
	}

	private void respondResult(HttpExchange exchange, Object id, Object result, String sessionId) throws IOException {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("result", result);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		if (sessionId != null) {
			headers.put("Mcp-Session-Id", sessionId);
		}
		respond(exchange, 200, headers, MAPPER.writeValueAsString(envelope));
	}

	private void respond(HttpExchange exchange, int status, Map<String, String> headers, String body)
			throws IOException {
		headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
		byte[] bytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
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
		this.server.stop(0);
	}
}
