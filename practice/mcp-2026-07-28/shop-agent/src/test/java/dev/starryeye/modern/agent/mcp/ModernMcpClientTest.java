package dev.starryeye.modern.agent.mcp;

import dev.starryeye.modern.agent.security.StepUpRequiredException;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDK {@code HttpServer}로 만든 가짜 MCP Server를 상대로 2026-07-28 요청 형식과 응답 해석을 본다.
 *
 * <p>가짜 서버는 받은 요청을 그대로 기록한다.
 * 답은 테스트마다 {@link #responder}로 정한다.
 * 기본 답은 실제 MCP Server와 같은 모양이다.
 */
class ModernMcpClientTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final McpJsonMapper MCP_JSON = new JacksonMcpJsonMapper(JSON);

	/** 가짜 서버가 받은 요청이다. */
	record Received(String method, String path, Headers headers, String body) {

		JsonNode json() {
			return JSON.readTree(this.body);
		}

		/** 응답에 그대로 돌려줄 요청 {@code id}의 JSON 표기다. */
		String id() {
			return json().get("id").toString();
		}

		String mcpMethod() {
			return json().get("method").asString();
		}
	}

	/** 가짜 서버의 답이다. */
	record Reply(int status, Map<String, String> headers, String body) {
	}

	HttpServer server;

	List<Received> received = new CopyOnWriteArrayList<>();

	/** redirect가 가리키는 다른 주소가 받은 요청 수다. */
	AtomicInteger elsewhere = new AtomicInteger();

	Function<Received, Reply> responder = ModernMcpClientTest::실제_서버처럼_답한다;

	ModernMcpClient client;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.createContext("/mcp", exchange -> {
			Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders(),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			this.received.add(request);
			send(exchange, this.responder.apply(request));
		});
		this.server.createContext("/elsewhere", exchange -> {
			this.elsewhere.incrementAndGet();
			send(exchange, new Reply(200, Map.of("Content-Type", "application/json"), "{}"));
		});
		this.server.createContext("/keep-alive-forever", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			stream(exchange, ":\n\n".getBytes(StandardCharsets.UTF_8));
		});
		this.server.createContext("/json-stalls", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			// 본문 길이는 1000byte라고 알리고 10byte만 보낸 뒤 멈춘다.
			exchange.sendResponseHeaders(200, 1000);
			stream(exchange, "{\"jsonrpc\"".getBytes(StandardCharsets.UTF_8));
		});
		this.server.start();
		this.client = new ModernMcpClient(URI.create(origin() + "/mcp"), HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(), MCP_JSON);
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	String origin() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	static void send(HttpExchange exchange, Reply reply) throws IOException {
		reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(reply.status(), (body.length == 0) ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
		exchange.close();
	}

	/**
	 * 헤더를 보낸 뒤 본문을 끝내지 않고 {@code chunk}를 20ms마다 계속 쓴다.
	 * client가 연결을 닫아 쓰기가 실패하거나 5초가 지나면 멈춘다.
	 * 시험이 끝나지 않는 일이 없도록 둔 상한이다.
	 */
	static void stream(HttpExchange exchange, byte[] chunk) {
		try (OutputStream out = exchange.getResponseBody()) {
			long giveUp = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (System.nanoTime() < giveUp) {
				out.write(chunk);
				out.flush();
				Thread.sleep(20);
			}
		}
		catch (IOException ex) {
			// client가 연결을 닫았다.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			exchange.close();
		}
	}

	ModernMcpClient clientWithDeadline(String path, Duration deadline) {
		return new ModernMcpClient(URI.create(origin() + path), HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(), MCP_JSON, deadline);
	}

	static Reply json(int status, String body) {
		return new Reply(status, Map.of("Content-Type", "application/json"), body);
	}

	static Reply 결과(Received request, String result) {
		return json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + request.id() + ",\"result\":" + result + "}");
	}

	/** 실제 MCP Server가 주는 값과 같은 모양이다. */
	static Reply 실제_서버처럼_답한다(Received request) {
		return switch (request.mcpMethod()) {
			case "server/discover" -> 결과(request, """
					{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},
					 "ttlMs":0,"cacheScope":"private","resultType":"complete"}""");
			case "tools/list" -> 결과(request, """
					{"tools":[{"name":"searchProducts","description":"상품을 찾는다","inputSchema":{"type":"object"}},
					          {"name":"getStock","description":"재고를 본다","inputSchema":{"type":"object"}}],
					 "ttlMs":60000,"cacheScope":"private","resultType":"complete"}""");
			case "tools/call" -> 결과(request, """
					{"content":[{"type":"text","text":"p1 재고 7개"}],"isError":false,"resultType":"complete"}""");
			default -> json(404, "{\"jsonrpc\":\"2.0\",\"id\":" + request.id()
					+ ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
		};
	}

	/** 실제 MCP Server의 filter와 SDK가 주는 오류 본문처럼 {@code id}가 없다. */
	static Reply 오류(int status, int code, String message) {
		return json(status, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}");
	}

	@Test
	void 요청마다_header_셋과_meta와_token을_붙인다() {
		this.client.discover("token-a");
		this.client.listTools("token-a", "cursor-1");
		this.client.callTool("token-a", "updateStock", Map.of("productId", "p1", "quantity", 10));

		assertThat(this.received).hasSize(3);
		assertThat(this.received).allSatisfy(request -> {
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.path()).isEqualTo("/mcp");
			assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer token-a");
			assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");
			assertThat(request.headers().getFirst("Accept")).isEqualTo("application/json, text/event-stream");
			assertThat(request.headers().getFirst("MCP-Protocol-Version")).isEqualTo("2026-07-28");
			assertThat(request.headers().getFirst("Mcp-Method")).isEqualTo(request.mcpMethod());
			JsonNode body = request.json();
			assertThat(body.get("jsonrpc").asString()).isEqualTo("2.0");
			assertThat(body.get("id").isIntegralNumber()).isTrue();
			JsonNode meta = body.get("params").get("_meta");
			assertThat(meta.get("io.modelcontextprotocol/protocolVersion").asString()).isEqualTo("2026-07-28");
			assertThat(meta.get("io.modelcontextprotocol/clientCapabilities").toString()).isEqualTo("{}");
			assertThat(meta.get("io.modelcontextprotocol/clientInfo"))
					.isEqualTo(JSON.valueToTree(Map.of("name", "modern-shop-agent", "version", "0.0.1")));
		});
		assertThat(this.received).extracting(Received::mcpMethod)
				.containsExactly("server/discover", "tools/list", "tools/call");
		// 요청마다 id가 다르다.
		assertThat(this.received).extracting(Received::id).doesNotHaveDuplicates();
		// Mcp-Name은 tools/call에만 붙는다.
		assertThat(this.received.get(0).headers().containsKey("Mcp-Name")).isFalse();
		assertThat(this.received.get(1).headers().containsKey("Mcp-Name")).isFalse();
		assertThat(this.received.get(2).headers().getFirst("Mcp-Name")).isEqualTo("updateStock");
		assertThat(this.received.get(1).json().get("params").get("cursor").asString()).isEqualTo("cursor-1");
		JsonNode call = this.received.get(2).json().get("params");
		assertThat(call.get("name").asString()).isEqualTo("updateStock");
		assertThat(call.get("arguments").get("productId").asString()).isEqualTo("p1");
		assertThat(call.get("arguments").get("quantity").asInt()).isEqualTo(10);
	}

	@Test
	void header에_그대로_쓸_수_없는_tool_이름은_base64로_적는다() {
		this.client.callTool("token-a", "재고변경", Map.of());

		assertThat(this.received.get(0).headers().getFirst("Mcp-Name")).isEqualTo("=?base64?7J6s6rOg67OA6rK9?=");
		// 본문의 이름은 그대로다.
		// 서버는 header를 풀어 본문과 맞춰 본다.
		assertThat(this.received.get(0).json().get("params").get("name").asString()).isEqualTo("재고변경");
		// 출력 가능한 ASCII는 가운데 공백까지 그대로 두고, 제어 문자가 있으면 base64로 적는다.
		assertThat(ModernMcpClient.mcpName("get stock")).isEqualTo("get stock");
		assertThat(ModernMcpClient.mcpName("a b")).isEqualTo("a b");
		assertThat(ModernMcpClient.mcpName("a\tb")).isEqualTo("=?base64?YQli?=");
		// 앞뒤 공백은 header를 지나며 지워질 수 있으므로 base64로 적는다.
		assertThat(ModernMcpClient.mcpName(" a")).isEqualTo("=?base64?IGE=?=");
		assertThat(ModernMcpClient.mcpName("a ")).isEqualTo("=?base64?YSA=?=");
		// base64 표기와 모양이 같은 이름은 서버가 풀어 버리지 않도록 그 글자 그대로를 base64로 적는다.
		assertThat(ModernMcpClient.mcpName("=?base64?abc?=")).isEqualTo("=?base64?PT9iYXNlNjQ/YWJjPz0=?=");
		// 끝이 ?=가 아니면 base64 표기로 읽히지 않으므로 그대로 둔다.
		assertThat(ModernMcpClient.mcpName("=?base64?abc")).isEqualTo("=?base64?abc");
	}

	@Test
	void JSON_응답을_읽는다() {
		McpSchema.DiscoverResult discover = this.client.discover("token-a");
		McpSchema.ListToolsResult tools = this.client.listTools("token-a", null);
		McpSchema.CallToolResult result = this.client.callTool("token-a", "getStock", Map.of("productId", "p1"));

		assertThat(discover.supportedVersions()).containsExactly("2026-07-28");
		assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactly("searchProducts", "getStock");
		assertThat(tools.ttlMs()).isEqualTo(60000L);
		assertThat(tools.cacheScope()).isEqualTo(McpSchema.CacheScope.PRIVATE);
		assertThat(tools.nextCursor()).isNull();
		assertThat(result.content()).singleElement().isInstanceOfSatisfying(McpSchema.TextContent.class,
				text -> assertThat(text.text()).isEqualTo("p1 재고 7개"));
		assertThat(result.isError()).isFalse();
	}

	@Test
	void SSE_응답에서_keep_alive와_다른_event를_건너뛰고_결과를_찾는다() {
		this.responder = request -> new Reply(200, Map.of("Content-Type", "text/event-stream"), String.join("\n",
				":",
				"",
				"event: message",
				"data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"p\",\"progress\":1}}",
				"",
				"event: message",
				"data: {\"jsonrpc\":\"2.0\",\"id\":987654,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"다른 요청\"}]}}",
				"",
				// data 줄이 여럿이면 줄바꿈으로 이어 한 JSON으로 읽는다.
				"event: message",
				"data: {\"jsonrpc\":\"2.0\",\"id\":" + request.id() + ",",
				"data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"p1 재고 7개\"}],\"resultType\":\"complete\"}}",
				"",
				""));

		McpSchema.CallToolResult result = this.client.callTool("token-a", "getStock", Map.of("productId", "p1"));

		assertThat(result.content()).singleElement().isInstanceOfSatisfying(McpSchema.TextContent.class,
				text -> assertThat(text.text()).isEqualTo("p1 재고 7개"));
	}

	@Test
	void _403_insufficient_scope면_StepUpRequiredException이다() {
		this.responder = request -> new Reply(403, Map.of("WWW-Authenticate",
				"Bearer error=\"insufficient_scope\", scope=\"products:read orders:write\", "
						+ "resource_metadata=\"http://localhost:8181/.well-known/oauth-protected-resource/mcp\""), "");

		assertThatThrownBy(() -> this.client.callTool("token-a", "checkout", Map.of("basketId", "bsk_1")))
				.isInstanceOfSatisfying(StepUpRequiredException.class, stepUp -> {
					assertThat(stepUp.scopes()).containsExactly("products:read", "orders:write");
					assertThat(stepUp.tool()).isEqualTo("checkout");
				});
	}

	@Test
	void _403이어도_insufficient_scope가_아니면_step_up이_아니다() {
		this.responder = request -> new Reply(403, Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""), "");

		assertThatThrownBy(() -> this.client.callTool("token-a", "checkout", Map.of()))
				.isExactlyInstanceOf(ModernMcpException.class)
				.satisfies(error -> assertThat(((ModernMcpException) error).httpStatus()).isEqualTo(403));
	}

	@Test
	void 모르는_tool이면_UnknownToolException이다() {
		this.responder = request -> 오류(400, -32602, "Unknown tool: updateStock");

		assertThatThrownBy(() -> this.client.callTool("token-a", "updateStock", Map.of()))
				.isInstanceOfSatisfying(UnknownToolException.class, error -> {
					assertThat(error.httpStatus()).isEqualTo(400);
					assertThat(error.jsonRpcCode()).isEqualTo(-32602);
					assertThat(error.getMessage()).isEqualTo("Unknown tool: updateStock");
				});
	}

	@Test
	void 같은_code의_인자_오류는_모르는_tool로_보지_않는다() {
		this.responder = request -> 오류(400, -32602, "Invalid arguments for tool updateStock");

		assertThatThrownBy(() -> this.client.callTool("token-a", "updateStock", Map.of()))
				.isExactlyInstanceOf(ModernMcpException.class)
				.hasMessage("Invalid arguments for tool updateStock");
	}

	@Test
	void 같은_message라도_code가_다르면_모르는_tool로_보지_않는다() {
		this.responder = request -> 오류(404, -32601, "Unknown tool: updateStock");

		assertThatThrownBy(() -> this.client.callTool("token-a", "updateStock", Map.of()))
				.isExactlyInstanceOf(ModernMcpException.class)
				.satisfies(error -> assertThat(((ModernMcpException) error).jsonRpcCode()).isEqualTo(-32601));
	}

	@Test
	void _429면_Retry_After를_담는다() {
		this.responder = request -> new Reply(429,
				Map.of("Content-Type", "application/json", "Retry-After", "17"),
				"{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,"
						+ "\"message\":\"tool 호출이 너무 잦습니다. 17초 뒤에 다시 시도하세요.\"}}");

		assertThatThrownBy(() -> this.client.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(ToolRateLimitedException.class, error -> {
					assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(17));
					assertThat(error.httpStatus()).isEqualTo(429);
					assertThat(error.getMessage()).isEqualTo("tool 호출이 너무 잦습니다. 17초 뒤에 다시 시도하세요.");
				});
	}

	@ParameterizedTest
	@ValueSource(ints = { -32020, -32021, -32022 })
	void header_불일치_오류는_McpProtocolMismatchException이다(int code) {
		this.responder = request -> 오류(400, code, "Mcp-Name header does not match request name/uri");

		assertThatThrownBy(() -> this.client.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpProtocolMismatchException.class, error -> {
					assertThat(error.httpStatus()).isEqualTo(400);
					assertThat(error.jsonRpcCode()).isEqualTo(code);
				});
	}

	@Test
	void discover가_2026_07_28을_모르면_멈춘다() {
		this.responder = request -> 결과(request, """
				{"supportedVersions":["2025-11-25"],"capabilities":{},"ttlMs":0,"cacheScope":"private"}""");

		assertThatThrownBy(() -> this.client.discover("token-a"))
				.isInstanceOf(McpProtocolMismatchException.class)
				.hasMessageContaining("2026-07-28")
				.hasMessageContaining("2025-11-25");
	}

	@Test
	void 그_밖의_HTTP_오류는_ModernMcpException이다() {
		this.responder = request -> new Reply(401, Map.of("WWW-Authenticate", "Bearer"), "");

		assertThatThrownBy(() -> this.client.listTools("token-a", null))
				.isExactlyInstanceOf(ModernMcpException.class)
				.satisfies(error -> {
					assertThat(((ModernMcpException) error).httpStatus()).isEqualTo(401);
					assertThat(((ModernMcpException) error).jsonRpcCode()).isNull();
				});
	}

	@Test
	void 응답을_정한_시간보다_오래_기다리지_않는다() {
		this.responder = request -> {
			try {
				Thread.sleep(2_000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return 실제_서버처럼_답한다(request);
		};
		ModernMcpClient impatient = new ModernMcpClient(URI.create(origin() + "/mcp"), HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(), MCP_JSON, Duration.ofMillis(200));

		// 응답이 없으므로 HTTP status는 0이다.
		// tool 호출이면 이 문장이 모델에게 tool 결과로 간다.
		assertThatThrownBy(() -> impatient.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpTransportException.class, error -> {
					assertThat(error.httpStatus()).isZero();
					assertThat(error.jsonRpcCode()).isNull();
					assertThat(error.getMessage()).contains("답하지 않았다");
				})
				.hasCauseInstanceOf(HttpTimeoutException.class);
		assertThat(ModernMcpClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(20));
	}

	/**
	 * JDK의 요청 제한 시간은 응답 header까지만 잰다.
	 * header 뒤에 keep-alive 줄만 보내면 SSE 본문을 읽는 동안에는 끝이 없으므로, 본문을 읽는 시간도 같은 제한으로 잰다.
	 */
	@Test
	void keep_alive만_보내고_결과를_주지_않는_SSE_응답은_정한_시간에_끊는다() {
		ModernMcpClient impatient = clientWithDeadline("/keep-alive-forever", Duration.ofMillis(300));

		long started = System.nanoTime();
		assertThatThrownBy(() -> impatient.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpTransportException.class, error -> {
					assertThat(error.httpStatus()).isZero();
					assertThat(error.jsonRpcCode()).isNull();
					assertThat(error.getMessage()).isEqualTo("MCP Server가 300ms 안에 응답을 끝까지 보내지 않았다");
				})
				.hasCauseInstanceOf(HttpTimeoutException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void 본문이_도중에_멈춘_JSON_응답도_같은_시간에_끊는다() {
		ModernMcpClient impatient = clientWithDeadline("/json-stalls", Duration.ofMillis(300));

		long started = System.nanoTime();
		assertThatThrownBy(() -> impatient.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpTransportException.class,
						error -> assertThat(error.getMessage()).isEqualTo("MCP Server가 300ms 안에 응답을 끝까지 보내지 않았다"));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void 제한_시간_안에_결과가_오면_keep_alive가_앞서도_읽는다() {
		this.responder = request -> new Reply(200, Map.of("Content-Type", "text/event-stream"), String.join("\n",
				":", "", ":", "",
				"data: {\"jsonrpc\":\"2.0\",\"id\":" + request.id()
						+ ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"p1 재고 7개\"}],\"resultType\":\"complete\"}}",
				"", ""));
		ModernMcpClient patient = clientWithDeadline("/mcp", Duration.ofSeconds(5));

		assertThat(patient.callTool("token-a", "getStock", Map.of()).content()).hasSize(1);
	}

	@Test
	void MCP_Server에_연결하지_못하면_McpTransportException이다() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		ModernMcpClient unreachable = new ModernMcpClient(URI.create("http://127.0.0.1:" + closedPort + "/mcp"),
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), MCP_JSON);

		assertThatThrownBy(() -> unreachable.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpTransportException.class, error -> {
					assertThat(error.httpStatus()).isZero();
					assertThat(error.getMessage()).contains("연결하지 못했다");
				})
				.hasCauseInstanceOf(ConnectException.class);
	}

	/**
	 * 연결 제한 시간이 지나도 JDK는 {@code HttpTimeoutException}의 하위 type을 던진다.
	 * 이때 "답하지 않았다"와 응답 제한 시간을 말하면 원인과 시간을 모두 잘못 알린다.
	 * 연결이 늦어지는 주소는 환경마다 달라서, 연결 제한 시간이 지난 것처럼 실패하는 client로 본다.
	 */
	@Test
	void 연결_제한_시간이_지나면_그_시간_안에_연결하지_못했다고_알린다() {
		ModernMcpClient slowToConnect = new ModernMcpClient(URI.create(origin() + "/mcp"),
				new ConnectTimingOut(Duration.ofSeconds(2)), MCP_JSON);

		assertThatThrownBy(() -> slowToConnect.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(McpTransportException.class, error -> {
					assertThat(error.httpStatus()).isZero();
					assertThat(error.getMessage()).isEqualTo("MCP Server에 2초 안에 연결하지 못했다");
				})
				.hasCauseInstanceOf(HttpConnectTimeoutException.class);
	}

	/**
	 * {@code send}가 연결 제한 시간이 지난 것처럼 실패하는 client다.
	 * 나머지 메서드는 이 시험에서 쓰지 않는다.
	 */
	static final class ConnectTimingOut extends HttpClient {

		private final Duration connectTimeout;

		ConnectTimingOut(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.of(this.connectTimeout);
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
			throw new HttpConnectTimeoutException("HTTP connect timed out");
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			throw new UnsupportedOperationException();
		}

		@Override
		public SSLParameters sslParameters() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_1_1;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException();
		}
	}

	@Test
	void redirect를_따라가지_않는다() {
		this.responder = request -> new Reply(307, Map.of("Location", origin() + "/elsewhere"), "");

		assertThatThrownBy(() -> this.client.callTool("token-a", "getStock", Map.of()))
				.isInstanceOfSatisfying(ModernMcpException.class,
						error -> assertThat(error.httpStatus()).isEqualTo(307));
		assertThat(this.elsewhere).hasValue(0);
		// redirect를 따라가는 HttpClient는 처음부터 받지 않는다.
		assertThatIllegalArgumentException().isThrownBy(() -> new ModernMcpClient(URI.create(origin() + "/mcp"),
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), MCP_JSON));
	}
}
