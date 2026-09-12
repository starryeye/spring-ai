package dev.starryeye.officialauthserver;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
