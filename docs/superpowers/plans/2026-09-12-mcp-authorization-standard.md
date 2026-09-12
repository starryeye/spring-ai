# MCP 인가 표준 준수 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증 practice 세 개(official / chat-memory / community)를 MCP 인가 명세에 맞추고, `practice/MCP-AUTHORIZATION.md` 한 문서로 인증이 포함된 MCP 스펙을 처음부터 배울 수 있게 한다.

**Architecture:** 인가 서버는 PKCE 강제·RFC 8707 audience·RFC 9207 `iss` 를 더한다. MCP 서버는 경로형 PRM·audience 검증·Origin/Host 검증을 더한다. 에이전트는 하드코딩한 issuer 를 버리고 401 → PRM → AS 메타데이터 순으로 발견한 뒤 PKCE·`resource`·`iss` 검증을 붙인다. 세 practice 는 같은 설계를 공유하되 community 만 모듈 자동설정 위에서 구현한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7.1.0(인가 서버 포함), Spring AI 2.0.0, MCP Java SDK 2.0.0, `org.springaicommunity` 0.1.14(community 만), Gradle, JUnit 5 + MockMvc + MockRestServiceServer.

**Spec:** `docs/superpowers/specs/2026-09-11-mcp-authorization-standard-design.md`

## Global Constraints

- 전송·수명주기 기준은 **MCP 2025-11-25** 다. SDK 가 그 위를 모른다. 인가는 **2025-11-25 + 2026-07-28 추가분**(RFC 9207 `iss`, issuer-bound 자격증명)까지 맞춘다.
- practice 별 포트·식별자는 절대 섞지 않는다.
  - official: AS `9010`, MCP `8111`, agent `8110`, 클라이언트 `official-shop-agent` / `official-shop-agent-secret`, 사용자 `user`/`password`, 패키지 `dev.starryeye.officialauthserver` · `dev.starryeye.officialmcpserver` · `dev.starryeye.officialagent`
  - chat-memory: AS `9020`, MCP `8131`, agent `8130`, 클라이언트 `memory-agent` / `memory-agent-secret`, 사용자 `alice`/`alice`, `bob`/`bob`, 패키지 `dev.starryeye.memoryauthn.authserver` · `.mcpserver` · `.agent`
  - community: AS `9000`, MCP `8101`, agent `8100`, 클라이언트 `shop-agent` / `shop-agent-secret`, 사용자 `user`/`password`, 패키지 `dev.starryeye.authserver` · `dev.starryeye.shopmcpserver` · `dev.starryeye.shopagent`
- resource 식별자는 항상 MCP 엔드포인트 전체 URL 이다: official `http://localhost:8111/mcp`, chat-memory `http://localhost:8131/mcp`, community `http://localhost:8101/mcp`.
- `error_description` 등 OAuth 응답에 들어가는 문자열은 ASCII 로 쓴다(RFC 6749 부록 A). 코드 주석·로그·테스트 이름은 한국어로 쓴다.
- 주석은 "무엇을 왜 켰는지"만 쓴다. 시행착오·버그 수정 서술을 새로 만들지 않는다.
- 테스트 이름은 기존 practice 와 같이 한국어 스네이크 표기를 쓴다.
- 커밋은 작업 단위로 한다. `.superpowers/` 는 절대 `git add` 하지 않는다.
- Java 실행 시 `JAVA_HOME=$HOME/.sdkman/candidates/java/current` 를 쓴다(시스템 기본은 17).

## 이미 끝난 측정 (스파이크)

계획을 쓰기 전에 official 사본으로 실제 실행해 확인했다. 아래는 **관측 결과**이므로 다시 조사하지 말고 그대로 전제한다.

| 확인 | 결과 |
|---|---|
| 인가/토큰 요청의 `resource` | `OAuth2AuthorizationGrantAuthenticationToken.getAdditionalParameters()` 와 `OAuth2Authorization` 의 `OAuth2AuthorizationRequest` 속성으로 토큰 커스터마이저까지 전달된다 |
| access token `aud` | 커스터마이저에서 `context.getClaims().audience(...)` 로 교체된다. `openid` 스코프가 있어도 동작한다 |
| id_token `aud` | `ACCESS_TOKEN` 타입만 손대면 그대로 client_id 다 |
| PKCE | `require-proof-key: true` 면 `code_challenge` 없는 인가 요청이 `invalid_request` 로 리다이렉트된다 |
| `iss` | `authorizationResponseHandler` / `errorResponseHandler` 로 성공·오류 리다이렉트 모두에 붙는다 |
| AS 메타데이터 | `authorizationServerMetadataCustomizer` 로 claim 추가 가능. 기본 메타데이터에 `code_challenge_methods_supported: ["S256"]` 이 이미 있다 |
| Spring PRM 필터 | 요청 경로에서 `resource` 를 만든다. `/.well-known/oauth-protected-resource/mcp` → `http://localhost:8111/mcp`. 기본값이 `tls_client_certificate_bound_access_tokens: true` 라 **반드시 false 로 바꿔야** 한다 |
| audience 검증 | `spring.security.oauth2.resourceserver.jwt.audiences` 로 붙는다. 틀리면 401 `invalid_token` ("aud claim is not valid") |
| MCP 전송 빈 | 자동설정 빈이 `@ConditionalOnMissingBean` 이라 직접 정의해 `securityValidator(...)` 를 줄 수 있다 |
| Origin/Host | `Origin` 없는 서버 간 요청은 통과. 허용 밖 Origin 403, 허용 밖 Host 421 |
| 협상된 프로토콜 | `initialize` 응답의 `protocolVersion` 은 `2025-11-25`, 응답에 `Mcp-Session-Id` 가 실린다 |
| 토큰 갱신 | `AuthorizedClientServiceOAuth2AuthorizedClientManager` 의 기본 provider 는 client_credentials 뿐이다. refresh provider 를 직접 넣어야 만료된 토큰이 갱신된다 |

## File Structure

세 practice 가 같은 구조를 공유한다(패키지만 다르다).

**인가 서버**

| 파일 | 책임 |
|---|---|
| `McpResourceProperties` | `mcp.authorization.resources` — 이 AS 가 토큰을 발급해 줄 수 있는 보호 리소스 목록 + 공통 상수 |
| `ResourceIndicatorValidator` | 인가 요청의 `resource` 검사(RFC 8707). 모르는 값이면 `invalid_target` |
| `ResourceAudienceTokenCustomizer` | access token 의 `aud` 를 `resource` 로 발급. 인가/토큰 요청 값 불일치도 여기서 막는다 |
| `IssuerIdentifyingAuthorizationResponseHandler` | 인가 응답(성공·오류)에 `iss` 추가(RFC 9207) |
| `AuthorizationServerConfig` (official/chat-memory) | 필터체인 두 개와 위 요소 배선 |
| `McpAuthorizationStandardConfig` (community) | 모듈 자동설정 확장점으로 같은 요소 배선 |

**MCP 서버**

| 파일 | 책임 |
|---|---|
| `SecurityConfig` | 경로형 PRM, `resource_metadata` 를 가리키는 401 챌린지, audience 검증 |
| `McpTransportConfig` (official/chat-memory) | 전송 빈을 직접 만들어 Origin/Host 검증기 장착 |

**에이전트**

| 파일 | 책임 |
|---|---|
| `McpAuthorizationProperties` | `mcp.authorization.resource-url`, `credentials-issuer` |
| `DiscoveredAuthorization` | 발견 결과(resource, issuer, AS 메타데이터) |
| `McpDiscoveryException` | 발견 실패 |
| `McpAuthorizationDiscovery` | 401 → PRM → AS 메타데이터 발견과 검증 |
| `DiscoveredClientRegistrationRepository` | 발견 결과 + 설정된 자격증명으로 `ClientRegistration` 생성(지연·캐시), issuer 바인딩 검사 |
| `ResourceIndicators` | 인가·토큰 요청에 `resource` 를 싣는 커스터마이저 |
| `AuthorizationResponseIssuerFilter` | 콜백의 `iss` 검증(RFC 9207) |
| `LoginFailureHandler` | 로그인 실패를 401 본문으로 알린다(리다이렉트 루프 방지) |
| `SecurityConfig` | oauth2Login 배선(PKCE·resource·iss·loginPage) |
| `McpSecurityConfig` | 발견·등록소·토큰 응답 클라이언트·인가 클라이언트 매니저 빈 |

---

## Task 1: official 인가 서버 — PKCE·RFC 8707 audience·RFC 9207 iss

**Files:**
- Create: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/McpResourceProperties.java`
- Create: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/ResourceIndicatorValidator.java`
- Create: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/ResourceAudienceTokenCustomizer.java`
- Create: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/IssuerIdentifyingAuthorizationResponseHandler.java`
- Create: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/AuthorizationServerConfig.java`
- Modify: `practice/mcp-security-authn-official/auth-server/src/main/resources/application.yml`
- Modify: `practice/mcp-security-authn-official/auth-server/build.gradle`
- Modify: `practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver/UserConfig.java` (javadoc)
- Modify: `practice/mcp-security-authn-official/auth-server/src/test/java/dev/starryeye/officialauthserver/AuthServerApplicationTests.java` (주석/이름)
- Test: `practice/mcp-security-authn-official/auth-server/src/test/java/dev/starryeye/officialauthserver/AuthorizationServerStandardTest.java`

**Interfaces:**
- Consumes: 없음(첫 작업)
- Produces:
  - `McpResourceProperties`: `List<String> resources()`, `boolean isAllowed(Object)`, 상수 `RESOURCE_PARAMETER = "resource"`, `INVALID_TARGET = "invalid_target"`
  - `AuthorizationServerConfig.ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported"`
  - 발급되는 access token 의 `aud` = 요청한 `resource`
  - 이후 Task 6(chat-memory)·Task 8(community)이 이 다섯 파일을 패키지만 바꿔 복사한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`auth-server/build.gradle` 의 `testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 줄 **아래**에 다음 한 줄을 추가한다(테스트가 `formLogin()`·`httpBasic()` 을 쓴다).

```gradle
	testImplementation 'org.springframework.security:spring-security-test'
```

`AuthorizationServerStandardTest.java` 를 만든다.

```java
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
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd practice/mcp-security-authn-official/auth-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*AuthorizationServerStandardTest'
```

기대: 컴파일은 되고 여러 테스트가 실패한다(메타데이터에 `authorization_response_iss_parameter_supported` 없음, 인가 응답에 `iss` 없음, PKCE 미강제, `aud` 가 client_id).

- [ ] **Step 3: `McpResourceProperties` 를 만든다**

```java
package dev.starryeye.officialauthserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 이 인가 서버가 토큰을 발급해 줄 수 있는 보호 리소스(MCP 서버) 목록.
 *
 * <p>RFC 8707 의 {@code resource} 는 "이 토큰을 어느 리소스에 쓸 것인가"를 말한다.
 * 인가 서버는 그 값을 그대로 믿으면 안 되고, 아는 리소스인지 확인한 뒤
 * 토큰의 {@code aud} 로 박아야 한다. 그래야 MCP 서버가 자기 앞으로 발급된
 * 토큰만 받아들일 수 있다.
 */
@ConfigurationProperties("mcp.authorization")
public record McpResourceProperties(List<String> resources) {

	/** RFC 8707 §2 의 요청 파라미터 이름. */
	public static final String RESOURCE_PARAMETER = "resource";

	/** RFC 8707 §2 가 정의한 오류 코드. */
	public static final String INVALID_TARGET = "invalid_target";

	/** 오류 응답의 error_uri 로 쓴다. */
	public static final String RFC_8707 = "https://www.rfc-editor.org/rfc/rfc8707#section-2";

	public McpResourceProperties {
		resources = (resources == null) ? List.of() : List.copyOf(resources);
	}

	/**
	 * 값이 문자열 하나이고 목록에 있을 때만 허용한다.
	 * {@code resource} 가 여러 개 오면 {@code String[]} 이 되는데,
	 * 이 practice 는 보호 리소스 하나만 다루므로 허용하지 않는다.
	 */
	public boolean isAllowed(Object resource) {
		return resource instanceof String value && this.resources.contains(value);
	}
}
```

- [ ] **Step 4: `ResourceIndicatorValidator` 를 만든다**

```java
package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

import java.util.function.Consumer;

/**
 * 인가 요청의 {@code resource} 를 검사한다(RFC 8707 §2.1).
 *
 * <p>토큰 발급 시점이 아니라 <b>인가 시점</b>에 먼저 막는다. 모르는 리소스를 향한
 * 요청이면 코드 자체를 발급하지 않고 클라이언트로 {@code invalid_target} 을 돌려준다.
 */
public class ResourceIndicatorValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	private final McpResourceProperties resources;

	public ResourceIndicatorValidator(McpResourceProperties resources) {
		this.resources = resources;
	}

	@Override
	public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
		OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
		Object resource = request.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER);

		if (resource != null && !this.resources.isAllowed(resource)) {
			OAuth2Error error = new OAuth2Error(McpResourceProperties.INVALID_TARGET,
					"The requested resource is not a known protected resource", McpResourceProperties.RFC_8707);
			throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
		}
	}
}
```

- [ ] **Step 5: `ResourceAudienceTokenCustomizer` 를 만든다**

```java
package dev.starryeye.officialauthserver;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

/**
 * access token 의 {@code aud} 를 요청한 {@code resource} 로 발급한다(RFC 8707 §2.2).
 *
 * <p>이것이 있어야 MCP 서버가 "내 앞으로 온 토큰"만 받아들일 수 있다. 없으면
 * 같은 인가 서버를 쓰는 다른 리소스의 토큰이 그대로 통한다(confused deputy).
 *
 * <p>id_token 은 건드리지 않는다. id_token 의 청중은 클라이언트 자신이다.
 */
public class ResourceAudienceTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

	private final McpResourceProperties resources;

	public ResourceAudienceTokenCustomizer(McpResourceProperties resources) {
		this.resources = resources;
	}

	@Override
	public void customize(JwtEncodingContext context) {
		if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
			return;
		}

		Object requested = requestedResource(context);
		Object authorized = authorizedResource(context);

		// 인가 때 지정한 리소스와 다른 리소스로 토큰을 받아가려는 시도를 막는다.
		if (requested != null && authorized != null && !requested.equals(authorized)) {
			throw invalidTarget("The requested resource does not match the authorization request");
		}

		Object resource = (requested != null) ? requested : authorized;
		if (resource == null) {
			// resource 없이 발급된 토큰은 aud 가 client_id 로 남는다.
			// MCP 서버는 audience 검증에서 그런 토큰을 거부한다.
			return;
		}
		if (!this.resources.isAllowed(resource)) {
			throw invalidTarget("The requested resource is not a known protected resource");
		}

		context.getClaims().audience(List.of((String) resource));
	}

	private static Object requestedResource(JwtEncodingContext context) {
		return (context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant)
				? grant.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER) : null;
	}

	/** 인가 요청에 실렸던 resource. refresh_token 그랜트에서도 이 값이 남아 있다. */
	private static Object authorizedResource(JwtEncodingContext context) {
		OAuth2Authorization authorization = context.getAuthorization();
		if (authorization == null) {
			return null;
		}
		OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
		return (request == null) ? null : request.getAdditionalParameters().get(McpResourceProperties.RESOURCE_PARAMETER);
	}

	private static OAuth2AuthenticationException invalidTarget(String description) {
		return new OAuth2AuthenticationException(new OAuth2Error(McpResourceProperties.INVALID_TARGET, description,
				McpResourceProperties.RFC_8707));
	}
}
```

- [ ] **Step 6: `IssuerIdentifyingAuthorizationResponseHandler` 를 만든다**

```java
package dev.starryeye.officialauthserver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인가 응답에 {@code iss} 를 싣는다(RFC 9207).
 *
 * <p>클라이언트가 여러 인가 서버를 알고 있을 때, 공격자가 자기 인가 서버의 응답을
 * 다른 인가 서버의 응답인 것처럼 흘려 보내는 mix-up 공격을 막는다. 클라이언트는
 * 받은 {@code iss} 가 자기가 요청을 보낸 인가 서버인지 확인한 뒤에야 코드를 교환한다.
 *
 * <p>성공 응답과 오류 응답 모두에 붙여야 한다(RFC 9207 §2.4). Spring Security 7.1 에는
 * RFC 9207 구현이 없어서 이 핸들러가 그 자리를 대신한다.
 */
