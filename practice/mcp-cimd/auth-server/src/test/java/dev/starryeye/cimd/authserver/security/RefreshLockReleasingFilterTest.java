package dev.starryeye.cimd.authserver.security;

import dev.starryeye.cimd.authserver.cimd.MutableClock;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RefreshLockReleasingFilterTest {

	RefreshTokenReuseDetectingAuthorizationService service = new RefreshTokenReuseDetectingAuthorizationService(
			new InMemoryOAuth2AuthorizationService(), new MutableClock(Instant.parse("2026-10-09T00:00:00Z")));

	RefreshLockReleasingFilter filter = new RefreshLockReleasingFilter(this.service);

	@Test
	void 저장하지_않고_실패한_요청의_lock을_요청이_끝날_때_푼다() {
		this.service.save(RefreshTokenReuseDetectingAuthorizationServiceTest.authorization("first"));

		// 요청 처리 중 grant를 찾고(lock) 저장하기 전에 실패한다.
		assertThatExceptionOfType(ServletException.class).isThrownBy(() -> this.filter.doFilter(
				new MockHttpServletRequest("POST", "/oauth2/token"), new MockHttpServletResponse(), (request, response) -> {
					assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
					assertThat(this.service.lockCount()).isEqualTo(1);
					throw new ServletException("검증 실패");
				}));

		assertThat(this.service.lockCount()).isZero();
	}
}
