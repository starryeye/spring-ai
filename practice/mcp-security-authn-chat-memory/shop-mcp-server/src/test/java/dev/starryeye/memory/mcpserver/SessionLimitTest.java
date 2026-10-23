package dev.starryeye.memory.mcpserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * streamable transport가 session 수에 상한을 두고, 오래 쓰지 않은 session을 만료시키는지 본다.
 * 두 context에서 시험한다.
 * 상한은 만료를 기본값(1시간)으로 둔 채 {@code max-sessions}만 2로 낮춘 context에서 본다.
 * 그래야 시험 도중에 만료가 끼어들지 않는다.
 * 만료는 상한 2, 만료 500ms로 낮춘 context에서 본다.
 *
 * <p>transport는 {@code session-idle-timeout} 간격으로 만료를 검사하므로, 쉬는 session은 500ms~1초 사이에 사라진다.
 * 같은 session에 요청을 보내면 쉰 시간이 다시 0이 된다.
 * 그래서 만료를 기다리는 동안에는 그 session에 요청을 보내지 않는다.
 * 대신 상한을 채워 두고, 새 {@code initialize}가 다시 성공하면 자리가 빈 것으로 본다.
 *
 * <p>이 practice는 transport 앞에서 {@code McpSessionBindingFilter}가 session을 사용자에게 묶는다.
 * 이 filter도 {@code session-idle-timeout}이 지난 묶음을 지우고, 묶음이 없는 session ID로 온 요청에 직접 {@code 404}로 답한다.
 * 그래서 만료된 session으로 보낸 요청은 filter를 지나면 transport까지 닿지 못한다.
 * transport의 답은 filter를 거치지 않는 {@code MockMvc}로 transport에 곧장 보내서 본다.
 */
class SessionLimitTest {

	static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

	/**
	 * 자리가 비기를 기다리는 최대 시간이다.
	 * 만료는 1초 안에 일어나므로 넉넉히 둔다.
	 */
	static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

	/**
	 * 상한에 닿기까지 보내는 {@code initialize}의 최대 수다.
	 * 상한이 2라서 만료가 끼어들어도 몇 번이면 닿는다.
	 */
	static final int MAX_ATTEMPTS = 10;

	static MockHttpServletResponse initialize(MockMvc mockMvc) throws Exception {
		return mockMvc.perform(MalformedMessageTest.mcp(MalformedMessageTest.TOKEN,
				McpAuthorizationStandardTest.INITIALIZE)).andReturn().getResponse();
	}

	static String session을_연다(MockMvc mockMvc) throws Exception {
		MockHttpServletResponse response = initialize(mockMvc);
		assertThat(response.getStatus()).isEqualTo(200);
		return response.getHeader("Mcp-Session-Id");
	}

	/** 해당 session에 {@code tools/list}를 보낸다. */
	static MockHttpServletResponse toolsList(MockMvc mockMvc, String sessionId) throws Exception {
		return mockMvc.perform(MalformedMessageTest.mcp(MalformedMessageTest.TOKEN, TOOLS_LIST)
				.header("Mcp-Session-Id", sessionId)).andReturn().getResponse();
	}

	@Nested
	@SpringBootTest(properties = "mcp.limits.max-sessions=2")
	@AutoConfigureMockMvc
	@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
	class 상한 {

		@Autowired
		MockMvc mockMvc;

		@Test
		void 상한을_넘은_initialize는_503이다() throws Exception {
			session을_연다(this.mockMvc);
			session을_연다(this.mockMvc);

			MockHttpServletResponse third = initialize(this.mockMvc);

			JsonNode error = MalformedMessageTest.내부_정보_없는_오류다("상한 초과", third, 503, -32603);
			assertThat(error.get("message").asString()).isEqualTo("Max number of sessions reached");
			assertThat(third.getHeader("Mcp-Session-Id")).isNull();
		}
	}

	/** 남은 session이 다음 테스트의 상한 계산에 섞이지 않도록, 테스트마다 context를 닫고 새로 띄운다. */
	@Nested
	@SpringBootTest(properties = { "mcp.limits.max-sessions=2", "mcp.limits.session-idle-timeout=500ms" })
	@AutoConfigureMockMvc
	@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
	@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
	class 만료 {

		@Autowired
		MockMvc mockMvc;

		@Autowired
		WebApplicationContext context;

		/** filter를 하나도 붙이지 않은 {@code MockMvc}다. 요청이 {@code McpSessionBindingFilter} 없이 transport에 곧장 닿는다. */
		MockMvc transportOnly;