public class IssuerIdentifyingAuthorizationResponseHandler
		implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

	static final String ISS = "iss";

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequest =
				(OAuth2AuthorizationCodeRequestAuthenticationToken) authentication;

		UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(authorizationCodeRequest.getRedirectUri())
				.queryParam(OAuth2ParameterNames.CODE, authorizationCodeRequest.getAuthorizationCode().getTokenValue());
		if (StringUtils.hasText(authorizationCodeRequest.getState())) {
			redirect.queryParam(OAuth2ParameterNames.STATE, encode(authorizationCodeRequest.getState()));
		}
		redirect.queryParam(ISS, encode(issuer()));

		this.redirectStrategy.sendRedirect(request, response, redirect.build(true).toUriString());
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
		OAuth2AuthorizationCodeRequestAuthenticationToken authorizationCodeRequest =
				(exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException codeRequestException)
						? codeRequestException.getAuthorizationCodeRequestAuthentication() : null;

		// redirect_uri 를 신뢰할 수 없으면 리다이렉트하지 않는다 — 오픈 리다이렉터가 된다.
		if (authorizationCodeRequest == null || !StringUtils.hasText(authorizationCodeRequest.getRedirectUri())) {
			response.sendError(HttpStatus.BAD_REQUEST.value(), error.toString());
			return;
		}

		UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(authorizationCodeRequest.getRedirectUri())
				.queryParam(OAuth2ParameterNames.ERROR, error.getErrorCode());
		if (StringUtils.hasText(error.getDescription())) {
			redirect.queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION, encode(error.getDescription()));
		}
		if (StringUtils.hasText(error.getUri())) {
			redirect.queryParam(OAuth2ParameterNames.ERROR_URI, encode(error.getUri()));
		}
		if (StringUtils.hasText(authorizationCodeRequest.getState())) {
			redirect.queryParam(OAuth2ParameterNames.STATE, encode(authorizationCodeRequest.getState()));
		}
		redirect.queryParam(ISS, encode(issuer()));

		this.redirectStrategy.sendRedirect(request, response, redirect.build(true).toUriString());
	}

	private static String issuer() {
		return AuthorizationServerContextHolder.getContext().getIssuer();
	}

	private static String encode(String value) {
		return UriUtils.encode(value, StandardCharsets.UTF_8);
	}
}
```

- [ ] **Step 7: `AuthorizationServerConfig` 를 만든다**

```java
package dev.starryeye.officialauthserver;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * 인가 서버 설정. MCP 인가 명세가 요구하는 것을 켠다.
 *
 * <p>필터체인을 직접 정의하므로 Boot 의 기본 인가 서버 필터체인
 * ({@code @ConditionalOnDefaultWebSecurity})은 물러난다. 인가 요청 검증기와
 * 응답 핸들러를 갈아끼우려면 이 방법뿐이다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class AuthorizationServerConfig {

	/** RFC 9207 §3 — 인가 응답에 iss 를 싣는다고 알리는 메타데이터 필드. */
	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
			McpResourceProperties resources) throws Exception {
		IssuerIdentifyingAuthorizationResponseHandler responseHandler =
				new IssuerIdentifyingAuthorizationResponseHandler();
		ResourceIndicatorValidator resourceValidator = new ResourceIndicatorValidator(resources);

		http.oauth2AuthorizationServer(authorizationServer -> {
					http.securityMatcher(authorizationServer.getEndpointsMatcher());
					authorizationServer
							.authorizationEndpoint(authorization -> authorization
									// RFC 9207: 성공·오류 응답 모두에 iss 를 싣는다.
									.authorizationResponseHandler(responseHandler)
									.errorResponseHandler(responseHandler)
									// RFC 8707: 기본 검증(redirect_uri·scope) 뒤에 resource 검증을 잇는다.
									.authenticationProviders(providers -> providers.forEach(provider -> {
										if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
											codeProvider.setAuthenticationValidator(
													new OAuth2AuthorizationCodeRequestAuthenticationValidator()
															.andThen(resourceValidator));
										}
									})))
							.authorizationServerMetadataEndpoint(metadata -> metadata
									.authorizationServerMetadataCustomizer(
											builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))
							// oauth2Login 이 id_token 을 받으려면 OIDC 가 필요하다.
							.oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
									.providerConfigurationCustomizer(
											builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true))));
				})
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				// 브라우저가 인가 엔드포인트에 로그인 없이 오면 로그인 화면으로 보낸다.
				.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
						new LoginUrlAuthenticationEntryPoint("/login"),
						new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
				.formLogin(Customizer.withDefaults())
				.build();
	}

	/**
	 * Spring 인가 서버가 이 타입의 빈을 찾아 JWT 발급 직전에 호출한다.
	 */
	@Bean
	public OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceTokenCustomizer(McpResourceProperties resources) {
		return new ResourceAudienceTokenCustomizer(resources);
	}
}
```

- [ ] **Step 8: `application.yml` 에 PKCE 강제와 허용 리소스를 넣는다**

`require-authorization-consent: false` 줄 **위**에 `require-proof-key: true` 를 넣는다. 들여쓰기는 `require-authorization-consent` 와 같다.

```yaml
            # MCP 2025-11-25 는 PKCE(S256)를 MUST 로 요구한다.
            # 이 값이 true 면 code_challenge 없는 인가 요청이 거부된다.
            require-proof-key: true
```

파일 맨 끝 `logging:` 블록 **위**에 다음을 추가한다.

```yaml
mcp:
  authorization:
    # RFC 8707. 이 인가 서버가 토큰을 발급해 줄 수 있는 보호 리소스 목록이다.
    # 여기 없는 resource 로 요청하면 invalid_target 으로 거부한다.
    resources:
      - http://localhost:8111/mcp

```

- [ ] **Step 9: `UserConfig` 의 javadoc 을 현재 사실에 맞춘다**

클래스 javadoc 전체를 아래로 교체한다(기존 문단은 "이 practice 에는 필터체인 파일이 없다"는 전제로 쓰여 있어 더 이상 맞지 않는다).

```java
/**
 * 학습용 사용자 한 명. user / password 로 로그인한다.
 *
 * <p>필터체인은 {@link AuthorizationServerConfig} 에 있다. 인가 서버용 체인과
 * 폼 로그인용 체인 두 개를 직접 정의하므로, Boot 의 기본 인가 서버 필터체인은 물러난다.
 */
```

- [ ] **Step 10: 기존 테스트의 이름과 주석을 현재 사실에 맞춘다**

`AuthServerApplicationTests` 의 `OIDC_메타데이터를_추가_설정_없이_공개한다` 테스트를 아래로 교체한다(이제 `oidc(...)` 를 우리가 켠다).

```java
	/**
	 * oauth2Login 은 openid 스코프로 id_token 을 받는다. 그 흐름이 성립하려면
	 * OIDC 디스커버리 문서가 있어야 한다 — AuthorizationServerConfig 가 oidc() 를 켠다.
	 */
	@Test
	void OIDC_메타데이터를_공개한다() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9010"));
	}
```

- [ ] **Step 11: 테스트를 돌려 통과를 확인한다**

```bash
cd practice/mcp-security-authn-official/auth-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: `AuthorizationServerStandardTest` 8개와 `AuthServerApplicationTests` 4개 전부 통과.

- [ ] **Step 12: 커밋한다**

```bash
git add practice/mcp-security-authn-official/auth-server && git commit -m "feat: official 인가 서버 — PKCE 강제, RFC 8707 audience, RFC 9207 iss"
```

---

## Task 2: official MCP 서버 — 경로형 PRM·audience 검증·Origin/Host 검증

**Files:**
- Modify: `practice/mcp-security-authn-official/shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/SecurityConfig.java`
- Create: `practice/mcp-security-authn-official/shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/McpTransportConfig.java`
- Modify: `practice/mcp-security-authn-official/shop-mcp-server/src/main/resources/application.yml`
- Test: `practice/mcp-security-authn-official/shop-mcp-server/src/test/java/dev/starryeye/officialmcpserver/McpAuthorizationStandardTest.java`

**Interfaces:**
- Consumes: Task 1 이 발급하는 토큰(`aud` = `http://localhost:8111/mcp`, `iss` = `http://localhost:9010`)
- Produces:
  - `GET /.well-known/oauth-protected-resource/mcp` → `{"resource":"http://localhost:8111/mcp","authorization_servers":["http://localhost:9010"],...}`
  - 401 챌린지: `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"`
  - Task 3 의 발견 로직이 위 두 가지를 그대로 소비한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`McpAuthorizationStandardTest.java` 를 만든다. 테스트용 RSA 키로 토큰을 직접 서명하고, JWT 디코더가 인가 서버 대신 가짜 메타데이터·JWKS 를 받도록 바꿔 끼운다. 그래야 인가 서버를 띄우지 않고도 운영 설정(issuer 검증 + audience 검증)을 그대로 검증할 수 있다.

```java
package dev.starryeye.officialmcpserver;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 인가 명세가 리소스 서버에 요구하는 것들을 검증한다.
 * RFC 9728 보호 리소스 메타데이터, 401 챌린지, audience 검증, Origin/Host 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpAuthorizationStandardTest {

	static final String ISSUER = "http://localhost:9010";
	static final String RESOURCE = "http://localhost:8111/mcp";
	static final String HOST = "localhost:8111";
	static final String KEY_ID = "test-key";

	static final RSAKey KEY;

	static {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			var keyPair = generator.generateKeyPair();
			KEY = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyID(KEY_ID)
					.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * JWT 디코더가 인가 서버에서 메타데이터·JWKS 를 받아오는 자리에 가짜 응답을 물린다.
	 * 운영 설정(issuer-uri + audiences)은 그대로 두고 네트워크만 끊는 것이라,
	 * 실제로 켜지는 검증기(issuer·audience)를 그대로 시험한다.
	 */
	@TestConfiguration
	static class StubAuthorizationServer {

		@Bean
		JwkSetUriJwtDecoderBuilderCustomizer stubbedAuthorizationServer() {
			RestTemplate restTemplate = new RestTemplate((uri, method) -> {
				String body = uri.getPath().endsWith("/jwks")
						? new JWKSet(KEY.toPublicJWK()).toString()
						: "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/oauth2/jwks\"}";
				MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
				MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(), HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				request.setResponse(response);
				return request;
			});
			return builder -> builder.restOperations(restTemplate);
		}
	}

	@Autowired
	MockMvc mockMvc;

	static String 토큰(String issuer, String audience) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject("user")
				.audience(List.of(audience))
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300))
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
	}

	static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
			"capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""";

	static MockHttpServletRequestBuilder mcp(String token, String origin, String host) {
		MockHttpServletRequestBuilder request = post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", host)
				.content(INITIALIZE);
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (origin != null) {
			request.header("Origin", origin);
		}
		return request;
	}

	@Test
	void 보호_리소스_메타데이터를_경로형으로_공개한다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", HOST))
				.andExpect(status().isOk())
				// RFC 9728 §3.3: resource 는 메타데이터 URL 을 만든 리소스 식별자와 같아야 한다.
				.andExpect(jsonPath("$.resource").value(RESOURCE))
				.andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
				.andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
				// 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다. Spring 기본값이 true 라 꺼야 한다.
				.andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
	}

	@Test
	void 토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다() throws Exception {
		this.mockMvc.perform(mcp(null, null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void aud_가_맞는_토큰이면_initialize_가_성공한다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
				.andExpect(header().exists("Mcp-Session-Id"));
	}

	@Test
	void aud_가_다른_토큰은_거부한다() throws Exception {
		// 같은 인가 서버가 발급했지만 다른 청중(클라이언트 자신)을 향한 토큰이다.
		this.mockMvc.perform(mcp(토큰(ISSUER, "official-shop-agent"), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void iss_가_다른_토큰은_거부한다() throws Exception {
		this.mockMvc.perform(mcp(토큰("http://localhost:9999", RESOURCE), null, HOST))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 허용되지_않은_Origin_은_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), "http://evil.example", HOST))
				.andExpect(status().isForbidden());
	}

	@Test
	void 허용되지_않은_Host_는_421이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, "evil.example:8111"))
				.andExpect(status().is(421));
	}

	@Test
	void Origin_없는_서버간_요청은_통과한다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-mcp-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*McpAuthorizationStandardTest'
```

기대: 경로형 PRM 404·`tls_client_certificate_bound_access_tokens` true·`aud` 미검증·Origin/Host 미검증으로 실패.

- [ ] **Step 3: `SecurityConfig` 를 교체한다**

```java
package dev.starryeye.officialmcpserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MCP 서버는 OAuth 2.0 보호 리소스다(MCP 2025-11-25 인가 §1).
 *
 * <p>여기서 켜는 것은 세 가지다.
 * <ul>
 *   <li>RFC 9728 보호 리소스 메타데이터 — 클라이언트가 인가 서버를 찾는 출발점</li>
 *   <li>401 챌린지의 {@code resource_metadata} — 그 메타데이터의 위치를 알려준다</li>
 *   <li>토큰 검증 — 서명·{@code iss}(issuer-uri)와 {@code aud}(audiences 속성)</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(resourceMetadataEntryPoint())
						.protectedResourceMetadata(metadata -> metadata
								.protectedResourceMetadataCustomizer(builder -> builder
										// MCP 클라이언트는 이 값을 보고 인가 서버로 간다.
										.authorizationServer(issuer)
										// 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다(Spring 기본값은 true).
										.tlsClientCertificateBoundAccessTokens(false))))
				// 무상태 리소스 서버다. 토큰으로만 인증하므로 CSRF 토큰을 쓰지 않는다.
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/**
	 * RFC 9728 §3.1 이 정한 규칙대로, 보호 리소스 URL 의 경로 앞에
	 * {@code /.well-known/oauth-protected-resource} 를 끼워 넣은 URL 을 알려준다.
	 * 즉 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp} 를 가리킨다.
	 */
	private static BearerTokenAuthenticationEntryPoint resourceMetadataEntryPoint() {
		BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
		entryPoint.setResourceMetadataParameterResolver(SecurityConfig::resourceMetadataUrl);
		return entryPoint;
	}

	private static String resourceMetadataUrl(HttpServletRequest request) {
		String path = request.getRequestURI();
		return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
				.replacePath(PROTECTED_RESOURCE_METADATA + ("/".equals(path) ? "" : path))
				.replaceQuery(null)
				.build()
				.toUriString();
	}
}
```

- [ ] **Step 4: `McpTransportConfig` 를 만든다**

```java
package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Streamable HTTP 전송을 직접 만든다. 자동설정 빈과 같되
 * {@code Origin}/{@code Host} 검증기를 하나 더 단다(MCP 2025-11-25 전송 §보안).
 *
 * <p>브라우저가 로컬 MCP 서버로 요청을 보내는 DNS 리바인딩 공격을 막는 장치다.
 * 서버 간 호출에는 {@code Origin} 이 없고, 없는 요청은 그대로 통과한다.
 */
@Configuration
public class McpTransportConfig {

	@Bean
	public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(JsonMapper jsonMapper,
			McpServerStreamableHttpProperties properties, @Value("${server.port}") int port) {
		return WebMvcStreamableServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.mcpEndpoint(properties.getMcpEndpoint())
				.keepAliveInterval(properties.getKeepAliveInterval())
				.disallowDelete(properties.isDisallowDelete())
				.securityValidator(DefaultServerTransportSecurityValidator.builder()
						// 브라우저에서 직접 호출할 일이 없으므로 허용 Origin 을 두지 않는다.
						// Origin 이 실려 오면 403 이다.
						.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port))
						.build())
				.build();
	}
}
```

- [ ] **Step 5: `application.yml` 에 audience 를 넣는다**

`issuer-uri: http://localhost:9010` 줄 **아래**에 같은 들여쓰기로 추가한다.

```yaml
          # RFC 8707 로 발급된 aud 를 검증한다. 이 값이 없으면 같은 인가 서버가 발급한
          # 다른 리소스용 토큰도 그대로 통과한다(confused deputy).
          audiences: http://localhost:8111/mcp
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-mcp-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: `McpAuthorizationStandardTest` 8개와 기존 `ShopMcpServerApplicationTests` 4개, `ProductToolsTest` 전부 통과.

- [ ] **Step 7: 커밋한다**

```bash
git add practice/mcp-security-authn-official/shop-mcp-server && git commit -m "feat: official MCP 서버 — 경로형 PRM, audience 검증, Origin/Host 검증"
```

---

## Task 3: official 에이전트 — 인가 서버 발견(하드코딩 제거)

**Files:**
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpAuthorizationProperties.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/DiscoveredAuthorization.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpDiscoveryException.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpAuthorizationDiscovery.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/DiscoveredClientRegistrationRepository.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/LoginFailureHandler.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpSecurityConfig.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/officialagent/SecurityConfig.java`
- Modify: `.../shop-agent/src/main/resources/application.yml`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/McpAuthorizationDiscoveryTest.java`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/DiscoveredClientRegistrationRepositoryTest.java`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/DiscoveryFixtures.java`
- Modify: `.../shop-agent/src/test/java/dev/starryeye/officialagent/ShopAgentApplicationTests.java`

(경로 앞부분은 `practice/mcp-security-authn-official` 이다.)

**Interfaces:**
- Consumes: Task 2 의 401 챌린지와 PRM, Task 1 의 AS 메타데이터
- Produces:
  - `McpAuthorizationDiscovery#discover(String resourceUrl)` → `DiscoveredAuthorization`
  - `DiscoveredAuthorization`: `resource()`, `issuer()`, `authorizationServerMetadata()`, `authorizationEndpoint()`, `tokenEndpoint()`, `jwksUri()`, `issParameterSupported()`
  - `DiscoveredClientRegistrationRepository`: `findByRegistrationId(String)`, `discovered()` → `DiscoveredAuthorization`
  - `LoginFailureHandler` (Task 4 가 iss 필터와 공유한다)
  - Task 4 가 `DiscoveredClientRegistrationRepository#discovered().resource()` 로 `resource` 값을 얻는다

- [ ] **Step 1: 발견 로직의 실패하는 테스트를 쓴다**

`McpAuthorizationDiscoveryTest.java`:

```java
package dev.starryeye.officialagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * MCP 2025-11-25 인가 §2.3 의 발견 순서를 검증한다.
 * 401 챌린지 → 보호 리소스 메타데이터 → 인가 서버 메타데이터.
 */
class McpAuthorizationDiscoveryTest {

	static final String RESOURCE = "http://localhost:8111/mcp";
	static final String ISSUER = "http://localhost:9010";

	static final String PROTECTED_RESOURCE_METADATA = """
			{"resource":"http://localhost:8111/mcp","authorization_servers":["http://localhost:9010"],\
			"bearer_methods_supported":["header"]}""";

	static final String AUTHORIZATION_SERVER_METADATA = """
			{"issuer":"http://localhost:9010","authorization_endpoint":"http://localhost:9010/oauth2/authorize",\
			"token_endpoint":"http://localhost:9010/oauth2/token","jwks_uri":"http://localhost:9010/oauth2/jwks",\
			"code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}""";

	MockRestServiceServer server;

	McpAuthorizationDiscovery discovery;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.discovery = new McpAuthorizationDiscovery(builder.build());
	}

	void 챌린지(String wwwAuthenticate) {
		this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
				.andRespond(withUnauthorizedRequest().header("WWW-Authenticate", wwwAuthenticate));
	}

	void 응답(String url, String body) {
		this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	void 없음(String url) {
		this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
	}

	@Test
	void 챌린지가_가리키는_메타데이터를_따라간다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE);

		assertThat(discovered.resource()).isEqualTo(RESOURCE);
		assertThat(discovered.issuer()).isEqualTo(ISSUER);
		assertThat(discovered.authorizationEndpoint()).isEqualTo(ISSUER + "/oauth2/authorize");
		assertThat(discovered.tokenEndpoint()).isEqualTo(ISSUER + "/oauth2/token");
		assertThat(discovered.jwksUri()).isEqualTo(ISSUER + "/oauth2/jwks");
		assertThat(discovered.issParameterSupported()).isTrue();
		this.server.verify();
	}

	@Test
	void 챌린지에_위치가_없으면_경로형_well_known_을_먼저_본다() {
		챌린지("Bearer");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE).resource()).isEqualTo(RESOURCE);
		this.server.verify();
	}

	@Test
	void 경로형이_없으면_루트_well_known_으로_간다() {
		챌린지("Bearer");
		없음("http://localhost:8111/.well-known/oauth-protected-resource/mcp");
		응답("http://localhost:8111/.well-known/oauth-protected-resource", """
				{"resource":"http://localhost:8111","authorization_servers":["http://localhost:9010"]}""");
		응답("http://localhost:9010/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		// 루트형이면 resource 는 서버 루트다. 클라이언트는 서버가 선언한 값을 그대로 쓴다.
		assertThat(this.discovery.discover(RESOURCE).resource()).isEqualTo("http://localhost:8111");
		this.server.verify();
	}

	@Test
	void 메타데이터의_resource_가_요청한_URL_과_다르면_실패한다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", """
				{"resource":"http://evil.example/mcp","authorization_servers":["http://localhost:9010"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE))
				.withMessageContaining("resource");
	}

	@Test
	void RFC8414_가_없으면_OIDC_디스커버리로_간다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		없음("http://localhost:9010/.well-known/oauth-authorization-server");
		응답("http://localhost:9010/.well-known/openid-configuration", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE).issuer()).isEqualTo(ISSUER);
		this.server.verify();
	}

	@Test
	void 메타데이터의_issuer_가_다르면_실패한다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server", """
				{"issuer":"http://evil.example","authorization_endpoint":"http://evil.example/oauth2/authorize",\
				"token_endpoint":"http://evil.example/oauth2/token","code_challenge_methods_supported":["S256"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE))
				.withMessageContaining("issuer");
	}

	@Test
	void PKCE_S256_을_광고하지_않으면_진행하지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8111/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8111/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9010/.well-known/oauth-authorization-server", """
				{"issuer":"http://localhost:9010","authorization_endpoint":"http://localhost:9010/oauth2/authorize",\
				"token_endpoint":"http://localhost:9010/oauth2/token"}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE))
				.withMessageContaining("S256");
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*McpAuthorizationDiscoveryTest'
```

