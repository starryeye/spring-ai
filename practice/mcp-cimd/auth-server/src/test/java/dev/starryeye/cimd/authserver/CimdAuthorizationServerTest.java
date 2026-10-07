package dev.starryeye.cimd.authserver;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.cimd.authserver.web.ConsentController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미리 등록하지 않은 두 client가 CIMD 문서만으로 code와 token을 받는 흐름을 검증한다.
 * 문서와 JWKS는 {@link CimdTestConfig}가 메모리에서 돌려준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CimdTestConfig.class)
class CimdAuthorizationServerTest {

	static final String ISSUER = "http://localhost:9060";

	static final String TOKEN_ENDPOINT = ISSUER + "/oauth2/token";

	static final String RESOURCE = "http://localhost:8171/mcp";

	static final String OTHER_RESOURCE = "http://localhost:9999/mcp";

	static final String CHATGPT = TestClientDocuments.CHATGPT;

	static final String CLAUDE = TestClientDocuments.CLAUDE;

	static final String REDIRECT_URI = TestClientDocuments.REDIRECT_URI;

	// RFC 7636 부록 B의 예시 값이다.
	static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

	static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	OAuth2AuthorizationConsentService consents;

	MockHttpSession session;

	@BeforeEach
	void login한다() throws Exception {
		this.session = (MockHttpSession) this.mockMvc.perform(formLogin().user("user").password("password"))
				.andExpect(status().is3xxRedirection())
				.andReturn().getRequest().getSession(false);
		OAuth2AuthorizationConsent consent = this.consents.findById(CHATGPT, "user");
		if (consent != null) {
			this.consents.remove(consent);
		}
	}

	static UriComponentsBuilder authorizationRequestBuilder(String clientId, String scope) {
		return UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", clientId)
				.queryParam("redirect_uri", REDIRECT_URI)
				.queryParam("scope", scope)
				.queryParam("state", "state-1")
				.queryParam("code_challenge", CODE_CHALLENGE)
				.queryParam("code_challenge_method", "S256")
				.queryParam("resource", RESOURCE);
	}

	static URI authorizationRequest(String clientId, String scope) {
		return authorizationRequestBuilder(clientId, scope).encode().build().toUri();
	}

