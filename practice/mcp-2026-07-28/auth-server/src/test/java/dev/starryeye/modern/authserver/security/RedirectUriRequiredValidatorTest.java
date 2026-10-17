package dev.starryeye.modern.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RedirectUriRequiredValidatorTest {

	static final String CLIENT_ID = "https://localhost:8182/oauth/public-client.json";

	static final String REDIRECT_URI = "http://localhost:8180/login/oauth2/code/authserver";

	RedirectUriRequiredValidator validator = new RedirectUriRequiredValidator();

	static OAuth2AuthorizationCodeRequestAuthenticationContext 요청(String redirectUri) {
		RegisteredClient client = RegisteredClient.withId(CLIENT_ID).clientId(CLIENT_ID)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri(REDIRECT_URI)
				.build();
		OAuth2AuthorizationCodeRequestAuthenticationToken request = new OAuth2AuthorizationCodeRequestAuthenticationToken(
				"http://localhost:9070/oauth2/authorize", CLIENT_ID,
				UsernamePasswordAuthenticationToken.authenticated("user", null, AuthorityUtils.NO_AUTHORITIES),
				redirectUri, "state-1", Set.of("products:read"), Map.of());
		return OAuth2AuthorizationCodeRequestAuthenticationContext.with(request).registeredClient(client).build();
	}

	@Test
	void redirect_uri가_있으면_통과한다() {
		assertThatNoException().isThrownBy(() -> this.validator.accept(요청(REDIRECT_URI)));
	}

	@Test
	void redirect_uri가_없으면_invalid_request이고_돌아갈_주소를_남기지_않는다() {
		OAuth2AuthorizationCodeRequestAuthenticationException ex = catchThrowableOfType(
				OAuth2AuthorizationCodeRequestAuthenticationException.class, () -> this.validator.accept(요청(null)));

		assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_request");
		assertThat(ex.getError().getDescription()).contains("redirect_uri");
		assertThat(ex.getAuthorizationCodeRequestAuthentication().getRedirectUri()).isNull();
	}

	@Test
	void 빈_redirect_uri도_없는_것과_같다() {
		OAuth2AuthorizationCodeRequestAuthenticationException ex = catchThrowableOfType(
				OAuth2AuthorizationCodeRequestAuthenticationException.class, () -> this.validator.accept(요청(" ")));

		assertThat(ex.getError().getErrorCode()).isEqualTo("invalid_request");
	}
}