기대: 클래스가 없어 컴파일 실패.

- [ ] **Step 3: 발견 결과와 예외 타입을 만든다**

`McpDiscoveryException.java`:

```java
package dev.starryeye.officialagent;

/** 인가 서버 발견이 명세대로 끝나지 않았다. 이럴 때는 토큰을 받으러 가지 않는다. */
public class McpDiscoveryException extends RuntimeException {

	public McpDiscoveryException(String message) {
		super(message);
	}
}
```

`DiscoveredAuthorization.java`:

```java
package dev.starryeye.officialagent;

import java.util.Map;

/**
 * 발견 결과. MCP 서버가 선언한 리소스 식별자와, 그 리소스를 지키는 인가 서버의 메타데이터다.
 *
 * @param resource 보호 리소스 메타데이터의 {@code resource} — 토큰 요청의 {@code resource} 로 그대로 쓴다
 * @param issuer 보호 리소스가 지목한 인가 서버
 * @param authorizationServerMetadata RFC 8414 또는 OIDC 디스커버리 문서
 */
public record DiscoveredAuthorization(String resource, String issuer, Map<String, Object> authorizationServerMetadata) {

	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	public DiscoveredAuthorization {
		authorizationServerMetadata = Map.copyOf(authorizationServerMetadata);
	}

	public String authorizationEndpoint() {
		return required("authorization_endpoint");
	}

	public String tokenEndpoint() {
		return required("token_endpoint");
	}

	public String jwksUri() {
		return required("jwks_uri");
	}

	/** RFC 9207 을 지원한다고 광고했는가. 광고했다면 응답에 iss 가 없을 때 거부해야 한다. */
	public boolean issParameterSupported() {
		return Boolean.TRUE.equals(this.authorizationServerMetadata.get(ISS_PARAMETER_SUPPORTED));
	}

	private String required(String name) {
		if (this.authorizationServerMetadata.get(name) instanceof String value) {
			return value;
		}
		throw new McpDiscoveryException("인가 서버 메타데이터에 %s 가 없다: %s".formatted(name, this.issuer));
	}
}
```

- [ ] **Step 4: `McpAuthorizationDiscovery` 를 만든다**

```java
package dev.starryeye.officialagent;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인가 서버를 발견한다(MCP 2025-11-25 인가 §2.3).
 *
 * <p>순서는 명세가 정해 두었다.
 * <ol>
 *   <li>토큰 없이 MCP 서버를 호출해 401 과 {@code WWW-Authenticate} 를 받는다</li>
 *   <li>헤더의 {@code resource_metadata} 를 따라간다. 없으면 경로형 → 루트형 well-known 순서로 찾는다</li>
 *   <li>메타데이터의 {@code resource} 가 우리가 부른 URL 과 같은지 확인한다(RFC 9728 §3.3)</li>
 *   <li>{@code authorization_servers} 의 인가 서버 메타데이터를 RFC 8414 → OIDC 순서로 찾는다</li>
 *   <li>메타데이터의 {@code issuer} 가 같은지, PKCE {@code S256} 을 지원하는지 확인한다</li>
 * </ol>
 *
 * <p>이 단계들이 있어야 "MCP 서버 주소 하나만 알면 나머지는 서버가 알려준다"가 성립한다.
 * 인가 서버 주소를 설정에 적어 두는 방식은 명세가 아니다.
 */
public class McpAuthorizationDiscovery {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

	private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

	private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
			new ParameterizedTypeReference<>() {
			};

	/** 발견용 탐침. 서버는 이 본문을 읽기 전에 401 을 준다. */
	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
			"capabilities":{},"clientInfo":{"name":"discovery-probe","version":"1.0.0"}}}""";

	private final RestClient restClient;

	public McpAuthorizationDiscovery(RestClient restClient) {
		this.restClient = restClient;
	}

	public DiscoveredAuthorization discover(String resourceUrl) {
		Map<String, Object> protectedResource = protectedResourceMetadata(resourceUrl);

		if (!(protectedResource.get("authorization_servers") instanceof List<?> servers) || servers.isEmpty()) {
			throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
		}
		String issuer = String.valueOf(servers.get(0));

		return new DiscoveredAuthorization((String) protectedResource.get("resource"), issuer,
				authorizationServerMetadata(issuer));
	}

	private Map<String, Object> protectedResourceMetadata(String resourceUrl) {
		String fromChallenge = resourceMetadataUrlFromChallenge(resourceUrl);
		if (fromChallenge != null) {
			Map<String, Object> metadata = json(fromChallenge);
			if (metadata == null) {
				throw new McpDiscoveryException("챌린지가 가리킨 메타데이터를 받지 못했다: " + fromChallenge);
			}
			return verifyResource(metadata, resourceUrl);
		}

		URI uri = URI.create(resourceUrl);
		String origin = origin(uri);
		String path = uri.getRawPath();

		if (path != null && !path.isEmpty() && !"/".equals(path)) {
			Map<String, Object> metadata = json(origin + PROTECTED_RESOURCE_METADATA + path);
			if (metadata != null) {
				return verifyResource(metadata, resourceUrl);
			}
		}

		Map<String, Object> metadata = json(origin + PROTECTED_RESOURCE_METADATA);
		if (metadata != null) {
			// 루트형 메타데이터의 리소스 식별자는 서버 루트다.
			return verifyResource(metadata, origin);
		}

		throw new McpDiscoveryException("보호 리소스 메타데이터를 찾지 못했다: " + resourceUrl);
	}

	/**
	 * RFC 9728 §3.3 — 메타데이터의 {@code resource} 는 그 메타데이터 URL 을 만든
	 * 리소스 식별자와 같아야 한다. 다르면 남의 메타데이터를 보고 있는 것이다.
	 */
	private static Map<String, Object> verifyResource(Map<String, Object> metadata, String expected) {
		if (!expected.equals(metadata.get("resource"))) {
			throw new McpDiscoveryException("메타데이터의 resource(%s) 가 요청한 리소스(%s) 와 다르다"
					.formatted(metadata.get("resource"), expected));
		}
		return metadata;
	}

	private String resourceMetadataUrlFromChallenge(String resourceUrl) {
		return this.restClient.post()
				.uri(resourceUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.body(INITIALIZE)
				.exchange((request, response) -> {
					if (response.getStatusCode().value() != 401) {
						throw new McpDiscoveryException("토큰 없는 요청에 401 이 아니라 %s 가 왔다: %s"
								.formatted(response.getStatusCode(), resourceUrl));
					}
					String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
					if (header == null) {
						return null;
					}
					Matcher matcher = RESOURCE_METADATA.matcher(header);
					return matcher.find() ? matcher.group(1) : null;
				});
	}

	private Map<String, Object> authorizationServerMetadata(String issuer) {
		for (String url : metadataUrls(issuer)) {
			Map<String, Object> metadata = json(url);
			if (metadata == null) {
				continue;
			}
			if (!issuer.equals(metadata.get("issuer"))) {
				throw new McpDiscoveryException("메타데이터의 issuer(%s) 가 요청한 인가 서버(%s) 와 다르다"
						.formatted(metadata.get("issuer"), issuer));
			}
			// MCP 2025-11-25 인가: 클라이언트는 S256 지원을 확인하고, 없으면 진행하면 안 된다.
			if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
					|| !methods.contains("S256")) {
				throw new McpDiscoveryException("인가 서버가 PKCE S256 을 광고하지 않는다: " + issuer);
			}
			return metadata;
		}
		throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
	}

	/** RFC 8414 §3.1 과 OIDC 디스커버리의 경로 규칙. MCP 는 RFC 8414 를 먼저 시도하라고 한다. */
	private static List<String> metadataUrls(String issuer) {
		URI uri = URI.create(issuer);
		String origin = origin(uri);
		String path = uri.getRawPath();

		if (path == null || path.isEmpty() || "/".equals(path)) {
			return List.of(origin + AUTHORIZATION_SERVER_METADATA, origin + OPENID_CONFIGURATION);
		}
		return List.of(origin + AUTHORIZATION_SERVER_METADATA + path, origin + OPENID_CONFIGURATION + path,
				origin + path + OPENID_CONFIGURATION);
	}

	private Map<String, Object> json(String url) {
		return this.restClient.get()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
						? response.bodyTo(JSON_OBJECT) : null);
	}

	private static String origin(URI uri) {
		return uri.getScheme() + "://" + uri.getRawAuthority();
	}
}
```

- [ ] **Step 5: 발견 테스트를 돌려 통과를 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*McpAuthorizationDiscoveryTest'
```

기대: 7개 전부 통과.

- [ ] **Step 6: 등록 저장소의 실패하는 테스트를 쓴다**

`DiscoveryFixtures.java` (테스트 전용 고정값):

```java
package dev.starryeye.officialagent;

import java.util.List;
import java.util.Map;

final class DiscoveryFixtures {

	static final String RESOURCE = "http://localhost:8111/mcp";

	static final String ISSUER = "http://localhost:9010";

	private DiscoveryFixtures() {
	}

	static DiscoveredAuthorization discovered() {
		return discovered(ISSUER);
	}

	static DiscoveredAuthorization discovered(String issuer) {
		return new DiscoveredAuthorization(RESOURCE, issuer, Map.of(
				"issuer", issuer,
				"authorization_endpoint", issuer + "/oauth2/authorize",
				"token_endpoint", issuer + "/oauth2/token",
				"jwks_uri", issuer + "/oauth2/jwks",
				"code_challenge_methods_supported", List.of("S256"),
				"authorization_response_iss_parameter_supported", true));
	}
}
```

`DiscoveredClientRegistrationRepositoryTest.java`:

```java
package dev.starryeye.officialagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class DiscoveredClientRegistrationRepositoryTest {

	McpAuthorizationDiscovery discovery = mock(McpAuthorizationDiscovery.class);

	OAuth2ClientProperties clientProperties = new OAuth2ClientProperties();

	@BeforeEach
	void 자격증명을_설정한다() {
		OAuth2ClientProperties.Registration registration = new OAuth2ClientProperties.Registration();
		registration.setClientId("official-shop-agent");
		registration.setClientSecret("official-shop-agent-secret");
		registration.setAuthorizationGrantType("authorization_code");
		registration.setRedirectUri("{baseUrl}/login/oauth2/code/{registrationId}");
		registration.setScope(java.util.Set.of("openid", "profile"));
		this.clientProperties.getRegistration().put("authserver", registration);
	}

	DiscoveredClientRegistrationRepository repository() {
		return new DiscoveredClientRegistrationRepository(this.discovery,
				new McpAuthorizationProperties(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER),
				this.clientProperties);
	}

	@Test
	void 발견한_엔드포인트로_등록을_만든다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE)).willReturn(DiscoveryFixtures.discovered());

		var registration = repository().findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("official-shop-agent");
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		assertThat(registration.getProviderDetails().getTokenUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/token");
		assertThat(registration.getProviderDetails().getJwkSetUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/jwks");
		assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile");
		// RFC 9207 지원 여부는 콜백 검증에서 쓰므로 등록에 실어 둔다.
		assertThat(registration.getProviderDetails().getConfigurationMetadata())
				.containsEntry("authorization_response_iss_parameter_supported", true);
	}

	@Test
	void 모르는_등록_ID_는_null_이다() {
		assertThat(repository().findByRegistrationId("other")).isNull();
	}

	@Test
	void 발견은_한_번만_한다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE)).willReturn(DiscoveryFixtures.discovered());
		DiscoveredClientRegistrationRepository repository = repository();

		repository.findByRegistrationId("authserver");
		repository.findByRegistrationId("authserver");
		repository.discovered();

		then(this.discovery).should(times(1)).discover(anyString());
	}

	@Test
	void 자격증명이_묶인_인가_서버가_아니면_쓰지_않는다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE))
				.willReturn(DiscoveryFixtures.discovered("http://evil.example"));

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> repository().findByRegistrationId("authserver"))
				.withMessageContaining("http://evil.example");
	}

	@Test
	void 실패는_캐시하지_않는다() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE))
				.willThrow(new McpDiscoveryException("서버가 아직 뜨지 않았다"))
				.willReturn(DiscoveryFixtures.discovered());
		DiscoveredClientRegistrationRepository repository = repository();

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> repository.findByRegistrationId("authserver"));

		assertThat(repository.findByRegistrationId("authserver")).isNotNull();
	}
}
```

- [ ] **Step 7: `McpAuthorizationProperties` 와 `DiscoveredClientRegistrationRepository` 를 만든다**

`McpAuthorizationProperties.java`:

```java
package dev.starryeye.officialagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resourceUrl 보호 리소스(MCP 엔드포인트)의 URL. 발견은 여기서 시작한다
 * @param credentialsIssuer 아래 client-id/secret 이 등록된 인가 서버.
 *        발견 결과가 이것과 다르면 자격증명을 보내지 않는다(MCP 2026-07-28: 자격증명은 issuer 에 묶인다)
 */
@ConfigurationProperties("mcp.authorization")
public record McpAuthorizationProperties(String resourceUrl, String credentialsIssuer) {
}
```

`DiscoveredClientRegistrationRepository.java`:

```java
package dev.starryeye.officialagent;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * 설정 파일에 인가 서버 주소를 적는 대신, MCP 서버에게 물어서 등록을 만든다.
 *
 * <p>발견은 처음 필요할 때 한 번만 하고 결과를 캐시한다. 기동 시점에 하지 않는 이유는
 * MCP 서버가 아직 떠 있지 않아도 에이전트는 떠야 하기 때문이다. 실패한 발견은
 * 캐시하지 않으므로 다음 요청에서 다시 시도한다.
 *
 * <p>자격증명(client_id/secret)은 특정 인가 서버에 등록된 것이다. 발견 결과가 다른 인가
 * 서버를 가리키면 자격증명을 보내지 않고 멈춘다 — 가짜 인가 서버로 비밀을 흘리지 않기 위해서다.
 */
public class DiscoveredClientRegistrationRepository implements ClientRegistrationRepository {

	private final McpAuthorizationDiscovery discovery;

	private final McpAuthorizationProperties properties;

	private final String registrationId;

	private final OAuth2ClientProperties.Registration credentials;

	private volatile Discovered discovered;

	public DiscoveredClientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
		Assert.state(clientProperties.getRegistration().size() == 1,
				"spring.security.oauth2.client.registration 은 정확히 하나여야 한다");
		Map.Entry<String, OAuth2ClientProperties.Registration> registration =
				clientProperties.getRegistration().entrySet().iterator().next();
		this.discovery = discovery;
		this.properties = properties;
		this.registrationId = registration.getKey();
		this.credentials = registration.getValue();
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		return this.registrationId.equals(registrationId) ? state().registration() : null;
	}

	/** 발견한 리소스 식별자·인가 서버 메타데이터. {@code resource} 파라미터와 iss 검증이 쓴다. */
	public DiscoveredAuthorization discovered() {
		return state().authorization();
	}

	private Discovered state() {
		Discovered current = this.discovered;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.discovered == null) {
				DiscoveredAuthorization authorization = this.discovery.discover(this.properties.resourceUrl());
				this.discovered = new Discovered(authorization, registration(authorization));
			}
			return this.discovered;
		}
	}

	private ClientRegistration registration(DiscoveredAuthorization authorization) {
		if (!this.properties.credentialsIssuer().equals(authorization.issuer())) {
			throw new McpDiscoveryException(
					"자격증명은 %s 에 등록된 것인데 발견한 인가 서버는 %s 다 — 자격증명을 보내지 않는다"
							.formatted(this.properties.credentialsIssuer(), authorization.issuer()));
		}
		return ClientRegistration.withRegistrationId(this.registrationId)
				.clientId(this.credentials.getClientId())
				.clientSecret(this.credentials.getClientSecret())
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri(this.credentials.getRedirectUri())
				.scope(this.credentials.getScope())
				.authorizationUri(authorization.authorizationEndpoint())
				.tokenUri(authorization.tokenEndpoint())
				.jwkSetUri(authorization.jwksUri())
				.issuerUri(authorization.issuer())
				.providerConfigurationMetadata(authorization.authorizationServerMetadata())
				.userNameAttributeName(IdTokenClaimNames.SUB)
				.clientName(this.registrationId)
				.build();
	}

	private record Discovered(DiscoveredAuthorization authorization, ClientRegistration registration) {
	}
}
```

- [ ] **Step 8: `LoginFailureHandler` 를 만든다**

```java
package dev.starryeye.officialagent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * 로그인 실패를 401 본문으로 알린다.
 *
 * <p>기본 동작은 로그인 페이지로 되돌리는 것인데, 이 앱의 로그인 페이지는 곧 인가 요청이라
 * 실패할 때마다 다시 인가 서버로 가는 고리가 된다. 실패 이유를 그대로 보여주고 멈춘다.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		log.warn("로그인 실패: {}", exception.getMessage());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("text/plain;charset=UTF-8");
		response.getWriter().write("로그인 실패: " + exception.getMessage());
	}
}
```

- [ ] **Step 9: `McpSecurityConfig` 에 발견·등록소 빈을 추가한다**

클래스 선언 위에 `@EnableConfigurationProperties(McpAuthorizationProperties.class)` 를 붙이고(`import org.springframework.boot.context.properties.EnableConfigurationProperties;`), `REGISTRATION_ID` 상수 **아래**에 다음 두 빈을 추가한다. 필요한 import 는 `org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties`, `org.springframework.web.client.RestClient` 다.

```java
	/**
	 * 발견용 HTTP 클라이언트. MCP 서버와 인가 서버의 메타데이터만 읽는다.
	 */
	@Bean
	public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
		return new McpAuthorizationDiscovery(RestClient.create());
	}

	/**
	 * Boot 가 만드는 기본 등록 저장소(설정의 issuer-uri 로 만드는 것) 대신 이것을 쓴다.
	 */
	@Bean
	public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
		return new DiscoveredClientRegistrationRepository(discovery, properties, clientProperties);
	}
