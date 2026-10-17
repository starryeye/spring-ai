package dev.starryeye.modern.mcpserver.config;

import dev.starryeye.modern.mcpserver.basket.BasketStore;
import dev.starryeye.modern.mcpserver.filter.McpTransportSecurityFilter;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * MCP endpoint에만 적용하는 servlet filter와, tool이 쓰는 저장소를 등록한다.
 *
 * <p>{@link McpTransportSecurityFilter}는 {@code Origin}·{@code Host}를 검증한다. Spring Security보다 먼저 돈다.
 * MCP 서버와 transport 자체는 {@link ModernMcpConfig}가 만든다.
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

	/** 장바구니 저장소다. 만료 계산은 {@link Clock}으로 한다(테스트가 시간을 돌릴 수 있게). */
	@Bean
	public BasketStore basketStore() {
		return new BasketStore(Clock.systemUTC());
	}
}
