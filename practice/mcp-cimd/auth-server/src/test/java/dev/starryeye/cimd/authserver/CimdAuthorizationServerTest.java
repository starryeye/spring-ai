package dev.starryeye.cimd.authserver;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.cimd.authserver.security.RefreshTokenReuseDetectingAuthorizationService;
import dev.starryeye.cimd.authserver.web.ConsentController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

	static final String MUTABLE = TestClientDocuments.MUTABLE;

	// RFC 7636 부록 B의 예시 값이다.
	static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

	static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	OAuth2AuthorizationConsentService consents;

	@Autowired
	RefreshTokenReuseDetectingAuthorizationService authorizations;

	@Autowired
	CimdTestConfig.TestDocumentHost documents;

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
		this.documents.withdraw(MUTABLE);
	}

	/** 바꿔 가며 쓰는 문서를 올리고, 앞선 테스트가 이 client에 남긴 consent를 지운다. */
	void 문서를_올린다(String document) {
		this.documents.publish(MUTABLE, document);
		OAuth2AuthorizationConsent consent = this.consents.findById(MUTABLE, "user");
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
		return 인가(authorizationRequest(clientId, scope), clientId, approvedScopes);
	}

	UriComponents 인가(URI uri, String clientId, String... approvedScopes) throws Exception {
		String location = redirect(uri);
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

	/**
	 * ChatGPT형은 private_key_jwt로 자신을 증명한다.
	 * 문서의 jwks_uri에 있는 key로 sign한 assertion을 붙인다.
	 */
	static MultiValueMap<String, String> withAssertion(MultiValueMap<String, String> parameters, RSAKey key)
			throws Exception {
		return withAssertion(parameters, key, CHATGPT);
	}

	static MultiValueMap<String, String> withAssertion(MultiValueMap<String, String> parameters, RSAKey key,
			String clientId) throws Exception {
		parameters.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
		parameters.add("client_assertion", TestClientDocuments.assertion(key, clientId, ISSUER));
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

	static List<String> scopes(String jwt) throws Exception {
		return claims(jwt).getStringListClaim("scope");
	}

	/**
	 * authorization request의 오류 응답 본문을 읽는다.
	 * consent 화면에서 거절한 경우 말고는 문서의 redirect_uri로 보내지 않고, Authorization Server가 400으로 끝낸다.
	 */
	String 오류_화면(MockHttpServletRequestBuilder request) throws Exception {
		return this.mockMvc.perform(request)
				.andExpect(status().isBadRequest())
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(content().contentType("text/plain;charset=UTF-8"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** login한 session으로 authorization request를 보내고 오류 응답 본문을 읽는다. */
	String 오류_화면(URI uri) throws Exception {
		return 오류_화면(get(uri).session(this.session));
	}

	/**
	 * ChatGPT형 문서는 {@code private_key_jwt}만 선언한다.
	 * 그래서 {@code client_secret}을 form으로 보내면 client 인증이 {@code invalid_client}로 실패한다.
	 * 이 실패는 Authorization header와 상관없이 일어나므로, header의 scheme을 읽는 방식만 따로 확인할 수 있다.
	 */
	static MultiValueMap<String, String> 허용되지_않는_인증_방식() {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "client_credentials");
		parameters.add("client_id", CHATGPT);
		parameters.add("client_secret", "not-a-secret");
		return parameters;
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
					.andExpect(jsonPath("$.dpop_signing_alg_values_supported").doesNotExist())
					.andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").doesNotExist());
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
	void aud가_token_endpoint인_assertion은_401이다() throws Exception {
		// RFC 7523bis: assertion의 aud는 이 서버의 issuer 하나여야 한다.
		MultiValueMap<String, String> parameters = codeExchange(CHATGPT, code(CHATGPT));
		parameters.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
		parameters.add("client_assertion", TestClientDocuments.assertion(TestClientDocuments.KEY, CHATGPT, TOKEN_ENDPOINT));

		String body = token(parameters, 401);

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

		assertThat(오류_화면(uri)).contains("invalid_request");
	}

	@Test
	void redirect_uri를_생략하면_redirect_없이_invalid_request다() throws Exception {
		// 문서의 redirect가 하나면 Spring은 openid 없는 요청의 redirect_uri 생략을 받고, consent 화면에는 돌아갈 host가 빠진다.
		// MCP Authorization은 consent 화면에 redirect URI의 host를 보이라고 한다(MUST). 그래서 모든 요청에 요구한다.
		for (String clientId : List.of(CHATGPT, CLAUDE)) {
			URI uri = authorizationRequestBuilder(clientId, "products:read")
					.replaceQueryParam("redirect_uri")
					.encode().build().toUri();

			assertThat(오류_화면(uri)).as(clientId).contains("invalid_request").contains("redirect_uri");
		}
	}

	@Test
	void PKCE가_없으면_redirect_없이_invalid_request다() throws Exception {
		URI uri = authorizationRequestBuilder(CLAUDE, "openid products:read")
				.replaceQueryParam("code_challenge")
				.replaceQueryParam("code_challenge_method")
				.encode().build().toUri();

		assertThat(오류_화면(uri)).contains("invalid_request");
	}

	@Test
	void 로그인하지_않은_browser의_오류도_문서의_redirect_uri로_보내지_않는다() throws Exception {
		// Spring은 authorization request를 login보다 먼저 검사한다. CIMD client의 redirect_uri는 누구나 문서에 적는 값이라,
		// 그 주소로 오류를 redirect하면 Authorization Server가 open redirector가 된다(RFC 9700 §4.11.2).
		String body = 오류_화면(get(authorizationRequest(CLAUDE, "openid")).accept(MediaType.TEXT_HTML));

		assertThat(body).contains("invalid_scope").doesNotContain(REDIRECT_URI);
	}

	@Test
	void consent_화면에서_거절하면_access_denied와_iss를_redirect로_돌려준다() throws Exception {
		// login한 사용자가 consent 화면에서 고른 결정만 client에게 redirect로 알린다.
		UriComponents response = 인가(CLAUDE, "openid products:read");

		assertThat(response.toUriString()).startsWith(REDIRECT_URI);
		assertThat(응답값(response, "error")).isEqualTo("access_denied");
		assertThat(응답값(response, "code")).isNull();
		assertThat(응답값(response, "state")).isEqualTo("state-1");
		assertThat(응답값(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void 성공한_authorization_response에도_iss와_state가_있다() throws Exception {
		UriComponents response = 인가(CHATGPT, "openid products:read", "products:read");

		assertThat(응답값(response, "code")).isNotBlank();
		assertThat(응답값(response, "state")).isEqualTo("state-1");
		assertThat(응답값(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void ChatGPT형도_openid만_요청하면_invalid_scope다() throws Exception {
		// Spring은 openid 하나면 consent를 건너뛴다.
		// 누구나 private_key_jwt 문서를 올릴 수 있으므로, 막지 않으면 consent 없이 code와 refresh token을 받는다.
		// login한 뒤의 오류도 consent 화면에서 거절한 것이 아니므로 redirect하지 않는다.
		String body = 오류_화면(authorizationRequest(CHATGPT, "openid"));

		assertThat(body).contains("invalid_scope").doesNotContain("code=");
	}

	@Test
	void Claude형도_openid만_요청하면_invalid_scope다() throws Exception {
		assertThat(오류_화면(authorizationRequest(CLAUDE, "openid"))).contains("invalid_scope");
	}

	@Test
	void scope_없이_요청하면_invalid_scope다() throws Exception {
		// RFC 6749 §3.3: scope 없는 요청은 기본값으로 처리하거나 거부한다. 이 서버는 거부한다.
		String body = 오류_화면(authorizationRequestBuilder(CHATGPT, "openid products:read")
				.replaceQueryParam("scope")
				.encode().build().toUri());

		assertThat(body).contains("invalid_scope");
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

		assertThat(오류_화면(uri)).contains("invalid_target");
	}

	@Test
	void token_request의_resource가_authorization_request와_다르면_invalid_target이다() throws Exception {
		MultiValueMap<String, String> parameters = withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY);
		parameters.set("resource", OTHER_RESOURCE);

		String body = token(parameters, 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}

	@Test
	void authorization_request에_없던_resource를_token_request에서_정하면_invalid_target이다() throws Exception {
		// resource는 사용자가 consent한 대상이다. authorization request에 없던 대상을 token request에서 새로 정하지 못한다.
		URI withoutResource = authorizationRequestBuilder(CHATGPT, "openid products:read")
				.replaceQueryParam("resource")
				.encode().build().toUri();
		String code = 응답값(인가(withoutResource, CHATGPT, "products:read"), "code");

		String body = token(withAssertion(codeExchange(CHATGPT, code), TestClientDocuments.KEY), 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}

	@Test
	void token_request의_resource가_여러_개면_invalid_target이다() throws Exception {
		MultiValueMap<String, String> parameters = withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY);
		parameters.add("resource", OTHER_RESOURCE);

		String body = token(parameters, 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}

	@Test
	void token_request에_resource가_없으면_authorization_request의_resource로_발급한다() throws Exception {
		MultiValueMap<String, String> parameters = withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY);
		parameters.remove("resource");

		String body = token(parameters, 200);

		assertThat(claims(JsonPath.read(body, "$.access_token")).getAudience()).containsExactly(RESOURCE);
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
	void 버린_refresh_token이_다시_오면_지금의_refresh_token도_끊긴다() throws Exception {
		String first = JsonPath.read(token(codeExchange(CLAUDE, code(CLAUDE)), 200), "$.refresh_token");
		String second = JsonPath.read(token(refresh(CLAUDE, first), 200), "$.refresh_token");

		// 도둑이 먼저 refresh해 second를 가졌고, 진짜 client가 버린 first를 내밀었다고 보자.
		String reused = token(refresh(CLAUDE, first), 400);
		assertThat((String) JsonPath.read(reused, "$.error")).isEqualTo("invalid_grant");

		// 어느 쪽이 도둑인지 모르므로, 지금의 refresh token도 더는 통하지 않는다.
		String afterReuse = token(refresh(CLAUDE, second), 400);
		assertThat((String) JsonPath.read(afterReuse, "$.error")).isEqualTo("invalid_grant");
	}

	@Test
	void 같은_refresh_token으로_겹쳐_refresh하면_하나만_성공하고_grant가_끊긴다() throws Exception {
		String first = JsonPath.read(token(codeExchange(CLAUDE, code(CLAUDE)), 200), "$.refresh_token");

		// 다른 요청이 이 grant를 rotation하는 중이라고 보고, 테스트 thread가 그 grant의 lock을 먼저 잡는다.
		// 그 사이 같은 refresh token으로 두 요청이 들어와 둘 다 lock을 기다린다.
		assertThat(this.authorizations.findByToken(first, OAuth2TokenType.REFRESH_TOKEN)).isNotNull();
		List<MvcResult> results = new CopyOnWriteArrayList<>();
		List<Thread> requests = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			Thread request = new Thread(() -> {
				try {
					results.add(this.mockMvc.perform(post("/oauth2/token").params(refresh(CLAUDE, first))).andReturn());
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
			request.start();
			requests.add(request);
		}
		for (Thread request : requests) {
			lock을_기다릴_때까지_기다린다(request);
		}
		this.authorizations.releaseHeldLocks();
		for (Thread request : requests) {
			request.join(10_000);
		}

		assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 400);
		MvcResult winner = results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
		MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 400).findFirst().orElseThrow();
		assertThat((String) JsonPath.read(loser.getResponse().getContentAsString(), "$.error")).isEqualTo("invalid_grant");

		// 늦은 쪽은 이미 버린 token을 내민 셈이다. 어느 쪽이 도둑인지 모르므로 이긴 쪽의 새 refresh token도 끊긴다.
		String winnerRefresh = JsonPath.read(winner.getResponse().getContentAsString(), "$.refresh_token");
		String afterRace = token(refresh(CLAUDE, winnerRefresh), 400);
		assertThat((String) JsonPath.read(afterRace, "$.error")).isEqualTo("invalid_grant");
	}

	@Test
	void 실패한_refresh_요청이_잡은_lock은_요청이_끝나면_풀린다() throws Exception {
		String chatgptRefresh = JsonPath.read(
				token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200), "$.refresh_token");
		// Claude형 client_id로 ChatGPT형의 refresh token을 내민다. grant를 찾은 뒤 client가 달라 저장하지 않고 끝난다.
		token(refresh(CLAUDE, chatgptRefresh), 400);

		// 다른 thread가 같은 grant를 바로 refresh할 수 있어야 한다. lock이 남아 있으면 기다리다 실패한다.
		List<Integer> statuses = new CopyOnWriteArrayList<>();
		Thread other = new Thread(() -> {
			try {
				statuses.add(this.mockMvc.perform(post("/oauth2/token")
						.params(withAssertion(refresh(CHATGPT, chatgptRefresh), TestClientDocuments.KEY)))
						.andReturn().getResponse().getStatus());
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		long start = System.nanoTime();
		other.start();
		other.join(15_000);

		assertThat(statuses).containsExactly(200);
		assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(5);
	}

	/** 다른 thread의 요청이 lock을 기다리는 상태(또는 이미 끝난 상태)가 될 때까지 기다린다. */
	static void lock을_기다릴_때까지_기다린다(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Thread.State state = thread.getState();
			if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
					|| state == Thread.State.TERMINATED) {
				return;
			}
			Thread.sleep(10);
		}
	}

	@Test
	void private_key_jwt로_받은_grant는_문서가_none으로_바뀌어도_client_id만으로_refresh할_수_없다() throws Exception {
		문서를_올린다(TestClientDocuments.privateKeyJwtDocument(MUTABLE, REDIRECT_URI));
		String issued = token(withAssertion(codeExchange(MUTABLE, code(MUTABLE)), TestClientDocuments.KEY, MUTABLE), 200);

		// 문서 host를 장악한 쪽이 문서를 none으로 바꿨다. private key 없이 grant를 이어 가려 한다.
		this.documents.publish(MUTABLE, TestClientDocuments.publicDocument(MUTABLE, REDIRECT_URI));
		String body = token(refresh(MUTABLE, JsonPath.read(issued, "$.refresh_token")), 401);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_client");
	}

	@Test
	void 문서의_redirect_uris가_바뀌면_전에_허락했어도_consent_화면을_다시_거친다() throws Exception {
		문서를_올린다(TestClientDocuments.privateKeyJwtDocument(MUTABLE, REDIRECT_URI));
		code(MUTABLE);
		assertThat(redirect(authorizationRequest(MUTABLE, "openid products:read"))).startsWith(REDIRECT_URI);

		// 문서에 다른 redirect 주소가 늘었다. 사용자가 허락한 것은 이전 주소 목록의 client다.
		this.documents.publish(MUTABLE,
				TestClientDocuments.privateKeyJwtDocument(MUTABLE, REDIRECT_URI, "https://attacker.example/cb"));

		assertThat(redirect(authorizationRequest(MUTABLE, "openid products:read"))).contains(ConsentController.PATH);
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

	/** private_key_jwt client로 인증해 introspection을 부르고 응답 본문을 돌려준다. */
	String introspect(String token, String clientId) throws Exception {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("token", token);
		parameters.add("client_id", clientId);
		withAssertion(parameters, TestClientDocuments.KEY, clientId);
		return this.mockMvc.perform(post("/oauth2/introspect").accept(MediaType.APPLICATION_JSON).params(parameters))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void 자기가_받은_token은_introspect할_수_있다() throws Exception {
		String body = token(withAssertion(codeExchange(CHATGPT, code(CHATGPT)), TestClientDocuments.KEY), 200);

		String introspection = introspect(JsonPath.read(body, "$.refresh_token"), CHATGPT);

		assertThat((Boolean) JsonPath.read(introspection, "$.active")).isTrue();
		assertThat((String) JsonPath.read(introspection, "$.client_id")).isEqualTo(CHATGPT);
	}

	@Test
	void 다른_client가_받은_token을_introspect하면_active가_false다() throws Exception {
		// 누구나 private_key_jwt 문서를 올려 인증된 client가 될 수 있다. 남의 token이 살아 있는지, 누구 것인지 묻지 못하게 한다.
		String claude = token(codeExchange(CLAUDE, code(CLAUDE)), 200);

		for (String token : List.of((String) JsonPath.read(claude, "$.access_token"),
				(String) JsonPath.read(claude, "$.refresh_token"))) {
			String introspection = introspect(token, CHATGPT);

			assertThat(introspection).isEqualTo("{\"active\":false}");
		}
	}

	@Test
	void token_주인의_문서를_가져오지_못해도_active_false로_끝난다() throws Exception {
		문서를_올린다(TestClientDocuments.privateKeyJwtDocument(MUTABLE, REDIRECT_URI));
		String body = token(withAssertion(codeExchange(MUTABLE, code(MUTABLE)), TestClientDocuments.KEY, MUTABLE), 200);
		this.documents.withdraw(MUTABLE);

		String introspection = introspect(JsonPath.read(body, "$.access_token"), CHATGPT);

		assertThat(introspection).isEqualTo("{\"active\":false}");
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
	void step_up에서_새_scope를_허락하면_세_scope가_모두_token에_담긴다() throws Exception {
		code(CHATGPT);

		UriComponents response = 인가(CHATGPT, "openid products:read products:write", "products:write");
		String body = token(withAssertion(codeExchange(CHATGPT, 응답값(response, "code")), TestClientDocuments.KEY), 200);

		assertThat(scopes(JsonPath.read(body, "$.access_token")))
				.containsExactlyInAnyOrder("openid", "products:read", "products:write");
	}

	@Test
	void step_up에서_새_scope를_고르지_않으면_전에_허락한_scope만_token에_담긴다() throws Exception {
		code(CHATGPT);

		UriComponents response = 인가(CHATGPT, "openid products:read products:write");
		String body = token(withAssertion(codeExchange(CHATGPT, 응답값(response, "code")), TestClientDocuments.KEY), 200);

		assertThat(JsonPath.<String>read(body, "$.scope").split(" ")).containsExactlyInAnyOrder("openid", "products:read");
		assertThat(scopes(JsonPath.read(body, "$.access_token"))).containsExactlyInAnyOrder("openid", "products:read");
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
	void Basic이_아닌_scheme으로_인증에_실패하면_그_scheme을_WWW_Authenticate에_넣는다() throws Exception {
		// 기본 scheme이 Basic이라, Basic 요청만으로는 header의 scheme을 실제로 읽는지 알 수 없다.
		this.mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
						.params(허용되지_않는_인증_방식()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Bearer realm=\"" + ISSUER + "\""));
	}

	@Test
	void scheme에_따옴표가_섞이면_Basic으로_바꿔_넣는다() throws Exception {
		// token 문법에 맞지 않는 scheme을 그대로 옮기면 WWW-Authenticate에 다른 값을 끼워 넣을 수 있다.
		this.mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, "Basic\" , evil=\"x not-a-real-credential")
						.params(허용되지_않는_인증_방식()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Basic realm=\"" + ISSUER + "\""));
	}

	@Test
	void Authorization_header_없이_인증에_실패하면_WWW_Authenticate가_없다() throws Exception {
		// form parameter로만 인증을 시도했으면 scheme을 알 수 없다. RFC 6749 §5.2의 요구도 header로 시도한 경우에만 해당한다.
		this.mockMvc.perform(post("/oauth2/token").params(허용되지_않는_인증_방식()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void 두_번째_계정_user2로_login할_수_있다() throws Exception {
		this.mockMvc.perform(formLogin("/login").user("user2").password("password"))
				.andExpect(authenticated().withUsername("user2"));
	}
}
