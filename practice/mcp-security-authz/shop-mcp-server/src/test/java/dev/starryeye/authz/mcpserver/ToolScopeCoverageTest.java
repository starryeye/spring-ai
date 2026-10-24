package dev.starryeye.authz.mcpserver;

import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서버가 실제로 내놓는 tool과 scope 표({@link ToolScopeRegistry})가 같은지 본다.
 *
 * <p>표에 없는 tool은 기본 scope만 요구한다.
 * 그래서 표에서 빠진 쓰기 tool은 조회 token으로도 불린다(fail-open).
 * 표를 만드는 방식이 Spring AI가 tool을 등록하는 방식과 어긋나면 이 테스트가 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class ToolScopeCoverageTest {

	static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ToolScopeRegistry registry;

	@Test
	void 서버가_tools_list에_내놓는_tool과_scope_표의_tool이_같다() throws Exception {
		String token = McpScopeTest.토큰("products:read");
		String sessionId = this.mockMvc.perform(McpScopeTest.mcp(token, McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader("Mcp-Session-Id");
		this.mockMvc.perform(McpScopeTest.mcp(token, ParserDifferentialTest.INITIALIZED)
						.header("Mcp-Session-Id", sessionId))
				.andExpect(status().isAccepted());

		String body = this.mockMvc.perform(McpScopeTest.mcp(token, TOOLS_LIST).header("Mcp-Session-Id", sessionId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		// Streamable HTTP는 응답을 SSE event 하나로 보낸다.
		// data 줄이 JSON-RPC 응답이다.
		String json = body.lines()
				.filter(line -> line.startsWith("data:"))
				.map(line -> line.substring("data:".length()))
				.findFirst()
				.orElseThrow();
		List<String> listed = JsonPath.read(json, "$.result.tools[*].name");

		assertThat(listed).isNotEmpty();
		assertThat(Set.copyOf(listed)).isEqualTo(this.registry.all().keySet());
	}
}
