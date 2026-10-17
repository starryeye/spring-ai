package dev.starryeye.modern.mcpserver;

import dev.starryeye.modern.mcpserver.ModernTestClient.Response;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP Server가 2026-07-28 요청을 받고, 지금까지의 인가 장치가 그 앞에서 그대로 도는지 본다.
 *
 * <p>SDK transport는 Spring MVC를 거치지 않는 servlet이라, 요청은 실제 HTTP로 보낸다({@link ModernTestClient}).
 * PRM과 {@code Host}·{@code Origin} 검사는 MockMvc로 보낸다.
 * 이 시험들은 {@code Host} header를 바꿔야 하는데 JDK HTTP client로는 바꿀 수 없다.
 * 이 요청들은 servlet filter에서 끝나므로 MockMvc로도 같은 동작을 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
@Import(TestTokens.StubAuthorizationServer.class)
class ModernProtocolTest {

	static final List<String> ALL_TOOLS = List.of("searchProducts", "getStock", "updateStock", "createBasket",
			"addItem", "getBasket", "checkout");

	@DynamicPropertySource
	static void 빈_port에_띄운다(DynamicPropertyRegistry registry) {
		ModernTestClient.listenOnFreePort(registry);
	}

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	ModernTestClient client() {
		return new ModernTestClient(this.port);
	}

	/** 이 서버가 지키는 resource다. 설정의 {@code audiences}와 같다. */
	String resource() {
		return "http://localhost:" + this.port + "/mcp";
	}

	String origin() {
		return "http://localhost:" + this.port;
	}

	String resourceMetadata() {
		return origin() + "/.well-known/oauth-protected-resource/mcp";
	}

	String host() {
		return "localhost:" + this.port;
	}

	/** {@code user}는 점원이다. */
	String 점원() {
		return TestTokens.token("user", resource(), "products:read");
	}

	/** {@code user2}는 손님이다. */
	String 손님() {
		return TestTokens.token("user2", resource(), "products:read");
	}

	static List<String> 이름들(JsonNode tools) {
		List<String> names = new ArrayList<>();
		tools.forEach(tool -> names.add(tool.get("name").asString()));
		return names;
	}

