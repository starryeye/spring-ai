package dev.starryeye.cimd.mcpserver;

import dev.starryeye.cimd.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** scope 검사가 Spring Security와 transport 사이에서 실제 요청에 적용되는지 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class McpScopeTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	static String 토큰(String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, "user", Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	static MockHttpServletRequestBuilder mcp(String token, String body) {
		return post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", McpAuthorizationStandardTest.HOST)
				.header("MCP-Protocol-Version", "2025-11-25")
				.header("Authorization", "Bearer " + token)
				.content(body);
	}

	/** 유효하지 않은 UTF-8 바이트를 그대로 실어 보내야 하는 시험을 위해, String이 아닌 raw byte[] 본문을 쓴다. */
	static MockHttpServletRequestBuilder mcpBytes(String token, byte[] body) {
		return post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", McpAuthorizationStandardTest.HOST)
				.header("MCP-Protocol-Version", "2025-11-25")
				.header("Authorization", "Bearer " + token)
				.content(body);
	}

	static final String UPDATE_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"updateStock",\
			"arguments":{"productId":"p1","quantity":10}}}""";

	@Test
	void 조회_scope_token으로_재고를_바꾸려_하면_403과_필요한_scope를_받는다() throws Exception {
		this.mockMvc.perform(mcp(토큰("products:read"), UPDATE_STOCK))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"products:write\", "
								+ "resource_metadata=\"http://localhost:8171/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void scope_없는_token은_initialize도_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate", containsString("scope=\"products:read\"")));
	}

	@Test
	void 조회_scope_token의_initialize는_filter를_지나_transport가_답한다() throws Exception {
		this.mockMvc.perform(mcp(토큰("products:read"), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Mcp-Session-Id"));
	}

	@Test
	void filter가_읽은_본문을_transport가_다시_읽는다() throws Exception {
		// filter가 본문을 읽은 뒤에도 transport가 같은 본문을 읽어 tool을 실행한다.
		this.mockMvc.perform(mcp(토큰("products:read"), StatelessTransportTest.GET_STOCK))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("p1")));
	}

	/**
	 * parser differential 회귀 시험이다. 유효하지 않은 UTF-8 바이트가 섞인
	 * 재고 변경 요청을 조회 scope token으로 보낸다.
	 *
	 * <p>filter가 transport와 다른 규칙으로 본문을 읽으면(예전처럼 decode 예외를 "JSON 아님"으로 삼켰다면)
	 * 이 요청은 scope 검사를 피해 그대로 transport까지 가고, transport는 같은 바이트를 대체 문자로 채워 읽어
	 * 여전히 유효한 JSON으로 보고 실행해 버린다. 지금은 filter가 transport와 같은 방식으로 decode하므로
	 * 200이 될 수 없고, 재고도 그대로다.
	 */
	@Test
	void 유효하지_않은_UTF8_바이트가_섞인_재고_변경_요청은_재고를_바꾸지_못한다() throws Exception {
		String token = 토큰("products:read");

		String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"updateStock\","
				+ "\"arguments\":{\"productId\":\"p1\",\"quantity\":777}},\"extra\":\"value\"}";
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		bytes[json.indexOf("value")] = (byte) 0xFF;

		this.mockMvc.perform(mcpBytes(token, bytes))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

		assertThat(this.productRepository.findById("p1").orElseThrow().stock()).isEqualTo(7);
	}
}
