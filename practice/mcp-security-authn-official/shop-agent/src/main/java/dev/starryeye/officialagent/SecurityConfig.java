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
 * 이 agent에서 login은 곧 MCP 호출에 쓸 token을 받는 과정이다.
 * 명세가 요구하는 세 가지를 여기서 켠다.
 *
 * <ul>
 *   <li>PKCE(S256) — 가로챈 authorization code를 쓰지 못하게 한다</li>
 *   <li>RFC 8707 {@code resource} — token을 이 MCP Server 전용으로 좁힌다</li>
 *   <li>RFC 9207 {@code iss} 검증 — code를 교환하기 전에 응답의 출처를 확인한다</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            DiscoveredClientRegistrationRepository registrations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient)
            throws Exception {
        // iss 검증 filter와 login filter가 같은 저장소를 봐야 한다.
        var authorizationRequests = new HttpSessionOAuth2AuthorizationRequestRepository();
        var failureHandler = new LoginFailureHandler();

        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(login -> login
                        // client 등록은 하나뿐이고, 그 등록 정보는 discovery를 해야 알 수 있다.
                        // 그래서 login 화면 대신 곧바로 authorization request로 보낸다.
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
                // /api/chat도 CSRF를 검사한다.
                // csrf.spa()는 JS가 읽을 수 있는 XSRF-TOKEN cookie를 응답에 넣는다.
                // index.html은 그 값을 X-XSRF-TOKEN header로 되돌려 보낸다.
                // 다른 사이트의 페이지는 이 cookie를 읽지 못하므로,
                // 사용자 몰래 채팅(곧 MCP tool 호출)을 보낼 수 없다.
                .csrf(csrf -> csrf.spa())
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
