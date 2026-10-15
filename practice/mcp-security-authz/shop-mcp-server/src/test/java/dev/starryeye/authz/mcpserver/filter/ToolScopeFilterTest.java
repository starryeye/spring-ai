package dev.starryeye.authz.mcpserver.filter;

import dev.starryeye.authz.mcpserver.tool.ProductTools;
import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeFilterTest {

	static final String METADATA = "http://localhost:8141/.well-known/oauth-protected-resource/mcp";

	/** 본문 상한을 시험하는 filter의 상한이다. 기본 상한(1 MiB)보다 작게 두어 큰 본문을 만들지 않는다. */
	static final int SMALL_LIMIT = 128;

	/** 속도 제한의 Retry-After가 실행 속도에 따라 흔들리지 않도록 시간을 멈춘다. */
	static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

	ToolScopeFilter filter = filter(1024 * 1024);

	static ToolScopeFilter filter(int maxBodyBytes) {
		// 속도 제한은 따로 시험한다. 다른 시험이 걸리지 않도록 넉넉하게 둔다.
		return filter(maxBodyBytes, 1000);
	}

	static ToolScopeFilter filter(int maxBodyBytes, int toolCallsPerMinute) {
		return new ToolScopeFilter(ToolScopeRegistry.of(ProductTools.class), JsonMapper.builder().build(), METADATA,
				maxBodyBytes, new ToolCallRateLimiter(toolCallsPerMinute, Duration.ofMinutes(1), FIXED_CLOCK));
	}

	/** {@code length} 바이트짜리 {@code tools/list} 본문이다. 끝의 공백으로 길이를 맞춘다. */
	static String 길이를_맞춘_본문(int length) {
		String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"";
		return head + " ".repeat(length - head.length() - 1) + "}";
	}

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

	/** chunked 요청처럼 {@code Content-Length}로 길이를 미리 알 수 없는 요청이다. */
	static final class 길이_모름 extends HttpServletRequestWrapper {

		길이_모름(HttpServletRequest request) {
			super(request);
		}

		@Override
		public int getContentLength() {
			return -1;
		}

		@Override
		public long getContentLengthLong() {
			return -1;
		}
	}

	@Test
	void Content_Length가_상한을_넘으면_본문을_읽지_않고_413이다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(SMALL_LIMIT).doFilter(new 본문_읽기_금지(post(길이를_맞춘_본문(SMALL_LIMIT + 1))), response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentAsString()).contains("\"code\":-32600");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 길이를_모르는_본문도_상한을_넘게_읽히면_413이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(SMALL_LIMIT).doFilter(new 길이_모름(post(길이를_맞춘_본문(SMALL_LIMIT + 1))), response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 상한과_같은_크기의_본문은_그대로_넘긴다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		String body = 길이를_맞춘_본문(SMALL_LIMIT);

		filter(SMALL_LIMIT).doFilter(new 길이_모름(post(body)), new MockHttpServletResponse(), chain);

		assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
				.isEqualTo(body);
	}

	@Test
	void 사용자의_tools_call이_상한을_넘으면_429와_Retry_After로_답하고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		ToolScopeFilter limited = filter(1024 * 1024, 1);
		limited.doFilter(post(call("getStock")), new MockHttpServletResponse(), new MockFilterChain());
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		limited.doFilter(post(call("getStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("60");
		assertThat(response.getContentAsString()).contains("\"code\":-32000");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void tools_call이_아닌_요청과_scope가_모자라_거절한_호출은_세지_않는다() throws Exception {
		로그인("products:read");
		ToolScopeFilter limited = filter(1024 * 1024, 1);
		limited.doFilter(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
				new MockHttpServletResponse(), new MockFilterChain());
		MockHttpServletResponse denied = new MockHttpServletResponse();
		limited.doFilter(post(call("updateStock")), denied, new MockFilterChain());
		assertThat(denied.getStatus()).isEqualTo(403);
		MockFilterChain chain = new MockFilterChain();

		limited.doFilter(post(call("getStock")), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
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
	 * 입력 바이트는 고정이고, 대체 문자는 문자열 값 안에 들어가 JSON 구조를 깨지 않는다.
	 * 그래서 결과는 늘 scope 검사의 {@code 403}이다. {@code 400}이 나오면 decode 규칙이 달라졌다는 뜻이니 실패해야 한다.
	 */
	@Test
	void 유효하지_않은_UTF8_바이트가_섞인_본문도_scope_검사를_받아_403이고_transport로_넘기지_않는다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"updateStock\","
				+ "\"arguments\":{\"productId\":\"p1\",\"quantity\":10},\"extra\":\"value\"}}";
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		bytes[json.indexOf("value")] = (byte) 0xFF;

		this.filter.doFilter(postBytes(bytes, "application/json"), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:write\"");
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
