package dev.starryeye.authserver;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * community 모듈의 인가 서버 자동설정 위에 MCP 인가 명세를 얹는다.
 *
 * <p>필터체인을 직접 만들지 않는다. 직접 만들면 {@code @ConditionalOnDefaultWebSecurity}
 * 때문에 모듈의 인가 서버 설정이 통째로 물러난다. 대신 모듈이 열어 둔 확장점
 * ({@code Customizer<McpAuthorizationServerConfigurer>})으로 넣는다.
 *
 * <p>모듈도 {@code resource} 를 {@code aud} 로 넣는 커스터마이저
 * ({@code ResourceIdentifierAudienceTokenCustomizer})를 갖고 있지만 {@code openid} 스코프가
 * 있으면 건너뛴다. 이 practice 의 에이전트는 로그인(openid)으로 토큰을 받으므로 그 경로가
 * 비어 버린다. 아래 토큰 커스터마이저가 모듈 것 뒤에 실행되어 스코프와 무관하게
 * {@code aud} 를 채운다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class McpAuthorizationStandardConfig {

    /** RFC 9207 §3 — 인가 응답에 iss 를 싣는다고 알리는 메타데이터 필드. */
    static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

    @Bean
    public Customizer<McpAuthorizationServerConfigurer> mcpAuthorizationStandardCustomizer(
            McpResourceProperties resources) {
        IssuerIdentifyingAuthorizationResponseHandler responseHandler =
                new IssuerIdentifyingAuthorizationResponseHandler();

        return configurer -> configurer
                // RFC 8707: 기본 검증(redirect_uri·scope) 뒤에 resource 검증을 잇는다.
                .authorizationCodeRequestValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                        .andThen(new ResourceIndicatorValidator(resources)))
                .authorizationServer(authorizationServer -> authorizationServer
                        // RFC 9207: 성공·오류 응답 모두에 iss 를 싣는다.
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationResponseHandler(responseHandler)
                                .errorResponseHandler(responseHandler))
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(
                                        builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))
                        .oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
                                .providerConfigurationCustomizer(
                                        builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))));
    }

    /** 모듈이 이 타입의 빈을 모아 기본 커스터마이저 뒤에 실행한다. */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceTokenCustomizer(McpResourceProperties resources) {
        return new ResourceAudienceTokenCustomizer(resources);
    }
}
