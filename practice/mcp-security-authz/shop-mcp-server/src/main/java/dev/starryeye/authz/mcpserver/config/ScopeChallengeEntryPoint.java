package dev.starryeye.authz.mcpserver.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * {@code 401} challenge에 처음 요청할 scope를 더한다(MCP 2025-11-25 Authorization — Scope Selection Strategy).
 *
 * <p>client는 {@code 401}의 {@code scope}를 가장 먼저 보고 그만큼만 요청한다.
 * 그래서 여기에는 가장 위험이 낮은 조회 scope 하나만 적는다.
 * 쓰기 scope는 그 작업을 처음 시도할 때 {@code 403}으로 알린다(Security Best Practices — Scope Minimization).
 *
 * <p>Spring의 {@code BearerTokenAuthenticationEntryPoint}는 token이 없는 요청의 challenge에 {@code scope}를 넣지 않는다.
 * 그래서 그 결과 header 끝에 {@code scope}를 붙인다.
 */
public class ScopeChallengeEntryPoint implements AuthenticationEntryPoint {

	private final AuthenticationEntryPoint delegate;

	private final String scope;

	public ScopeChallengeEntryPoint(AuthenticationEntryPoint delegate, String scope) {
		this.delegate = delegate;
		this.scope = scope;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		this.delegate.commence(request, response, authException);
		String challenge = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
		if (challenge != null && !challenge.contains("scope=\"")) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge + ", scope=\"" + this.scope + "\"");
		}
	}
}
