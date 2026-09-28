package dev.starryeye.authz.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

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

	static final String UPDATE_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"updateStock",\
			"arguments":{"productId":"p1","quantity":10}}}""";

	@Test
	void 조회_scope_token으로_재고를_바꾸려_하면_403과_필요한_scope를_받는다() throws Exception {
		this.mockMvc.perform(mcp(토큰("products:read"), UPDATE_STOCK))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"products:write\", "
								+ "resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\""));
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
				.andExpect(header().exists("Mcp-Session-Id"));
	}

	@Test
	void filter가_읽은_본문을_transport가_다시_읽는다() throws Exception {
		// session 없이 보낸 tools/call이다. transport가 본문을 읽고 나서야 session을 확인하므로,
		// "Session ID missing"은 본문이 transport까지 온전히 전달됐다는 뜻이다.
		this.mockMvc.perform(mcp(토큰("products:read", "products:write"), UPDATE_STOCK))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Session ID missing")));
	}
}
