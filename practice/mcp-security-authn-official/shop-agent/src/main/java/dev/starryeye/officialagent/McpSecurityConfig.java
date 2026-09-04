package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * community 버전에서 {@code mcp-client-security-spring-boot} 의 자동설정이
 * 통째로 해주던 배선이다. 공식 구성에서는 이 파일이 그 역할을 한다.
 */
@Configuration
public class McpSecurityConfig {

    /** application.yml 의 registration 키와 같아야 한다. */
    private static final String REGISTRATION_ID = "authserver";

    /**
     * 인가된 클라이언트를 <b>세션이 아니라 서비스</b>에 저장한다.
     *
     * <p>이 빈이 있으면 {@code oauth2Login} 이 로그인 성공 시 여기에 저장하고,
     * 나중에 {@code Authentication} 만으로 토큰을 꺼낼 수 있다.
     * 서블릿 요청·응답이 필요 없어지므로 리액터 스레드에서도 동작한다 —
     * 스트리밍 응답에서 토큰을 붙이려면 이 성질이 필요하다.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} 는
     * {@code HttpServletRequest} 를 요구하지 않는다. community 가 쓰던
     * {@code DefaultOAuth2AuthorizedClientManager} 와 다른 점이고,
     * 그 차이가 이 practice 를 internal API 없이 가능하게 하는 열쇠다.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
    }

    /** 모든 MCP 동기 클라이언트에 인증 전달용 컨텍스트 공급자를 꽂는다. */
    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> mcpAuthenticationCustomizer() {
        return (name, spec) -> spec.transportContextProvider(new SecurityMcpTransportContextProvider());
    }

    /** 모든 streamable-HTTP 전송에 토큰 부착 커스터마이저를 꽂는다. */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTokenAttachingCustomizer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return (name, transport) -> transport.httpRequestCustomizer(
                new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager, REGISTRATION_ID));
    }
}
