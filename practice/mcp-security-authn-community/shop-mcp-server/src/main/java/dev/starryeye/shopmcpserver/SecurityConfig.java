package dev.starryeye.shopmcpserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

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

    private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

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
                    // McpServerOAuth2Configurer.init() 은 자기 진입점(모듈의
                    // BearerResourceMetadataTokenAuthenticationEntryPoint)을 먼저 설정한 뒤
                    // 이 customizer 를 호출하므로, 여기서 진입점을 official 과 같은 것으로 바꿔 끼울 수 있다.
                    // 모듈 진입점은 401 챌린지의 resource_metadata 값에 따옴표를 붙이지 않는데,
                    // RFC 9110 §11.2 의 auth-param 문법상 ":" 와 "/" 를 담은 URL 값은
                    // quoted-string(따옴표)이어야 한다.
                    mcp.oauth2ResourceServer(rs -> rs.authenticationEntryPoint(resourceMetadataEntryPoint()));
                })
                // 무상태 리소스 서버다. 토큰으로만 인증하므로 CSRF 토큰을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * RFC 9728 §3.1 이 정한 규칙대로, 보호 리소스 URL 의 경로 앞에
     * {@code /.well-known/oauth-protected-resource} 를 끼워 넣은 URL 을 알려준다.
     * 즉 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp} 를 가리킨다.
     */
    private static BearerTokenAuthenticationEntryPoint resourceMetadataEntryPoint() {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(SecurityConfig::resourceMetadataUrl);
        return entryPoint;
    }

    private static String resourceMetadataUrl(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath(PROTECTED_RESOURCE_METADATA + ("/".equals(path) ? "" : path))
                .replaceQuery(null)
                .build()
                .toUriString();
    }
}
