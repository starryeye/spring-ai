package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicClientRefreshTokenGeneratorTest {

	static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

	PublicClientRefreshTokenGenerator generator = new PublicClientRefreshTokenGenerator(Clock.fixed(NOW, ZoneOffset.UTC));

	static RegisteredClient publicClient(boolean refreshGrant) {
		RegisteredClient.Builder builder = RegisteredClient.withId("https://localhost:8172/oauth/public-client.json")
				.clientId("https://localhost:8172/oauth/public-client.json")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver");
		if (refreshGrant) {
			builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
		}
		return builder.build();
	}

	// Spring의 generator는 authorization grant의 client 인증이 none이면 refresh token을 만들지 않는다.
	// 그래서 context에도 public client가 authorization code로 인증한 grant를 담는다.
	static DefaultOAuth2TokenContext context(RegisteredClient client, OAuth2TokenType type) {
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(client,
				ClientAuthenticationMethod.NONE, null);
		return DefaultOAuth2TokenContext.builder()
				.registeredClient(client)
				.tokenType(type)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizationGrant(new OAuth2AuthorizationCodeAuthenticationToken("code", clientPrincipal,
						"http://localhost:8170/login/oauth2/code/authserver", Map.of()))
				.build();
	}

	@Test
	void public_client에게도_refresh_token을_만든다() {
		RegisteredClient client = publicClient(true);
		DefaultOAuth2TokenContext context = context(client, OAuth2TokenType.REFRESH_TOKEN);

		// Spring의 기본 generator는 같은 요청에 refresh token을 만들지 않는다. 이 class가 다른 점이다.
		assertThat(new OAuth2RefreshTokenGenerator().generate(context)).isNull();

		OAuth2RefreshToken token = this.generator.generate(context);

		assertThat(token).isNotNull();
		assertThat(token.getTokenValue()).hasSizeGreaterThan(60);
		assertThat(token.getIssuedAt()).isEqualTo(NOW);
		assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(client.getTokenSettings().getRefreshTokenTimeToLive()));
		assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofMinutes(60));
	}

	@Test
	void refresh_token_grant가_없는_client에게는_만들지_않는다() {
		assertThat(this.generator.generate(context(publicClient(false), OAuth2TokenType.REFRESH_TOKEN))).isNull();
	}

	@Test
	void refresh_token이_아닌_요청에는_만들지_않는다() {
		assertThat(this.generator.generate(context(publicClient(true), OAuth2TokenType.ACCESS_TOKEN))).isNull();
	}
}
