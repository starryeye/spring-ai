package dev.starryeye.authz.mcpserver.config;

import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP Server는 OAuth 2.1 resource server다(MCP 2025-11-25 Authorization — Roles, 안내서 2장).
 *
 * <p>여기서 켜는 것은 네 가지다.
 * <ul>
 *   <li>RFC 9728 Protected Resource Metadata(PRM) — client가 Authorization Server를 찾는 출발점이다</li>
 *   <li>401 challenge의 {@code resource_metadata} — PRM의 위치를 알려 준다</li>
 *   <li>token 검증 — signature, {@code iss}(issuer-uri), {@code aud}(audiences 설정), header의 {@code typ}을 본다</li>
 *   <li>{@code 401}과 PRM에 처음 요청할 scope({@code products:read}) — client의 scope 선택 순서가 이 값을 쓴다</li>
 * </ul>
 *
 * <p>PRM의 {@code resource}와 {@code resource_metadata}는 요청의 {@code Host}가 아니라 설정한 주소로 만든다({@link ResourceMetadataUrl}).
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
			OAuth2ResourceServerProperties properties) throws Exception {
		String resource = ResourceMetadataUrl.resource(properties);
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(new ScopeChallengeEntryPoint(
								resourceMetadataEntryPoint(ResourceMetadataUrl.of(resource)), ToolScopeRegistry.BASE_SCOPE))
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										// Spring이 요청 URL로 만든 기본값에서 경로만 두고 origin을 설정값으로 바꾼다.
										.claims(claims -> claims.put("resource",
												ResourceMetadataUrl.withConfiguredOrigin((String) claims.get("resource"), resource)))
										// MCP client는 이 값을 보고 Authorization Server를 찾아간다.
										.authorizationServer(issuer)
										// 이 서버는 mTLS에 묶인 token을 쓰지 않는다(Spring 기본값은 true).
										.tlsClientCertificateBoundAccessTokens(false)
										// 처음 요청할 조회 scope만 알린다.
										// 쓰기 scope는 필요할 때 403으로 알린다 (Security Best Practices — Scope Minimization: scopes_supported에 모든 scope를 넣지 않는다).
										.scope(ToolScopeRegistry.BASE_SCOPE))))
				// stateless resource server다.
				// token으로만 인증하므로 CSRF token을 쓰지 않는다.
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/**
	 * access token header의 {@code typ}으로 받을 값을 정한다.
	 * Spring Security의 기본 검증기는 {@code typ}이 없거나 {@code JWT}인 token만 받는다.
	 * 그러면 RFC 9068 형식의 {@code typ: at+jwt} token을 {@code 401 invalid_token}으로 거절한다.
	 * 그래서 RFC 9068이 정한 {@code at+jwt}와 {@code application/at+jwt}도 받는다.
	 * 이 검증기가 bean으로 있으면 Boot는 기본 검증기를 더하지 않고 이 검증기를 쓴다.
	 * issuer와 audience 검증은 Boot가 설정에서 만들어 그대로 더한다.
	 * 그 밖의 {@code typ}은 access token이 아닌 JWT일 수 있어 계속 거절한다.
	 */
	@Bean
	public JwtTypeValidator accessTokenTypeValidator() {
		JwtTypeValidator validator = new JwtTypeValidator("JWT", "at+jwt", "application/at+jwt");
		validator.setAllowEmpty(true);
		return validator;
	}

	/**
	 * {@code 401} challenge에 PRM 주소를 넣는다.
	 * MCP endpoint가 아닌 경로로 온 요청도 이 서버가 지키는 resource는 하나라서 같은 주소를 알린다.
	 */
	private static BearerTokenAuthenticationEntryPoint resourceMetadataEntryPoint(String resourceMetadataUrl) {
		BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
		entryPoint.setResourceMetadataParameterResolver(request -> resourceMetadataUrl);
		return entryPoint;
	}
}
