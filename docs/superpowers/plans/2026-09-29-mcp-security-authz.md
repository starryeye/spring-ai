# mcp-security-authz (scope와 step-up) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** official practice를 복사해 tool마다 scope를 두고, `403 insufficient_scope`와 step-up(웹 agent의 대화 안 consent 카드, `local-client`의 다시 login)을 보여 주는 `practice/mcp-security-authz`와 안내서 10장을 만든다.

**Architecture:** official의 네 module(auth-server, shop-mcp-server, shop-agent, local-client)을 그대로 복사한 뒤 바꾼다. MCP Server는 Spring Security 뒤의 servlet filter가 JSON-RPC 본문에서 tool 이름을 읽어 필요한 scope를 검사하고 `403`과 `WWW-Authenticate`를 돌려준다. agent는 MCP SDK의 authorization error handler로 `403`을 잡아 예외로 올리고, Spring AI tool 예외 처리가 그 예외를 채팅까지 흘려보내 SSE 카드 event가 된다. `local-client`는 같은 handler 안에서 browser authorization을 한 번 더 하고 요청을 다시 보낸다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7.1.0(Spring Authorization Server 포함), Spring AI 2.0.0, MCP Java SDK 2.0.0, Jackson 3(`tools.jackson`), JUnit 5, MockMvc, bash + curl(캡처).

**Spec:** `docs/superpowers/specs/2026-09-29-mcp-security-authz-design.md`

## Global Constraints

- practice 폴더: `practice/mcp-security-authz/`, module 네 개: `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`.
- package: `dev.starryeye.authz.authserver`, `dev.starryeye.authz.mcpserver`, `dev.starryeye.authz.agent`, `dev.starryeye.authz.localclient`. 하위 package는 official과 같은 계층별 구성(`config`, `security`, `filter`, `tool`, `domain`, `repository`, `controller`, `discovery`, `mcp`)이고 `local-client`는 한 package다.
- 포트: auth-server 9030, shop-mcp-server 8141(`/mcp`), shop-agent 8140. `local-client`의 loopback redirect 등록값은 `http://127.0.0.1:8123/callback`.
- `client_id`: `authz-shop-agent`(secret `authz-shop-agent-secret`, confidential), `local-mcp-client`(public). cookie: `AUTHZAUTHSESSIONID`, `AUTHZAGENTSESSIONID`. 계정 `user`/`password`. agent의 registration id는 `authserver`.
- scope: `products:read`(조회, 모든 MCP 요청의 기본 scope), `products:write`(재고 변경). scope 계층은 없다. `offline_access`는 challenge와 `scopes_supported`에 넣지 않는다.
- 새 tool: `updateStock(productId, quantity)` — 재고를 `quantity`로 바꾸고 바뀐 값을 문장으로 돌려준다.
- 의존성은 바꾸지 않는다(버전·starter 추가 없음). official·chat-memory·community 코드는 건드리지 않는다.
- Gradle은 `JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)`로 돌린다.
- 서버를 띄웠으면 끝난 뒤 포트로 내린다: `lsof -ti tcp:PORT -sTCP:LISTEN | xargs kill`(`-sTCP:LISTEN` 없이 쓰면 연결된 다른 process까지 죽는다). `run.sh`는 background로 돌린다.
- `.superpowers/`, `logs/`, `build/`, `.gradle/`은 절대 `git add`하지 않는다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 코드 주석·javadoc·문서는 저장소 스킬 `.claude/skills/writing-practice-docs/SKILL.md` 문체를 따른다: 기술 용어는 흔한 말도 영어(token, client, scope, consent, login, browser, header, parameter, session, redirect, callback…), 영어·코드 뒤 조사는 붙여 쓴다("token을"), 번역 어투(싣다, 걸다, 배선, 물러나다, 드러나다, 내놓다, 모양) 금지, 한 줄에 한 문장, 이유를 규칙보다 먼저.
- 안내서 장 본문에는 MUST 같은 요구 수준 단어·캡처 번호·테스트 메서드 이름·HTML·`<a id>`를 쓰지 않는다. 요구 수준은 장 끝 "명세 근거" 표에만 쓴다. 부록(`reference-*.md`)은 예외.

## Review Focus

1. 본문이 JSON이 아니거나 `tools/call`에 `params.name`이 없으면, scope filter는 500으로 터지지 않고 기본 scope만 보고 transport로 넘긴다(transport가 JSON-RPC 오류로 답한다). — Task 4의 `형식이_틀린_본문도_기본_scope만_보고_transport로_넘긴다`, `이름_없는_tools_call은_기본_scope만_본다`.
2. `products:write`만 있고 `products:read`가 없는 token(public client consent에서 write만 체크한 경우)은 모든 MCP 요청이 `403 scope="products:read"`다. — Task 4의 `write만_있는_token도_기본_scope가_없으면_403이다`.
3. challenge의 `scope`에 여러 값이 공백으로 들어오면(`scope="a b"`) 두 client 모두 나눠서 합친다. — Task 6·9의 `BearerChallengeTest`.
4. `insufficient_scope`가 아닌 `403`(예: `Origin` 거절, header 없음)은 step-up을 시작하지 않는다. — Task 6의 `insufficient_scope가_아닌_403은_step_up을_시작하지_않는다`, Task 9의 같은 이름 테스트.
5. step-up 뒤 access token이 만료되어 refresh해도 scope가 줄지 않는다(refresh 요청에 `scope`를 보내지 않는다). — Task 5의 `refresh_요청에_scope를_보내지_않아_늘어난_scope가_유지된다`.

---

### Task 1: official을 복사해 `practice/mcp-security-authz`를 만든다

**Files:**
- Create: `practice/mcp-security-authz/**` (official의 `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`, `run.sh`, `stop.sh`, `.gitignore` 복사본. `README.md`는 복사하지 않는다 — Task 11에서 새로 쓴다)

**Interfaces:**
- Produces: 위 Global Constraints의 이름·포트로 바뀐 네 module. 모든 테스트가 official과 같은 개수로 통과한다(auth-server 38, shop-mcp-server 26, shop-agent 38, local-client 31).

- [ ] **Step 1: 복사하고 package 폴더를 옮긴다**

저장소 최상위 폴더에서:

```bash
set -euo pipefail
SRC=practice/mcp-security-authn-official
DST=practice/mcp-security-authz
test ! -e "$DST"
rsync -a --exclude build --exclude .gradle --exclude .idea --exclude logs --exclude out --exclude bin \
  --exclude README.md "$SRC/" "$DST/"
for m in auth-server shop-mcp-server shop-agent local-client; do
  for s in main test; do
    d="$DST/$m/src/$s/java/dev/starryeye"
    if [ -d "$d/official" ]; then mv "$d/official" "$d/authz"; fi
  done
done
```

- [ ] **Step 2: 이름과 포트를 바꾼다**

```bash
python3 - <<'EOF'
import pathlib, re
root = pathlib.Path('practice/mcp-security-authz')
rules = [
    (r'dev\.starryeye\.official\.', 'dev.starryeye.authz.'),
    (r'official-shop-agent-secret', 'authz-shop-agent-secret'),
    (r'official-shop-agent', 'authz-shop-agent'),
    (r'official_shop_agent_', 'authz_shop_agent_'),
    (r'official-shop-mcp-server', 'authz-shop-mcp-server'),
    (r'official-auth-server', 'authz-auth-server'),
    (r'OFFICIALAUTHSESSIONID', 'AUTHZAUTHSESSIONID'),
    (r'OFFICIALAGENTSESSIONID', 'AUTHZAGENTSESSIONID'),
    (r'<title>official shop-agent</title>', '<title>authz shop-agent</title>'),
    (r'(?<!\d)9010(?!\d)', '9030'),
    (r'(?<!\d)8111(?!\d)', '8141'),
    (r'(?<!\d)8110(?!\d)', '8140'),
]
for p in root.rglob('*'):
    if p.is_file() and p.suffix in {'.java', '.yml', '.html', '.gradle', '.properties', '.sh'}:
        s = p.read_text(encoding='utf-8')
        t = s
        for a, b in rules:
            t = re.sub(a, b, t)
        if t != s:
            p.write_text(t, encoding='utf-8')
EOF
grep -rIn "official\|OFFICIAL\|9010\|8111\|8110" practice/mcp-security-authz --exclude-dir=build --exclude-dir=.gradle || echo "남은 것 없음"
```

Expected: `남은 것 없음`. 무엇이 남으면 위 규칙으로 바뀌지 않은 표기이므로 같은 뜻의 새 이름으로 고친다.

- [ ] **Step 3: 네 module의 테스트를 돌린다**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for m in auth-server shop-mcp-server shop-agent local-client; do
  (cd practice/mcp-security-authz/$m && ./gradlew -q test && echo "$m OK")
done
```

Expected: 네 줄 모두 `OK`.

- [ ] **Step 4: 커밋**

```bash
git status --short practice/mcp-security-authz | grep -E '/(build|logs|\.gradle|\.idea)/' && echo "제외할 파일이 섞였다" || true
git add practice/mcp-security-authz
git commit -m "feat(authz): official을 복사해 mcp-security-authz practice 시작

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: auth-server — scope 등록, agent consent, access token의 `client_id`

**Files:**
- Modify: `practice/mcp-security-authz/auth-server/src/main/resources/application.yml`
- Modify: `practice/mcp-security-authz/auth-server/src/main/java/dev/starryeye/authz/authserver/security/ResourceAudienceTokenCustomizer.java`
- Modify: `practice/mcp-security-authz/auth-server/src/test/java/dev/starryeye/authz/authserver/AuthorizationServerStandardTest.java`

**Interfaces:**
- Produces: 두 client에 `products:read`·`products:write`(agent는 `openid`도) 등록. agent도 consent 화면을 거친다. access token에 `client_id` claim.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`AuthorizationServerStandardTest`에 다음을 더한다. import에 `java.net.URI`, `java.util.Arrays`, `org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent`, `org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService`, `org.springframework.security.oauth2.server.authorization.client.RegisteredClient`, `org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository`, `org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder`를 더한다.

```java
	@Autowired
	OAuth2AuthorizationConsentService authorizationConsentService;

	@Autowired
	RegisteredClientRepository registeredClientRepository;

	/** 기밀 client의 authorization request 주소다. PKCE와 resource를 넣는다. */
	static URI 기밀클라이언트_인가요청_URI(String scope) {
		return UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", CLIENT_ID)
				.queryParam("redirect_uri", REDIRECT_URI)
				.queryParam("scope", scope)
				.queryParam("state", "state-1")
				.queryParam("code_challenge", CODE_CHALLENGE)
				.queryParam("code_challenge_method", "S256")
				.queryParam("resource", RESOURCE)
				.encode().build().toUri();
	}

	/**
	 * 기밀 client의 authorization request를 보낸다.
	 * consent 화면(200)이 나오면 approvedScopes를 체크해 제출하고, 이미 consent한 scope만 요청했으면 곧장 redirect(302)를 받는다.
	 */
	UriComponents 기밀클라이언트_인가(URI uri, String... approvedScopes) throws Exception {
		MvcResult result = this.mockMvc.perform(get(uri).session(this.session)).andReturn();
		if (result.getResponse().getStatus() == 200) {
			MockHttpServletRequestBuilder consent = post("/oauth2/authorize").session(this.session)
					.param("client_id", CLIENT_ID)
					.param("state", 동의화면_state(result.getResponse().getContentAsString()));
			for (String scope : approvedScopes) {
				consent.param("scope", scope);
			}
			result = this.mockMvc.perform(consent).andReturn();
		}
		assertThat(result.getResponse().getStatus()).isEqualTo(302);
		return UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();
	}

	/** consent할 scope다. openid는 consent 대상이 아니라 뺀다. */
	static String[] 동의할_scope(String scope) {
		return Arrays.stream(scope.split(" ")).filter(s -> !"openid".equals(s)).toArray(String[]::new);
	}

	static List<String> 토큰의_scope(String jwt) throws Exception {
		return SignedJWT.parse(jwt).getJWTClaimsSet().getStringListClaim("scope");
	}

	@Test
	void 기밀_클라이언트도_처음_요청하는_scope에는_동의_화면을_거친다() throws Exception {
		MvcResult response = this.mockMvc
				.perform(get(기밀클라이언트_인가요청_URI("openid products:read")).session(this.session))
				.andReturn();

		assertThat(response.getResponse().getStatus()).isEqualTo(200);
		assertThat(response.getResponse().getContentAsString()).contains("Consent required").contains("products:read");
	}

	@Test
	void step_up_동의_화면에는_새_scope만_선택_항목으로_나온다() throws Exception {
		기밀클라이언트_인가(기밀클라이언트_인가요청_URI("openid products:read"), "products:read");

		MvcResult stepUp = this.mockMvc
				.perform(get(기밀클라이언트_인가요청_URI("openid products:read products:write")).session(this.session))
				.andReturn();

		assertThat(stepUp.getResponse().getStatus()).isEqualTo(200);
		String html = stepUp.getResponse().getContentAsString();
		// 새 scope는 체크되지 않은 선택 항목이다.
		assertThat(html).contains("value=\"products:write\" id=\"products:write\">");
		// 이미 허락한 scope는 체크된 채 바꿀 수 없게 나온다.
		assertThat(html).contains("id=\"products:read\" checked disabled>");
	}

	@Test
	void step_up_에서_새_scope를_체크하지_않으면_이전_scope만_담긴_token을_받는다() throws Exception {
		기밀클라이언트_인가(기밀클라이언트_인가요청_URI("openid products:read"), "products:read");

		UriComponents response = 기밀클라이언트_인가(기밀클라이언트_인가요청_URI("openid products:read products:write"));
		String body = 토큰요청(인가코드교환(응답파라미터(response, "code"), RESOURCE), 200);

		assertThat(JsonPath.<String>read(body, "$.scope").split(" "))
				.containsExactlyInAnyOrder("openid", "products:read");
		assertThat(토큰의_scope(JsonPath.read(body, "$.access_token")))
				.containsExactlyInAnyOrder("openid", "products:read");
	}

	@Test
	void step_up_에서_새_scope를_허락하면_두_scope가_모두_담긴다() throws Exception {
		기밀클라이언트_인가(기밀클라이언트_인가요청_URI("openid products:read"), "products:read");

		UriComponents response = 기밀클라이언트_인가(
				기밀클라이언트_인가요청_URI("openid products:read products:write"), "products:write");
		String body = 토큰요청(인가코드교환(응답파라미터(response, "code"), RESOURCE), 200);

		assertThat(토큰의_scope(JsonPath.read(body, "$.access_token")))
				.containsExactlyInAnyOrder("openid", "products:read", "products:write");
	}

	@Test
	void access_token_에는_발급받은_client_의_client_id가_있다() throws Exception {
		String body = 토큰요청(인가코드교환(인가코드(RESOURCE), RESOURCE), 200);

		assertThat(SignedJWT.parse(JsonPath.read(body, "$.access_token")).getJWTClaimsSet()
				.getStringClaim("client_id")).isEqualTo(CLIENT_ID);
	}
```

- [ ] **Step 2: 기존 도우미와 테스트를 새 scope에 맞춘다**

같은 파일에서:

1. `로그인한다()` 끝에 agent의 consent를 지우는 코드를 더한다. consent 저장소는 context 전체가 함께 쓰므로, 지우지 않으면 앞 테스트의 consent가 뒤 테스트에 남는다.

```java
		RegisteredClient agent = this.registeredClientRepository.findByClientId(CLIENT_ID);
		OAuth2AuthorizationConsent consent = this.authorizationConsentService.findById(agent.getId(), USERNAME);
		if (consent != null) {
			this.authorizationConsentService.remove(consent);
		}
```

2. `인가요청(boolean pkce, String resource)`: `.queryParam("scope", "openid profile")`을 `.queryParam("scope", "openid products:read")`로 바꾸고, `String location = ...` 부터 `return`까지를 다음 한 줄로 바꾼다.

```java
		return 기밀클라이언트_인가(uri.encode().build().toUri(), "products:read");
```

3. `인가요청(String scope, String... resources)`: `String location = ...` 부터 `return`까지를 다음 한 줄로 바꾼다.

```java
		return 기밀클라이언트_인가(uri.encode().build().toUri(), 동의할_scope(scope));
```

4. `공개클라이언트_인가요청_URI`의 `"openid profile"` → `"products:read"`, `공개클라이언트_인가코드`의 `공개클라이언트_동의(authorizationResponse, "profile")` → `"products:read"`, redirect URI 테스트(`등록되지_않은_redirect_uri_는_리다이렉트_없이_거부된다`)의 `"openid profile"` → `"openid products:read"`, 루프백 테스트의 `공개클라이언트_동의(consentPage, "profile")` → `"products:read"`, `인가_요청의_resource_가_여러_개면_invalid_target_이다`의 `"openid profile"` → `"openid products:read"`.

5. `공개_클라이언트가_openid_없이_profile_만_요청해도_consent_화면을_거친다`를 `공개_클라이언트가_openid_없이_products_read_만_요청해도_consent_화면을_거친다`로 이름을 바꾸고 `replaceQueryParam("scope", "profile")`을 `replaceQueryParam("scope", "products:read")`로 바꾼다.

6. `공개_클라이언트는_동의_화면을_거치고_기존_에이전트는_바로_코드를_받는다`는 지운다. 위 `기밀_클라이언트도_처음_요청하는_scope에는_동의_화면을_거친다`가 대신한다.

- [ ] **Step 3: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-security-authz/auth-server && ./gradlew test --tests 'dev.starryeye.authz.authserver.AuthorizationServerStandardTest'
```

Expected: FAIL. 등록된 scope가 `openid profile`이라 `products:read`가 `invalid_scope`이고, agent는 consent 화면 없이 code를 받으며, `client_id` claim이 없다.

- [ ] **Step 4: 설정과 token customizer를 바꾼다**

`application.yml`의 `authz-shop-agent` registration에서 `scopes`와 `require-authorization-consent`를 다음처럼 바꾼다.

```yaml
              scopes:
                - openid
                # MCP Server의 tool을 부를 때 쓰는 scope다. 조회와 재고 변경을 나눈다(안내서 10장).
                - products:read
                - products:write
            require-proof-key: true
            # official은 false다. 이 practice는 step-up에서 사용자가 새 scope를 직접 확인해야 하므로 켠다.
            # consent는 사용자별로 저장되고, 다음 요청에서는 새로 요청한 scope만 묻는다.
            require-authorization-consent: true
```

`local-mcp-client` registration의 `scopes`를 다음처럼 바꾼다(`openid`, `profile`을 뺀다).

```yaml
              scopes:
                - products:read
                - products:write
```

`ResourceAudienceTokenCustomizer#customize`의 access token 분기 바로 뒤(`if (!OAuth2TokenType.ACCESS_TOKEN...) return;` 다음 줄)에 다음을 넣는다. 클래스 javadoc 끝에도 한 문단을 더한다.

