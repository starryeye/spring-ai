package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                // 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
                // 이 엔드포인트는 상태를 바꾸지 않는 조회성 질의만 받지만,
                // 실제 서비스라면 XSRF-TOKEN 쿠키를 읽어 헤더에 실어야 한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }
}
