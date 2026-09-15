package dev.starryeye.memoryauthn.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * MCP 호출에 쓸 토큰을 마련하는 배선.
 *
 * <p>인가 서버의 위치는 설정이 아니라 발견에서 온다. 토큰 요청과 갱신 요청에는
 * RFC 8707 {@code resource} 를 실어, 발급되는 토큰이 이 MCP 서버 전용이 되게 한다.
 */
@Configuration
// OAuth2ClientProperties 를 직접 켠다: Boot 의 OAuth2ClientAutoConfiguration 은 이 프로퍼티를
// ClientRegistrationRepository 빈과 같은 조건부 설정 클래스에 묶어 두는데, 그 클래스는
// @ConditionalOnMissingBean(ClientRegistrationRepository.class) 로 우리가 아래서 직접 만드는
// DiscoveredClientRegistrationRepository 빈이 있으면 통째로 비활성화된다 — OAuth2ClientProperties
// 도 같이 사라져 자격증명(client-id/secret) 을 읽어올 곳이 없어진다. 그래서 여기서 따로 켠다.
@EnableConfigurationProperties({ McpAuthorizationProperties.class, OAuth2ClientProperties.class })
public class McpSecurityConfig {

    /** application.yml 의 registration 키와 같아야 한다. */
    static final String REGISTRATION_ID = "authserver";

    @Bean
    public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
        return new McpAuthorizationDiscovery(RestClient.create());
    }

    @Bean
    public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
        return new DiscoveredClientRegistrationRepository(discovery, properties, clientProperties);
    }

    /**
     * 인가된 클라이언트를 세션이 아니라 서비스에 저장한다. 서블릿 요청 없이
     * {@code Authentication} 만으로 토큰을 꺼낼 수 있어야 리액터 스레드에서도 토큰을 붙인다.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /** 로그인(코드 교환) 때 쓰는 토큰 요청 클라이언트. resource 를 함께 보낸다. */
    @Bean
    public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    /** 액세스 토큰이 만료된 뒤 쓰는 갱신 클라이언트. 여기에도 resource 가 필요하다. */
    @Bean
    public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    /**
     * 이 매니저의 기본 구성에는 갱신이 들어 있지 않다. refresh provider 를 직접 넣어야
     * 만료된 토큰이 갱신된다.
     *
     * <p>테스트가 이 메서드를 직접 호출해야 해서 static 으로 둔다. Spring 은 static
     * {@code @Bean} 메서드도 정상적으로 지원하며, 세 번째 인자는 구체 타입인
     * {@code RestClientRefreshTokenTokenResponseClient} 빈이 타입 할당 가능성으로
     * 주입된다.
     */
    @Bean
    static AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository,
                authorizedClientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refreshToken -> refreshToken.accessTokenResponseClient(refreshTokenTokenResponseClient))
                .build());
        return manager;
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
