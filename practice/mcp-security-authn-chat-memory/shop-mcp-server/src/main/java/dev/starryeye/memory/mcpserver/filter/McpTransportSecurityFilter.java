package dev.starryeye.memory.mcpserver.filter;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * MCP endpoint의 {@code Origin}·{@code Host}를 인증보다 먼저 검사한다(MCP 2025-11-25 Transports — Security Warning).
 *
 * <p>검사 규칙은 SDK의 {@link DefaultServerTransportSecurityValidator}를 그대로 쓴다.
 * Spring AI transport의 기본 validator는 아무것도 막지 않아, 이 filter가 유일한 검사다.
 * Spring Security가 먼저 돌면 token 없는 요청은 401로 끝나고 이 검사까지 오지 않는다.
 * 그래서 이 filter는 security filter chain 앞에 서서, token과 상관없이 잘못된 {@code Origin}은 403으로,
 * 잘못된 {@code Host}는 421로 막는다.
 * 응답 본문은 SDK transport와 같은 평문 메시지다.
 */
public class McpTransportSecurityFilter extends OncePerRequestFilter {

	private final ServerHttpHeaderValidator validator;

	public McpTransportSecurityFilter(ServerHttpHeaderValidator validator) {
		this.validator = validator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			this.validator.validate(new ServletHeaderAccessor(request));
		}
		catch (ServerTransportSecurityException ex) {
			response.setStatus(ex.getStatusCode());
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(ex.getMessage() == null ? "" : ex.getMessage());
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * servlet 요청의 header를 그대로 읽는다.
	 * servlet은 header 이름의 대소문자를 가리지 않으므로 validator가 어떤 표기로 물어도 된다.
	 * SDK의 servlet용 accessor는 공개되어 있지 않아 직접 만든다.
	 */
	private static final class ServletHeaderAccessor implements HeaderAccessor {

		private final HttpServletRequest request;

		private ServletHeaderAccessor(HttpServletRequest request) {
			this.request = request;
		}

		@Override
		public List<String> getHeader(String name) {
			return Collections.list(this.request.getHeaders(name));
		}

		@Override
		public List<String> getHeaderNames() {
			return Collections.list(this.request.getHeaderNames());
		}
	}
}