```

- [ ] **Step 10: `SecurityConfig` 의 로그인 진입점을 명시한다**

발견 저장소는 등록을 미리 나열하지 않으므로(기동 시 발견하지 않으므로) 기본 로그인 링크 화면을 만들 수 없다. 로그인 진입점을 인가 요청 URL 로 직접 지정한다. `SecurityConfig` 를 아래로 교체한다.

```java
package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(login -> login
						// 등록이 하나뿐이고, 그 등록은 발견해야 알 수 있다.
						// 로그인 화면 대신 곧바로 인가 요청으로 보낸다.
						.loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.failureHandler(new LoginFailureHandler()))
				.oauth2Client(Customizer.withDefaults())
				// 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
				.build();
	}
}
```

- [ ] **Step 11: `application.yml` 에서 issuer 하드코딩을 걷어내고 발견 설정을 넣는다**

`spring.security.oauth2.client` 아래의 `provider:` 블록(두 줄 + issuer-uri)을 **삭제**하고, `registration.authserver` 블록 위에 주석을 남긴다. 그리고 `logging:` 위에 `mcp:` 블록을 추가한다.

```yaml
  security:
    oauth2:
      client:
        registration:
          # 여기에는 자격증명만 둔다. 인가 서버의 엔드포인트는 설정에 적지 않고
          # MCP 서버에게 물어서 알아낸다(McpAuthorizationDiscovery).
          authserver:
            client-id: official-shop-agent
            client-secret: official-shop-agent-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile

mcp:
  authorization:
    # 발견의 출발점. 이 URL 하나만 알면 인가 서버는 따라온다.
    resource-url: http://localhost:8111/mcp
    # 위 자격증명이 등록된 인가 서버. 발견 결과가 다르면 자격증명을 보내지 않는다.
    credentials-issuer: http://localhost:9010
```

- [ ] **Step 12: 애플리케이션 테스트를 발견 기반으로 고친다**

`ShopAgentApplicationTests` 에 발견을 대신하는 스텁을 넣고, 등록 검증을 발견 결과 기준으로 바꾼다. 클래스 상단(`@Autowired MockMvc mockMvc;` 위)에 추가한다.

```java
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	McpAuthorizationDiscovery discovery;

	@org.junit.jupiter.api.BeforeEach
	void 발견_결과를_고정한다() {
		org.mockito.BDDMockito.given(this.discovery.discover(DiscoveryFixtures.RESOURCE))
				.willReturn(DiscoveryFixtures.discovered());
	}
```

`OAuth2_클라이언트_등록이_정확히_하나다` 테스트 본문을 아래로 교체한다.

```java
		var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("official-shop-agent");
		// 엔드포인트는 설정이 아니라 발견 결과에서 온다.
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
```

- [ ] **Step 13: 테스트를 돌려 통과를 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 발견 7개, 등록소 5개, 기존 에이전트 테스트 전부 통과.

- [ ] **Step 14: 커밋한다**

```bash
git add practice/mcp-security-authn-official/shop-agent && git commit -m "feat: official 에이전트 — 401·PRM·AS 메타데이터로 인가 서버 발견"
```

---

## Task 4: official 에이전트 — PKCE·resource·iss 검증·토큰 갱신

**Files:**
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/ResourceIndicators.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/AuthorizationResponseIssuerFilter.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/officialagent/SecurityConfig.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpSecurityConfig.java`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/AuthorizationResponseIssuerFilterTest.java`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/TokenRefreshTest.java`
- Modify: `.../shop-agent/src/test/java/dev/starryeye/officialagent/ShopAgentApplicationTests.java`

**Interfaces:**
- Consumes: Task 3 의 `DiscoveredClientRegistrationRepository#discovered()`, `LoginFailureHandler`
- Produces:
  - `ResourceIndicators.authorizationRequest(Supplier<String>)` → `Consumer<OAuth2AuthorizationRequest.Builder>`
  - `ResourceIndicators.tokenRequest(Supplier<String>)` → `Converter<T, MultiValueMap<String,String>>` (`T extends AbstractOAuth2AuthorizationGrantRequest`)
  - `McpSecurityConfig.authorizedClientManager(ClientRegistrationRepository, OAuth2AuthorizedClientService, OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>)` (static, 테스트가 직접 호출한다)
  - 인가 요청에 `code_challenge`·`code_challenge_method=S256`·`resource`, 토큰/갱신 요청에 `resource`

- [ ] **Step 1: iss 검증 필터의 실패하는 테스트를 쓴다**

`AuthorizationResponseIssuerFilterTest.java`:

```java
package dev.starryeye.officialagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 9207 — 콜백의 iss 가 요청을 보낸 인가 서버와 같은지 확인한다.
 * mix-up 공격(다른 인가 서버의 응답을 끼워 넣기)을 여기서 막는다.
 */
class AuthorizationResponseIssuerFilterTest {

	static final String ISSUER = "http://localhost:9010";

	HttpSessionOAuth2AuthorizationRequestRepository authorizationRequests =
			new HttpSessionOAuth2AuthorizationRequestRepository();

	MockHttpServletRequest request;

	MockHttpServletResponse response = new MockHttpServletResponse();

	MockFilterChain chain = new MockFilterChain();

	@BeforeEach
	void 인가_요청을_저장해_둔다() {
		this.request = new MockHttpServletRequest("GET", "/login/oauth2/code/authserver");
		OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri(ISSUER + "/oauth2/authorize")
				.clientId("official-shop-agent")
				.redirectUri("http://localhost:8110/login/oauth2/code/authserver")
				.state("state-1")
				.attributes(attributes -> attributes.put(OAuth2ParameterNames.REGISTRATION_ID, "authserver"))
				.build();
		this.authorizationRequests.saveAuthorizationRequest(authorizationRequest, this.request, this.response);
		this.request.setParameter("state", "state-1");
		this.request.setParameter("code", "code-1");
	}

	AuthorizationResponseIssuerFilter filter(boolean issAdvertised) {
		ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
				.clientId("official-shop-agent")
				.clientSecret("official-shop-agent-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri(ISSUER + "/oauth2/authorize")
				.tokenUri(ISSUER + "/oauth2/token")
				.issuerUri(ISSUER)
				.providerConfigurationMetadata(
						Map.of(DiscoveredAuthorization.ISS_PARAMETER_SUPPORTED, issAdvertised))
				.build();
		return new AuthorizationResponseIssuerFilter(this.authorizationRequests,
				new InMemoryClientRegistrationRepository(registration), new LoginFailureHandler());
	}

	@Test
	void iss_가_같으면_통과시킨다() throws Exception {
		this.request.setParameter("iss", ISSUER);

		filter(true).doFilter(this.request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
	}

	@Test
	void iss_가_다르면_코드를_교환하지_않는다() throws Exception {
		this.request.setParameter("iss", "http://evil.example");

		filter(true).doFilter(this.request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(401);
		// 저장해 둔 인가 요청도 버린다. 같은 state 로 다시 시도하지 못하게 한다.
		assertThat(this.authorizationRequests.loadAuthorizationRequest(this.request)).isNull();
	}

	@Test
	void 지원한다고_광고했는데_iss_가_없으면_거부한다() throws Exception {
		filter(true).doFilter(this.request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(401);
	}

	@Test
	void 광고하지_않은_인가_서버라면_iss_없이도_통과시킨다() throws Exception {
		filter(false).doFilter(this.request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void 콜백이_아닌_요청은_건드리지_않는다() throws Exception {
		MockHttpServletRequest other = new MockHttpServletRequest("GET", "/");

		filter(true).doFilter(other, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*AuthorizationResponseIssuerFilterTest'
```

기대: 클래스가 없어 컴파일 실패.

- [ ] **Step 3: `AuthorizationResponseIssuerFilter` 를 만든다**

```java
package dev.starryeye.officialagent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 인가 응답의 {@code iss} 를 검증한다(RFC 9207 §2.4).
 *
 * <p>클라이언트는 코드를 교환하기 <b>전에</b> 확인해야 한다. 확인 없이 교환하면,
 * 공격자가 자기 인가 서버에서 받은 코드를 우리 콜백에 흘려 넣어 우리가 신뢰하는
 * 인가 서버의 코드인 것처럼 쓰게 만들 수 있다(mix-up).
 *
 * <p>Spring Security 의 로그인 필터는 {@code iss} 를 읽지 않으므로 그 앞에 선다.
 */
public class AuthorizationResponseIssuerFilter extends OncePerRequestFilter {

	private static final String ISS = "iss";

	private static final String RFC_9207 = "https://www.rfc-editor.org/rfc/rfc9207#section-2.4";

	private final RequestMatcher callback =
			PathPatternRequestMatcher.withDefaults().matcher("/login/oauth2/code/*");

	private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests;

	private final ClientRegistrationRepository registrations;

	private final AuthenticationFailureHandler failureHandler;

	public AuthorizationResponseIssuerFilter(
			AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests,
			ClientRegistrationRepository registrations, AuthenticationFailureHandler failureHandler) {
		this.authorizationRequests = authorizationRequests;
		this.registrations = registrations;
		this.failureHandler = failureHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!this.callback.matches(request)) {
			chain.doFilter(request, response);
			return;
		}

		OAuth2AuthorizationRequest authorizationRequest = this.authorizationRequests.loadAuthorizationRequest(request);
		if (authorizationRequest == null) {
			// 저장된 요청이 없으면 로그인 필터가 authorization_request_not_found 로 처리한다.
			chain.doFilter(request, response);
			return;
		}

		String registrationId = authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
		ClientRegistration registration = (registrationId == null) ? null
				: this.registrations.findByRegistrationId(registrationId);
		if (registration == null) {
			chain.doFilter(request, response);
			return;
		}

		String problem = problem(registration, request.getParameter(ISS));
		if (problem == null) {
			chain.doFilter(request, response);
			return;
		}

		this.authorizationRequests.removeAuthorizationRequest(request, response);
		this.failureHandler.onAuthenticationFailure(request, response,
				new OAuth2AuthenticationException(new OAuth2Error("invalid_request", problem, RFC_9207)));
	}

	private static String problem(ClientRegistration registration, String iss) {
		String expected = registration.getProviderDetails().getIssuerUri();
		boolean advertised = Boolean.TRUE.equals(registration.getProviderDetails().getConfigurationMetadata()
				.get(DiscoveredAuthorization.ISS_PARAMETER_SUPPORTED));

		if (iss == null) {
			return advertised ? "iss is missing although the authorization server advertises it" : null;
		}
		return iss.equals(expected) ? null
				: "iss mismatch: expected %s but got %s".formatted(expected, iss);
	}
}
```

- [ ] **Step 4: 필터 테스트가 통과하는지 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test --tests '*AuthorizationResponseIssuerFilterTest'
```

기대: 5개 통과.

- [ ] **Step 5: 토큰 갱신의 실패하는 테스트를 쓴다**

`TokenRefreshTest.java`:

```java
package dev.starryeye.officialagent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 만료된 액세스 토큰은 refresh 로 갱신되어야 하고, 갱신 요청에도 resource 가 실려야 한다.
 * resource 가 빠지면 갱신된 토큰의 aud 가 달라져 MCP 서버가 거부한다.
 */
class TokenRefreshTest {

	static final String ISSUER = "http://localhost:9010";

	static final String RESOURCE = "http://localhost:8111/mcp";

