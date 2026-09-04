package dev.starryeye.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명. user / password 로 로그인한다.
 *
 * <p>여기에 {@code SecurityFilterChain} 빈을 추가하면 안 된다.
 * {@code McpAuthorizationServerAutoConfiguration} 이 {@code @ConditionalOnDefaultWebSecurity}
 * 라서, 직접 만든 필터체인이 있으면 인가 서버 설정이 통째로 물러난다.
 * 필터체인(인가 서버용 + 폼 로그인용) 두 개는 그 자동설정이 이미 제공한다.
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
