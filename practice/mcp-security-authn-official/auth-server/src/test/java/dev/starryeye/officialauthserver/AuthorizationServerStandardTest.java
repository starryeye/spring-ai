package dev.starryeye.officialauthserver;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 인가 명세가 인가 서버에 요구하는 것들을 실제 흐름으로 검증한다.
 * PKCE(S256) 강제, RFC 8707 resource → aud, RFC 9207 iss.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationServerStandardTest {

	static final String USERNAME = "user";
	static final String PASSWORD = "password";
	static final String CLIENT_ID = "official-shop-agent";
	static final String CLIENT_SECRET = "official-shop-agent-secret";
	static final String ISSUER = "http://localhost:9010";
	static final String REDIRECT_URI = "http://localhost:8110/login/oauth2/code/authserver";
	static final String RESOURCE = "http://localhost:8111/mcp";
	static final String OTHER_RESOURCE = "http://localhost:9999/mcp";

	// RFC 6749 §2.1 의 공개 클라이언트 — 비밀을 보관할 수 없어 client_id 만으로 토큰
	// 엔드포인트에 온다. redirect-uri 는 RFC 8252 §7.3 의 루프백이다.
	static final String PUBLIC_CLIENT_ID = "local-mcp-client";
	static final String PUBLIC_CLIENT_REDIRECT_URI = "http://127.0.0.1:8123/callback";

	// RFC 7636 부록 B 의 예시 값이다.
	static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RegisteredClientRepository registeredClientRepository;

	@Autowired
	OAuth2AuthorizationConsentService authorizationConsentService;

	MockHttpSession session;

	@BeforeEach
	void 로그인한다() throws Exception {
		this.session = (MockHttpSession) this.mockMvc
				.perform(formLogin().user(USERNAME).password(PASSWORD))
				.andExpect(status().is3xxRedirection())
				.andReturn().getRequest().getSession(false);
		공개클라이언트_동의_기록을_지운다();
	}

	/**
	 * 동의 기록은 (클라이언트, 사용자) 단위로 인가 서버에 영구히 남는다. 한 테스트가
	 * 공개 클라이언트에 동의를 마치면 스프링이 재사용하는 같은 테스트 컨텍스트 안에서
	 * 다른 테스트의 인가 요청이 동의 화면 없이 곧장 통과해버린다 — 실제 서비스에서
	 * "다시 물어보지 않는다"로 동작하는 것과 같은 이유다. 테스트끼리 이 기록으로
	 * 영향을 주지 않도록 매번 지운다.
	 */
	private void 공개클라이언트_동의_기록을_지운다() {
		var registeredClient = this.registeredClientRepository.findByClientId(PUBLIC_CLIENT_ID);
		OAuth2AuthorizationConsent consent = this.authorizationConsentService.findById(registeredClient.getId(),
				USERNAME);
		if (consent != null) {
			this.authorizationConsentService.remove(consent);
		}
	}

	UriComponents 인가요청(boolean pkce, String resource) throws Exception {
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", CLIENT_ID)
				.queryParam("redirect_uri", REDIRECT_URI)
				.queryParam("scope", "openid profile")
				.queryParam("state", "state-1");
		if (pkce) {
			uri.queryParam("code_challenge", CODE_CHALLENGE).queryParam("code_challenge_method", "S256");
		}
		if (resource != null) {
			uri.queryParam("resource", resource);
		}
		String location = this.mockMvc.perform(get(uri.encode().build().toUri()).session(this.session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		return UriComponentsBuilder.fromUriString(location).build();
	}

	static String 응답파라미터(UriComponents response, String name) {
		String value = response.getQueryParams().getFirst(name);
		return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
	}

	String 인가코드(String resource) throws Exception {
		return 응답파라미터(인가요청(true, resource), "code");
	}

	String 토큰요청(MultiValueMap<String, String> parameters, int expectedStatus) throws Exception {
		return this.mockMvc.perform(post("/oauth2/token").with(httpBasic(CLIENT_ID, CLIENT_SECRET)).params(parameters))
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
	}

	static MultiValueMap<String, String> 인가코드교환(String code, String resource) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "authorization_code");
		parameters.add("code", code);
		parameters.add("redirect_uri", REDIRECT_URI);
		parameters.add("code_verifier", CODE_VERIFIER);
		if (resource != null) {
			parameters.add("resource", resource);
		}
		return parameters;
	}

	static List<String> 토큰의_aud(String jwt) throws Exception {
		return SignedJWT.parse(jwt).getJWTClaimsSet().getAudience();
	}

	private static UriComponentsBuilder 공개클라이언트_인가요청_URI(boolean pkce, String resource) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", PUBLIC_CLIENT_ID)
				.queryParam("redirect_uri", PUBLIC_CLIENT_REDIRECT_URI)
				.queryParam("scope", "openid profile")
				.queryParam("state", "state-1");
		if (pkce) {
			uri.queryParam("code_challenge", CODE_CHALLENGE).queryParam("code_challenge_method", "S256");
		}
		if (resource != null) {
			uri.queryParam("resource", resource);
		}
		return uri;
	}

	/**
	 * 공개 클라이언트는 require-authorization-consent 가 true 라, 유효한 요청이면
	 * 리다이렉트가 아니라 200 으로 동의 화면을 돌려준다. 그 판단은 호출부에서 한다.
	 */
	MvcResult 공개클라이언트_인가요청(boolean pkce, String resource) throws Exception {
		return this.mockMvc
				.perform(get(공개클라이언트_인가요청_URI(pkce, resource).encode().build().toUri()).session(this.session))
				.andReturn();
	}

	/** redirect_uri 가 유효해서 곧장 리다이렉트로 거부되는 경우(예: PKCE 누락)에 쓴다. */
	UriComponents 공개클라이언트_인가요청_거부(boolean pkce, String resource) throws Exception {
		String location = this.mockMvc
				.perform(get(공개클라이언트_인가요청_URI(pkce, resource).encode().build().toUri()).session(this.session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		return UriComponentsBuilder.fromUriString(location).build();
	}

	/**
	 * Spring 기본 동의 화면(DefaultConsentPage)이 실제로 돌려주는 폼의 hidden state 값을
	 * 읽는다. 이 값은 원래 인가 요청의 state 파라미터가 아니라, 대기 중인 인가를 찾기 위해
	 * 서버가 새로 발급한 값이다.
	 *
	 * DefaultConsentPage 는 Spring Authorization Server 소스에 "For internal use only"로
	 * 표시된 비공개 클래스이고, 이 렌더링 결과를 정규식으로 파싱한다. DefaultConsentPage 의
	 * 렌더링 형식이 바뀌면 이 정규식은 값을 찾지 못해 이 테스트가 실패한다. spring-security-test
	 * 에는 동의 화면 파싱을 위한 공개 지원이 없어 지금은 이 결합을 대체할 방법이 없다.
	 */
	private static String 동의화면_state(String html) {
		Matcher matcher = Pattern.compile("name=\"state\" value=\"([^\"]*)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	/**
	 * 동의 화면의 체크박스를 담아 같은 URI 로 다시 POST 한다(client_id·state·scope). openid
	 * 는 동의 대상이 아니라 체크박스 자체가 없으므로 scope 파라미터로 보내지 않아도
	 * OAuth2AuthorizationConsentAuthenticationProvider 가 자동으로 다시 붙여 준다.
	 */
	UriComponents 공개클라이언트_동의(MvcResult authorizationResponse, String approvedScope) throws Exception {
		String state = 동의화면_state(authorizationResponse.getResponse().getContentAsString());
		String location = this.mockMvc
				.perform(post("/oauth2/authorize").session(this.session)
						.param("client_id", PUBLIC_CLIENT_ID)
						.param("state", state)
						.param("scope", approvedScope))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		return UriComponentsBuilder.fromUriString(location).build();
	}

	String 공개클라이언트_인가코드(String resource) throws Exception {
		MvcResult authorizationResponse = 공개클라이언트_인가요청(true, resource);
		assertThat(authorizationResponse.getResponse().getStatus()).isEqualTo(200);
		UriComponents codeResponse = 공개클라이언트_동의(authorizationResponse, "profile");
		return 응답파라미터(codeResponse, "code");
	}

	/** 공개 클라이언트는 client_secret_basic 헤더 없이 client_id 파라미터만으로 온다. */
	String 공개클라이언트_토큰요청(MultiValueMap<String, String> parameters, int expectedStatus) throws Exception {
		return this.mockMvc.perform(post("/oauth2/token").params(parameters))
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
	}

	static MultiValueMap<String, String> 공개클라이언트_인가코드교환(String code, String resource) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "authorization_code");
		parameters.add("client_id", PUBLIC_CLIENT_ID);
		parameters.add("code", code);
		parameters.add("redirect_uri", PUBLIC_CLIENT_REDIRECT_URI);
		parameters.add("code_verifier", CODE_VERIFIER);
		if (resource != null) {
			parameters.add("resource", resource);
		}
		return parameters;
	}

	@Test
	void 메타데이터가_PKCE_S256_과_RFC9207_iss_지원을_광고한다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value(ISSUER))
				.andExpect(jsonPath("$.code_challenge_methods_supported", hasItem("S256")))
				.andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true));

		this.mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true));
	}

	@Test
	void 메타데이터에_클라이언트_인증_서명_알고리즘이_광고된다() throws Exception {
		// RFC 8414 §2: token/revocation/introspection 이 client_secret_jwt·private_key_jwt 를
		// 광고하면, 그 알고리즘 목록도 함께 실어야 한다(조건부 MUST). none 은 MUST NOT.
		this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", hasItem("RS256")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", hasItem("HS256")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))))
				.andExpect(jsonPath("$.revocation_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.revocation_endpoint_auth_signing_alg_values_supported", hasItem("RS256")))
				.andExpect(jsonPath("$.revocation_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))))
				.andExpect(jsonPath("$.introspection_endpoint_auth_methods_supported", hasItem("client_secret_jwt")))
				.andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported", hasItem("HS256")))
				.andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))));
	}

	@Test
	void OIDC_디스커버리에도_token_revocation_introspection_인증_서명_알고리즘이_모두_있다() throws Exception {
		// OIDC Discovery 1.0 도 RFC 8414 §2 와 같은 조건부 MUST 를 요구한다.
		// OidcProviderConfigurationEndpointFilter 가 세 엔드포인트 모두에 private_key_jwt·
		// client_secret_jwt 를 광고하므로, 짝이 되는 서명 알고리즘 claim 도 세 개 다 있어야 한다.
		this.mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", hasItem("RS256")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", hasItem("HS256")))
				.andExpect(jsonPath("$.token_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))))
				.andExpect(jsonPath("$.revocation_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.revocation_endpoint_auth_signing_alg_values_supported", hasItem("RS256")))
				.andExpect(jsonPath("$.revocation_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))))
				.andExpect(jsonPath("$.introspection_endpoint_auth_methods_supported", hasItem("client_secret_jwt")))
				.andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported", hasItem("HS256")))
				.andExpect(jsonPath("$.introspection_endpoint_auth_signing_alg_values_supported", not(hasItem("none"))));
	}

	@Test
	void Basic_인증_실패시_스킴에_맞는_WWW_Authenticate_가_실린다() throws Exception {
		// RFC 6749 §5.2 / OAuth 2.1 §3.2.4: Authorization 헤더로 인증을 시도했다면
		// 그 스킴에 맞는 WWW-Authenticate 를 401 과 함께 반드시 실어야 한다.
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "client_credentials");

		this.mockMvc.perform(post("/oauth2/token").with(httpBasic(CLIENT_ID, "wrong-secret")).params(parameters))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Basic realm=\"" + ISSUER + "\""));
	}

	/**
	 * client_secret_post 파라미터(client_id·client_secret 폼 파라미터)는 등록된 클라이언트가
	 * client_secret_basic 만 허용하므로 {@code ClientSecretAuthenticationProvider} 가
	 * "authentication_method" 사유로 invalid_client 를 던진다 — 자격증명 자체는 맞아도 실패한다.
	 * 이 변환기는 Authorization 헤더 내용과 무관하게 동작하므로, 헤더에 임의의(때로는 문법에
	 * 어긋나는) 스킴을 실어도 그 값과 무관하게 실패를 재현할 수 있다. 우리 핸들러는 어느
	 * 변환기가 실패시켰는지와 상관없이 원본 Authorization 헤더를 다시 파싱하므로, 이 방식으로
	 * requestedScheme() 의 파싱·폴백 로직만 독립적으로 검증할 수 있다.
	 */
	static MultiValueMap<String, String> 인증방법이_허용되지_않는_클라이언트_자격증명() {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "client_credentials");
		parameters.add("client_id", CLIENT_ID);
		parameters.add("client_secret", CLIENT_SECRET);
		return parameters;
	}

	@Test
	void Basic_아닌_스킴으로_인증_실패시_그_스킴이_그대로_반영된다() throws Exception {
		// DEFAULT_SCHEME 이 "Basic" 이라 Basic 요청만으로는 스킴 파싱 자체가 이뤄지는지
		// 검증하지 못한다 — 파싱을 건너뛰고 항상 기본값만 돌려주는 구현도 통과해버린다.
		// Bearer 처럼 다른 스킴으로 시도했을 때 그 스킴이 그대로 실리는지로 파싱을 고정한다.
		this.mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
						.params(인증방법이_허용되지_않는_클라이언트_자격증명()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Bearer realm=\"" + ISSUER + "\""));
	}

	@Test
	void 스킴_토큰에_따옴표가_섞이면_Basic_으로_폴백하고_주입되지_않는다() throws Exception {
		// RFC 7230 §3.2.6 token 문법에 어긋나는 스킴(따옴표·공백 포함)은 헤더에 그대로
		// 옮기면 WWW-Authenticate 값 주입으로 이어질 수 있다. 기본 스킴으로 폴백해야 한다.
		this.mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, "Basic\" , evil=\"x not-a-real-credential")
						.params(인증방법이_허용되지_않는_클라이언트_자격증명()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("invalid_client"))
				.andExpect(header().string("WWW-Authenticate", "Basic realm=\"" + ISSUER + "\""));
	}

	@Test
	void Authorization_헤더_없이_실패하면_WWW_Authenticate_가_없다() throws Exception {
		// client_secret_post 처럼 폼 파라미터로만 인증을 시도한 경우엔 스킴을 알 수 없으므로
		// RFC 6749 §5.2 요구(Authorization 헤더로 시도한 경우 한정)의 대상이 아니다.
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "client_credentials");
		parameters.add("client_id", CLIENT_ID);
		parameters.add("client_secret", "wrong-secret");

		this.mockMvc.perform(post("/oauth2/token").params(parameters))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void client_인증은_성공하고_code_verifier_만_틀리면_WWW_Authenticate_가_없다() throws Exception {
		// PKCE code_verifier 검증은 OAuth2TokenEndpointFilter 가 아니라, ClientSecretAuthenticationProvider
		// 내부의 CodeVerifierAuthenticator 가 "클라이언트 인증"의 일부로 OAuth2ClientAuthenticationFilter
		// 안에서 수행한다. 그래서 이 실패도 우리 챌린지 핸들러(그 필터의 실패 핸들러)를 거치지만,
		// 오류 코드는 invalid_client 가 아니라 invalid_grant 다 — client_secret 자체는 맞았기 때문이다.
		// RFC 6749 §5.2 의 챌린지 의무는 invalid_client 응답에 한정되므로 붙으면 안 된다.
		String code = 인가코드(RESOURCE);
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "authorization_code");
		parameters.add("code", code);
		parameters.add("redirect_uri", REDIRECT_URI);
		parameters.add("code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier-000");
		parameters.add("resource", RESOURCE);

		this.mockMvc.perform(post("/oauth2/token").with(httpBasic(CLIENT_ID, CLIENT_SECRET)).params(parameters))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_grant"))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void 인가_응답에_code_state_iss_가_실린다() throws Exception {
		UriComponents response = 인가요청(true, RESOURCE);

		assertThat(응답파라미터(response, "code")).isNotBlank();
		assertThat(응답파라미터(response, "state")).isEqualTo("state-1");
		assertThat(응답파라미터(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void PKCE_없는_인가_요청은_거부된다() throws Exception {
		UriComponents response = 인가요청(false, RESOURCE);

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_request");
		assertThat(응답파라미터(response, "code")).isNull();
		// 오류 응답에도 iss 가 실려야 한다(RFC 9207 §2.4).
		assertThat(응답파라미터(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void 등록되지_않은_resource_는_invalid_target_이다() throws Exception {
		UriComponents response = 인가요청(true, OTHER_RESOURCE);

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_target");
		assertThat(응답파라미터(response, "code")).isNull();
		assertThat(응답파라미터(response, "iss")).isEqualTo(ISSUER);
	}

	@Test
	void access_token_의_aud_는_resource_이고_id_token_은_client_id_다() throws Exception {
		String body = 토큰요청(인가코드교환(인가코드(RESOURCE), RESOURCE), 200);

		String accessToken = JsonPath.read(body, "$.access_token");
		String idToken = JsonPath.read(body, "$.id_token");

		// openid 스코프가 있어도 access token 의 aud 는 보호 리소스여야 한다.
		assertThat(토큰의_aud(accessToken)).containsExactly(RESOURCE);
		assertThat(토큰의_aud(idToken)).containsExactly(CLIENT_ID);
	}

	@Test
	void 토큰_요청의_resource_가_인가_요청과_다르면_invalid_target_이다() throws Exception {
		String body = 토큰요청(인가코드교환(인가코드(RESOURCE), OTHER_RESOURCE), 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_target");
	}

	@Test
	void 토큰_요청에_resource_가_없으면_인가_요청의_resource_로_발급한다() throws Exception {
		String body = 토큰요청(인가코드교환(인가코드(RESOURCE), null), 200);

		assertThat(토큰의_aud(JsonPath.read(body, "$.access_token"))).containsExactly(RESOURCE);
	}

	@Test
	void refresh_로_받은_access_token_도_같은_aud_다() throws Exception {
		String first = 토큰요청(인가코드교환(인가코드(RESOURCE), RESOURCE), 200);
		String refreshToken = JsonPath.read(first, "$.refresh_token");

		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		parameters.add("grant_type", "refresh_token");
		parameters.add("refresh_token", refreshToken);
		parameters.add("resource", RESOURCE);

		String refreshed = 토큰요청(parameters, 200);

		assertThat(토큰의_aud(JsonPath.read(refreshed, "$.access_token"))).containsExactly(RESOURCE);
	}

	@Test
	void 메타데이터에_공개_클라이언트_인증_방식_none_이_광고되고_기존_방식도_유지된다() throws Exception {
		// RFC 8414 §2: none 은 client_secret 이 없는 공개 클라이언트를 뜻한다.
		// OAuth2AuthorizationServerMetadataEndpointFilter.clientAuthenticationMethods() 는
		// 이 값을 절대 넣지 않으므로, 커스터마이저가 더한 값이 기존 여섯 방식 곁에
		// 그대로 남아 있어야 한다 — 지우고 다시 채운 게 아니라 더한 것이어야 한다.
		this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("none")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_basic")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_post")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("tls_client_auth")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("self_signed_tls_client_auth")));

		this.mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("none")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_basic")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_post")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("client_secret_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("private_key_jwt")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("tls_client_auth")))
				.andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("self_signed_tls_client_auth")));
	}

	@Test
	void 공개_클라이언트는_동의_화면을_거치고_기존_에이전트는_바로_코드를_받는다() throws Exception {
		// require-authorization-consent 가 client 단위 설정이라 official-shop-agent(false)의
		// 흐름은 그대로여야 한다 — 공개 클라이언트를 더한다고 바뀌면 안 된다.
		MvcResult publicResponse = 공개클라이언트_인가요청(true, RESOURCE);
		assertThat(publicResponse.getResponse().getStatus()).isEqualTo(200);
		assertThat(publicResponse.getResponse().getContentAsString()).contains("Consent required");

		UriComponents agentResponse = 인가요청(true, RESOURCE);
		assertThat(응답파라미터(agentResponse, "code")).isNotBlank();
	}

	@Test
	void 공개_클라이언트는_동의_뒤_클라이언트_인증_없이_토큰을_받고_access_token의_aud는_resource다() throws Exception {
		String code = 공개클라이언트_인가코드(RESOURCE);

		// client_secret_basic 헤더도 client_secret 파라미터도 없다 — client_id 만 보내고
		// PKCE code_verifier 가 비밀의 자리를 대신한다(RFC 6749 §3.2.1 / OAuth 2.1 §3.2.2).
		String body = 공개클라이언트_토큰요청(공개클라이언트_인가코드교환(code, RESOURCE), 200);

		String accessToken = JsonPath.read(body, "$.access_token");
		assertThat(토큰의_aud(accessToken)).containsExactly(RESOURCE);
	}

	@Test
	void 공개_클라이언트도_code_challenge_없는_인가_요청은_거부된다() throws Exception {
		UriComponents response = 공개클라이언트_인가요청_거부(false, RESOURCE);

		assertThat(응답파라미터(response, "error")).isEqualTo("invalid_request");
		assertThat(응답파라미터(response, "code")).isNull();
	}

	@Test
	void 공개_클라이언트가_code_verifier_를_틀리면_invalid_grant_다() throws Exception {
		String code = 공개클라이언트_인가코드(RESOURCE);
		MultiValueMap<String, String> parameters = 공개클라이언트_인가코드교환(code, RESOURCE);
		parameters.set("code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier-000");

		String body = 공개클라이언트_토큰요청(parameters, 400);

		assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("invalid_grant");
	}

	@Test
	void 등록되지_않은_redirect_uri_는_리다이렉트_없이_거부된다() throws Exception {
		// redirect_uri 자체가 미등록이면 그 주소로 리다이렉트하지 않는다 — 오픈 리다이렉터를
		// 막기 위한 RFC 6749 §4.1.2.1 요구다. IssuerIdentifyingAuthorizationResponseHandler 가
		// 이 경우 redirectUri 가 null 로 지워진 예외를 받아 response.sendError 로 직접 응답한다.
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", PUBLIC_CLIENT_ID)
				.queryParam("redirect_uri", "http://127.0.0.1:8123/not-registered")
				.queryParam("scope", "openid profile")
				.queryParam("state", "state-1")
				.queryParam("code_challenge", CODE_CHALLENGE)
				.queryParam("code_challenge_method", "S256");

		// response.sendError(int, String) 는 상태 코드만 응답에 반영하고, 그 메시지 자체는
		// MockHttpServletResponse.getErrorMessage() 로만 확인할 수 있다(본문에는 실리지 않는다
		// — 실제 컨테이너라면 에러 페이지가 그 메시지를 담아 렌더링하지만 MockMvc 는 렌더링까지
		// 가지 않는다).
		String errorMessage = this.mockMvc.perform(get(uri.encode().build().toUri()).session(this.session))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getErrorMessage();

		assertThat(errorMessage).contains("invalid_request");
	}
}
