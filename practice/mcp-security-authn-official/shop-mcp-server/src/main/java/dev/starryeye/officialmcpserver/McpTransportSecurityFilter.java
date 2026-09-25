package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP endpoint 의 {@code Origin}·{@code Host} 를 인증보다 먼저 검사한다(MCP 2025-11-25 Transports — Security Warning).
 *
 * <p>검사 규칙은 SDK {@link DefaultServerTransportSecurityValidator} 를 그대로 쓴다. SDK 는 이 검사를
 * transport 안에서 하므로 Spring Security 가 먼저 돌면 token 없는 요청은 401 을 받고 검사에 닿지 않는다.
 * 이 filter 를 security filter chain 앞에 두어 token 과 무관하게 잘못된 {@code Origin} 은 403,
 * {@code Host} 는 421 로 막는다. 응답 본문은 SDK transport 와 같은 평문 메시지다.
 */
public class McpTransportSecurityFilter extends OncePerRequestFilter {

	private final ServerTransportSecurityValidator validator;

	public McpTransportSecurityFilter(ServerTransportSecurityValidator validator) {
		this.validator = validator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			this.validator.validateHeaders(headers(request));
		}
		catch (ServerTransportSecurityException ex) {
			response.setStatus(ex.getStatusCode());
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(ex.getMessage() == null ? "" : ex.getMessage());
			return;
		}
		chain.doFilter(request, response);
	}

	/** SDK WebMvc transport 와 같은 모양: 소문자 헤더 이름 → 값 목록. */
	private static Map<String, List<String>> headers(HttpServletRequest request) {
		Map<String, List<String>> headers = new HashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name)));
		}
		return headers;
	}
}
