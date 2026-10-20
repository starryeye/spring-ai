package dev.starryeye.modern.mcpserver;

import dev.starryeye.modern.mcpserver.tool.ShopTool;
import dev.starryeye.modern.mcpserver.tool.ToolScopeRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopMcpServerApplicationTests {

	@Autowired
	List<ShopTool> shopTools;

	@Autowired
	ToolScopeRegistry registry;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 401과 함께 {@code WWW-Authenticate} 스킴이 {@code Bearer} 인지 본다. Boot 기본 보안(Basic)으로
	 * 막혀도 401이므로, 스킴이 OAuth2 Resource Server가 연결됐다는 증거다.
	 * {@code Host}는 {@code McpTransportSecurityFilter}가 인증보다 먼저 보므로 허용 값으로 넣는다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Host", "localhost:8181")
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		mockMvc.perform(post("/"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	/**
	 * 서버가 내놓는 tool 정의와 scope 표가 같은 목록에서 나오는지 본다.
	 * 표에서 빠진 쓰기 tool은 기본 scope만 요구하게 되므로(fail-open) 둘이 어긋나면 안 된다.
	 */
	@Test
	void tool_7개가_등록되고_scope_표와_같다() {
		List<String> toolNames = this.shopTools.stream().map(tool -> tool.tool().name()).toList();

		assertThat(toolNames).containsExactlyInAnyOrder("searchProducts", "getStock", "updateStock",
				"createBasket", "addItem", "getBasket", "checkout");
		assertThat(this.registry.all().keySet()).containsExactlyInAnyOrderElementsOf(toolNames);
	}
}
