package dev.starryeye.community.authserver.config;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;

/**
 * 꼭 필요한 설정이다. {@code McpAuthorizationServerAutoConfiguration}이 만드는 Authorization Server
 * filter chain은 {@code OAuth2AuthorizationServerConfigurer#oidc(...)}를 부르지 않는다.
 * 그래서 {@code /.well-known/openid-configuration}은 security matcher 범위에는 들어가지만,
 * 실제로 응답하는 filter가 없어 404가 난다.
 * {@code openid} scope로 login하는 {@code oauth2Login} 흐름도 id_token을 받지 못해 실패한다.
 *
 * <p>이 bean은 그 자동 구성이 {@code ObjectProvider}로 모아 적용해 주는 확장점
 * ({@code Customizer<McpAuthorizationServerConfigurer>})을 써서 oidc()를 켠다.
 * {@code SecurityFilterChain}을 직접 정의하지 않으려고 일부러 이 경로를 쓴다.
 * 직접 정의하면 {@code @ConditionalOnDefaultWebSecurity} 조건이 깨져 이 module의 Authorization Server
 * 설정 전체가 빠진다.
 */
@Configuration
public class OidcDiscoveryConfig {

    /**
     * {@code McpAuthorizationServerAutoConfiguration}은 이 타입의 bean들을
     * {@code ObjectProvider#orderedStream()}으로 모아 순서대로 적용한다({@code @Order}가
     * 없으면 {@code Ordered.LOWEST_PRECEDENCE}로 취급되어, 등록 순서에 암묵적으로 기댄다).
     * 이 bean은 oidc()를 켜서 {@code OidcConfigurer}를 만드는 쪽이다.
     * 그래서 {@code providerConfigurationCustomizer}로 그 안의 claim을 채우는
     * {@link McpAuthorizationStandardConfig}보다 먼저 적용되도록 낮은 순서를 준다.
     * 다만 {@code OAuth2AuthorizationServerConfigurer#oidc(...)}는 부를 때마다 같은
     * {@code OidcConfigurer}를 가져오거나 새로 만들어 바로 적용한다(community module이 아니라
     * spring-security-config 7.1.0의 {@code OAuth2AuthorizationServerConfigurer#oidc}를 확인했다).
     * 그래서 두 bean이 서로 다른 field를 건드리는 지금은 이 순서가 없어도 결과는 같다.
     */
    @Bean
    @Order(0)
    public Customizer<McpAuthorizationServerConfigurer> mcpOidcDiscoveryCustomizer() {
        return configurer -> configurer.authorizationServer(oauth2 -> oauth2.oidc(Customizer.withDefaults()));
    }
}
