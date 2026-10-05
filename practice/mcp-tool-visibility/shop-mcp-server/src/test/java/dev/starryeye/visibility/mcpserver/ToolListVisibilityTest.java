package dev.starryeye.visibility.mcpserver;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 서버가 사용자 역할에 따라 다른 tool 목록을 주는지 본다.
 * 목록은 역할로만 달라지고, token의 scope나 요청 횟수로는 달라지지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class ToolListVisibilityTest {

	static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":3,"method":"tools/list"}""";

	@Autowired
	MockMvc mockMvc;

	static String 토큰(String subject, String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, subject, Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	String 응답(String token) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, TOOLS_LIST))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	List<String> 목록(String token) throws Exception {
		return JsonPath.read(응답(token), "$.result.tools[*].name");
	}

	@Test
	void 점원은_tool_7개를_모두_받는다() throws Exception {
		assertThat(목록(토큰("user", "products:read"))).containsExactlyInAnyOrder("searchProducts", "getStock",
				"updateStock", "createBasket", "addItem", "getBasket", "checkout");
	}

	@Test
	void 손님은_updateStock만_빠진_목록을_같은_순서로_받는다() throws Exception {
		List<String> staff = 목록(토큰("user", "products:read"));
		List<String> customer = 목록(토큰("user2", "products:read"));

		// 절대 순서는 실행 환경마다 다를 수 있어서, 점원 목록에서 updateStock만 뺀 것과 비교한다.
		assertThat(customer).isEqualTo(staff.stream().filter(name -> !name.equals("updateStock")).toList());
	}

	@Test
	void 같은_사용자는_몇_번을_받아도_같은_순서다() throws Exception {
		String token = 토큰("user2", "products:read");

		assertThat(목록(token)).isEqualTo(목록(token));
	}

	@Test
	void 목록은_역할로만_정해지고_token의_scope로는_바뀌지_않는다() throws Exception {
		assertThat(목록(토큰("user2", "products:read", "products:write", "orders:write")))
				.isEqualTo(목록(토큰("user2", "products:read")));
		assertThat(목록(토큰("user", "products:read", "products:write")))
				.isEqualTo(목록(토큰("user", "products:read")));
	}

	@Test
	void 처음_부르면_권한을_묻는_tool은_설명에_그렇게_적는다() throws Exception {
		String body = 응답(토큰("user", "products:read"));
		List<Map<String, Object>> tools = JsonPath.read(body, "$.result.tools[?(@.name in ['updateStock','checkout'])]");

		assertThat(tools).hasSize(2)
				.allSatisfy(tool -> assertThat((String) tool.get("description")).contains("처음 부르면"));
	}
}
