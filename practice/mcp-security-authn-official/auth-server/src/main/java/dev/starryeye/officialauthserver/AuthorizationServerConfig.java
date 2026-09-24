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
 * 인가 서버 설정. MCP 인가 명세가 요구하는 것을 켠다.
 *
 * <p>필터체인을 직접 정의하므로 Boot 의 기본 인가 서버 필터체인
 * ({@code @ConditionalOnDefaultWebSecurity})은 물러난다. 인가 요청 검증기와
 * 응답 핸들러를 갈아끼우려면 이 방법뿐이다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class AuthorizationServerConfig {

	/** RFC 9207 §3 — 인가 응답에 iss 를 싣는다고 알리는 메타데이터 필드. */
	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	/**
	 * RFC 8414 §2 — token/revocation/introspection 각 엔드포인트의
	 * {@code *_endpoint_auth_methods_supported} 가 {@code private_key_jwt} 또는
	 * {@code client_secret_jwt} 를 담고 있으면 짝이 되는 이 claim 들이 조건부 MUST 다.
	 * 최신 Spring Security(7.2.0-M1 포함)에는 이 claim 상수조차 없다.
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

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
			McpResourceProperties resources) throws Exception {
		IssuerIdentifyingAuthorizationResponseHandler responseHandler =
				new IssuerIdentifyingAuthorizationResponseHandler();
		ResourceIndicatorValidator resourceValidator = new ResourceIndicatorValidator(resources);

		http.oauth2AuthorizationServer(authorizationServer -> {
					http.securityMatcher(authorizationServer.getEndpointsMatcher());
					authorizationServer
							.authorizationEndpoint(authorization -> authorization
									// RFC 9207: 성공·오류 응답 모두에 iss 를 싣는다.
									.authorizationResponseHandler(responseHandler)
									.errorResponseHandler(responseHandler)
									// RFC 8707: 기본 검증(redirect_uri·scope) 뒤에 resource 검증을 잇는다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
											codeProvider.setAuthenticationValidator(
													new OAuth2AuthorizationCodeRequestAuthenticationValidator()
															.andThen(resourceValidator));
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
											// 공개 클라이언트(local-mcp-client) 는 client_secret 이 없어
											// none 으로만 인증한다. 토큰 엔드포인트에서 이 방식을 받는다고 광고한다.
											.tokenEndpointAuthenticationMethods(methods ->
													methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD))))
							// RFC 6749 §5.2: Authorization 헤더로 인증을 시도했다면 그 스킴에 맞는
							// WWW-Authenticate 를 붙인다. Spring 기본 핸들러는 TODO 로 남겨 두고 있다.
							.clientAuthentication(clientAuthentication -> clientAuthentication
									.errorResponseHandler(new ClientAuthenticationChallengeFailureHandler()))
							// oauth2Login 이 id_token 을 받으려면 OIDC 가 필요하다.
							// OidcProviderConfigurationEndpointFilter 는 token/revocation/introspection
							// 세 엔드포인트 모두에 clientAuthenticationMethods()(private_key_jwt·
							// client_secret_jwt 포함)를 그대로 광고하므로, AS 메타데이터와 동일하게
							// 세 claim 모두가 조건부 MUST 대상이다.
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
													methods.add(PUBLIC_CLIENT_AUTHENTICATION_METHOD)))));
				})
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				// 브라우저가 인가 엔드포인트에 로그인 없이 오면 로그인 화면으로 보낸다.
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
	 * Spring 인가 서버가 이 타입의 빈을 찾아 JWT 발급 직전에 호출한다.
	 */
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
