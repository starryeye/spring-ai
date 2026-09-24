package dev.starryeye.authserver;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

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
 *
 * <p>메타데이터·OIDC 디스커버리 커스터마이저는 이 클래스 안에서 한 번에 구성해야 한다.
 * {@code OAuth2AuthorizationServerMetadataEndpointConfigurer#authorizationServerMetadataCustomizer}
 * 와 {@code OidcProviderConfigurationEndpointConfigurer#providerConfigurationCustomizer} 는
 * 둘 다 커스터마이저를 리스트가 아니라 필드 하나에 담아 마지막 호출로 덮어쓴다(module 소스
 * 확인: spring-security-config 7.1.0 소스의 두 클래스 모두 {@code this.xxxCustomizer = xxx;}).
 * 그래서 다른 {@code Customizer<McpAuthorizationServerConfigurer>} 빈을 하나 더 만들어 같은
 * 메서드를 다시 호출하면, 먼저 등록된 {@link #ISS_PARAMETER_SUPPORTED} claim 이 사라진다.
 * {@code OidcDiscoveryConfig} 는 {@code oidc(Customizer.withDefaults())} 로 OidcConfigurer 를
 * 존재하게만 만들 뿐 providerConfigurationCustomizer 를 건드리지 않으므로 이 클래스와 부딪히지
 * 않는다. 반면 {@code errorResponseHandler}(클라이언트 인증)는 이 프로젝트에서 이 클래스만
 * 설정하므로 별도 호출로 두어도 안전하지만, 한 곳에 모아 두는 편이 위 함정을 다시 만들
 * 위험을 줄인다.
 *
 * <p>같은 이유로 이 클래스의 {@code authorizationServerMetadataCustomizer} 호출은 모듈
 * 자신의 {@code McpAuthorizationServerConfigurer#init(HttpSecurity)} 와도 부딪힌다(module 소스
 * 확인: {@code cimd(true)} 면 init() 이 그 메서드로 {@code client_id_metadata_document_supported}
 * claim 을 먼저 심고, 그 뒤 {@code this.authServerCustomizer.forEach(...)} 로 이 클래스가 등록한
 * 커스터마이저가 실행되어 같은 필드를 덮어쓴다). 이 프로젝트는 CIMD 를 켜지 않으므로(properties
 * 로 켜는 경로도 없다) 지금은 무해하지만, {@code McpAuthorizationServerConfigurer#cimd(true)} 를
 * 호출하는 커스터마이저 빈이 추가되는 순간 그 claim 은 조용히 사라진다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class McpAuthorizationStandardConfig {

    /** RFC 9207 §3 — 인가 응답에 iss 를 싣는다고 알리는 메타데이터 필드. */
    static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

    /**
     * RFC 8414 §2 — token/revocation/introspection 각 엔드포인트의
     * {@code *_endpoint_auth_methods_supported} 가 {@code private_key_jwt} 또는
     * {@code client_secret_jwt} 를 담고 있으면 짝이 되는 이 claim 들이 조건부 MUST 다.
     * 최신 Spring Security(community 모듈이 물고 있는 7.1.0 포함)에는 이 claim 상수조차 없다.
     */
    static final String TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "token_endpoint_auth_signing_alg_values_supported";
    static final String REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "revocation_endpoint_auth_signing_alg_values_supported";
    static final String INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "introspection_endpoint_auth_signing_alg_values_supported";

    /**
     * 위 세 claim 에 실을 값. 지어낸 목록이 아니라, Spring 인가 서버의
     * {@code JwtClientAssertionDecoderFactory} 가 client_secret_jwt/private_key_jwt
     * 클라이언트 인증에서 실제로 검증기를 만들어내는 알고리즘 전부다 — 대칭키
     * {@code MacAlgorithm}(HS256/HS384/HS512) 과 비대칭 {@code SignatureAlgorithm}
     * (RS/ES/PS 256/384/512) 9종. {@code JwsAlgorithms} 상수를 그대로 참조해 값이
     * 어긋나지 않게 한다. {@code none} 은 RFC 8414 §2 상 MUST NOT 이라 넣지 않는다.
     */
    static final List<String> CLIENT_ASSERTION_SIGNING_ALGORITHMS = List.of(
            JwsAlgorithms.HS256, JwsAlgorithms.HS384, JwsAlgorithms.HS512,
            JwsAlgorithms.RS256, JwsAlgorithms.RS384, JwsAlgorithms.RS512,
            JwsAlgorithms.ES256, JwsAlgorithms.ES384, JwsAlgorithms.ES512,
            JwsAlgorithms.PS256, JwsAlgorithms.PS384, JwsAlgorithms.PS512);

    /**
     * RFC 8414 §2 — {@code none} 은 클라이언트 인증을 하지 않는(비밀이 없는) 공개 클라이언트를
     * 뜻한다. {@code OAuth2AuthorizationServerMetadataEndpointFilter.clientAuthenticationMethods()}
     * 는 이 값을 절대 광고하지 않으므로(client_secret_basic·client_secret_post·client_secret_jwt·
     * private_key_jwt·tls_client_auth·self_signed_tls_client_auth 여섯 가지만 고정으로 넣는다)
     * 커스터마이저에서 더한다. 필터가 먼저 그 여섯 값을 담아 빌더를 넘기므로,
     * {@code tokenEndpointAuthenticationMethods}(Consumer) 는 그 목록에 덧붙일 뿐 지우지 않는다.
     */
    static final String PUBLIC_CLIENT_AUTHENTICATION_METHOD = ClientAuthenticationMethod.NONE.getValue();

    // OidcDiscoveryConfig(순서 0) 가 OidcConfigurer 를 먼저 켠 뒤에 적용되도록 순서를 명시한다.
    @Bean
    @Order(1)
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
                                .authorizationServerMetadataCustomizer(builder -> builder
                                        .claim(ISS_PARAMETER_SUPPORTED, true)
                                        .claim(TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        // 공개 클라이언트(local-mcp-client) 는 client_secret 이 없어
                                        // none 으로만 인증한다. 토큰 엔드포인트에서 이 방식을 받는다고 광고한다.
                                        .tokenEndpointAuthenticationMethods(methods ->
                                                methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD))))
                        // RFC 6749 §5.2: Authorization 헤더로 인증을 시도했다면 그 스킴에 맞는
                        // WWW-Authenticate 를 붙인다. Spring 기본 핸들러는 TODO 로 남겨 두고 있다.
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .errorResponseHandler(new ClientAuthenticationChallengeFailureHandler()))
                        // OidcDiscoveryConfig 가 oidc() 를 켜 둔다. providerConfigurationCustomizer 는
                        // OidcProviderConfigurationEndpointConfigurer 필드 하나에 담기므로(module 소스
                        // 확인) token/revocation/introspection 세 claim 모두 이 한 호출에 모아야 한다 —
                        // OidcProviderConfigurationEndpointFilter 도 AS 메타데이터와 동일하게 세
                        // 엔드포인트 모두에 clientAuthenticationMethods()(private_key_jwt·
                        // client_secret_jwt 포함)를 그대로 광고하기 때문에 세 claim 모두가 대상이다.
                        .oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
                                .providerConfigurationCustomizer(builder -> builder
                                        .claim(ISS_PARAMETER_SUPPORTED, true)
                                        .claim(TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        // OIDC 디스커버리도 AS 메타데이터와 같은 이유로 none 을 더한다.
                                        .tokenEndpointAuthenticationMethods(methods ->
                                                methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD))))));
    }

    /** 모듈이 이 타입의 빈을 모아 기본 커스터마이저 뒤에 실행한다. */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceTokenCustomizer(McpResourceProperties resources) {
        return new ResourceAudienceTokenCustomizer(resources);
    }

    /**
     * {@code OAuth2AuthorizationServerConfigurer} 는 이 타입의 빈이 있으면 그것을 쓰고, 없으면
     * {@code InMemoryOAuth2AuthorizationConsentService} 를 내부적으로 만든다
     * ({@code OAuth2ConfigurerUtils.getAuthorizationConsentService}). 공개 클라이언트의 동의를
     * 기록하지 않도록 기본 저장소를 {@link PublicClientConsentService} 로 감싸 빈으로 올린다.
     */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            RegisteredClientRepository registeredClientRepository) {
        return new PublicClientConsentService(new InMemoryOAuth2AuthorizationConsentService(),
                registeredClientRepository);
    }
}