		@BeforeEach
		void transportOnly를_만든다() {
			this.transportOnly = MockMvcBuilders.webAppContextSetup(this.context).build();
		}

		/** {@code 503}이 올 때까지 {@code initialize}를 보낸다. */
		MockHttpServletResponse 상한에_닿을_때까지_initialize한다() throws Exception {
			MockHttpServletResponse response = initialize(this.mockMvc);
			for (int attempt = 1; response.getStatus() != 503 && attempt < MAX_ATTEMPTS; attempt++) {
				response = initialize(this.mockMvc);
			}
			return response;
		}

		/**
		 * 상한이 찬 동안 {@code initialize}는 {@code 503}이다.
		 * {@code 503}이 아닌 응답이 올 때까지 다시 보낸다.
		 */
		MockHttpServletResponse 자리가_빌_때까지_initialize한다() throws Exception {
			Instant deadline = Instant.now().plus(WAIT_LIMIT);
			MockHttpServletResponse response = initialize(this.mockMvc);
			while (response.getStatus() == 503 && Instant.now().isBefore(deadline)) {
				Thread.sleep(50);
				response = initialize(this.mockMvc);
			}
			return response;
		}

		/**
		 * transport에서 만료된 session의 ID를 돌려준다.
		 * 상한을 채우고, 새 {@code initialize}가 성공할 때까지 기다린다.
		 * 먼저 연 session이 먼저 만료되므로, 새 {@code initialize}가 성공했다면 먼저 연 session은 만료됐다.
		 */
		String 만료된_session을_만든다() throws Exception {
			String first = session을_연다(this.mockMvc);
			session을_연다(this.mockMvc);
			assertThat(자리가_빌_때까지_initialize한다().getStatus()).isEqualTo(200);
			return first;
		}

		@Test
		void 만료된_session의_요청은_404다() throws Exception {
			String first = 만료된_session을_만든다();

			MockHttpServletResponse response = toolsList(this.transportOnly, first);

			JsonNode error = MalformedMessageTest.내부_정보_없는_오류다("만료된 session", response, 404, -32603);
			assertThat(error.get("message").asString()).isEqualTo("Session not found: " + first);
		}

		@Test
		void 만료로_자리가_비면_다시_initialize할_수_있다() throws Exception {
			assertThat(상한에_닿을_때까지_initialize한다().getStatus()).isEqualTo(503);

			MockHttpServletResponse again = 자리가_빌_때까지_initialize한다();

			assertThat(again.getStatus()).isEqualTo(200);
			assertThat(again.getHeader("Mcp-Session-Id")).isNotBlank();
		}

		/**
		 * 묶음의 만료와 transport의 만료가 같은 {@code session-idle-timeout}에서 나오는지 본다.
		 * 묶음이 transport보다 오래 남으면 만료된 session의 요청이 filter를 지나 transport까지 가므로, filter가 직접 답하는 {@code 404}를 볼 수 없다.
		 *
		 * <p>두 답은 모양이 다르다.
		 * {@code McpSessionBindingFilter}는 {@code response.sendError(404, ...)}로 답한다.
		 * MockMvc는 오류 page로 보내지 않으므로 이 답의 본문은 비어 있고, 상태 코드와 message만 남는다.
		 * 실제 서버에서는 Spring Boot의 오류 page가 JSON-RPC가 아닌 {@code timestamp}·{@code status}·{@code error}·{@code path} 본문을 쓴다.
		 * transport는 JSON-RPC 오류 본문으로 답한다.
		 * client는 상태 코드로 새 session을 열지 정하므로 둘 다 {@code 404}이면 된다.
		 */
		@Test
		void binding_만료와_transport_만료가_같다() throws Exception {
			String first = 만료된_session을_만든다();

			MockHttpServletResponse binding = toolsList(this.mockMvc, first);
			MockHttpServletResponse transport = toolsList(this.transportOnly, first);

			assertThat(binding.getStatus()).as("binding filter").isEqualTo(404);
			assertThat(binding.getErrorMessage()).isEqualTo("MCP session not found");
			assertThat(binding.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
			assertThat(transport.getStatus()).as("transport").isEqualTo(404);
			JsonNode error = MalformedMessageTest.내부_정보_없는_오류다("transport", transport, 404, -32603);
			assertThat(error.get("message").asString()).isEqualTo("Session not found: " + first);
		}
	}
}
