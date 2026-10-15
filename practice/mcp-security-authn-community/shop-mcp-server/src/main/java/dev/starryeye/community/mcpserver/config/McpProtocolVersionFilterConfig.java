package dev.starryeye.community.mcpserver.config;

import dev.starryeye.community.mcpserver.filter.McpProtocolVersionFilter;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link McpProtocolVersionFilter} 를 MCP 엔드포인트에만 건다(MCP 2025-11-25 전송 명세: 유효하지
 * 않거나 지원하지 않는 {@code MCP-Protocol-Version} 헤더에 400).
 *
 * <p>official practice 의 {@code McpTransportConfig} 는 전송 빈을 정의하지 않고 Spring AI 자동설정의
 * 것을 그대로 쓰며, 이 필터와 Origin/Host 를 검증하는 {@code McpTransportSecurityFilter} 두 개를 등록한다.
 * community 는 Origin/Host 검증을 모듈의 {@code OriginValidationFilter}({@link SecurityConfig} 에서 켠다)가
 * 담당하므로 {@code McpTransportSecurityFilter} 가 필요 없다 — 전송 빈은 마찬가지로 자동설정 것을 그대로
 * 두고, 이 필터 등록만 얹는다.
 * 포트처럼 practice 마다 달라지는 값을 하드코딩하지 않기 위해 엔드포인트 경로도 설정값에서
 * 그대로 가져온다.
 */
@Configuration
public class McpProtocolVersionFilterConfig {

    @Bean
    public FilterRegistrationBean<McpProtocolVersionFilter> mcpProtocolVersionFilter(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        FilterRegistrationBean<McpProtocolVersionFilter> registration =
                new FilterRegistrationBean<>(new McpProtocolVersionFilter(jsonMapper));
        registration.addUrlPatterns(properties.getMcpEndpoint());
        return registration;
    }
}
