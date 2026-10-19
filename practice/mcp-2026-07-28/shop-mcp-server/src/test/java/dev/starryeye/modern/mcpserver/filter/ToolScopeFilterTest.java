package dev.starryeye.modern.mcpserver.filter;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.repository.ProductRepository;
import dev.starryeye.modern.mcpserver.tool.BasketTools;
import dev.starryeye.modern.mcpserver.tool.ProductTools;
import dev.starryeye.modern.mcpserver.tool.ShopTools;
import dev.starryeye.modern.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.modern.mcpserver.tool.ToolVisibility;

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

import java.io.BufferedReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scope filter가 본문 없이 {@code Mcp-Method}·{@code Mcp-Name} header만으로 scope를 보는지 확인한다.
 * 요청은 모두 본문을 읽으면 실패하는 {@link 본문_읽기_금지}로 감싼다.
 */
class ToolScopeFilterTest {

	static final String METADATA = "http://localhost:8181/.well-known/oauth-protected-resource/mcp";

	/** 속도 제한의 {@code Retry-After}가 실행 속도에 따라 흔들리지 않도록 시간을 멈춘다. */
	static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

	/**
	 * 속도 제한은 따로 시험한다.
	 * 다른 시험이 걸리지 않도록 넉넉하게 둔다.
	 */
	ToolScopeFilter filter = filter(1000);

