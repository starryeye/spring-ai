package dev.starryeye.community.mcpserver.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * community module의 MCP Server 설정기를 직접 적용한다.
 *
 * <p>자동 구성({@code McpServerSecurityAutoConfiguration})도 같은 설정기를 쓰지만
 * audience 검증을 켜는 방법이 없다. audience 검증이 없으면 같은 Authorization Server가 발급한
 * 다른 resource용 token이 그대로 통과한다. MCP authorization 명세가 MUST로 막는 경우다.
 * 그래서 여기서 filter chain을 직접 정의한다(자동 구성은 빠진다).
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
                    // Boot가 issuer-uri로 만든 decoder를 그대로 쓴다(signature·iss·typ 검증).
                    mcp.jwtDecoder(jwtDecoder);
                    // 요청 URL로 계산한 resource 식별자가 token의 aud에 있는지 본다.
                    mcp.validateAudienceClaim(true);
                    mcp.protectedResourceMetadataCustomizer(metadata -> metadata
                            .authorizationServer(issuer)
                            .resourceName("shop-mcp-server")
                            // 이 서버는 mTLS에 묶인 token을 쓰지 않는다(module 기본값은 true).
                            .tlsClientCertificateBoundAccessTokens(false));
                    // browser에서 직접 부를 일이 없다. 자기 자신 밖의 Origin은 막는다.
                    mcp.allowedOrigins(List.of("http://localhost:" + port));
                    mcp.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port));
                    // McpServerOAuth2Configurer.init()은 자기 entry point(module의
                    // BearerResourceMetadataTokenAuthenticationEntryPoint)를 먼저 설정한 뒤 이 customizer를 부른다.
                    // 그래서 여기서 entry point를 official과 같은 것으로 바꿀 수 있다.
                    // module의 entry point는 401 challenge의 resource_metadata 값에 따옴표를 붙이지 않는다.
                    // 하지만 RFC 9110 §11.2의 auth-param 문법에 따르면 ":"와 "/"가 든 URL 값은
                    // quoted-string(따옴표)이어야 한다.
                    mcp.oauth2ResourceServer(rs -> rs.authenticationEntryPoint(resourceMetadataEntryPoint()));
                })
                // stateless resource server다. token으로만 인증하므로 CSRF token을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * access token header의 {@code typ}으로 받을 값을 정한다.
     * Spring Security의 기본 검증기는 {@code typ}이 없거나 {@code JWT}인 token만 받는다.
     * 그러면 RFC 9068 형식의 {@code typ: at+jwt} token을 {@code 401 invalid_token}으로 거절한다.
     * 그래서 RFC 9068이 정한 {@code at+jwt}와 {@code application/at+jwt}도 받는다.
     * 이 검증기가 bean으로 있으면 Boot는 기본 검증기를 더하지 않고 이 검증기를 쓴다.
     * 위 filter chain에 넘기는 decoder가 Boot가 만든 것이라 이 검증기가 그대로 적용된다.
     * issuer 검증은 Boot가 설정에서 만들어 그대로 더하고, audience 검증은 module이 decoder를 감싸 맡는다.
     * 그 밖의 {@code typ}은 access token이 아닌 JWT일 수 있어 계속 거절한다.
     */
    @Bean
    public JwtTypeValidator accessTokenTypeValidator() {
        JwtTypeValidator validator = new JwtTypeValidator("JWT", "at+jwt", "application/at+jwt");
        validator.setAllowEmpty(true);
        return validator;
    }

    /**
     * RFC 9728 §3.1의 규칙대로, protected resource URL의 경로 앞에
     * {@code /.well-known/oauth-protected-resource}를 끼워 넣은 URL을 알려 준다.
     * 곧 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp}를 가리킨다.
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