```java
		// RFC 9068 §2.2: JWT access token에는 발급받은 client의 client_id를 넣는다.
		// 아래에서 aud를 resource로 바꾸면 client를 가리키는 값이 없어지므로, 서버가 어느 client를 거친 요청인지 알 수 있게 한다.
		context.getClaims().claim("client_id", context.getRegisteredClient().getClientId());
```

```java
 * <p>access token에는 {@code client_id}도 넣는다(RFC 9068 §2.2).
 * {@code aud}가 resource로 바뀌어도 MCP Server는 어느 client에 발급된 token인지 알 수 있다.
```

- [ ] **Step 5: 테스트가 통과하는지 본다**

```bash
cd practice/mcp-security-authz/auth-server && ./gradlew test
```

Expected: PASS(38 − 1 + 5 = 42개).

- [ ] **Step 6: 커밋**

```bash
git add practice/mcp-security-authz/auth-server
git commit -m "feat(authz): auth-server에 products scope, agent consent, access token의 client_id

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: shop-mcp-server — `updateStock` tool과 tool별 scope 표

**Files:**
- Create: `practice/mcp-security-authz/shop-mcp-server/src/main/java/dev/starryeye/authz/mcpserver/tool/RequiredScope.java`
- Create: `practice/mcp-security-authz/shop-mcp-server/src/main/java/dev/starryeye/authz/mcpserver/tool/ToolScopeRegistry.java`
- Modify: `.../mcpserver/tool/ProductTools.java`, `.../mcpserver/repository/ProductRepository.java`
- Test: `.../src/test/java/dev/starryeye/authz/mcpserver/tool/ToolScopeRegistryTest.java`(create), `.../tool/ProductToolsTest.java`, `.../mcpserver/ShopMcpServerApplicationTests.java`

**Interfaces:**
- Produces: `@RequiredScope(String value)`; `ToolScopeRegistry.BASE_SCOPE = "products:read"`, `static ToolScopeRegistry scan(Object... toolBeans)`, `String scopeFor(String toolName)`, `Map<String, String> all()`; `ProductTools#updateStock(String productId, int quantity)`; `ProductRepository#updateStock(String id, int stock): Optional<Product>`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ToolScopeRegistryTest`:

```java
package dev.starryeye.authz.mcpserver.tool;

import dev.starryeye.authz.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeRegistryTest {

	ToolScopeRegistry registry = ToolScopeRegistry.scan(new ProductTools(new ProductRepository()));

	@Test
	void 조회_tool은_products_read다() {
		assertThat(this.registry.scopeFor("getStock")).isEqualTo("products:read");
		assertThat(this.registry.scopeFor("searchProducts")).isEqualTo("products:read");
	}

	@Test
	void 재고_변경_tool은_products_write다() {
		assertThat(this.registry.scopeFor("updateStock")).isEqualTo("products:write");
	}

	@Test
	void 모르는_tool은_기본_scope만_요구한다() {
		assertThat(this.registry.scopeFor("nope")).isEqualTo(ToolScopeRegistry.BASE_SCOPE);
	}

	@Test
	void McpTool이_붙은_메서드를_모두_모은다() {
		assertThat(this.registry.all()).containsOnlyKeys("searchProducts", "getStock", "updateStock");
	}
}
```

`ProductToolsTest`에 더한다:

```java
    @Test
    void 재고를_바꾸고_바뀐_값을_문장으로_반환한다() {
        assertThat(tools.updateStock("p1", 10)).isEqualTo("상품 p1 (게이밍 노트북 15인치) 의 재고를 10개로 바꿨습니다.");
        assertThat(tools.getStock("p1")).contains("10개");
    }

    @Test
    void 음수_재고는_바꾸지_않는다() {
        assertThat(tools.updateStock("p1", -1)).isEqualTo("재고 수량은 0 이상이어야 합니다. (받은 값: -1)");
        assertThat(tools.getStock("p1")).contains("7개");
    }

    @Test
    void 없는_상품의_재고는_바꾸지_않는다() {
        assertThat(tools.updateStock("p99", 3)).isEqualTo("상품 p99 를 찾을 수 없습니다.");
    }
```

`ShopMcpServerApplicationTests#MCP_툴이_실제로_등록된다`의 기대값을 `containsExactlyInAnyOrder("searchProducts", "getStock", "updateStock")`로 바꾼다.

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-mcp-server && ./gradlew test
```

Expected: 컴파일 실패(`ToolScopeRegistry`, `updateStock` 없음).

- [ ] **Step 3: 구현한다**

`RequiredScope.java`:

```java
package dev.starryeye.authz.mcpserver.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 tool을 부르려면 access token에 있어야 하는 scope다(안내서 10장).
 *
 * <p>MCP 명세에는 tool 정의에 필요한 scope를 적는 표준 field가 없다.
 * 그래서 서버 안에서만 쓰는 annotation으로 tool 옆에 적고, {@link ToolScopeRegistry}가 모은다.
 * client에게는 tool 정의로 알리지 않고, 권한이 모자랄 때 {@code 403}의 {@code WWW-Authenticate}로 알린다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiredScope {

	String value();
}
```

`ToolScopeRegistry.java`:

```java
package dev.starryeye.authz.mcpserver.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tool 이름마다 필요한 scope를 모아 둔 표다.
 *
 * <p>scope 검사는 {@code ToolScopeFilter}가 HTTP 단계에서 한다.
 * filter는 tool 코드를 모르므로, 기동할 때 {@link McpTool} 메서드의 {@link RequiredScope}를 읽어 이 표를 만든다.
 */
public final class ToolScopeRegistry {

	/** {@code tools/list}를 포함한 모든 MCP 요청에 필요한 기본 scope다. 가장 위험이 낮은 조회 권한이다. */
	public static final String BASE_SCOPE = "products:read";

	private final Map<String, String> scopeByTool;

	private ToolScopeRegistry(Map<String, String> scopeByTool) {
		this.scopeByTool = Map.copyOf(scopeByTool);
	}

	/** {@link McpTool}이 붙은 메서드를 찾아 tool 이름과 scope를 모은다. {@link RequiredScope}가 없으면 기본 scope다. */
	public static ToolScopeRegistry scan(Object... toolBeans) {
		Map<String, String> scopes = new LinkedHashMap<>();
		for (Object bean : toolBeans) {
			for (Method method : ClassUtils.getUserClass(bean).getMethods()) {
				McpTool tool = method.getAnnotation(McpTool.class);
				if (tool == null) {
					continue;
				}
				String name = tool.name().isEmpty() ? method.getName() : tool.name();
				RequiredScope required = method.getAnnotation(RequiredScope.class);
				scopes.put(name, (required != null) ? required.value() : BASE_SCOPE);
			}
		}
		return new ToolScopeRegistry(scopes);
	}

	/** 모르는 tool이면 기본 scope만 요구한다. 없는 tool은 transport가 JSON-RPC 오류로 답한다. */
	public String scopeFor(String toolName) {
		return this.scopeByTool.getOrDefault(toolName, BASE_SCOPE);
	}

	public Map<String, String> all() {
		return this.scopeByTool;
	}
}
```

`ProductRepository`: 세 메서드를 `synchronized`로 만들고(`findByKeyword`, `findById`), 아래를 더한다. 클래스 javadoc에 "재고를 바꾸는 tool이 생겨 여러 요청이 같은 저장소를 고칠 수 있으므로 메서드를 `synchronized`로 둔다" 한 줄을 더한다.

```java
    /** 재고를 바꾼다. 없는 상품이면 빈 값이다. */
    public synchronized Optional<Product> updateStock(String id, int stock) {
        Product updated = store.computeIfPresent(id, (key, product) ->
                new Product(product.id(), product.name(), product.category(), product.price(), stock));
        return Optional.ofNullable(updated);
    }
```

`ProductTools`: `searchProducts`와 `getStock`에 `@RequiredScope("products:read")`를 붙이고(import `dev.starryeye.authz.mcpserver.tool.RequiredScope`는 같은 package라 필요 없다), 아래 tool을 더한다.

```java
    @McpTool(
            name = "updateStock",
            description = "상품 ID의 재고 수량을 quantity로 바꾼다. "
                    + "사용자가 재고를 바꿔 달라고 분명히 요청할 때만 사용한다. "
                    + "바뀐 뒤의 재고를 반환한다."
    )
    @RequiredScope("products:write")
    public String updateStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)
            String productId,
            @McpToolParam(description = "바꿀 재고 수량. 0 이상의 정수", required = true)
            int quantity) {
        log.info("updateStock 호출 (productId={}, quantity={}, 사용자={})", productId, quantity, currentUser());

        if (quantity < 0) {
            return "재고 수량은 0 이상이어야 합니다. (받은 값: %d)".formatted(quantity);
        }
        return productRepository.updateStock(productId, quantity)
                .map(product -> "상품 %s (%s) 의 재고를 %d개로 바꿨습니다."
                        .formatted(productId, product.name(), product.stock()))
                .orElse("상품 %s 를 찾을 수 없습니다.".formatted(productId));
    }
```

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-mcp-server && ./gradlew test
```

Expected: PASS(26 + 7 = 33개).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/shop-mcp-server
git commit -m "feat(authz): updateStock tool과 tool별 scope 표

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: shop-mcp-server — scope 검사 filter, `403`, `401`의 scope, PRM `scopes_supported`

**Files:**
- Create: `.../mcpserver/config/ResourceMetadataUrl.java`, `.../mcpserver/config/ScopeChallengeEntryPoint.java`
- Create: `.../mcpserver/filter/CachedBodyHttpServletRequest.java`, `.../mcpserver/filter/ToolScopeFilter.java`
- Modify: `.../mcpserver/config/SecurityConfig.java`, `.../mcpserver/config/McpTransportConfig.java`
- Modify: `.../src/test/java/dev/starryeye/authz/mcpserver/McpAuthorizationStandardTest.java`
- Test: `.../src/test/java/dev/starryeye/authz/mcpserver/filter/ToolScopeFilterTest.java`(create), `.../src/test/java/dev/starryeye/authz/mcpserver/McpScopeTest.java`(create)

**Interfaces:**
- Consumes: Task 3의 `ToolScopeRegistry`.
- Produces: `ResourceMetadataUrl.of(HttpServletRequest): String`; `ScopeChallengeEntryPoint(AuthenticationEntryPoint delegate, String scope)`; `ToolScopeFilter(ToolScopeRegistry, JsonMapper, Function<HttpServletRequest, String>)`; 응답 규칙 — `401`: `Bearer resource_metadata="…", scope="products:read"`; `403`: `Bearer error="insufficient_scope", scope="<필요한 scope>", resource_metadata="…"`, 본문 없음; PRM `scopes_supported: ["products:read"]`. filter 순서: `Origin`·`Host` → Spring Security(token) → `ToolScopeFilter` → `McpProtocolVersionFilter` → transport.

- [ ] **Step 1: filter 단위 테스트를 쓴다**

`ToolScopeFilterTest`:

```java
package dev.starryeye.authz.mcpserver.filter;

import dev.starryeye.authz.mcpserver.repository.ProductRepository;
import dev.starryeye.authz.mcpserver.tool.ProductTools;
import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeFilterTest {

	static final String METADATA = "http://localhost:8141/.well-known/oauth-protected-resource/mcp";

	ToolScopeFilter filter = new ToolScopeFilter(ToolScopeRegistry.scan(new ProductTools(new ProductRepository())),
			JsonMapper.builder().build(), request -> METADATA);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	static void 로그인(String... scopes) {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user").claim("scope", scopes).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
				Arrays.stream(scopes).map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
	}

	static MockHttpServletRequest post(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	static String call(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{}}}";
	}

	@Test
	void 조회_scope로_조회_tool을_부르면_본문을_그대로_넘긴다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post(call("getStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		// transport가 본문을 다시 읽을 수 있어야 한다.
		assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
				.isEqualTo(call("getStock"));
	}

	@Test
	void 조회_scope로_재고_변경_tool을_부르면_403과_필요한_scope를_알린다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post(call("updateStock")), response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(
				"Bearer error=\"insufficient_scope\", scope=\"products:write\", resource_metadata=\"" + METADATA + "\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void 두_scope가_있으면_재고_변경_tool도_넘긴다() throws Exception {
		로그인("products:read", "products:write");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post(call("updateStock")), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void write만_있는_token도_기본_scope가_없으면_403이다() throws Exception {
		로그인("products:write");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.filter.doFilter(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), response,
				new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).contains("scope=\"products:read\"");
	}

	@Test
	void 형식이_틀린_본문도_기본_scope만_보고_transport로_넘긴다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post("{not json"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void 이름_없는_tools_call은_기본_scope만_본다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{}}"),
				new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void GET은_본문_없이_기본_scope만_본다() throws Exception {
		로그인("products:read");
		MockFilterChain chain = new MockFilterChain();

		this.filter.doFilter(new MockHttpServletRequest("GET", "/mcp"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
	}
}
```

- [ ] **Step 2: 통합 테스트를 쓰고 기존 테스트를 맞춘다**

`McpAuthorizationStandardTest`에서:
1. `토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key)`의 claims builder에 `.claim("scope", List.of("products:read"))`를 더한다(기본 token은 조회 scope를 가진다).
2. 도우미 `static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key, List<String> scopes)`를 더하고, 기존 5인자 메서드는 `List.of("products:read")`로 이 메서드를 부르게 바꾼다.
3. `토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다`의 기대 header를 다음으로 바꾼다.

```java
						"Bearer resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\", scope=\"products:read\""));
```

4. `보호_리소스_메타데이터를_경로형으로_공개한다`에 `.andExpect(jsonPath("$.scopes_supported").value(org.hamcrest.Matchers.contains("products:read")))`를 더한다.

`McpScopeTest`(같은 test package `dev.starryeye.authz.mcpserver`, `McpAuthorizationStandardTest`의 `StubAuthorizationServer`와 도우미를 그대로 쓴다):

```java
package dev.starryeye.authz.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** scope 검사가 Spring Security와 transport 사이에서 실제 요청에 적용되는지 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class McpScopeTest {

	@Autowired
	MockMvc mockMvc;

	static String 토큰(String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, "user", Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	static MockHttpServletRequestBuilder mcp(String token, String body) {
		return post("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", "application/json, text/event-stream")
				.header("Host", McpAuthorizationStandardTest.HOST)
				.header("MCP-Protocol-Version", "2025-11-25")
				.header("Authorization", "Bearer " + token)
				.content(body);
	}

	static final String UPDATE_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"updateStock",\
			"arguments":{"productId":"p1","quantity":10}}}""";

	@Test
	void 조회_scope_token으로_재고를_바꾸려_하면_403과_필요한_scope를_받는다() throws Exception {
		this.mockMvc.perform(mcp(토큰("products:read"), UPDATE_STOCK))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"products:write\", "
								+ "resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void scope_없는_token은_initialize도_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate", containsString("scope=\"products:read\"")));
	}

	@Test
	void 조회_scope_token의_initialize는_filter를_지나_transport가_답한다() throws Exception {
		this.mockMvc.perform(mcp(토큰("products:read"), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andExpect(header().exists("Mcp-Session-Id"));
	}

	@Test
	void filter가_읽은_본문을_transport가_다시_읽는다() throws Exception {
		// session 없이 보낸 tools/call이다. transport가 본문을 읽고 나서야 session을 확인하므로,
		// "Session ID missing"은 본문이 transport까지 온전히 전달됐다는 뜻이다.
		this.mockMvc.perform(mcp(토큰("products:read", "products:write"), UPDATE_STOCK))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Session ID missing")));
	}
}
```

`McpAuthorizationStandardTest`의 `StubAuthorizationServer`, `KEY`, `ISSUER`, `RESOURCE`, `HOST`, `INITIALIZE`, 6인자 `토큰`은 package-private `static`이라 같은 package의 `McpScopeTest`에서 쓸 수 있다.

- [ ] **Step 3: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-mcp-server && ./gradlew test
```

Expected: 컴파일 실패(`ToolScopeFilter` 없음).

- [ ] **Step 4: 구현한다**

`config/ResourceMetadataUrl.java` — `SecurityConfig`의 `resourceMetadataUrl` private 메서드와 상수를 옮긴다.

```java
package dev.starryeye.authz.mcpserver.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * RFC 9728 §3.1의 규칙대로, protected resource URL의 경로 앞에
 * {@code /.well-known/oauth-protected-resource}를 끼워 넣은 URL을 만든다.
 * 예를 들어 {@code /mcp} 요청은 {@code /.well-known/oauth-protected-resource/mcp}를 가리킨다.
 * {@code 401}과 {@code 403}의 {@code resource_metadata}가 같은 값을 쓴다.
 */
public final class ResourceMetadataUrl {

	private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

	private ResourceMetadataUrl() {
	}

	public static String of(HttpServletRequest request) {
		String path = request.getRequestURI();
		return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
				.replacePath(PROTECTED_RESOURCE_METADATA + ("/".equals(path) ? "" : path))
				.replaceQuery(null)
				.build()
				.toUriString();
	}
}
```

`config/ScopeChallengeEntryPoint.java`:

```java
package dev.starryeye.authz.mcpserver.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * {@code 401} challenge에 처음 요청할 scope를 더한다(MCP 2025-11-25 Authorization — Scope Selection Strategy).
 *
 * <p>client는 {@code 401}의 {@code scope}를 가장 먼저 보고 그만큼만 요청한다.
 * 그래서 여기에는 가장 위험이 낮은 조회 scope 하나만 적는다.
 * 쓰기 scope는 그 작업을 처음 시도할 때 {@code 403}으로 알린다(Security Best Practices — Scope Minimization).
 *
 * <p>Spring의 {@code BearerTokenAuthenticationEntryPoint}는 token이 없는 요청의 challenge에 {@code scope}를 넣지 않는다.
 * 그래서 그 결과 header 끝에 {@code scope}를 붙인다.
 */
public class ScopeChallengeEntryPoint implements AuthenticationEntryPoint {

	private final AuthenticationEntryPoint delegate;

	private final String scope;

	public ScopeChallengeEntryPoint(AuthenticationEntryPoint delegate, String scope) {
		this.delegate = delegate;
		this.scope = scope;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		this.delegate.commence(request, response, authException);
		String challenge = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
		if (challenge != null && !challenge.contains("scope=\"")) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge + ", scope=\"" + this.scope + "\"");
		}
	}
}
```

`filter/CachedBodyHttpServletRequest.java`:

```java
package dev.starryeye.authz.mcpserver.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 요청 본문을 한 번 읽어 두고, 뒤에서 몇 번이든 처음부터 다시 읽게 한다.
 *
 * <p>scope filter는 JSON-RPC 본문에서 tool 이름을 읽어야 한다.
 * 그런데 servlet 요청의 본문은 한 번만 읽을 수 있어서, 그대로 읽으면 transport가 빈 본문을 받는다.
 * Spring의 {@code ContentCachingRequestWrapper}는 읽힌 뒤에야 내용을 모으므로 이 용도에 맞지 않는다.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
		super(request);
		this.body = request.getInputStream().readAllBytes();
	}

	public byte[] body() {
		return this.body;
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream in = new ByteArrayInputStream(this.body);
		return new ServletInputStream() {

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("비동기 읽기는 쓰지 않는다");
			}

			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				return in.read(buffer, offset, length);
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		String encoding = getCharacterEncoding();
		Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

	@Override
	public int getContentLength() {
		return this.body.length;
	}

	@Override
	public long getContentLengthLong() {
		return this.body.length;
	}
}
```

`filter/ToolScopeFilter.java`:

```java
package dev.starryeye.authz.mcpserver.filter;

import dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * token의 scope가 이 요청에 충분한지 본다(안내서 10장).
 *
 * <p>모든 MCP 요청에는 기본 scope({@link ToolScopeRegistry#BASE_SCOPE})가 있어야 한다.
 * {@code tools/call}은 그 tool에 필요한 scope도 있어야 한다.
 * 모자라면 {@code 403}과 {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="…"}로 답한다
 * (RFC 6750 §3.1, MCP 2025-11-25 Authorization — Scope Challenge Handling).
 * challenge에는 이 요청에 필요한 scope만 적는다. scope 목록 전체를 알리지 않는다.
 *
 * <p>tool 이름은 JSON-RPC 본문의 {@code params.name}에 있어 본문을 읽어야 한다.
 * MCP 2026-07-28은 이 이름을 {@code Mcp-Name} header로도 보내게 해서, 서버는 본문 없이 같은 검사를 할 수 있다.
 * 지금 SDK는 2025-11-25라 본문을 읽는다.
 *
 * <p>검사를 tool 메서드 안(예: {@code @PreAuthorize})에서 하면 거절이 HTTP {@code 200}의 tool 오류 결과가 된다.
 * client가 step-up을 시작하려면 HTTP {@code 403}과 challenge가 필요하므로 transport 앞에서 검사한다.
 */
public class ToolScopeFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ToolScopeFilter.class);

	private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

	private final ToolScopeRegistry registry;

	private final JsonMapper jsonMapper;

	private final Function<HttpServletRequest, String> resourceMetadataUrl;

	public ToolScopeFilter(ToolScopeRegistry registry, JsonMapper jsonMapper,
			Function<HttpServletRequest, String> resourceMetadataUrl) {
		this.registry = registry;
		this.jsonMapper = jsonMapper;
		this.resourceMetadataUrl = resourceMetadataUrl;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
			// token이 없거나 틀린 요청은 Spring Security가 이미 401로 끝냈다.
			chain.doFilter(request, response);
			return;
		}
		Set<String> granted = grantedScopes(token);
		HttpServletRequest forwarded = request;
		String tool = null;
		if (HttpMethod.POST.matches(request.getMethod())) {
			CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
			forwarded = cached;
			tool = toolName(cached.body());
		}
		String missing = missingScope(granted, tool);
		if (missing != null) {
			insufficientScope(request, response, token, tool, missing, granted);
			return;
		}
		chain.doFilter(forwarded, response);
	}

	private String missingScope(Set<String> granted, String tool) {
		if (!granted.contains(ToolScopeRegistry.BASE_SCOPE)) {
			return ToolScopeRegistry.BASE_SCOPE;
		}
		if (tool != null && !granted.contains(this.registry.scopeFor(tool))) {
			return this.registry.scopeFor(tool);
		}
		return null;
	}

	/** Spring Security는 token의 {@code scope}를 {@code SCOPE_} authority로 바꿔 둔다. */
	private static Set<String> grantedScopes(JwtAuthenticationToken token) {
		return token.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.filter(authority -> authority.startsWith(SCOPE_AUTHORITY_PREFIX))
				.map(authority -> authority.substring(SCOPE_AUTHORITY_PREFIX.length()))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/** {@code tools/call}이면 tool 이름을, 아니면 {@code null}을 돌려준다. */
	private String toolName(byte[] body) {
		try {
			JsonNode message = this.jsonMapper.readTree(body);
			if (message == null || !message.isObject()) {
				return null;
			}
			JsonNode method = message.get("method");
			if (method == null || !method.isString() || !"tools/call".equals(method.stringValue())) {
				return null;
			}
			JsonNode params = message.get("params");
			JsonNode name = (params == null) ? null : params.get("name");
			return (name != null && name.isString() && !name.stringValue().isBlank()) ? name.stringValue() : null;
		}
		catch (JacksonException ex) {
			// JSON이 아닌 본문은 transport가 JSON-RPC 오류로 답한다. 여기서는 기본 scope만 본다.
			return null;
		}
	}

	private void insufficientScope(HttpServletRequest request, HttpServletResponse response,
			JwtAuthenticationToken token, String tool, String missing, Set<String> granted) {
		// Security Best Practices — Scope Minimization: 권한 상승 요청을 기록으로 남긴다.
		log.info("scope 부족 — 사용자={}, client_id={}, tool={}, 필요한 scope={}, 가진 scope={}",
				token.getName(), token.getToken().getClaimAsString("client_id"), tool, missing, granted);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + missing
				+ "\", resource_metadata=\"" + this.resourceMetadataUrl.apply(request) + "\"");
	}
}
```

`SecurityConfig`:
1. `PROTECTED_RESOURCE_METADATA` 상수와 `resourceMetadataUrl` 메서드를 지우고, `resourceMetadataEntryPoint()`에서 `entryPoint.setResourceMetadataParameterResolver(ResourceMetadataUrl::of);`로 바꾼다.
2. `.authenticationEntryPoint(resourceMetadataEntryPoint())`를 `.authenticationEntryPoint(new ScopeChallengeEntryPoint(resourceMetadataEntryPoint(), ToolScopeRegistry.BASE_SCOPE))`로 바꾼다.
3. PRM customizer에 한 줄을 더한다.

```java
										// 처음 요청할 조회 scope만 알린다. 쓰기 scope는 필요할 때 403으로 알린다
										// (Security Best Practices — Scope Minimization: scopes_supported에 모든 scope를 싣지 않는다).
										.scope(ToolScopeRegistry.BASE_SCOPE)
```

4. 클래스 javadoc의 목록에 `<li>{@code 401}과 PRM에 처음 요청할 scope({@code products:read}) — client의 scope 선택 순서가 이 값을 쓴다</li>`를 더한다.

`McpTransportConfig`: javadoc 목록에 `ToolScopeFilter`를 더하고, bean 두 개를 더하며, `mcpProtocolVersionFilter` 등록에 순서를 준다.

```java
	@Bean
	public ToolScopeRegistry toolScopeRegistry(ProductTools productTools) {
		return ToolScopeRegistry.scan(productTools);
	}

	/**
	 * {@link ToolScopeFilter}를 MCP endpoint에만 적용한다.
	 * token 검증(Spring Security) 바로 뒤, 버전 검사 앞에서 돈다.
	 * 인증된 사용자의 scope를 보고, 모자라면 transport에 닿기 전에 {@code 403}으로 끝낸다.
	 */
	@Bean
	public FilterRegistrationBean<ToolScopeFilter> toolScopeFilter(ToolScopeRegistry registry,
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		FilterRegistrationBean<ToolScopeFilter> registration =
				new FilterRegistrationBean<>(new ToolScopeFilter(registry, jsonMapper, ResourceMetadataUrl::of));
		registration.addUrlPatterns(properties.getMcpEndpoint());
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
		return registration;
	}
```

`mcpProtocolVersionFilter`의 `return registration;` 앞에 `registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);`를 넣고 주석 "scope 검사 뒤에 돈다"를 단다. import: `dev.starryeye.authz.mcpserver.filter.ToolScopeFilter`, `dev.starryeye.authz.mcpserver.tool.ProductTools`, `dev.starryeye.authz.mcpserver.tool.ToolScopeRegistry`.

- [ ] **Step 5: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-mcp-server && ./gradlew test
```

Expected: PASS(33 + 7 + 4 = 44개). `McpScopeTest#filter가_읽은_본문을_transport가_다시_읽는다`가 다른 오류 문구로 실패하면, transport가 본문을 받지 못한 것이다. `CachedBodyHttpServletRequest`가 `getInputStream`·`getReader` 둘 다 새 stream을 주는지 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add practice/mcp-security-authz/shop-mcp-server
git commit -m "feat(authz): MCP Server의 tool별 scope 검사, 403 insufficient_scope, 401과 PRM의 최소 scope

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: shop-agent — scope 고르기와 login scope

**Files:**
- Modify: `.../agent/discovery/McpAuthorizationDiscovery.java`, `.../agent/discovery/DiscoveredAuthorization.java`, `.../agent/discovery/DiscoveredClientRegistrationRepository.java`
- Modify: `practice/mcp-security-authz/shop-agent/src/main/resources/application.yml`
- Test: `.../discovery/McpAuthorizationDiscoveryTest.java`, `.../discovery/DiscoveryFixtures.java`, `.../discovery/DiscoveredClientRegistrationRepositoryTest.java`, `.../config/TokenRefreshTest.java`

**Interfaces:**
- Produces: `DiscoveredAuthorization(String resource, String issuer, Map<String, Object> authorizationServerMetadata, List<String> scopes)`; `McpAuthorizationDiscovery.selectScopes(String challengeScope, Object scopesSupported): List<String>`(package-private static); login registration scope = `openid` + 고른 scope.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`McpAuthorizationDiscoveryTest`에 더한다(`PROTECTED_RESOURCE_METADATA`의 `resource`·issuer 값은 Task 1에서 8141·9030으로 바뀌어 있다).

```java
	static final String PRM_WITH_SCOPES = """
			{"resource":"http://localhost:8141/mcp","authorization_servers":["http://localhost:9030"],\
			"scopes_supported":["products:read","products:write"]}""";

	@Test
	void 챌린지의_scope를_먼저_고른다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\", "
				+ "scope=\"products:read\"");
		응답("http://localhost:8141/.well-known/oauth-protected-resource/mcp", PRM_WITH_SCOPES);
		응답("http://localhost:9030/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, ISSUER).scopes()).containsExactly("products:read");
	}

	@Test
	void 챌린지에_scope가_없으면_scopes_supported를_모두_고른다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8141/.well-known/oauth-protected-resource/mcp", PRM_WITH_SCOPES);
		응답("http://localhost:9030/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, ISSUER).scopes())
				.containsExactly("products:read", "products:write");
	}

	@Test
	void 둘_다_없으면_scope를_고르지_않는다() {
		챌린지("Bearer resource_metadata=\"http://localhost:8141/.well-known/oauth-protected-resource/mcp\"");
		응답("http://localhost:8141/.well-known/oauth-protected-resource/mcp", PROTECTED_RESOURCE_METADATA);
		응답("http://localhost:9030/.well-known/oauth-authorization-server", AUTHORIZATION_SERVER_METADATA);

		assertThat(this.discovery.discover(RESOURCE, ISSUER).scopes()).isEmpty();
	}

	@Test
	void 챌린지의_여러_scope는_공백으로_나눈다() {
		assertThat(McpAuthorizationDiscovery.selectScopes("products:read  products:write", null))
				.containsExactly("products:read", "products:write");
	}
```

`DiscoveryFixtures.discovered(String issuer)`의 `new DiscoveredAuthorization(RESOURCE, issuer, Map.of(...))`에 네 번째 인자 `, List.of("products:read")`를 더한다.

`DiscoveredClientRegistrationRepositoryTest`: `registration.setScope(...)` 줄을 지우고, `assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile");`를 `containsExactlyInAnyOrder("openid", "products:read")`로 바꾼다.

`TokenRefreshTest#만료된_토큰을_resource_를_실어_갱신한다` 아래에 테스트를 더한다(같은 준비 코드를 쓰되, 만료된 token에 scope를 주고 refresh 응답에는 scope를 넣지 않는다). import `org.hamcrest.Matchers.containsString`, `org.hamcrest.Matchers.not`, `java.util.Set`.

```java
    @Test
    void refresh_요청에_scope를_보내지_않아_늘어난_scope가_유지된다() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("authserver")
                .clientId("authz-shop-agent")
                .clientSecret("authz-shop-agent-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri(ISSUER + "/oauth2/authorize")
                .tokenUri(ISSUER + "/oauth2/token")
                .build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        var authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var principal = new TestingAuthenticationToken("user", null, "ROLE_USER");
        // step-up으로 늘어난 scope를 가진 token이 만료됐다.
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300),
                Set.of("openid", "products:read", "products:write"));
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user", expired,
                new OAuth2RefreshToken("refresh-1", Instant.now().minusSeconds(600))), principal);

        RestClient.Builder builder = RestClient.builder()
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscoveredClientRegistrationRepository discovered = mock(DiscoveredClientRegistrationRepository.class);
        given(discovered.discovered()).willReturn(DiscoveryFixtures.discovered());
        var refreshTokenClient = new McpSecurityConfig().refreshTokenTokenResponseClient(discovered);
        refreshTokenClient.setRestClient(builder.build());

        // refresh 요청에 scope가 없으면 Authorization Server는 처음 허락한 scope 그대로 발급한다(RFC 6749 §6).
        server.expect(requestTo(ISSUER + "/oauth2/token"))
                .andExpect(content().string(not(containsString("scope="))))
                .andRespond(withSuccess("""
                        {"access_token":"new-token","token_type":"Bearer","expires_in":300}""",
                        MediaType.APPLICATION_JSON));

        var manager = McpSecurityConfig.authorizedClientManager(registrations, authorizedClients, refreshTokenClient);
        OAuth2AuthorizedClient authorized = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("authserver").principal(principal).build());

        // 응답에 scope가 없으면 Spring은 이전 token의 scope를 그대로 둔다.
        assertThat(authorized.getAccessToken().getScopes())
                .containsExactlyInAnyOrder("openid", "products:read", "products:write");
        server.verify();
    }
```

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test --tests '*.discovery.*' --tests '*.config.TokenRefreshTest'
```

Expected: 컴파일 실패(`scopes()`, 4인자 생성자, `selectScopes` 없음). refresh 테스트는 이 단계에서 이미 통과할 수 있다(Spring 동작의 고정용이다).

- [ ] **Step 3: 구현한다**

`DiscoveredAuthorization`: javadoc에 `@param scopes 처음 요청할 scope. discovery가 MCP Scope Selection Strategy로 고른다`를 더하고, record에 네 번째 component `List<String> scopes`를, compact constructor에 `scopes = List.copyOf(scopes);`를 더한다(import `java.util.List`).

`McpAuthorizationDiscovery`:

```java
	private static final Pattern SCOPE = Pattern.compile("(?<![A-Za-z_])scope=\"([^\"]*)\"");

	/** 401 challenge에서 읽은 값이다. 둘 다 없을 수 있다. */
	private record Challenge(String resourceMetadata, String scope) {
	}
```

`discover`를 다음으로 바꾼다.

```java
	public DiscoveredAuthorization discover(String resourceUrl, String trustedIssuer) {
		Challenge challenge = challenge(resourceUrl);
		Map<String, Object> protectedResource = protectedResourceMetadata(resourceUrl, challenge.resourceMetadata());

		if (!(protectedResource.get("authorization_servers") instanceof List<?> servers) || servers.isEmpty()) {
			throw new McpDiscoveryException("보호 리소스 메타데이터에 authorization_servers 가 없다: " + resourceUrl);
		}
		String issuer = String.valueOf(servers.get(0));
		if (!trustedIssuer.equals(issuer)) {
			throw new McpDiscoveryException(
					"자격증명은 %s 에 등록된 것인데 PRM 이 가리키는 인가 서버는 %s 다 — 메타데이터를 요청하지 않는다"
							.formatted(trustedIssuer, issuer));
		}

		return new DiscoveredAuthorization((String) protectedResource.get("resource"), issuer,
				authorizationServerMetadata(issuer),
				selectScopes(challenge.scope(), protectedResource.get("scopes_supported")));
	}

	/**
	 * MCP 2025-11-25 Authorization — Scope Selection Strategy.
	 * {@code 401}의 {@code scope}가 있으면 그 값, 없으면 PRM의 {@code scopes_supported} 전부,
	 * 둘 다 없으면 scope를 요청하지 않는다.
	 * 범용 client는 서버마다 어떤 scope가 필요한지 모르므로, 서버가 알려 준 값을 쓴다.
	 */
	static List<String> selectScopes(String challengeScope, Object scopesSupported) {
		if (challengeScope != null && !challengeScope.isBlank()) {
			return List.of(challengeScope.trim().split("\\s+"));
		}
		if (scopesSupported instanceof List<?> supported && !supported.isEmpty()) {
			return supported.stream().map(String::valueOf).toList();
		}
		return List.of();
	}
```

`protectedResourceMetadata(String resourceUrl)`를 `protectedResourceMetadata(String resourceUrl, String fromChallenge)`로 바꾸고 첫 줄 `String fromChallenge = resourceMetadataUrlFromChallenge(resourceUrl);`를 지운다. `resourceMetadataUrlFromChallenge`를 다음 `challenge`로 바꾼다.

```java
	private Challenge challenge(String resourceUrl) {
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
						return new Challenge(null, null);
					}
					Matcher metadata = RESOURCE_METADATA.matcher(header);
					Matcher scope = SCOPE.matcher(header);
					return new Challenge(metadata.find() ? metadata.group(1) : null, scope.find() ? scope.group(1) : null);
				});
	}
```

클래스 javadoc 목록의 2번 뒤에 `<li>{@code 401}의 {@code scope}와 PRM의 {@code scopes_supported}로 처음 요청할 scope를 고른다</li>`를 더한다.

`DiscoveredClientRegistrationRepository#registration`: `.scope(this.credentials.getScope())`를 `.scope(requestedScopes(authorization))`로 바꾸고 메서드를 더한다(import `java.util.LinkedHashSet`, `java.util.Set`, `org.springframework.security.oauth2.core.oidc.OidcScopes`).

```java
	/** login에는 {@code openid}가 필요하고, MCP 호출에 쓸 scope는 discovery가 고른다. */
	private static Set<String> requestedScopes(DiscoveredAuthorization authorization) {
		Set<String> scopes = new LinkedHashSet<>();
		scopes.add(OidcScopes.OPENID);
		scopes.addAll(authorization.scopes());
		return scopes;
	}
```

`application.yml`의 `registration.authserver`에서 `scope:` 목록(`openid`, `profile`)을 지우고 그 자리에 주석을 둔다.