	@Test
	void 만료된_토큰을_resource_를_실어_갱신한다() {
		ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
				.clientId("official-shop-agent")
				.clientSecret("official-shop-agent-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.authorizationUri(ISSUER + "/oauth2/authorize")
				.tokenUri(ISSUER + "/oauth2/token")
				.build();
		var registrations = new InMemoryClientRegistrationRepository(registration);
		var authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);

		var principal = new TestingAuthenticationToken("user", null, "ROLE_USER");
		var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
				Instant.now().minusSeconds(600), Instant.now().minusSeconds(300));
		authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", expired,
				new OAuth2RefreshToken("refresh-1", Instant.now().minusSeconds(600))), principal);

		RestClient.Builder builder = RestClient.builder()
				.messageConverters(converters -> {
					converters.add(new FormHttpMessageConverter());
					converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
				})
				.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		var refreshTokenClient = new RestClientRefreshTokenTokenResponseClient();
		refreshTokenClient.setRestClient(builder.build());
		refreshTokenClient.addParametersConverter(ResourceIndicators.tokenRequest(() -> RESOURCE));

		server.expect(requestTo(ISSUER + "/oauth2/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().formDataContains(Map.of(
						"grant_type", "refresh_token",
						"refresh_token", "refresh-1",
						"resource", RESOURCE)))
				.andRespond(withSuccess("""
						{"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
						MediaType.APPLICATION_JSON));

		var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients, refreshTokenClient);
		OAuth2AuthorizedClient authorized = manager.authorize(OAuth2AuthorizeRequest
				.withClientRegistrationId("authserver").principal(principal).build());

		assertThat(authorized.getAccessToken().getTokenValue()).isEqualTo("new-token");
		server.verify();
	}
}
```

- [ ] **Step 6: `ResourceIndicators` 를 만든다**

```java
package dev.starryeye.officialagent;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.endpoint.AbstractOAuth2AuthorizationGrantRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * RFC 8707 {@code resource} 를 인가·토큰 요청에 싣는다.
 *
 * <p>"이 토큰은 이 MCP 서버에 쓸 것"이라고 인가 서버에 알리는 값이다. 이것이 있어야
 * 인가 서버가 {@code aud} 를 그 서버로 좁혀 발급하고, 토큰이 다른 리소스에서 재사용되지 않는다.
 *
 * <p>값은 발견 결과에서 오므로 {@link Supplier} 로 받는다 — 설정 시점에는 아직 모른다.
 */
public final class ResourceIndicators {

	public static final String PARAMETER = "resource";

	private ResourceIndicators() {
	}

	/** 인가 요청(브라우저 리다이렉트)에 resource 를 싣는다. */
	public static Consumer<OAuth2AuthorizationRequest.Builder> authorizationRequest(Supplier<String> resource) {
		return builder -> builder.additionalParameters(parameters -> parameters.put(PARAMETER, resource.get()));
	}

	/** 토큰·갱신 요청(백채널)에 resource 를 싣는다. */
	public static <T extends AbstractOAuth2AuthorizationGrantRequest> Converter<T, MultiValueMap<String, String>>
			tokenRequest(Supplier<String> resource) {
		return grantRequest -> {
			MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
			parameters.add(PARAMETER, resource.get());
			return parameters;
		};
	}
}
```

- [ ] **Step 7: `McpSecurityConfig` 를 최종 형태로 교체한다**

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * MCP 호출에 쓸 토큰을 마련하는 배선.
 *
 * <p>인가 서버의 위치는 설정이 아니라 발견에서 온다. 토큰 요청과 갱신 요청에는
 * RFC 8707 {@code resource} 를 실어, 발급되는 토큰이 이 MCP 서버 전용이 되게 한다.
 */
@Configuration
@EnableConfigurationProperties(McpAuthorizationProperties.class)
public class McpSecurityConfig {

	/** application.yml 의 registration 키와 같아야 한다. */
	static final String REGISTRATION_ID = "authserver";

	@Bean
	public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
		return new McpAuthorizationDiscovery(RestClient.create());
	}

	@Bean
	public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
		return new DiscoveredClientRegistrationRepository(discovery, properties, clientProperties);
	}

	/**
	 * 인가된 클라이언트를 세션이 아니라 서비스에 저장한다. 서블릿 요청 없이
	 * {@code Authentication} 만으로 토큰을 꺼낼 수 있어야 리액터 스레드에서도 토큰을 붙인다.
	 */
	@Bean
	public OAuth2AuthorizedClientService authorizedClientService(
			ClientRegistrationRepository clientRegistrationRepository) {
		return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
	}

	/** 로그인(코드 교환) 때 쓰는 토큰 요청 클라이언트. resource 를 함께 보낸다. */
	@Bean
	public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
			DiscoveredClientRegistrationRepository registrations) {
		var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
		tokenResponseClient.addParametersConverter(
				ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
		return tokenResponseClient;
	}

	/** 액세스 토큰이 만료된 뒤 쓰는 갱신 클라이언트. 여기에도 resource 가 필요하다. */
	@Bean
	public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
			DiscoveredClientRegistrationRepository registrations) {
		var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
		tokenResponseClient.addParametersConverter(
				ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
		return tokenResponseClient;
	}

	@Bean
	public OAuth2AuthorizedClientManager authorizedClientManager(
			ClientRegistrationRepository clientRegistrationRepository,
			OAuth2AuthorizedClientService authorizedClientService,
			RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient) {
		return authorizedClientManager(clientRegistrationRepository, authorizedClientService,
				refreshTokenTokenResponseClient);
	}

	/**
	 * 이 매니저의 기본 구성에는 갱신이 들어 있지 않다. refresh provider 를 직접 넣어야
	 * 만료된 토큰이 갱신된다.
	 */
	static AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
			ClientRegistrationRepository clientRegistrationRepository,
			OAuth2AuthorizedClientService authorizedClientService,
			OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
		var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository,
				authorizedClientService);
		manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
				.refreshToken(refreshToken -> refreshToken.accessTokenResponseClient(refreshTokenTokenResponseClient))
				.build());
		return manager;
	}

	/** 모든 MCP 동기 클라이언트에 인증 전달용 컨텍스트 공급자를 꽂는다. */
	@Bean
	public McpClientCustomizer<McpClient.SyncSpec> mcpAuthenticationCustomizer() {
		return (name, spec) -> spec.transportContextProvider(new SecurityMcpTransportContextProvider());
	}

	/** 모든 streamable-HTTP 전송에 토큰 부착 커스터마이저를 꽂는다. */
	@Bean
	public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTokenAttachingCustomizer(
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return (name, transport) -> transport.httpRequestCustomizer(
				new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager, REGISTRATION_ID));
	}
}
```

- [ ] **Step 8: `SecurityConfig` 를 최종 형태로 교체한다**

```java
package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.authentication.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 로그인 = MCP 호출에 쓸 토큰을 받는 과정이다. 명세가 요구하는 세 가지를 여기서 건다.
 *
 * <ul>
 *   <li>PKCE(S256) — 가로챈 인가 코드를 쓰지 못하게 한다</li>
 *   <li>RFC 8707 {@code resource} — 토큰을 이 MCP 서버 전용으로 좁힌다</li>
 *   <li>RFC 9207 {@code iss} 검증 — 코드를 교환하기 전에 응답의 출처를 확인한다</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			DiscoveredClientRegistrationRepository registrations,
			OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient)
			throws Exception {
		// iss 검증 필터와 로그인 필터가 같은 저장소를 봐야 한다.
		var authorizationRequests = new HttpSessionOAuth2AuthorizationRequestRepository();
		var failureHandler = new LoginFailureHandler();

		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(login -> login
						// 등록이 하나뿐이고, 그 등록은 발견해야 알 수 있다.
						// 로그인 화면 대신 곧바로 인가 요청으로 보낸다.
						.loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.authorizationEndpoint(authorization -> authorization
								.authorizationRequestRepository(authorizationRequests)
								.authorizationRequestResolver(authorizationRequestResolver(registrations)))
						.tokenEndpoint(token -> token.accessTokenResponseClient(authorizationCodeTokenResponseClient))
						.failureHandler(failureHandler))
				.addFilterBefore(new AuthorizationResponseIssuerFilter(authorizationRequests, registrations,
						failureHandler), OAuth2LoginAuthenticationFilter.class)
				.oauth2Client(Customizer.withDefaults())
				// 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
				.build();
	}

	private static OAuth2AuthorizationRequestResolver authorizationRequestResolver(
			DiscoveredClientRegistrationRepository registrations) {
		var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
				.andThen(ResourceIndicators.authorizationRequest(() -> registrations.discovered().resource())));
		return resolver;
	}
}
```

- [ ] **Step 9: 인가 요청에 PKCE 와 resource 가 실리는지 테스트를 추가한다**

`ShopAgentApplicationTests` 에 다음 테스트를 추가한다.

```java
	/**
	 * 브라우저로 보내는 인가 요청에 PKCE 챌린지와 resource 가 실려야 한다.
	 * 둘 중 하나라도 빠지면 인가 서버가 거부하거나(PKCE), 토큰의 aud 가 좁혀지지 않는다(resource).
	 */
	@Test
	void 인가_요청에_PKCE_와_resource_가_실린다() throws Exception {
		String location = mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();

		var parameters = org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build()
				.getQueryParams();

		assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.getFirst("code_challenge")).isNotBlank();
		assertThat(org.springframework.web.util.UriUtils.decode(parameters.getFirst("resource"),
				java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(DiscoveryFixtures.RESOURCE);
	}
```

- [ ] **Step 10: 전체 테스트를 돌려 통과를 확인한다**

```bash
cd practice/mcp-security-authn-official/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 발견 7개, 등록소 5개, iss 필터 5개, 갱신 1개, 애플리케이션 테스트 6개 전부 통과.

- [ ] **Step 11: 커밋한다**

```bash
git add practice/mcp-security-authn-official/shop-agent && git commit -m "feat: official 에이전트 — PKCE S256, resource 파라미터, iss 검증, 토큰 갱신"
```

---

## Task 5: official 종단 검증과 관측 기록

문서(Task 10)의 엔드포인트 명세는 **명세 문서를 기준**으로 쓰고, 그 옆에 **실제로 주고받은 값**을 붙인다. 이 작업이 그 관측값을 만든다.

**Files:**
- Create: `docs/superpowers/captures/mcp-authorization-walkthrough.sh`
- Create: `docs/superpowers/captures/2026-09-12-official.txt` (스크립트 출력)

**Interfaces:**
- Consumes: Task 1~4 의 결과물
- Produces: Task 10 이 인용할 실제 요청·응답 기록

- [ ] **Step 1: 캡처 스크립트를 만든다**

`docs/superpowers/captures/mcp-authorization-walkthrough.sh` 를 만든다. 세 practice 모두 환경변수만 바꿔 재사용한다.

```bash
#!/usr/bin/env bash
# 인증이 포함된 MCP 흐름을 curl 로 한 단계씩 밟으며 요청과 응답을 기록한다.
# 사용: AS=... MCP_BASE=... CLIENT_ID=... ./mcp-authorization-walkthrough.sh > 결과.txt
set -uo pipefail

AS=${AS:-http://localhost:9010}
MCP_BASE=${MCP_BASE:-http://localhost:8111}
MCP="$MCP_BASE/mcp"
CLIENT_ID=${CLIENT_ID:-official-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-official-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8110/login/oauth2/code/authserver}
USERNAME=${USERNAME:-user}
PASSWORD=${PASSWORD:-password}
PROTOCOL_VERSION=${PROTOCOL_VERSION:-2025-11-25}

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)
INITIALIZE='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"walkthrough","version":"1.0.0"}}}'

step() { printf '\n\n===== %s =====\n' "$1"; }
payload() {
  local segment="$1" pad
  pad=$(( (4 - ${#segment} % 4) % 4 ))
  [ "$pad" -gt 0 ] && segment="$segment$(printf '=%.0s' $(seq 1 $pad))"
  printf '%s' "$segment" | tr '_-' '/+' | base64 -d 2>/dev/null
  echo
}

step "1. 토큰 없이 MCP 를 호출한다 (401 + WWW-Authenticate)"
curl -si -X POST "$MCP" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE"

step "2. 보호 리소스 메타데이터 (RFC 9728, 경로형)"
curl -si "$MCP_BASE/.well-known/oauth-protected-resource/mcp"

step "3. 인가 서버 메타데이터 (RFC 8414)"
curl -si "$AS/.well-known/oauth-authorization-server"

step "4. 사용자 로그인 (인가 서버 폼)"
CSRF=$(curl -s -c "$JAR" -b "$JAR" "$AS/login" | sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' | head -1)
curl -si -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "_csrf=$CSRF" | head -8

step "5. 인가 요청 (PKCE S256 + resource)"
AUTHORIZE=$(curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode "resource=$MCP")
echo "$AUTHORIZE" | head -12
LOCATION=$(printf '%s' "$AUTHORIZE" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
CODE=$(printf '%s' "$LOCATION" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')

step "6. 토큰 요청 (code_verifier + resource)"
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=authorization_code' --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "code_verifier=$VERIFIER" \
  --data-urlencode "resource=$MCP")
echo "$TOKEN"
ACCESS=$(printf '%s' "$TOKEN" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
REFRESH=$(printf '%s' "$TOKEN" | sed -n 's/.*"refresh_token":"\([^"]*\)".*/\1/p')
ID_TOKEN=$(printf '%s' "$TOKEN" | sed -n 's/.*"id_token":"\([^"]*\)".*/\1/p')

step "6-1. access token 페이로드 (aud 가 MCP 서버다)"
payload "$(printf '%s' "$ACCESS" | cut -d. -f2)"

step "6-2. id token 페이로드 (aud 는 클라이언트다)"
payload "$(printf '%s' "$ID_TOKEN" | cut -d. -f2)"

step "7. initialize (Bearer)"
INIT_RESPONSE=$(curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE")
echo "$INIT_RESPONSE"
SESSION=$(printf '%s' "$INIT_RESPONSE" | tr -d '\r' | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p')

MCP_HEADERS=(-H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json'
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION"
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION")

step "8. notifications/initialized (202)"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

step "9. tools/list"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

step "10. tools/call"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getStock","arguments":{"productId":"p1"}}}'

step "11. refresh_token 으로 갱신 (resource 를 다시 싣는다)"
REFRESHED=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -X POST "$AS/oauth2/token" \
  --data-urlencode 'grant_type=refresh_token' --data-urlencode "refresh_token=$REFRESH" \
  --data-urlencode "resource=$MCP")
echo "$REFRESHED"
payload "$(printf '%s' "$REFRESHED" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' | cut -d. -f2)"

step "12. 오류: aud 가 다른 토큰(id_token)을 Bearer 로 쓴다"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ID_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$INITIALIZE"

step "13. 오류: 허용되지 않은 Origin"
curl -si -X POST "$MCP" "${MCP_HEADERS[@]}" -H 'Origin: http://evil.example' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}'

step "14. 오류: Mcp-Session-Id 없이 tools/list"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "MCP-Protocol-Version: $PROTOCOL_VERSION" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list"}'

step "15. 오류: 지원하지 않는 MCP-Protocol-Version"
curl -si -X POST "$MCP" -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SESSION" \
  -H 'MCP-Protocol-Version: 1999-01-01' -d '{"jsonrpc":"2.0","id":6,"method":"tools/list"}'

step "16. 오류: 모르는 resource 로 인가 요청 (invalid_target)"
curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' --data-urlencode 'resource=http://localhost:9999/mcp' | head -8

step "17. 오류: PKCE 없는 인가 요청 (invalid_request)"
curl -si -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
  --data-urlencode 'response_type=code' --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode 'scope=openid profile' \
  --data-urlencode 'state=walkthrough-state' --data-urlencode "resource=$MCP" | head -8

step "18. 세션 종료 (DELETE)"
curl -si -X DELETE "$MCP" -H "Authorization: Bearer $ACCESS" -H "Mcp-Session-Id: $SESSION" \
  -H "MCP-Protocol-Version: $PROTOCOL_VERSION"

rm -f "$JAR"
```

실행 권한을 준다.

```bash
chmod +x docs/superpowers/captures/mcp-authorization-walkthrough.sh
```

- [ ] **Step 2: 세 앱을 띄운다**

```bash
cd practice/mcp-security-authn-official && ./run.sh
```

기대: auth-server → shop-mcp-server → shop-agent 순으로 `[준비됨]` 이 찍힌다. 뜨지 않으면 `logs/<앱>.log` 마지막 20줄을 읽고 원인을 고친 뒤 다시 시작한다.

- [ ] **Step 3: 캡처를 만든다**

```bash
docs/superpowers/captures/mcp-authorization-walkthrough.sh > docs/superpowers/captures/2026-09-12-official.txt 2>&1; tail -40 docs/superpowers/captures/2026-09-12-official.txt
```

확인할 것(하나라도 어긋나면 구현을 고친다):
- 1번: `401` 과 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"`
- 2번: `resource` 가 `http://localhost:8111/mcp`, `authorization_servers` 존재, `tls_client_certificate_bound_access_tokens` 가 false
- 3번: `code_challenge_methods_supported` 에 S256, `authorization_response_iss_parameter_supported` 가 true
- 5번: Location 에 `code`, `state`, `iss`
- 6-1번: `aud` 가 `http://localhost:8111/mcp`
- 6-2번: id_token 의 `aud` 가 `official-shop-agent`
- 7번: `200` 과 `Mcp-Session-Id`, 결과의 `protocolVersion` 이 `2025-11-25`
- 12번: `401` 과 `error="invalid_token"`
- 13번: `403`
- 16번: `error=invalid_target`, 17번: `error=invalid_request`

- [ ] **Step 4: 에이전트를 브라우저로 확인한다**

브라우저로 `http://localhost:8110/` 를 열어 `user` / `password` 로 로그인하고 "p1 재고 알려줘" 를 물어 답이 나오는지 본다. 그리고 아래로 흐름이 실제로 일어났는지 확인한다.

```bash
grep -c 'searchProducts 호출\|getStock 호출' practice/mcp-security-authn-official/logs/shop-mcp-server.log; grep -i 'discovery\|McpAuthorizationDiscovery' practice/mcp-security-authn-official/logs/shop-agent.log | head -5
```

기대: MCP 서버 로그에 툴 호출이 찍힌다(0 이면 실패다).

- [ ] **Step 5: 앱을 내린다**

```bash
cd practice/mcp-security-authn-official && ./stop.sh
```

- [ ] **Step 6: 커밋한다**

```bash
git add docs/superpowers/captures && git commit -m "docs: official 인가 흐름 관측 기록과 캡처 스크립트"
```

---

## Task 6: chat-memory 인가 서버·MCP 서버 이식

chat-memory 는 official 의 복사본이다. 같은 변경을 포트·이름만 바꿔 넣는다.

**Files:**
- Create(복사): `practice/mcp-security-authn-chat-memory/auth-server/src/main/java/dev/starryeye/memoryauthn/authserver/{McpResourceProperties,ResourceIndicatorValidator,ResourceAudienceTokenCustomizer,IssuerIdentifyingAuthorizationResponseHandler,AuthorizationServerConfig}.java`
- Create(복사): `.../auth-server/src/test/java/dev/starryeye/memoryauthn/authserver/AuthorizationServerStandardTest.java`
- Create(복사): `.../shop-mcp-server/src/main/java/dev/starryeye/memoryauthn/mcpserver/McpTransportConfig.java`
- Modify(복사로 교체): `.../shop-mcp-server/src/main/java/dev/starryeye/memoryauthn/mcpserver/SecurityConfig.java`
- Create(복사): `.../shop-mcp-server/src/test/java/dev/starryeye/memoryauthn/mcpserver/McpAuthorizationStandardTest.java`
- Modify: `.../auth-server/src/main/resources/application.yml`, `.../auth-server/build.gradle`, `.../shop-mcp-server/src/main/resources/application.yml`
- Modify: `.../auth-server/src/main/java/dev/starryeye/memoryauthn/authserver/UserConfig.java` (javadoc)

**Interfaces:**
- Consumes: Task 1·2 의 파일들(원본)
- Produces: chat-memory 의 AS(9020)가 `aud = http://localhost:8131/mcp` 토큰을 발급하고, MCP 서버(8131)가 그것만 받는다

- [ ] **Step 1: 인가 서버 파일을 포트·이름을 바꿔 복사한다**

```bash
O=practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver
M=practice/mcp-security-authn-chat-memory/auth-server/src/main/java/dev/starryeye/memoryauthn/authserver
for f in McpResourceProperties ResourceIndicatorValidator ResourceAudienceTokenCustomizer IssuerIdentifyingAuthorizationResponseHandler AuthorizationServerConfig; do
  sed -e 's/dev\.starryeye\.officialauthserver/dev.starryeye.memoryauthn.authserver/' -e 's/8111/8131/g' -e 's/9010/9020/g' "$O/$f.java" > "$M/$f.java"
done
```

- [ ] **Step 2: 인가 서버 테스트를 복사한다**

```bash
OT=practice/mcp-security-authn-official/auth-server/src/test/java/dev/starryeye/officialauthserver
MT=practice/mcp-security-authn-chat-memory/auth-server/src/test/java/dev/starryeye/memoryauthn/authserver
sed -e 's/dev\.starryeye\.officialauthserver/dev.starryeye.memoryauthn.authserver/' \
    -e 's/official-shop-agent-secret/memory-agent-secret/g' -e 's/official-shop-agent/memory-agent/g' \
    -e 's/9010/9020/g' -e 's/8111/8131/g' -e 's/8110/8130/g' \
    -e 's/USERNAME = "user"/USERNAME = "alice"/' -e 's/PASSWORD = "password"/PASSWORD = "alice"/' \
    "$OT/AuthorizationServerStandardTest.java" > "$MT/AuthorizationServerStandardTest.java"
grep -n 'USERNAME\|CLIENT_ID\|ISSUER =\|RESOURCE =' "$MT/AuthorizationServerStandardTest.java" | head
```

기대: `alice`, `memory-agent`, `http://localhost:9020`, `http://localhost:8131/mcp` 로 바뀌어 있다.

- [ ] **Step 3: 인가 서버 설정과 빌드 파일을 고친다**

`auth-server/build.gradle` 의 `testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 아래에 추가한다.

```gradle
	testImplementation 'org.springframework.security:spring-security-test'
```

`auth-server/src/main/resources/application.yml` 의 `require-authorization-consent: false` 위에 추가한다.

```yaml
            # MCP 2025-11-25 는 PKCE(S256)를 MUST 로 요구한다.
            # 이 값이 true 면 code_challenge 없는 인가 요청이 거부된다.
            require-proof-key: true
```

같은 파일의 `logging:` 위에 추가한다.

```yaml
mcp:
  authorization:
    # RFC 8707. 이 인가 서버가 토큰을 발급해 줄 수 있는 보호 리소스 목록이다.
    resources:
      - http://localhost:8131/mcp

```

- [ ] **Step 4: `UserConfig` javadoc 을 현재 사실에 맞춘다**

클래스 javadoc 에 필터체인 위치를 한 줄 적는다(문구는 official Task 1 Step 9 와 같은 뜻이면 된다).

```java
/**
 * 학습용 사용자 두 명. alice / alice, bob / bob 으로 로그인한다.
 *
 * <p>필터체인은 {@link AuthorizationServerConfig} 에 있다. 인가 서버용 체인과
 * 폼 로그인용 체인 두 개를 직접 정의하므로, Boot 의 기본 인가 서버 필터체인은 물러난다.
 */
```

(기존 javadoc 의 사용자 설명 문장이 다르면 그 문장은 살리고 필터체인 문단만 추가한다.)

- [ ] **Step 5: 인가 서버 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-chat-memory/auth-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 새 테스트 8개와 기존 테스트 전부 통과. 기존 `AuthServerApplicationTests` 의 OIDC 테스트 이름·주석이 official Task 1 Step 10 과 같은 이유로 어긋나면 같이 고친다.

- [ ] **Step 6: MCP 서버 파일을 복사한다**

```bash
O=practice/mcp-security-authn-official/shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver
M=practice/mcp-security-authn-chat-memory/shop-mcp-server/src/main/java/dev/starryeye/memoryauthn/mcpserver
for f in SecurityConfig McpTransportConfig; do
  sed -e 's/dev\.starryeye\.officialmcpserver/dev.starryeye.memoryauthn.mcpserver/' "$O/$f.java" > "$M/$f.java"
done
OT=practice/mcp-security-authn-official/shop-mcp-server/src/test/java/dev/starryeye/officialmcpserver
MT=practice/mcp-security-authn-chat-memory/shop-mcp-server/src/test/java/dev/starryeye/memoryauthn/mcpserver
sed -e 's/dev\.starryeye\.officialmcpserver/dev.starryeye.memoryauthn.mcpserver/' \
    -e 's/9010/9020/g' -e 's/8111/8131/g' -e 's/official-shop-agent/memory-agent/g' \
    "$OT/McpAuthorizationStandardTest.java" > "$MT/McpAuthorizationStandardTest.java"
```

- [ ] **Step 7: MCP 서버 설정에 audience 를 넣는다**

`shop-mcp-server/src/main/resources/application.yml` 의 `issuer-uri: http://localhost:9020` 아래에 추가한다.

```yaml
          # RFC 8707 로 발급된 aud 를 검증한다. 이 값이 없으면 같은 인가 서버가 발급한
          # 다른 리소스용 토큰도 그대로 통과한다(confused deputy).
          audiences: http://localhost:8131/mcp
```

- [ ] **Step 8: MCP 서버 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-chat-memory/shop-mcp-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 새 테스트 8개와 기존 테스트 전부 통과.

- [ ] **Step 9: 커밋한다**

```bash
git add practice/mcp-security-authn-chat-memory/auth-server practice/mcp-security-authn-chat-memory/shop-mcp-server && git commit -m "feat: chat-memory 인가 서버·MCP 서버 표준 준수 이식"
```

---

## Task 7: chat-memory 에이전트 이식과 종단 검증

**Files:**
- Create(복사): `.../shop-agent/src/main/java/dev/starryeye/memoryauthn/agent/{McpAuthorizationProperties,DiscoveredAuthorization,McpDiscoveryException,McpAuthorizationDiscovery,DiscoveredClientRegistrationRepository,ResourceIndicators,AuthorizationResponseIssuerFilter,LoginFailureHandler}.java`
- Modify(복사로 교체): `.../shop-agent/src/main/java/dev/starryeye/memoryauthn/agent/McpSecurityConfig.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/memoryauthn/agent/SecurityConfig.java` (CSRF 설정은 유지)
- Modify: `.../shop-agent/src/main/resources/application.yml`
- Create(복사): 테스트 4개 (`McpAuthorizationDiscoveryTest`, `DiscoveredClientRegistrationRepositoryTest`, `DiscoveryFixtures`, `AuthorizationResponseIssuerFilterTest`, `TokenRefreshTest`)
- Modify: `.../shop-agent/src/test/java/dev/starryeye/memoryauthn/agent/ShopAgentApplicationTests.java`

**Interfaces:**
- Consumes: Task 3·4 의 에이전트 파일들, Task 6 의 서버들
- Produces: chat-memory 에이전트가 발견·PKCE·resource·iss 를 모두 쓰면서 기존 대화 격리 기능을 유지한다

- [ ] **Step 1: 에이전트 파일을 복사한다**

```bash
O=practice/mcp-security-authn-official/shop-agent/src/main/java/dev/starryeye/officialagent
M=practice/mcp-security-authn-chat-memory/shop-agent/src/main/java/dev/starryeye/memoryauthn/agent
for f in McpAuthorizationProperties DiscoveredAuthorization McpDiscoveryException McpAuthorizationDiscovery DiscoveredClientRegistrationRepository ResourceIndicators AuthorizationResponseIssuerFilter LoginFailureHandler McpSecurityConfig; do
  sed -e 's/dev\.starryeye\.officialagent/dev.starryeye.memoryauthn.agent/' -e 's/official-shop-agent-secret/memory-agent-secret/g' -e 's/official-shop-agent/memory-agent/g' "$O/$f.java" > "$M/$f.java"
done
OT=practice/mcp-security-authn-official/shop-agent/src/test/java/dev/starryeye/officialagent
MT=practice/mcp-security-authn-chat-memory/shop-agent/src/test/java/dev/starryeye/memoryauthn/agent
for f in DiscoveryFixtures McpAuthorizationDiscoveryTest DiscoveredClientRegistrationRepositoryTest AuthorizationResponseIssuerFilterTest TokenRefreshTest; do
  sed -e 's/dev\.starryeye\.officialagent/dev.starryeye.memoryauthn.agent/' \
      -e 's/official-shop-agent-secret/memory-agent-secret/g' -e 's/official-shop-agent/memory-agent/g' \
      -e 's/9010/9020/g' -e 's/8111/8131/g' -e 's/8110/8130/g' "$OT/$f.java" > "$MT/$f.java"
done
```

- [ ] **Step 2: `SecurityConfig` 를 고친다 (CSRF 구성은 유지)**

chat-memory 의 `SecurityConfig` 는 CSRF 를 쿠키 기반으로 쓰고 예외를 두지 않는다. 그 부분(`csrf(...)`, `addFilterAfter(new CsrfCookieFilter(), ...)`, 중첩 클래스 두 개)은 **그대로 두고**, `securityFilterChain` 메서드만 아래로 바꾼다. import 는 official Task 4 Step 8 의 것을 더한다.

```java
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			DiscoveredClientRegistrationRepository registrations,
			OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient)
			throws Exception {
		// iss 검증 필터와 로그인 필터가 같은 저장소를 봐야 한다.
		var authorizationRequests = new HttpSessionOAuth2AuthorizationRequestRepository();
		var failureHandler = new LoginFailureHandler();

		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(login -> login
						.loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.authorizationEndpoint(authorization -> authorization
								.authorizationRequestRepository(authorizationRequests)
								.authorizationRequestResolver(authorizationRequestResolver(registrations)))
						.tokenEndpoint(token -> token.accessTokenResponseClient(authorizationCodeTokenResponseClient))
						.failureHandler(failureHandler))
				.addFilterBefore(new AuthorizationResponseIssuerFilter(authorizationRequests, registrations,
						failureHandler), OAuth2LoginAuthenticationFilter.class)
				.oauth2Client(Customizer.withDefaults())
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
				.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
				.build();
	}

	private static OAuth2AuthorizationRequestResolver authorizationRequestResolver(
			DiscoveredClientRegistrationRepository registrations) {
		var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
				.andThen(ResourceIndicators.authorizationRequest(() -> registrations.discovered().resource())));
		return resolver;
	}
```

- [ ] **Step 3: `application.yml` 을 고친다**

official Task 3 Step 11 과 같은 변경을 포트만 바꿔 적용한다. `provider:` 블록을 지우고, `logging:` 위에 추가한다.

```yaml
mcp:
  authorization:
    # 발견의 출발점. 이 URL 하나만 알면 인가 서버는 따라온다.
    resource-url: http://localhost:8131/mcp
    # 위 자격증명이 등록된 인가 서버. 발견 결과가 다르면 자격증명을 보내지 않는다.
    credentials-issuer: http://localhost:9020
```

`registration.memory-agent` 블록 위에는 "여기에는 자격증명만 둔다" 주석을 남긴다.

- [ ] **Step 4: 애플리케이션 테스트를 고친다**

`ShopAgentApplicationTests` 의 `@Autowired MockMvc mockMvc;` 위에 스텁을 넣는다.

```java
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	McpAuthorizationDiscovery discovery;

	@org.junit.jupiter.api.BeforeEach
	void 발견_결과를_고정한다() {
		org.mockito.BDDMockito.given(this.discovery.discover(DiscoveryFixtures.RESOURCE))
				.willReturn(DiscoveryFixtures.discovered());
	}
```

`OAuth2_클라이언트_등록이_정확히_하나다` 의 본문을 아래로 교체한다.

```java
		var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("memory-agent");
		// 엔드포인트는 설정이 아니라 발견 결과에서 온다.
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
```

그리고 아래 테스트를 추가한다.

```java
	@Test
	void 인가_요청에_PKCE_와_resource_가_실린다() throws Exception {
		String location = mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();

		var parameters = org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build()
				.getQueryParams();

		assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.getFirst("code_challenge")).isNotBlank();
		assertThat(org.springframework.web.util.UriUtils.decode(parameters.getFirst("resource"),
				java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(DiscoveryFixtures.RESOURCE);
	}
```

- [ ] **Step 5: 전체 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-chat-memory/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 기존 대화 격리 테스트(`ConversationControllerTest`, `ConversationIdTest` 등)를 포함해 전부 통과한다. 격리 테스트가 깨지면 그 원인을 먼저 고친다 — 이 practice 의 핵심 기능이다.

- [ ] **Step 6: 종단 검증**

```bash
cd practice/mcp-security-authn-chat-memory && ./run.sh
```

그리고 캡처를 만든다.

```bash
AS=http://localhost:9020 MCP_BASE=http://localhost:8131 CLIENT_ID=memory-agent CLIENT_SECRET=memory-agent-secret REDIRECT_URI=http://localhost:8130/login/oauth2/code/authserver USERNAME=alice PASSWORD=alice docs/superpowers/captures/mcp-authorization-walkthrough.sh > docs/superpowers/captures/2026-09-12-chat-memory.txt 2>&1; grep -c '===== ' docs/superpowers/captures/2026-09-12-chat-memory.txt
```

브라우저로 `http://localhost:8130/` 에서 alice 로 로그인해 질문하고, bob 으로 로그인했을 때 alice 의 대화가 보이지 않는지 확인한다. 끝나면 `./stop.sh`.

- [ ] **Step 7: 커밋한다**

```bash
git add practice/mcp-security-authn-chat-memory docs/superpowers/captures && git commit -m "feat: chat-memory 에이전트 표준 준수 이식과 종단 검증"
```

---

## Task 8: community 인가 서버·MCP 서버

community 는 `org.springaicommunity` 모듈의 자동설정을 쓰는 것이 이 practice 의 요점이다. 자동설정을 유지한 채 확장점으로 같은 규칙을 얹고, 막히는 곳(감사 검증)만 직접 정의한다.

**Files:**
- Create(복사): `practice/mcp-security-authn-community/auth-server/src/main/java/dev/starryeye/authserver/{McpResourceProperties,ResourceIndicatorValidator,ResourceAudienceTokenCustomizer,IssuerIdentifyingAuthorizationResponseHandler}.java`
- Create: `.../auth-server/src/main/java/dev/starryeye/authserver/McpAuthorizationStandardConfig.java`
- Create(복사): `.../auth-server/src/test/java/dev/starryeye/authserver/AuthorizationServerStandardTest.java`
- Modify: `.../auth-server/src/main/resources/application.yml`, `.../auth-server/build.gradle`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/SecurityConfig.java`
- Create(복사): `.../shop-mcp-server/src/test/java/dev/starryeye/shopmcpserver/McpAuthorizationStandardTest.java`
- Modify: `.../shop-mcp-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1·2 의 파일들(원본), community 0.1.14 의 `McpAuthorizationServerConfigurer` · `McpServerOAuth2Configurer`
- Produces: community AS(9000)가 `aud = http://localhost:8101/mcp` 토큰을 발급하고 MCP 서버(8101)가 그것만 받는다

- [ ] **Step 1: 인가 서버 요소를 복사한다 (설정 클래스는 제외)**

```bash
O=practice/mcp-security-authn-official/auth-server/src/main/java/dev/starryeye/officialauthserver
C=practice/mcp-security-authn-community/auth-server/src/main/java/dev/starryeye/authserver
for f in McpResourceProperties ResourceIndicatorValidator ResourceAudienceTokenCustomizer IssuerIdentifyingAuthorizationResponseHandler; do
  sed -e 's/dev\.starryeye\.officialauthserver/dev.starryeye.authserver/' -e 's/8111/8101/g' -e 's/9010/9000/g' "$O/$f.java" > "$C/$f.java"
done
```

- [ ] **Step 2: 자동설정 확장점에 같은 규칙을 얹는다**

`McpAuthorizationStandardConfig.java` 를 만든다.

```java
package dev.starryeye.authserver;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * community 모듈의 인가 서버 자동설정 위에 MCP 인가 명세를 얹는다.
 *
 * <p>필터체인을 직접 만들지 않는다. 직접 만들면 {@code @ConditionalOnDefaultWebSecurity}
 * 때문에 모듈의 인가 서버 설정이 통째로 물러난다. 대신 모듈이 열어 둔 확장점
 * ({@code Customizer<McpAuthorizationServerConfigurer>})으로 넣는다.
 *
 * <p>모듈도 {@code resource} 를 {@code aud} 로 넣는 커스터마이저를 갖고 있지만
 * {@code openid} 스코프가 있으면 건너뛴다. 이 practice 의 에이전트는 로그인(openid)으로
 * 토큰을 받으므로 그 경로가 비어 버린다. 아래 토큰 커스터마이저가 모듈 것 뒤에 실행되어
 * 스코프와 무관하게 {@code aud} 를 채운다.
 */
@Configuration
@EnableConfigurationProperties(McpResourceProperties.class)
public class McpAuthorizationStandardConfig {

	/** RFC 9207 §3 — 인가 응답에 iss 를 싣는다고 알리는 메타데이터 필드. */
	static final String ISS_PARAMETER_SUPPORTED = "authorization_response_iss_parameter_supported";

	@Bean
	public Customizer<McpAuthorizationServerConfigurer> mcpAuthorizationStandardCustomizer(
			McpResourceProperties resources) {
		IssuerIdentifyingAuthorizationResponseHandler responseHandler =
				new IssuerIdentifyingAuthorizationResponseHandler();

		return configurer -> configurer
				// RFC 8707: 기본 검증(redirect_uri·scope) 뒤에 resource 검증을 잇는다.
				.authorizationCodeRequestValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator()
						.andThen(new ResourceIndicatorValidator(resources)))
				.authorizationServer(authorizationServer -> authorizationServer
						// RFC 9207: 성공·오류 응답 모두에 iss 를 싣는다.
						.authorizationEndpoint(authorization -> authorization
								.authorizationResponseHandler(responseHandler)
								.errorResponseHandler(responseHandler))
						.authorizationServerMetadataEndpoint(metadata -> metadata
								.authorizationServerMetadataCustomizer(
										builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))
						.oidc(oidc -> oidc.providerConfigurationEndpoint(configuration -> configuration
								.providerConfigurationCustomizer(
										builder -> builder.claim(ISS_PARAMETER_SUPPORTED, true)))));
	}

	/** 모듈이 이 타입의 빈을 모아 기본 커스터마이저 뒤에 실행한다. */
	@Bean
	public OAuth2TokenCustomizer<JwtEncodingContext> resourceAudienceTokenCustomizer(McpResourceProperties resources) {
		return new ResourceAudienceTokenCustomizer(resources);
	}
}
```

- [ ] **Step 3: 인가 서버 설정을 고친다**

`auth-server/build.gradle` 에 테스트 의존성을 추가한다.

```gradle
	testImplementation 'org.springframework.security:spring-security-test'
```

`auth-server/src/main/resources/application.yml` 의 `require-authorization-consent: false` 위에 추가한다.

```yaml
            # MCP 2025-11-25 는 PKCE(S256)를 MUST 로 요구한다.
            require-proof-key: true
```

`logging:` 위에 추가한다.

```yaml
# 이 모듈은 동적 클라이언트 등록(DCR)을 기본으로 켠다. MCP 2026-07-28 에서
# DCR 은 deprecated 이고, 이 practice 는 사전 등록만 쓰므로 끈다.
spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false

mcp:
  authorization:
    # RFC 8707. 이 인가 서버가 토큰을 발급해 줄 수 있는 보호 리소스 목록이다.
    resources:
      - http://localhost:8101/mcp

```

(위 `spring.ai...` 한 줄은 기존 `spring:` 블록과 충돌하지 않도록 평면 키로 적는다.)

- [ ] **Step 4: 인가 서버 테스트를 복사하고 DCR 검증을 더한다**

```bash
OT=practice/mcp-security-authn-official/auth-server/src/test/java/dev/starryeye/officialauthserver
CT=practice/mcp-security-authn-community/auth-server/src/test/java/dev/starryeye/authserver
sed -e 's/dev\.starryeye\.officialauthserver/dev.starryeye.authserver/' \
    -e 's/official-shop-agent-secret/shop-agent-secret/g' -e 's/official-shop-agent/shop-agent/g' \
    -e 's/9010/9000/g' -e 's/8111/8101/g' -e 's/8110/8100/g' \
    "$OT/AuthorizationServerStandardTest.java" > "$CT/AuthorizationServerStandardTest.java"
```

복사한 파일에 다음 테스트를 추가한다.

```java
	/**
	 * MCP 2026-07-28 에서 DCR 은 deprecated 다. 이 practice 는 사전 등록만 쓰므로
	 * 등록 엔드포인트를 광고하지 않아야 한다.
	 */
	@Test
	void 동적_클라이언트_등록은_켜지_않는다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-authorization-server"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.registration_endpoint").doesNotExist());
	}
```

- [ ] **Step 5: 인가 서버 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-community/auth-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 새 테스트 9개와 기존 테스트 전부 통과. 실패하면 모듈이 우리 커스터마이저를 언제 적용하는지(자동설정의 `mcpCustomizers.orderedStream()`)를 확인하고 배선을 맞춘다.

- [ ] **Step 6: MCP 서버 보안 설정을 만든다**

모듈 자동설정은 audience 검증을 켤 수단을 주지 않는다. 같은 설정기(`McpServerOAuth2Configurer`)를 우리가 직접 적용한다.

`SecurityConfig.java`:

```java
package dev.starryeye.shopmcpserver;

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * community 모듈의 MCP 서버 설정기를 직접 적용한다.
 *
 * <p>자동설정({@code McpServerSecurityAutoConfiguration})도 같은 설정기를 쓰지만
 * audience 검증을 켜는 길이 없다. audience 검증이 없으면 같은 인가 서버가 발급한
 * 다른 리소스용 토큰이 그대로 통과한다 — MCP 인가 명세가 MUST 로 막는 것이다.
 * 그래서 여기서 필터체인을 직접 정의한다(자동설정은 물러난다).
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain mcpServerSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
			@Value("${server.port}") int port) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> {
					mcp.authorizationServer(issuer);
					// Boot 가 issuer-uri 로 만든 디코더를 그대로 쓴다(서명·iss 검증).
					mcp.jwtDecoder(jwtDecoder);
					// 요청 URL 로 계산한 리소스 식별자가 토큰의 aud 에 있는지 본다.
					mcp.validateAudienceClaim(true);
					mcp.protectedResourceMetadataCustomizer(metadata -> metadata
							.authorizationServer(issuer)
							.resourceName("shop-mcp-server")
							// 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다(모듈 기본값은 true).
							.tlsClientCertificateBoundAccessTokens(false));
					// 브라우저에서 직접 부를 일이 없다. 자기 자신 외의 Origin 은 막는다.
					mcp.allowedOrigins(List.of("http://localhost:" + port));
					mcp.allowedHosts(List.of("localhost:" + port, "127.0.0.1:" + port));
				})
				.build();
	}
}
```

- [ ] **Step 7: MCP 서버 테스트를 만든다**

official 테스트를 복사한 뒤 community 의 차이에 맞춘다.

```bash
OT=practice/mcp-security-authn-official/shop-mcp-server/src/test/java/dev/starryeye/officialmcpserver
CT=practice/mcp-security-authn-community/shop-mcp-server/src/test/java/dev/starryeye/shopmcpserver
sed -e 's/dev\.starryeye\.officialmcpserver/dev.starryeye.shopmcpserver/' \
    -e 's/9010/9000/g' -e 's/8111/8101/g' -e 's/official-shop-agent/shop-agent/g' \
    "$OT/McpAuthorizationStandardTest.java" > "$CT/McpAuthorizationStandardTest.java"
```

복사본에서 두 가지를 고친다.

1. PRM 검증에 모듈이 넣는 `resource_name` 기대를 추가한다.

```java
				.andExpect(jsonPath("$.resource_name").value("shop-mcp-server"))
```

2. Origin 거부 응답은 모듈 필터가 JSON-RPC 본문으로 돌려준다. 해당 테스트를 아래로 바꾼다.

```java
	@Test
	void 허용되지_않은_Origin_은_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), "http://evil.example", HOST))
				.andExpect(status().isForbidden())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid Origin header")));
	}
```

- [ ] **Step 8: MCP 서버 설정의 주석을 현재 사실로 고친다**

`shop-mcp-server/src/main/resources/application.yml` 의 `issuer-uri` 주석은 "이 줄이 없으면 자동설정이 뜨지 않는다"는 내용이다. 이제 필터체인을 직접 정의하므로 아래로 교체한다.

```yaml
        jwt:
          # 토큰 서명 검증용 JWK 와 iss 검증의 기준이고,
          # 보호 리소스 메타데이터의 authorization_servers 값이기도 하다.
          issuer-uri: http://localhost:9000
```

- [ ] **Step 9: MCP 서버 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-community/shop-mcp-server && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

기대: 새 테스트 8개와 기존 테스트 전부 통과. 기존 `ShopMcpServerApplicationTests` 의 401 테스트가 CSRF 때문에 달라지면 그 테스트의 주석도 현재 구성에 맞게 정리한다.

- [ ] **Step 10: 커밋한다**

```bash
git add practice/mcp-security-authn-community/auth-server practice/mcp-security-authn-community/shop-mcp-server && git commit -m "feat: community 인가 서버·MCP 서버 표준 준수"
```

---

## Task 9: community 에이전트와 종단 검증

**Files:**
- Create(복사): `.../shop-agent/src/main/java/dev/starryeye/shopagent/{McpAuthorizationProperties,DiscoveredAuthorization,McpDiscoveryException,DiscoveredClientRegistrationRepository,ResourceIndicators,AuthorizationResponseIssuerFilter,LoginFailureHandler}.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/shopagent/McpAuthorizationDiscovery.java` (모듈의 발견 서비스를 쓴다)
- Create: `.../shop-agent/src/main/java/dev/starryeye/shopagent/McpSecurityConfig.java`
- Modify: `.../shop-agent/src/main/java/dev/starryeye/shopagent/SecurityConfig.java`
- Modify: `.../shop-agent/src/main/resources/application.yml`
- Create/Modify: 테스트
- Modify: `.../shop-agent/src/test/java/dev/starryeye/shopagent/ShopAgentApplicationTests.java`

**Interfaces:**
- Consumes: Task 3·4 의 에이전트 파일, Task 8 의 서버들, community 의 `McpMetadataDiscoveryService`
- Produces: community 에이전트가 발견·PKCE·resource·iss 를 모두 쓴다

- [ ] **Step 1: 공통 파일을 복사한다**

```bash
O=practice/mcp-security-authn-official/shop-agent/src/main/java/dev/starryeye/officialagent
C=practice/mcp-security-authn-community/shop-agent/src/main/java/dev/starryeye/shopagent
for f in McpAuthorizationProperties DiscoveredAuthorization McpDiscoveryException DiscoveredClientRegistrationRepository ResourceIndicators AuthorizationResponseIssuerFilter LoginFailureHandler; do
  sed -e 's/dev\.starryeye\.officialagent/dev.starryeye.shopagent/' -e 's/official-shop-agent-secret/shop-agent-secret/g' -e 's/official-shop-agent/shop-agent/g' "$O/$f.java" > "$C/$f.java"
done
OT=practice/mcp-security-authn-official/shop-agent/src/test/java/dev/starryeye/officialagent
CT=practice/mcp-security-authn-community/shop-agent/src/test/java/dev/starryeye/shopagent
for f in DiscoveryFixtures DiscoveredClientRegistrationRepositoryTest AuthorizationResponseIssuerFilterTest TokenRefreshTest; do
  sed -e 's/dev\.starryeye\.officialagent/dev.starryeye.shopagent/' \
      -e 's/official-shop-agent-secret/shop-agent-secret/g' -e 's/official-shop-agent/shop-agent/g' \
      -e 's/9010/9000/g' -e 's/8111/8101/g' -e 's/8110/8100/g' "$OT/$f.java" > "$CT/$f.java"
done
```

`TokenRefreshTest` 는 community 의 매니저 타입에 맞춰 Step 5 에서 손본다.

- [ ] **Step 2: 모듈의 발견 서비스를 쓰는 `McpAuthorizationDiscovery` 를 만든다**

```java
package dev.starryeye.shopagent;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.ProtectedResourceMetadata;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 인가 서버를 발견한다(MCP 2025-11-25 인가 §2.3).
 *
 * <p>보호 리소스 메타데이터까지는 모듈의 {@link McpMetadataDiscoveryService} 가 해 준다 —
 * 401 챌린지를 읽고, {@code resource_metadata} 를 따라가고, 없으면 well-known 경로를
 * 차례로 시도하고, 메타데이터의 {@code resource} 가 우리가 부른 URL 과 같은지 확인한다.
 *
 * <p>그다음 단계(인가 서버 메타데이터 발견과 검증)는 모듈이 다루지 않으므로 여기서 한다.
 */
public class McpAuthorizationDiscovery {

	private static final String AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server";

	private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
			new ParameterizedTypeReference<>() {
			};

	private final McpMetadataDiscoveryService protectedResourceDiscovery;

	private final RestClient restClient;

	public McpAuthorizationDiscovery(McpMetadataDiscoveryService protectedResourceDiscovery, RestClient restClient) {
		this.protectedResourceDiscovery = protectedResourceDiscovery;
		this.restClient = restClient;
	}

	public DiscoveredAuthorization discover(String resourceUrl) {
		ProtectedResourceMetadata protectedResource;
		try {
			protectedResource = this.protectedResourceDiscovery.getMcpMetadata(resourceUrl).protectedResourceMetadata();
		}
		catch (IllegalStateException ex) {
			throw new McpDiscoveryException("보호 리소스 메타데이터를 얻지 못했다: " + ex.getMessage());
		}

		List<String> servers = protectedResource.authorizationServers();
		if (servers == null || servers.isEmpty()) {
			throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
		}
		String issuer = servers.get(0);

		return new DiscoveredAuthorization(protectedResource.resource(), issuer, authorizationServerMetadata(issuer));
	}

	private Map<String, Object> authorizationServerMetadata(String issuer) {
		for (String url : metadataUrls(issuer)) {
			Map<String, Object> metadata = json(url);
			if (metadata == null) {
				continue;
			}
			if (!issuer.equals(metadata.get("issuer"))) {
				throw new McpDiscoveryException("메타데이터의 issuer(%s) 가 요청한 인가 서버(%s) 와 다르다"
						.formatted(metadata.get("issuer"), issuer));
			}
			if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
					|| !methods.contains("S256")) {
				throw new McpDiscoveryException("인가 서버가 PKCE S256 을 광고하지 않는다: " + issuer);
			}
			return metadata;
		}
		throw new McpDiscoveryException("인가 서버 메타데이터를 찾지 못했다: " + issuer);
	}

	/** RFC 8414 §3.1 과 OIDC 디스커버리의 경로 규칙. MCP 는 RFC 8414 를 먼저 시도하라고 한다. */
	private static List<String> metadataUrls(String issuer) {
		URI uri = URI.create(issuer);
		String origin = uri.getScheme() + "://" + uri.getRawAuthority();
		String path = uri.getRawPath();

		if (path == null || path.isEmpty() || "/".equals(path)) {
			return List.of(origin + AUTHORIZATION_SERVER_METADATA, origin + OPENID_CONFIGURATION);
		}
		return List.of(origin + AUTHORIZATION_SERVER_METADATA + path, origin + OPENID_CONFIGURATION + path,
				origin + path + OPENID_CONFIGURATION);
	}

	private Map<String, Object> json(String url) {
		return this.restClient.get()
				.uri(url)
				.accept(MediaType.APPLICATION_JSON)
				.exchange((request, response) -> response.getStatusCode().is2xxSuccessful()
						? response.bodyTo(JSON_OBJECT) : null);
	}
}
```

- [ ] **Step 3: 발견 테스트를 만든다**

`McpAuthorizationDiscoveryTest.java` (community 판):

```java
package dev.starryeye.shopagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class McpAuthorizationDiscoveryTest {

	static final String RESOURCE = "http://localhost:8101/mcp";

	static final String ISSUER = "http://localhost:9000";

	static final String PROTECTED_RESOURCE_METADATA = """
			{"resource":"http://localhost:8101/mcp","authorization_servers":["http://localhost:9000"],\
			"bearer_methods_supported":["header"]}""";

	static final String AUTHORIZATION_SERVER_METADATA = """
			{"issuer":"http://localhost:9000","authorization_endpoint":"http://localhost:9000/oauth2/authorize",\
			"token_endpoint":"http://localhost:9000/oauth2/token","jwks_uri":"http://localhost:9000/oauth2/jwks",\
			"code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}""";

	MockRestServiceServer server;

	McpAuthorizationDiscovery discovery;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		// 학습 환경은 localhost HTTP 다. 기본값은 HTTPS 만 허용한다.
		this.discovery = new McpAuthorizationDiscovery(
				new McpMetadataDiscoveryService(restClient, new DefaultUrlValidator(true)), restClient);
	}

	void 챌린지() {
		this.server.expect(requestTo(RESOURCE)).andExpect(method(HttpMethod.POST))
				.andRespond(withUnauthorizedRequest().header("WWW-Authenticate",
						"Bearer resource_metadata=\"http://localhost:8101/.well-known/oauth-protected-resource/mcp\""));
	}

	void 응답(String url, String body) {
		this.server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	@Test
	void 챌린지에서_인가_서버까지_찾아낸다() {
		챌린지();
		응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9000/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		DiscoveredAuthorization discovered = this.discovery.discover(RESOURCE);

		assertThat(discovered.resource()).isEqualTo(RESOURCE);
		assertThat(discovered.issuer()).isEqualTo(ISSUER);
		assertThat(discovered.tokenEndpoint()).isEqualTo(ISSUER + "/oauth2/token");
		assertThat(discovered.issParameterSupported()).isTrue();
		this.server.verify();
	}

	@Test
	void 메타데이터의_issuer_가_다르면_실패한다() {
		챌린지();
		응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9000/.well-known/oauth-authorization-server", """
				{"issuer":"http://evil.example","authorization_endpoint":"http://evil.example/oauth2/authorize",\
				"token_endpoint":"http://evil.example/oauth2/token","code_challenge_methods_supported":["S256"]}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE))
				.withMessageContaining("issuer");
	}

	@Test
	void PKCE_S256_을_광고하지_않으면_진행하지_않는다() {
		챌린지();
		응답("http://localhost:8101/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9000/.well-known/oauth-authorization-server", """
				{"issuer":"http://localhost:9000","authorization_endpoint":"http://localhost:9000/oauth2/authorize",\
				"token_endpoint":"http://localhost:9000/oauth2/token"}""");

		assertThatExceptionOfType(McpDiscoveryException.class)
				.isThrownBy(() -> this.discovery.discover(RESOURCE))
				.withMessageContaining("S256");
	}
}
```

모듈이 보내는 요청이 기대와 다르면 `MockRestServiceServer` 가 실제 요청을 알려준다. 그 실제 동작에 맞춰 기대를 고친다(모듈 동작이 기준이다).

- [ ] **Step 4: `McpSecurityConfig` 를 만든다**

```java
package dev.starryeye.shopagent;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.client.RestClient;