	/** authorization request를 보내고 redirect 주소를 돌려준다. consent가 필요하면 consent 화면 주소다. */
	String redirect(URI uri) throws Exception {
		return this.mockMvc.perform(get(uri).session(this.session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
	}

	String consent화면(String location) throws Exception {
		assertThat(location).contains(ConsentController.PATH);
		return this.mockMvc.perform(get(URI.create(location)).session(this.session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	/** consent 화면의 hidden state다. 대기 중인 authorization을 찾으려고 서버가 새로 만든 값이다. */
	static String state(String html) {
		Matcher matcher = Pattern.compile("name=\"state\" value=\"([^\"]*)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	/** consent 화면이 나오면 approvedScopes를 골라 보낸다. 이미 허락한 scope만 요청했으면 바로 redirect를 받는다. */
	UriComponents 인가(String clientId, String scope, String... approvedScopes) throws Exception {
		String location = redirect(authorizationRequest(clientId, scope));
		if (location.contains(ConsentController.PATH)) {
			MockHttpServletRequestBuilder consent = post("/oauth2/authorize").session(this.session)
					.param("client_id", clientId)
					.param("state", state(consent화면(location)));
			for (String approved : approvedScopes) {
				consent.param("scope", approved);
			}
			location = this.mockMvc.perform(consent)
					.andExpect(status().is3xxRedirection())
					.andReturn().getResponse().getRedirectedUrl();
		}
		return UriComponentsBuilder.fromUriString(location).build();
	}

	static String 응답값(UriComponents response, String name) {
		String value = response.getQueryParams().getFirst(name);
		return (value == null) ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
	}

	String code(String clientId) throws Exception {
		UriComponents response = 인가(clientId, "openid products:read", "products:read");
		assertThat(response.toUriString()).startsWith(REDIRECT_URI);
		return 응답값(response, "code");
	}

	static MultiValueMap<String, String> codeExchange(String clientId, String code) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "authorization_code");
		parameters.add("client_id", clientId);
		parameters.add("code", code);
		parameters.add("redirect_uri", REDIRECT_URI);
		parameters.add("code_verifier", CODE_VERIFIER);
		parameters.add("resource", RESOURCE);
		return parameters;
	}

	static MultiValueMap<String, String> refresh(String clientId, String refreshToken) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "refresh_token");
		parameters.add("client_id", clientId);
		parameters.add("refresh_token", refreshToken);
		parameters.add("resource", RESOURCE);
		return parameters;
	}

	/** ChatGPT형은 private_key_jwt로 자신을 증명한다. 문서의 jwks_uri에 있는 key로 서명한 assertion을 붙인다. */
	static MultiValueMap<String, String> withAssertion(MultiValueMap<String, String> parameters, RSAKey key)
			throws Exception {
		parameters.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
		parameters.add("client_assertion", TestClientDocuments.assertion(key, CHATGPT, TOKEN_ENDPOINT));
		return parameters;
	}

	String token(MultiValueMap<String, String> parameters, int expectedStatus) throws Exception {
		return this.mockMvc.perform(post("/oauth2/token").params(parameters))
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
	}

	static JWTClaimsSet claims(String jwt) throws Exception {
		return SignedJWT.parse(jwt).getJWTClaimsSet();
	}

	@Test
	void metadata는_CIMD와_두_인증_방식만_알린다() throws Exception {
		for (String path : List.of("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration")) {
			this.mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.issuer").value(ISSUER))
					.andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
					.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", contains("private_key_jwt", "none")))
					.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", contains("RS256")))
					.andExpect(jsonPath("$.revocation_endpoint_auth_methods_supported", contains("private_key_jwt")))
					.andExpect(jsonPath("$.revocation_endpoint_auth_signing_alg_values_supported", contains("RS256")))
					.andExpect(jsonPath("$.introspection_endpoint_auth_methods_supported", contains("private_key_jwt")))
					.andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported", contains("RS256")))
					.andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true))
					.andExpect(jsonPath("$.dpop_signing_alg_values_supported").doesNotExist());
		}
		this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(jsonPath("$.code_challenge_methods_supported", hasItem("S256")));
	}

	@Test
	void 처음_보는_client의_authorization_request는_consent_화면으로_간다() throws Exception {
		String html = consent화면(redirect(authorizationRequest(CHATGPT, "openid products:read")));

		assertThat(html).contains("Shop Agent (ChatGPT형)", "localhost:8172", "localhost:8170", "이 기기의 주소");
		assertThat(html).contains("name=\"scope\" value=\"products:read\"");
	}

	@Test
	void ChatGPT형은_client_assertion으로_token을_받는다() throws Exception {
		String body = token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200);

		JWTClaimsSet accessToken = claims(JsonPath.read(body, "$.access_token"));
		assertThat(accessToken.getAudience()).containsExactly(RESOURCE);
		assertThat(accessToken.getStringClaim("client_id")).isEqualTo(CHATGPT);
		assertThat(claims(JsonPath.read(body, "$.id_token")).getAudience()).containsExactly(CHATGPT);
		assertThat((String) JsonPath.read(body, "$.refresh_token")).isNotBlank();
	}

	@Test
	void ChatGPT형_refresh도_assertion으로_하고_새_refresh_token을_받는다() throws Exception {
		String first = JsonPath.read(
				token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200), "$.refresh_token");

		String refreshed = token(withAssertion(refresh(CHATGPT, first), TestClientDocuments.KEY), 200);