```yaml
            # scope는 적지 않는다. openid에 discovery가 고른 scope(401의 scope → PRM의 scopes_supported)를 더해 요청한다.
```

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test
```

Expected: PASS(38 + 5 = 43개).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/shop-agent
git commit -m "feat(authz): agent가 401과 PRM으로 처음 요청할 scope를 고른다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: shop-agent — MCP `403`을 step-up 예외로 올린다

**Files:**
- Create: `.../agent/security/BearerChallenge.java`, `.../agent/security/StepUpRequiredException.java`
- Create: `.../agent/mcp/StepUpAuthorizationErrorHandler.java`, `.../agent/mcp/StepUpToolExecutionExceptionProcessor.java`
- Modify: `.../agent/config/McpSecurityConfig.java`
- Test: `.../security/BearerChallengeTest.java`, `.../mcp/StepUpAuthorizationErrorHandlerTest.java`, `.../mcp/StepUpToolExecutionExceptionProcessorTest.java`, `.../mcp/StepUpTransportTest.java`(모두 create)

**Interfaces:**
- Produces:
  - `record BearerChallenge(String error, List<String> scopes)`, `static Optional<BearerChallenge> parse(String header)`, `boolean insufficientScope()`.
  - `StepUpRequiredException(List<String> scopes, String tool)`, `List<String> scopes()`, `String tool()`, `StepUpRequiredException withTool(String tool)`, `static Optional<StepUpRequiredException> find(Throwable error)`.
  - `StepUpAuthorizationErrorHandler implements McpHttpClientTransportAuthorizationErrorHandler`: `403` + `insufficient_scope`면 `Mono.error(StepUpRequiredException)`, 그 밖에는 `Mono.just(false)`.
  - `StepUpToolExecutionExceptionProcessor(ToolExecutionExceptionProcessor delegate)`: 원인 사슬에 `StepUpRequiredException`이 있으면 tool 이름을 더해 다시 던진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BearerChallengeTest`:

```java
package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BearerChallengeTest {

	@Test
	void insufficient_scope와_scope를_읽는다() {
		BearerChallenge challenge = BearerChallenge.parse("Bearer error=\"insufficient_scope\", "
				+ "scope=\"products:write\", resource_metadata=\"http://localhost:8141/.well-known/x\"").orElseThrow();

		assertThat(challenge.error()).isEqualTo("insufficient_scope");
		assertThat(challenge.scopes()).containsExactly("products:write");
		assertThat(challenge.insufficientScope()).isTrue();
	}

	@Test
	void 여러_scope는_공백으로_나눈다() {
		assertThat(BearerChallenge.parse("Bearer error=\"insufficient_scope\", scope=\"a b\"").orElseThrow().scopes())
				.containsExactly("a", "b");
	}

	@Test
	void Bearer가_아니면_읽지_않는다() {
		assertThat(BearerChallenge.parse("Basic realm=\"x\"")).isEmpty();
		assertThat(BearerChallenge.parse(null)).isEmpty();
	}

	@Test
	void scope가_없는_insufficient_scope는_step_up_대상이_아니다() {
		assertThat(BearerChallenge.parse("Bearer error=\"insufficient_scope\"").orElseThrow().insufficientScope())
				.isFalse();
	}
}
```

`StepUpAuthorizationErrorHandlerTest`:

```java
package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpAuthorizationErrorHandlerTest {

	record Info(int statusCode, HttpHeaders headers, HttpClient.Version version) implements HttpResponse.ResponseInfo {
	}

	static HttpResponse.ResponseInfo info(int status, String challenge) {
		Map<String, List<String>> headers = (challenge == null) ? Map.of() : Map.of("WWW-Authenticate", List.of(challenge));
		return new Info(status, HttpHeaders.of(headers, (name, value) -> true), HttpClient.Version.HTTP_1_1);
	}

	StepUpAuthorizationErrorHandler handler = new StepUpAuthorizationErrorHandler();

	Boolean handle(HttpResponse.ResponseInfo info) {
		return Mono.from(this.handler.handle(null, info, McpTransportContext.EMPTY)).block();
	}

	@Test
	void insufficient_scope_403은_step_up_예외로_올린다() {
		assertThatThrownBy(() -> handle(info(403, "Bearer error=\"insufficient_scope\", scope=\"products:write\"")))
				.isInstanceOf(StepUpRequiredException.class)
				.satisfies(error -> assertThat(((StepUpRequiredException) error).scopes()).containsExactly("products:write"));
	}

	@Test
	void insufficient_scope가_아닌_403은_step_up을_시작하지_않는다() {
		assertThat(handle(info(403, null))).isFalse();
		assertThat(handle(info(403, "Bearer error=\"invalid_token\""))).isFalse();
	}

	@Test
	void 401은_다시_보내지_않는다() {
		assertThat(handle(info(401, "Bearer resource_metadata=\"x\""))).isFalse();
	}
}
```

`StepUpToolExecutionExceptionProcessorTest`:

```java
package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.StepUpRequiredException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpToolExecutionExceptionProcessorTest {

	StepUpToolExecutionExceptionProcessor processor = new StepUpToolExecutionExceptionProcessor(
			DefaultToolExecutionExceptionProcessor.builder().build());

	static ToolExecutionException failed(Throwable cause) {
		return new ToolExecutionException(DefaultToolDefinition.builder().name("updateStock").description("d")
				.inputSchema("{}").build(), cause);
	}

	@Test
	void 감싸인_step_up_예외를_tool_이름과_함께_다시_던진다() {
		RuntimeException wrapped = new RuntimeException("SDK가 감쌈",
				new StepUpRequiredException(List.of("products:write"), null));

		assertThatThrownBy(() -> this.processor.process(failed(wrapped)))
				.isInstanceOf(StepUpRequiredException.class)
				.satisfies(error -> {
					StepUpRequiredException stepUp = (StepUpRequiredException) error;
					assertThat(stepUp.scopes()).containsExactly("products:write");
					assertThat(stepUp.tool()).isEqualTo("updateStock");
				});
	}

	@Test
	void 다른_예외는_기본_처리대로_LLM에게_줄_문장이_된다() {
		assertThat(this.processor.process(failed(new IllegalStateException("재고 서버 오류")))).contains("재고 서버 오류");
	}
}
```

`StepUpTransportTest` — SDK가 handler의 예외를 호출한 쪽까지 올리는지 확인한다. 이 설계의 전제다.

```java
package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.StepUpRequiredException;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StepUpTransportTest {

	HttpServer server;

	@BeforeEach
	void start() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		this.server.createContext("/mcp", exchange -> {
			exchange.getResponseHeaders().add("WWW-Authenticate",
					"Bearer error=\"insufficient_scope\", scope=\"products:write\", resource_metadata=\"http://x\"");
			exchange.sendResponseHeaders(403, -1);
			exchange.close();
		});
		this.server.start();
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	@Test
	void SDK는_handler가_던진_step_up_예외를_호출한_쪽까지_올린다() {
		var transport = HttpClientStreamableHttpTransport
				.builder("http://127.0.0.1:" + this.server.getAddress().getPort())
				.endpoint("/mcp")
				.authorizationErrorHandler(new StepUpAuthorizationErrorHandler())
				.build();
		McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();

		Throwable error = catchThrowable(client::initialize);

		assertThat(StepUpRequiredException.find(error))
				.hasValueSatisfying(stepUp -> assertThat(stepUp.scopes()).containsExactly("products:write"));
		client.close();
	}
}
```

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test --tests '*.security.BearerChallengeTest' --tests '*.mcp.StepUp*'
```

Expected: 컴파일 실패.

- [ ] **Step 3: 구현한다**

`security/BearerChallenge.java`:

```java
package dev.starryeye.authz.agent.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code WWW-Authenticate: Bearer …} challenge에서 {@code error}와 {@code scope}를 읽는다(RFC 6750 §3).
 *
 * @param error 예: {@code insufficient_scope}. 없으면 {@code null}
 * @param scopes {@code scope}를 공백으로 나눈 값. 없으면 빈 목록
 */
public record BearerChallenge(String error, List<String> scopes) {

	private static final Pattern PARAMETER = Pattern.compile("([A-Za-z_]+)=\"([^\"]*)\"");

	public BearerChallenge {
		scopes = List.copyOf(scopes);
	}

	public static Optional<BearerChallenge> parse(String header) {
		if (header == null || !header.regionMatches(true, 0, "Bearer", 0, 6)) {
			return Optional.empty();
		}
		Map<String, String> parameters = new HashMap<>();
		Matcher matcher = PARAMETER.matcher(header);
		while (matcher.find()) {
			parameters.putIfAbsent(matcher.group(1), matcher.group(2));
		}
		String scope = parameters.get("scope");
		List<String> scopes = (scope == null || scope.isBlank()) ? List.of() : List.of(scope.trim().split("\\s+"));
		return Optional.of(new BearerChallenge(parameters.get("error"), scopes));
	}

	/** step-up을 시작할 challenge인지 본다. 필요한 scope를 알려 주지 않으면 무엇을 요청할지 모른다. */
	public boolean insufficientScope() {
		return "insufficient_scope".equals(this.error) && !this.scopes.isEmpty();
	}
}
```

`security/StepUpRequiredException.java`:

```java
package dev.starryeye.authz.agent.security;

import java.util.List;
import java.util.Optional;

/**
 * MCP Server가 {@code 403 insufficient_scope}로 더 넓은 scope를 요구했다(안내서 10장).
 *
 * <p>agent는 이 예외를 LLM에게 tool 오류 문장으로 넘기지 않는다.
 * 채팅 응답까지 올려서 사용자에게 consent 카드를 보여 준다.
 */
public class StepUpRequiredException extends RuntimeException {

	private final List<String> scopes;

	private final String tool;

	public StepUpRequiredException(List<String> scopes, String tool) {
		super("추가 권한이 필요하다: " + String.join(" ", scopes) + ((tool == null) ? "" : " (tool=" + tool + ")"));
		this.scopes = List.copyOf(scopes);
		this.tool = tool;
	}

	public List<String> scopes() {
		return this.scopes;
	}

	/** 권한이 모자랐던 tool 이름이다. transport 단계에서는 알 수 없어 {@code null}일 수 있다. */
	public String tool() {
		return this.tool;
	}

	public StepUpRequiredException withTool(String tool) {
		return new StepUpRequiredException(this.scopes, tool);
	}

	/** 원인 사슬에서 이 예외를 찾는다. MCP SDK와 Spring AI가 예외를 감쌀 수 있어서다. */
	public static Optional<StepUpRequiredException> find(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof StepUpRequiredException stepUp) {
				return Optional.of(stepUp);
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return Optional.empty();
	}
}
```

`mcp/StepUpAuthorizationErrorHandler.java`:

```java
package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.BearerChallenge;
import dev.starryeye.authz.agent.security.StepUpRequiredException;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.net.http.HttpResponse;

/**
 * MCP 요청이 {@code 403 insufficient_scope}를 받으면 {@link StepUpRequiredException}으로 올린다.
 *
 * <p>SDK는 {@code 401}·{@code 403}을 받으면 이 handler를 부른다.
 * {@code true}를 돌려주면 같은 요청을 다시 보내고, 오류를 돌려주면 그 오류를 호출한 쪽에 그대로 전한다.
 * 웹 agent는 여기서 새 token을 받을 수 없다. 사용자의 browser가 consent 화면을 거쳐야 하기 때문이다.
 * 그래서 다시 보내지 않고, 예외로 채팅까지 올린다.
 */
public class StepUpAuthorizationErrorHandler implements McpHttpClientTransportAuthorizationErrorHandler {

	@Override
	public Publisher<Boolean> handle(HttpRequestSnapshot requestSnapshot, HttpResponse.ResponseInfo responseInfo,
			McpTransportContext context) {
		if (responseInfo.statusCode() != 403) {
			return Mono.just(false);
		}
		return responseInfo.headers().firstValue("WWW-Authenticate")
				.flatMap(BearerChallenge::parse)
				.filter(BearerChallenge::insufficientScope)
				.<Publisher<Boolean>>map(challenge -> Mono.error(new StepUpRequiredException(challenge.scopes(), null)))
				.orElseGet(() -> Mono.just(false));
	}
}
```

`mcp/StepUpToolExecutionExceptionProcessor.java`:

```java
package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.StepUpRequiredException;

import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.util.Optional;

/**
 * tool 호출이 step-up을 요구하면 LLM에게 오류 문장으로 넘기지 않고 다시 던진다.
 *
 * <p>Spring AI는 tool 예외를 문장으로 바꿔 LLM에게 돌려준다.
 * 그러면 LLM은 "권한이 없습니다" 같은 답을 지어낼 뿐 사용자는 권한을 줄 기회를 얻지 못한다.
 * 그래서 이 예외만 채팅 응답까지 올린다. 나머지는 {@code delegate}가 처리한다.
 */
public class StepUpToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

	private final ToolExecutionExceptionProcessor delegate;

	public StepUpToolExecutionExceptionProcessor(ToolExecutionExceptionProcessor delegate) {
		this.delegate = delegate;
	}

	@Override
	public String process(ToolExecutionException exception) {
		Optional<StepUpRequiredException> stepUp = StepUpRequiredException.find(exception);
		if (stepUp.isPresent()) {
			throw stepUp.get().withTool(exception.getToolDefinition().name());
		}
		return this.delegate.process(exception);
	}
}
```

`McpSecurityConfig`:
1. `mcpTokenAttachingCustomizer`를 바꾼다.

```java
    /** 모든 Streamable HTTP transport에 token을 붙이고, 403 insufficient_scope를 step-up 예외로 올린다. */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTokenAttachingCustomizer(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return (name, transport) -> transport
                .httpRequestCustomizer(new OAuth2TokenAttachingRequestCustomizer(authorizedClientManager, REGISTRATION_ID))
                .authorizationErrorHandler(new StepUpAuthorizationErrorHandler());
    }
```

2. bean을 더한다(import `org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor`, `org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor`, `org.springframework.security.oauth2.client.ClientAuthorizationException`, `dev.starryeye.authz.agent.mcp.StepUpAuthorizationErrorHandler`, `dev.starryeye.authz.agent.mcp.StepUpToolExecutionExceptionProcessor`, `java.util.List`).

```java
    /**
     * step-up 예외는 채팅까지 올리고, 나머지는 Spring AI 기본 처리와 같게 둔다.
     * 기본 처리는 Spring Security의 {@code ClientAuthorizationException}을 다시 던지고, 그 밖의 예외는 LLM에게 줄 문장으로 바꾼다.
     * 이 bean이 있으면 Spring AI 자동 구성의 같은 bean은 만들어지지 않는다.
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return new StepUpToolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
                .rethrowExceptions(List.of(ClientAuthorizationException.class))
                .build());
    }
```

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test
```

Expected: PASS(43 + 12 = 55개). `StepUpTransportTest`가 실패하면(SDK가 원인 없이 다른 예외로 바꾸는 경우) 멈추고 BLOCKED로 보고한다. step-up 설계 전체가 이 동작에 기대고 있다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/shop-agent
git commit -m "feat(authz): agent가 MCP 403 insufficient_scope를 step-up 예외로 채팅까지 올린다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: shop-agent — step-up authorization request와 login 뒤 처리

**Files:**
- Create: `.../agent/security/StepUpState.java`, `.../agent/security/StepUpAuthorizationRequestResolver.java`, `.../agent/security/StepUpLoginSuccessHandler.java`, `.../agent/controller/StepUpController.java`
- Modify: `.../agent/security/LoginFailureHandler.java`, `.../agent/config/SecurityConfig.java`
- Test: `.../security/StepUpStateTest.java`, `.../security/StepUpAuthorizationRequestResolverTest.java`, `.../security/StepUpLoginSuccessHandlerTest.java`, `.../security/LoginFailureHandlerTest.java`, `.../controller/StepUpControllerTest.java`(모두 create)

**Interfaces:**
- Consumes: Task 6의 `StepUpRequiredException`.
- Produces:
  - `StepUpState`(HTTP session attribute): `static StepUpState of(HttpSession)`, `static StepUpState existing(HttpSession)`(없으면 `null`), `void start(Collection<String> scopes)`, `List<String> finish()`, `boolean isPending()`, `boolean attempted(Collection<String> scopes)`, `void retry(Collection<String> scopes)`.
  - `StepUpAuthorizationRequestResolver.PARAMETER = "step_up"`. `/oauth2/authorization/authserver?step_up=products:write`는 scope = (원래 scope) ∪ (지금 token의 scope) ∪ (요청 scope)인 authorization request가 된다.
  - `GET /step-up/retry?scope=…` → 시도 기록을 지우고 위 주소로 `302`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`StepUpStateTest`:

```java
package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpStateTest {

	@Test
	void session마다_하나를_만들어_다시_쓴다() {
		MockHttpSession session = new MockHttpSession();

		assertThat(StepUpState.existing(session)).isNull();
		assertThat(StepUpState.of(session)).isSameAs(StepUpState.of(session));
	}

	@Test
	void 시작하면_기다리는_중이고_시도한_scope로_남는다() {
		StepUpState state = new StepUpState();

		state.start(List.of("products:write"));

		assertThat(state.isPending()).isTrue();
		assertThat(state.attempted(List.of("products:write"))).isTrue();
		assertThat(state.finish()).containsExactly("products:write");
		assertThat(state.isPending()).isFalse();
		// login이 끝나도 시도 기록은 남는다. 같은 scope로 step-up을 되풀이하지 않기 위해서다.
		assertThat(state.attempted(List.of("products:write"))).isTrue();
	}

	@Test
	void 다시_요청하면_시도_기록을_지운다() {
		StepUpState state = new StepUpState();
		state.start(List.of("products:write"));
		state.finish();

		state.retry(List.of("products:write"));

		assertThat(state.attempted(List.of("products:write"))).isFalse();
	}
}
```

`StepUpAuthorizationRequestResolverTest`:

```java
package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpAuthorizationRequestResolverTest {

	static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("authserver")
			.clientId("authz-shop-agent").clientSecret("s")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("http://localhost:8140/login/oauth2/code/authserver")
			.authorizationUri("http://localhost:9030/oauth2/authorize")
			.tokenUri("http://localhost:9030/oauth2/token")
			.scope("openid", "products:read")
			.build();

	static final OAuth2AuthorizationRequest ORIGINAL = OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("http://localhost:9030/oauth2/authorize")
			.clientId("authz-shop-agent")
			.redirectUri("http://localhost:8140/login/oauth2/code/authserver")
			.scopes(Set.of("openid", "products:read"))
			.state("state-1")
			.additionalParameters(Map.of("resource", "http://localhost:8141/mcp"))
			.build();

	/** 원래 resolver 자리에 고정된 요청을 돌려주는 stub을 둔다. */
	static final OAuth2AuthorizationRequestResolver DELEGATE = new OAuth2AuthorizationRequestResolver() {

		@Override
		public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
			return ORIGINAL;
		}

		@Override
		public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
			return ORIGINAL;
		}
	};

	InMemoryOAuth2AuthorizedClientService authorizedClients =
			new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(REGISTRATION));

	StepUpAuthorizationRequestResolver resolver =
			new StepUpAuthorizationRequestResolver(DELEGATE, this.authorizedClients, "authserver");

	MockHttpServletRequest 요청(String stepUp) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/authserver");
		request.setUserPrincipal(new TestingAuthenticationToken("user", null));
		if (stepUp != null) {
			request.setParameter(StepUpAuthorizationRequestResolver.PARAMETER, stepUp);
		}
		return request;
	}

	@Test
	void step_up이_없으면_원래_요청을_그대로_쓴다() {
		assertThat(this.resolver.resolve(요청(null))).isSameAs(ORIGINAL);
	}

	@Test
	void 지금_가진_scope와_요청한_scope를_합친다() {
		var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
				Instant.now().plusSeconds(300), Set.of("openid", "products:read"));
		this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(REGISTRATION, "user", token),
				new TestingAuthenticationToken("user", null));
		MockHttpServletRequest request = 요청("products:write");

		OAuth2AuthorizationRequest stepUp = this.resolver.resolve(request);

		assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
		assertThat(stepUp.getAdditionalParameters()).containsEntry("resource", "http://localhost:8141/mcp");
		assertThat(stepUp.getAuthorizationRequestUri()).contains("products:write");
		assertThat(StepUpState.of(request.getSession()).isPending()).isTrue();
	}

	@Test
	void login_기록이_없어도_원래_scope에_요청한_scope를_더한다() {
		OAuth2AuthorizationRequest stepUp = this.resolver.resolve(요청("products:write"), "authserver");

		assertThat(stepUp.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write");
	}
}
```

`StepUpLoginSuccessHandlerTest`:

```java
package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepUpLoginSuccessHandlerTest {

	InMemoryOAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(
			new InMemoryClientRegistrationRepository(StepUpAuthorizationRequestResolverTest.REGISTRATION));

	StepUpLoginSuccessHandler handler = new StepUpLoginSuccessHandler(this.authorizedClients, "authserver");

	void 새_token(String... scopes) {
		var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(),
				Instant.now().plusSeconds(300), Set.of(scopes));
		this.authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(
				StepUpAuthorizationRequestResolverTest.REGISTRATION, "user", token), new TestingAuthenticationToken("user", null));
	}

	@Test
	void step_up_login이_끝나면_기다림을_끝내고_채팅_화면으로_돌아간다() throws Exception {
		새_token("openid", "products:read", "products:write");
		MockHttpServletRequest request = new MockHttpServletRequest();
		StepUpState.of(request.getSession()).start(List.of("products:write"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));

		assertThat(StepUpState.of(request.getSession()).isPending()).isFalse();
		assertThat(response.getRedirectedUrl()).isEqualTo("/");
	}

	@Test
	void 허락받지_못한_scope가_있어도_채팅_화면으로_돌아간다() throws Exception {
		새_token("openid", "products:read");
		MockHttpServletRequest request = new MockHttpServletRequest();
		StepUpState.of(request.getSession()).start(List.of("products:write"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));

		// 시도 기록이 남아 있어서, 다음 403에는 카드 대신 거절 안내가 나간다.
		assertThat(StepUpState.of(request.getSession()).attempted(List.of("products:write"))).isTrue();
		assertThat(response.getRedirectedUrl()).isEqualTo("/");
	}
}
```

`LoginFailureHandlerTest`:

```java
package dev.starryeye.authz.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginFailureHandlerTest {

	LoginFailureHandler handler = new LoginFailureHandler();

	@Test
	void step_up_중에_거절되면_채팅_화면으로_돌아간다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		StepUpState.of(request.getSession()).start(List.of("products:write"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.handler.onAuthenticationFailure(request, response,
				new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

		assertThat(response.getRedirectedUrl()).isEqualTo("/");
		assertThat(StepUpState.of(request.getSession()).isPending()).isFalse();
	}

	@Test
	void 처음_login이_실패하면_이유를_401로_보여_준다() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
				new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).startsWith("로그인 실패:");
	}
}
```

`StepUpControllerTest`:

```java
package dev.starryeye.authz.agent.controller;

