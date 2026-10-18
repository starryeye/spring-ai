package dev.starryeye.modern.agent.mcp;

import dev.starryeye.modern.agent.security.BearerChallenge;
import dev.starryeye.modern.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 2026-07-28 형식으로 MCP Server를 부르는 client다.
 *
 * <p>2026-07-28에는 {@code initialize}와 session이 없다.
 * 그래서 요청 하나가 혼자 서도록, 요청마다 같은 정보를 다시 보낸다.
 * 본문 {@code params._meta}에는 버전·client capability·client 정보를 넣는다.
 * header에는 {@code MCP-Protocol-Version}, {@code Mcp-Method}, ({@code tools/call}이면) {@code Mcp-Name}을 붙인다.
 * MCP Server의 scope filter는 본문을 읽지 않고 이 header만 보고 scope를 검사한다.
 * SDK transport는 header와 본문이 다르면 실행하지 않고 {@code -32020}으로 답한다.
 *
 * <p>요청 하나는 {@code POST} 하나이고, 응답은 JSON 하나나 SSE stream이다.
 * MCP Server가 둘 중 하나를 고르므로 둘 다 읽는다.
 * JSON 타입은 SDK의 {@link McpSchema}·{@link JsonRpc}를 쓰고, HTTP 요청과 응답 해석만 여기서 한다.
 * MCP Java SDK 2.2.0-M1에는 아직 2026-07-28 client가 없기 때문이다.
 *
 * <p>오류는 HTTP status와 JSON-RPC {@code error.code}로 가른다.
 * filter와 SDK가 만든 오류 본문에는 {@code id}가 없을 수 있어서, 오류는 요청의 {@code id}와 맞춰 보지 않는다.
 * 결과는 요청의 {@code id}와 같은 것만 받는다.
 * 응답을 받지 못한 경우(연결 거절, 시간 초과, 중단)도 {@link McpTransportException}으로 알린다.
 * 그래서 이 client가 던지는 MCP 쪽 실패는 모두 {@link ModernMcpException}이거나 step-up 예외다.
 */
public class ModernMcpClient {

	private static final Logger log = LoggerFactory.getLogger(ModernMcpClient.class);

	/** 이 client가 말하는 MCP 버전이다. header와 {@code _meta}에 같은 값을 넣는다. */
	public static final String PROTOCOL_VERSION = "2026-07-28";

	static final String CLIENT_NAME = "modern-shop-agent";

	static final String CLIENT_VERSION = "0.0.1";

	/**
	 * 요청마다 {@code params._meta}에 넣는 값이다.
	 * web agent는 elicitation·sampling·roots를 쓰지 않으므로 client capability는 빈 object다.
	 */
	private static final Map<String, Object> META = Map.of(
			McpSchema.MetaKeys.PROTOCOL_VERSION, PROTOCOL_VERSION,
			McpSchema.MetaKeys.CLIENT_CAPABILITIES, McpSchema.ClientCapabilities.NONE,
			McpSchema.MetaKeys.CLIENT_INFO, McpSchema.Implementation.builder(CLIENT_NAME, CLIENT_VERSION).build());

	/**
	 * 응답 header를 기다리는 시간이다.
	 * MCP Server가 멈추면 채팅 요청도 함께 멈추므로 끝을 둔다.
	 * 값은 Spring AI MCP client의 기본 request timeout과 같다.
	 */
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private final URI endpoint;

	private final HttpClient http;

	private final McpJsonMapper json;

	private final Duration requestTimeout;

	private final AtomicLong ids = new AtomicLong();

	/**
	 * redirect를 따라가면 access token이 담긴 요청이 MCP Server가 고른 다른 주소로 간다.
	 * 그래서 redirect를 따라가는 {@link HttpClient}는 받지 않는다.
	 */
	public ModernMcpClient(URI endpoint, HttpClient http, McpJsonMapper json) {
		this(endpoint, http, json, REQUEST_TIMEOUT);
	}

	/** 테스트가 기다리는 시간을 줄이려고 둔다. */
	ModernMcpClient(URI endpoint, HttpClient http, McpJsonMapper json, Duration requestTimeout) {
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		this.http = Objects.requireNonNull(http, "http");
		this.json = Objects.requireNonNull(json, "json");
		this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
		if (http.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("MCP 요청은 redirect를 따라가지 않아야 한다(지금 " + http.followRedirects() + ")");
		}
	}

	public URI endpoint() {
		return this.endpoint;
	}

	/**
	 * MCP Server가 2026-07-28을 지원하는지 묻는다.
	 * 목록에 없으면 이 client의 요청은 모두 거절된다.
	 * 그래서 tool을 부르기 전에 멈추고 이유를 남긴다.
	 */
	public McpSchema.DiscoverResult discover(String accessToken) {
		McpSchema.DiscoverResult result = send(accessToken, McpSchema.METHOD_SERVER_DISCOVER, null,
				Map.of("_meta", META), McpSchema.DiscoverResult.class);
		if (!result.supportedVersions().contains(PROTOCOL_VERSION)) {
			throw new McpProtocolMismatchException(200, null, "MCP Server가 " + PROTOCOL_VERSION
					+ "을 지원하지 않는다(supportedVersions=" + result.supportedVersions() + ")");
		}
		return result;
	}

