package dev.starryeye.modern.authserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * token endpoint 요청이 끝나면, 그 요청이 잡은 refresh lock을 모두 푼다.
 *
 * <p>{@link RefreshTokenReuseDetectingAuthorizationService}는 refresh token으로 grant를 찾을 때 lock을 잡고,
 * 그 grant를 저장할 때 푼다. 검증에 실패한 요청(다른 client의 token, 넓힌 scope, client 인증 실패 등)은
 * 저장하지 않고 끝나므로 lock이 남는다. Tomcat은 thread를 다시 쓰므로, 남은 lock은 다음 요청까지 이어진다.
 * 그래서 성공과 실패에 상관없이 요청이 끝날 때 푼다.
 *
 * <p>Spring Security filter chain보다 바깥에 둔다. client 인증 filter가 던진 실패도 이 filter의 finally를 거친다.
 */
public final class RefreshLockReleasingFilter extends OncePerRequestFilter {

	private final RefreshTokenReuseDetectingAuthorizationService authorizations;

	public RefreshLockReleasingFilter(RefreshTokenReuseDetectingAuthorizationService authorizations) {
		this.authorizations = authorizations;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		finally {
			this.authorizations.releaseHeldLocks();
		}
	}
}
