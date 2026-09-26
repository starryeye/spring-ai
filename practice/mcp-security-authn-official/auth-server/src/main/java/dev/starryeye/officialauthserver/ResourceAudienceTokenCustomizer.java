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
 * access token의 {@code aud}를 요청한 {@code resource}로 정해 발급한다(RFC 8707 §2.2).
 *
 * <p>이 값이 있어야 MCP Server가 "나에게 발급된 token"만 받을 수 있다.
 * 없으면 같은 Authorization Server를 쓰는 다른 resource의 token도 그대로 통한다(confused deputy).
 *
 * <p>id_token은 건드리지 않는다. id_token의 audience는 client 자신이다.
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

		// authorization request에서 정한 resource와 다른 resource로 token을 받아 가려는 시도를 막는다.
		if (requested != null && authorized != null && !requested.equals(authorized)) {
			throw invalidTarget("The requested resource does not match the authorization request");
		}
		// authorization request에 없던 resource를 token request에서 새로 정하지 못하게 한다.
		// 사용자가 consent한 대상이 아니기 때문이다.
		if (requested != null && authorized == null) {
			throw invalidTarget("The authorization request did not include a resource");
		}

		Object resource = (requested != null) ? requested : authorized;
		if (resource == null) {
			// resource 없이 발급한 token은 aud가 client_id로 남는다.
			// MCP Server는 audience 검증에서 그런 token을 거부한다.
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

	/** authorization request에 있던 resource다. refresh_token grant에서도 이 값이 남아 있다. */
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
