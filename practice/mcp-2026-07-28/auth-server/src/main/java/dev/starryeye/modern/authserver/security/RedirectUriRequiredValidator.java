package dev.starryeye.modern.authserver.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

/**
 * 모든 authorization request에 {@code redirect_uri}를 요구한다.
 *
 * <p>Spring은 문서의 redirect URI가 하나뿐이면 {@code openid} 없는 요청의 {@code redirect_uri} 생략을 받는다
 * (RFC 6749 §3.1.2.3이 허용한다). 그러면 대기 중인 authorization request에 {@code redirect_uri}가 없어서,
 * consent 화면이 "허락하면 돌아갈 주소"를 보여 주지 못한다.
 * MCP Authorization(Localhost Redirect URI Risks)은 consent 화면에 redirect URI의 host를 분명히 보이라고 한다(MUST).
 * CIMD client의 redirect URI는 사용자가 확인해야 할 값이므로, 생략을 받지 않고 {@code invalid_request}로 끝낸다.
 *
 * <p>Spring 기본 검증 바로 뒤에 둔다. 기본 검증이 {@code client_id}와 {@code redirect_uri}의 값을 먼저 확인한다.
 * 이 오류에는 믿을 redirect URI가 없으므로, {@link IssuerIdentifyingAuthorizationResponseHandler}는 redirect하지 않고 400으로 보인다.
 */
public class RedirectUriRequiredValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	static final String RFC_6749_ERROR_RESPONSE = "https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1";

	@Override
	public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
		OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
		if (!StringUtils.hasText(request.getRedirectUri())) {
			OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
					"The request must include redirect_uri", RFC_6749_ERROR_RESPONSE);
			throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
		}
	}
}
