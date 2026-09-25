package dev.starryeye.memoryauthn.mcpserver;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * MCP endpoint 에만 거는 servlet filter 두 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host} 검증. Spring Security 보다 먼저 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version} 검증. 인증 뒤에 돈다.</li>
 * </ul>
 *
 * <p>Streamable HTTP transport 는 Spring AI 자동 구성 bean 을 그대로 쓴다.
 */
@Configuration
public class McpTransportConfig {

	@Bean
	public FilterRegistrationBean<McpTransportSecurityFilter> mcpTransportSecurityFilter(
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		DefaultServerTransportSecurityValidator validator = DefaultServerTransportSecurityValidator.builder()
				// browser 에서 직접 부를 일이 없으므로 허용 Origin 을 두지 않는다. Origin 이 실려 오면 403 이다.
				.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
				.build();
		FilterRegistrationBean<McpTransportSecurityFilter> registration =
				new FilterRegistrationBean<>(new McpTransportSecurityFilter(validator));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		// Spring Security filter chain 보다 먼저 돈다 — 인증 전에 막는다.
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}

	/**
	 * {@link McpProtocolVersionFilter} 를 MCP 엔드포인트에만 건다. 포트처럼 practice 마다
	 * 달라지는 값을 하드코딩하지 않기 위해 엔드포인트 경로도 설정값에서 그대로 가져온다.
	 */
	@Bean
	public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		FilterRegistrationBean<McpProtocolVersionFilter> registration =
				new FilterRegistrationBean<>(new McpProtocolVersionFilter(jsonMapper));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		return registration;
	}
}
