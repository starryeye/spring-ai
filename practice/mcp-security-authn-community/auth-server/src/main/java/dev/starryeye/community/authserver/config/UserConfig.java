package dev.starryeye.community.authserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명이다.
 * user / password로 login한다.
 *
 * <p>여기에 {@code SecurityFilterChain} bean을 더하면 안 된다.
 * {@code McpAuthorizationServerAutoConfiguration}에는 {@code @ConditionalOnDefaultWebSecurity}가 붙어 있어,
 * 직접 만든 filter chain이 있으면 Authorization Server 설정이 통째로 빠진다.
 * filter chain 두 개(Authorization Server용, form login용)는 그 자동 구성이 이미 만들어 준다.
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
