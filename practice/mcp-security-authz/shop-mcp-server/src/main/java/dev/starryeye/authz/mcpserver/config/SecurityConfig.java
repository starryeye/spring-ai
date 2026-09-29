package dev.starryeye.authz.mcpserver.config;

import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP Server는 OAuth 2.1 resource server다(MCP 2025-11-25 Authorization — Roles, 안내서 2장).
 *
 * <p>여기서 켜는 것은 세 가지다.
 * <ul>
 *   <li>RFC 9728 Protected Resource Metadata(PRM) — client가 Authorization Server를 찾는 출발점이다</li>
 *   <li>401 challenge의 {@code resource_metadata} — PRM의 위치를 알려 준다</li>
 *   <li>token 검증 — signature, {@code iss}(issuer-uri), {@code aud}(audiences 설정)를 본다</li>
 *   <li>{@code 401}과 PRM에 처음 요청할 scope({@code products:read}) — client의 scope 선택 순서가 이 값을 쓴다</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(
								new ScopeChallengeEntryPoint(resourceMetadataEntryPoint(), ToolScopeRegistry.BASE_SCOPE))
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										// MCP client는 이 값을 보고 Authorization Server를 찾아간다.
										.authorizationServer(issuer)
										// 이 서버는 mTLS에 묶인 token을 쓰지 않는다(Spring 기본값은 true).
										.tlsClientCertificateBoundAccessTokens(false)
										// 처음 요청할 조회 scope만 알린다. 쓰기 scope는 필요할 때 403으로 알린다
										// (Security Best Practices — Scope Minimization: scopes_supported에 모든 scope를 싣지 않는다).
										.scope(ToolScopeRegistry.BASE_SCOPE))))
				// stateless resource server다. token으로만 인증하므로 CSRF token을 쓰지 않는다.
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/**
	 * RFC 9728 §3.1의 규칙대로, protected resource URL의 경로 앞에
	 * {@code /.well-known/oauth-protected-resource}를 끼워 넣은 URL을 알려 준다.
	 * 예를 들어 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp}를 가리킨다.
	 */
	private static BearerTokenAuthenticationEntryPoint resourceMetadataEntryPoint() {
		BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
		entryPoint.setResourceMetadataParameterResolver(ResourceMetadataUrl::of);
		return entryPoint;
	}
}
