package dev.starryeye.visibility.mcpserver;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 손님이 숨긴 tool을 부르면 정말 없는 tool과 구별되지 않는 "모르는 tool" 오류가 온다.
 * scope를 늘려도 쓸 수 없는 tool이라 403(step-up)을 보내지 않는다.
 * 점원은 같은 tool에서 여전히 step-up한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class HiddenToolCallTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	static String 토큰(String subject, String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, subject, Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	static String 호출(String tool, String arguments) {
		return """
				{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
				.formatted(tool, arguments);
	}

	MockHttpServletResponse 응답(String token, String body) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, body)).andReturn().getResponse();
	}

	static Map<String, List<String>> header들(MockHttpServletResponse response) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (String name : response.getHeaderNames()) {
			headers.put(name, response.getHeaders(name));
		}
		return headers;
	}

	@Test
	void 손님이_숨긴_tool을_부르면_없는_tool과_같은_응답이다() throws Exception {
		String token = 토큰("user2", "products:read");
		String arguments = "{\"productId\":\"p1\",\"quantity\":10}";
		// 이름이 data와 Content-Length에 들어가므로, 같은 길이의 없는 이름(updateStack)과 비교한다.
		MockHttpServletResponse hidden = 응답(token, 호출("updateStock", arguments));
		MockHttpServletResponse unknown = 응답(token, 호출("updateStack", arguments));

		assertThat(hidden.getStatus()).isEqualTo(200).isEqualTo(unknown.getStatus());
		assertThat(header들(hidden)).isEqualTo(header들(unknown)).doesNotContainKey("WWW-Authenticate");
		assertThat(hidden.getContentAsString(StandardCharsets.UTF_8).replace("updateStock", "NAME"))
				.isEqualTo(unknown.getContentAsString(StandardCharsets.UTF_8).replace("updateStack", "NAME"));
		assertThat(hidden.getContentAsString(StandardCharsets.UTF_8))
				.contains("\"code\":-32602", "\"message\":\"Unknown tool: invalid_tool_name\"",
						"\"data\":\"Tool not found: updateStock\"");
	}

	@Test
	void 손님은_products_write가_있어도_숨긴_tool을_부를_수_없다() throws Exception {
		int before = this.productRepository.findById("p1").orElseThrow().stock();

		String body = 응답(토큰("user2", "products:read", "products:write"),
				호출("updateStock", "{\"productId\":\"p1\",\"quantity\":0}")).getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("Unknown tool: invalid_tool_name");
		assertThat(this.productRepository.findById("p1").orElseThrow().stock()).isEqualTo(before);
	}

	@Test
	void 숨긴_tool은_인자가_틀려도_모르는_tool이다() throws Exception {
		// 입력 검증이 먼저 돌면 "그런 tool은 있다"가 드러난다.
		String body = 응답(토큰("user2", "products:read"), 호출("updateStock", "{}"))
				.getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("Unknown tool: invalid_tool_name");
	}

	@Test
	void 점원의_조회_token은_updateStock에서_403_step_up이다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(토큰("user", "products:read"),
						호출("updateStock", "{\"productId\":\"p1\",\"quantity\":10}")))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"products:write\", "
								+ "resource_metadata=\"http://localhost:8161/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void 손님도_받을_수_있는_scope의_tool은_step_up한다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(토큰("user2", "products:read"),
						호출("checkout", "{\"basketId\":\"bsk_AAAAAAAAAAAAAAAAAAAAAA\"}")))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("scope=\"orders:write\"")));
	}
}
