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
	 * 이 practice 의 존재 이유. agent-mcps 에서는 이 요청이 통했다.
	 *
	 * <p>{@code SecurityConfig} 가 필터체인을 직접 정의하면서 CSRF 는 명시적으로 끈다
	 * (무상태 리소스 서버라 세션 기반 CSRF 토큰을 쓰지 않는다). 대신 Origin/Host 검증
	 * ({@code OriginValidationFilter}, {@code allowedHosts} 를 켰다)이 이 필터체인이 지키는
	 * 모든 경로에 걸리므로, {@code Host} 헤더가 허용 목록과 일치해야 그 다음 인증 단계까지
	 * 도달해 401 을 본다 — 없으면 421(Invalid Host header)로 먼저 막힌다.
	 *
	 * <p><b>{@code WWW-Authenticate} 헤더까지 검증하는 이유:</b> 상태 코드 401 만으로는
	 * "이 MCP 보안 모듈이 실제로 동작해서 막은 것"인지, 아니면 단순히 {@code spring-boot-starter-security}
	 * 가 기본으로 켜주는 Basic 인증(무작위 생성 비밀번호)이 대신 막은 것인지 구분할 수 없다 —
	 * 둘 다 401 을 반환한다. 실제로 {@code issuer-uri} 설정을 지워도 이 상태 코드 검증만으로는
	 * 통과해 버린다 (검증됨). 두 경우를 가르는 신호는 인증 스킴이다: 이 모듈이 살아있으면
	 * {@code WWW-Authenticate: Bearer resource_metadata=...} 를, Boot 기본 보안으로 대체되면
	 * {@code WWW-Authenticate: Basic realm=...} 를 반환한다. 따라서 스킴이 {@code Bearer} 로
	 * 시작하는지까지 검증해야 이 테스트가 "MCP OAuth2 보호가 실제로 연결되어 있다"는 것을
	 * 증명한다.
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
