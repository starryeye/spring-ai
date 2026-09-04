package dev.starryeye.officialmcpserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * community 버전에는 이 파일이 <b>없었다</b>. mcp-server-security-spring-boot 의
 * 자동설정이 필터체인을 대신 만들어 줬고, 오히려 여기에 이런 빈을 정의하면
 * {@code @ConditionalOnDefaultWebSecurity} 때문에 그 자동설정이 통째로 물러났다.
 *
 * <p>공식 구성에서는 반대다 — 내가 전부 쓴다. 코드는 늘지만 숨은 동작이 없다.
 * 무엇이 켜지는지가 이 메서드 안에 전부 보인다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        // issuer-uri 로 JWK 를 받아 서명·issuer 를 검증한다.
                        .jwt(Customizer.withDefaults())
                        // RFC 9728 보호 리소스 메타데이터.
                        // community 는 이것을 위해 라이브러리를 썼는데,
                        // Spring Security 7.1 에 이미 들어와 있다.
                        .protectedResourceMetadata(Customizer.withDefaults()))
                // 무상태 리소스 서버다. 토큰으로만 인증하므로 CSRF 토큰을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
