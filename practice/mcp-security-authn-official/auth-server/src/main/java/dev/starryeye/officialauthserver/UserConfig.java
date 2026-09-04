package dev.starryeye.officialauthserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명. community 버전과 동일하다.
 *
 * <p>community 버전에는 여기에 {@code OidcDiscoveryConfig} 가 더 있었다.
 * mcp-authorization-server 자동설정이 {@code .oidc(...)} 를 켜주지 않아
 * {@code /.well-known/openid-configuration} 이 404 였기 때문이다.
 * <b>공식 Boot 자동설정({@code OAuth2AuthorizationServerWebSecurityConfiguration})은
 * 그것을 켜주므로 이 practice 에는 그 파일이 없다.</b> 이 차이가 Task 1 의 관측 대상이다.
 */
@Configuration
public class UserConfig {

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername("user")
                        .password("{noop}password")
                        .roles("USER")
                        .build()
        );
    }
}
