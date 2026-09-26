package dev.starryeye.officialmcpserver;

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
 * MCP endpoint에만 적용하는 servlet filter 두 개를 등록한다.
 *
 * <ul>
 *   <li>{@link McpTransportSecurityFilter} — {@code Origin}·{@code Host}를 검증한다. Spring Security보다 먼저 돈다.</li>
 *   <li>{@link McpProtocolVersionFilter} — {@code MCP-Protocol-Version}을 검증한다. 인증 뒤에 돈다.</li>
 * </ul>
 *
 * <p>Streamable HTTP transport는 Spring AI 자동 구성 bean을 그대로 쓴다.
 */
@Configuration
public class McpTransportConfig {

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
		return registration;
	}
}
