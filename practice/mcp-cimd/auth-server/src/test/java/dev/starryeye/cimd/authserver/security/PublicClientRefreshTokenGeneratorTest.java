package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

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

	static DefaultOAuth2TokenContext context(RegisteredClient client, OAuth2TokenType type) {
		return DefaultOAuth2TokenContext.builder()
				.registeredClient(client)
				.tokenType(type)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.build();
	}

	@Test
	void public_client에게도_refresh_token을_만든다() {
		RegisteredClient client = publicClient(true);

		OAuth2RefreshToken token = this.generator.generate(context(client, OAuth2TokenType.REFRESH_TOKEN));

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
