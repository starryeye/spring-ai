package dev.starryeye.cimd.authserver.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsentControllerTest {

	static final String CHATGPT = "https://localhost:8172/oauth/client.json";

	static final String LOCAL_REDIRECT = "http://localhost:8170/login/oauth2/code/authserver";

	static final String WEB = "https://app.example.com/oauth/client.json";

	static final String WEB_REDIRECT = "https://app.example.com/callback";

	static final String LOOPBACK_WARNING = "이 기기의 주소";

	InMemoryOAuth2AuthorizationConsentService consents = new InMemoryOAuth2AuthorizationConsentService();

	InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService();

	MockMvc mockMvc;

	static RegisteredClient client(String id, String name, String redirectUri) {
		return RegisteredClient.withId(id).clientId(id).clientName(name)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri(redirectUri)
				.scopes(scopes -> scopes.addAll(Set.of("openid", "products:read", "products:write")))
				.build();
	}

	@BeforeEach
	void setUp() {
		RegisteredClient local = client(CHATGPT, "Shop Agent (ChatGPT형)", LOCAL_REDIRECT);
		RegisteredClient web = client(WEB, "<b>Web</b>", WEB_REDIRECT);
		대기중인_authorization(local, "local-state", LOCAL_REDIRECT);
		대기중인_authorization(web, "web-state", WEB_REDIRECT);
		this.mockMvc = MockMvcBuilders.standaloneSetup(new ConsentController(
				new InMemoryRegisteredClientRepository(local, web), this.consents, this.authorizations)).build();
	}

	void 대기중인_authorization(RegisteredClient client, String state, String redirectUri) {
		OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("http://localhost:9060/oauth2/authorize")
				.clientId(client.getClientId())
				.redirectUri(redirectUri)
				.scopes(Set.of("openid", "products:read"))
				.state("client-state")
				.build();
		this.authorizations.save(OAuth2Authorization.withRegisteredClient(client)
				.principalName("user")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.attribute(OAuth2ParameterNames.STATE, state)
				.attribute(OAuth2AuthorizationRequest.class.getName(), request)
				.build());
	}

	String 화면(String clientId, String scope, String state) throws Exception {
		return this.mockMvc.perform(get(ConsentController.PATH)
						.principal(new TestingAuthenticationToken("user", null))
						.param("client_id", clientId).param("scope", scope).param("state", state))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void client_이름과_문서_host와_redirect_host를_보여_준다() throws Exception {
		String html = 화면(CHATGPT, "openid products:read", "local-state");

		assertThat(html).contains("Shop Agent (ChatGPT형)", "localhost:8172", "localhost:8170");
		assertThat(html).contains("name=\"state\" value=\"local-state\"");
		assertThat(html).contains("name=\"client_id\" value=\"" + CHATGPT + "\"");
		assertThat(html).contains("name=\"scope\" value=\"products:read\"");
		// openid는 consent 대상이 아니다. 허락한 scope가 있으면 Spring이 다시 붙인다.
		assertThat(html).doesNotContain("value=\"openid\"");
	}

	@Test
	void redirect가_loopback뿐이면_경고한다() throws Exception {
		assertThat(화면(CHATGPT, "openid products:read", "local-state")).contains(LOOPBACK_WARNING);
	}

	@Test
	void redirect가_공개_주소면_경고하지_않는다() throws Exception {
		assertThat(화면(WEB, "openid products:read", "web-state")).doesNotContain(LOOPBACK_WARNING);
	}

	@Test
	void 값은_HTML로_escape한다() throws Exception {
		String html = 화면(WEB, "openid products:read", "web-state");

		assertThat(html).contains("&lt;b&gt;Web&lt;/b&gt;").doesNotContain("<b>Web</b>");
	}

	@Test
	void 이미_허락한_scope는_체크박스_대신_목록으로_보여_준다() throws Exception {
		this.consents.save(OAuth2AuthorizationConsent.withId(CHATGPT, "user").scope("products:read").build());

		String html = 화면(CHATGPT, "openid products:read products:write", "local-state");

		assertThat(html).contains("name=\"scope\" value=\"products:write\"");
		assertThat(html).doesNotContain("name=\"scope\" value=\"products:read\"");
		assertThat(html).contains("이미 허락한 권한").contains("products:read");
	}

	@Test
	void authority가_없는_redirect_주소도_주소_전체를_보여_주고_200이다() throws Exception {
		String opaqueClientId = "https://opaque.example/client.json";
		RegisteredClient opaque = client(opaqueClientId, "Opaque", "a:b");
		대기중인_authorization(opaque, "opaque-state", "a:b");
		MockMvc opaqueMockMvc = MockMvcBuilders.standaloneSetup(new ConsentController(
				new InMemoryRegisteredClientRepository(opaque), this.consents, this.authorizations)).build();

		String html = opaqueMockMvc.perform(get(ConsentController.PATH)
						.principal(new TestingAuthenticationToken("user", null))
						.param("client_id", opaqueClientId).param("scope", "openid products:read")
						.param("state", "opaque-state"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<code>a:b</code>").doesNotContain(LOOPBACK_WARNING);
	}

	@Test
	void 다른_client의_state면_redirect_host를_보여_주지_않는다() throws Exception {
		// web-state는 WEB client가 시작한 authorization의 state다. CHATGPT 화면에 붙여 와도 그 host가 나오면 안 된다.
		String html = 화면(CHATGPT, "openid products:read", "web-state");

		assertThat(html).doesNotContain("app.example.com").doesNotContain("허락하면 돌아갈 주소");
		assertThat(html).contains("localhost:8172");
	}

	@Test
	void 모르는_client면_400이다() throws Exception {
		this.mockMvc.perform(get(ConsentController.PATH)
						.principal(new TestingAuthenticationToken("user", null))
						.param("client_id", "https://unknown.example/client.json")
						.param("scope", "products:read").param("state", "s"))
				.andExpect(status().isBadRequest());
	}
}
