package dev.starryeye.community.authserver.config;

import dev.starryeye.community.authserver.security.ClientAuthenticationChallengeFailureHandler;
import dev.starryeye.community.authserver.security.IssuerIdentifyingAuthorizationResponseHandler;
import dev.starryeye.community.authserver.security.PublicClientConsentService;
import dev.starryeye.community.authserver.security.PublicClientScopeValidator;
import dev.starryeye.community.authserver.security.ResourceAudienceTokenCustomizer;
import dev.starryeye.community.authserver.security.ResourceIndicatorValidator;
import dev.starryeye.community.authserver.security.SingleResourceTokenRequestConverter;

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
 * community module의 Authorization Server 자동 구성 위에 MCP authorization 명세의 요구를 더한다.
 *
 * <p>filter chain을 직접 만들지 않는다. 직접 만들면 {@code @ConditionalOnDefaultWebSecurity}
 * 때문에 module의 Authorization Server 설정이 통째로 빠진다. 대신 module이 열어 둔 확장점
 * ({@code Customizer<McpAuthorizationServerConfigurer>})으로 넣는다.
 *
 * <p>module의 {@code ResourceIdentifierAudienceTokenCustomizer}는 access token이면서 {@code openid}가
 * 승인됐을 때만 건너뛰고, ID token에는 {@code aud}를 {@code resource}로 덮어쓴다.
 * 이 practice의 agent는 login(openid)으로 token을 받는다.
 * 그래서 access token의 {@code aud}가 {@code resource}가 아니라 client_id로 남고, ID token의 {@code aud}는 틀어진다.
 * 아래 {@code ResourceAudienceTokenCustomizer}가 module의 것 뒤에 실행되어,
 * access token에 {@code aud=resource}를 넣고 ID token의 {@code aud}를 client_id로 되돌린다.
 *
 * <p>metadata와 OpenID Connect Discovery customizer는 이 클래스 안에서 한 번에 구성해야 한다.
 * {@code OAuth2AuthorizationServerMetadataEndpointConfigurer#authorizationServerMetadataCustomizer}와
 * {@code OidcProviderConfigurationEndpointConfigurer#providerConfigurationCustomizer}는
 * 둘 다 customizer를 list가 아니라 field 하나에 담아, 마지막 호출로 덮어쓴다(community module이
 * 아니라 spring-security-config 7.1.0의 두 클래스를 확인했다: 모두 {@code this.xxxCustomizer = xxx;}).
 * 그래서 다른 {@code Customizer<McpAuthorizationServerConfigurer>} bean을 하나 더 만들어 같은
 * 메서드를 다시 부르면, 먼저 등록한 {@link #ISS_PARAMETER_SUPPORTED} claim이 사라진다.
 * {@code OidcDiscoveryConfig}는 {@code oidc(Customizer.withDefaults())}로 OidcConfigurer를
 * 만들어 두기만 하고 providerConfigurationCustomizer를 건드리지 않으므로 이 클래스와 부딪히지 않는다.
 * {@code errorResponseHandler}(client 인증)는 이 프로젝트에서 이 클래스만 설정하므로 따로 불러도 안전하다.
 * 그래도 한 곳에 모아 두면 위 함정을 다시 만들 위험이 줄어든다.
 *
 * <p>같은 이유로 이 클래스의 {@code authorizationServerMetadataCustomizer} 호출은 module
 * 자신의 {@code McpAuthorizationServerConfigurer#init(HttpSecurity)}와도 부딪힌다(community module
 * {@code mcp-authorization-server} 0.1.14 확인: {@code cimd(true)}면 init()이 그 메서드로
 * {@code client_id_metadata_document_supported} claim을 먼저 넣는다. 그 뒤
 * {@code this.authServerCustomizer.forEach(...)}로 이 클래스가 등록한
 * customizer가 실행되어 같은 field를 덮어쓴다).
 * 이 프로젝트는 CIMD를 켜지 않으므로(properties로 켜는 경로도 없다) 지금은 문제가 없다.
 * 하지만 {@code McpAuthorizationServerConfigurer#cimd(true)}를 부르는 customizer bean이 더해지는 순간
 * 그 claim은 조용히 사라진다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class McpAuthorizationStandardConfig {

    /** authorization response에 iss를 넣는다고 알리는 metadata field다(RFC 9207 §3). */
    static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

    /**
     * token·revocation·introspection endpoint마다 짝이 되는 claim이다(RFC 8414 §2).
     * 그 endpoint의 {@code *_endpoint_auth_methods_supported}에 {@code private_key_jwt}나
     * {@code client_secret_jwt}가 있으면, 짝이 되는 claim은 조건부 MUST다.
     * 최신 Spring Security(community module이 쓰는 7.1.0 포함)에는 이 claim의 상수도 없다.
     */
    static final String TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "token_endpoint_auth_signing_alg_values_supported";
    static final String REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "revocation_endpoint_auth_signing_alg_values_supported";
    static final String INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
            "introspection_endpoint_auth_signing_alg_values_supported";

    /**
     * 위 세 claim에 넣을 값이다.
     * 임의로 고른 목록이 아니다. Spring Authorization Server의 {@code JwtClientAssertionDecoderFactory}가
     * client_secret_jwt·private_key_jwt client 인증에서 실제로 검증기를 만들 수 있는 알고리즘 전부다.
     * 대칭 key 방식인 {@code MacAlgorithm}(HS256/HS384/HS512) 3종과
     * 비대칭 key 방식인 {@code SignatureAlgorithm}(RS/ES/PS 256/384/512) 9종이다.
     * {@code JwsAlgorithms} 상수를 그대로 참조해 값이 어긋나지 않게 한다.
     * {@code none}은 RFC 8414 §2가 MUST NOT으로 정해서 넣지 않는다.
     */
    static final List<String> CLIENT_ASSERTION_SIGNING_ALGORITHMS = List.of(
            JwsAlgorithms.HS256, JwsAlgorithms.HS384, JwsAlgorithms.HS512,
            JwsAlgorithms.RS256, JwsAlgorithms.RS384, JwsAlgorithms.RS512,
            JwsAlgorithms.ES256, JwsAlgorithms.ES384, JwsAlgorithms.ES512,
            JwsAlgorithms.PS256, JwsAlgorithms.PS384, JwsAlgorithms.PS512);

    /**
     * {@code none}은 client 인증을 하지 않는 public client, 곧 비밀이 없는 client를 뜻한다(RFC 8414 §2).
     * {@code OAuth2AuthorizationServerMetadataEndpointFilter.clientAuthenticationMethods()}는 이 값을 넣지 않는다.
     * 이 메서드는 client_secret_basic·client_secret_post·client_secret_jwt·private_key_jwt·
     * tls_client_auth·self_signed_tls_client_auth 여섯 가지만 고정으로 넣는다.
     * 그래서 metadata customizer에서 {@code none}을 더한다.
     * filter가 여섯 값을 먼저 담아 builder를 넘기므로, {@code tokenEndpointAuthenticationMethods}(Consumer)는
     * 그 목록에 덧붙일 뿐 지우지 않는다.
     */
    static final String PUBLIC_CLIENT_AUTHENTICATION_METHOD = ClientAuthenticationMethod.NONE.getValue();

    // OidcDiscoveryConfig(순서 0)가 OidcConfigurer를 먼저 켠 뒤에 적용되도록 순서를 정한다.
    @Bean
    @Order(1)
    public Customizer<McpAuthorizationServerConfigurer> mcpAuthorizationStandardCustomizer(
            McpResourceProperties resources) {
        IssuerIdentifyingAuthorizationResponseHandler responseHandler =
                new IssuerIdentifyingAuthorizationResponseHandler();

        return configurer -> configurer
                // 기본 검증(redirect_uri·scope) 뒤에 RFC 8707 resource 검증과
                // public client의 scope 검증(OAuth 2.1 §7.3.1)을 차례로 잇는다.
                .authorizationCodeRequestValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                        .andThen(new ResourceIndicatorValidator(resources))
                        .andThen(new PublicClientScopeValidator()))
                .authorizationServer(authorizationServer -> authorizationServer
                        // RFC 8707: module customizer의 (String) cast보다 먼저 resource 개수를 본다.
                        .tokenEndpoint(token -> token.accessTokenRequestConverter(new SingleResourceTokenRequestConverter()))
                        // RFC 9207: 성공 응답과 오류 응답 모두에 iss를 넣는다.
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
                                        // public client(local-mcp-client)는 client_secret이 없어 none으로만 인증한다.
                                        // token endpoint가 이 방식을 받는다고 알린다.
                                        .tokenEndpointAuthenticationMethods(methods ->
                                                methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD))))
                        // RFC 6749 §5.2: client가 Authorization header로 인증을 시도했다면 그 scheme에 맞는
                        // WWW-Authenticate를 붙인다. Spring의 기본 handler는 이 부분을 TODO로 남겨 두었다.
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .errorResponseHandler(new ClientAuthenticationChallengeFailureHandler()))
                        // OidcDiscoveryConfig가 oidc()를 켜 둔다. providerConfigurationCustomizer는
                        // OidcProviderConfigurationEndpointConfigurer의 field 하나에 담기므로(spring-security-config
                        // 7.1.0 확인) token/revocation/introspection 세 claim을 모두 이 한 호출에 모아야 한다.
                        // OidcProviderConfigurationEndpointFilter도 Authorization Server Metadata와 마찬가지로 세
                        // endpoint 모두에 clientAuthenticationMethods()(private_key_jwt·client_secret_jwt 포함)를
                        // 그대로 알리므로, 세 claim 모두가 대상이다.
                        .oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
                                .providerConfigurationCustomizer(builder -> builder
                                        .claim(ISS_PARAMETER_SUPPORTED, true)
                                        .claim(TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        .claim(INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED,
                                                CLIENT_ASSERTION_SIGNING_ALGORITHMS)
                                        // OpenID Connect Discovery에도 Authorization Server Metadata와
                                        // 같은 이유로 none을 더한다.
                                        .tokenEndpointAuthenticationMethods(methods ->
                                                methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD))))));
    }

    /** module이 이 타입의 bean을 모아 기본 customizer 뒤에 실행한다. */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceTokenCustomizer(McpResourceProperties resources) {
        return new ResourceAudienceTokenCustomizer(resources);
    }

    /**
     * {@code OAuth2AuthorizationServerConfigurer}는 이 타입의 bean이 있으면 그것을 쓴다.
     * 없으면 {@code InMemoryOAuth2AuthorizationConsentService}를 안에서 직접 만든다
     * ({@code OAuth2ConfigurerUtils.getAuthorizationConsentService}).
     * public client의 consent를 기록하지 않도록, 기본 저장소를 {@link PublicClientConsentService}로 감싸
     * bean으로 등록한다.
     */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            RegisteredClientRepository registeredClientRepository) {
        return new PublicClientConsentService(new InMemoryOAuth2AuthorizationConsentService(),
                registeredClientRepository);
    }
}
