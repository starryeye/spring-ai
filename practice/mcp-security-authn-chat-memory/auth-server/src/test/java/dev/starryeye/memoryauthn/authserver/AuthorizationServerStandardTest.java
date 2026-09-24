package dev.starryeye.memoryauthn.authserver;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

	static final String USERNAME = "alice";
	static final String PASSWORD = "alice";
	static final String CLIENT_ID = "memory-agent";
	static final String CLIENT_SECRET = "memory-agent-secret";
	static final String ISSUER = "http://localhost:9020";
	static final String REDIRECT_URI = "http://localhost:8130/login/oauth2/code/authserver";
	static final String RESOURCE = "http://localhost:8131/mcp";
	static final String OTHER_RESOURCE = "http://localhost:9999/mcp";

	// RFC 7636 부록 B 의 예시 값이다.
	static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Autowired
	MockMvc mockMvc;

	MockHttpSession session;

	@BeforeEach
	void 로그인한다() throws Exception {
		this.session = (MockHttpSession) this.mockMvc
				.perform(formLogin().user(USERNAME).password(PASSWORD))
				.andExpect(status().is3xxRedirection())
				.andReturn().getRequest().getSession(false);
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
}
