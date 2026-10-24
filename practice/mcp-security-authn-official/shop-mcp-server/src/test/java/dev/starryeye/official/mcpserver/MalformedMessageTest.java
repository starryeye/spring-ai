package dev.starryeye.official.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 형식이 틀린 JSON-RPC 본문을 token이 있는 사용자가 보내도, 응답에 서버 내부 정보(stack trace, class 이름)가 나오지 않는지 본다.
 *
 * <p>Spring AI 2.0.2의 streamable transport는 처리하지 못한 요청에 예외 객체({@code McpError})를 그대로 본문으로 쓴다.
 * Spring MVC는 그 {@code stackTrace}까지 JSON으로 직렬화한다.
 * router filter가 이 본문을 {@code code}와 {@code message}만 남긴 JSON-RPC 오류로 바꾼다.
 *
 * <p>깨진 {@code id}나 {@code jsonrpc}를 읽을 때 MCP Java SDK 2.1.0의 Jackson 3 mapper는 변환 오류({@code JacksonException})를
 * {@code IllegalArgumentException}으로 감싼다.
 * transport는 이 예외를 형식 오류로 보고 {@code 400}과 {@code -32600}으로 답한다.
 * 본문은 session을 찾기 전에 읽으므로, session 안에서 보내도 같은 응답이다.
 *
 * <p>{@code Mcp-Session-Id}를 붙인 {@code initialize}는 이미 연 session을 다시 여는 요청이다.
 * Spring AI 2.0.2의 transport는 이 요청에 {@code 400}과 {@code -32603}으로 답한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class MalformedMessageTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	/** 응답 본문에 나오면 안 되는 내부 정보의 흔적이다. */
	static final List<String> INTERNALS = List.of("stackTrace", "localizedMessage", "cause", "className", "Exception",
			"dev.starryeye", "io.modelcontextprotocol", "org.springframework", "tools.jackson", "java.");

	static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	static final String TOKEN =
			McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER, McpAuthorizationStandardTest.RESOURCE);

	@Autowired
	MockMvc mockMvc;

	static MockHttpServletRequestBuilder mcp(String token, String body) {
		return post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", McpAuthorizationStandardTest.HOST)
				.header("MCP-Protocol-Version", "2025-11-25")
				.header("Authorization", "Bearer " + token)
				.content(body);
	}

	/**
	 * 응답이 내부 정보 없는 JSON-RPC 오류인지 보고, 그 {@code error}를 돌려준다.
	 * 본문의 field는 {@code jsonrpc}·{@code id}·{@code error}뿐이고, {@code error}의 field는 {@code code}·{@code message}뿐이다.
	 * transport는 오류 응답에 요청의 {@code id}를 넘겨주지 않으므로 {@code id}는 {@code null}이다.
	 */
	static JsonNode 내부_정보_없는_오류다(String label, MockHttpServletResponse response, int status, int code)
			throws Exception {
		String text = response.getContentAsString(StandardCharsets.UTF_8);

		assertThat(text).as(label).doesNotContain(INTERNALS);
		assertThat(response.getStatus()).as(label + " 본문: " + text).isEqualTo(status);
		JsonNode body = JSON.readTree(text);
		assertThat(body.propertyNames()).as(label).containsExactlyInAnyOrder("jsonrpc", "id", "error");
		assertThat(body.get("jsonrpc").asString()).as(label).isEqualTo("2.0");
		assertThat(body.get("id").isNull()).as(label).isTrue();
		JsonNode error = body.get("error");
		assertThat(error.propertyNames()).as(label).containsExactlyInAnyOrder("code", "message");
		assertThat(error.get("code").asInt()).as(label).isEqualTo(code);
		return error;
	}

	static String initialize(String jsonrpcAndId) {
		return "{" + jsonrpcAndId + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
				+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0.0\"}}}";
	}

	MockHttpServletResponse 보낸다(String body, String sessionId) throws Exception {
		var request = mcp(TOKEN, body);
		if (sessionId != null) {
			request.header("Mcp-Session-Id", sessionId);
		}
		return this.mockMvc.perform(request).andReturn().getResponse();
	}

	String session을_연다() throws Exception {
		String sessionId = this.mockMvc.perform(mcp(TOKEN, McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader("Mcp-Session-Id");
		this.mockMvc.perform(mcp(TOKEN, INITIALIZED).header("Mcp-Session-Id", sessionId))
				.andExpect(status().isAccepted());
		return sessionId;
	}

	@Test
	void 깨진_id나_jsonrpc는_400이고_내부_정보가_없다() throws Exception {
		Map<String, String> bodies = new LinkedHashMap<>();
		bodies.put("id object", initialize("\"jsonrpc\":\"2.0\",\"id\":{\"a\":1}"));
		bodies.put("id 배열", initialize("\"jsonrpc\":\"2.0\",\"id\":[1]"));
		bodies.put("jsonrpc 빈 문자열", initialize("\"jsonrpc\":\"\",\"id\":1"));
		bodies.put("jsonrpc 없음", initialize("\"id\":1"));

		for (Map.Entry<String, String> body : bodies.entrySet()) {
			JsonNode error = 내부_정보_없는_오류다(body.getKey(), 보낸다(body.getValue(), null), 400, -32600);
			assertThat(error.get("message").asString()).as(body.getKey()).isEqualTo("Invalid message format");
		}
	}

	@Test
	void session_ID를_붙인_initialize는_400이고_내부_정보가_없다() throws Exception {
		String sessionId = session을_연다();

		JsonNode error = 내부_정보_없는_오류다("session ID를 붙인 initialize",
				보낸다(McpAuthorizationStandardTest.INITIALIZE, sessionId), 400, -32603);
		assertThat(error.get("message").asString()).isEqualTo("Session already initialized");
	}

	@Test
	void session_안의_깨진_본문도_내부_정보가_없다() throws Exception {
		String sessionId = session을_연다();
		Map<String, String> bodies = new LinkedHashMap<>();
		bodies.put("tools/list id object", "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"tools/list\"}");
		bodies.put("tools/list jsonrpc 빈 문자열", "{\"jsonrpc\":\"\",\"id\":3,\"method\":\"tools/list\"}");

		for (Map.Entry<String, String> body : bodies.entrySet()) {
			JsonNode error = 내부_정보_없는_오류다(body.getKey(), 보낸다(body.getValue(), sessionId), 400, -32600);
			assertThat(error.get("message").asString()).as(body.getKey()).isEqualTo("Invalid message format");
		}
	}
}
