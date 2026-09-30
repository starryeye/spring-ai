package dev.starryeye.stateless.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * session 없이 도는 MCP Server가 실제 요청에 어떻게 답하는지 본다.
 * session header가 없고, GET stream과 DELETE가 없으며, session 없이 보낸 tools/call도 처리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class StatelessTransportTest {

	@Autowired
	MockMvc mockMvc;

	static final String GET_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getStock",\
			"arguments":{"productId":"p1"}}}""";

	@Test
	void initialize_응답에_session_header가_없다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(McpScopeTest.토큰("products:read"), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Mcp-Session-Id"));
	}

	@Test
	void session_없이_보낸_tools_call도_처리한다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(McpScopeTest.토큰("products:read"), GET_STOCK))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("p1")));
	}

	@Test
	void GET_stream은_405다() throws Exception {
		this.mockMvc.perform(get("/mcp")
						.header("Accept", "text/event-stream")
						.header("Host", McpAuthorizationStandardTest.HOST)
						.header("Authorization", "Bearer " + McpScopeTest.토큰("products:read")))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void DELETE는_성공하지_않는다() throws Exception {
		int status = this.mockMvc.perform(delete("/mcp")
						.header("Host", McpAuthorizationStandardTest.HOST)
						.header("Authorization", "Bearer " + McpScopeTest.토큰("products:read")))
				.andReturn().getResponse().getStatus();
		// stateless transport의 router에는 GET·POST만 있다. 실제 값(404나 405)은 보고서와 안내서에 적는다.
		assertThat(status).isGreaterThanOrEqualTo(400);
	}
}
