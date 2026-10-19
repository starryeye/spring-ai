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
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PublicClientRefreshTokenAuthenticationProviderTest {

	static final String CLAUDE = "https://localhost:8182/oauth/public-client.json";

	static final String CHATGPT = "https://localhost:8182/oauth/client.json";

	static final String NO_REFRESH = "https://localhost:8182/oauth/no-refresh.json";

	static RegisteredClient client(String id, ClientAuthenticationMethod method, boolean refreshGrant) {
		RegisteredClient.Builder builder = RegisteredClient.withId(id).clientId(id)
				.clientAuthenticationMethod(method)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8180/login/oauth2/code/authserver");
		if (refreshGrant) {
			builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
		}
		return builder.build();
	}

	static final RegisteredClient CLAUDE_CLIENT = client(CLAUDE, ClientAuthenticationMethod.NONE, true);

	static final RegisteredClient CHATGPT_CLIENT = client(CHATGPT, ClientAuthenticationMethod.PRIVATE_KEY_JWT, true);

	InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService();

	PublicClientRefreshTokenAuthenticationProvider provider = new PublicClientRefreshTokenAuthenticationProvider(
			new InMemoryRegisteredClientRepository(CLAUDE_CLIENT, CHATGPT_CLIENT,
					client(NO_REFRESH, ClientAuthenticationMethod.NONE, false)),
			this.authorizations);

	static OAuth2ClientAuthenticationToken refresh(String clientId) {
		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
				Map.of("grant_type", "refresh_token"));
	}

	static OAuth2ClientAuthenticationToken refresh(String clientId, String refreshToken) {
		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
				Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
	}

	/** grant를 하나 저장한다. {@code recordedMethod}가 null이면 처음 저장할 때의 인증 방식 기록이 없는 grant다. */
	void grant(RegisteredClient client, String refreshToken, String recordedMethod) {
		Instant now = Instant.now();
		OAuth2Authorization.Builder grant = OAuth2Authorization.withRegisteredClient(client)
				.id(refreshToken + "-grant")
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.refreshToken(new OAuth2RefreshToken(refreshToken, now, now.plusSeconds(3600)));
		if (recordedMethod != null) {
			grant.attribute(AuthenticationMethodRecordingAuthorizationService.CLIENT_AUTHENTICATION_METHOD, recordedMethod);
		}
		this.authorizations.save(grant.build());
	}

	@Test
	void public_client의_refresh_요청을_인증한다() {
		grant(CLAUDE_CLIENT, "rt-none", "none");

		OAuth2ClientAuthenticationToken result =
				(OAuth2ClientAuthenticationToken) this.provider.authenticate(refresh(CLAUDE, "rt-none"));

		assertThat(result.isAuthenticated()).isTrue();
		assertThat(result.getRegisteredClient().getClientId()).isEqualTo(CLAUDE);
	}

	@Test
	void private_key_jwt_client는_refresh에서_public_client로_인증되지_않는다() {
		invalidClient(refresh(CHATGPT));
	}

	@Test
	void private_key_jwt로_받은_grant는_지금_문서가_none이어도_public_client로_refresh하지_못한다() {
		// 문서 host를 장악한 쪽이 문서를 none으로 바꿨다. grant는 private_key_jwt로 받은 것이다.
		grant(CLAUDE_CLIENT, "rt-pkjwt", "private_key_jwt");

		invalidClient(refresh(CLAUDE, "rt-pkjwt"));
	}

	@Test
	void 인증_방식_기록이_없는_grant는_public_client로_refresh하지_못한다() {
		grant(CLAUDE_CLIENT, "rt-unknown", null);

		invalidClient(refresh(CLAUDE, "rt-unknown"));
	}

	@Test
	void 다른_client의_grant나_모르는_token은_Spring의_검사에_맡긴다() {
		// Spring의 OAuth2RefreshTokenAuthenticationProvider가 이어서 invalid_grant로 끝낸다.
		grant(CHATGPT_CLIENT, "rt-chatgpt", "private_key_jwt");

		assertThat(this.provider.authenticate(refresh(CLAUDE, "rt-chatgpt")).isAuthenticated()).isTrue();
		assertThat(this.provider.authenticate(refresh(CLAUDE, "unknown")).isAuthenticated()).isTrue();
	}

	@Test
	void 모르는_client나_refresh_grant가_없는_client는_거절한다() {
		invalidClient(refresh("https://localhost:8182/oauth/unknown.json"));
		invalidClient(refresh(NO_REFRESH));
	}

	@Test
	void authorization_code_요청은_맡지_않는다() {
		// Spring의 public client 인증은 이 단계에서 PKCE(code_verifier)를 검사한다.
		// 이 provider가 authorization code 요청까지 맡으면 그 검사를 건너뛴다.
		OAuth2ClientAuthenticationToken code = new OAuth2ClientAuthenticationToken(CLAUDE, ClientAuthenticationMethod.NONE,
				null, Map.of("grant_type", "authorization_code", "code_verifier", "v"));

		assertThat(this.provider.authenticate(code)).isNull();
	}

	@Test
	void none이_아닌_인증은_맡지_않는다() {
		OAuth2ClientAuthenticationToken basic = new OAuth2ClientAuthenticationToken(CLAUDE,
				ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "s", Map.of("grant_type", "refresh_token"));

		assertThat(this.provider.authenticate(basic)).isNull();
	}

	@Test
	void 감싼_Spring_public_client_인증은_refresh_요청을_건너뛰고_code_요청만_맡는다() {
		// Spring의 public client 인증이 refresh 요청에 invalid_grant를 던지면 이 provider의 invalid_client를 덮는다.
		AuthenticationProvider springPublicClient = new AuthenticationProvider() {
			@Override
			public Authentication authenticate(Authentication authentication) {
				throw new OAuth2AuthenticationException("invalid_grant");
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return true;
			}
		};
		AuthenticationProvider wrapped = PublicClientRefreshTokenAuthenticationProvider.skippingRefreshRequests(springPublicClient);

		assertThat(wrapped.authenticate(refresh(CLAUDE, "rt"))).isNull();
		OAuth2ClientAuthenticationToken code = new OAuth2ClientAuthenticationToken(CLAUDE, ClientAuthenticationMethod.NONE,
				null, Map.of("grant_type", "authorization_code", "code_verifier", "v"));
		assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> wrapped.authenticate(code));
	}

	void invalidClient(OAuth2ClientAuthenticationToken token) {
		assertThatExceptionOfType(OAuth2AuthenticationException.class)
				.isThrownBy(() -> this.provider.authenticate(token))
				.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_client"));
	}
}