/**
 * 발견과 토큰 요청 배선.
 *
 * <p>토큰을 MCP 요청에 붙이는 일은 모듈 자동설정
 * ({@code HttpClientStreamableHttpTransportAutoConfiguration})이 계속 맡는다.
 * 그 자동설정은 등록이 정확히 하나일 때만 동작하므로 {@code registration} 설정은 남겨 둔다.
 * 다만 인가 서버의 주소는 설정이 아니라 발견에서 온다.
 */
@Configuration
@EnableConfigurationProperties(McpAuthorizationProperties.class)
public class McpSecurityConfig {

	/** application.yml 의 registration 키와 같아야 한다. */
	static final String REGISTRATION_ID = "authserver";

	@Bean
	public McpAuthorizationDiscovery mcpAuthorizationDiscovery() {
		RestClient restClient = RestClient.create();
		// 학습 환경은 localhost HTTP 다. 운영에서는 기본값(HTTPS 만 허용)을 쓴다.
		return new McpAuthorizationDiscovery(
				new McpMetadataDiscoveryService(restClient, new DefaultUrlValidator(true)), restClient);
	}

	@Bean
	public DiscoveredClientRegistrationRepository clientRegistrationRepository(McpAuthorizationDiscovery discovery,
			McpAuthorizationProperties properties, OAuth2ClientProperties clientProperties) {
		return new DiscoveredClientRegistrationRepository(discovery, properties, clientProperties);
	}

