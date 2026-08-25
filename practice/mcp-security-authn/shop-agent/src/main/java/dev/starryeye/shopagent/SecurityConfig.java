package dev.starryeye.shopagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 에이전트는 모든 요청에 로그인을 요구한다. 브라우저로 "/" 에 들어오면
 * auth-server 로 튕겨 로그인하고 돌아온다.
 *
 * <p>여기서 만드는 {@code OAuth2AuthorizedClient} 가 그대로 MCP 호출에 쓰인다 —
 * 즉 사용자가 로그인해서 받은 토큰이 MCP 서버로 간다. 이것이
 * authorization_code 를 고른 이유다.
 *
 * <p>MCP 서버·인가 서버와 달리 에이전트에는 {@code @ConditionalOnDefaultWebSecurity}
 * 제약이 없다. 여기서는 필터체인을 직접 정의해도 된다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                // MCP 호출 시 토큰을 얻으려면 oauth2Client 가 필요하다.
                .oauth2Client(Customizer.withDefaults())
                // 학습용 단순화: index.html 의 fetch 가 CSRF 토큰을 싣지 않으므로
                // 이 엔드포인트만 예외로 둔다. 실제 서비스라면 토큰을 실어 보내야 한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }
}
