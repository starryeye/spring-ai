package dev.starryeye.cimd.authserver.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * {@link PublicClientRefreshTokenAuthenticationConverter}가 만든 refresh 요청의 public client 인증을 마친다.
 *
 * <p>client는 문서에 {@code none}을 선언했어야 한다. {@code private_key_jwt}를 선언한 client가
 * {@code client_id}만으로 오면 거절한다. 그렇지 않으면 key 없이 그 client 행세를 할 수 있다.
 * refresh token이 이 client의 것인지, rotation으로 이미 버린 것인지는 Spring의
 * {@code OAuth2RefreshTokenAuthenticationProvider}가 이어서 검사한다.
 *
 * <p>authorization code 요청은 맡지 않는다. Spring의 public client 인증은 그 단계에서 PKCE를 검사하므로,
 * 이 provider가 대신 인증하면 PKCE 검사를 건너뛰게 된다.
 */
public final class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

	private final RegisteredClientRepository clients;

	public PublicClientRefreshTokenAuthenticationProvider(RegisteredClientRepository clients) {
		this.clients = clients;
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
		if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
				|| !AuthorizationGrantType.REFRESH_TOKEN.getValue()
						.equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE))) {
			return null;
		}
		RegisteredClient client = this.clients.findByClientId(String.valueOf(token.getPrincipal()));
		if (client == null) {
			throw invalidClient("모르는 client다");
		}
		if (!client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
			throw invalidClient("문서가 none을 선언하지 않은 client다");
		}
		if (!client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			throw invalidClient("refresh_token grant가 없는 client다");
		}
		return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static OAuth2AuthenticationException invalidClient(String description) {
		return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, description, null));
	}
}
