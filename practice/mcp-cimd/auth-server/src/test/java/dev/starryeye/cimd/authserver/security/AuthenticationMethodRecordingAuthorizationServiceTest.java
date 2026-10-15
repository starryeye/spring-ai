package dev.starryeye.cimd.authserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationMethodRecordingAuthorizationServiceTest {

	static final String CLIENT_ID = "https://localhost:8172/oauth/client.json";

	static final String ATTRIBUTE = AuthenticationMethodRecordingAuthorizationService.CLIENT_AUTHENTICATION_METHOD;

	MutableRegisteredClientRepository clients = new MutableRegisteredClientRepository();

	AuthenticationMethodRecordingAuthorizationService service = new AuthenticationMethodRecordingAuthorizationService(
			new InMemoryOAuth2AuthorizationService(), this.clients);

	static RegisteredClient client(ClientAuthenticationMethod method) {
		return RegisteredClient.withId(CLIENT_ID).clientId(CLIENT_ID)
				.clientAuthenticationMethod(method)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8170/login/oauth2/code/authserver")
				.build();
	}

	static OAuth2Authorization grant(RegisteredClient client) {
		return OAuth2Authorization.withRegisteredClient(client)
				.id("grant-1")
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.attribute("state", "s")
				.build();
	}

	@Test
	void 처음_저장할_때_client의_지금_인증_방식을_남긴다() {
		this.clients.put(client(ClientAuthenticationMethod.PRIVATE_KEY_JWT));

		this.service.save(grant(client(ClientAuthenticationMethod.PRIVATE_KEY_JWT)));

		assertThat((String) this.service.findById("grant-1").getAttribute(ATTRIBUTE)).isEqualTo("private_key_jwt");
	}

	@Test
	void 다시_저장할_때는_처음_기록을_지킨다() {
		RegisteredClient privateKeyJwt = client(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		this.clients.put(privateKeyJwt);
		this.service.save(grant(privateKeyJwt));

		// 그사이 문서가 none으로 바뀌었고, 다시 저장하는 authorization에는 기록이 빠져 있다.
		this.clients.put(client(ClientAuthenticationMethod.NONE));
		this.service.save(grant(privateKeyJwt));

		assertThat((String) this.service.findById("grant-1").getAttribute(ATTRIBUTE)).isEqualTo("private_key_jwt");
	}

	@Test
	void client를_찾지_못하면_기록하지_않는다() {
		// 기록이 없는 grant는 public client로 refresh할 수 없다(fail-closed).
		this.service.save(grant(client(ClientAuthenticationMethod.NONE)));

		assertThat((Object) this.service.findById("grant-1").getAttribute(ATTRIBUTE)).isNull();
	}
}
