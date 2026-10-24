package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
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
		// 이 서비스를 쓰기 전에 남은 기록이 있어도 public client에는 쓰지 않는다.
		this.delegate.save(consent(PUBLIC_CLIENT));

		assertThat(this.service.findById(PUBLIC_CLIENT.getId(), "user")).isNull();
	}

	@Test
	void client를_찾지_못하면_public_client처럼_저장하지도_찾지도_않는다() {
		// CIMD 저장소는 문서를 가져오지 못하면 null을 돌려준다.
		// 이때 confidential client로 보면 consent를 건너뛸 수 있다.
		RegisteredClient unknown = client("unknown-client", ClientAuthenticationMethod.PRIVATE_KEY_JWT);

		this.service.save(consent(unknown));
		assertThat(this.delegate.findById(unknown.getId(), "user")).isNull();

		this.delegate.save(consent(unknown));
		assertThat(this.service.findById(unknown.getId(), "user")).isNull();
	}

	@Test
	void 기밀_클라이언트의_동의에는_redirect_주소의_hash를_함께_남기고_scope로는_보이지_않는다() {
		this.service.save(consent(CONFIDENTIAL_CLIENT));

		OAuth2AuthorizationConsent stored = this.delegate.findById(CONFIDENTIAL_CLIENT.getId(), "user");
		assertThat(stored.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.anyMatch(authority -> authority.startsWith(PublicClientConsentService.REDIRECT_URIS_AUTHORITY_PREFIX));
		// consent 화면과 Spring은 getScopes()만 본다.
		// hash는 scope가 아니다.
		assertThat(this.service.findById(CONFIDENTIAL_CLIENT.getId(), "user").getScopes()).containsExactly("profile");
	}

	@Test
	void 문서의_redirect_주소가_바뀌면_저장된_동의가_없는_것처럼_본다() {
		MutableRegisteredClientRepository clients = new MutableRegisteredClientRepository();
		clients.put(CONFIDENTIAL_CLIENT);
		PublicClientConsentService service = new PublicClientConsentService(this.delegate, clients);
		service.save(consent(CONFIDENTIAL_CLIENT));
		assertThat(service.findById(CONFIDENTIAL_CLIENT.getId(), "user")).isNotNull();

		// 문서 host를 장악한 쪽이 redirect 주소를 바꿨다.
		// 저장된 consent로 새 주소에 code가 나가면 안 된다.
		clients.put(RegisteredClient.from(CONFIDENTIAL_CLIENT)
				.redirectUri("https://attacker.example/cb")
				.build());

		assertThat(service.findById(CONFIDENTIAL_CLIENT.getId(), "user")).isNull();
	}

	@Test
	void redirect_주소의_hash가_없는_동의는_쓰지_않는다() {
		// 이 규칙 전에 저장된 consent처럼, 어느 주소에 대해 받은 consent인지 모른다.
		this.delegate.save(consent(CONFIDENTIAL_CLIENT));

		assertThat(this.service.findById(CONFIDENTIAL_CLIENT.getId(), "user")).isNull();
	}

	@Test
	void 다시_저장해도_redirect_주소의_hash는_하나만_남는다() {
		this.service.save(consent(CONFIDENTIAL_CLIENT));
		OAuth2AuthorizationConsent stepUp = OAuth2AuthorizationConsent
				.from(this.service.findById(CONFIDENTIAL_CLIENT.getId(), "user"))
				.scope("orders:write")
				.build();

		this.service.save(stepUp);

		assertThat(this.delegate.findById(CONFIDENTIAL_CLIENT.getId(), "user").getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.filteredOn(authority -> authority.startsWith(PublicClientConsentService.REDIRECT_URIS_AUTHORITY_PREFIX))
				.hasSize(1);
		assertThat(this.service.findById(CONFIDENTIAL_CLIENT.getId(), "user").getScopes())
				.containsExactlyInAnyOrder("profile", "orders:write");
	}

	static RegisteredClient client(String clientId, ClientAuthenticationMethod method) {
		return RegisteredClient.withId(clientId + "-id")
				.clientId(clientId)
				.clientAuthenticationMethod(method)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver")
				.scope("profile")
				.build();
	}

	static OAuth2AuthorizationConsent consent(RegisteredClient client) {
		return OAuth2AuthorizationConsent.withId(client.getId(), "user").scope("profile").build();
	}
}
