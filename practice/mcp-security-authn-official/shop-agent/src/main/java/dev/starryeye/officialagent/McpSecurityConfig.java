package dev.starryeye.officialagent;

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
 * MCP 호출에 쓸 token을 준비하는 bean을 등록한다.
 *
 * <p>Authorization Server의 endpoint는 설정이 아니라 discovery에서 온다.
 * token request와 refresh 요청에는 RFC 8707 {@code resource}를 넣는다.
 * 그래서 발급되는 token은 이 MCP Server 전용이 된다.
 */
@Configuration
// OAuth2ClientProperties를 여기서 직접 켠다.
// Boot의 OAuth2ClientAutoConfiguration은 OAuth2ClientProperties를 ClientRegistrationRepository bean과
// 같은 조건부 설정 클래스에서 켠다.
// 그 클래스에는 @ConditionalOnMissingBean(ClientRegistrationRepository.class)가 붙어 있다.
// 아래에서 DiscoveredClientRegistrationRepository bean을 직접 만들면 그 클래스가 통째로 빠진다.
// 그러면 OAuth2ClientProperties도 함께 사라져 credentials(client-id/secret)를 읽을 곳이 없다.
@EnableConfigurationProperties({ McpAuthorizationProperties.class, OAuth2ClientProperties.class })
public class McpSecurityConfig {

    /** application.yml의 registration key와 같아야 한다. */
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
     * authorized client를 session이 아니라 서비스에 저장한다.
     * servlet 요청 없이 {@code Authentication}만으로 token을 꺼낼 수 있어야
     * reactor thread에서도 token을 붙인다.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /** login(code 교환) 때 token request를 보내는 client다. resource를 함께 보낸다. */
    @Bean
    public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    /** access token이 만료된 뒤 refresh 요청을 보내는 client다. 여기에도 resource가 필요하다. */
    @Bean
    public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    /**
     * 이 manager의 기본 구성에는 refresh가 없다.
     * refresh provider를 직접 넣어야 만료된 token을 새로 받는다.
     *
     * <p>테스트가 이 메서드를 직접 불러야 해서 static으로 둔다.
     * Spring은 static {@code @Bean} 메서드도 지원한다.
     * 세 번째 인자에는 {@code RestClientRefreshTokenTokenResponseClient} bean이 주입된다.
     * 이 구체 타입을 인자 타입에 대입할 수 있기 때문이다.
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

    /** 모든 MCP sync client에 인증을 전달하는 transport context provider를 넣는다. */
    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> mcpAuthenticationCustomizer() {
        return (name, spec) -> spec.transportContextProvider(new SecurityMcpTransportContextProvider());
    }

    /** 모든 Streamable HTTP transport에 token을 붙이는 customizer를 넣는다. */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTokenAttachingCustomizer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return (name, transport) -> transport.httpRequestCustomizer(
                new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager, REGISTRATION_ID));
    }
}
