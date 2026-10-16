package dev.starryeye.cimd.authserver.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * {@link PublicClientRefreshTokenAuthenticationConverter}가 만든 refresh 요청의 public client 인증을 마친다.
 *
 * <p>client는 문서에 {@code none}을 선언했어야 한다. {@code private_key_jwt}를 선언한 client가
 * {@code client_id}만으로 오면 거절한다. 그렇지 않으면 key 없이 그 client 행세를 할 수 있다.
 *
 * <p>지금 문서만 보면 문서를 {@code none}으로 바꾼 쪽이 {@code private_key_jwt}로 받은 grant를 key 없이 이어 갈 수 있다.
 * 그래서 refresh token의 grant가 이 client의 것이면, grant를 받을 때 기록한 인증 방식
 * ({@link AuthenticationMethodRecordingAuthorizationService})도 {@code none}이어야 한다. 기록이 없어도 거절한다.
 * grant를 찾지 못했거나 다른 client의 grant면 여기서 판단하지 않는다. Spring의
 * {@code OAuth2RefreshTokenAuthenticationProvider}가 이어서 {@code invalid_grant}로 끝낸다.
 * rotation으로 이미 버린 token이면 이 조회에서 {@link RefreshTokenReuseDetectingAuthorizationService}가 grant를 끊는다.
 *
 * <p>authorization code 요청은 맡지 않는다. Spring의 public client 인증은 그 단계에서 PKCE를 검사하므로,
 * 이 provider가 대신 인증하면 PKCE 검사를 건너뛰게 된다.
 */
public final class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

	private final RegisteredClientRepository clients;

	private final OAuth2AuthorizationService authorizations;

	public PublicClientRefreshTokenAuthenticationProvider(RegisteredClientRepository clients,
			OAuth2AuthorizationService authorizations) {
		this.clients = clients;
		this.authorizations = authorizations;
	}

	/**
	 * Spring의 {@code PublicClientAuthenticationProvider}가 이 provider의 refresh 요청을 다시 맡지 않게 감싼다.
	 * {@code ProviderManager}는 한 provider가 던진 인증 예외를 기억해 두고 다음 provider를 계속 부른다.
	 * Spring의 public client 인증은 refresh 요청에 {@code code_verifier}가 없다며 {@code invalid_grant}를 던지므로,
	 * 이 provider가 던진 {@code invalid_client}(401)가 {@code invalid_grant}(400)로 덮인다.
	 * Spring의 public client 인증은 refresh 요청을 통과시킬 수 없으므로, 건너뛰게 해도 받아들이는 요청은 늘지 않는다.
	 */
	public static AuthenticationProvider skippingRefreshRequests(AuthenticationProvider publicClientProvider) {
		return new AuthenticationProvider() {
			@Override
			public Authentication authenticate(Authentication authentication) {
				return isPublicClientRefresh(authentication) ? null : publicClientProvider.authenticate(authentication);
			}

			@Override
			public boolean supports(Class<?> authentication) {
				return publicClientProvider.supports(authentication);
			}
		};
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		if (!isPublicClientRefresh(authentication)) {
			return null;
		}
		OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
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
		if (notGrantedAsPublicClient(client, token.getAdditionalParameters().get(OAuth2ParameterNames.REFRESH_TOKEN))) {
			throw invalidClient("none으로 받은 grant가 아니다");
		}
		return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
	}

	/**
	 * refresh token의 grant가 이 client의 것인데 {@code none}으로 받은 기록이 없으면 참이다.
	 * grant를 찾지 못했거나 다른 client의 grant면 거짓을 돌려주고 Spring의 검사에 맡긴다.
	 * 이 조회는 refresh 요청의 첫 조회라서 {@link RefreshTokenReuseDetectingAuthorizationService}가 grant의 lock을 잡는다.
	 * 이어지는 Spring의 조회는 같은 요청 안의 두 번째 조회다.
	 */
	private boolean notGrantedAsPublicClient(RegisteredClient client, Object refreshToken) {
		if (!(refreshToken instanceof String value)) {
			return false;
		}
		OAuth2Authorization grant = this.authorizations.findByToken(value, OAuth2TokenType.REFRESH_TOKEN);
		if (grant == null || !client.getId().equals(grant.getRegisteredClientId())) {
			return false;
		}
		return !ClientAuthenticationMethod.NONE.getValue()
				.equals(grant.getAttribute(AuthenticationMethodRecordingAuthorizationService.CLIENT_AUTHENTICATION_METHOD));
	}

	/** {@link PublicClientRefreshTokenAuthenticationConverter}가 만든 요청인지 본다. */
	private static boolean isPublicClientRefresh(Authentication authentication) {
		return authentication instanceof OAuth2ClientAuthenticationToken token
				&& ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
				&& AuthorizationGrantType.REFRESH_TOKEN.getValue()
						.equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE));
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static OAuth2AuthenticationException invalidClient(String description) {
		return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, description, null));
	}
}