	@Bean
	public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
			DiscoveredClientRegistrationRepository registrations) {
		var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
		tokenResponseClient.addParametersConverter(
				ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
		return tokenResponseClient;
	}

	@Bean
	public RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient(
			DiscoveredClientRegistrationRepository registrations) {
		var tokenResponseClient = new RestClientRefreshTokenTokenResponseClient();
		tokenResponseClient.addParametersConverter(
				ResourceIndicators.tokenRequest(() -> registrations.discovered().resource()));
		return tokenResponseClient;
	}

	/**
	 * 모듈의 요청 커스터마이저는 서블릿 요청을 함께 넘기므로 이 매니저를 쓴다.
	 * 기본 구성 대신 갱신 클라이언트를 지정해 resource 가 실리게 한다.
	 */
	@Bean
	public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientRepository authorizedClients,
			RestClientRefreshTokenTokenResponseClient refreshTokenTokenResponseClient) {
		return authorizedClientManager(registrations, authorizedClients, refreshTokenTokenResponseClient);
	}

	static DefaultOAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientRepository authorizedClients,
			OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
		var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
		manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
				.authorizationCode()
				.refreshToken(refreshToken -> refreshToken.accessTokenResponseClient(refreshTokenTokenResponseClient))
				.build());
		return manager;
	}
}
```

- [ ] **Step 5: `TokenRefreshTest` 를 community 매니저에 맞춘다**

복사본에서 매니저 생성과 호출 부분만 바꾼다. `DefaultOAuth2AuthorizedClientManager` 는 서블릿 요청·응답을 요구한다.

```java
		var authorizedClients = new org.springframework.security.oauth2.client.web
				.AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClientService);
		var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients, refreshTokenClient);
		OAuth2AuthorizedClient authorized = manager.authorize(OAuth2AuthorizeRequest
				.withClientRegistrationId("authserver").principal(principal)
				.attribute(jakarta.servlet.http.HttpServletRequest.class.getName(),
						new org.springframework.mock.web.MockHttpServletRequest())
				.attribute(jakarta.servlet.http.HttpServletResponse.class.getName(),
						new org.springframework.mock.web.MockHttpServletResponse())
				.build());
```

(`InMemoryOAuth2AuthorizedClientService` 변수 이름을 `authorizedClientService` 로 맞춘다.)

- [ ] **Step 6: `SecurityConfig` 를 교체한다**

```java
package dev.starryeye.shopagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.authentication.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 에이전트는 모든 요청에 로그인을 요구한다. 로그인해서 받은 토큰이 그대로 MCP 호출에 쓰인다.
 *
 * <p>명세가 요구하는 세 가지를 여기서 건다 — PKCE(S256), RFC 8707 {@code resource},
 * RFC 9207 {@code iss} 검증.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			DiscoveredClientRegistrationRepository registrations,
			OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient)
			throws Exception {
		// iss 검증 필터와 로그인 필터가 같은 저장소를 봐야 한다.
		var authorizationRequests = new HttpSessionOAuth2AuthorizationRequestRepository();
		var failureHandler = new LoginFailureHandler();

		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(login -> login
						// 등록이 하나뿐이고, 그 등록은 발견해야 알 수 있다.
						.loginPage(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.authorizationEndpoint(authorization -> authorization
								.authorizationRequestRepository(authorizationRequests)
								.authorizationRequestResolver(authorizationRequestResolver(registrations)))
						.tokenEndpoint(token -> token.accessTokenResponseClient(authorizationCodeTokenResponseClient))
						.failureHandler(failureHandler))
				.addFilterBefore(new AuthorizationResponseIssuerFilter(authorizationRequests, registrations,
						failureHandler), OAuth2LoginAuthenticationFilter.class)
				// MCP 호출 시 토큰을 얻으려면 oauth2Client 가 필요하다.
				.oauth2Client(Customizer.withDefaults())
				// 학습용 단순화: index.html 의 fetch 가 CSRF 토큰을 싣지 않으므로 이 엔드포인트만 예외로 둔다.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
				.build();
	}

	private static OAuth2AuthorizationRequestResolver authorizationRequestResolver(
			DiscoveredClientRegistrationRepository registrations) {
		var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
				.andThen(ResourceIndicators.authorizationRequest(() -> registrations.discovered().resource())));
		return resolver;
	}
}
```

기존 `SecurityConfig` 의 CSRF 예외 주석(툴 오남용 위험을 설명하는 문단)은 살려 둔다.

- [ ] **Step 7: `application.yml` 을 고친다**

`provider:` 블록을 지우고 `logging:` 위에 추가한다.

```yaml
mcp:
  authorization:
    # 발견의 출발점. 이 URL 하나만 알면 인가 서버는 따라온다.
    resource-url: http://localhost:8101/mcp
    # 위 자격증명이 등록된 인가 서버. 발견 결과가 다르면 자격증명을 보내지 않는다.
    credentials-issuer: http://localhost:9000
```

- [ ] **Step 8: 애플리케이션 테스트를 고친다**

`ShopAgentApplicationTests` 의 `@Autowired MockMvc mockMvc;` 위에 스텁을 넣는다.

```java
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	McpAuthorizationDiscovery discovery;

	@org.junit.jupiter.api.BeforeEach
	void 발견_결과를_고정한다() {
		org.mockito.BDDMockito.given(this.discovery.discover(DiscoveryFixtures.RESOURCE))
				.willReturn(DiscoveryFixtures.discovered());
	}
