package dev.starryeye.community.agent.config;

import dev.starryeye.community.agent.discovery.DiscoveredClientRegistrationRepository;
import dev.starryeye.community.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.community.agent.security.ResourceIndicators;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.client.RestClient;

/**
 * discovery와 token request에 필요한 bean을 등록한다.
 *
 * <p>token을 MCP 요청에 붙이는 일은 module 자동 구성
 * ({@code HttpClientStreamableHttpTransportAutoConfiguration})이 그대로 맡는다.
 * 그 자동 구성은 client 등록이 정확히 하나일 때만 동작하므로 {@code registration} 설정은 남겨 둔다.
 * 다만 Authorization Server의 endpoint는 설정이 아니라 discovery에서 온다.
 *
 * <p>{@code OAuth2ClientProperties}를 여기서 다시 켤 필요는 없다.
 * module의 {@code McpOAuth2ClientAutoConfiguration}이 이미 {@code @EnableConfigurationProperties}로
 * 등록해 두었고, 그 등록은 이 practice가 {@link ClientRegistrationRepository} bean을 정의해도
 * 조건에 걸리지 않는다(빠지는 것은 그 자동 구성이 만드는 {@code mcpClientRegistrationRepository}
 * bean뿐이다).
 */
@Configuration
@EnableConfigurationProperties(McpAuthorizationProperties.class)
public class McpSecurityConfig {

    /** application.yml의 registration key와 같아야 한다. */
    public static final String REGISTRATION_ID = "authserver";

    /**
     * discovery 요청은 login하지 않은 요청에서도 나간다.
     * 그래서 연결과 응답에 시간 제한을 둔 request factory를 쓴다.
     * Boot가 만든 {@code RestClient.Builder}를 받아 쓰므로 message converter 같은 Boot 설정은 그대로 온다.
     */
    @Bean
    public McpAuthorizationDiscovery mcpAuthorizationDiscovery(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .requestFactory(McpAuthorizationDiscovery.requestFactory(McpAuthorizationDiscovery.CONNECT_TIMEOUT,
                        McpAuthorizationDiscovery.READ_TIMEOUT))
                .build();
        // 학습 환경은 localhost HTTP다.
        // 운영에서는 기본값(HTTPS만 허용)을 쓴다.
        return new McpAuthorizationDiscovery(
                new McpMetadataDiscoveryService(restClient, new DefaultUrlValidator(true)), restClient);
    }

    @Bean
    public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
            McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
        return new DiscoveredClientRegistrationRepository(discovery, properties, clientProperties);
    }

    @Bean
    public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    @Bean
    public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
            DiscoveredClientRegistrationRepository registrations) {
        var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
        tokenResponseClient.addParametersConverter(
                ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
        return tokenResponseClient;
    }

    /**
     * module의 요청 customizer는 servlet 요청을 함께 넘기므로 이 manager를 쓴다.
     * 기본 구성 대신 refresh client를 지정해 resource가 들어가게 한다.
     *
     * <p>static helper({@link #buildAuthorizedClientManager})와 이름을 같게 두면, 이 bean
     * 메서드가 구체 타입({@code RestClientRefreshTokenTokenResponseClient})을 받는
     * 시그니처가 더 구체적인 것으로 골라져 자기 자신을 다시 부르는 무한 재귀가 된다.
     * 그 경우 테스트({@code McpSecurityConfig.authorizedClientManager(...)}를 바깥의 static context에서 부른다)도
     * "non-static method" 컴파일 오류가 난다.
     * 그래서 이름을 나눠 둔다.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient) {
        return buildAuthorizedClientManager(registrations, authorizedClients, refreshTokenTokenResponseClient);
    }

    static DefaultOAuth2AuthorizedClientManager buildAuthorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(refreshToken -> refreshToken.accessTokenResponseClient(refreshTokenTokenResponseClient))
                .build());
        return manager;
    }
}