import dev.starryeye.authz.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StepUpControllerTest {

	MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StepUpController()).build();

	@Test
	void 다시_요청하면_시도_기록을_지우고_step_up_authorization으로_보낸다() throws Exception {
		MockHttpSession session = new MockHttpSession();
		StepUpState.of(session).start(List.of("products:write"));
		StepUpState.of(session).finish();

		this.mockMvc.perform(get("/step-up/retry").param("scope", "products:write").session(session))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/oauth2/authorization/authserver?step_up=products:write"));

		assertThat(StepUpState.of(session).attempted(List.of("products:write"))).isFalse();
	}
}
```

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test --tests '*.security.StepUp*' --tests '*.security.LoginFailureHandlerTest' --tests '*.controller.StepUpControllerTest'
```

Expected: 컴파일 실패.

- [ ] **Step 3: 구현한다**

`security/StepUpState.java`:

```java
package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 사용자의 step-up 진행 상태다. HTTP session에 둔다.
 *
 * <p>{@code pending}은 consent 화면에 가 있는 동안 기다리는 scope다. login이 끝나거나 실패하면 비운다.
 * {@code attempted}는 이 session에서 한 번 step-up을 거친 scope다.
 * 그 scope로 다시 {@code 403}이 오면 사용자가 허락하지 않은 것이므로, consent 카드를 다시 띄우지 않는다.
 * MCP Security Best Practices의 client 지침("거절된 scope로 권한 상승을 되풀이하지 않는다")을 따른다.
 */
public final class StepUpState implements Serializable {

	static final String ATTRIBUTE = StepUpState.class.getName();

	private final Set<String> attempted = ConcurrentHashMap.newKeySet();

	private volatile List<String> pending = List.of();

	public static StepUpState of(HttpSession session) {
		synchronized (session) {
			StepUpState existing = existing(session);
			if (existing != null) {
				return existing;
			}
			StepUpState created = new StepUpState();
			session.setAttribute(ATTRIBUTE, created);
			return created;
		}
	}

	public static StepUpState existing(HttpSession session) {
		return (session != null && session.getAttribute(ATTRIBUTE) instanceof StepUpState state) ? state : null;
	}

	public void start(Collection<String> scopes) {
		this.pending = List.copyOf(scopes);
		this.attempted.addAll(scopes);
	}

	/** 기다리던 scope를 돌려주고 비운다. */
	public List<String> finish() {
		List<String> finished = this.pending;
		this.pending = List.of();
		return finished;
	}

	public boolean isPending() {
		return !this.pending.isEmpty();
	}

	public boolean attempted(Collection<String> scopes) {
		return scopes.stream().anyMatch(this.attempted::contains);
	}

	/** 사용자가 명시적으로 다시 요청할 때 시도 기록을 지운다. */
	public void retry(Collection<String> scopes) {
		this.attempted.removeAll(scopes);
	}
}
```

`security/StepUpAuthorizationRequestResolver.java`:

```java
package dev.starryeye.authz.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code step_up} parameter가 있으면, 지금 가진 scope에 요청한 scope를 더해 authorization request를 만든다(안내서 10장).
 *
 * <p>MCP 2026-07-28은 step-up 때 client가 이전 scope와 새 scope를 합쳐 요청하게 한다.
 * 새 scope만 요청하면 새 token에서 조회 권한이 빠진다.
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	public static final String PARAMETER = "step_up";

	private static final Logger log = LoggerFactory.getLogger(StepUpAuthorizationRequestResolver.class);

	private final OAuth2AuthorizationRequestResolver delegate;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final String registrationId;

	public StepUpAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate,
			OAuth2AuthorizedClientService authorizedClients, String registrationId) {
		this.delegate = delegate;
		this.authorizedClients = authorizedClients;
		this.registrationId = registrationId;
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		return stepUp(request, this.delegate.resolve(request));
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return stepUp(request, this.delegate.resolve(request, clientRegistrationId));
	}

	private OAuth2AuthorizationRequest stepUp(HttpServletRequest request, OAuth2AuthorizationRequest original) {
		String requested = request.getParameter(PARAMETER);
		if (original == null || requested == null || requested.isBlank()) {
			return original;
		}
		List<String> added = List.of(requested.trim().split("\\s+"));
		Set<String> scopes = new LinkedHashSet<>(original.getScopes());
		scopes.addAll(grantedScopes(request));
		scopes.addAll(added);
		StepUpState.of(request.getSession()).start(added);
		log.info("step-up authorization request — 추가 scope={}, 요청 scope={}", added, scopes);
		// from(...)은 이미 만든 주소를 복사하지 않는다. build()가 바뀐 scope로 주소를 다시 만든다.
		return OAuth2AuthorizationRequest.from(original).scopes(scopes).build();
	}

	private Set<String> grantedScopes(HttpServletRequest request) {
		Principal principal = request.getUserPrincipal();
		if (principal == null) {
			return Set.of();
		}
		OAuth2AuthorizedClient client = this.authorizedClients.loadAuthorizedClient(this.registrationId,
				principal.getName());
		return (client == null) ? Set.of() : client.getAccessToken().getScopes();
	}
}
```

`security/StepUpLoginSuccessHandler.java`:

```java
package dev.starryeye.authz.agent.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * login이 끝나면 step-up 기다림을 끝내고, 받은 scope를 기록으로 남긴다.
 *
 * <p>사용자는 consent 화면에서 새 scope를 체크하지 않을 수 있다.
 * 그러면 Authorization Server는 이전 scope만 담은 token을 준다(MCP Security Best Practices — down-scoping).
 * 그 경우도 login은 성공이므로 채팅 화면으로 돌아간다. 다음 {@code 403}에는 카드 대신 거절 안내가 나간다.
 */
public class StepUpLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

	private static final Logger log = LoggerFactory.getLogger(StepUpLoginSuccessHandler.class);

	private final OAuth2AuthorizedClientService authorizedClients;

	private final String registrationId;

	public StepUpLoginSuccessHandler(OAuth2AuthorizedClientService authorizedClients, String registrationId) {
		this.authorizedClients = authorizedClients;
		this.registrationId = registrationId;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
		StepUpState state = StepUpState.existing(request.getSession(false));
		if (state != null && state.isPending()) {
			List<String> requested = state.finish();
			OAuth2AuthorizedClient client = this.authorizedClients.loadAuthorizedClient(this.registrationId,
					authentication.getName());
			Set<String> granted = (client == null) ? Set.of() : client.getAccessToken().getScopes();
			List<String> missing = requested.stream().filter(scope -> !granted.contains(scope)).toList();
			if (missing.isEmpty()) {
				log.info("step-up 완료 — 사용자={}, 받은 scope={}", authentication.getName(), granted);
			}
			else {
				log.info("step-up에서 허락받지 못한 scope — 사용자={}, 빠진 scope={}", authentication.getName(), missing);
			}
		}
		super.onAuthenticationSuccess(request, response, authentication);
	}
}
```

`security/LoginFailureHandler#onAuthenticationFailure` 맨 앞에 넣는다. javadoc에도 "step-up 중의 실패(consent 취소 등)는 채팅 화면으로 돌려보낸다" 한 문단을 더한다.

```java
		StepUpState state = StepUpState.existing(request.getSession(false));
		if (state != null && state.isPending()) {
			log.info("step-up이 끝나지 않았다({}): {}", state.finish(), exception.getMessage());
			response.sendRedirect(request.getContextPath() + "/");
			return;
		}
```

`controller/StepUpController.java`:

```java
package dev.starryeye.authz.agent.controller;

import dev.starryeye.authz.agent.config.McpSecurityConfig;
import dev.starryeye.authz.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.authz.agent.security.StepUpState;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** 한 번 거절한 scope를 사용자가 명시적으로 다시 요청할 때 쓴다. */
@RestController
public class StepUpController {

	@GetMapping("/step-up/retry")
	public ResponseEntity<Void> retry(@RequestParam String scope, HttpSession session) {
		StepUpState.of(session).retry(List.of(scope.trim().split("\\s+")));
		URI location = UriComponentsBuilder
				.fromPath(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/"
						+ McpSecurityConfig.REGISTRATION_ID)
				.queryParam(StepUpAuthorizationRequestResolver.PARAMETER, scope)
				.encode().build().toUri();
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
	}
}
```

`config/SecurityConfig`:
1. `securityFilterChain` 인자에 `OAuth2AuthorizedClientService authorizedClientService`를 더한다.
2. `.authorizationRequestResolver(authorizationRequestResolver(registrations))`를 다음으로 바꾼다.

```java
                                .authorizationRequestResolver(new StepUpAuthorizationRequestResolver(
                                        authorizationRequestResolver(registrations), authorizedClientService,
                                        McpSecurityConfig.REGISTRATION_ID)))
```

3. `.failureHandler(failureHandler))` 앞에 `.successHandler(new StepUpLoginSuccessHandler(authorizedClientService, McpSecurityConfig.REGISTRATION_ID))`를 넣는다.
4. 클래스 javadoc 목록에 `<li>step-up — {@code step_up} parameter로 scope를 늘린 authorization request를 다시 보낸다</li>`를 더한다.

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test
```

Expected: PASS(55 + 13 = 68개).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/shop-agent
git commit -m "feat(authz): agent의 step-up authorization request와 login 뒤 처리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: shop-agent — 채팅 SSE와 대화 안 consent 카드

**Files:**
- Create: `.../agent/controller/ChatEvents.java`
- Modify: `.../agent/controller/ChatController.java`, `practice/mcp-security-authz/shop-agent/src/main/resources/static/index.html`
- Test: `.../controller/ChatEventsTest.java`(create), `.../agent/ChatCsrfTest.java`

**Interfaces:**
- Consumes: Task 6의 `StepUpRequiredException`, Task 7의 `StepUpState`, `StepUpAuthorizationRequestResolver.PARAMETER`.
- Produces: `POST /api/chat`(`text/event-stream`). event 종류:
  - `message`: data = 답변 토막을 JSON 문자열로 감싼 값(예: `"\" 재고\""`).
  - `step-up`: data = `{"scope":"products:write","tool":"updateStock","url":"/oauth2/authorization/authserver?step_up=products:write"}`.
  - `step-up-declined`: data = `{"scope":…,"tool":…,"url":"/step-up/retry?scope=products:write"}`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ChatEventsTest`:

```java
package dev.starryeye.authz.agent.controller;

import dev.starryeye.authz.agent.security.StepUpRequiredException;
import dev.starryeye.authz.agent.security.StepUpState;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatEventsTest {

	StepUpState state = new StepUpState();

	List<ServerSentEvent<String>> events(Flux<String> content) {
		return ChatEvents.of(content, this.state).collectList().block();
	}

	@Test
	void 답변_토막은_앞의_공백까지_JSON_문자열로_감싼_message_event다() {
		List<ServerSentEvent<String>> events = events(Flux.just(" 재고", "는 7개"));

		assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "message");
		assertThat(events).extracting(ServerSentEvent::data).containsExactly("\" 재고\"", "\"는 7개\"");
	}

	@Test
	void step_up_예외는_consent_카드_event가_된다() {
		List<ServerSentEvent<String>> events = events(Flux.concat(Flux.just("확인해 볼게요"),
				Flux.error(new RuntimeException("감쌈",
						new StepUpRequiredException(List.of("products:write"), "updateStock")))));

		assertThat(events.get(1).event()).isEqualTo("step-up");
		assertThat(events.get(1).data()).isEqualTo("{\"scope\":\"products:write\",\"tool\":\"updateStock\","
				+ "\"url\":\"/oauth2/authorization/authserver?step_up=products:write\"}");
	}

	@Test
	void 이미_시도한_scope면_거절_안내_event가_된다() {
		this.state.start(List.of("products:write"));
		this.state.finish();

		List<ServerSentEvent<String>> events = events(
				Flux.error(new StepUpRequiredException(List.of("products:write"), "updateStock")));

		assertThat(events.get(0).event()).isEqualTo("step-up-declined");
		assertThat(events.get(0).data()).contains("\"url\":\"/step-up/retry?scope=products:write\"");
	}

	@Test
	void 다른_예외는_그대로_흘려보낸다() {
		assertThatThrownBy(() -> events(Flux.error(new IllegalStateException("모델 오류"))))
				.isInstanceOf(IllegalStateException.class);
	}
}
```

`ChatCsrfTest#페이지가_준_XSRF_TOKEN_을_헤더로_보내면_채팅이_시작된다`의 `post("/api/chat")`에 `.accept(MediaType.TEXT_EVENT_STREAM)`을 더한다(import `org.springframework.http.MediaType`). `CSRF_토큰_없이_채팅하면_403`은 그대로 둔다.

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test --tests '*.controller.ChatEventsTest'
```

Expected: 컴파일 실패.

- [ ] **Step 3: 구현한다**

`controller/ChatEvents.java`:

```java
package dev.starryeye.authz.agent.controller;

import dev.starryeye.authz.agent.config.McpSecurityConfig;
import dev.starryeye.authz.agent.security.StepUpAuthorizationRequestResolver;
import dev.starryeye.authz.agent.security.StepUpRequiredException;
import dev.starryeye.authz.agent.security.StepUpState;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 채팅 답을 SSE event로 바꾼다(안내서 10장).
 *
 * <p>답변 토막은 {@code message} event다.
 * tool 호출이 step-up을 요구하면 답을 멈추고 {@code step-up} event(consent 카드)를 보낸다.
 * 이미 step-up을 거친 scope면 {@code step-up-declined} event(거절 안내)를 보낸다.
 * 사용자가 거절한 권한을 카드로 되풀이해 묻지 않기 위해서다.
 */
public final class ChatEvents {

	static final String MESSAGE = "message";

	static final String STEP_UP = "step-up";

	static final String STEP_UP_DECLINED = "step-up-declined";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ChatEvents() {
	}

	public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state) {
		return content.map(text -> event(MESSAGE, JSON.writeValueAsString(text)))
				.onErrorResume(error -> StepUpRequiredException.find(error).isPresent(),
						error -> Flux.just(stepUp(StepUpRequiredException.find(error).orElseThrow(), state)));
	}

	private static ServerSentEvent<String> stepUp(StepUpRequiredException required, StepUpState state) {
		String scope = String.join(" ", required.scopes());
		boolean declined = state.attempted(required.scopes());
		String url = declined
				? UriComponentsBuilder.fromPath("/step-up/retry").queryParam("scope", scope)
						.encode().build().toUriString()
				: UriComponentsBuilder.fromPath(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
								+ "/" + McpSecurityConfig.REGISTRATION_ID)
						.queryParam(StepUpAuthorizationRequestResolver.PARAMETER, scope)
						.encode().build().toUriString();
		Map<String, Object> card = new LinkedHashMap<>();
		card.put("scope", scope);
		card.put("tool", required.tool());
		card.put("url", url);
		return event(declined ? STEP_UP_DECLINED : STEP_UP, JSON.writeValueAsString(card));
	}

	/**
	 * data는 항상 JSON이다.
	 * SSE는 {@code data:} 뒤의 공백 하나를 지우므로, 답변 토막을 그대로 보내면 토막 앞의 공백이 사라진다.
	 */
	private static ServerSentEvent<String> event(String name, String json) {
		return ServerSentEvent.<String>builder(json).event(name).build();
	}
}
```

`ChatController`의 `chat` 메서드를 바꾼다(import `org.springframework.http.codec.ServerSentEvent`, `jakarta.servlet.http.HttpSession`, `dev.starryeye.authz.agent.security.StepUpState`). 기존 javadoc 끝에 "답은 SSE event로 보낸다. tool이 step-up을 요구하면 consent 카드 event로 끝난다({@link ChatEvents})." 한 문장을 더한다.

```java
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session) {
        return ChatEvents.of(chatClient.prompt()
                .user(message)
                .stream()
                .content(), StepUpState.of(session));
    }
