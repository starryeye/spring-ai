package dev.starryeye.community.mcpserver.config;

import dev.starryeye.community.mcpserver.filter.McpProtocolVersionFilter;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link McpProtocolVersionFilter}를 MCP endpoint에만 적용한다(MCP 2025-11-25 Transports 명세:
 * 잘못됐거나 지원하지 않는 {@code MCP-Protocol-Version} header에는 400).
 *
 * <p>official practice의 {@code McpTransportConfig}는 이 filter와 Origin/Host를 검증하는
 * {@code McpTransportSecurityFilter} 두 개를 등록한다.
 * community는 module의 {@code OriginValidationFilter}({@link SecurityConfig}에서 켠다)가 Origin/Host 검증을
 * 맡으므로 {@code McpTransportSecurityFilter}가 필요 없다.
 * 그래서 이 설정은 filter 등록만 한다.
 * transport의 router는 {@link McpTransportConfig}가 맡는다.
 * 포트처럼 practice마다 달라지는 값을 코드에 고정하지 않도록, endpoint 경로도 설정값에서 가져온다.
 */
@Configuration
public class McpProtocolVersionFilterConfig {

    @Bean
    public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        FilterRegistrationBean<McpProtocolVersionFilter> registration =
                new FilterRegistrationBean<>(new McpProtocolVersionFilter(jsonMapper));
        registration.addUrlPatterns(properties.getMcpEndpoint());
        // 순서는 일부러 정하지 않는다.
        // 기본값(Ordered.LOWEST_PRECEDENCE)이라 Spring Security filter chain(SecurityFilterProperties.DEFAULT_FILTER_ORDER) 뒤에 돈다.
        // 그래서 token이 없는 요청은 버전을 보기 전에 401을 받는다.
        return registration;
    }
}
