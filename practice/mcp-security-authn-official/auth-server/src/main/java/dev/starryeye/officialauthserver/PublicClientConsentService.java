package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * public client의 consent는 기록하지 않는다(OAuth 2.1 §7.3.1).
 *
 * <p>Authorization Server가 client의 신원을 확인할 수 없다면, 사용자가 같은 client_id에 전에
 * consent했더라도 그 요청을 처음처럼 처리하는 것이 좋다(SHOULD).
 * consent 화면 없이 자동으로 처리하지 않는다(SHOULD NOT).
 * 비밀이 없는 public client({@code none})는 누구나 그 client_id를 댈 수 있으므로 신원을 확인할 수 없다.
 *
 * <p>Spring은 한 번 받은 consent를 이 서비스에 저장한다.
 * 다음 authorization request에서 저장된 consent가 요청한 scope를 모두 덮으면 consent 화면을 건너뛴다.
 * public client라면 consent를 저장하지 않고, 조회에도 {@code null}을 돌려준다.
 * consent를 받은 그 요청은 저장 여부와 상관없이 방금 고른 scope로 code를 발급한다.
 * Spring이 저장과 상관없이 consent를 건너뛰는 경우(scope가 {@code openid} 하나)는
 * {@link PublicClientScopeValidator}가 막는다.
 * confidential client의 consent는 감싼 서비스에 그대로 맡긴다.
 */
public class PublicClientConsentService implements OAuth2AuthorizationConsentService {

	private final OAuth2AuthorizationConsentService delegate;

	private final RegisteredClientRepository registeredClientRepository;

	public PublicClientConsentService(OAuth2AuthorizationConsentService delegate,
			RegisteredClientRepository registeredClientRepository) {
		this.delegate = delegate;
		this.registeredClientRepository = registeredClientRepository;
	}

	@Override
	public void save(OAuth2AuthorizationConsent authorizationConsent) {
		if (isPublicClient(authorizationConsent.getRegisteredClientId())) {
			return;
		}
		this.delegate.save(authorizationConsent);
	}

	@Override
	public void remove(OAuth2AuthorizationConsent authorizationConsent) {
		this.delegate.remove(authorizationConsent);
	}

	@Override
	public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
		if (isPublicClient(registeredClientId)) {
			return null;
		}
		return this.delegate.findById(registeredClientId, principalName);
	}

	private boolean isPublicClient(String registeredClientId) {
		RegisteredClient client = this.registeredClientRepository.findById(registeredClientId);
		return client != null
				&& client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
	}
}