	static List<String> 값들(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asString()));
		return values;
	}

	// ---- 2026-07-28 요청 ----

	@Test
	void initialize_없이_tools_list를_받는다() {
		Response response = client().listTools(점원());

		assertThat(response.status()).isEqualTo(200);
		JsonNode result = response.json().get("result");
		assertThat(이름들(result.get("tools"))).containsExactlyInAnyOrderElementsOf(ALL_TOOLS);
		assertThat(result.get("resultType").asString()).isEqualTo("complete");
		// 목록은 사용자의 역할로 달라지므로 다른 access token과 cache를 나누지 않는다.
		assertThat(result.get("ttlMs").asLong()).isEqualTo(60000L);
		assertThat(result.get("cacheScope").asString()).isEqualTo("private");
		assertThat(response.header("Mcp-Session-Id")).isNull();
	}

	@Test
	void server_discover는_2026_07_28을_알린다() {
		Response response = client().discover(점원());

		assertThat(response.status()).isEqualTo(200);
		JsonNode result = response.json().get("result");
		assertThat(값들(result.get("supportedVersions"))).contains("2026-07-28");
		JsonNode serverInfo = result.get("_meta").get("io.modelcontextprotocol/serverInfo");
		assertThat(serverInfo.get("name").asString()).isEqualTo("modern-shop-mcp-server");
		assertThat(serverInfo.get("version").asString()).isEqualTo("0.0.1");
	}

	@Test
	void 손님에게는_updateStock이_목록에_없다() {
		Response response = client().listTools(손님());

		assertThat(response.status()).isEqualTo(200);
		List<String> names = 이름들(response.json().get("result").get("tools"));
		assertThat(names).hasSize(6).doesNotContain("updateStock");
	}

	@Test
	void 숨긴_tool과_없는_tool의_응답이_같다() {
		Map<String, Object> arguments = Map.of("productId", "p1", "quantity", 3);

		Response hidden = client().callTool(손님(), "updateStock", arguments);
		Response missing = client().callTool(손님(), "nope", arguments);

		assertThat(hidden.status()).isEqualTo(missing.status());
		JsonNode hiddenError = hidden.json().get("error");
		JsonNode missingError = missing.json().get("error");
		assertThat(hiddenError.get("code").asInt()).isEqualTo(missingError.get("code").asInt());
		assertThat(hiddenError.get("message").asString()).isEqualTo("Unknown tool: updateStock");
		assertThat(missingError.get("message").asString()).isEqualTo("Unknown tool: nope");
	}

	@Test
	void 남의_handle은_찾을_수_없다() {
		Response created = client().callTool(점원(), "createBasket", Map.of());
		String basketId = created.json().get("result").get("structuredContent").get("basketId").asString();

		Response result = client().callTool(손님(), "getBasket", Map.of("basketId", basketId));

		assertThat(result.status()).isEqualTo(200);
		JsonNode body = result.json().get("result");
		assertThat(body.get("isError").asBoolean()).isTrue();
		assertThat(body.get("content").get(0).get("text").asString()).contains("찾을 수 없는 장바구니입니다(");
	}

	/**
	 * SDK transport는 본문의 tool 이름이 문자열일 때만 {@code Mcp-Name}과 맞춰 본다.
	 * 숫자나 boolean이면 대조를 건너뛰고, Jackson이 그 값을 문자열로 바꿔 실행하려 한다.
	 * header만 보는 scope 검사를 지나 다른 tool이 실행되면 안 된다.
	 */
	@Test
	void 숫자나_boolean_tool_이름으로는_실행되지_않는다() {
		for (Object name : List.of(1, true)) {
			for (String mcpName : Arrays.asList("searchProducts", null)) {
				Map<String, Object> params = new LinkedHashMap<>();
				params.put("name", name);
				params.put("arguments", Map.of());

				Response response = client().post(점원(), ModernTestClient.headers("tools/call", mcpName),
						ModernTestClient.body("tools/call", params));

				assertThat(response.status()).as("name=%s, Mcp-Name=%s", name, mcpName).isEqualTo(400);
				assertThat(response.json().get("error").get("code").asInt()).isEqualTo(-32602);
			}
		}
	}

	@Test
	void 옛_initialize는_400이다() {
		// 2025-11-25 client가 보내는 그대로다. _meta도 2026-07-28 header도 없다.
		String initialize = """
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
				"capabilities":{},"clientInfo":{"name":"old-client","version":"1.0.0"}}}""";

		Response response = client().post(점원(), Map.of(), initialize);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.header("Mcp-Session-Id")).isNull();
	}

	@Test
	void 오류_본문에_stack_trace가_없다() {
		// sub가 없는 token이면 tool이 사용자를 꺼내다 예외를 던진다. 그 예외가 응답 본문에 나가면 안 된다.
		String withoutSubject = TestTokens.token(TestTokens.ISSUER, null, resource(), Instant.now().plusSeconds(300),
				TestTokens.KEY, "products:read");

		Response response = client().callTool(withoutSubject, "searchProducts", Map.of());

		assertThat(response.json().get("error").get("message").asString()).isEqualTo("Internal error");
		assertThat(response.body()).doesNotContain("stackTrace").doesNotContain("Exception")
				.doesNotContain("dev.starryeye");
	}

	// ---- token 검증과 PRM ----

	@Test
	void token_없으면_401과_resource_metadata를_준다() {
		Response response = client().post(null, ModernTestClient.headers("server/discover", null),
				ModernTestClient.body("server/discover", Map.of()));

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.header("WWW-Authenticate"))
				.contains("resource_metadata=\"" + resourceMetadata() + "\"")
				.contains("scope=\"products:read\"");
		Response metadata = client().get("/.well-known/oauth-protected-resource/mcp");
		assertThat(metadata.json().get("resource").asString()).isEqualTo(resource());
	}

	@Test
	void aud가_다른_token은_401이다() {
		// 같은 Authorization Server가 발급했지만 다른 resource에 쓰라고 받은 token이다.
		String otherAudience = TestTokens.token("user", "http://localhost:9999/mcp", "products:read");

		Response response = client().listTools(otherAudience);

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.header("WWW-Authenticate")).contains("error=\"invalid_token\"");
	}

	@Test
	void iss나_만료나_signature가_틀린_token은_401이다() {
		Instant later = Instant.now().plusSeconds(300);
		// Spring의 JwtTimestampValidator는 60초의 clock skew를 둔다. 그보다 오래 지난 token을 쓴다.
		Instant expired = Instant.now().minusSeconds(120);
		List<String> tokens = List.of(
				TestTokens.token("http://localhost:9999", "user", resource(), later, TestTokens.KEY, "products:read"),
				TestTokens.token(TestTokens.ISSUER, "user", resource(), expired, TestTokens.KEY, "products:read"),
				// kid는 같지만 JWKS의 public key로 signature가 맞지 않는다.
				TestTokens.token(TestTokens.ISSUER, "user", resource(), later, TestTokens.rsaKey(), "products:read"));

		for (String token : tokens) {
			Response response = client().listTools(token);

			assertThat(response.status()).isEqualTo(401);
			assertThat(response.header("WWW-Authenticate")).contains("error=\"invalid_token\"");
		}
	}

	/*
	 * PRM의 resource와 401의 resource_metadata는 요청의 Host가 아니라 설정한 주소(audiences)로 만든다.
	 * Host로 만들면 Host를 바꾼 요청에 다른 서버 주소를 알리게 된다(reverse proxy 뒤에서는 scheme도 틀린다).
	 * RFC 9728 §3.3에 따라 PRM의 resource는 그 PRM 주소를 만든 식별자와 같다.
	 * 그래서 경로 모양은 요청한 PRM 주소를 따르고, scheme·host·port만 설정에서 가져온다.
	 */

	@Test
	void 경로형_PRM은_설정한_resource를_알린다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", host()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(resource()))
				.andExpect(jsonPath("$.authorization_servers[0]").value(TestTokens.ISSUER))
				.andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
				// 이 서버는 mTLS에 묶인 token을 쓰지 않는다. Spring 기본값이 true라 꺼야 한다.
				.andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
				.andExpect(jsonPath("$.scopes_supported").value(contains("products:read")));
	}

	@Test
	void Host를_바꿔도_경로형_PRM의_resource는_설정한_주소다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(resource()));
	}

	@Test
	void 루트형_PRM의_resource는_설정한_주소의_origin이다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource").header("Host", host()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(origin()));
	}

	@Test
	void Host를_바꿔도_루트형_PRM의_resource는_설정한_origin이다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(origin()));
	}

	@Test
	void 그_밖의_경로형_PRM은_설정한_origin에_요청한_경로를_붙인다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/other").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(origin() + "/other"));
	}

	@Test
	void Host를_바꿔도_401의_resource_metadata는_경로형_PRM_주소다() throws Exception {
		// MCP endpoint 밖의 경로는 Host 검사(McpTransportSecurityFilter)를 거치지 않고 바로 401이 된다.
		this.mockMvc.perform(get("/").header("Host", "evil.example"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						containsString("resource_metadata=\"" + resourceMetadata() + "\"")));
		// 허용한 다른 Host로 MCP endpoint를 불러도 같다.
		this.mockMvc.perform(post("/mcp").header("Host", "127.0.0.1:" + this.port))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						containsString("resource_metadata=\"" + resourceMetadata() + "\"")));
	}

	// ---- Origin·Host 검사 ----

	@Test
	void token이_없어도_허용하지_않은_Origin은_인증보다_먼저_403이다() throws Exception {
		this.mockMvc.perform(post("/mcp").header("Host", host()).header("Origin", "http://evil.example"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void token이_없어도_허용하지_않은_Host는_인증보다_먼저_421이다() throws Exception {
		this.mockMvc.perform(post("/mcp").header("Host", "evil.example:" + this.port))
				.andExpect(status().is(421))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}
}
