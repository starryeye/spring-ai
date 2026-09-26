package dev.starryeye.officialauthserver;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.util.List;

/**
 * Authorization Server 설정이다. MCP authorization 명세가 요구하는 기능을 켠다.
 *
 * <p>filter chain을 직접 정의하므로 Boot의 기본 Authorization Server filter chain
 * ({@code @ConditionalOnDefaultWebSecurity})은 빠진다.
 * authorization request 검증기와 응답 handler를 바꾸려면 이 방법밖에 없다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class AuthorizationServerConfig {

	/** authorization response에 iss를 넣는다고 알리는 metadata field다(RFC 9207 §3). */
	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	/**
	 * token·revocation·introspection endpoint마다 짝이 되는 claim이다(RFC 8414 §2).
	 * 그 endpoint의 {@code *_endpoint_auth_methods_supported}에 {@code private_key_jwt}나
	 * {@code client_secret_jwt}가 있으면, 짝이 되는 claim은 조건부 MUST다.
	 * 최신 Spring Security(7.2.0-M1 포함)에는 이 claim의 상수도 없다.
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

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
			McpResourceProperties resources) throws Exception {
		IssuerIdentifyingAuthorizationResponseHandler responseHandler =
				new IssuerIdentifyingAuthorizationResponseHandler();
		ResourceIndicatorValidator resourceValidator = new ResourceIndicatorValidator(resources);
		PublicClientScopeValidator publicClientScopeValidator = new PublicClientScopeValidator();

		http.oauth2AuthorizationServer(authorizationServer -> {
					http.securityMatcher(authorizationServer.getEndpointsMatcher());
					authorizationServer
							.authorizationEndpoint(authorization -> authorization
									// RFC 9207: 성공 응답과 오류 응답 모두에 iss를 넣는다.
									.authorizationResponseHandler(responseHandler)
									.errorResponseHandler(responseHandler)
									// 기본 검증(redirect_uri·scope) 뒤에 RFC 8707 resource 검증과
									// public client의 scope 검증(OAuth 2.1 §7.3.1)을 차례로 잇는다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
											codeProvider.setAuthenticationValidator(
													new OAuth2AuthorizationCodeRequestAuthenticationValidator()
															.andThen(resourceValidator)
															.andThen(publicClientScopeValidator));
										}
									})))
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
							// oauth2Login이 id_token을 받으려면 OIDC가 필요하다.
							// OidcProviderConfigurationEndpointFilter는 token·revocation·introspection
							// 세 endpoint 모두에 clientAuthenticationMethods()(private_key_jwt·
							// client_secret_jwt 포함)를 그대로 알린다. 그래서 Authorization Server
							// Metadata와 마찬가지로 세 claim 모두 조건부 MUST 대상이다.
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
													methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD)))));
				})
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				// browser가 login 없이 authorization endpoint에 오면 login 화면으로 보낸다.
				.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint("/login"),
						new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				.formLogin(Customizer.withDefaults())
				.build();
	}

	/**
	 * Spring Authorization Server가 이 타입의 bean을 찾아 JWT를 발급하기 직전에 부른다.
	 */
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