	/** {@code user}는 점원이고, 그 밖의 사용자는 손님이다. */
	static ToolScopeFilter filter(int toolCallsPerMinute) {
		ToolScopeRegistry registry = ToolScopeRegistry.of(ShopTools.all(new ProductTools(new ProductRepository()),
				new BasketTools(new BasketStore(Clock.systemUTC()), new ProductRepository())));
		return new ToolScopeFilter(registry, new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF)),
				METADATA, new ToolCallRateLimiter(toolCallsPerMinute, Duration.ofMinutes(1), FIXED_CLOCK));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	static void 로그인(String subject, String... scopes) {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).claim("scope", scopes).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
				Arrays.stream(scopes).map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
	}

	static void 점원(String... scopes) {
		로그인("user", scopes);
	}

	/**
	 * 2026-07-28 header만 붙인 요청이다.
	 * {@code mcpName}이 {@code null}이면 {@code Mcp-Name}을 붙이지 않는다.
	 */
	static HttpServletRequest 요청(String method, String mcpName) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.addHeader("MCP-Protocol-Version", "2026-07-28");
		request.addHeader("Mcp-Method", method);
		if (mcpName != null) {
			request.addHeader("Mcp-Name", mcpName);
		}
		return new 본문_읽기_금지(request);
	}

	static HttpServletRequest 호출(String tool) {
		return 요청("tools/call", tool);
	}

	static String challenge(String scope) {
		return "Bearer error=\"insufficient_scope\", scope=\"" + scope + "\", resource_metadata=\"" + METADATA + "\"";
	}

	/**
	 * 본문을 읽으면 실패한다.
	 * filter가 header만 본다는 것을 증명하는 데 쓴다.
	 */
	static final class 본문_읽기_금지 extends HttpServletRequestWrapper {

		본문_읽기_금지(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() {
			throw new AssertionError("scope filter는 본문을 읽으면 안 된다");
		}

		@Override
		public BufferedReader getReader() {
			throw new AssertionError("scope filter는 본문을 읽으면 안 된다");
		}
	}

	@Test
	void header의_tool_이름으로_scope를_본다() throws Exception {
		점원("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(호출("updateStock"), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(challenge("products:write"));
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 기본_scope와_tool_scope가_모두_모자라면_한_challenge에_둘을_적는다() throws Exception {
		점원();
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(호출("checkout"), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(challenge("products:read orders:write"));
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void base64로_적은_Mcp_Name도_같은_규칙으로_푼다() throws Exception {
		점원("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		// "checkout"을 base64로 적었다.
		this.filter.doFilter(호출("=?base64?Y2hlY2tvdXQ=?="), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(challenge("orders:write"));
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 숨긴_tool은_scope를_보지_않고_넘긴다() throws Exception {
		// 손님은 products:write를 받을 수 없어 updateStock이 숨겨져 있다.
		// 여기서 403을 주면 step-up이 시작되고, 그런 tool이 있다는 것도 알려진다.
		로그인("user2", "products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(호출("updateStock"), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("WWW-Authenticate")).isNull();
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 숨긴_tool이어도_기본_scope가_없으면_403이다() throws Exception {
		로그인("user2");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(호출("updateStock"), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(challenge("products:read"));
	}

	@Test
	void tools_call이_아니면_기본_scope만_본다() throws Exception {
		점원("products:read");
		MockFilterChain chain = new MockFilterChain();

		// Mcp-Name은 tools/call에만 쓰는 header다.
		// 다른 method에 붙어 있어도 tool scope를 보지 않는다.
		this.filter.doFilter(요청("tools/list", "updateStock"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();

		점원();
		MockHttpServletResponse denied = new MockHttpServletResponse();
		this.filter.doFilter(요청("tools/list", null), denied, new MockFilterChain());

		assertThat(denied.getStatus()).isEqualTo(403);
		assertThat(denied.getHeader("WWW-Authenticate")).isEqualTo(challenge("products:read"));
	}

	@Test
	void 두_scope가_있으면_재고_변경_tool도_넘긴다() throws Exception {
		점원("products:read", "products:write");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(호출("updateStock"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void Mcp_Name이_없는_tools_call은_기본_scope만_보고_넘긴다() throws Exception {
		// tool 이름을 모르니 tool scope를 정할 수 없다.
		// 넘기면 SDK가 400으로 끝낸다.
		점원("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(호출(null), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 풀_수_없는_Mcp_Name은_400이고_transport로_넘기지_않는다() throws Exception {
		점원("products:read");

		for (String mcpName : List.of("=?base64?=", "=?base64?!!!?=")) {
			MockFilterChain chain = new MockFilterChain();
			MockHttpServletResponse response = new MockHttpServletResponse();

			this.filter.doFilter(호출(mcpName), response, chain);

			assertThat(response.getStatus()).as(mcpName).isEqualTo(400);
			assertThat(response.getContentType()).as(mcpName).startsWith("application/json");
			assertThat(response.getContentAsString()).as(mcpName).isEqualTo("{\"jsonrpc\":\"2.0\",\"error\":"
					+ "{\"code\":-32020,\"message\":\"Mcp-Name header has a malformed Base64 value\"}}");
			assertThat(chain.getRequest()).as(mcpName).isNull();
		}
	}

	@Test
	void 통과한_tools_call만_속도_제한에_센다() throws Exception {
		점원("products:read");
		ToolScopeFilter limited = filter(1);
		// tools/call이 아닌 요청, scope가 모자라 거절한 호출, 풀 수 없는 Mcp-Name은 세지 않는다.
		limited.doFilter(요청("tools/list", null), new MockHttpServletResponse(), new MockFilterChain());
		MockHttpServletResponse denied = new MockHttpServletResponse();
		limited.doFilter(호출("updateStock"), denied, new MockFilterChain());
		assertThat(denied.getStatus()).isEqualTo(403);
		MockHttpServletResponse malformed = new MockHttpServletResponse();
		limited.doFilter(호출("=?base64?="), malformed, new MockFilterChain());
		assertThat(malformed.getStatus()).isEqualTo(400);
		MockFilterChain passed = new MockFilterChain();
		limited.doFilter(호출("getStock"), new MockHttpServletResponse(), passed);
		assertThat(passed.getRequest()).isNotNull();
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		limited.doFilter(호출("getStock"), response, chain);

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("60");
		assertThat(response.getContentAsString()).isEqualTo("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,"
				+ "\"message\":\"tool 호출이 너무 잦습니다. 60초 뒤에 다시 시도하세요.\"}}");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void JWT가_아닌_인증은_403이다() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", "pw",
				List.of(new SimpleGrantedAuthority("SCOPE_products:read"), new SimpleGrantedAuthority("SCOPE_products:write"))));
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(호출("getStock"), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}
}
