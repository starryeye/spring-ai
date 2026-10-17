package dev.starryeye.modern.agent.config;

import dev.starryeye.modern.agent.cimd.ClientType;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP client는 설정의 resource 주소로, redirect 없이 보낸다.
 * cache key가 되는 token과 tool을 부를 때 보내는 token은 같은 호출로 얻는다.
 * 그래야 만료로 갱신된 token도 key와 실제로 보내는 값이 같다.
 */
class ToolCatalogConfigTest {

	OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);

	Authentication user = new TestingAuthenticationToken("user", null, "ROLE_USER");

	OAuth2AuthorizedClient authorizedClientWithToken(String tokenValue) {
		ClientRegistration registration = ClientRegistration.withRegistrationId(McpSecurityConfig.REGISTRATION_ID)
				.clientId("modern-shop-agent")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri("http://localhost:9070/oauth2/authorize")
				.tokenUri("http://localhost:9070/oauth2/token")
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
	void MCP_client는_resource_주소로_보낸다() {
		var properties = new McpAuthorizationProperties("http://localhost:8181/mcp", ClientType.CHATGPT);

		assertThat(new ToolCatalogConfig().modernMcpClient(properties).endpoint())
				.isEqualTo(URI.create("http://localhost:8181/mcp"));
	}

	@Test
	void MCP_client는_redirect를_따라가지_않고_연결을_2초만_기다린다() {
		HttpClient http = ToolCatalogConfig.httpClient();

		assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
		assertThat(http.connectTimeout()).contains(Duration.ofSeconds(2));
	}

	@Test
	void authorized_client가_없으면_오류다() {
		when(this.manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(null);

		assertThatThrownBy(() -> ToolCatalogConfig.accessToken(this.manager, this.user))
				.isInstanceOf(IllegalStateException.class);
	}
}
