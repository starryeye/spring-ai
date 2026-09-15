package dev.starryeye.memoryauthn.authserver;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

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
									.authorizationServerMetadataCustomizer(
											builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))
							// oauth2Login 이 id_token 을 받으려면 OIDC 가 필요하다.
							.oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
									.providerConfigurationCustomizer(
											builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true))));
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
}
