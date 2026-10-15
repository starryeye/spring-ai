package dev.starryeye.memory.mcpserver.config;

import dev.starryeye.memory.mcpserver.filter.McpSessionBindingFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

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
 *
 * <p>PRM의 {@code resource}와 {@code resource_metadata}는 요청의 {@code Host}가 아니라 설정한 주소({@code audiences})로 만든다.
 * Spring의 기본값은 요청 URL로 만든다. 그러면 {@code Host}를 바꾼 요청에 다른 서버 주소를 광고하고,
 * reverse proxy 뒤에서는 scheme도 틀린다. token의 {@code aud}로 검증하는 값과 같은 하나를 쓰면 셋이 어긋날 일이 없다.
 * 다만 PRM의 {@code resource}는 요청한 PRM 주소의 경로 모양을 따른다({@link #withConfiguredOrigin}).
 */
@Configuration
public class SecurityConfig {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
			OAuth2ResourceServerProperties properties) throws Exception {
		String resource = resource(properties);
		String resourceMetadataUrl = resourceMetadataUrl(resource);
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(resourceMetadataEntryPoint(resourceMetadataUrl))
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										// Spring이 요청 URL로 만든 기본값에서 경로만 두고 origin을 설정값으로 바꾼다.
										.claims(claims -> claims.put("resource",
												withConfiguredOrigin((String) claims.get("resource"), resource)))
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
	 * 이 서버가 지키는 resource의 식별자다. token의 {@code aud}로 검증하는 값({@code audiences})과 같다.
	 * 이 서버의 resource는 MCP endpoint 하나라서 값도 하나여야 한다.
	 */
	private static String resource(OAuth2ResourceServerProperties properties) {
		List<String> audiences = properties.getJwt().getAudiences();
		Assert.state(audiences.size() == 1,
				"spring.security.oauth2.resourceserver.jwt.audiences에는 이 MCP Server의 주소 하나만 둔다: " + audiences);
		return audiences.get(0);
	}

	/**
	 * RFC 9728 §3.1의 규칙대로, resource URL의 경로 앞에 {@code /.well-known/oauth-protected-resource}를 끼워 넣는다.
	 * 예를 들어 {@code https://mcp.example.com/mcp}는 {@code https://mcp.example.com/.well-known/oauth-protected-resource/mcp}다.
	 */
	private static String resourceMetadataUrl(String resource) {
		UriComponents uri = UriComponentsBuilder.fromUriString(resource).build();
		String path = (uri.getPath() == null || "/".equals(uri.getPath())) ? "" : uri.getPath();
		return UriComponentsBuilder.fromUriString(resource)
				.replacePath(PROTECTED_RESOURCE_METADATA + path)
				.replaceQuery(null)
				.build()
				.toUriString();
	}

	/**
	 * Spring이 요청한 PRM 주소로 만든 {@code resource}에서 경로만 두고, scheme·host·port는 설정한 resource의 것으로 바꾼다.
	 *
	 * <p>Spring은 PRM 주소에서 {@code /.well-known/oauth-protected-resource}를 뺀 경로를 요청의 {@code Host}에 붙인다.
	 * 경로형({@code .../oauth-protected-resource/mcp})이면 {@code /mcp}, 루트형이면 빈 경로가 남는다.
	 * RFC 9728 §3.3은 PRM의 {@code resource}가 그 PRM 주소를 만든 식별자와 같아야 한다고 한다.
	 * 루트형 주소를 만든 식별자는 서버 origin이라, client는 루트형 PRM에서 경로 없는 {@code resource}를 기대한다.
	 * 그래서 경로는 그대로 두고 {@code Host}에 기대는 부분만 설정값으로 바꾼다.
	 */
	private static String withConfiguredOrigin(String requested, String resource) {
		return UriComponentsBuilder.fromUriString(resource)
				.replacePath(UriComponentsBuilder.fromUriString(requested).build().getPath())
				.replaceQuery(null)
				.build()
				.toUriString();
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
