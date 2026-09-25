package dev.starryeye.shopagent;

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
 * 에이전트는 모든 요청에 로그인을 요구한다. 로그인해서 받은 토큰이 그대로 MCP 호출에 쓰인다.
 *
 * <p>명세가 요구하는 세 가지를 여기서 건다 — PKCE(S256), RFC 8707 {@code resource},
 * RFC 9207 {@code iss} 검증.
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
                        .loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                                + "/" + McpSecurityConfig.REGISTRATION_ID)
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestRepository(authorizationRequests)
                                .authorizationRequestResolver(authorizationRequestResolver(registrations)))
                        .tokenEndpoint(token -> token.accessTokenResponseClient(authorizationCodeTokenResponseClient))
                        .failureHandler(failureHandler))
                .addFilterBefore(new AuthorizationResponseIssuerFilter(authorizationRequests, registrations,
                        failureHandler), OAuth2LoginAuthenticationFilter.class)
                // MCP 호출 시 토큰을 얻으려면 oauth2Client 가 필요하다.
                .oauth2Client(Customizer.withDefaults())
                // /api/chat 도 CSRF 를 검사한다. csrf.spa() 는 JS 가 읽을 수 있는 XSRF-TOKEN 쿠키를
                // 응답에 싣고, index.html 이 그 값을 X-XSRF-TOKEN 헤더로 되돌려 보낸다. 다른 사이트의
                // 페이지는 이 쿠키를 읽지 못하므로 사용자 몰래 채팅(=MCP tool 호출)을 보낼 수 없다.
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