```

`index.html`의 `<body>` 내용을 다음으로 바꾼다(`<style>`에는 `.card { border: 1px solid #c9a227; background: #fff8e1; padding: 12px; margin-top: 12px; } .card[hidden] { display: none; }`를 더한다).

```html
<h1>shop-agent</h1>
<p>로그인한 사용자를 대신해 MCP 서버의 툴을 호출합니다. 예: <code>노트북 재고 있어?</code>, <code>p1 재고를 10개로 바꿔 줘</code></p>
<input id="q" placeholder="질문을 입력하세요" autofocus>
<button id="send">보내기</button>
<pre id="out"></pre>
<div id="card" class="card" hidden>
    <p id="card-text"></p>
    <button id="card-button"></button>
</div>
<script>
    const out = document.getElementById('out');
    const send = document.getElementById('send');
    const q = document.getElementById('q');
    const card = document.getElementById('card');
    const cardText = document.getElementById('card-text');
    const cardButton = document.getElementById('card-button');
    // consent 화면에 다녀오는 동안 질문을 잠시 둔다. 돌아오면 한 번만 다시 보낸다.
    const PENDING = 'shop-agent.pending-message';

    // csrf.spa()가 넣어 둔 XSRF-TOKEN cookie를 읽어 X-XSRF-TOKEN header로 되돌려 보낸다.
    function csrfHeaders() {
        const match = document.cookie.match('(?:^|;\\s*)XSRF-TOKEN=([^;]*)');
        return match ? {'X-XSRF-TOKEN': decodeURIComponent(match[1])} : {};
    }

    // SSE event 하나(빈 줄로 나뉜 덩어리)를 읽는다. data는 항상 JSON이다.
    function handle(block, message) {
        let event = 'message';
        const data = [];
        for (const line of block.split('\n')) {
            if (line.startsWith('event:')) event = line.slice(6).trim();
            else if (line.startsWith('data:')) data.push(line.slice(5));
        }
        if (data.length === 0) return;
        const value = JSON.parse(data.join('\n'));
        if (event === 'message') out.textContent += value;
        else if (event === 'step-up') showCard(value, message, false);
        else if (event === 'step-up-declined') showCard(value, message, true);
    }

    function showCard(info, message, declined) {
        const tool = info.tool ? info.tool : '이 작업';
        cardText.textContent = declined
            ? `${tool}에 필요한 ${info.scope} 권한을 받지 못했습니다. 다시 요청하려면 아래 버튼을 누르세요.`
            : `${tool}을(를) 하려면 ${info.scope} 권한이 더 필요합니다. 허용하면 권한을 받은 뒤 질문을 다시 보냅니다.`;
        cardButton.textContent = declined ? '다시 요청' : '권한 허용';
        cardButton.onclick = () => {
            sessionStorage.setItem(PENDING, message);
            location.href = info.url;
        };
        card.hidden = false;
    }

    async function ask() {
        const message = q.value.trim();
        if (!message) return;
        send.disabled = true;
        card.hidden = true;
        out.textContent = '생각 중... (로컬 모델은 30~100초 걸립니다)';
        try {
            const res = await fetch('/api/chat', {
                method: 'POST',
                headers: {...csrfHeaders(), 'Accept': 'text/event-stream'},
                body: message
            });
            if (!res.ok) {
                out.textContent = '오류: HTTP ' + res.status;
                return;
            }
            out.textContent = '';
            const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
            let buffer = '';
            for (;;) {
                const {value, done} = await reader.read();
                if (done) break;
                buffer += value.replace(/\r/g, '');
                let index;
                while ((index = buffer.indexOf('\n\n')) >= 0) {
                    handle(buffer.slice(0, index), message);
                    buffer = buffer.slice(index + 2);
                }
            }
            if (buffer.trim()) handle(buffer, message);
        } catch (e) {
            out.textContent = '오류: ' + e;
        } finally {
            send.disabled = false;
        }
    }

    send.addEventListener('click', ask);
    q.addEventListener('keydown', e => { if (e.key === 'Enter') ask(); });

    // consent 화면에서 돌아왔으면 멈췄던 질문을 한 번 다시 보낸다.
    window.addEventListener('load', () => {
        const pending = sessionStorage.getItem(PENDING);
        if (pending) {
            sessionStorage.removeItem(PENDING);
            q.value = pending;
            ask();
        }
    });
</script>
```

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/shop-agent && ./gradlew test
```

Expected: PASS(68 + 4 = 72개).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/shop-agent
git commit -m "feat(authz): 채팅을 SSE로 바꾸고 step-up을 대화 안 consent 카드로 보여 준다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: local-client — scope 고르기와 step-up

**Files:**
- Create: `.../localclient/BearerChallenge.java`, `.../localclient/ScopeSelection.java`, `.../localclient/TokenHolder.java`, `.../localclient/StepUp.java`
- Modify: `.../localclient/Discovery.java`, `.../localclient/AuthorizationRequest.java`, `.../localclient/TokenResponse.java`, `.../localclient/TokenClient.java`, `.../localclient/McpCalls.java`, `.../localclient/Main.java`
- Test: `BearerChallengeTest.java`, `ScopeSelectionTest.java`, `StepUpTest.java`(create); `DiscoveryTest.java`, `AuthorizationRequestTest.java`, `TokenClientTest.java`(modify)

(모든 경로는 `practice/mcp-security-authz/local-client/src/{main,test}/java/dev/starryeye/authz/localclient/` 아래다.)

**Interfaces:**
- Produces:
  - `record BearerChallenge(String error, List<String> scopes)` — Task 6과 같은 규칙(이 module의 복사본).
  - `record ScopeSelection(List<String> scopes, String source)`, `static ScopeSelection select(String challengeScope, Object scopesSupported)`, `String describe()`.
  - `Discovery#discover(String, String)`이 `Discovery.Result(AuthorizationServer server, ScopeSelection scopes)`를 돌려준다.
  - `record TokenResponse(String accessToken, long expiresIn, String scope)`(scope는 응답에 없으면 `null`), `Set<String> grantedScopes(Collection<String> requested)`.
  - `TokenHolder(String accessToken, Set<String> scopes)`: `accessToken()`, `scopes()`, `update(String, Set<String>)`.
  - `StepUp(TokenHolder, Authorizer, PrintStream) implements McpHttpClientTransportAuthorizationErrorHandler.Sync`, `interface Authorizer { TokenResponse authorize(Set<String> scopes); }`.
  - `AuthorizationRequest.uri(...)`는 `scope`가 `null`이면 `scope` parameter를 빼고 만든다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BearerChallengeTest` — Task 6의 `BearerChallengeTest`와 같은 네 테스트를 package만 `dev.starryeye.authz.localclient`로 바꿔 만든다.

`ScopeSelectionTest`:

```java
package dev.starryeye.authz.localclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeSelectionTest {

	@Test
	void challenge의_scope가_먼저다() {
		ScopeSelection selection = ScopeSelection.select("products:read", List.of("products:read", "products:write"));

		assertThat(selection.scopes()).containsExactly("products:read");
		assertThat(selection.describe()).isEqualTo("products:read (401의 scope)");
	}

	@Test
	void challenge에_없으면_scopes_supported_전부다() {
		ScopeSelection selection = ScopeSelection.select(null, List.of("products:read", "products:write"));

		assertThat(selection.scopes()).containsExactly("products:read", "products:write");
		assertThat(selection.describe()).isEqualTo("products:read products:write (PRM의 scopes_supported)");
	}

	@Test
	void 둘_다_없으면_scope를_보내지_않는다() {
		ScopeSelection selection = ScopeSelection.select(" ", null);

		assertThat(selection.scopes()).isEmpty();
		assertThat(selection.describe()).isEqualTo("없음 (scope parameter를 보내지 않는다)");
	}
}
```

`StepUpTest` — `FakeServer`로 `403`을 주는 MCP Server를 흉내 내지 않고, handler를 직접 부른다.

```java
package dev.starryeye.authz.localclient;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpTest {

	record Info(int statusCode, HttpHeaders headers, HttpClient.Version version) implements HttpResponse.ResponseInfo {
	}

	static HttpResponse.ResponseInfo info(int status, String challenge) {
		Map<String, List<String>> headers = (challenge == null) ? Map.of() : Map.of("WWW-Authenticate", List.of(challenge));
		return new Info(status, HttpHeaders.of(headers, (name, value) -> true), HttpClient.Version.HTTP_1_1);
	}

	static final String WRITE = "Bearer error=\"insufficient_scope\", scope=\"products:write\"";

	TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

	List<Set<String>> requested = new ArrayList<>();

	ByteArrayOutputStream printed = new ByteArrayOutputStream();

	StepUp stepUp(TokenResponse answer) {
		return new StepUp(this.holder, scopes -> {
			this.requested.add(scopes);
			return answer;
		}, new PrintStream(this.printed, true, StandardCharsets.UTF_8));
	}

	@Test
	void 합친_scope로_다시_authorization을_받고_새_token으로_다시_보낸다() {
		StepUp stepUp = stepUp(new TokenResponse("write-token", 300, "products:read products:write"));

		boolean retry = stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY);

		assertThat(retry).isTrue();
		assertThat(this.requested).containsExactly(Set.of("products:read", "products:write"));
		assertThat(this.holder.accessToken()).isEqualTo("write-token");
		assertThat(this.printed.toString(StandardCharsets.UTF_8)).contains("[6] step-up");
	}

	@Test
	void 새_token에도_scope가_없으면_멈춘다() {
		StepUp stepUp = stepUp(new TokenResponse("read-token-2", 300, "products:read"));

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("products:write 권한을 받지 못했다");
	}

	@Test
	void 같은_scope로_두_번_step_up하지_않는다() {
		StepUp stepUp = stepUp(new TokenResponse("write-token", 300, "products:read products:write"));
		stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY);

		assertThatThrownBy(() -> stepUp.handle(null, info(403, WRITE), McpTransportContext.EMPTY))
				.isInstanceOf(LocalClientException.class);
		assertThat(this.requested).hasSize(1);
	}

	@Test
	void insufficient_scope가_아닌_403은_step_up을_시작하지_않는다() {
		StepUp stepUp = stepUp(new TokenResponse("x", 300, null));

		assertThat(stepUp.handle(null, info(403, null), McpTransportContext.EMPTY)).isFalse();
		assertThat(stepUp.handle(null, info(401, "Bearer resource_metadata=\"x\""), McpTransportContext.EMPTY)).isFalse();
		assertThat(this.requested).isEmpty();
	}

	@Test
	void token_응답에_scope가_없으면_요청한_scope를_받은_것으로_본다() {
		assertThat(new TokenResponse("t", 300, null).grantedScopes(List.of("products:read")))
				.containsExactly("products:read");
	}
}
```

`DiscoveryTest`:
1. `this.discovery.discover(...)`를 부르는 모든 곳에서 결과의 `.server()`를 쓰도록 바꾼다(예: `AuthorizationServer server = this.discovery.discover(resource(), this.as.origin()).server();`).
2. 테스트를 더한다.

```java
	@Test
	void 챌린지의_scope로_처음_요청할_scope를_고른다() {
		this.mcp.on("POST", "/mcp", new FakeServer.Reply(401, Map.of("WWW-Authenticate",
				"Bearer resource_metadata=\"" + this.mcp.origin() + "/.well-known/oauth-protected-resource/mcp\", "
						+ "scope=\"products:read\""), ""));
		prm(resource(), this.as.origin());
		metadata(this.as.origin(), this.as.origin() + "/oauth2/authorize", "[\"S256\"]");

		Discovery.Result result = this.discovery.discover(resource(), this.as.origin());

		assertThat(result.scopes().scopes()).containsExactly("products:read");
	}
