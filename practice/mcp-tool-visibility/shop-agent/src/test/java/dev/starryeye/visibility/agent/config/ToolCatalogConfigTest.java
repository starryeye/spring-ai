package dev.starryeye.visibility.agent.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * cache key가 되는 token은 MCP 요청에 token을 붙이는 customizer와 같은 호출로 얻어야 한다.
 * 그래야 만료로 갱신된 token도 key와 실제로 붙는 값이 같다.
 */
class ToolCatalogConfigTest {

	OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);

	Authentication user = new TestingAuthenticationToken("user", null, "ROLE_USER");

	OAuth2AuthorizedClient authorizedClientWithToken(String tokenValue) {
		ClientRegistration registration = ClientRegistration.withRegistrationId(McpSecurityConfig.REGISTRATION_ID)
				.clientId("visibility-shop-agent")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("http://localhost:9050/oauth2/authorize")
				.tokenUri("http://localhost:9050/oauth2/token")
				.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, tokenValue, Instant.now(), Instant.now().plusSeconds(3600));
		return new OAuth2AuthorizedClient(registration, "user", accessToken);
	}

	@Test
	void authorize가_준_token_값을_그대로_돌려준다() {
		when(this.manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorizedClientWithToken("abc123"));

		String token = ToolCatalogConfig.accessToken(this.manager, this.user);

		assertThat(token).isEqualTo("abc123");
		ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
		verify(this.manager).authorize(request.capture());
		assertThat(request.getValue().getClientRegistrationId()).isEqualTo(McpSecurityConfig.REGISTRATION_ID);
		assertThat(request.getValue().getPrincipal()).isSameAs(this.user);
	}

	@Test
	void authorized_client가_없으면_오류다() {
		when(this.manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);

		assertThatThrownBy(() -> ToolCatalogConfig.accessToken(this.manager, this.user))
				.isInstanceOf(IllegalStateException.class);
	}
}
