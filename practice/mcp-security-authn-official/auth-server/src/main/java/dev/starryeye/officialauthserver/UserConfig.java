package dev.starryeye.officialauthserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명이다. user / password로 login한다.
 *
 * <p>filter chain은 {@link AuthorizationServerConfig}에 있다.
 * Authorization Server용 filter chain과 form login용 filter chain을 직접 정의하므로,
 * Boot의 기본 Authorization Server filter chain은 빠진다.
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
