package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 로그인 = MCP 호출에 쓸 토큰을 받는 과정이다. 명세가 요구하는 세 가지를 여기서 건다.
 *
 * <ul>
 *   <li>PKCE(S256) — 가로챈 인가 코드를 쓰지 못하게 한다</li>
 *   <li>RFC 8707 {@code resource} — 토큰을 이 MCP 서버 전용으로 좁힌다</li>
 *   <li>RFC 9207 {@code iss} 검증 — 코드를 교환하기 전에 응답의 출처를 확인한다</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            DiscoveredClientRegistrationRepository registrations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient)
            throws Exception {
        // iss 검증 필터와 로그인 필터가 같은 저장소를 봐야 한다.
        var authorizationRequests = new HttpSessionOAuth2AuthorizationRequestRepository();
        var failureHandler = new LoginFailureHandler();

        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(login -> login
                        // 등록이 하나뿐이고, 그 등록은 발견해야 알 수 있다.
                        // 로그인 화면 대신 곧바로 인가 요청으로 보낸다.
                        .loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                                + "/" + McpSecurityConfig.REGISTRATION_ID)
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestRepository(authorizationRequests)
                                .authorizationRequestResolver(authorizationRequestResolver(registrations)))
                        .tokenEndpoint(token -> token.accessTokenResponseClient(authorizationCodeTokenResponseClient))
                        .failureHandler(failureHandler))
                .addFilterBefore(new AuthorizationResponseIssuerFilter(authorizationRequests, registrations,
                        failureHandler), OAuth2LoginAuthenticationFilter.class)
                .oauth2Client(Customizer.withDefaults())
                // 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }

    private static OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            DiscoveredClientRegistrationRepository registrations) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(ResourceIndicators.authorizationRequest(() -> registrations.discovered().resource())));
        return resolver;
    }
}