```

`OAuth2_클라이언트_등록이_정확히_하나다` 의 본문을 아래로 교체한다.

```java
		var registration = clientRegistrationRepository.findByRegistrationId(McpSecurityConfig.REGISTRATION_ID);

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("shop-agent");
		assertThat(registration.getAuthorizationGrantType().getValue()).isEqualTo("authorization_code");
		// 엔드포인트는 설정이 아니라 발견 결과에서 온다.
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(DiscoveryFixtures.ISSUER);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
				.isEqualTo(DiscoveryFixtures.ISSUER + "/oauth2/authorize");
		// 모듈의 transport 커스터마이저가 실제로 읽는 것은 이 맵이다.
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
```

그리고 아래 테스트를 추가한다.

```java
	@Test
	void 인가_요청에_PKCE_와_resource_가_실린다() throws Exception {
		String location = mockMvc.perform(get("/oauth2/authorization/" + McpSecurityConfig.REGISTRATION_ID))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();

		var parameters = org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build()
				.getQueryParams();

		assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.getFirst("code_challenge")).isNotBlank();
		assertThat(org.springframework.web.util.UriUtils.decode(parameters.getFirst("resource"),
				java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(DiscoveryFixtures.RESOURCE);
	}
```

기존 `SYNC_클라이언트_보안_자동설정이_로드된다`(`preRegisteredClientCustomizer` 빈 확인)는 그대로 둔다 — 토큰을 붙이는 경로가 여전히 모듈이라는 사실을 지키는 테스트다.

- [ ] **Step 9: 전체 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-community/shop-agent && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test
```

- [ ] **Step 10: 종단 검증과 캡처**

```bash
cd practice/mcp-security-authn-community && ./run.sh
```

```bash
AS=http://localhost:9000 MCP_BASE=http://localhost:8101 CLIENT_ID=shop-agent CLIENT_SECRET=shop-agent-secret REDIRECT_URI=http://localhost:8100/login/oauth2/code/authserver USERNAME=user PASSWORD=password docs/superpowers/captures/mcp-authorization-walkthrough.sh > docs/superpowers/captures/2026-09-12-community.txt 2>&1; grep -c '===== ' docs/superpowers/captures/2026-09-12-community.txt
```

브라우저로 `http://localhost:8100/` 에서 `user`/`password` 로 로그인해 질문이 동작하는지 확인하고 `./stop.sh`.

- [ ] **Step 11: 커밋한다**

```bash
git add practice/mcp-security-authn-community docs/superpowers/captures && git commit -m "feat: community 에이전트 표준 준수와 종단 검증"
```

---

## Task 10: `practice/MCP-AUTHORIZATION.md` 작성

**Files:**
- Create: `practice/MCP-AUTHORIZATION.md`

**Interfaces:**
- Consumes: Task 5·7·9 의 캡처 파일(`docs/superpowers/captures/2026-09-12-*.txt`), 아래 출처 목록
- Produces: 세 practice README 가 링크할 학습 문서

### 작성 원칙 (어기지 말 것)

1. **명세가 기준이다.** 엔드포인트 명세는 구현에서 역으로 옮겨 적지 않는다. 각 항목의 근거 조항을 열어 확인하고, 정의된 파라미터·헤더·응답 필드를 **REQUIRED / RECOMMENDED / OPTIONAL 표시와 함께 전부** 싣는다. 우리 구현이 쓰지 않는 OPTIONAL 도 표에 남기고 "이 practice 에서는 쓰지 않음"으로 적는다.
2. **관측은 대조용이다.** 캡처 파일의 실제 요청·응답을 명세 옆에 붙인다. 표시를 구분한다 — `명세` / `관측`.
3. **차이는 드러낸다.** 명세와 관측이 다르면 그 차이를 적는다. 고칠 수 있는 것이면 구현을 고치고(그 경우 해당 practice 를 수정하고 테스트를 다시 돌린다), 고치지 않으면 이유를 적는다.
4. **시행착오를 쓰지 않는다.** "처음에는 …였는데 고쳤다" 류의 문장을 넣지 않는다. 완성된 흐름만 순서대로 설명한다.
5. **한국어로 쓴다.** 명세 용어(파라미터·필드 이름)는 원문 그대로 둔다.

### 반드시 들어가야 할 것

- mermaid 시퀀스 다이어그램 (아래 8개)
- 엔드포인트 명세 표 (아래 10개 엔드포인트)
- 준수표 (MUST/SHOULD × 세 practice)
- 출처 (아래 목록 전부, 링크 포함)

### 문서 구조

```
# 인증이 포함된 MCP — 표준으로 배우기

1. 이 문서의 범위
   - 기준 리비전 두 개(2025-11-25 전송/인가, 2026-07-28 인가 추가분)와 그 이유
   - 표시 규칙: [명세] / [관측]
2. 등장인물과 신뢰 관계
   - MCP 클라이언트(에이전트) / MCP 서버(보호 리소스) / 인가 서버 / 사용자 브라우저
   - 왜 MCP 서버가 인가 서버를 겸하지 않는가(2025-06-18 에서 분리된 이유)
   - practice 별 포트·계정 표 (official / chat-memory / community)
3. 전체 흐름 한눈에 — 다이어그램 ①
4. 단계별
   4.1 토큰 없는 요청과 401 챌린지 — 다이어그램 ②
   4.2 보호 리소스 메타데이터 발견(fallback 순서, resource 일치 검증)
   4.3 인가 서버 메타데이터 발견(RFC 8414 → OIDC 순서, issuer 일치, S256 확인)
   4.4 클라이언트 등록 — 사전 등록 / CIMD / DCR / 사용자 입력의 우선순위
   4.5 인가 요청 — PKCE 와 resource — 다이어그램 ③
   4.6 콜백과 iss 검증 — mix-up 공격 — 다이어그램 ④
   4.7 토큰 요청과 access token 의 구조 — 다이어그램 ⑤
   4.8 인증된 MCP 호출 — initialize → notifications/initialized → tools/list → tools/call — 다이어그램 ⑥
   4.9 MCP 서버의 토큰 검증(서명·iss·aud·exp)
   4.10 만료와 refresh — 다이어그램 ⑦
   4.11 오류 응답 모음
5. 엔드포인트 명세 (표)
6. 2026-07-28 에서 달라지는 것 — 다이어그램 ⑧ (stateless)
7. 보안 고려사항
8. 준수표
9. 이 practice 에서 다루지 않는 것
10. 출처
```

### 다이어그램 8개

각 다이어그램의 참여자는 `사용자 브라우저`, `MCP 클라이언트(에이전트)`, `MCP 서버`, `인가 서버` 로 통일한다.

1. **전체 흐름**: 401 → PRM → AS 메타데이터 → 인가 요청(브라우저) → 콜백 → 토큰 → 인증된 MCP 호출
2. **발견**: 401 챌린지와 well-known fallback 세 갈래(헤더 → 경로형 → 루트형)
3. **인가 요청**: `code_challenge` 생성, 브라우저 리다이렉트, 로그인, 동의(이 practice 는 생략), 코드 발급
4. **iss 검증**: 정상 경로와, 공격자 인가 서버의 응답이 섞였을 때 거부되는 경로 두 갈래
5. **토큰 요청**: `code_verifier` 검증, `resource` → `aud` 발급, id_token 과의 차이
6. **MCP 세션**: `initialize`(+`Mcp-Session-Id` 발급) → `notifications/initialized`(202) → `tools/list` → `tools/call`, 매 요청의 `Authorization`·`MCP-Protocol-Version`·`Mcp-Session-Id`
7. **만료와 갱신**: 401 → refresh(`resource` 포함) → 재시도
8. **2026-07-28 stateless**: `initialize` 없음, `Mcp-Session-Id` 없음, 매 요청 `_meta` 와 `MCP-Protocol-Version`·`Mcp-Method`·`Mcp-Name` 헤더

### 엔드포인트 명세 표 (10개)

엔드포인트마다 **메서드·URL·요청(헤더/파라미터/본문)·응답(상태·헤더/필드)·근거 조항·관측값** 을 적는다. 아래는 각 표에 들어가야 할 항목의 점검 목록이다. **표를 쓰기 전에 근거 문서를 열어 확인하고, 아래 목록과 다르면 문서 쪽을 따른다.**

| # | 엔드포인트 | 근거 | 표에 반드시 담을 것 |
|---|---|---|---|
| 1 | `POST /mcp` (토큰 없음) | MCP 2025-11-25 인가 §2.2, RFC 6750 §3, RFC 9728 §5.1 | 401, `WWW-Authenticate` 의 `realm`·`scope`·`error`·`error_description`·`error_uri`·`resource_metadata` |
| 2 | `GET /.well-known/oauth-protected-resource[/path]` | RFC 9728 §2·§3 | `resource`(REQUIRED), `authorization_servers`, `jwks_uri`, `scopes_supported`, `bearer_methods_supported`, `resource_signing_alg_values_supported`, `resource_name`, `resource_documentation`, `resource_policy_uri`, `resource_tos_uri`, `tls_client_certificate_bound_access_tokens`, `authorization_details_types_supported`, `dpop_signing_alg_values_supported`, `dpop_bound_access_tokens_required`, `signed_metadata` |
| 3 | `GET /.well-known/oauth-authorization-server` | RFC 8414 §2, RFC 9207 §3 | `issuer`·`response_types_supported`(REQUIRED), `authorization_endpoint`, `token_endpoint`, `jwks_uri`, `registration_endpoint`, `scopes_supported`, `response_modes_supported`, `grant_types_supported`, `token_endpoint_auth_methods_supported`, `code_challenge_methods_supported`, `revocation_endpoint`, `introspection_endpoint`, `service_documentation`, `op_policy_uri`, `op_tos_uri`, `authorization_response_iss_parameter_supported` |
| 4 | `GET /.well-known/openid-configuration` | OIDC Discovery 1.0 §3 | RFC 8414 와의 차이(`subject_types_supported`, `id_token_signing_alg_values_supported` 등 REQUIRED 항목), MCP 가 RFC 8414 를 먼저 시도하는 이유 |
| 5 | `GET /oauth2/authorize` | RFC 6749 §4.1.1, RFC 7636 §4.3, RFC 8707 §2.1, OAuth 2.1 §4.1.1 | `response_type`·`client_id`(REQUIRED), `redirect_uri`, `scope`, `state`, `code_challenge`·`code_challenge_method`, `resource`, `prompt`·`login_hint` 등 OIDC 추가분은 이 practice 범위 밖임을 명시 |
| 6 | 인가 응답(리다이렉트) | RFC 6749 §4.1.2·§4.1.2.1, RFC 9207 §2 | 성공: `code`(REQUIRED), `state`, `iss`. 오류: `error`(REQUIRED), `error_description`, `error_uri`, `state`, `iss`. 오류 코드 목록(`invalid_request`, `unauthorized_client`, `access_denied`, `unsupported_response_type`, `invalid_scope`, `server_error`, `temporarily_unavailable`, `invalid_target`) |
| 7 | `POST /oauth2/token` (authorization_code) | RFC 6749 §4.1.3·§5.1·§5.2, RFC 7636 §4.5, RFC 8707 §2.2 | 요청: `grant_type`·`code`(REQUIRED), `redirect_uri`, `client_id`, `code_verifier`, `resource`. 클라이언트 인증(Basic). 응답: `access_token`·`token_type`(REQUIRED), `expires_in`, `refresh_token`, `scope`, `id_token`(OIDC). 오류: `invalid_request`, `invalid_client`, `invalid_grant`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`, `invalid_target` |
| 8 | `POST /oauth2/token` (refresh_token) | RFC 6749 §6, RFC 8707 §2.2 | `grant_type`·`refresh_token`(REQUIRED), `scope`, `resource` |
| 9 | `POST /mcp` (Bearer) | MCP 2025-11-25 전송(Streamable HTTP), RFC 6750 §2.1 | 요청 헤더: `Authorization`(REQUIRED), `Content-Type`, `Accept`(둘 다: `application/json`, `text/event-stream`), `MCP-Protocol-Version`, `Mcp-Session-Id`, `Origin`. 응답: 200(JSON 또는 SSE)·202(알림)·400(세션/버전 오류)·401·403(Origin)·404(만료 세션). `initialize` 응답의 `Mcp-Session-Id` |
| 10 | `DELETE /mcp`, `GET /mcp` | MCP 2025-11-25 전송 | 세션 종료(405 허용), SSE 스트림 열기(405 허용), `Last-Event-ID` 재개 |

### 준수표

행은 명세 항목, 열은 `official` / `chat-memory` / `community` 로 만든다. 최소한 아래 항목을 포함한다.

- PRM 제공(RFC 9728 MUST), `resource` 일치(§3.3)
- 401 의 `resource_metadata`(MCP MUST)
- 클라이언트의 PRM 발견과 fallback 순서(MUST)
- AS 메타데이터 발견 순서와 `issuer` 검증(MUST)
- `code_challenge_methods_supported` 확인(MUST)
- PKCE S256(MUST)
- `resource` 파라미터 — 인가·토큰·갱신(MUST)
- 토큰 audience 발급과 검증(MUST)
- RFC 9207 `iss` — AS 광고(SHOULD)·클라이언트 검증(MUST when present)
- 자격증명의 issuer 바인딩(2026-07-28)
- Origin 검증(MUST), Host 검증
- HTTPS(MUST) — 세 practice 모두 위반, 이유 명시
- 토큰 passthrough 금지(MUST) — 각 practice 가 사용자 토큰만 쓰고 다른 토큰을 전달하지 않음
- DCR / CIMD / scope·step-up — 다루지 않음

### 출처 (전부 싣는다)

작성 전에 아래를 열어 확인한다. 2026-07-28 문서의 정확한 하위 경로는 목차에서 확인한다.

- MCP 2025-11-25: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization , `.../basic/transports` , `.../basic/lifecycle` , `.../basic/security_best_practices` , `.../changelog`
- MCP 2026-07-28: https://modelcontextprotocol.io/specification/2026-07-28 (인가·전송·변경점 하위 문서)
- RFC 9728 Protected Resource Metadata: https://www.rfc-editor.org/rfc/rfc9728
- RFC 8414 Authorization Server Metadata: https://www.rfc-editor.org/rfc/rfc8414
- RFC 8707 Resource Indicators: https://www.rfc-editor.org/rfc/rfc8707
- RFC 9207 Issuer Identification: https://www.rfc-editor.org/rfc/rfc9207
- RFC 7636 PKCE: https://www.rfc-editor.org/rfc/rfc7636
- RFC 6749 OAuth 2.0: https://www.rfc-editor.org/rfc/rfc6749
- RFC 6750 Bearer Token Usage: https://www.rfc-editor.org/rfc/rfc6750
- RFC 7591 Dynamic Client Registration: https://www.rfc-editor.org/rfc/rfc7591
- RFC 9068 JWT Profile for Access Tokens: https://www.rfc-editor.org/rfc/rfc9068
- OAuth 2.1: https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13
- OpenID Connect Discovery 1.0: https://openid.net/specs/openid-connect-discovery-1_0.html
- OAuth Client ID Metadata Document: https://datatracker.ietf.org/doc/draft-ietf-oauth-client-id-metadata-document/

### 단계

- [ ] **Step 1: 근거 문서를 확인한다**

MCP 2025-11-25 인가·전송 문서와 RFC 9728·8414·8707·9207·7636·6749·6750 의 해당 절을 열어, 위 "표에 반드시 담을 것" 목록이 맞는지 확인한다. 다르면 문서를 따른다.

- [ ] **Step 2: 캡처에서 관측값을 뽑는다**

```bash
grep -n '=====' docs/superpowers/captures/2026-09-12-official.txt
```

각 단계의 실제 요청 줄과 응답 헤더·본문을 문서에 인용할 형태로 정리한다. 토큰 문자열은 앞 20자만 남기고 `...` 로 줄인다.

- [ ] **Step 3: 문서를 쓴다**

위 구조대로 `practice/MCP-AUTHORIZATION.md` 를 작성한다. 다이어그램 8개와 엔드포인트 표 10개, 준수표, 출처를 모두 포함한다.

- [ ] **Step 4: 자체 점검**

- 다이어그램 8개가 모두 있는가. mermaid 문법이 맞는가(```mermaid 펜스, `sequenceDiagram`)
- 엔드포인트 표 10개가 모두 있고, 각 항목에 REQUIRED/RECOMMENDED/OPTIONAL 표시가 있는가
- 모든 절에 근거 조항 링크가 있는가
- 시행착오 서술이 없는가 (`grep -nE '처음에는|알고 보니|버그|고쳤다|삽질' practice/MCP-AUTHORIZATION.md` 가 비어야 한다)
- 준수표의 각 칸이 "예/아니오 + 근거" 로 채워져 있는가
- 관측값과 명세가 어긋나는 곳에 설명이 있는가

- [ ] **Step 5: 커밋한다**

```bash
git add practice/MCP-AUTHORIZATION.md && git commit -m "docs: 인증이 포함된 MCP 학습 문서 — 시퀀스 다이어그램과 엔드포인트 명세"
```

---

## Task 11: README 갱신

**Files:**
- Modify: `practice/mcp-security-authn-official/README.md`
- Modify: `practice/mcp-security-authn-chat-memory/README.md`
- Modify: `practice/mcp-security-authn-community/README.md`
- Modify: `README.md` (루트)

**Interfaces:**
- Consumes: Task 10 의 문서, Task 1~9 의 변경 사실
- Produces: 세 practice 가 새 문서를 가리키고, 바뀐 사실이 반영된 README

- [ ] **Step 1: 오래된 서술을 찾는다**

```bash
grep -nE 'issuer-uri|resource_metadata|oauth-protected-resource|PKCE|aud|audience|9010|8111|9000|8101|9020|8131' practice/mcp-security-authn-*/README.md | head -60
```

- [ ] **Step 2: 세 README 를 고친다**

각 README 에서 다음을 한다.

1. 문서 링크를 맨 앞 개요 근처에 넣는다.

```markdown
> 인증이 포함된 MCP 스펙 자체를 배우려면 [MCP 인가 표준 문서](../MCP-AUTHORIZATION.md) 를 먼저 읽는다.
> 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.
```

2. 바뀐 사실을 고친다 — 최소한 아래 네 가지다.
   - 에이전트가 인가 서버 주소를 설정에 두지 않고 발견한다
   - 인가 요청에 PKCE(S256)와 `resource` 가 실린다
   - 토큰의 `aud` 가 MCP 서버이고 MCP 서버가 그것을 검증한다
   - MCP 엔드포인트가 Origin/Host 를 검증한다
3. 새 파일 목록(클래스 이름과 한 줄 설명)을 해당 절에 추가한다.
4. 사실과 다른 문장을 지운다. 특히 "issuer-uri 를 설정한다" 류의 설명.

- [ ] **Step 3: 루트 README 를 고친다**

practice 목록 위 또는 아래에 문서 한 줄을 추가한다.

```markdown
- [MCP 인가 표준](practice/MCP-AUTHORIZATION.md) — 인증이 포함된 MCP 스펙 정리 (시퀀스 다이어그램·엔드포인트 명세·준수표)
```

- [ ] **Step 4: 링크를 확인한다**

```bash
grep -c 'MCP-AUTHORIZATION.md' README.md practice/mcp-security-authn-official/README.md practice/mcp-security-authn-chat-memory/README.md practice/mcp-security-authn-community/README.md
```

기대: 네 파일 모두 1 이상.

- [ ] **Step 5: 커밋한다**

```bash
git add README.md practice/mcp-security-authn-official/README.md practice/mcp-security-authn-chat-memory/README.md practice/mcp-security-authn-community/README.md && git commit -m "docs: 세 practice README 를 표준 준수 내용으로 갱신하고 학습 문서로 연결"
```

---

## 마무리

모든 작업이 끝나면 전체 테스트를 한 번 더 돌리고, 세 practice 를 차례로 띄워 브라우저 로그인까지 확인한 뒤 브랜치를 정리한다(superpowers:finishing-a-development-branch).

```bash
for m in practice/mcp-security-authn-official practice/mcp-security-authn-chat-memory practice/mcp-security-authn-community; do for a in auth-server shop-mcp-server shop-agent; do (cd $m/$a && JAVA_HOME=$HOME/.sdkman/candidates/java/current ./gradlew test -q) || echo "FAILED: $m/$a"; done; done
```
