package dev.starryeye.memoryauthn.mcpserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP session 을 그 session 을 연 사용자에 묶는다(MCP Security Best Practices — Session Hijacking, SHOULD).
 *
 * <p>session ID 는 인증을 대신하지 않는다 — 모든 요청은 먼저 token 으로 인증된다. 이 filter 는 그 뒤에
 * 돌며, 요청의 {@code Mcp-Session-Id} 가 다른 사용자(token 의 {@code sub})에게 발급된 것이면 403 으로 막는다.
 *
 * <p>묶는 시점은 transport 가 응답에 {@code Mcp-Session-Id} 헤더를 쓰는 순간이다(응답 wrapper). 본문이
 * 나가기 전에 묶이므로 client 가 다음 요청을 보낼 때는 이미 묶여 있다. {@code DELETE} 로 session 이
 * 끝나면 묶음을 지운다.
 */
public class McpSessionBindingFilter extends OncePerRequestFilter {

	static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	private final Map<String, String> owners = new ConcurrentHashMap<>();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			chain.doFilter(request, response);
			return;
		}
		String user = authentication.getName();
		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId != null) {
			String owner = this.owners.get(sessionId);
			if (owner != null && !owner.equals(user)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP session belongs to another user");
				return;
			}
		}

		chain.doFilter(request, new BindingResponse(response, user));

		if ("DELETE".equals(request.getMethod()) && sessionId != null && response.getStatus() < 300) {
			this.owners.remove(sessionId, user);
		}
	}

	/** transport 가 {@code Mcp-Session-Id} 를 쓰는 순간 그 session 을 현재 사용자에 묶는다. */
	private final class BindingResponse extends HttpServletResponseWrapper {

		private final String user;

		BindingResponse(HttpServletResponse response, String user) {
			super(response);
			this.user = user;
		}

		@Override
		public void setHeader(String name, String value) {
			bind(name, value);
			super.setHeader(name, value);
		}

		@Override
		public void addHeader(String name, String value) {
			bind(name, value);
			super.addHeader(name, value);
		}

		private void bind(String name, String value) {
			if (SESSION_ID_HEADER.equalsIgnoreCase(name) && value != null) {
				owners.putIfAbsent(value, this.user);
			}
		}
	}
}
