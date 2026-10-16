package dev.starryeye.memory.authserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 사용자 <b>두 명</b>이다. official·community practice는 한 명이다.
 * 대화 격리는 사용자가 둘 이상이어야 볼 수 있다.
 * 한 명으로는 "격리되었다"와 "격리 코드가 없다"를 구분하지 못한다.
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
                User.withUsername("alice")
                        .password("{noop}alice")
                        .roles("USER")
                        .build(),
                User.withUsername("bob")
                        .password("{noop}bob")
                        .roles("USER")
                        .build()
        );
    }
}
