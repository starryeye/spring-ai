package dev.starryeye.modern.agent.config;

import dev.starryeye.modern.agent.mcp.ModernMcpClient;
import dev.starryeye.modern.agent.mcp.UserToolCatalog;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * MCP Server를 부르는 2026-07-28 client와, 사용자마다 다른 tool 목록을 access token별로 들고 있는 보관소를 만든다.
 *
 * <p>다른 class는 이 두 bean에만 기댄다.
 * SDK에 2026-07-28 client가 생기면 {@link ModernMcpClient}와 {@link UserToolCatalog}만 바꾸면 된다.
 */
@Configuration
public class ToolCatalogConfig {

	/** MCP Server가 연결을 받지 않으면 채팅 요청을 오래 붙잡지 않고 실패로 끝낸다. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

	/** endpoint는 discovery의 출발점과 같은 {@code mcp.authorization.resource-url}이다. */
	@Bean
	public ModernMcpClient modernMcpClient(McpAuthorizationProperties properties) {
		return new ModernMcpClient(URI.create(properties.resourceUrl()), httpClient(),
				new JacksonMcpJsonMapper(JsonMapper.builder().build()));
	}

	/**
	 * redirect를 따라가면 access token이 담긴 요청이 MCP Server가 고른 다른 주소로 간다.
	 * 그래서 redirect는 따라가지 않고 오류로 끝낸다.
	 * MCP Server(Tomcat)는 h2c를 켜지 않았다.
	 * 그래서 평문 연결에서 HTTP/2 upgrade를 시도하지 않도록 HTTP/1.1로 보낸다.
	 */
	static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
	}

	@Bean
	public UserToolCatalog userToolCatalog(ModernMcpClient modernMcpClient,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return new UserToolCatalog(modernMcpClient, user -> accessToken(authorizedClientManager, user),
				Clock.systemUTC());
	}

	/**
	 * 목록을 받을 때와 tool을 부를 때 모두 이 호출로 token을 꺼낸다.
	 * 만료된 token이면 여기서 refresh되므로, cache key의 token과 실제로 보내는 token이 같다.
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
