package dev.starryeye.shopmcpserver;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopMcpServerApplicationTests {

	/**
	 * 자동설정은 {@code List<SyncToolSpecification>} 빈을 둘 만든다
	 * (어노테이션 스캔용, {@code ToolCallback} 변환용) — 둘 다 모으려면
	 * 단일 {@code List} 대신 {@link ObjectProvider} 로 받아야 한다.
	 */
	@Autowired
	ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> toolSpecificationLists;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 이 practice 의 존재 이유. agent-mcps 에서는 이 요청이 통했다.
	 *
	 * <p>{@code .with(csrf())} 를 붙인 이유: 이 모듈(0.1.14)의 필터 체인은 CSRF 를 끄지 않는다.
	 * 실제 서버(Tomcat)에 curl 로 토큰 없이 요청하면 세션이 막 생성된 첫 요청이라 CSRF 검사를
	 * 통과하고 정상적으로 401(Bearer 챌린지)이 온다. 그런데 MockMvc + spring-security-test 조합은
	 * 세션에 CSRF 토큰을 미리 채워 두므로, 토큰 없는 요청이 인증 이전에 CsrfFilter 에서
	 * 403 으로 걸린다 — 이 테스트가 검증하려는 "인증 여부"와 무관한 차단이다.
	 * {@code csrf()} 로 그 우연한 차단을 제거해야 실제 운영 동작(401)과 일치하는 결과를 본다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		mockMvc.perform(post("/").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * SYNC 서버는 리액티브 반환 타입을 조용히 걸러낸다.
	 * ProductTools 의 반환 타입을 Mono<String> 으로 바꾸면 이 테스트만 깨진다.
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
