package dev.starryeye.modern.authserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;

/**
 * introspection endpoint가 token을 발급받은 client에게만 그 token의 내용을 알려 준다(RFC 7662 §4).
 *
 * <p>Spring의 {@code OAuth2TokenIntrospectionAuthenticationProvider}는 인증된 client면 token이 누구 것인지 묻지 않고 내용을 돌려준다.
 * CIMD에서는 누구나 {@code private_key_jwt} 문서를 올려 인증된 client가 될 수 있다.
 * 그대로 두면 손에 넣은 token(특히 겉으로는 알 수 없는 refresh token)이 살아 있는지, 누구의 어떤 scope인지 묻는 창구가 된다.
 * 그래서 token을 발급받은 client가 물을 때만 Spring에 맡기고, 그 밖에는 내용 없이 {@code active: false}로 답한다.
 * RFC 7662 §2.2도 token이 있는지 감추려면 {@code active: false}만 돌려주라고 한다.
 *
 * <p>Spring은 token 주인의 client를 다시 찾고, 찾지 못하면 {@code Assert}로 실패해 500이 된다.
 * CIMD 저장소는 문서를 가져오지 못하면 client를 찾지 못한다. 다른 client의 token은 그 조회까지 가지 않고,
 * 자기 token인데 그사이 문서를 가져오지 못한 경우도 {@code active: false}로 끝낸다.
 *
 * <p>MCP Server는 access token(JWT)을 직접 검증하므로 이 endpoint를 쓰지 않는다.
 */
public final class OwnTokenIntrospectionAuthenticationProvider implements AuthenticationProvider {

	private static final Logger log = LoggerFactory.getLogger(OwnTokenIntrospectionAuthenticationProvider.class);

	private final AuthenticationProvider delegate;

	private final OAuth2AuthorizationService authorizations;

	public OwnTokenIntrospectionAuthenticationProvider(AuthenticationProvider delegate,
			OAuth2AuthorizationService authorizations) {
		this.delegate = delegate;
		this.authorizations = authorizations;
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		OAuth2TokenIntrospectionAuthenticationToken request = (OAuth2TokenIntrospectionAuthenticationToken) authentication;
		OAuth2ClientAuthenticationToken caller = authenticatedClient(request);
		OAuth2Authorization authorization = this.authorizations.findByToken(request.getToken(), null);
		if (authorization == null
				|| !caller.getRegisteredClient().getId().equals(authorization.getRegisteredClientId())) {
			return inactive(request, caller);
		}
		try {
			return this.delegate.authenticate(request);
		}
		catch (IllegalArgumentException ex) {
			log.warn("token 주인의 client를 다시 찾지 못해 active: false로 답한다 (이유={})", ex.getMessage());
			return inactive(request, caller);
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2TokenIntrospectionAuthenticationToken.class.isAssignableFrom(authentication);
	}

	/** Spring의 introspection provider와 같은 기준으로 요청한 client가 인증됐는지 본다. */
	private static OAuth2ClientAuthenticationToken authenticatedClient(OAuth2TokenIntrospectionAuthenticationToken request) {
		if (request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client && client.isAuthenticated()) {
			return client;
		}
		throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
	}

	private static Authentication inactive(OAuth2TokenIntrospectionAuthenticationToken request,
			OAuth2ClientAuthenticationToken caller) {
		return new OAuth2TokenIntrospectionAuthenticationToken(request.getToken(), caller,
				OAuth2TokenIntrospection.builder().build());
	}
}
