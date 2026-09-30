package dev.starryeye.stateless.mcpserver.config;

import dev.starryeye.stateless.mcpserver.basket.BasketStore;
import dev.starryeye.stateless.mcpserver.filter.McpProtocolVersionFilter;
import dev.starryeye.stateless.mcpserver.filter.McpTransportSecurityFilter;
import dev.starryeye.stateless.mcpserver.filter.ToolScopeFilter;
import dev.starryeye.stateless.mcpserver.security.McpCaller;
import dev.starryeye.stateless.mcpserver.tool.BasketTools;
import dev.starryeye.stateless.mcpserver.tool.ProductTools;
import dev.starryeye.stateless.mcpserver.tool.ToolScopeRegistry;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/**
 * MCP endpoint에만 적용하는 servlet filter 세 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host}를 검증한다. Spring Security보다 먼저 돈다.</li>
 *   <li>{@link ToolScopeFilter} — token의 scope가 이 요청에 충분한지 본다. 인증 뒤, 버전 검사 앞에서 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version}을 검증한다. scope 검사 뒤에 돈다.</li>
 * </ul>
 *
 * <p>stateless transport bean은 사용자를 읽는 {@code contextExtractor}를 넣으려고 직접 만든다.
 */
@Configuration
public class McpTransportConfig {

	/**
	 * Spring AI 자동 설정의 stateless transport bean을 대신한다(그 bean은 {@code @ConditionalOnMissingBean}이다).
	 * 바꾸는 것은 {@code contextExtractor} 하나다.
	 * 요청마다 token의 사용자를 {@code McpTransportContext}에 넣어, tool이 누가 불렀는지 알게 한다.
	 */
	@Bean
	public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		return WebMvcStatelessServerTransport.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.messageEndpoint(properties.getMcpEndpoint())
				.contextExtractor(McpCaller::context)
				.build();
	}

	@Bean
	public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				// browser에서 직접 부를 일이 없으므로 허용 Origin을 두지 않는다.
				// Origin header가 있으면 403이다.
				.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
				.build();
		FilterRegistrationBean<McpTransportSecurityFilter> registration =
				new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// Spring Security filter chain보다 먼저 돌아서, 인증 전에 막는다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}

	/** 장바구니 저장소다. 만료 계산은 {@link Clock}으로 한다(테스트가 시간을 돌릴 수 있게). */
	@Bean
	public BasketStore basketStore() {
		return new BasketStore(Clock.systemUTC());
	}

	@Bean
	public ToolScopeRegistry toolScopeRegistry(ProductTools productTools, BasketTools basketTools) {
		return ToolScopeRegistry.scan(productTools, basketTools);
	}

	/**
	 * {@link ToolScopeFilter}를 MCP endpoint에만 적용한다.
	 * token 검증(Spring Security) 바로 뒤, 버전 검사 앞에서 돈다.
	 * 인증된 사용자의 scope를 보고, 모자라면 transport에 닿기 전에 {@code 403}으로 끝낸다.
	 */
	@Bean
	public FilterRegistrationBean<ToolScopeFilter> toolScopeFilter(ToolScopeRegistry registry,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		FilterRegistrationBean<ToolScopeFilter> registration =
				new FilterRegistrationBean<>(new ToolScopeFilter(registry, jsonMapper, ResourceMetadataUrl::of));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
		return registration;
	}

	/**
	 * {@link McpProtocolVersionFilter}를 MCP endpoint에만 적용한다.
	 * 포트처럼 practice마다 달라지는 값을 코드에 고정하지 않도록,
	 * endpoint 경로도 설정값에서 가져온다.
	 */
	@Bean
	public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		FilterRegistrationBean<McpProtocolVersionFilter> registration =
				new FilterRegistrationBean<>(new McpProtocolVersionFilter(jsonMapper));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// scope 검사 뒤에 돈다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
		return registration;
	}
}