	/** tool 목록의 한 쪽이다. {@code cursor}가 {@code null}이면 첫 쪽이다. */
	public McpSchema.ListToolsResult listTools(String accessToken, String cursor) {
		return send(accessToken, McpSchema.METHOD_TOOLS_LIST, null, new McpSchema.PaginatedRequest(cursor, META),
				McpSchema.ListToolsResult.class);
	}

	/**
	 * tool을 부른다.
	 * tool이 실패해도 MCP Server는 {@code isError}가 {@code true}인 결과로 답하므로, 그때는 예외가 아니라 결과다.
	 */
	public McpSchema.CallToolResult callTool(String accessToken, String name, Map<String, Object> arguments) {
		McpSchema.CallToolRequest params = McpSchema.CallToolRequest.builder(name)
				.arguments(arguments)
				.meta(META)
				.build();
		return send(accessToken, McpSchema.METHOD_TOOLS_CALL, name, params, McpSchema.CallToolResult.class);
	}

	/**
	 * {@code Mcp-Name} header 값이다.
	 * header에는 출력 가능한 ASCII(0x20~0x7E)만 그대로 쓸 수 있다.
	 * 그 밖의 글자가 하나라도 있으면 UTF-8 byte를 base64로 바꿔 {@code =?base64?...?=}로 적는다.
	 * MCP Server는 같은 규칙으로 풀어 본문의 이름과 맞춰 본다.
	 */
	static String mcpName(String name) {
		boolean printableAscii = name.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
		if (printableAscii) {
			return name;
		}
		return "=?base64?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
	}

	private <T extends McpSchema.Result> T send(String accessToken, String method, String name, Object params,
			Class<T> type) {
		long id = this.ids.incrementAndGet();
		HttpRequest request = request(accessToken, method, name, new JsonRpc.JSONRPCRequest(method, id, params));
		HttpResponse<InputStream> response = exchange(request);
		try (InputStream body = response.body()) {
			JsonRpc.JSONRPCResponse rpc = read(response, body, id);
			return result(response, rpc, id, name, type);
		}
		catch (IOException ex) {
			throw transportFailure("MCP Server의 응답을 읽다 연결이 끊겼다", ex);
		}
	}

