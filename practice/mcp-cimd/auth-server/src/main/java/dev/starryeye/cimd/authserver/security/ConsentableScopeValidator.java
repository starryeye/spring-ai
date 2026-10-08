package dev.starryeye.cimd.authserver.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

import java.util.function.Consumer;

/**
 * consent할 scope 없이 온 authorization request를 거부한다.
 *
 * <p>이 서버의 client는 모두 자기 문서로 스스로 등록한다.
 * 누구나 문서를 올려 {@code private_key_jwt} client가 될 수 있으므로, 미리 확인한 client가 없다.
 * Spring은 요청한 scope가 {@code openid} 하나면 consent를 건너뛴다.
 * 그대로 두면 아무도 확인하지 않은 client가 consent 화면 없이 code, id_token, refresh token을 받는다.
 * 그래서 인증 방식과 상관없이 모든 client에게 {@code openid} 말고 scope가 하나 이상 있기를 요구한다.
 * 그러면 모든 authorization request가 consent 화면을 거치거나, 사용자가 전에 그 화면에서 허락한 scope 안에 든다.
 * public client는 전에 허락한 consent도 저장하지 않으므로({@link PublicClientConsentService}), 매번 화면을 거친다(OAuth 2.1 §7.3.1).
 *
 * <p>RFC 6749 §3.3은 scope 없는 요청을 기본값으로 처리하거나 거부하게 한다(MUST).
 * 이 서버는 거부하므로, scope를 생략한 요청도 {@code openid} 하나뿐인 요청처럼 {@code invalid_scope}다.
 * Spring은 사용자가 고른 scope가 없으면 {@code openid}도 붙이지 않고 {@code access_denied}로 끝낸다.
 * 그래서 consent 화면을 강제로 띄우지 않고, consent 판정 전에 거부한다.
 */
public class ConsentableScopeValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	static final String RFC_6749_SCOPE = "https://www.rfc-editor.org/rfc/rfc6749#section-3.3";

	@Override
	public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
		OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
		boolean hasConsentableScope = request.getScopes().stream()
				.anyMatch(scope -> !OidcScopes.OPENID.equals(scope));
		if (!hasConsentableScope) {
			OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
					"The request must include at least one scope other than openid", RFC_6749_SCOPE);
			throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
		}
	}
}
