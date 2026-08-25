package dev.starryeye.authserver;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;

/**
 * 실험용: McpAuthorizationServerAutoConfiguration 이 만드는 인가 서버 필터체인은
 * OAuth2AuthorizationServerConfigurer#oidc(...) 를 호출하지 않는다.
 * 그 결과 /.well-known/openid-configuration 이 시큐리티 매처 범위에는 포함되지만
 * 실제로 응답하는 필터가 등록되지 않아 404 가 난다.
 * 이 빈은 자동설정이 ObjectProvider 로 수집하는 확장 포인트를 이용해 oidc() 를 켠다.
 */
@Configuration
public class OidcDiscoveryConfig {

    @Bean
    public Customizer<McpAuthorizationServerConfigurer> mcpOidcDiscoveryCustomizer() {
        return configurer -> configurer.authorizationServer(oauth2 -> oauth2.oidc(Customizer.withDefaults()));
    }
}