	private HttpRequest request(String accessToken, String method, String name, JsonRpc.JSONRPCRequest body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(this.endpoint)
				.timeout(this.requestTimeout)
				.header("Content-Type", APPLICATION_JSON)
				.header("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM)
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", method)
				.POST(HttpRequest.BodyPublishers.ofByteArray(write(body)));
		if (name != null) {
			builder.header("Mcp-Name", mcpName(name));
		}
		if (accessToken != null) {
			builder.header("Authorization", "Bearer " + accessToken);
		}
		return builder.build();
	}

	private byte[] write(JsonRpc.JSONRPCRequest body) {
		try {
			return this.json.writeValueAsBytes(body);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private HttpResponse<InputStream> exchange(HttpRequest request) {
		try {
			return this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (HttpTimeoutException ex) {
			throw transportFailure("MCP Server가 " + describe(this.requestTimeout) + " 안에 답하지 않았다", ex);
		}
		catch (IOException ex) {
			throw transportFailure("MCP Server에 연결하지 못했다", ex);
		}
		catch (InterruptedException ex) {
			// 기다리던 thread를 멈추라는 요청이므로, 예외로 바꾸더라도 interrupt 표시는 되돌려 둔다.
			Thread.currentThread().interrupt();
			throw transportFailure("MCP Server의 응답을 기다리다 중단되었다", ex);
		}
	}

	/**
	 * 모델에게 갈 수 있는 문장이라 내부 주소는 message에 넣지 않고 log에만 남긴다.
	 * 원인 예외는 cause로 둔다.
	 */
	private McpTransportException transportFailure(String message, Exception cause) {
		log.warn("{} ({}): {}", message, this.endpoint, cause.toString());
		return new McpTransportException(message, cause);
	}

	private static String describe(Duration duration) {
		return (duration.toMillis() % 1000 == 0) ? duration.toSeconds() + "초" : duration.toMillis() + "ms";
	}

	/**
	 * 응답 본문에서 이 요청의 JSON-RPC 응답을 꺼낸다.
	 * 본문이 JSON-RPC 응답이 아니면(빈 본문, HTML 오류 page) {@code null}이다.
	 */
	private JsonRpc.JSONRPCResponse read(HttpResponse<InputStream> response, InputStream body, long id)
			throws IOException {
		String contentType = response.headers().firstValue("Content-Type").orElse("");
		if (contentType.regionMatches(true, 0, TEXT_EVENT_STREAM, 0, TEXT_EVENT_STREAM.length())) {
			return readEventStream(body, id);
		}
		return parse(new String(body.readAllBytes(), StandardCharsets.UTF_8));
	}

	/**
	 * SSE stream에서 이 요청의 응답을 찾는다.
	 * {@code data:} 줄을 빈 줄까지 모아 JSON 하나로 읽는다.
	 * keep-alive 같은 {@code :} 주석, 진행 알림, 다른 {@code id}의 응답은 건너뛴다.
	 * 찾으면 stream을 더 읽지 않는다.
	 */
	private JsonRpc.JSONRPCResponse readEventStream(InputStream body, long id) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
		StringBuilder data = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				JsonRpc.JSONRPCResponse found = dispatch(data, id);
				if (found != null) {
					return found;
				}
			}
			else if (line.startsWith("data:")) {
				String value = line.substring("data:".length());
				data.append(data.isEmpty() ? "" : "\n").append(value.startsWith(" ") ? value.substring(1) : value);
			}
		}
		return dispatch(data, id);
	}

	private JsonRpc.JSONRPCResponse dispatch(StringBuilder data, long id) {
		if (data.isEmpty()) {
			return null;
		}
		JsonRpc.JSONRPCResponse response = parse(data.toString());
		data.setLength(0);
		if (response == null) {
			return null;
		}
		boolean ours = sameId(response.id(), id) || (response.error() != null && response.id() == null);
		return ours ? response : null;
	}

	private JsonRpc.JSONRPCResponse parse(String text) {
		if (text.isBlank()) {
			return null;
		}
		try {
			return (JsonRpc.deserializeMessage(this.json, text) instanceof JsonRpc.JSONRPCResponse response)
					? response : null;
		}
		catch (IOException | IllegalArgumentException ex) {
			log.debug("JSON-RPC 응답이 아닌 본문을 건너뛴다: {}", ex.getMessage());
			return null;
		}
	}

	private static boolean sameId(Object responseId, long id) {
		return responseId instanceof Number number && number.longValue() == id;
	}

	/**
	 * status와 본문을 결과나 예외로 바꾼다.
	 * {@code 403 insufficient_scope}와 {@code 429}는 header가 알려 주는 값이 있어 먼저 본다.
	 */
	private <T extends McpSchema.Result> T result(HttpResponse<InputStream> response, JsonRpc.JSONRPCResponse rpc,
			long id, String name, Class<T> type) {
		int status = response.statusCode();
		if (status == 403) {
			BearerChallenge challenge = response.headers().firstValue("WWW-Authenticate")
					.flatMap(BearerChallenge::parse)
					.filter(BearerChallenge::insufficientScope)
					.orElse(null);
			if (challenge != null) {
				throw new StepUpRequiredException(challenge.scopes(), name);
			}
		}
		JsonRpc.JSONRPCResponse.JSONRPCError error = (rpc != null) ? rpc.error() : null;
		if (status == 429) {
			throw new ToolRateLimitedException(retryAfter(response), (error != null) ? error.code() : null,
					(error != null) ? error.message() : "MCP Server가 tool 호출이 너무 잦다고 답했다(429)");
		}
		if (error != null) {
			throw error(status, error);
		}
		if (status / 100 != 2 || rpc == null) {
			throw new ModernMcpException(status, null, "MCP Server가 JSON-RPC 결과 없이 HTTP " + status + "로 답했다");
		}
		if (!sameId(rpc.id(), id)) {
			throw new ModernMcpException(status, null, "MCP Server가 다른 요청(id=" + rpc.id() + ")의 결과를 주었다");
		}
		T result = convert(status, rpc.result(), type);
		if (!McpSchema.ResultType.COMPLETE.equals(result.resultType())) {
			// 이 client는 client capability를 하나도 선언하지 않으므로 MCP Server가 입력을 되물을 수 없다.
			throw new ModernMcpException(status, null, "이 client가 다루지 않는 resultType이다: " + result.resultType());
		}
		return result;
	}

	private static ModernMcpException error(int status, JsonRpc.JSONRPCResponse.JSONRPCError error) {
		if (UnknownToolException.matches(error.code(), error.message())) {
			return new UnknownToolException(status, error.message());
		}
		if (McpProtocolMismatchException.matches(error.code())) {
			log.warn("MCP Server가 요청 형식을 받지 않았다: {} {}", error.code(), error.message());
			return new McpProtocolMismatchException(status, error.code(), error.message());
		}
		return new ModernMcpException(status, error.code(), error.message());
	}

	private <T> T convert(int status, Object result, Class<T> type) {
		try {
			return this.json.convertValue(result, type);
		}
		catch (IllegalArgumentException ex) {
			throw new ModernMcpException(status, null, "MCP 결과를 " + type.getSimpleName() + "로 읽지 못했다");
		}
	}

	/** {@code Retry-After}의 초다. HTTP-date 같은 다른 형식이면 모르는 것으로 둔다. */
	private static Duration retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After")
				.filter(value -> value.trim().matches("\\d{1,9}"))
				.map(value -> Duration.ofSeconds(Long.parseLong(value.trim())))
				.orElse(null);
	}
}
