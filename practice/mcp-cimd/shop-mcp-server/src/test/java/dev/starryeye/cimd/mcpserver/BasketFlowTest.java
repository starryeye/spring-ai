package dev.starryeye.cimd.mcpserver;

import dev.starryeye.cimd.mcpserver.repository.ProductRepository;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * session 없이 handle만으로 장바구니를 이어 쓰는 흐름을 실제 filter chain과 transport로 본다.
 * 다른 사용자의 token, 결제의 {@code orders:write}, 두 번째 결제를 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class BasketFlowTest {

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
				{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
				.formatted(tool, arguments);
	}

	String 응답(String token, String body) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void handle로_담고_보고_결제한다() throws Exception {
		String read = 토큰("user", "products:read");
		String handle = JsonPath.read(응답(read, 호출("createBasket", "{}")), "$.result.structuredContent.basketId");
		응답(read, 호출("addItem", "{\"basketId\":\"%s\",\"productId\":\"p4\",\"quantity\":1}".formatted(handle)));
		int before = this.productRepository.findById("p4").orElseThrow().stock();

		String basket = 응답(read, 호출("getBasket", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(basket).contains("p4").doesNotContain("\"isError\":true");

		// 조회 token으로는 결제할 수 없다. 서버는 모자란 scope만 알린다.
		this.mockMvc.perform(McpScopeTest.mcp(read, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle))))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"orders:write\", "
								+ "resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\""));

		String write = 토큰("user", "products:read", "orders:write");
		String ordered = 응답(write, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(ordered).contains("ord-").doesNotContain("\"isError\":true");
		assertThat(this.productRepository.findById("p4").orElseThrow().stock()).isEqualTo(before - 1);

		String again = 응답(write, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(again).contains("\"isError\":true").contains("이미 주문");
	}

	@Test
	void 다른_사용자의_token으로는_handle을_써도_찾을_수_없다() throws Exception {
		String read = 토큰("user", "products:read");
		String handle = JsonPath.read(응답(read, 호출("createBasket", "{}")), "$.result.structuredContent.basketId");
		String added = 응답(read,
				호출("addItem", "{\"basketId\":\"%s\",\"productId\":\"p4\",\"quantity\":1}".formatted(handle)));
		assertThat(added).contains("[p4]");

		String other = 응답(토큰("user2", "products:read"), 호출("getBasket", "{\"basketId\":\"%s\"}".formatted(handle)));

		assertThat(other).contains("\"isError\":true").contains("찾을 수 없는 장바구니").doesNotContain("p4");
	}
}
