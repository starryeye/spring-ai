package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PublicClientRefreshTokenAuthenticationProviderTest {

	static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

	static final String CHATGPT = "https://localhost:8172/oauth/client.json";

	static final String NO_REFRESH = "https://localhost:8172/oauth/no-refresh.json";

	static RegisteredClient client(String id, ClientAuthenticationMethod method, boolean refreshGrant) {
		RegisteredClient.Builder builder = RegisteredClient.withId(id).clientId(id)
				.clientAuthenticationMethod(method)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver");
		if (refreshGrant) {
			builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
		}
		return builder.build();
	}

	PublicClientRefreshTokenAuthenticationProvider provider = new PublicClientRefreshTokenAuthenticationProvider(
			new InMemoryRegisteredClientRepository(
					client(CLAUDE, ClientAuthenticationMethod.NONE, true),
					client(CHATGPT, ClientAuthenticationMethod.PRIVATE_KEY_JWT, true),
					client(NO_REFRESH, ClientAuthenticationMethod.NONE, false)));

	static OAuth2ClientAuthenticationToken refresh(String clientId) {
		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null,
				Map.of("grant_type", "refresh_token"));
	}

	@Test
	void public_client의_refresh_요청을_인증한다() {
		OAuth2ClientAuthenticationToken result = (OAuth2ClientAuthenticationToken) this.provider.authenticate(refresh(CLAUDE));

		assertThat(result.isAuthenticated()).isTrue();
		assertThat(result.getRegisteredClient().getClientId()).isEqualTo(CLAUDE);
	}

	@Test
	void private_key_jwt_client는_refresh에서_public_client로_인증되지_않는다() {
		invalidClient(refresh(CHATGPT));
	}

	@Test
	void 모르는_client나_refresh_grant가_없는_client는_거절한다() {
		invalidClient(refresh("https://localhost:8172/oauth/unknown.json"));
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

	void invalidClient(OAuth2ClientAuthenticationToken token) {
		assertThatExceptionOfType(OAuth2AuthenticationException.class)
				.isThrownBy(() -> this.provider.authenticate(token))
				.satisfies(ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_client"));
	}
}
