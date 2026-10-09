package dev.starryeye.cimd.authserver.security;

import dev.starryeye.cimd.authserver.cimd.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenReuseDetectingAuthorizationServiceTest {

	static final String CLIENT_ID = "https://localhost:8172/oauth/public-client.json";

	static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

	MutableClock clock = new MutableClock(NOW);

	RefreshTokenReuseDetectingAuthorizationService service =
			new RefreshTokenReuseDetectingAuthorizationService(new InMemoryOAuth2AuthorizationService(), this.clock);

	static RegisteredClient client() {
		return RegisteredClient.withId(CLIENT_ID)
				.clientId(CLIENT_ID)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver")
				.build();
	}

	static OAuth2RefreshToken refreshToken(String value) {
		return new OAuth2RefreshToken(value, NOW, NOW.plus(Duration.ofHours(1)));
	}

	static OAuth2Authorization authorization(String refreshToken) {
		return authorization("authorization-1", refreshToken);
	}

	static OAuth2Authorization authorization(String id, String refreshToken) {
		return OAuth2Authorization.withRegisteredClient(client())
				.id(id)
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.refreshToken(refreshToken(refreshToken))
				.build();
	}

	/** refresh 한 번: Spring은 같은 authorization의 refresh token만 새것으로 바꿔 저장한다. */
	OAuth2Authorization rotate(OAuth2Authorization current, String next) {
		OAuth2Authorization rotated = OAuth2Authorization.from(current).refreshToken(refreshToken(next)).build();
		this.service.save(rotated);
		return rotated;
	}

	@Test
	void 지금의_refresh_token으로는_grant를_찾는다() {
		this.service.save(authorization("first"));

		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}

	@Test
	void 버린_refresh_token이_다시_오면_grant_전체를_끊는다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// 도둑이든 진짜 client든, 버린 token을 내민 쪽이 있으면 도난으로 본다.
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNull();

		// 지금 쓰던 refresh token과 grant도 함께 없어진다.
		assertThat(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.findById("authorization-1")).isNull();
	}

	@Test
	void 모르는_refresh_token은_아무것도_끊지_않는다() {
		this.service.save(authorization("first"));

		assertThat(this.service.findByToken("unknown", OAuth2TokenType.REFRESH_TOKEN)).isNull();
		assertThat(this.service.findByToken("first", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}

	@Test
	void 버린_refresh_token이_만료된_뒤에는_기록을_지운다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// 버린 token의 만료(1시간)가 지나면 그 token은 어차피 쓸 수 없으므로 기록하지 않는다.
		this.clock.advance(Duration.ofHours(2));
		this.service.save(authorization("authorization-2", "other")); // 저장할 때 만료된 기록을 치운다.

		assertThat(this.service.retiredCount()).isZero();
	}

	@Test
	void refresh_token이_아닌_종류로_찾을_때는_끊지_않는다() {
		OAuth2Authorization first = authorization("first");
		this.service.save(first);
		rotate(first, "second");

		// introspection이나 type 없는 조회는 refresh 요청이 아니다.
		assertThat(this.service.findByToken("first", null)).isNull();
		assertThat(this.service.findByToken("second", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
	}
}
