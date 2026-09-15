package dev.starryeye.shopmcpserver;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link McpProtocolVersionFilter} 를 MCP 엔드포인트에만 건다(MCP 2025-11-25 전송 명세: 유효하지
 * 않거나 지원하지 않는 {@code MCP-Protocol-Version} 헤더에 400).
 *
 * <p>원본 practice 는 이 필터를 전송 빈({@code WebMvcStreamableServerTransportProvider})과 함께
 * 직접 정의한 {@code McpTransportConfig} 에서 등록한다. community 는 Origin/Host 검증을 이미
 * {@code OriginValidationFilter}(모듈, {@link SecurityConfig} 에서 켠다)가 담당하므로 전송 빈을
 * 다시 정의할 이유가 없다 — 자동설정이 만든 전송 빈은 그대로 두고, 필터 등록만 얹는다.
 * 포트처럼 practice 마다 달라지는 값을 하드코딩하지 않기 위해 엔드포인트 경로도 설정값에서
 * 그대로 가져온다.
 */
@Configuration
public class McpProtocolVersionFilterConfig {

    @Bean
    public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
            McpServerStreamableHttpProperties properties) {
        FilterRegistrationBean<McpProtocolVersionFilter> registration =
                new FilterRegistrationBean<>(new McpProtocolVersionFilter());
        registration.addUrlPatterns(properties.getMcpEndpoint());
        return registration;
    }
}
