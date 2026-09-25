package dev.starryeye.authserver;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

import java.util.function.Consumer;

/**
 * public client 가 consent 할 scope 없이 오면 인가 요청을 거부한다.
 *
 * <p>OAuth 2.1 §7.3.1 — 신원을 확인할 수 없는 client 의 요청은 이전 consent 가 있어도 처음처럼
 * 처리하고, consent 화면 없이 자동으로 처리하지 않는다(SHOULD NOT). Spring 은 요청 scope 가
 * {@code openid} 하나면 consent 를 건너뛰고, 사용자가 고른 scope 가 없으면 {@code openid} 도 붙이지
 * 않고 {@code access_denied} 로 끝낸다. 그래서 consent 를 강제하는 대신 consent 할 scope 가 없는
 * 요청을 consent 판정 전에 거부한다. community 모듈의 McpNoScopeClientConsentNotRequired 는 scope 가
 * 없는 요청도 consent 를 건너뛴다 — 이 검증기가 그 경로도 막는다.
 *
 * <p>scope 를 생략한 요청은 RFC 6749 §3.3 에 따라 {@code invalid_scope} 다(기본값으로 처리하거나
 * 거부 — MUST). {@code openid} 하나뿐인 요청도 같은 오류로 거부한다.
 */
public class PublicClientScopeValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    static final String RFC_6749_SCOPE = "https://www.rfc-editor.org/rfc/rfc6749#section-3.3";

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        if (!context.getRegisteredClient().getClientAuthenticationMethods()
                .contains(ClientAuthenticationMethod.NONE)) {
            return;
        }
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        boolean hasConsentableScope = request.getScopes().stream()
                .anyMatch(scope -> !OidcScopes.OPENID.equals(scope));
        if (!hasConsentableScope) {
            OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
                    "A public client must request at least one scope other than openid", RFC_6749_SCOPE);
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
        }
    }
}
