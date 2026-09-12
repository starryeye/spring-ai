package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

/**
 * access token 의 {@code aud} 를 요청한 {@code resource} 로 발급한다(RFC 8707 §2.2).
 *
 * <p>이것이 있어야 MCP 서버가 "내 앞으로 온 토큰"만 받아들일 수 있다. 없으면
 * 같은 인가 서버를 쓰는 다른 리소스의 토큰이 그대로 통한다(confused deputy).
 *
 * <p>id_token 은 건드리지 않는다. id_token 의 청중은 클라이언트 자신이다.
 */
public class ResourceAudienceTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

	private final McpResourceProperties resources;

	public ResourceAudienceTokenCustomizer(McpResourceProperties resources) {
		this.resources = resources;
	}

	@Override
	public void customize(JwtEncodingContext context) {
		if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			return;
		}

		Object requested = requestedResource(context);
		Object authorized = authorizedResource(context);

		// 인가 때 지정한 리소스와 다른 리소스로 토큰을 받아가려는 시도를 막는다.
		if (requested != null && authorized != null && !requested.equals(authorized)) {
			throw invalidTarget("The requested resource does not match the authorization request");
		}

		Object resource = (requested != null) ? requested : authorized;
		if (resource == null) {
			// resource 없이 발급된 토큰은 aud 가 client_id 로 남는다.
			// MCP 서버는 audience 검증에서 그런 토큰을 거부한다.
			return;
		}
		if (!this.resources.isAllowed(resource)) {
			throw invalidTarget("The requested resource is not a known protected resource");
		}

		context.getClaims().audience(List.of((String) resource));
	}

	private static Object requestedResource(JwtEncodingContext context) {
		return (context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant)
				? grant.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER) : null;
	}

	/** 인가 요청에 실렸던 resource. refresh_token 그랜트에서도 이 값이 남아 있다. */
	private static Object authorizedResource(JwtEncodingContext context) {
		OAuth2Authorization authorization = context.getAuthorization();
		if (authorization == null) {
			return null;
		}
		OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
		return (request == null) ? null : request.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER);
	}

	private static OAuth2AuthenticationException invalidTarget(String description) {
		return new OAuth2AuthenticationException(new OAuth2Error(McpResourceProperties.INVALID_TARGET, description,
				McpResourceProperties.RFC_8707));
	}
}
