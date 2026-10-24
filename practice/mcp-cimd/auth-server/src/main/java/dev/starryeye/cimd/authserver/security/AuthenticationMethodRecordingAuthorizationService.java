package dev.starryeye.cimd.authserver.security;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.Objects;
import java.util.Set;

/**
 * grant를 처음 저장할 때 그 client가 문서에 선언한 인증 방식({@code none} 또는 {@code private_key_jwt})을 grant에 남긴다.
 *
 * <p>CIMD client의 인증 방식은 지금 문서가 정한다.
 * 문서는 바뀔 수 있다.
 * {@code private_key_jwt}로 받은 refresh token을 훔친 사람이 문서 host까지 손에 넣으면(호스팅 계정 탈취, 만료 도메인 재등록 등),
 * 문서를 {@code none}으로 바꾸고 cache가 끝난 뒤 {@code client_id}만으로 refresh할 수 있다.
 * private key 없이 grant를 이어 가는 것이다.
 * 그래서 grant를 받을 때의 인증 방식을 기록하고, {@link PublicClientRefreshTokenAuthenticationProvider}가 refresh 때 비교한다.
 *
 * <p>처음 저장은 authorization request 단계(consent를 기다리거나 code를 발급할 때)다.
 * 그때 문서는 막 가져온 상태다.
 * 그 뒤의 저장(code 교환, refresh)은 처음 기록을 그대로 지킨다.
 * 다시 저장하는 authorization에 기록이 빠졌거나 달라도 처음 값을 쓴다.
 * client를 찾지 못했거나 인증 방식이 하나가 아니면 기록하지 않는다.
 * 기록이 없는 grant는 public client로 refresh할 수 없다(fail-closed).
 */
public class AuthenticationMethodRecordingAuthorizationService implements OAuth2AuthorizationService {

	/**
	 * grant에 남기는 attribute 이름이다.
	 * 값은 인증 방식의 문자열({@code none}, {@code private_key_jwt})이다.
	 */
	public static final String CLIENT_AUTHENTICATION_METHOD =
			AuthenticationMethodRecordingAuthorizationService.class.getName() + ".CLIENT_AUTHENTICATION_METHOD";

	private final OAuth2AuthorizationService delegate;

	private final RegisteredClientRepository clients;

	public AuthenticationMethodRecordingAuthorizationService(OAuth2AuthorizationService delegate,
			RegisteredClientRepository clients) {
		this.delegate = delegate;
		this.clients = clients;
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		OAuth2Authorization previous = this.delegate.findById(authorization.getId());
		String recorded = (previous != null) ? previous.getAttribute(CLIENT_AUTHENTICATION_METHOD)
				: currentMethod(authorization.getRegisteredClientId());
		if (recorded != null && !Objects.equals(recorded, authorization.getAttribute(CLIENT_AUTHENTICATION_METHOD))) {
			authorization = OAuth2Authorization.from(authorization)
					.attribute(CLIENT_AUTHENTICATION_METHOD, recorded)
					.build();
		}
		this.delegate.save(authorization);
	}

	@Override
	public void remove(OAuth2Authorization authorization) {
		this.delegate.remove(authorization);
	}

	@Override
	public OAuth2Authorization findById(String id) {
		return this.delegate.findById(id);
	}

	@Override
	public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
		return this.delegate.findByToken(token, tokenType);
	}

	private String currentMethod(String registeredClientId) {
		RegisteredClient client = this.clients.findById(registeredClientId);
		if (client == null) {
			return null;
		}
		Set<ClientAuthenticationMethod> methods = client.getClientAuthenticationMethods();
		return (methods.size() == 1) ? methods.iterator().next().getValue() : null;
	}
}
