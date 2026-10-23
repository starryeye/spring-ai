package dev.starryeye.visibility.mcpserver;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검사({@code ToolScopeFilter})와 실행(SDK)이 tool 이름을 다르게 읽을 수 있는 본문을 조회 scope token으로 보낸다
 * (안내서 10장의 parser differential).
 *
 * <p>상태 코드만 보지 않고 결과를 본다. 쓰기 tool({@code updateStock})이 실행되면 재고가 바뀐다.
 * 대조군은 같은 경로로 쓰기 scope token을 보내 재고가 정말 바뀌는 것을 보인다. 그래야 "재고가 그대로다"라는 단정이 의미를 가진다.
 *
 * <p>지금 조합(Spring AI 2.0.2, MCP SDK 2.1.0)에서 확인한 동작은 이렇다.
 * 중복 key는 filter와 SDK 모두 뒤의 값을 쓴다.
 * 문자열이 아닌 이름(숫자·boolean·배열)은 filter가 transport로 넘기지 않고 {@code 400}(JSON-RPC {@code -32602})으로 끝낸다.
 * filter가 넘기면 SDK는 숫자·boolean을 문자열로 바꿔 "모르는 tool"로 답하고, 배열은 바꾸지 못해 transport가
 * stack trace가 든 {@code 500}을 낸다.
 * 그래서 쓰기 tool이 검사를 피해 실행되는 본문은 없다. SDK나 mapper 설정이 바뀌면 이 테스트가 먼저 알려 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class ParserDifferentialTest {

	static final String WRITE_ARGUMENTS = "\"arguments\":{\"productId\":\"p1\",\"quantity\":777}";

	static final String READ_TOKEN = McpScopeTest.토큰("products:read");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	static String 호출(String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":" + params + "}";
	}

	ResultActions 보낸다(String token, String body) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, body));
	}

	int 재고() {
		return this.productRepository.findById("p1").orElseThrow().stock();
	}

	@Test
	void 대조군_쓰기_scope_token이면_같은_경로로_updateStock이_실행돼_재고가_바뀐다() throws Exception {
		int before = 재고();
		try {
			보낸다(McpScopeTest.토큰("products:read", "products:write"),
					호출("{\"name\":\"updateStock\"," + WRITE_ARGUMENTS + "}"))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("777개로 바꿨습니다")));

			assertThat(재고()).isEqualTo(777);
		}
		finally {
			this.productRepository.updateStock("p1", before);
		}
	}

	@Test
	void name_key가_두_번_나오면_뒤의_updateStock으로_검사해_403이고_재고는_그대로다() throws Exception {
		int before = 재고();

		보낸다(READ_TOKEN, 호출("{\"name\":\"getStock\",\"name\":\"updateStock\"," + WRITE_ARGUMENTS + "}"))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate", containsString("scope=\"products:write\"")));

		assertThat(재고()).isEqualTo(before);
	}

	@Test
	void name이_숫자면_filter가_400으로_끝내고_재고는_그대로다() throws Exception {
		int before = 재고();

		보낸다(READ_TOKEN, 호출("{\"name\":123," + WRITE_ARGUMENTS + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("\"code\":-32602")));

		assertThat(재고()).isEqualTo(before);
	}

	@Test
	void name이_boolean이면_filter가_400으로_끝내고_재고는_그대로다() throws Exception {
		int before = 재고();

		보낸다(READ_TOKEN, 호출("{\"name\":true," + WRITE_ARGUMENTS + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("\"code\":-32602")));

		assertThat(재고()).isEqualTo(before);
	}

	@Test
	void name이_배열이면_filter가_400으로_끝내고_재고는_그대로다() throws Exception {
		int before = 재고();

		보낸다(READ_TOKEN, 호출("{\"name\":[\"updateStock\"]," + WRITE_ARGUMENTS + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("\"code\":-32602")))
				.andExpect(content().string(not(containsString("stackTrace"))));

		assertThat(재고()).isEqualTo(before);
	}

	@Test
	void params_key가_두_번_나오면_뒤의_updateStock으로_검사해_403이고_재고는_그대로다() throws Exception {
		int before = 재고();

		보낸다(READ_TOKEN, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"getStock\",\"arguments\":{\"productId\":\"p1\"}},"
				+ "\"params\":{\"name\":\"updateStock\"," + WRITE_ARGUMENTS + "}}")
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate", containsString("scope=\"products:write\"")));

		assertThat(재고()).isEqualTo(before);
	}
}