```

`AuthorizationRequestTest`: 기존 테스트의 `"openid profile"`을 `"products:read"`로(인자와 `containsEntry("scope", …)` 둘 다) 바꾸고, 테스트를 더한다.

```java
	@Test
	void scope가_없으면_scope_parameter를_보내지_않는다() {
		AuthorizationServer server = new AuthorizationServer("http://localhost:8141/mcp", "http://localhost:9030",
				"http://localhost:9030/oauth2/authorize", "http://localhost:9030/oauth2/token", true);

		URI uri = AuthorizationRequest.uri(server, "local-mcp-client", URI.create("http://127.0.0.1:50000/callback"),
				null, "state-1", Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

		assertThat(Form.decode(uri.getRawQuery())).doesNotContainKey("scope");
	}
```

`TokenClientTest`의 성공 테스트 응답 JSON에 `"scope":"products:read"`를 넣고 `assertThat(token.scope()).isEqualTo("products:read");`를 더한다.

- [ ] **Step 2: 실패하는지 본다**

```bash
cd practice/mcp-security-authz/local-client && ./gradlew test
```

Expected: 컴파일 실패.

- [ ] **Step 3: 구현한다**

`BearerChallenge.java` — Task 6의 `dev.starryeye.authz.agent.security.BearerChallenge`와 같은 코드를 package `dev.starryeye.authz.localclient`로 둔다(javadoc 첫 줄 뒤에 "agent와 같은 규칙이다. module이 서로 의존하지 않아 따로 둔다." 한 줄을 더한다).

`ScopeSelection.java`:

```java
package dev.starryeye.authz.localclient;

import java.util.List;

/**
 * 처음 요청할 scope다(MCP 2025-11-25 Authorization — Scope Selection Strategy, 안내서 10장).
 *
 * <p>범용 client는 서버마다 어떤 scope가 필요한지 모른다.
 * 그래서 {@code 401}의 {@code scope}를 먼저 쓰고, 없으면 PRM의 {@code scopes_supported} 전부를 쓰고,
 * 둘 다 없으면 scope 없이 요청한다.
 *
 * @param scopes 요청할 scope. 비어 있으면 scope parameter를 보내지 않는다
 * @param source 어디서 고른 값인지. 실행 출력에 쓴다
 */
public record ScopeSelection(List<String> scopes, String source) {

	public ScopeSelection {
		scopes = List.copyOf(scopes);
	}

	public static ScopeSelection select(String challengeScope, Object scopesSupported) {
		if (challengeScope != null && !challengeScope.isBlank()) {
			return new ScopeSelection(List.of(challengeScope.trim().split("\\s+")), "401의 scope");
		}
		if (scopesSupported instanceof List<?> supported && !supported.isEmpty()) {
			return new ScopeSelection(supported.stream().map(String::valueOf).toList(), "PRM의 scopes_supported");
		}
		return new ScopeSelection(List.of(), "scope parameter를 보내지 않는다");
	}

	public String describe() {
		return (this.scopes.isEmpty() ? "없음" : String.join(" ", this.scopes)) + " (" + this.source + ")";
	}
}
```

`Discovery`:
1. 상수 `private static final Pattern SCOPE = Pattern.compile("(?<![A-Za-z_])scope=\"([^\"]*)\"");`와 record 두 개를 더한다.

```java
	/** discovery의 결과다. */
	public record Result(AuthorizationServer server, ScopeSelection scopes) {
	}

	private record Challenge(String resourceMetadata, String scope) {
	}
```

2. `discover`의 반환 타입을 `Result`로 바꾸고, 첫 줄을 `Challenge challenge = challenge(resourceUrl); Map<String, Object> prm = protectedResourceMetadata(resourceUrl, challenge.resourceMetadata());`로, 마지막 `return`을 다음으로 바꾼다.

```java
			AuthorizationServer server = new AuthorizationServer((String) prm.get("resource"), trustedIssuer,
					requireEndpoint(metadata, "authorization_endpoint"), requireEndpoint(metadata, "token_endpoint"),
					Boolean.TRUE.equals(metadata.get("authorization_response_iss_parameter_supported")));
			return new Result(server, ScopeSelection.select(challenge.scope(), prm.get("scopes_supported")));
```

3. `protectedResourceMetadata(String resourceUrl)`를 `protectedResourceMetadata(String resourceUrl, String fromChallenge)`로 바꾸고 첫 줄의 `resourceMetadataUrl(resourceUrl)` 호출을 지운다. `resourceMetadataUrl`을 다음 `challenge`로 바꾼다.

```java
	/** token 없이 `initialize`를 보내고, `401`의 `WWW-Authenticate`에서 PRM 주소와 scope를 꺼낸다. */
	private Challenge challenge(String resourceUrl) {
		HttpResponse<String> response = Http.send(this.http, HttpRequest.newBuilder(URI.create(resourceUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
				.build());
		if (response.statusCode() != 401) {
			throw new LocalClientException("token 없는 요청에 401이 아니라 %d가 왔다".formatted(response.statusCode()));
		}
		String header = response.headers().firstValue("WWW-Authenticate").orElse("");
		Matcher metadata = RESOURCE_METADATA.matcher(header);
		Matcher scope = SCOPE.matcher(header);
		return new Challenge(metadata.find() ? metadata.group(1) : null, scope.find() ? scope.group(1) : null);
	}
```

4. 클래스 javadoc 목록 1번을 "token 없이 `initialize`를 보내 `401`의 `resource_metadata`와 `scope`를 받는다"로, 끝에 `<li>처음 요청할 scope를 고른다({@link ScopeSelection})</li>`를 더한다.

`AuthorizationRequest.uri`: `params.put("scope", scope);`를 `if (scope != null) { params.put("scope", scope); }`로 바꾸고, javadoc에 "scope가 `null`이면 scope parameter를 보내지 않는다." 한 줄을 더한다.

`TokenResponse.java`:

```java
package dev.starryeye.authz.localclient;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * token endpoint의 응답 중 이 client가 쓰는 값. public client에는 refresh token이 오지 않는다.
 *
 * @param scope 응답의 `scope`. 요청과 같으면 Authorization Server가 생략할 수 있어 `null`일 수 있다(RFC 6749 §5.1)
 */
public record TokenResponse(String accessToken, long expiresIn, String scope) {

	/** 받은 scope다. 응답에 `scope`가 없으면 요청한 scope를 그대로 받은 것이다. */
	public Set<String> grantedScopes(Collection<String> requested) {
		return (this.scope == null) ? new LinkedHashSet<>(requested)
				: new LinkedHashSet<>(List.of(this.scope.trim().split("\\s+")));
	}
}
```

`TokenClient#exchange`: `return new TokenResponse(accessToken, expiresIn);`를 다음으로 바꾼다.

```java
		String scope = (body.get("scope") instanceof String granted && !granted.isBlank()) ? granted : null;
		return new TokenResponse(accessToken, expiresIn, scope);
```

`TokenHolder.java`:

```java
package dev.starryeye.authz.localclient;

import java.util.Set;

/**
 * 지금 쓰는 access token과 그 scope다.
 * step-up이 새 token을 받으면 바꾸고, MCP 요청은 보낼 때마다 여기서 token을 읽는다.
 */
public final class TokenHolder {

	private volatile String accessToken;

	private volatile Set<String> scopes;

	public TokenHolder(String accessToken, Set<String> scopes) {
		this.accessToken = accessToken;
		this.scopes = Set.copyOf(scopes);
	}

	public String accessToken() {
		return this.accessToken;
	}

	public Set<String> scopes() {
		return this.scopes;
	}

	public void update(String accessToken, Set<String> scopes) {
		this.accessToken = accessToken;
		this.scopes = Set.copyOf(scopes);
	}
}
```

`StepUp.java`:

```java
package dev.starryeye.authz.localclient;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 요청이 `403 insufficient_scope`를 받으면, 합친 scope로 browser authorization을 한 번 더 하고 요청을 다시 보낸다(안내서 10장).
 *
 * <p>사용자 기기의 앱은 사용자가 바로 앞에 있으므로 그 자리에서 browser를 연다.
 * 새 scope만이 아니라 지금 가진 scope와 합쳐 요청한다(MCP 2026-07-28).
 * 같은 scope로는 한 번만 시도한다. 사용자가 허락하지 않으면 멈춘다.
 */
public final class StepUp implements McpHttpClientTransportAuthorizationErrorHandler.Sync {

	/** 주어진 scope로 authorization code 흐름을 한 번 밟아 token을 받는다. */
	public interface Authorizer {

		TokenResponse authorize(Set<String> scopes);
	}

	private final TokenHolder holder;

	private final Authorizer authorizer;

	private final PrintStream out;

	private final Set<String> attempted = new HashSet<>();

	public StepUp(TokenHolder holder, Authorizer authorizer, PrintStream out) {
		this.holder = holder;
		this.authorizer = authorizer;
		this.out = out;
	}

	@Override
	public boolean handle(HttpRequestSnapshot requestSnapshot, HttpResponse.ResponseInfo responseInfo,
			McpTransportContext context) {
		if (responseInfo.statusCode() != 403) {
			return false;
		}
		Optional<BearerChallenge> challenge = responseInfo.headers().firstValue("WWW-Authenticate")
				.flatMap(BearerChallenge::parse)
				.filter(BearerChallenge::insufficientScope);
		if (challenge.isEmpty()) {
			return false;
		}
		List<String> needed = challenge.get().scopes();
		String neededText = String.join(" ", needed);
		if (!this.attempted.addAll(needed)) {
			throw new LocalClientException(neededText + " 권한을 받지 못했다");
		}
		Set<String> scopes = new LinkedHashSet<>(this.holder.scopes());
		scopes.addAll(needed);
		this.out.println("    403 insufficient_scope — 필요한 scope: " + neededText);
		this.out.println("[6] step-up: " + String.join(" ", scopes) + "로 다시 authorization을 받는다");
		TokenResponse token = this.authorizer.authorize(scopes);
		Set<String> granted = token.grantedScopes(scopes);
		if (!granted.containsAll(needed)) {
			throw new LocalClientException(neededText + " 권한을 받지 못했다");
		}
		this.holder.update(token.accessToken(), granted);
		this.out.println("[7] 새 token으로 같은 요청을 다시 보낸다");
		return true;
	}
}
```

`McpCalls`:
1. 시그니처를 `public static void run(String resourceUrl, TokenHolder holder, StepUp stepUp, Duration requestTimeout, PrintStream out)`로 바꾼다.
2. transport를 다음으로 만든다. `.requestBuilder(...)` 줄은 지운다.

```java
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(uri.getScheme() + "://" + uri.getRawAuthority())
				.endpoint(uri.getRawPath())
				// 요청을 보낼 때마다 지금 token을 붙인다. step-up이 token을 바꾸면 다음 요청부터 새 token이 붙는다.
				.httpRequestCustomizer((builder, method, endpoint, body, context) ->
						builder.setHeader("Authorization", "Bearer " + holder.accessToken()))
				.authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(stepUp))
				.build();
```

3. `.requestTimeout(Duration.ofSeconds(20))`를 `.requestTimeout(requestTimeout)`으로 바꾼다.
4. `getStock` 출력 뒤에 `updateStock`을 부른다.

```java
			McpSchema.CallToolResult updated = client.callTool(McpSchema.CallToolRequest.builder("updateStock")
					.arguments(Map.of("productId", "p1", "quantity", 10)).build());
			updated.content().forEach(content -> out.println("    updateStock(p1, 10): "
					+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
```

5. `catch (LocalClientException ex)` 블록을 지우고, `catch (RuntimeException ex)`를 다음으로 바꾼다.

```java
		catch (RuntimeException ex) {
			// step-up에서 난 LocalClientException은 SDK를 거치며 감싸일 수 있다. 원인 사슬에서 찾아 그대로 올린다.
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof LocalClientException localClient) {
					throw localClient;
				}
			}
			throw new LocalClientException("MCP 호출이 실패했다: " + ex.getMessage(), ex);
		}
```

6. 클래스 javadoc을 "요청마다 {@link TokenHolder}의 token을 붙인다. `403 insufficient_scope`는 {@link StepUp}이 처리하고, 같은 요청을 다시 보낸다." 로 바꾼다. import `io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler`, `java.time.Duration`.

`Main`:
1. `SCOPE` 상수를 지운다.
2. `run`을 다음으로 바꾼다.

```java
	static void run(Options options, PrintStream out) {
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();

		out.println("[1] discovery: " + options.resourceUrl());
		Discovery.Result discovered = new Discovery(http).discover(options.resourceUrl(), options.issuer());
		AuthorizationServer server = discovered.server();
		out.println("    Authorization Server: " + server.issuer());
		out.println("    처음 요청할 scope: " + discovered.scopes().describe());

		StepUp.Authorizer authorizer = scopes -> authorize(http, server, scopes, options, out);
		Set<String> initial = new LinkedHashSet<>(discovered.scopes().scopes());
		out.println("[2] browser에서 login과 consent를 한다");
		TokenResponse token = authorizer.authorize(initial);
		TokenHolder holder = new TokenHolder(token.accessToken(), token.grantedScopes(initial));

		out.println("[5] MCP 호출");
		// step-up은 요청 도중에 browser login을 기다리므로, 요청 시간 제한을 login 시간 제한보다 길게 둔다.
		McpCalls.run(options.resourceUrl(), holder, new StepUp(holder, authorizer, out),
				LOGIN_TIMEOUT.plus(REQUEST_TIMEOUT), out);
	}

	/** authorization code 흐름을 한 번 밟는다: browser → loopback callback → client_secret 없는 token request. */
	static TokenResponse authorize(HttpClient http, AuthorizationServer server, Set<String> scopes, Options options,
			PrintStream out) {
		Pkce pkce = Pkce.generate();
		String state = randomState();
		try (LoopbackCallbackServer callback = LoopbackCallbackServer.start()) {
			URI authorization = AuthorizationRequest.uri(server, CLIENT_ID, callback.redirectUri(),
					scopes.isEmpty() ? null : String.join(" ", scopes), state, pkce);
			out.println("    " + authorization);
			if (options.openBrowser()) {
				Browser.open(authorization, out);
			}
			String code = AuthorizationResponse.code(callback.await(LOGIN_TIMEOUT), state, server);
			out.println("[3] callback으로 authorization code를 받았다: " + callback.redirectUri());

			TokenResponse token = new TokenClient(http).exchange(server, CLIENT_ID, code, callback.redirectUri(), pkce);
			out.println("[4] client_secret 없이 access token을 받았다(" + token.expiresIn() + "초 뒤 만료, scope: "
					+ String.join(" ", token.grantedScopes(scopes)) + ")");
			return token;
		}
		catch (IOException ex) {
			throw new LocalClientException("callback server를 열지 못했다", ex);
		}
	}
```

3. 상수 `static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);`와 import `java.util.LinkedHashSet`, `java.util.Set`를 더한다. 클래스 javadoc의 흐름 줄에 "… → MCP 호출 → `403`이면 합친 scope로 step-up"을 더한다.

- [ ] **Step 4: 통과하는지 본다**

```bash
cd practice/mcp-security-authz/local-client && ./gradlew test
```

Expected: PASS(31 + 4 + 3 + 5 + 1 + 1 = 45개).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-security-authz/local-client
git commit -m "feat(authz): local-client가 scope를 고르고 403이면 합친 scope로 step-up한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 실제 실행과 캡처

**Files:**
- Create: `docs/superpowers/captures/mcp-authz-walkthrough.sh`, `docs/superpowers/captures/authz-local-client-run.sh`
- Create: `docs/superpowers/captures/2026-09-29-authz-walkthrough.txt`, `docs/superpowers/captures/2026-09-29-authz-local-client.txt` (날짜는 실제로 뜬 날로 한다)

**Interfaces:**
- Consumes: Task 1–9의 네 module.
- Produces: 안내서 10장과 README가 인용할 실제 요청·응답(A 번호)과 `local-client` 출력.

- [ ] **Step 1: curl 캡처 스크립트를 만든다**

`docs/superpowers/captures/mcp-authz-walkthrough.sh`:

```bash
#!/usr/bin/env bash
# mcp-security-authz practice 의 scope 와 step-up 흐름을 curl 로 한 단계씩 밟으며 기록한다(A 번호).
# 기밀 client(authz-shop-agent)로 밟는다. consent 가 저장되어, step-up 에서 새 scope 만 묻는 화면을 볼 수 있다.
# 사용: auth-server(:9030)와 shop-mcp-server(:8141)를 새로 띄운 직후에 실행한다(저장된 consent 가 없어야 한다).
#   ./mcp-authz-walkthrough.sh > 결과.txt
# 출력의 token 은 줄인다 — JWT 는 앞 20자, 인가 코드는 앞 12자 뒤에 "...".
set -uo pipefail

AS=${AS:-http://localhost:9030}
MCP_BASE=${MCP_BASE:-http://localhost:8141}
MCP="$MCP_BASE/mcp"
CLIENT_ID=${CLIENT_ID:-authz-shop-agent}
CLIENT_SECRET=${CLIENT_SECRET:-authz-shop-agent-secret}
REDIRECT_URI=${REDIRECT_URI:-http://localhost:8140/login/oauth2/code/authserver}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
PROTOCOL_VERSION=2025-11-25

# RFC 7636 부록 B 의 예시 값이다.
VERIFIER=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
CHALLENGE=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

step() { printf '\n\n===== %s =====\n' "$1"; }
fail() { printf '\n[오류] %s\n' "$1" >&2; exit 1; }

tidy() {
  tr -d '\r' \
    | grep -vE '^(X-Content-Type-Options|X-XSS-Protection|X-Frame-Options|Expires|Date|Keep-Alive|Connection|Cache-Control|Pragma|Vary):' \
    | sed -E \
        -e 's/(eyJ[A-Za-z0-9_-]{17})[A-Za-z0-9_.-]+/\1.../g' \
        -e 's/([?&]code=[^&[:space:]]{12})[^&[:space:]]*/\1.../g'
}

payload() {
  local segment="$1" pad
  pad=$(( (4 - ${#segment} % 4) % 4 ))
  [ "$pad" -gt 0 ] && segment="$segment$(printf '=%.0s' $(seq 1 $pad))"
  printf '%s' "$segment" | tr '_-' '/+' | base64 -d 2>/dev/null
  echo
}

location_of() { printf '%s' "$1" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p'; }

# authorization request 를 보낸다. consent 화면이면 체크박스 줄을 보여 주고 PAGE_STATE 를, 아니면 CODE 를 채운다.
authorize() {
  local scope="$1" headers body
  body=$(mktemp)
  headers=$(curl -s -D - -o "$body" -c "$JAR" -b "$JAR" -G "$AS/oauth2/authorize" \
    --data-urlencode response_type=code --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "redirect_uri=$REDIRECT_URI" --data-urlencode "scope=$scope" \
    --data-urlencode state=state-1 --data-urlencode "code_challenge=$CHALLENGE" \
    --data-urlencode code_challenge_method=S256 --data-urlencode "resource=$MCP")
  PAGE_STATE=""
  CODE=""
  if printf '%s' "$headers" | head -1 | grep -q ' 200'; then
    echo "HTTP 200 — consent 화면. scope 선택 항목:"
    grep -o '<input class="form-check-input"[^>]*>' "$body"
    PAGE_STATE=$(grep -o 'name="state" value="[^"]*"' "$body" | head -1 | sed 's/.*value="//;s/"$//')
  else
    printf '%s\n' "$headers" | grep -iE '^(HTTP|location):' | tidy
    CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  fi
  rm -f "$body"
}

# consent 화면에서 주어진 scope 를 체크해 제출하고 CODE 를 채운다.
consent() {
  local args=(--data-urlencode "client_id=$CLIENT_ID" --data-urlencode "state=$PAGE_STATE") headers
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  echo "체크한 scope: ${*:-(없음)}"
  headers=$(curl -s -D - -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}")
  printf '%s\n' "$headers" | grep -iE '^(HTTP|location):' | tidy
  CODE=$(location_of "$headers" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
}

# CODE 를 token 으로 바꾸고 TOKEN 을 채운다.
token() {
  [ -n "$CODE" ] || fail "authorization code 가 없다"
  local body
  body=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" "$AS/oauth2/token" -d grant_type=authorization_code \
    --data-urlencode "code=$CODE" --data-urlencode "redirect_uri=$REDIRECT_URI" \
    -d "code_verifier=$VERIFIER" --data-urlencode "resource=$MCP")
  printf '%s\n' "$body" | tidy
  TOKEN=$(printf '%s' "$body" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ] || fail "access_token 이 없다"
  echo "access token payload:"
  payload "$(printf '%s' "$TOKEN" | cut -d. -f2)"
}

mcp() {  # $1 token, $2 JSON 본문, 나머지는 curl 인자
  local token="$1" body="$2"; shift 2
  curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "MCP-Protocol-Version: $PROTOCOL_VERSION" -H "Authorization: Bearer $token" "$@" -d "$body" | tidy
}

UPDATE_STOCK='{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}}'

step "A1. token 없이 부르면 401 과 처음 요청할 scope"
curl -s -i -X POST "$MCP" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  | tidy | head -8

step "A2. PRM 의 scopes_supported"
curl -s "$MCP_BASE/.well-known/oauth-protected-resource/mcp"; echo

step "A3. login"
FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
CSRF=$(printf '%s' "$FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
[ -n "$CSRF" ] || fail "CSRF token 을 찾지 못했다"
curl -s -o /dev/null -w 'POST /login → HTTP %{http_code}\n' -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$CSRF"

step "A4. 최소 scope(openid products:read)로 authorization — consent 화면"
authorize "openid products:read"
consent products:read
step "A4-token. token request"
token
READ_TOKEN=$TOKEN

step "A5. 조회 token 으로 initialize 와 tools/list"
INIT=$(mcp "$READ_TOKEN" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')
printf '%s\n' "$INIT" | head -12
SESSION=$(printf '%s' "$INIT" | sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d: //p' | head -1)
[ -n "$SESSION" ] || fail "Mcp-Session-Id 가 없다"
mcp "$READ_TOKEN" '{"jsonrpc":"2.0","method":"notifications/initialized"}' -H "Mcp-Session-Id: $SESSION" | head -1
mcp "$READ_TOKEN" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' -H "Mcp-Session-Id: $SESSION" \
  | grep -o '"name":"[A-Za-z]*"' | sort -u

step "A6. 조회 token 으로 재고 변경 — 403 insufficient_scope"
mcp "$READ_TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION" | head -6

step "A7. step-up(openid products:read products:write) — 새 scope 만 묻는 consent 화면, products:write 를 체크하지 않는다"
authorize "openid products:read products:write"
consent
step "A7-token. 이전 scope 만 담긴 token"
token
step "A7-call. 다시 재고 변경 — 여전히 403"
mcp "$TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION" | head -6

step "A8. step-up 을 다시 하고 products:write 를 체크한다"
authorize "openid products:read products:write"
consent products:write
step "A8-token. 두 scope 가 담긴 token"
token
step "A8-call. 재고 변경 — 200"
mcp "$TOKEN" "$UPDATE_STOCK" -H "Mcp-Session-Id: $SESSION"
```

`docs/superpowers/captures/authz-local-client-run.sh`(official의 `local-client-run.sh`를 두 번의 authorization에 맞게 고친 것):

```bash
#!/usr/bin/env bash
# mcp-security-authz 의 local-client 를 --no-browser 로 돌리고, browser 대신 curl 이 login 과 consent 를 한다.
# 처음 authorization 과 step-up authorization 두 번을 처리한다.
# 사용: practice/mcp-security-authz 의 auth-server(:9030)·shop-mcp-server(:8141) 를 띄운 뒤 실행한다.
#   ./authz-local-client-run.sh > 결과.txt
set -uo pipefail

AS=${AS:-http://localhost:9030}
LOGIN_USERNAME=${LOGIN_USERNAME:-user}
LOGIN_PASSWORD=${LOGIN_PASSWORD:-password}
CLIENT_DIR=${CLIENT_DIR:-$(cd "$(dirname "$0")/../../../practice/mcp-security-authz/local-client" && pwd)}

JAR=$(mktemp)
OUT=$(mktemp)
trap 'rm -f "$JAR" "$OUT"' EXIT

( cd "$CLIENT_DIR" && ./gradlew -q run --args="--no-browser" > "$OUT" 2>&1 ) &
CLIENT_PID=$!

# n 번째 authorization 주소가 출력에 나올 때까지 기다린다.
wait_url() {
  URL=""
  for _ in $(seq 1 360); do
    URL=$(grep -o 'http://[^ ]*/oauth2/authorize?[^ ]*' "$OUT" | sed -n "${1}p")
    [ -n "$URL" ] && return 0
    kill -0 "$CLIENT_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

# 주소를 열어 consent 화면의 state 를 읽고, 주어진 scope 를 체크해 제출한다. redirect 는 loopback callback 까지 따라간다.
consent() {
  local url="$1" page state args
  shift
  page=$(curl -s -c "$JAR" -b "$JAR" "$url")
  state=$(printf '%s' "$page" | grep -o 'name="state" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
  if [ -z "$state" ]; then
    echo "[오류] consent 화면의 state 를 찾지 못했다" >&2
    kill "$CLIENT_PID" 2>/dev/null
    exit 1
  fi
  args=(--data-urlencode 'client_id=local-mcp-client' --data-urlencode "state=$state")
  for s in "$@"; do args+=(--data-urlencode "scope=$s"); done
  curl -s -o /dev/null -L -c "$JAR" -b "$JAR" -X POST "$AS/oauth2/authorize" "${args[@]}"
}

wait_url 1 || { echo "[오류] 첫 authorization 주소가 나오지 않았다" >&2; cat "$OUT" >&2; exit 1; }
FORM=$(curl -s -c "$JAR" -b "$JAR" "$AS/login")
CSRF=$(printf '%s' "$FORM" | grep -o '<input[^>]*name="_csrf"[^>]*>' | head -1 | grep -o 'value="[^"]*"' | sed 's/^value="//;s/"$//')
curl -s -o /dev/null -c "$JAR" -b "$JAR" -X POST "$AS/login" \
  --data-urlencode "username=$LOGIN_USERNAME" --data-urlencode "password=$LOGIN_PASSWORD" --data-urlencode "_csrf=$CSRF"
consent "$URL" products:read

# public client 는 consent 를 저장하지 않아 step-up 에서도 두 scope 를 모두 묻는다. 둘 다 체크한다.
wait_url 2 || { echo "[오류] step-up authorization 주소가 나오지 않았다" >&2; cat "$OUT" >&2; exit 1; }
consent "$URL" products:read products:write

wait "$CLIENT_PID"
STATUS=$?
printf '# mcp-security-authz local-client 실행 — %s (authz-local-client-run.sh, browser 대신 curl)\n\n' "$(date +%F)"
sed -E 's/(code_challenge=)[^&]{12}[^&]*/\1.../; s/(state=)[^&]{6}[^&]*/\1.../' "$OUT"
exit "$STATUS"
```

```bash
chmod +x docs/superpowers/captures/mcp-authz-walkthrough.sh docs/superpowers/captures/authz-local-client-run.sh
```

- [ ] **Step 2: 서버를 띄운다**

```bash
cd practice/mcp-security-authz
nohup ./run.sh > /tmp/authz-run.log 2>&1 &
# 세 서버가 뜰 때까지 기다린다(run.sh 는 ollama 준비도 한다).
for _ in $(seq 1 180); do
  curl -s -o /dev/null -w '%{http_code}' http://localhost:8140/ | grep -qv 000 && \
  curl -s -o /dev/null -w '%{http_code}' http://localhost:8141/mcp | grep -qv 000 && break
  sleep 2
done
```

- [ ] **Step 3: curl 캡처를 뜬다**

```bash
cd docs/superpowers/captures
./mcp-authz-walkthrough.sh > 2026-09-29-authz-walkthrough.txt
```

Expected(파일에서 확인):
- A1: `401`, `WWW-Authenticate: Bearer resource_metadata="http://localhost:8141/.well-known/oauth-protected-resource/mcp", scope="products:read"`.
- A2: `"scopes_supported":["products:read"]`.
- A4: consent 화면에 `products:read` 선택 항목, token payload의 `scope`는 `["openid","products:read"]`, `client_id`는 `authz-shop-agent`.
- A5: tool 세 개(`getStock`, `searchProducts`, `updateStock`).
- A6: `403`, `WWW-Authenticate: Bearer error="insufficient_scope", scope="products:write", resource_metadata="…"`.
- A7: consent 화면에 `products:write`만 선택 항목이고 `products:read`는 `checked disabled`, 새 token의 `scope`는 `["openid","products:read"]`, 다시 `403`.
- A8: 새 token의 `scope`에 `products:write`가 있고, `200`과 `재고를 10개로 바꿨습니다`.

기대와 다르면 원인을 고치고(코드라면 해당 Task의 테스트를 더해) 다시 뜬다. auth-server는 consent를 메모리에 두므로 다시 뜰 때는 auth-server를 다시 띄운다.

- [ ] **Step 4: local-client 캡처를 뜬다**

```bash
cd docs/superpowers/captures
./authz-local-client-run.sh > 2026-09-29-authz-local-client.txt
```

Expected: `[1]`의 `처음 요청할 scope: products:read (401의 scope)`, `[4]`의 `scope: products:read`, `[5]` 뒤 `getStock(p1)`, `403 insufficient_scope — 필요한 scope: products:write`, `[6] step-up: products:read products:write로 …`, `[4]`의 `scope: products:read products:write`, `[7]`, `updateStock(p1, 10): 상품 p1 (게이밍 노트북 15인치) 의 재고를 10개로 바꿨습니다.` 앞 단계의 A8이 이미 재고를 10으로 바꿨으므로 `getStock(p1)`은 10개로 나올 수 있다. 캡처 순서를 지키면 된다.

- [ ] **Step 5: 웹 agent를 browser로 확인한다 (controller가 한다)**

subagent는 이 단계를 건너뛰고 보고서에 "controller 확인 필요"로 남긴다. controller는 Claude Browser pane으로 `http://localhost:8140`을 열어 다음을 확인하고, 결과(화면에 나온 문구)를 보고서에 붙인다.
1. login 뒤 consent 화면에 `products:read`만 나온다.
2. "노트북 재고 있어?"에 답한다.
3. "p1 재고를 10개로 바꿔 줘" → 채팅 아래 consent 카드가 뜬다(LLM이 `updateStock`을 고르지 않으면 질문을 더 분명히 한다).
4. [권한 허용] → consent 화면에 `products:write`만 선택 항목 → 체크하고 제출 → 채팅으로 돌아와 질문이 다시 가고 "재고를 10개로 바꿨습니다"가 나온다.
5. 새로 띄운 뒤 4에서 체크하지 않고 제출 → 돌아와 다시 간 질문에 "권한을 받지 못했습니다 … [다시 요청]" 안내가 뜬다.
6. `logs/shop-mcp-server.log`에 `scope 부족 — 사용자=user, client_id=authz-shop-agent, tool=updateStock` 줄이 있다.

- [ ] **Step 6: 서버를 내리고 커밋한다**

```bash
cd practice/mcp-security-authz && ./stop.sh
for p in 8140 8141 9030; do lsof -ti tcp:$p -sTCP:LISTEN || echo "$p 비었음"; done
cd ../..
git add docs/superpowers/captures/mcp-authz-walkthrough.sh docs/superpowers/captures/authz-local-client-run.sh \
  docs/superpowers/captures/2026-09-29-authz-walkthrough.txt docs/superpowers/captures/2026-09-29-authz-local-client.txt
git commit -m "docs(captures): mcp-security-authz의 scope·step-up curl 캡처와 local-client 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: practice README

**Files:**
- Create: `practice/mcp-security-authz/README.md`

**Interfaces:**
- Consumes: Task 10의 캡처, 안내서 10장 파일 이름 `practice/mcp-guide/10-scope-and-step-up.md`(Task 12에서 만든다. 이 Task의 검사에서 그 링크만 `link` 위반으로 남는 것은 허용한다).

- [ ] **Step 1: 저장소 스킬과 기준 README를 읽는다**

`.claude/skills/writing-practice-docs/SKILL.md`, `templates.md`, `practice/mcp-security-authn-chat-memory/README.md`("official과 다른 점" 중심 README의 기준), `practice/mcp-security-authn-official/README.md`(실행·코드 지도 형식).

- [ ] **Step 2: README를 쓴다**

구성(chat-memory README와 같은 형식):
1. 소개 3–4줄: official을 복사해 tool마다 scope를 두고 step-up을 보여 주는 practice. 안내서 10장 링크.
2. `## official과 다른 점` — 표(바뀐 곳 · official · 이 practice · 안내서 절):
   - scope 등록(`openid profile` → `products:read`·`products:write`), agent consent(`false` → `true`), access token의 `client_id`
   - MCP Server: `@RequiredScope`·`ToolScopeRegistry`, `ToolScopeFilter`(`403 insufficient_scope`), `401`의 `scope`, PRM의 `scopes_supported`, `updateStock`
   - agent: scope 고르기, `StepUpAuthorizationErrorHandler`·`StepUpToolExecutionExceptionProcessor`, `StepUpAuthorizationRequestResolver`·`StepUpState`·`StepUpLoginSuccessHandler`, 채팅 SSE와 consent 카드(`ChatEvents`, `index.html`)
   - local-client: scope 고르기(`ScopeSelection`), `StepUp`, `TokenHolder`
   - 이어서 짧은 절 두 개: "step-up 흐름"(mermaid 한 장: 사용자 → agent → MCP Server `403` → 카드 → consent → 돌아와 다시 보내기. 아래에 PNG 링크, `render_diagrams.py`로 만든다), "거절과 일부 허락"(되풀이하지 않는 규칙).
3. `## 실행` — 포트(9030/8141/8140), 계정, `./run.sh`/`./stop.sh`, `local-client` 실행(`./gradlew run`, `--no-browser`), JAVA_HOME 한 줄. official README와 같은 문장 형식을 쓴다.
4. `## 코드 지도` — 이 practice에만 있거나 official과 다른 클래스(module · 클래스 · 하는 일 · 안내서). 나머지는 official README 코드 지도 링크.
5. `## 직접 확인할 것` — 표(할 일 · 기대 결과): Task 10 Step 3·4·5의 기대 결과를 그대로 쓴다(캡처 값 기준).
6. `## 더 읽을 것` — 안내서 10장, 5·6장, 준수표 부록, 로드맵의 다음 practice(stateless와 handle) 한 줄.

- [ ] **Step 3: 검사한다**

```bash
python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-security-authz/README.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-security-authz/README.md
```

Expected: `10-scope-and-step-up.md`로 가는 `link` 위반만 남는다.

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-security-authz/README.md practice/mcp-security-authz/diagrams
git commit -m "docs(authz): mcp-security-authz README

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`render_diagrams.py`가 README 옆에 `diagrams/` 폴더를 만든다. 다른 위치에 만들면 그 위치를 `git add`한다.)

---

### Task 12: 안내서 10장 — scope와 step-up

**Files:**
- Create: `practice/mcp-guide/10-scope-and-step-up.md`, `practice/mcp-guide/diagrams/10-scope-and-step-up-*.png`, `practice/mcp-guide/diagrams/.sources.json`(갱신)

**Interfaces:**
- Consumes: Task 10의 캡처 두 개, Task 1–9의 코드, spec 1절의 개념.

- [ ] **Step 1: 읽는다**

스킬(`SKILL.md`, `templates.md`), 기준 장 `practice/mcp-guide/03-discovery.md`, 이웃 장 `05-authorization-and-token.md`(5.3 scope 고르기), `06-mcp-call-and-validation.md`(`403 insufficient_scope`와 step-up), `07-local-client.md`, spec 1절, 캡처 두 개, MCP 원문(`/tmp/mcp-refs/mcp/2025-11-25_basic_authorization.mdx`의 Scope Selection Strategy·Scope Challenge Handling, `2025-11-25_security_best_practices.md`의 Scope Minimization; 없으면 modelcontextprotocol.io에서 읽는다).

- [ ] **Step 2: 장을 쓴다**

제목 `# 10. scope와 step-up — 필요한 권한만 받고, 필요할 때 늘린다`. 장의 틀을 따른다:
1. `## 10.1 최소 권한의 필요성` — agent의 권한은 사용자가 그 client에 맡긴 범위(scope가 상한, token의 `client_id`), 처음부터 다 받으면 생기는 일(권고의 공격 설명: 새어 나간 넓은 token), prompt injection과 사람이 확인하는 관문. 5·6장과 이어지는 점.
2. `## 10.2 시퀀스 다이어그램` — 처음 authorization(최소 scope) → 조회 → 재고 변경 `403` → step-up → 다시 호출. mermaid + PNG 링크.
3. `## 10.3 1단계: 처음에는 조회 scope만 받는다` — A1(`401`의 scope), A2(PRM의 `scopes_supported`), A4(consent 화면과 token의 `scope`·`client_id`). 흔한 실수(모든 scope를 싣기) 한 문단.
4. `## 10.4 2단계: 쓰기를 처음 시도하면 403이 온다` — A6의 header 세 값, challenge에 필요한 scope만 넣는 이유, 서버가 HTTP 단계에서 검사하는 이유(`@PreAuthorize`는 `200`의 tool 오류가 된다), 2026-07-28의 `Mcp-Name` header.
5. `## 10.5 3단계: 합친 scope로 다시 authorization을 받는다` — A7·A8의 consent 화면(새 scope만 선택 항목), 합쳐서 요청하는 이유(2026-07-28), 일부 허락(down-scoping)과 되풀이하지 않기.
6. `## 10.6 웹 agent: 대화 안 consent 카드` — agent는 스스로 새 token을 받을 수 없는 이유, 예외가 채팅까지 오는 경로(SDK handler → tool 예외 처리 → SSE event), 카드와 돌아온 뒤 다시 보내기, 실제 제품들의 방식(ChatGPT Apps SDK `mcp/www_authenticate`, Claude.ai Connect 카드, Copilot Studio consent card; SEP-1488은 Draft). 제품 이야기는 한 문단으로 짧게.
7. `## 10.7 사용자 기기의 앱: 그 자리에서 다시 login` — `local-client` 출력(캡처) 인용, `StepUp`이 요청 도중 browser를 여는 이유와 시간 제한.
8. `## 10.8 서버 코드에서 보기` — `ToolScopeRegistry`/`@RequiredScope`, `ToolScopeFilter`, `ScopeChallengeEntryPoint`, `CachedBodyHttpServletRequest`(본문을 두 번 읽는 문제). 코드 인용은 짧게, `/* ... */`로 줄인다.
9. `## 10.9 client 코드에서 보기` — agent의 `StepUpAuthorizationErrorHandler`, `StepUpToolExecutionExceptionProcessor`, `StepUpAuthorizationRequestResolver`, `ChatEvents`; `local-client`의 `ScopeSelection`, `StepUp`.
10. `## 10.10 이 장에서 다루지 않는 것` — agent 자기 신원(client credentials 확장, Enterprise-Managed Authorization), 위임 사슬의 token exchange(RFC 8693, `act`), tool 정의에 scope를 적는 표준 field 없음, scope별 tool 목록(로드맵 다음 practice). 한 줄씩.
11. `## 10.11 직접 해 보기` — 저장소 최상위에서 실행할 수 있는 명령: `practice/mcp-security-authz/run.sh`, curl 두세 개(A1, A6 흉내; 조회 token은 캡처 스크립트로 받는다고 안내), 웹 agent에서 재고 변경 질문.
12. `## 10.12 정리` — 4–5줄.
13. `## 10.13 명세 근거` — 표(내용 · 명세 · 요구 수준): Scope Selection Strategy(SHOULD, MUST), Scope Challenge Handling(SHOULD, MAY), RFC 6750 §3.1(SHOULD, MAY), Security Best Practices — Scope Minimization, MCP 2026-07-28 Authorization(합쳐서 요청, scope 계층 MUST, `offline_access` SHOULD NOT), RFC 9068 §2.2(`client_id` REQUIRED), RFC 6749 §5.1·§6(응답 scope 생략, refresh의 scope). 원문을 확인하고 요구 수준을 적는다.
14. 끝 줄: `[← 9장](09-versions.md) · [목차](README.md) · [부록: API 레퍼런스 →](reference-api.md)`.

문체 규칙(Global Constraints)을 지킨다. 길이는 `03-discovery.md`의 1.3~1.8배 안이 목표다(개념 두 개와 client 두 종류를 다루므로 3장보다 길다). 요청·응답은 캡처 값을 그대로 쓰고, JSON은 들여쓰고 핵심 field만 남긴다.

- [ ] **Step 3: 다이어그램과 검사**

```bash
MMDC=/Users/starryeye/.npm/_npx/668c188756b835f3/node_modules/.bin/mmdc \
  python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-guide/10-scope-and-step-up.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/10-scope-and-step-up.md
```

(`MMDC` 경로가 없으면 변수 없이 돌린다. 스크립트가 `npx`를 쓴다.)

Expected: 위반 0.

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-guide/10-scope-and-step-up.md practice/mcp-guide/diagrams
git commit -m "docs(guide): 10장 scope와 step-up

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 안내서와 부록, 입구 문서 갱신

**Files:**
- Modify: `practice/mcp-guide/README.md`, `05-authorization-and-token.md`, `06-mcp-call-and-validation.md`, `07-local-client.md`, `08-security.md`, `09-versions.md`, `reference-api.md`, `reference-compliance.md`
- Modify: `README.md`(저장소 최상위), `practice/mcp-security-authn-official/README.md`(더 읽을 것 한 줄)

- [ ] **Step 1: 안내서 입구와 장 사이 링크**

- `practice/mcp-guide/README.md`: 장 목록 표에 10장 행(한 줄 요약 · 직접 해 보는 것), 읽는 순서에 "10장은 6장 다음에 읽어도 된다" 한 줄, 다른 practice 목록에 `mcp-security-authz` 한 줄.
- 5장의 "scope 고르기" 문단 끝, 6장의 "`403 insufficient_scope`와 step-up" 문단 끝, 7장 7.10의 scope 항목, 8장 8.11(다루지 않는 것)의 scope 항목에 "`mcp-security-authz` practice로 직접 해 보는 것은 [10장](10-scope-and-step-up.md)에 있다" 식의 한 문장과 링크. 이미 "official은 쓰지 않는다"는 문장은 그대로 두고, 그 뒤에 이 practice를 가리키는 문장을 더한다.
- 9장 끝 nav 줄의 `[부록: API 레퍼런스 →](reference-api.md)`를 `[10장 →](10-scope-and-step-up.md)`로 바꾼다.

- [ ] **Step 2: 부록**

- `reference-api.md`: PRM 절의 `scopes_supported` 행, `401` 절의 `scope` 행, `403 insufficient_scope` 행에 "authz practice" 동작을 더한다(값은 Task 10 캡처 A 번호로 인용한다. 부록은 캡처 번호를 써도 된다). 끝 nav 줄 앞 링크를 `[← 10장](10-scope-and-step-up.md)`으로 바꾼다.
- `reference-compliance.md`: "읽는 법" 다음에 `## mcp-security-authz에서 달라지는 행` 절을 더한다. 표(행 번호 · 항목 · official · authz · 근거): 16번(scope challenge와 step-up), 37번(client의 scope 선택), 34번 참고(`client_id` claim)와 새로 생긴 항목(서버의 `insufficient_scope` 응답, challenge에 필요한 scope만, 합쳐서 요청, 거절된 scope 되풀이 안 함). 근거는 클래스 이름과 캡처 A 번호.

- [ ] **Step 3: 저장소 README와 official README**

- 저장소 `README.md`의 practice 목록에 `mcp-security-authz` 행(scope와 step-up, 네 module).
- official README "더 읽을 것"에 "scope와 step-up은 [mcp-security-authz practice](../mcp-security-authz/README.md)" 한 줄.

- [ ] **Step 4: 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md
```

Expected: 위반 0.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-guide README.md practice/mcp-security-authn-official/README.md
git commit -m "docs(guide): 10장과 authz practice를 안내서·부록·입구 문서에 잇기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 마지막 확인

- [ ] **Step 1: 모든 테스트**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for d in practice/mcp-security-authz/{auth-server,shop-mcp-server,shop-agent,local-client} \
         practice/mcp-security-authn-official/{auth-server,shop-mcp-server,shop-agent,local-client}; do
  (cd "$d" && ./gradlew -q cleanTest test && echo "$d OK")
done
python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
```

Expected: 여덟 줄 모두 `OK`, 스킬 테스트 통과. official은 바뀌지 않았어야 한다(`git diff main -- practice/mcp-security-authn-official`에는 README 한 줄만).

- [ ] **Step 2: 문서 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md
```

Expected: 위반 0, 다이어그램 PNG 최신.

- [ ] **Step 3: 커밋할 것이 남았는지 본다**

```bash
git status --short
```

Expected: 비어 있다(`logs/`, `build/`는 `.gitignore`가 가린다).
