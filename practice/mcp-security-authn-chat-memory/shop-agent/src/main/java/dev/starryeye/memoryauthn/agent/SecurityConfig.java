package dev.starryeye.memoryauthn.agent;

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

@Configuration
public class SecurityConfig {

    /**
     * {@code /api/chat} 과 {@code DELETE /api/conversations/{label}} 모두 CSRF 를 검사한다 — 예외가 없다.
     * {@code csrf.spa()} 는 JS 가 읽을 수 있는 {@code XSRF-TOKEN} 쿠키를 응답에 싣고, index.html 이 그 값을
     * {@code X-XSRF-TOKEN} 헤더로 되돌려 보낸다.
     */
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
