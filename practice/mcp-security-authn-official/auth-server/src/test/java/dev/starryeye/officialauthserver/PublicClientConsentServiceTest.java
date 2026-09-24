package dev.starryeye.officialauthserver;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import static org.assertj.core.api.Assertions.assertThat;

class PublicClientConsentServiceTest {

	static final RegisteredClient PUBLIC_CLIENT = client("public-client", ClientAuthenticationMethod.NONE);

	static final RegisteredClient CONFIDENTIAL_CLIENT = client("confidential-client",
			ClientAuthenticationMethod.CLIENT_SECRET_BASIC);

	final InMemoryOAuth2AuthorizationConsentService delegate = new InMemoryOAuth2AuthorizationConsentService();

	final PublicClientConsentService service = new PublicClientConsentService(this.delegate,
			new InMemoryRegisteredClientRepository(PUBLIC_CLIENT, CONFIDENTIAL_CLIENT));

	@Test
	void 공개_클라이언트의_동의는_저장하지_않는다() {
		this.service.save(consent(PUBLIC_CLIENT));

		assertThat(this.delegate.findById(PUBLIC_CLIENT.getId(), "user")).isNull();
		assertThat(this.service.findById(PUBLIC_CLIENT.getId(), "user")).isNull();
	}

	@Test
	void 기밀_클라이언트의_동의는_그대로_저장한다() {
		this.service.save(consent(CONFIDENTIAL_CLIENT));

		assertThat(this.service.findById(CONFIDENTIAL_CLIENT.getId(), "user")).isNotNull();
	}

	@Test
	void 공개_클라이언트의_동의가_이미_저장돼_있어도_찾지_않는다() {
		// 이 서비스를 쓰기 전에 남은 기록이 있어도 공개 클라이언트에는 쓰지 않는다.
		this.delegate.save(consent(PUBLIC_CLIENT));

		assertThat(this.service.findById(PUBLIC_CLIENT.getId(), "user")).isNull();
	}

	static RegisteredClient client(String clientId, ClientAuthenticationMethod method) {
		return RegisteredClient.withId(clientId + "-id")
				.clientId(clientId)
				.clientAuthenticationMethod(method)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://127.0.0.1:8123/callback")
				.scope("profile")
				.build();
	}

	static OAuth2AuthorizationConsent consent(RegisteredClient client) {
		return OAuth2AuthorizationConsent.withId(client.getId(), "user").scope("profile").build();
	}
}
