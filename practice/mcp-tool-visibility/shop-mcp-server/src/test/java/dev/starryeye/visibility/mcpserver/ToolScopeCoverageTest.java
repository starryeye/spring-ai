package dev.starryeye.visibility.mcpserver;

import dev.starryeye.visibility.mcpserver.tool.ToolScopeRegistry;

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
		// McpScopeTest의 token은 sub가 user라 점원이다.
		// 점원에게는 숨기는 tool이 없어 전체 목록을 받는다.
		String json = this.mockMvc.perform(McpScopeTest.mcp(McpScopeTest.토큰("products:read"), TOOLS_LIST))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		List<String> listed = JsonPath.read(json, "$.result.tools[*].name");

		assertThat(listed).isNotEmpty();
		assertThat(Set.copyOf(listed)).isEqualTo(this.registry.all().keySet());
	}
}
