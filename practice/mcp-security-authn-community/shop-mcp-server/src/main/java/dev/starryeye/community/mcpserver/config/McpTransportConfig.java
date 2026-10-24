package dev.starryeye.community.mcpserver.config;

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Streamable HTTP transport의 router bean을 직접 만들어, 오류 응답에서 Java 예외 정보를 뺀다.
 *
 * <p>community module은 filter chain({@link SecurityConfig})만 설정하고 transport와 router는 만들지 않는다.
 * 그래서 transport bean은 Spring AI 자동 구성의 것을 그대로 쓰고, 여기서는 그 transport의 router에 filter만 더한다.
 */
@Configuration
public class McpTransportConfig {

    /**
     * Spring AI 자동 구성의 router bean을 대신한다.
     * 자동 구성은 이 이름({@code webMvcStreamableServerRouterFunction})의 bean이 없을 때만 router bean을 만든다.
     * 그래서 이 bean 이름은 바꾸면 안 된다.
     * 바꾸면 filter 없는 자동 구성 router도 함께 등록되고, 어느 router가 답할지는 bean 순서에 달린다.
     * transport의 router는 그대로 쓰고, 오류 응답에서 예외 객체를 빼는 {@link TransportErrorResponseFilter}만 더한다.
     */
    @Bean
    public RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
            WebMvcStreamableServerTransportProvider transport,
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
        return transport.getRouterFunction().filter(new TransportErrorResponseFilter(jsonMapper));
    }
}
