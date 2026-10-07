package dev.starryeye.cimd.authserver.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.starryeye.cimd.authserver.cimd.CimdJwtClientAssertionDecoderFactory;
import dev.starryeye.cimd.authserver.cimd.ClientIdMetadataDocumentProperties;
import dev.starryeye.cimd.authserver.cimd.ClientIdMetadataDocumentRegisteredClientRepository;
import dev.starryeye.cimd.authserver.cimd.ClientIdUrlValidator;
import dev.starryeye.cimd.authserver.cimd.ClientMetadataHttp;
import dev.starryeye.cimd.authserver.cimd.HostResolver;
import dev.starryeye.cimd.authserver.cimd.HttpsClientMetadataFetcher;
import dev.starryeye.cimd.authserver.security.ClientAuthenticationChallengeFailureHandler;
import dev.starryeye.cimd.authserver.security.ConsentableScopeValidator;
import dev.starryeye.cimd.authserver.security.IssuerIdentifyingAuthorizationResponseHandler;
import dev.starryeye.cimd.authserver.security.PublicClientConsentService;
import dev.starryeye.cimd.authserver.security.PublicClientRefreshTokenAuthenticationConverter;
import dev.starryeye.cimd.authserver.security.PublicClientRefreshTokenAuthenticationProvider;
import dev.starryeye.cimd.authserver.security.PublicClientRefreshTokenGenerator;
import dev.starryeye.cimd.authserver.security.ResourceAudienceTokenCustomizer;
import dev.starryeye.cimd.authserver.security.ResourceIndicatorValidator;
import dev.starryeye.cimd.authserver.web.ConsentController;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import javax.net.ssl.SSLContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Authorization Server 설정이다. MCP authorization 명세가 요구하는 기능과 CIMD를 켠다.
 *
 * <p>미리 등록한 client는 없다. 모든 client는 자기가 올린 문서의 주소를 client_id로 쓴다.
 * filter chain을 직접 정의하므로 Boot의 기본 Authorization Server filter chain
 * ({@code @ConditionalOnDefaultWebSecurity})은 빠진다.
 */
@Configuration
@EnableConfigurationProperties({ McpResourceProperties.class, ClientIdMetadataDocumentProperties.class })
public class AuthorizationServerConfig {

	/** authorization response에 iss를 넣는다고 알리는 metadata field다(RFC 9207 §3). */
	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	/** CIMD 문서 주소를 client_id로 받는다고 알리는 metadata field다(CIMD draft §5). */
	static final String CLIENT_ID_METADATA_DOCUMENT_SUPPORTED = "client_id_metadata_document_supported";

	/**
	 * token·revocation·introspection endpoint마다 짝이 되는 claim이다(RFC 8414 §2).
	 * 그 endpoint가 {@code private_key_jwt}를 받는다고 알리면 함께 알린다.
	 * Spring Security에는 이 claim의 상수가 없다.
	 */
	static final String TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
			"token_endpoint_auth_signing_alg_values_supported";
	static final String REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
			"revocation_endpoint_auth_signing_alg_values_supported";
	static final String INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED =
			"introspection_endpoint_auth_signing_alg_values_supported";

	/**
	 * CIMD client가 문서에 선언할 수 있는 인증 방식이다.
	 * 문서는 누구나 읽으므로 비밀을 나누는 방식(client_secret_*)은 쓸 수 없다.
	 * 남는 것은 key로 서명하는 {@code private_key_jwt}와 인증하지 않는 {@code none}이다.
	 * Claude 앱은 이 목록에 {@code none}이 있어야 CIMD를 쓴다.
	 */
	static final List<String> TOKEN_ENDPOINT_AUTHENTICATION_METHODS = List.of(
			ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue(), ClientAuthenticationMethod.NONE.getValue());

	/** revocation·introspection endpoint는 client 인증이 있어야 쓸 수 있다. public client는 쓰지 못한다. */
	static final List<String> AUTHENTICATED_ENDPOINT_METHODS = List.of(ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue());

