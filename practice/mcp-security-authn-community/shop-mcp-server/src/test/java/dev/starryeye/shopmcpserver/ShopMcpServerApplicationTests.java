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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
	 * 401 과 함께 {@code WWW-Authenticate} 스킴이 {@code Bearer} 인지 본다. Boot 기본 보안(Basic)으로
	 * 막혀도 401 이므로, 스킴이 모듈의 OAuth2 Resource Server 설정이 연결됐다는 증거다.
	 * {@code Host} 는 모듈 {@code OriginValidationFilter} 가 인증보다 먼저 보므로 허용 값으로 싣는다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.header("Host", "localhost:8101")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		// 상태 코드만 보면 보안 모듈이 빠지고 Boot 기본 Basic 인증이 대신 막아도
		// 그대로 통과한다. 스킴까지 확인해야 이 모듈이 실제로 걸었다는 증거가 된다.
		// Host 헤더가 허용 목록과 일치해야 421(Invalid Host header)이 아니라 401 을 본다.
		mockMvc.perform(post("/").header("Host", "localhost:8101"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
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
