package dev.starryeye.modern.mcpserver.config;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.filter.McpTransportSecurityFilter;
import dev.starryeye.modern.mcpserver.filter.ToolCallRateLimiter;
import dev.starryeye.modern.mcpserver.filter.ToolScopeFilter;
import dev.starryeye.modern.mcpserver.tool.ToolScopeRegistry;
import dev.starryeye.modern.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * MCP endpoint에만 적용하는 servlet filter와, tool이 쓰는 저장소를 등록한다.
 *
 * <p>filter는 이 순서로 돌고, 모두 통과한 요청만 SDK transport servlet에 닿는다.
 * <ol>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host}를 검증한다.
 *       Spring Security보다 먼저 돈다.</li>
 *   <li>Spring Security — token을 검증한다.
 *       없거나 틀리면 {@code 401}이다.</li>
 *   <li>{@link ToolScopeFilter} — token의 scope가 이 요청에 충분한지 header로 본다.
 *       모자라면 {@code 403}이다.</li>
 * </ol>
 * MCP Server와 transport 자체는 {@link ModernMcpConfig}가 만든다.
 */
@Configuration
public class McpTransportConfig {

	@Bean
	public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
			@Value("${server.port}") int port) {
		DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				// browser에서 직접 부를 일이 없으므로 허용 Origin을 두지 않는다.
				// Origin header가 있으면 403이다.
				.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
				.build();
		FilterRegistrationBean<McpTransportSecurityFilter> registration =
				new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
		registration.addUrlPatterns(ModernMcpConfig.ENDPOINT);
		// Spring Security filter chain보다 먼저 돌아서, 인증 전에 막는다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}

	/**
	 * {@link ToolScopeFilter}를 MCP endpoint에만 적용한다.
	 * 인증된 사용자의 scope를 봐야 하므로 Spring Security 바로 뒤에서 돈다.
	 * scope가 모자라면 SDK transport에 닿기 전에 {@code 403}으로 끝낸다.
	 * 사용자마다 {@code tools/call} 횟수도 세어, 1분 상한을 넘으면 {@code 429}로 끝낸다.
	 */
	@Bean
	public FilterRegistrationBean<ToolScopeFilter> toolScopeFilter(ToolScopeRegistry registry,
			ToolVisibility visibility, OAuth2ResourceServerProperties resourceServer, McpLimitProperties limits) {
		String resourceMetadataUrl = ResourceMetadataUrl.of(ResourceMetadataUrl.resource(resourceServer));
		ToolCallRateLimiter rateLimiter =
				new ToolCallRateLimiter(limits.toolCallsPerMinute(), Duration.ofMinutes(1), Clock.systemUTC());
		FilterRegistrationBean<ToolScopeFilter> registration = new FilterRegistrationBean<>(
				new ToolScopeFilter(registry, visibility, resourceMetadataUrl, rateLimiter));
		registration.addUrlPatterns(ModernMcpConfig.ENDPOINT);
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
		return registration;
	}

	/** 장바구니 저장소다. 만료 계산은 {@link Clock}으로 한다(테스트가 시간을 돌릴 수 있게). */
	@Bean
	public BasketStore basketStore() {
		return new BasketStore(Clock.systemUTC());
	}
}
