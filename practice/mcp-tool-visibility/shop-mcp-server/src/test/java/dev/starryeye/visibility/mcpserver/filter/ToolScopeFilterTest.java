package dev.starryeye.visibility.mcpserver.filter;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;
import dev.starryeye.visibility.mcpserver.tool.ProductTools;
import dev.starryeye.visibility.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.visibility.mcpserver.tool.ToolVisibility;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeFilterTest {

	static final String METADATA = "http://localhost:8161/.well-known/oauth-protected-resource/mcp";

	ToolScopeRegistry registry = ToolScopeRegistry.scan(new ProductTools(new ProductRepository()));

	// 이 test의 token은 sub가 user라 점원이다. 점원은 모든 scope를 받을 수 있으니 tool이 숨지 않는다.
	ToolScopeFilter filter = new ToolScopeFilter(registry,
			new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF)), JsonMapper.builder().build(),
			request -> METADATA);

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
		return post(body, StandardCharsets.UTF_8);
	}

	static MockHttpServletRequest post(String body, Charset charset) {
		return postBytes(body.getBytes(charset), "application/json");
	}

	static MockHttpServletRequest postBytes(byte[] body, String contentType) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setContentType(contentType);
		request.setContent(body);
		return request;
	}

	static String call(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{}}}";
	}

	/** {@code getInputStream}이 불리면 실패한다. 본문을 읽기 전에 걸러야 하는 검사를 증명하는 데 쓴다. */
	static final class 본문_읽기_금지 extends HttpServletRequestWrapper {

		본문_읽기_금지(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() {
			throw new AssertionError("이 시점에는 본문을 읽으면 안 된다");
		}
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
	void 형식이_틀린_본문은_400이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post("{not json"), response, chain);

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 최상위가_배열인_본문은_400이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]"), response, chain);

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(chain.getRequest()).isNull();
	}

	/**
	 * transport({@code StringHttpMessageConverter})는 잘못된 UTF-8 바이트를 예외 없이 대체 문자로 바꿔 읽는다.
	 * 이 filter도 같은 방식으로 읽어야, 조회 scope만 있는 token이 이 바이트 하나로 재고 변경을 몰래 통과시키지 못한다.
	 * (구조를 깨지 않는 위치의 대체라 JSON 자체는 여전히 유효하므로, 검사는 그대로 적용돼 403이 된다. 값에 따라
	 * 대체가 구조를 깨면 400도 있을 수 있어 둘 다 허용한다.)
	 */
	@Test
	void 유효하지_않은_UTF8_바이트가_섞인_본문도_403_또는_400이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"updateStock\","
				+ "\"arguments\":{\"productId\":\"p1\",\"quantity\":10},\"extra\":\"value\"}}";
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		bytes[json.indexOf("value")] = (byte) 0xFF;

		this.filter.doFilter(postBytes(bytes, "application/json"), response, chain);

		assertThat(response.getStatus()).isIn(403, 400);
		if (response.getStatus() == 403) {
			assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:write\"");
		}
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void IBM037로_encoding한_재고_변경_요청도_scope_검사를_받는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		byte[] body = call("updateStock").getBytes(Charset.forName("IBM037"));

		this.filter.doFilter(postBytes(body, "application/json;charset=IBM037"), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:write\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 모르는_charset_이름은_400이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(
				postBytes(call("getStock").getBytes(StandardCharsets.UTF_8), "application/json;charset=x-unknown"),
				response, chain);

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).isEqualTo(
				"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,"
						+ "\"message\":\"요청의 Content-Type을 읽을 수 없습니다.\"}}");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void scope가_없는_token은_본문을_읽기_전에_403이다() throws Exception {
		로그인();
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		HttpServletRequest request = new 본문_읽기_금지(post(call("getStock")));

		this.filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:read\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void JWT가_아닌_인증은_403이다() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", "pw",
				List.of(new SimpleGrantedAuthority("SCOPE_products:read"), new SimpleGrantedAuthority("SCOPE_products:write"))));
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post(call("getStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
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
