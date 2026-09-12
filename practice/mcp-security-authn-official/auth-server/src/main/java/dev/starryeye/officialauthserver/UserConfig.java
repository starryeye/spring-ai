package dev.starryeye.officialauthserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명. user / password 로 로그인한다.
 *
 * <p>필터체인은 {@link AuthorizationServerConfig} 에 있다. 인가 서버용 체인과
 * 폼 로그인용 체인 두 개를 직접 정의하므로, Boot 의 기본 인가 서버 필터체인은 물러난다.
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
