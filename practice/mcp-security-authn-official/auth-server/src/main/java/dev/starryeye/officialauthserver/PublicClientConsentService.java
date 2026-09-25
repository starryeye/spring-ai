package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * 공개 클라이언트의 동의는 기록하지 않는다(OAuth 2.1 §7.3.1).
 *
 * <p>인가 서버는 클라이언트의 신원을 확인할 수 없으면, 사용자가 같은 client_id 에 이전에
 * 동의했더라도 그 요청을 처음처럼 처리하는 것이 좋다(SHOULD) — 동의 화면 없이 자동으로
 * 처리하지 않는다(SHOULD NOT). 비밀이 없는 공개 클라이언트({@code none})는 client_id 를
 * 누구나 댈 수 있으므로 신원을 확인할 수 없다.
 *
 * <p>Spring 은 한 번 받은 동의를 이 서비스에 저장하고, 다음 인가 요청에서 저장된 동의가
 * 요청 scope 를 모두 덮으면 동의 화면을 건너뛴다. 공개 클라이언트면 저장하지 않고 조회에도
 * {@code null} 을 돌려준다. 동의를 받은 그 요청은 저장 여부와 상관없이 방금 고른 scope 로 코드를
 * 발급한다. Spring 이 저장과 무관하게 건너뛰는 경로(scope 가 {@code openid} 하나)는
 * {@link PublicClientScopeValidator} 가 막는다. 기밀 클라이언트는 위임한 서비스에 그대로 남긴다.
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
