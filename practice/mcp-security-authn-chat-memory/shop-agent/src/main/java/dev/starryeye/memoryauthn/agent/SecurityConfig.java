package dev.starryeye.memoryauthn.agent;

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
                // /api/conversations/{label} 의 DELETE(대화 비우기)도 상태를 바꾸므로
                // 여기 포함시키지 않으면 index.html 의 "이 대화 비우기" 버튼이 403 을 받는다.
                // 실제 서비스라면 XSRF-TOKEN 쿠키를 읽어 헤더에 실어야 한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat", "/api/conversations/**"))
                .build();
    }
}
