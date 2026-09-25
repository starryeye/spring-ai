package dev.starryeye.memoryauthn.mcpserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MCP 서버는 OAuth 2.0 보호 리소스다(MCP 2025-11-25 인가 §1).
 *
 * <p>여기서 켜는 것은 네 가지다.
 * <ul>
 *   <li>RFC 9728 보호 리소스 메타데이터 — 클라이언트가 인가 서버를 찾는 출발점</li>
 *   <li>401 챌린지의 {@code resource_metadata} — 그 메타데이터의 위치를 알려준다</li>
 *   <li>토큰 검증 — 서명·{@code iss}(issuer-uri)와 {@code aud}(audiences 속성)</li>
 *   <li>MCP session 을 token 의 사용자에 묶기 — {@link McpSessionBindingFilter}</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(resourceMetadataEntryPoint())
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										// MCP 클라이언트는 이 값을 보고 인가 서버로 간다.
										.authorizationServer(issuer)
										// 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다(Spring 기본값은 true).
										.tlsClientCertificateBoundAccessTokens(false))))
				// MCP session 을 token 의 사용자에 묶는다. 인증·인가를 마친 요청만 이 filter 에 닿는다.
				.addFilterAfter(new McpSessionBindingFilter(), AuthorizationFilter.class)
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
