package dev.starryeye.visibility.agent.config;

import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.time.Clock;
import java.util.List;

/** 사용자마다 다른 MCP tool 목록을 access token별로 들고 있는 보관소를 만든다(안내서 12장). */
@Configuration
public class ToolCatalogConfig {

	@Bean
	public UserToolCatalog userToolCatalog(List<McpSyncClient> mcpSyncClients,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return new UserToolCatalog(UserToolCatalog.mcp(mcpSyncClients.get(0)),
				user -> accessToken(authorizedClientManager, user), Clock.systemUTC());
	}

	/**
	 * MCP 요청에 token을 붙이는 customizer와 같은 호출이다.
	 * 만료된 token이면 여기서 갱신되므로, cache key의 token과 실제로 붙는 token이 같다.
	 */
	static String accessToken(OAuth2AuthorizedClientManager manager, Authentication user) {
		OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
				.withClientRegistrationId(McpSecurityConfig.REGISTRATION_ID)
				.principal(user)
				.build());
		if (client == null) {
			throw new IllegalStateException("로그인한 사용자의 access token이 없다");
		}
		return client.getAccessToken().getTokenValue();
	}
}
