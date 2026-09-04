package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopMcpServerApplicationTests {

	@Autowired
	ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> toolSpecificationLists;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 상태 코드만으로는 "이 리소스 서버 설정이 막은 것"인지 "Boot 기본 Basic 인증이
	 * 대신 막은 것"인지 구분할 수 없다 — 둘 다 401 이다(community 에서 실측했다).
	 * 스킴이 {@code Bearer} 여야 OAuth2 리소스 서버가 실제로 동작한다는 증거가 된다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		mockMvc.perform(post("/").with(csrf()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	/**
	 * SYNC 서버는 리액티브 반환 타입을 조용히 걸러낸다.
	 * ProductTools 의 반환을 Mono<String> 으로 바꾸면 이 테스트만 깨진다.
	 */
	@Test
	void MCP_툴이_실제로_등록된다() {
		List<String> toolNames = toolSpecificationLists.orderedStream()
				.flatMap(List::stream)
				.map(spec -> spec.tool().name())
				.toList();

		assertThat(toolNames).containsExactlyInAnyOrder("searchProducts", "getStock");
	}
}
