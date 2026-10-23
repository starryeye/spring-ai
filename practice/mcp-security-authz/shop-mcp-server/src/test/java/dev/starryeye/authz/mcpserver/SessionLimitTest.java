package dev.starryeye.authz.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * streamable transport가 session 수에 상한을 두고, 오래 쓰지 않은 session을 만료시키는지 본다.
 * 상한을 2, 만료를 500ms로 낮춘 context에서 시험한다.
 *
 * <p>transport는 {@code session-idle-timeout} 간격으로 만료를 검사하므로, 쉬는 session은 500ms~1초 사이에 사라진다.
 * 같은 session에 요청을 보내면 쉰 시간이 다시 0이 된다.
 * 그래서 만료를 기다리는 동안에는 그 session에 요청을 보내지 않는다.
 * 대신 상한을 채워 두고, 새 {@code initialize}가 다시 성공하면 자리가 빈 것으로 본다.
 *
 * <p>남은 session이 다음 테스트의 상한 계산에 섞이지 않도록, 테스트마다 context를 닫고 새로 띄운다.
 */
@SpringBootTest(properties = { "mcp.limits.max-sessions=2", "mcp.limits.session-idle-timeout=500ms" })
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SessionLimitTest {

	static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

	/** 자리가 비기를 기다리는 최대 시간이다. 만료는 1초 안에 일어나므로 넉넉히 둔다. */
	static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

	static final String TOKEN = McpScopeTest.토큰("products:read");

	@Autowired
	MockMvc mockMvc;

	MockHttpServletResponse initialize() throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(TOKEN, McpAuthorizationStandardTest.INITIALIZE))
				.andReturn().getResponse();
	}

	String session을_연다() throws Exception {
		MockHttpServletResponse response = initialize();
		assertThat(response.getStatus()).isEqualTo(200);
		return response.getHeader("Mcp-Session-Id");
	}

	/** 상한이 찬 동안 {@code initialize}는 {@code 503}이다. {@code 503}이 아닌 응답이 올 때까지 다시 보낸다. */
	MockHttpServletResponse 자리가_빌_때까지_initialize한다() throws Exception {
		Instant deadline = Instant.now().plus(WAIT_LIMIT);
		MockHttpServletResponse response = initialize();
		while (response.getStatus() == 503 && Instant.now().isBefore(deadline)) {
			Thread.sleep(50);
			response = initialize();
		}
		return response;
	}

	@Test
	void 상한을_넘은_initialize는_503이다() throws Exception {
		session을_연다();
		session을_연다();

		MockHttpServletResponse third = initialize();

		JsonNode error = MalformedMessageTest.내부_정보_없는_오류다("상한 초과", third, 503, -32603);
		assertThat(error.get("message").asString()).isEqualTo("Max number of sessions reached");
		assertThat(third.getHeader("Mcp-Session-Id")).isNull();
	}

	@Test
	void 만료된_session의_요청은_404다() throws Exception {
		String first = session을_연다();
		// 상한을 채운다.
		// 먼저 연 session이 먼저 만료되므로, 새 initialize가 성공했다면 first는 만료됐다.
		session을_연다();
		assertThat(자리가_빌_때까지_initialize한다().getStatus()).isEqualTo(200);

		MockHttpServletResponse response = this.mockMvc
				.perform(McpScopeTest.mcp(TOKEN, TOOLS_LIST).header("Mcp-Session-Id", first))
				.andReturn().getResponse();

		JsonNode error = MalformedMessageTest.내부_정보_없는_오류다("만료된 session", response, 404, -32603);
		assertThat(error.get("message").asString()).isEqualTo("Session not found: " + first);
	}

	@Test
	void 만료로_자리가_비면_다시_initialize할_수_있다() throws Exception {
		session을_연다();
		session을_연다();
		assertThat(initialize().getStatus()).isEqualTo(503);

		MockHttpServletResponse again = 자리가_빌_때까지_initialize한다();

		assertThat(again.getStatus()).isEqualTo(200);
		assertThat(again.getHeader("Mcp-Session-Id")).isNotBlank();
	}
}
