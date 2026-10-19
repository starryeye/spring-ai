package dev.starryeye.modern.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OwnTokenIntrospectionAuthenticationProviderTest {

	static final RegisteredClient OWNER = client("https://localhost:8182/oauth/client.json");

	static final RegisteredClient OTHER = client("https://attacker.example/client.json");

	InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService();

	AtomicInteger delegateCalls = new AtomicInteger();

	/** Spring의 introspection provider 대신이다. 불리면 active인 결과를 돌려준다. */
	AuthenticationProvider springIntrospection = new AuthenticationProvider() {
		@Override
		public Authentication authenticate(Authentication authentication) {
			OwnTokenIntrospectionAuthenticationProviderTest.this.delegateCalls.incrementAndGet();
			OAuth2TokenIntrospectionAuthenticationToken request = (OAuth2TokenIntrospectionAuthenticationToken) authentication;
			return new OAuth2TokenIntrospectionAuthenticationToken(request.getToken(),
					(Authentication) request.getPrincipal(), OAuth2TokenIntrospection.builder(true).build());
		}

		@Override
		public boolean supports(Class<?> authentication) {
			return true;
		}
	};

	OwnTokenIntrospectionAuthenticationProvider provider =
			new OwnTokenIntrospectionAuthenticationProvider(this.springIntrospection, this.authorizations);

	static RegisteredClient client(String id) {
		return RegisteredClient.withId(id).clientId(id)
				.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8180/login/oauth2/code/authserver")
				.build();
	}

	static OAuth2TokenIntrospectionAuthenticationToken 요청(String token, RegisteredClient caller) {
		OAuth2ClientAuthenticationToken client =
				new OAuth2ClientAuthenticationToken(caller, ClientAuthenticationMethod.PRIVATE_KEY_JWT, null);
		return new OAuth2TokenIntrospectionAuthenticationToken(token, client, null, Map.of());
	}

	void grant(RegisteredClient owner, String refreshToken) {
		Instant now = Instant.now();
		this.authorizations.save(OAuth2Authorization.withRegisteredClient(owner)
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.refreshToken(new OAuth2RefreshToken(refreshToken, now, now.plusSeconds(3600)))
				.build());
	}

	static boolean active(Authentication result) {
		return ((OAuth2TokenIntrospectionAuthenticationToken) result).getTokenClaims().isActive();
	}

	@Test
	void 자기_token이면_Spring의_introspection에_맡긴다() {
		grant(OWNER, "rt");

		assertThat(active(this.provider.authenticate(요청("rt", OWNER)))).isTrue();
		assertThat(this.delegateCalls).hasValue(1);
	}

	@Test
	void 다른_client의_token이면_내용_없이_active_false다() {
		grant(OWNER, "rt");

		Authentication result = this.provider.authenticate(요청("rt", OTHER));

		assertThat(active(result)).isFalse();
		assertThat(((OAuth2TokenIntrospectionAuthenticationToken) result).getTokenClaims().getClaims())
				.containsOnlyKeys("active");
		// token 주인의 문서를 가져오는 Spring의 조회까지 가지 않는다.
		assertThat(this.delegateCalls).hasValue(0);
	}

	@Test
	void 모르는_token이면_active_false다() {
		assertThat(active(this.provider.authenticate(요청("unknown", OWNER)))).isFalse();
	}

	@Test
	void token_주인의_client를_다시_찾지_못해_Spring이_실패하면_active_false다() {
		// Spring은 token 주인의 client를 다시 찾고, 없으면 Assert로 실패한다(500). 문서를 가져오지 못한 경우다.
		grant(OWNER, "rt");
		OwnTokenIntrospectionAuthenticationProvider failing = new OwnTokenIntrospectionAuthenticationProvider(
				new AuthenticationProvider() {
					@Override
					public Authentication authenticate(Authentication authentication) {
						throw new IllegalArgumentException("authorizedClient cannot be null");
					}

					@Override
					public boolean supports(Class<?> authentication) {
						return true;
					}
				}, this.authorizations);

		assertThat(active(failing.authenticate(요청("rt", OWNER)))).isFalse();
	}

	@Test
	void 인증되지_않은_client는_invalid_client다() {
		OAuth2ClientAuthenticationToken unauthenticated = new OAuth2ClientAuthenticationToken(OWNER.getClientId(),
				ClientAuthenticationMethod.PRIVATE_KEY_JWT, "assertion", null);
		OAuth2TokenIntrospectionAuthenticationToken request =
				new OAuth2TokenIntrospectionAuthenticationToken("rt", unauthenticated, null, Map.of());

		assertThatExceptionOfType(OAuth2AuthenticationException.class)
				.isThrownBy(() -> this.provider.authenticate(request))
				.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_client"));
	}
}
