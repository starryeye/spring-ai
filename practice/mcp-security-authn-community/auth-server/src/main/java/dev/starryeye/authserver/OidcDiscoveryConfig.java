package dev.starryeye.authserver;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;

/**
 * 필수 설정이다. {@code McpAuthorizationServerAutoConfiguration} 이 만드는 인가 서버
 * 필터체인은 {@code OAuth2AuthorizationServerConfigurer#oidc(...)} 를 호출하지 않는다.
 * 그 결과 {@code /.well-known/openid-configuration} 이 시큐리티 매처 범위에는 포함되지만
 * 실제로 응답하는 필터가 등록되지 않아 404 가 나고, {@code openid} 스코프로 로그인하는
 * {@code oauth2Login} 흐름은 id_token 을 받지 못해 실패한다.
 *
 * <p>이 빈은 그 자동설정이 {@code ObjectProvider} 로 수집해 적용해주는 확장 포인트
 * ({@code Customizer<McpAuthorizationServerConfigurer>}) 를 이용해 oidc() 를 켠다.
 * 직접 {@code SecurityFilterChain} 을 정의하지 않기 위해 일부러 이 경로를 쓴 것이다 —
 * 그렇게 하면 {@code @ConditionalOnDefaultWebSecurity} 조건이 깨져 이 모듈의 인가 서버
 * 설정 전체가 물러난다.
 */
@Configuration
public class OidcDiscoveryConfig {

    @Bean
    public Customizer<McpAuthorizationServerConfigurer> mcpOidcDiscoveryCustomizer() {
        return configurer -> configurer.authorizationServer(oauth2 -> oauth2.oidc(Customizer.withDefaults()));
    }
}
