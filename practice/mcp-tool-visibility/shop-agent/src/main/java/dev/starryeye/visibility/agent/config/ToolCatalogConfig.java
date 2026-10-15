package dev.starryeye.visibility.agent.config;

import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.util.Assert;

import java.time.Clock;
import java.util.List;

/** 사용자마다 다른 MCP tool 목록을 access token별로 들고 있는 보관소를 만든다(안내서 12장). */
@Configuration
public class ToolCatalogConfig {

	@Bean
	public UserToolCatalog userToolCatalog(List<McpSyncClient> mcpSyncClients,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return new UserToolCatalog(UserToolCatalog.mcp(onlyClient(mcpSyncClients)),
				user -> accessToken(authorizedClientManager, user), Clock.systemUTC());
	}

	/**
	 * 이 agent는 MCP Server 하나(application.yml의 connection 하나)에 붙는다.
	 * 연결이 여럿인데 첫 것만 고르면, 목록을 받는 서버와 tool을 부르는 서버가 달라질 수 있다.
	 * 그래서 하나가 아니면 기동을 멈춘다.
	 */
	static McpSyncClient onlyClient(List<McpSyncClient> mcpSyncClients) {
		Assert.state(mcpSyncClients.size() == 1,
				() -> "MCP 연결은 정확히 하나여야 한다(지금 %d개)".formatted(mcpSyncClients.size()));
		return mcpSyncClients.get(0);
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
