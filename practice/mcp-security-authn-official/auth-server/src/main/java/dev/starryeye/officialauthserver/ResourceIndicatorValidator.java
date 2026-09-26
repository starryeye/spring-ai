package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

import java.util.function.Consumer;

/**
 * authorization request의 {@code resource}를 검사한다(RFC 8707 §2.1).
 *
 * <p>token을 발급할 때가 아니라 <b>authorization request를 받을 때</b> 먼저 막는다.
 * 모르는 resource를 향한 요청이면 code를 아예 발급하지 않고 client에 {@code invalid_target}을 돌려준다.
 */
public class ResourceIndicatorValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	private final McpResourceProperties resources;

	public ResourceIndicatorValidator(McpResourceProperties resources) {
		this.resources = resources;
	}

	@Override
	public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
		OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
		Object resource = request.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER);

		if (resource != null && !this.resources.isAllowed(resource)) {
			OAuth2Error error = new OAuth2Error(McpResourceProperties.INVALID_TARGET,
					"The requested resource is not a known protected resource", McpResourceProperties.RFC_8707);
			throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
		}
	}
}
