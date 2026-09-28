package dev.starryeye.authz.mcpserver.filter;

import dev.starryeye.authz.mcpserver.repository.ProductRepository;
import dev.starryeye.authz.mcpserver.tool.ProductTools;
import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeFilterTest {

	static final String METADATA = "http://localhost:8141/.well-known/oauth-protected-resource/mcp";

	ToolScopeFilter filter = new ToolScopeFilter(ToolScopeRegistry.scan(new ProductTools(new ProductRepository())),
			JsonMapper.builder().build(), request -> METADATA);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	static void 로그인(String... scopes) {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user").claim("scope", scopes).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
				Arrays.stream(scopes).map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
	}

	static MockHttpServletRequest post(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	static String call(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{}}}";
	}

	@Test
	void 조회_scope로_조회_tool을_부르면_본문을_그대로_넘긴다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post(call("getStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		// transport가 본문을 다시 읽을 수 있어야 한다.
		assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
				.isEqualTo(call("getStock"));
	}

	@Test
	void 조회_scope로_재고_변경_tool을_부르면_403과_필요한_scope를_알린다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post(call("updateStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
				"Bearer error=\"insufficient_scope\", scope=\"products:write\", resource_metadata=\"" + METADATA + "\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 두_scope가_있으면_재고_변경_tool도_넘긴다() throws Exception {
		로그인("products:read", "products:write");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post(call("updateStock")), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void write만_있는_token도_기본_scope가_없으면_403이다() throws Exception {
		로그인("products:write");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), response,
				new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:read\"");
	}

	@Test
	void 형식이_틀린_본문도_기본_scope만_보고_transport로_넘긴다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post("{not json"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 이름_없는_tools_call은_기본_scope만_본다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{}}"),
				new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void GET은_본문_없이_기본_scope만_본다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(new MockHttpServletRequest("GET", "/mcp"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}
}
