package dev.starryeye.modern.mcpserver;

import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server를 2026-07-28 형식으로 부르는 테스트 client다.
 *
 * <p>SDK의 HTTP transport는 Spring MVC의 {@code DispatcherServlet}이 아니라 따로 등록한 servlet이다.
 * 그래서 MockMvc로는 닿지 않고, 서버를 port에 띄워 실제 HTTP로 부른다.
 *
 * <p>요청 하나는 {@code POST /mcp} 하나다.
 * 본문 {@code params._meta}에 버전·client capability·client 정보를 넣고,
 * header {@code MCP-Protocol-Version}·{@code Mcp-Method}·{@code Mcp-Name}(tools/call에만)을 붙인다.
 */
final class ModernTestClient {

	static final String PROTOCOL_VERSION = "2026-07-28";

	static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private final String origin;

	ModernTestClient(int port) {
		this.origin = "http://localhost:" + port;
	}

	/**
	 * 서버를 비어 있는 port 하나에 띄우고, token의 {@code aud}로 검증할 resource도 그 port의 주소로 정한다.
	 *
	 * <p>{@code RANDOM_PORT}로 띄우면 {@code server.port}가 0으로 남는다.
	 * {@code McpTransportSecurityFilter}는 {@code server.port}로 허용할 {@code Host}를 정하므로, 그러면 모든 요청이 {@code 421}이 된다.
	 * 그래서 port를 먼저 골라 {@code server.port}로 넘기고 {@code DEFINED_PORT}로 띄운다.
	 */
	static void listenOnFreePort(DynamicPropertyRegistry registry) {
		int port = freePort();
		registry.add("server.port", () -> port);
		registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "http://localhost:" + port + "/mcp");
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	Response discover(String token) {
		return send(token, "server/discover", Map.of());
	}

	Response listTools(String token) {
		return send(token, "tools/list", Map.of());
	}

	Response callTool(String token, String name, Map<String, Object> arguments) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("name", name);
		params.put("arguments", arguments);
		return post(token, headers("tools/call", name), body("tools/call", params));
	}

	/**
	 * {@code tools/call}이 아닌 method를 보낸다.
	 * {@code Mcp-Name}은 붙이지 않는다.
	 */
	Response send(String token, String method, Map<String, Object> params) {
		return post(token, headers(method, null), body(method, params));
	}

	/**
	 * 2026-07-28 client가 붙이는 header다.
	 * {@code mcpName}이 {@code null}이면 {@code Mcp-Name}을 붙이지 않는다.
	 */
	static Map<String, String> headers(String method, String mcpName) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("MCP-Protocol-Version", PROTOCOL_VERSION);
		headers.put("Mcp-Method", method);
		if (mcpName != null) {
			headers.put("Mcp-Name", mcpName);
		}
		return headers;
	}

	/**
	 * {@code params}에 {@code _meta}를 더한 JSON-RPC 요청 본문이다.
	 * {@code params}의 값은 그대로 직렬화한다.
	 */
	static String body(String method, Map<String, Object> params) {
		Map<String, Object> withMeta = new LinkedHashMap<>(params);
		withMeta.put("_meta", Map.of(
				"io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION,
				"io.modelcontextprotocol/clientCapabilities", Map.of(),
				"io.modelcontextprotocol/clientInfo", Map.of("name", "modern-test-client", "version", "0.0.1")));
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("jsonrpc", "2.0");
		request.put("id", 1);
		request.put("method", method);
		request.put("params", withMeta);
		return JSON.writeValueAsString(request);
	}

	/**
	 * header와 본문을 그대로 보낸다.
	 * {@code token}이 {@code null}이면 {@code Authorization}을 붙이지 않는다.
	 */
	Response post(String token, Map<String, String> headers, String body) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.origin + "/mcp"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		headers.forEach(request::header);
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return exchange(request.build());
	}

	Response get(String path) {
		return exchange(HttpRequest.newBuilder(URI.create(this.origin + path)).GET().build());
	}

	private static Response exchange(HttpRequest request) {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return new Response(response.statusCode(), response.headers().map(), response.body());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	record Response(int status, Map<String, List<String>> headers, String body) {

		JsonNode json() {
			return JSON.readTree(this.body);
		}

		/**
		 * 이름이 같은 첫 header 값이다.
		 * 없으면 {@code null}이다.
		 */
		String header(String name) {
			return this.headers.entrySet().stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase(name))
					.flatMap(entry -> entry.getValue().stream())
					.findFirst()
					.orElse(null);
		}
	}
}
