package dev.starryeye.shopagent;

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
 * 발견과 토큰 요청 배선.
 *
 * <p>토큰을 MCP 요청에 붙이는 일은 모듈 자동설정
 * ({@code HttpClientStreamableHttpTransportAutoConfiguration})이 계속 맡는다.
 * 그 자동설정은 등록이 정확히 하나일 때만 동작하므로 {@code registration} 설정은 남겨 둔다.
 * 다만 인가 서버의 주소는 설정이 아니라 발견에서 온다.
 *
 * <p>{@code OAuth2ClientProperties} 를 여기서 다시 켤 필요는 없다 — 모듈의
 * {@code McpOAuth2ClientAutoConfiguration} 이 이미 {@code @EnableConfigurationProperties}
 * 로 등록해 두고, 그 등록은 우리가 {@link ClientRegistrationRepository} 빈을 정의해도
 * 조건에 걸리지 않는다(물러나는 것은 그 자동설정이 만드는 {@code mcpClientRegistrationRepository}
 * 빈뿐이다).
 */
@Configuration
@EnableConfigurationProperties(McpAuthorizationProperties.class)
public class McpSecurityConfig {

    /** application.yml 의 registration 키와 같아야 한다. */
    static final String REGISTRATION_ID = "authserver";

    @Bean
    public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
        RestClient restClient = RestClient.create();
        // 학습 환경은 localhost HTTP 다. 운영에서는 기본값(HTTPS 만 허용)을 쓴다.
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
     * 모듈의 요청 커스터마이저는 서블릿 요청을 함께 넘기므로 이 매니저를 쓴다.
     * 기본 구성 대신 갱신 클라이언트를 지정해 resource 가 실리게 한다.
     *
     * <p>정적 헬퍼({@link #buildAuthorizedClientManager})와 이름을 같게 두면, 이 빈
     * 메서드가 구체 타입({@code RestClientRefreshTokenTokenResponseClient})을 받는
     * 시그니처가 더 구체적으로 뽑혀 스스로를 다시 호출하는 무한 재귀가 된다 —
     * 테스트({@code McpSecurityConfig.authorizedClientManager(...)} 를 외부 정적
     * 컨텍스트에서 호출)도 그 경우 "non-static method" 컴파일 오류가 난다. 이름을
     * 분리해 둔다.
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