		assertThat((String) JsonPath.read(refreshed, "$.refresh_token")).isNotEqualTo(first);
		assertThat(claims(JsonPath.read(refreshed, "$.access_token")).getAudience()).containsExactly(RESOURCE);
	}

	@Test
	void 다른_key로_서명한_assertion은_401이다() throws Exception {
		String body = token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.OTHER_KEY), 401);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_client");
	}

	@Test
	void private_key_jwt_client가_assertion_없이_오면_401이다() throws Exception {
		// 문서가 private_key_jwt를 선언했으므로 client_id와 code_verifier만으로는 받지 않는다.
		String codeOnly = token(codeExchange(CHATGPT, code(CHATGPT)), 401);
		assertThat((String) JsonPath.read(codeOnly, "$.error")).isEqualTo("invalid_client");

		// refresh 요청도 같다.
		String issued = token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200);
		String refreshOnly = token(refresh(CHATGPT, JsonPath.read(issued, "$.refresh_token")), 401);
		assertThat((String) JsonPath.read(refreshOnly, "$.error")).isEqualTo("invalid_client");
	}

	@Test
	void 문서에_없는_redirect_uri는_redirect_없이_400이다() throws Exception {
		URI uri = authorizationRequestBuilder(CHATGPT, "openid products:read")
				.replaceQueryParam("redirect_uri", "http://localhost:8170/elsewhere")
				.encode().build().toUri();

		String error = this.mockMvc.perform(get(uri).session(this.session))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getErrorMessage();
		assertThat(error).contains("invalid_request");
	}

	@Test
	void PKCE가_없으면_invalid_request다() throws Exception {
		URI uri = authorizationRequestBuilder(CLAUDE, "openid products:read")
				.replaceQueryParam("code_challenge")
				.replaceQueryParam("code_challenge_method")
				.encode().build().toUri();

		UriComponents response = UriComponentsBuilder.fromUriString(redirect(uri)).build();
		assertThat(응답값(response, "error")).isEqualTo("invalid_request");
		assertThat(응답값(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void 가져올_수_없는_문서의_client_id는_400이다() throws Exception {
		this.mockMvc.perform(get(authorizationRequest("https://localhost:8172/oauth/unknown.json", "openid products:read"))
						.session(this.session))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 등록되지_않은_resource는_invalid_target이다() throws Exception {
		URI uri = authorizationRequestBuilder(CHATGPT, "openid products:read")
				.replaceQueryParam("resource", OTHER_RESOURCE)
				.encode().build().toUri();

		UriComponents response = UriComponentsBuilder.fromUriString(redirect(uri)).build();
		assertThat(응답값(response, "error")).isEqualTo("invalid_target");
	}

	@Test
	void Claude형은_client_id만으로_token과_refresh_token을_받는다() throws Exception {
		String body = token(codeExchange(CLAUDE, code(CLAUDE)), 200);

		assertThat(claims(JsonPath.read(body, "$.access_token")).getStringClaim("client_id")).isEqualTo(CLAUDE);
		assertThat((String) JsonPath.read(body, "$.refresh_token")).isNotBlank();
	}

	@Test
	void Claude형_refresh는_새_refresh_token을_주고_옛것은_invalid_grant다() throws Exception {
		String first = JsonPath.read(token(codeExchange(CLAUDE, code(CLAUDE)), 200), "$.refresh_token");

		String refreshed = token(refresh(CLAUDE, first), 200);
		assertThat((String) JsonPath.read(refreshed, "$.refresh_token")).isNotEqualTo(first);
		assertThat(claims(JsonPath.read(refreshed, "$.access_token")).getAudience()).containsExactly(RESOURCE);

		String reused = token(refresh(CLAUDE, first), 400);
		assertThat((String) JsonPath.read(reused, "$.error")).isEqualTo("invalid_grant");
	}

	@Test
	void 다른_client의_refresh_token은_쓸_수_없다() throws Exception {
		String chatgptRefresh = JsonPath.read(
				token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200), "$.refresh_token");

		// Claude형 client_id만 대고 ChatGPT형의 refresh token을 쓰려 한다.
		String body = token(refresh(CLAUDE, chatgptRefresh), 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_grant");
	}

	@Test
	void introspect_endpoint에서는_client_id만으로_인증되지_않는다() throws Exception {
		String accessToken = JsonPath.read(token(codeExchange(CLAUDE, code(CLAUDE)), 200), "$.access_token");

		// token endpoint의 public client refresh 요청과 같은 parameter다. 다른 client 인증은 없다.
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "refresh_token");
		parameters.add("client_id", CLAUDE);
		parameters.add("token", accessToken);

		// introspection을 부르는 resource server는 JSON 응답을 받겠다고 알린다(Spring의 introspector도 그렇다).
		// Accept가 없거나 text/html이면 browser 요청으로 보고 login 화면으로 redirect한다.
		String body = this.mockMvc.perform(post("/oauth2/introspect").accept(MediaType.APPLICATION_JSON).params(parameters))
				.andExpect(status().is4xxClientError())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("\"active\":true");
	}

	@Test
	void Claude형은_전에_허락했어도_매번_consent_화면을_거친다() throws Exception {
		code(CLAUDE);

		assertThat(redirect(authorizationRequest(CLAUDE, "openid products:read"))).contains(ConsentController.PATH);
	}

	@Test
	void ChatGPT형은_허락한_scope면_consent_화면을_건너뛴다() throws Exception {
		code(CHATGPT);

		assertThat(redirect(authorizationRequest(CHATGPT, "openid products:read")))
				.startsWith(REDIRECT_URI).contains("code=");
	}

	@Test
	void step_up_consent_화면에는_새_scope만_체크박스로_나온다() throws Exception {
		code(CHATGPT);

		String html = consent화면(redirect(authorizationRequest(CHATGPT, "openid products:read products:write")));

		assertThat(html).contains("name=\"scope\" value=\"products:write\"");
		assertThat(html).doesNotContain("name=\"scope\" value=\"products:read\"");
		assertThat(html).contains("이미 허락한 권한");
	}

	@Test
	void Basic_인증_실패에는_WWW_Authenticate가_붙는다() throws Exception {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "client_credentials");

		this.mockMvc.perform(post("/oauth2/token").with(httpBasic("unknown-client", "secret")).params(parameters))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Basic realm=\"" + ISSUER + "\""));
	}

	@Test
	void 두_번째_계정_user2로_login할_수_있다() throws Exception {
		this.mockMvc.perform(formLogin("/login").user("user2").password("password"))
				.andExpect(authenticated().withUsername("user2"));
	}
}