	/** {@code CimdJwtClientAssertionDecoderFactory}가 검증하는 알고리즘이다. 문서의 key는 RSA다. */
	static final List<String> CLIENT_ASSERTION_SIGNING_ALGORITHMS = List.of(JwsAlgorithms.RS256);

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, McpResourceProperties resources,
			RegisteredClientRepository clients, CimdJwtClientAssertionDecoderFactory assertionDecoderFactory)
			throws Exception {
		IssuerIdentifyingAuthorizationResponseHandler responseHandler =
				new IssuerIdentifyingAuthorizationResponseHandler();
		ResourceIndicatorValidator resourceValidator = new ResourceIndicatorValidator(resources);
		ConsentableScopeValidator consentableScopeValidator = new ConsentableScopeValidator();

		http.oauth2AuthorizationServer(authorizationServer -> {
					http.securityMatcher(authorizationServer.getEndpointsMatcher());
					authorizationServer
							.authorizationEndpoint(authorization -> authorization
									// 문서 host와 redirect host를 보여 주는 consent 화면이다.
									.consentPage(ConsentController.PATH)
									// RFC 9207: 성공 응답과 오류 응답 모두에 iss를 넣는다.
									.authorizationResponseHandler(responseHandler)
									.errorResponseHandler(responseHandler)
									// 기본 검증(redirect_uri·scope) 뒤에 RFC 8707 resource 검증과
									// consent할 scope가 있는지 보는 검증을 차례로 잇는다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
											codeProvider.setAuthenticationValidator(
													new OAuth2AuthorizationCodeRequestAuthenticationValidator()
															.andThen(resourceValidator)
															.andThen(consentableScopeValidator));
										}
									})))
							.clientAuthentication(clientAuthentication -> clientAuthentication
									// public client의 refresh 요청을 인증한다. Spring 기본은 code_verifier가 있는 요청만 받는다.
									.authenticationConverter(new PublicClientRefreshTokenAuthenticationConverter())
									.authenticationProvider(new PublicClientRefreshTokenAuthenticationProvider(clients))
									// private_key_jwt의 key는 문서의 jwks_uri에서 가져온다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof JwtClientAssertionAuthenticationProvider assertionProvider) {
											assertionProvider.setJwtDecoderFactory(assertionDecoderFactory);
										}
									}))
									// RFC 6749 §5.2: client가 Authorization header로 인증을 시도했다면 그 scheme에 맞는
									// WWW-Authenticate를 붙인다.
									.errorResponseHandler(new ClientAuthenticationChallengeFailureHandler()))
							.authorizationServerMetadataEndpoint(metadata -> metadata
									.authorizationServerMetadataCustomizer(builder -> builder.claims(AuthorizationServerConfig::advertise)))
							// oauth2Login이 id_token을 받으려면 OIDC가 필요하다.
							// OpenID Connect Discovery 문서에도 같은 값을 알린다.
							.oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
									.providerConfigurationCustomizer(builder -> builder.claims(AuthorizationServerConfig::advertise))));
				})
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				// browser가 login 없이 authorization endpoint에 오면 login 화면으로 보낸다.
				.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint("/login"),
						new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

		return http.build();
	}

	/**
	 * 두 discovery 문서에 같은 값을 넣는다.
	 *
	 * <p>이 서버가 받는 client 인증 방식은 문서에 선언할 수 있는 두 방식뿐이다.
	 * 그래서 Spring이 기본으로 알리는 여섯 방식 대신 이 두 방식만 알린다.
	 * 이 서버의 token generator는 access token을 DPoP key나 client 인증서(mTLS)에 묶지 않는다.
	 * 그래서 {@code dpop_signing_alg_values_supported}와 {@code tls_client_certificate_bound_access_tokens}를 지운다.
	 */
	static void advertise(Map<String, Object> claims) {
		claims.put(ISS_PARAMETER_SUPPORTED, true);
		claims.put(CLIENT_ID_METADATA_DOCUMENT_SUPPORTED, true);
		claims.put(OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED,
				TOKEN_ENDPOINT_AUTHENTICATION_METHODS);
		claims.put(OAuth2AuthorizationServerMetadataClaimNames.REVOCATION_ENDPOINT_AUTH_METHODS_SUPPORTED,
				AUTHENTICATED_ENDPOINT_METHODS);
		claims.put(OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED,
				AUTHENTICATED_ENDPOINT_METHODS);
		claims.put(TOKEN_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED, CLIENT_ASSERTION_SIGNING_ALGORITHMS);
		claims.put(REVOCATION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED, CLIENT_ASSERTION_SIGNING_ALGORITHMS);
		claims.put(INTROSPECTION_ENDPOINT_AUTH_SIGNING_ALG_VALUES_SUPPORTED, CLIENT_ASSERTION_SIGNING_ALGORITHMS);
		claims.remove(OAuth2AuthorizationServerMetadataClaimNames.DPOP_SIGNING_ALG_VALUES_SUPPORTED);
		claims.remove(OAuth2AuthorizationServerMetadataClaimNames.TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS);
	}

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				.formLogin(Customizer.withDefaults())
				.build();
	}

	@Bean
	public ClientIdUrlValidator clientIdUrlValidator(ClientIdMetadataDocumentProperties properties) {
		return new ClientIdUrlValidator(properties.loopbackException(), HostResolver.SYSTEM);
	}

	/**
	 * 문서와 JWKS를 가져온다. 문서 host의 인증서는 {@code trust-bundle}의 truststore로만 믿는다.
	 * bundle을 정하지 않으면 JVM 기본 truststore를 쓴다(공인 인증서를 쓰는 실제 배포).
	 */
	@Bean
	public ClientMetadataHttp clientMetadataHttp(ClientIdUrlValidator urlValidator,
			ClientIdMetadataDocumentProperties properties, SslBundles sslBundles) throws Exception {
		SSLContext sslContext = (properties.trustBundle() == null) ? SSLContext.getDefault()
				: sslBundles.getBundle(properties.trustBundle()).createSslContext();
		return new HttpsClientMetadataFetcher(urlValidator, properties, sslContext);
	}

	@Bean
	public RegisteredClientRepository registeredClientRepository(ClientIdUrlValidator urlValidator,
			ClientMetadataHttp http, ClientIdMetadataDocumentProperties properties) {
		return new ClientIdMetadataDocumentRegisteredClientRepository(urlValidator, http, properties, Clock.systemUTC());
	}

	@Bean
	public CimdJwtClientAssertionDecoderFactory clientAssertionDecoderFactory(ClientMetadataHttp http) {
		return new CimdJwtClientAssertionDecoderFactory(http);
	}

	/**
	 * consent 화면이 대기 중인 authorization request의 {@code redirect_uri}를 읽어야 하므로 bean으로 둔다.
	 * bean이 없으면 Spring이 안에서 직접 만들어, 화면에서 같은 저장소를 쓸 수 없다.
	 */
	@Bean
	public OAuth2AuthorizationService authorizationService() {
		return new InMemoryOAuth2AuthorizationService();
	}

	/**
	 * Spring 기본 generator와 같은 순서(JWT, opaque access token, refresh token)로 만들되,
	 * refresh token은 public client에게도 주는 {@link PublicClientRefreshTokenGenerator}로 바꾼다.
	 * 이 bean이 있으면 Spring은 {@code OAuth2TokenCustomizer} bean을 찾지 않으므로 customizer를 직접 넣는다.
	 */
	@Bean
	public OAuth2TokenGenerator<OAuth2Token> tokenGenerator(JWKSource<SecurityContext> jwkSource,
			McpResourceProperties resources) {
		JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
		jwtGenerator.setJwtCustomizer(new ResourceAudienceTokenCustomizer(resources));
		return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2AccessTokenGenerator(),
				new PublicClientRefreshTokenGenerator());
	}

	/**
	 * {@code OAuth2AuthorizationServerConfigurer}는 이 타입의 bean이 있으면 그것을 쓴다.
	 * public client의 consent를 기록하지 않도록, 기본 저장소를 {@link PublicClientConsentService}로 감싼다.
	 */
	@Bean
	public OAuth2AuthorizationConsentService authorizationConsentService(
			RegisteredClientRepository registeredClientRepository) {
		return new PublicClientConsentService(new InMemoryOAuth2AuthorizationConsentService(),
				registeredClientRepository);
	}
}
