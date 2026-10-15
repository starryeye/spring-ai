package dev.starryeye.authz.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자(token의 {@code sub})마다 {@code tools/call} 속도를 제한하는지 실제 요청으로 본다
 * (MCP 2025-11-25 Tools — Security Considerations: 서버는 tool 호출 속도를 제한해야 한다, MUST).
 * 상한을 2로 낮춘 context에서 시험한다.
 */
@SpringBootTest(properties = "mcp.limits.tool-calls-per-minute=2")
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class ToolCallRateLimitTest {

	static final String GET_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getStock",\
			"arguments":{"productId":"p1"}}}""";

	@Autowired
	MockMvc mockMvc;

	static String 토큰(String subject) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, subject, Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of("products:read"));
	}

	String session을_연다(String token) throws Exception {
		String sessionId = this.mockMvc.perform(McpScopeTest.mcp(token, McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader("Mcp-Session-Id");
		this.mockMvc.perform(McpScopeTest.mcp(token, ParserDifferentialTest.INITIALIZED)
						.header("Mcp-Session-Id", sessionId))
				.andExpect(status().isAccepted());
		return sessionId;
	}

	ResultActions 재고를_묻는다(String token, String sessionId) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, GET_STOCK).header("Mcp-Session-Id", sessionId));
	}

	@Test
	void 한_사용자가_1분에_상한보다_많이_tool을_부르면_429와_Retry_After를_받고_다른_사용자는_따로_센다() throws Exception {
		String alice = 토큰("rate-alice");
		String aliceSession = session을_연다(alice);
		재고를_묻는다(alice, aliceSession).andExpect(status().isOk());
		재고를_묻는다(alice, aliceSession).andExpect(status().isOk());

		재고를_묻는다(alice, aliceSession)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(content().string(containsString("\"code\":-32000")));

		String bob = 토큰("rate-bob");
		재고를_묻는다(bob, session을_연다(bob)).andExpect(status().isOk());
	}
}
