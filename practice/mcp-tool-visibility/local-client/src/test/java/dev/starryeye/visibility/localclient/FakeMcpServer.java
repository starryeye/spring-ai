package dev.starryeye.visibility.localclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * `/mcp`로 오는 JSON-RPC 호출에 최소한으로 답하는 테스트용 MCP Streamable HTTP server다(McpCallsTest 전용).
 *
 * <p>`checkout` tool만 `Authorization` header를 본다: `Bearer read-token`이면 403
 * (`insufficient_scope`, `scope="orders:write"`)을, 그 밖의 값이면 200을 돌려준다.
 * SSE reconnect(GET)는 다루지 않고 405로 답해 SDK가 "server가 SSE를 지원하지 않는다"로 보고 조용히
 * 건너뛰게 한다({@code HttpClientStreamableHttpTransport#reconnect}).
 */
final class FakeMcpServer implements AutoCloseable {

	static final String BASKET_ID = "bsk_fake0000000000000000";

	record Recorded(String httpMethod, String rpcMethod, String toolName, Map<String, Object> arguments,
			String authorization) {
	}

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final HttpServer server;

	final List<Recorded> requests = new CopyOnWriteArrayList<>();

	/** 서버에 있는 tool이다. 이 순서로 목록을 준다. */
	List<String> tools = List.of("getStock", "createBasket", "addItem", "getBasket", "checkout", "updateStock");

	/** 이 사용자에게 숨긴 tool이다. 목록에서 빠지고, 부르면 "모르는 tool" 오류다. */
	Set<String> hidden = new HashSet<>();

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
			this.requests.add(new Recorded(httpMethod, null, null, Map.of(), authorization));
			respond(exchange, 405, Map.of(), null);
			return;
		}
		if ("DELETE".equals(httpMethod)) {
			this.requests.add(new Recorded(httpMethod, null, null, Map.of(), authorization));
			respond(exchange, 200, Map.of(), null);
			return;
		}
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, Object> request = Json.object(body);
		Object id = request.get("id");
		String rpcMethod = (String) request.get("method");
		String toolName = null;
		Map<String, Object> arguments = Map.of();
		if ("tools/call".equals(rpcMethod)) {
			Map<String, Object> params = (Map<String, Object>) request.get("params");
			toolName = (String) params.get("name");
			arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
		}
		this.requests.add(new Recorded(httpMethod, rpcMethod, toolName, arguments, authorization));
		switch (rpcMethod) {
			// stateless server와 같다. session이 없으니 client도 끝나고 DELETE를 보내지 않는다.
			case "initialize" -> respondResult(exchange, id,
					Map.of("protocolVersion", "2025-11-25", "capabilities",
							Map.of("tools", Map.of("listChanged", false)), "serverInfo",
							Map.of("name", "fake-mcp-server", "version", "0.0.1")),
					null);
			case "notifications/initialized" -> respond(exchange, 202, Map.of(), null);
			case "tools/list" -> respondResult(exchange, id, Map.of("tools", this.tools.stream()
					.filter(name -> !this.hidden.contains(name))
					.map(name -> Map.of("name", name, "inputSchema", Map.of("type", "object")))
					.toList()), null);
			case "tools/call" -> handleToolCall(exchange, id, toolName, arguments, authorization);
			default -> respond(exchange, 404, Map.of(), null);
		}
	}

	private void handleToolCall(HttpExchange exchange, Object id, String toolName, Map<String, Object> arguments,
			String authorization) throws IOException {
		// MCP Java SDK 2.0.1 서버가 없는 tool에 주는 응답과 같다. HTTP 200이라 step-up handler는 불리지 않는다.
		if (this.hidden.contains(toolName)) {
			String body = MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of(
					"code", -32602, "message", "Unknown tool: invalid_tool_name", "data", "Tool not found: " + toolName)));
			respond(exchange, 200, Map.of("Content-Type", "application/json"), body);
			return;
		}
		if ("checkout".equals(toolName) && "Bearer read-token".equals(authorization)) {
			respond(exchange, 403,
					Map.of("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"orders:write\""), null);
			return;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		switch (toolName) {
			case "createBasket" -> {
				result.put("content", List.of(Map.of("type", "text", "text", "장바구니를 만들었습니다. ID는 " + BASKET_ID + "입니다.")));
				result.put("structuredContent", Map.of("basketId", BASKET_ID));
			}
			case "getBasket" -> {
				boolean known = BASKET_ID.equals(arguments.get("basketId"));
				result.put("content", List.of(Map.of("type", "text",
						"text", known ? "장바구니 " + BASKET_ID + ": p4 × 1" : "찾을 수 없는 장바구니입니다.")));
				result.put("isError", !known);
			}
			case "checkout" -> result.put("content", List.of(Map.of("type", "text", "text", "주문을 접수했습니다. 주문 번호는 ord-1001입니다.")));
			default -> result.put("content", List.of(Map.of("type", "text", "text", toolName + " 완료")));
		}
		respondResult(exchange, id, result, null);
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
