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
                // 학습용 단순화: index.html 의 fetch 가 CSRF 토큰을 싣지 않으므로
                // 이 엔드포인트만 예외로 둔다. 대가: 로그인한 사용자가 다른 탭에서 악성
                // 페이지를 열어 두면, 그 페이지가 세션 쿠키만으로 /api/chat 에 임의의
                // 질문(=MCP 툴 호출)을 사용자 모르게 시킬 수 있다 — CSRF 로 인한 툴 오남용.
                // 실제 서비스라면 fetch 에 CSRF 토큰(XSRF-TOKEN 쿠키 등)을 실어 보내
                // 이 예외를 없애야 한다. 여기서는 그 처리를 구현하지 않았다.
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
