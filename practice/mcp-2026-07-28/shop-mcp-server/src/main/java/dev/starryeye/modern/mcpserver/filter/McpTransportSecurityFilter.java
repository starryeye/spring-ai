package dev.starryeye.modern.mcpserver.filter;

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
 * MCP endpoint의 {@code Origin}·{@code Host}를 인증보다 먼저 검사한다(MCP 2026-07-28 Transports — Streamable HTTP).
 *
 * <p>검사 규칙은 SDK의 {@link DefaultServerTransportSecurityValidator}를 그대로 쓴다.
 * SDK transport도 {@code Origin}을 검사하지만, 그 검사는 Spring Security 뒤의 servlet 안에서 돈다.
 * 그래서 token 없는 요청은 401로 끝나고 그 검사까지 오지 않는다.
 * 이 filter는 security filter chain 앞에 서서, token과 상관없이 잘못된 {@code Origin}은 403으로,
 * 잘못된 {@code Host}는 421로 막는다.
 * 응답 본문은 평문 메시지다.
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
			this.validator.validate(headers(request));
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
	 * servlet은 header 이름의 대소문자를 가리지 않는다.
	 */
	private static HeaderAccessor headers(HttpServletRequest request) {
		return new HeaderAccessor() {

			@Override
			public List<String> getHeader(String name) {
				return Collections.list(request.getHeaders(name));
			}

			@Override
			public List<String> getHeaderNames() {
				return Collections.list(request.getHeaderNames());
			}
		};
	}
}
