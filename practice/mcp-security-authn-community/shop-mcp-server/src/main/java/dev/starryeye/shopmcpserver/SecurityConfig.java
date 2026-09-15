package dev.starryeye.shopmcpserver;

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * community 모듈의 MCP 서버 설정기를 직접 적용한다.
 *
 * <p>자동설정({@code McpServerSecurityAutoConfiguration})도 같은 설정기를 쓰지만
 * audience 검증을 켜는 길이 없다. audience 검증이 없으면 같은 인가 서버가 발급한
 * 다른 리소스용 토큰이 그대로 통과한다 — MCP 인가 명세가 MUST 로 막는 것이다.
 * 그래서 여기서 필터체인을 직접 정의한다(자동설정은 물러난다).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain mcpServerSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${server.port}") int port) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> {
                    mcp.authorizationServer(issuer);
                    // Boot 가 issuer-uri 로 만든 디코더를 그대로 쓴다(서명·iss 검증).
                    mcp.jwtDecoder(jwtDecoder);
                    // 요청 URL 로 계산한 리소스 식별자가 토큰의 aud 에 있는지 본다.
                    mcp.validateAudienceClaim(true);
                    mcp.protectedResourceMetadataCustomizer(metadata -> metadata
                            .authorizationServer(issuer)
                            .resourceName("shop-mcp-server")
                            // 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다(모듈 기본값은 true).
                            .tlsClientCertificateBoundAccessTokens(false));
                    // 브라우저에서 직접 부를 일이 없다. 자기 자신 외의 Origin 은 막는다.
                    mcp.allowedOrigins(List.of("http://localhost:" + port));
                    mcp.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port));
                })
                // 무상태 리소스 서버다. 토큰으로만 인증하므로 CSRF 토큰을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
