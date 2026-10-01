# mcp-stateless-handle (stateless와 handle) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mcp-security-authz`를 복사해 MCP Server를 session 없이(stateless) 돌리고, 장바구니 상태를 서버가 만든 handle(`<sub>:<handle>` 저장소)로 다루는 `practice/mcp-stateless-handle`과 안내서 11장을 만든다.

**Architecture:** 네 module(auth-server, shop-mcp-server, shop-agent, local-client)을 authz에서 복사한다. MCP Server는 `spring.ai.mcp.server.protocol: STATELESS`로 돌고, 직접 만든 `WebMvcStatelessServerTransport` bean의 `contextExtractor`가 token의 `sub`·`client_id`를 `McpTransportContext`에 넣는다. 장바구니 tool은 그 값으로 `BasketStore`에서 `<sub>:<handle>` key를 찾고, 결과를 `CallToolResult`(text + `structuredContent`, 오류는 `isError`)로 돌려준다. agent는 내부 history를 끈 `ToolCallingAdvisor` 안쪽에 `MessageChatMemoryAdvisor`를 두어 tool 결과까지 사용자별로 기억하고, step-up으로 끊긴 turn은 기억에서 되돌린다. `local-client`는 장바구니 시나리오를 돌며 `checkout`에서 step-up한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7.1.1(Spring Authorization Server 포함), Spring AI 2.0.1, MCP Java SDK 2.0.1, Jackson 3(`tools.jackson`), JUnit 5, MockMvc, bash + curl(캡처).

**Spec:** `docs/superpowers/specs/2026-10-01-mcp-stateless-handle-design.md`

## Global Constraints

- practice 폴더: `practice/mcp-stateless-handle/`, module 네 개: `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`.
- package: `dev.starryeye.stateless.authserver`, `dev.starryeye.stateless.mcpserver`, `dev.starryeye.stateless.agent`, `dev.starryeye.stateless.localclient`. 하위 package는 authz와 같은 계층별 구성이고, 새 package는 MCP Server의 `basket`(장바구니 저장소)과 `security`(`McpCaller`)다.
- 포트: auth-server 9040, shop-mcp-server 8151(`/mcp`), shop-agent 8150. `local-client`의 loopback redirect 등록값은 `http://127.0.0.1:8123/callback`(authz와 같음).
- `client_id`: `stateless-shop-agent`(secret `stateless-shop-agent-secret`, confidential), `local-mcp-client`(public). cookie: `STATELESSAUTHSESSIONID`, `STATELESSAGENTSESSIONID`. 계정 `user`/`password`, `user2`/`password`. agent의 registration id는 `authserver`.
- scope: `products:read`(기본, 모든 MCP 요청), `products:write`(`updateStock`), `orders:write`(`checkout`). scope 계층은 없다. PRM의 `scopes_supported`와 `401`의 `scope`는 `products:read`만.
- 새 tool: `createBasket()`, `addItem(basketId, productId, quantity)`, `getBasket(basketId)`, `checkout(basketId)`. handle은 `bsk_` + 128bit 무작위 base64url(22자). 장바구니 TTL 30분, 만료·결제된 항목은 30분 더 남긴다, 사용자당 열린 장바구니 5개.
- 버전: Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1. authz·official·chat-memory·community 코드와 버전은 건드리지 않는다.
- 요청 형식은 2025-11-25 그대로다. 2026-07-28의 `_meta`·`server/discover`·`Mcp-Name`은 다루지 않는다.
- Gradle은 `JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)`로 돌린다.
- 서버를 띄웠으면 끝난 뒤 포트로 내린다: `lsof -ti tcp:PORT -sTCP:LISTEN | xargs kill`(`-sTCP:LISTEN` 없이 쓰면 연결된 다른 process까지 죽는다). `run.sh`는 background로 돌린다.
- `.superpowers/`, `logs/`, `build/`, `.gradle/`은 절대 `git add`하지 않는다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 코드 주석·javadoc·문서는 저장소 스킬 `.claude/skills/writing-practice-docs/SKILL.md` 문체를 따른다: 기술 용어는 흔한 말도 영어(token, client, scope, consent, login, browser, header, parameter, session, handle…), 영어·코드 뒤 조사는 붙여 쓴다("handle을"), 번역 어투 금지, 한 줄에 한 문장, 이유를 규칙보다 먼저. 문서의 범위 `~`는 `\~`로 쓴다(검사기 `tilde` 규칙).
- 안내서 장 본문에는 MUST 같은 요구 수준 단어·캡처 번호·테스트 메서드 이름·HTML을 쓰지 않는다. 요구 수준은 장 끝 "명세 근거" 표에만. 부록(`reference-*.md`)은 예외.
- 제품 예시는 OpenAI(ChatGPT, Codex CLI)와 Anthropic(claude.ai, Claude Desktop, Claude Code(CLI)) 것만 든다.

## Review Focus

1. 다른 사용자가 알아낸 handle로 부르면 장바구니 내용이 새지 않고 "찾을 수 없다"가 온다. 모르는 handle과 구별되지 않는다. — Task 4 `다른_사용자는_같은_handle을_찾지_못한다`, Task 5 `다른_사용자의_handle은_찾을_수_없다는_tool_오류다`.
2. 같은 장바구니를 두 번 결제해도 재고는 한 번만 준다. 두 번째는 "이미 주문했다"다. — Task 4 `결제한_장바구니는_다시_결제할_수_없다`, Task 5 `같은_handle로_두_번_결제하면_두_번째는_이미_주문이다`.
3. 결제 중 재고가 모자란 상품이 있으면 아무 재고도 줄지 않고 장바구니는 열린 채로 남는다. — Task 5 `재고가_모자라면_아무것도_줄지_않고_장바구니가_열려_있다`.
4. 본인 장바구니가 만료되면 "만료되었다"가 온다(모델이 새로 만들어 이어 갈 수 있게). 모양이 틀린 handle(공백, 다른 접두어)은 "찾을 수 없다"다. — Task 4 `만료된_장바구니는_만료_오류다`, `모양이_틀린_handle은_찾을_수_없다`.
5. `checkout`의 step-up으로 끊긴 turn은 대화 기억에 "결과 없는 tool 호출"을 남기지 않는다. — Task 7 `step_up으로_끊긴_turn은_기억에서_되돌린다`.

---

### Task 1: authz를 복사해 `practice/mcp-stateless-handle`을 만들고 patch 버전으로 올린다

**Files:**
- Create: `practice/mcp-stateless-handle/**` (authz의 `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`, `run.sh`, `stop.sh`, `.gitignore`. `README.md`·`diagrams/`는 복사하지 않는다 — Task 10에서 새로 쓴다)
- Modify: 네 module의 `build.gradle`(버전)

**Interfaces:**
- Produces: 이름·포트가 바뀌고 patch 버전으로 올라간 네 module. authz와 같은 테스트가 모두 통과한다.

- [ ] **Step 1: 복사하고 package 폴더를 옮긴다**

저장소 최상위 폴더에서:

```bash
set -euo pipefail
SRC=practice/mcp-security-authz
DST=practice/mcp-stateless-handle
test ! -e "$DST"
rsync -a --exclude build --exclude .gradle --exclude .idea --exclude logs --exclude out --exclude bin \
  --exclude README.md --exclude diagrams "$SRC/" "$DST/"
for m in auth-server shop-mcp-server shop-agent local-client; do
  for s in main test; do
    d="$DST/$m/src/$s/java/dev/starryeye"
    if [ -d "$d/authz" ]; then mv "$d/authz" "$d/stateless"; fi
  done
done
```

- [ ] **Step 2: 이름과 포트를 바꾼다**

```bash
python3 - <<'EOF'
import pathlib, re
root = pathlib.Path('practice/mcp-stateless-handle')
rules = [
    (r'dev\.starryeye\.authz\.', 'dev.starryeye.stateless.'),
    (r'authz-shop-agent-secret', 'stateless-shop-agent-secret'),
    (r'authz-shop-agent', 'stateless-shop-agent'),
    (r'authz_shop_agent_', 'stateless_shop_agent_'),
    (r'authz-shop-mcp-server', 'stateless-shop-mcp-server'),
    (r'authz-auth-server', 'stateless-auth-server'),
    (r'AUTHZAUTHSESSIONID', 'STATELESSAUTHSESSIONID'),
    (r'AUTHZAGENTSESSIONID', 'STATELESSAGENTSESSIONID'),
    (r'<title>authz shop-agent</title>', '<title>stateless shop-agent</title>'),
    (r'(?<!\d)9030(?!\d)', '9040'),
    (r'(?<!\d)8141(?!\d)', '8151'),
    (r'(?<!\d)8140(?!\d)', '8150'),
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
grep -rIn "authz\|AUTHZ\|9030\|8141\|8140" practice/mcp-stateless-handle --exclude-dir=build --exclude-dir=.gradle || echo "남은 것 없음"
```

Expected: 남은 줄은 주석에서 authz practice나 안내서 10장을 가리키는 설명뿐이다(예: "authz practice와 같다"). 이름·포트·package로 쓰인 것이 남으면 위 규칙의 새 이름으로 고친다. 주석 속 "authz"는 이 practice가 authz를 이어받았다는 설명이면 그대로 두고, 이 practice 자신을 가리키면 "이 practice"로 고친다.

- [ ] **Step 3: 버전을 올린다**

네 module의 `build.gradle`에서:
- `id 'org.springframework.boot' version '4.1.0'` → `'4.1.1'`
- `set('springAiVersion', "2.0.0")` → `"2.0.1"`(있는 module만)
- `implementation 'io.modelcontextprotocol.sdk:mcp:2.0.0'` → `'io.modelcontextprotocol.sdk:mcp:2.0.1'`(있는 module만: shop-agent, local-client)

```bash
cd practice/mcp-stateless-handle
grep -n "4.1.0\|2.0.0\|springAiVersion\|modelcontextprotocol" */build.gradle
sed -i '' "s/id 'org.springframework.boot' version '4.1.0'/id 'org.springframework.boot' version '4.1.1'/" */build.gradle
sed -i '' "s/set('springAiVersion', \"2.0.0\")/set('springAiVersion', \"2.0.1\")/" */build.gradle
sed -i '' "s/io.modelcontextprotocol.sdk:mcp:2.0.0/io.modelcontextprotocol.sdk:mcp:2.0.1/" */build.gradle
grep -n "4.1.1\|2.0.1" */build.gradle
```

Expected: 네 module 모두 Boot 4.1.1, Spring AI를 쓰는 module은 2.0.1, SDK를 직접 쓰는 두 module은 2.0.1.

- [ ] **Step 4: 네 module의 테스트를 돌린다**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for m in auth-server shop-mcp-server shop-agent local-client; do
  (cd practice/mcp-stateless-handle/$m && ./gradlew -q test && echo "$m OK")
done
```

Expected: 네 줄 모두 `OK`. 테스트 개수는 authz와 같다(보고서에 module별 개수를 적는다).
실패하면 버전 차이 때문인지 먼저 본다. 알려진 변경은 둘이다.
- Spring AI 2.0.1 [#6716]: `McpSyncHttpClientRequestCustomizer` bean을 transport에 깔고, 그 뒤에 `McpClientCustomizer`를 적용한다. authz는 `McpClientCustomizer`에서 `httpRequestCustomizer(...)`를 직접 부르므로 우선한다. token 붙이기 테스트가 깨지면 이 순서를 확인한다.
- Spring AI 2.0.1 [#6534]: `@McpTool` 예외 처리가 `@Tool`과 같아졌다. tool 예외 모양을 확인하는 테스트가 깨지면 새 동작에 맞게 기대값만 고치고, 보고서에 무엇이 달라졌는지 적는다.

- [ ] **Step 5: 커밋**

```bash
git status --short practice/mcp-stateless-handle | grep -E '/(build|logs|\.gradle|\.idea)/' && echo "제외할 파일이 섞였다" || true
git add practice/mcp-stateless-handle
git commit -m "feat(stateless): authz를 복사해 mcp-stateless-handle practice 시작, patch 버전으로 올림

Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: auth-server에 `orders:write` scope와 두 번째 계정을 더한다

**Files:**
- Modify: `practice/mcp-stateless-handle/auth-server/src/main/resources/application.yml`
- Modify: `practice/mcp-stateless-handle/auth-server/src/main/java/dev/starryeye/stateless/authserver/config/UserConfig.java`
- Test: `practice/mcp-stateless-handle/auth-server/src/test/java/dev/starryeye/stateless/authserver/AuthorizationServerStandardTest.java`

**Interfaces:**
- Produces: 두 client의 scope에 `orders:write`. 계정 `user2`/`password`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`AuthorizationServerStandardTest`에 두 테스트를 더한다. authz의 `step_up_에서_새_scope를_허락하면_두_scope가_모두_담긴다`와 같은 helper(`기밀클라이언트_인가`, `기밀클라이언트_인가요청_URI`, `토큰요청`, `인가코드교환`, `응답파라미터`, `토큰의_scope`)를 쓴다.

```java
	@Test
	void agent_client는_orders_write를_허락받아_token에_담는다() throws Exception {
		UriComponents response = 기밀클라이언트_인가(
				기밀클라이언트_인가요청_URI("openid products:read orders:write"), "products:read", "orders:write");
		String body = 토큰요청(인가코드교환(응답파라미터(response, "code"), RESOURCE), 200);

		assertThat(토큰의_scope(JsonPath.read(body, "$.access_token")))
				.containsExactlyInAnyOrder("openid", "products:read", "orders:write");
	}

	@Test
	void 두_번째_계정_user2로_login할_수_있다() throws Exception {
		this.mockMvc.perform(formLogin("/login").user("user2").password("password"))
				.andExpect(authenticated().withUsername("user2"));
	}
```

`formLogin`·`authenticated`는 `org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin`, `org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated`를 static import한다(`formLogin`은 CSRF token을 함께 보낸다).

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-stateless-handle/auth-server && ./gradlew test --tests '*AuthorizationServerStandardTest*'
```

Expected: 두 테스트가 FAIL(`invalid_scope`, 로그인 실패).

- [ ] **Step 3: scope와 계정을 더한다**

`application.yml`의 두 client `scopes`에 `orders:write`를 더한다.

```yaml
              scopes:
                - openid
                # MCP Server의 tool을 부를 때 쓰는 scope다. 조회, 재고 변경, 주문을 나눈다(안내서 10·11장).
                - products:read
                - products:write
                - orders:write
```

`local-mcp-client`도 같다(`openid` 없이 `products:read`, `products:write`, `orders:write`).

`UserConfig`:

```java
/**
 * 학습용 사용자 두 명이다. user / password, user2 / password로 login한다.
 * user2는 다른 사용자의 장바구니 handle을 써 보는 데 쓴다(안내서 11장).
 */
@Configuration
public class UserConfig {

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername("user").password("{noop}password").roles("USER").build(),
                User.withUsername("user2").password("{noop}password").roles("USER").build()
        );
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS(authz 개수 + 2).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-stateless-handle/auth-server
git commit -m "feat(stateless): auth-server에 orders:write scope와 user2 계정을 더한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: MCP Server를 stateless로 돌리고 tool이 호출한 사용자를 알게 한다

**Files:**
- Modify: `practice/mcp-stateless-handle/shop-mcp-server/src/main/resources/application.yml`
- Create: `practice/mcp-stateless-handle/shop-mcp-server/src/main/java/dev/starryeye/stateless/mcpserver/security/McpCaller.java`
- Modify: `practice/mcp-stateless-handle/shop-mcp-server/src/main/java/dev/starryeye/stateless/mcpserver/config/McpTransportConfig.java`
- Modify: `practice/mcp-stateless-handle/shop-mcp-server/src/test/java/dev/starryeye/stateless/mcpserver/McpScopeTest.java`, `McpAuthorizationStandardTest.java`, `ShopMcpServerApplicationTests.java`
- Create: `practice/mcp-stateless-handle/shop-mcp-server/src/test/java/dev/starryeye/stateless/mcpserver/StatelessTransportTest.java`
- Create: `practice/mcp-stateless-handle/shop-mcp-server/src/test/java/dev/starryeye/stateless/mcpserver/security/McpCallerTest.java`

**Interfaces:**
- Produces:
  - `McpCaller(String subject, String clientId)` record.
  - `static McpTransportContext McpCaller.context(ServerRequest request)` — `request.principal()`이 `JwtAuthenticationToken`이면 `{"sub": subject, "client_id": clientId}`, 아니면 `McpTransportContext.EMPTY`.
  - `static McpCaller McpCaller.from(McpTransportContext context)` — `sub`가 없으면 `IllegalStateException("인증된 사용자가 없다")`.
  - `McpTransportConfig#webMvcStatelessServerTransport(JsonMapper, McpServerStreamableHttpProperties)` bean(Spring AI 자동 설정 bean을 대신한다).

- [ ] **Step 1: 실패하는 테스트를 쓴다 — stateless transport**

`StatelessTransportTest.java`:

```java
package dev.starryeye.stateless.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * session 없이 도는 MCP Server가 실제 요청에 어떻게 답하는지 본다.
 * session header가 없고, GET stream과 DELETE가 없으며, session 없이 보낸 tools/call도 처리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class StatelessTransportTest {

	@Autowired
	MockMvc mockMvc;

	static final String GET_STOCK = """
			{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getStock",\
			"arguments":{"productId":"p1"}}}""";

	@Test
	void initialize_응답에_session_header가_없다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(McpScopeTest.토큰("products:read"), McpAuthorizationStandardTest.INITIALIZE))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Mcp-Session-Id"));
	}

	@Test
	void session_없이_보낸_tools_call도_처리한다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(McpScopeTest.토큰("products:read"), GET_STOCK))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("p1")));
	}

	@Test
	void GET_stream은_405다() throws Exception {
		this.mockMvc.perform(get("/mcp")
						.header("Accept", "text/event-stream")
						.header("Host", McpAuthorizationStandardTest.HOST)
						.header("Authorization", "Bearer " + McpScopeTest.토큰("products:read")))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void DELETE는_성공하지_않는다() throws Exception {
		int status = this.mockMvc.perform(delete("/mcp")
						.header("Host", McpAuthorizationStandardTest.HOST)
						.header("Authorization", "Bearer " + McpScopeTest.토큰("products:read")))
				.andReturn().getResponse().getStatus();
		// stateless transport의 router에는 GET·POST만 있다. 실제 값(404나 405)은 보고서와 안내서에 적는다.
		assertThat(status).isGreaterThanOrEqualTo(400);
	}
}
```

`McpScopeTest`의 `mcp(...)`와 `토큰(...)`이 `static`이 아니거나 접근할 수 없으면 package-private `static`으로 바꾼다.

- [ ] **Step 2: 실패하는 테스트를 쓴다 — `McpCaller`**

`security/McpCallerTest.java`:

```java
package dev.starryeye.stateless.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCallerTest {

	static ServerRequest 요청(Object principal) {
		MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/mcp");
		if (principal instanceof java.security.Principal p) {
			servlet.setUserPrincipal(p);
		}
		return ServerRequest.create(servlet, List.of());
	}

	static JwtAuthenticationToken 인증(String subject, String clientId) {
		Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (clientId != null) {
			jwt.claim("client_id", clientId);
		}
		return new JwtAuthenticationToken(jwt.build());
	}

	@Test
	void token의_sub와_client_id를_transport_context에_넣는다() {
		McpTransportContext context = McpCaller.context(요청(인증("user", "local-mcp-client")));

		assertThat(McpCaller.from(context)).isEqualTo(new McpCaller("user", "local-mcp-client"));
	}

	@Test
	void client_id가_없는_token이면_빈_값이다() {
		McpTransportContext context = McpCaller.context(요청(인증("user", null)));

		assertThat(McpCaller.from(context)).isEqualTo(new McpCaller("user", ""));
	}

	@Test
	void 인증이_없으면_빈_context이고_사용자를_꺼내면_예외다() {
		McpTransportContext context = McpCaller.context(요청(null));

		assertThat(context).isSameAs(McpTransportContext.EMPTY);
		assertThatThrownBy(() -> McpCaller.from(context)).isInstanceOf(IllegalStateException.class)
				.hasMessage("인증된 사용자가 없다");
	}

	@Test
	void sub가_빈_문자열이면_사용자가_없는_것이다() {
		assertThatThrownBy(() -> McpCaller.from(McpTransportContext.create(Map.of("sub", " "))))
				.isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **Step 3: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-stateless-handle/shop-mcp-server && ./gradlew test --tests '*StatelessTransportTest*' --tests '*McpCallerTest*'
```

Expected: `McpCallerTest`는 컴파일 오류(`McpCaller` 없음). 그것을 빼고 돌리면 `StatelessTransportTest`의 session header·tools/call·GET 테스트가 FAIL(지금은 stateful).

- [ ] **Step 4: stateless로 바꾸고 `McpCaller`와 transport bean을 만든다**

`application.yml`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: stateless-shop-mcp-server
        version: 0.0.1
        # session 없이 돈다. initialize 응답에 Mcp-Session-Id를 주지 않고, GET stream과 DELETE가 없다.
        # 요청 하나하나가 token만으로 처리되므로, 공유 MCP client를 여러 사용자가 써도 섞일 상태가 없다(안내서 11장).
        protocol: STATELESS
        # SYNC다. @McpTool 메서드가 Mono를 반환하면 오류 없이 등록에서 빠진다.
        type: SYNC
```

`security/McpCaller.java`:

```java
package dev.starryeye.stateless.mcpserver.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;
import java.util.Objects;

/**
 * tool을 부른 사용자다. token의 {@code sub}와 {@code client_id}다.
 *
 * <p>stateless transport는 요청마다 {@link #context(ServerRequest)}로 이 값을 {@link McpTransportContext}에 넣는다.
 * tool 메서드는 {@code McpTransportContext} 인자로 받아 {@link #from(McpTransportContext)}로 꺼낸다.
 * handle을 사용자에게 묶는 key의 {@code <sub>}가 여기서 온다(안내서 11장).
 * client가 보낸 값이 아니라 검증한 token에서 꺼낸 값이라는 점이 중요하다.
 */
public record McpCaller(String subject, String clientId) {

	static final String SUBJECT = "sub";

	static final String CLIENT_ID = "client_id";

	/** Spring Security가 검증한 token을 읽는다. 인증이 없으면 빈 context다. */
	public static McpTransportContext context(ServerRequest request) {
		return request.principal()
				.filter(JwtAuthenticationToken.class::isInstance)
				.map(JwtAuthenticationToken.class::cast)
				.map(authentication -> McpTransportContext.create(Map.of(
						SUBJECT, authentication.getToken().getSubject(),
						CLIENT_ID, Objects.toString(authentication.getToken().getClaimAsString(CLIENT_ID), ""))))
				.orElse(McpTransportContext.EMPTY);
	}

	public static McpCaller from(McpTransportContext context) {
		if (!(context.get(SUBJECT) instanceof String subject) || subject.isBlank()) {
			throw new IllegalStateException("인증된 사용자가 없다");
		}
		return new McpCaller(subject, Objects.toString(context.get(CLIENT_ID), ""));
	}
}
```

`McpTransportConfig`에 bean을 더한다(import: `io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport`, `io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper`, `dev.starryeye.stateless.mcpserver.security.McpCaller`):

```java
	/**
	 * Spring AI 자동 설정의 stateless transport bean을 대신한다(그 bean은 {@code @ConditionalOnMissingBean}이다).
	 * 바꾸는 것은 {@code contextExtractor} 하나다.
	 * 요청마다 token의 사용자를 {@code McpTransportContext}에 넣어, tool이 누가 불렀는지 알게 한다.
	 */
	@Bean
	public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
			@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
		return WebMvcStatelessServerTransport.builder()
				.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
				.messageEndpoint(properties.getMcpEndpoint())
				.contextExtractor(McpCaller::context)
				.build();
	}
```

클래스 javadoc의 "Streamable HTTP transport는 Spring AI 자동 구성 bean을 그대로 쓴다"를 "stateless transport bean은 사용자를 읽는 `contextExtractor`를 넣으려고 직접 만든다"로 고친다.

- [ ] **Step 5: session을 전제한 기존 테스트를 고친다**

```bash
grep -rn "Mcp-Session-Id\|Session ID\|McpServerFeatures" src/test
```

- `McpAuthorizationStandardTest`의 `aud_가_맞는_토큰이면_initialize_가_성공한다`: `.andExpect(header().exists("Mcp-Session-Id"))` → `.andExpect(header().doesNotExist("Mcp-Session-Id"))`.
- `McpScopeTest`의 `조회_scope_token의_initialize는_filter를_지나_transport가_답한다`: 같은 방식으로 `doesNotExist`.
- `McpScopeTest`의 `filter가_읽은_본문을_transport가_다시_읽는다`: stateless에서는 "Session ID missing"이 없다. 본문이 transport까지 온전히 갔다는 것을 `getStock` 결과로 확인한다.

```java
	@Test
	void filter가_읽은_본문을_transport가_다시_읽는다() throws Exception {
		// filter가 본문을 읽은 뒤에도 transport가 같은 본문을 읽어 tool을 실행한다.
		this.mockMvc.perform(mcp(토큰("products:read"), StatelessTransportTest.GET_STOCK))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("p1")));
	}
```

- `McpScopeTest`의 `유효하지_않은_UTF8_바이트가_섞인_재고_변경_요청은_세션이_있어도_재고를_바꾸지_못한다`: 이름을 `유효하지_않은_UTF8_바이트가_섞인_재고_변경_요청은_재고를_바꾸지_못한다`로 바꾸고, `initialize`로 session을 만드는 줄과 `.header("Mcp-Session-Id", sessionId)`를 지운다. 나머지(200이 아니고 재고가 그대로)는 같다.
- `ShopMcpServerApplicationTests`의 `MCP_툴이_실제로_등록된다`: stateless에서는 tool spec 타입이 다르다.

```java
	@Autowired
	ObjectProvider<List<McpStatelessServerFeatures.SyncToolSpecification>> toolSpecificationLists;
```

(import `io.modelcontextprotocol.server.McpStatelessServerFeatures`, `McpServerFeatures` import는 지운다.)

- [ ] **Step 6: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS. `DELETE는_성공하지_않는다`의 실제 status를 보고서에 적는다.

- [ ] **Step 7: 커밋**

```bash
git add practice/mcp-stateless-handle/shop-mcp-server
git commit -m "feat(stateless): MCP Server를 session 없이 돌리고 tool에 호출한 사용자를 넘긴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: 장바구니 저장소 `BasketStore` — `<sub>:<handle>`, 만료, 개수 제한

**Files:**
- Create: `practice/mcp-stateless-handle/shop-mcp-server/src/main/java/dev/starryeye/stateless/mcpserver/basket/BasketStore.java`
- Create: `.../mcpserver/basket/BasketView.java`
- Create: `.../mcpserver/basket/BasketException.java`
- Test: `practice/mcp-stateless-handle/shop-mcp-server/src/test/java/dev/starryeye/stateless/mcpserver/basket/BasketStoreTest.java`

**Interfaces:**
- Produces (Task 5가 쓴다):
  - `public record BasketView(String handle, Instant expiresAt, Map<String, Integer> items)` — `items`는 상품 ID → 수량, 담은 순서를 지킨다(복사본).
  - `public class BasketException extends RuntimeException` + `public enum Reason { NOT_FOUND, EXPIRED, ORDERED, EMPTY, LIMIT }`, `Reason reason()`. 메시지는 모델에게 그대로 보여 줄 한국어 문장이다.
  - `public class BasketStore`:
    - `public BasketStore(Clock clock)`
    - `public synchronized BasketView create(String subject)`
    - `public synchronized BasketView addItem(String subject, String handle, String productId, int quantity)`
    - `public synchronized BasketView view(String subject, String handle)`
    - `public synchronized String checkout(String subject, String handle, Consumer<Map<String, Integer>> reserveStock)` — 주문 번호(`ord-1001`부터)를 돌려준다. `reserveStock`이 예외를 던지면 장바구니는 열린 채로 남고 예외가 그대로 올라간다.
    - 상수 `TTL = Duration.ofMinutes(30)`, `KEEP_AFTER_CLOSE = Duration.ofMinutes(30)`, `MAX_OPEN_PER_USER = 5`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`basket/BasketStoreTest.java`:

```java
package dev.starryeye.stateless.mcpserver.basket;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BasketStoreTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-01T00:00:00Z");

		void 지나감(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}

	시계 clock = new 시계();

	BasketStore store = new BasketStore(this.clock);

	List<Map<String, Integer>> reserved = new ArrayList<>();

	static BasketException.Reason 이유(Runnable call) {
		try {
			call.run();
		}
		catch (BasketException ex) {
			return ex.reason();
		}
		throw new AssertionError("BasketException이 나야 한다");
	}

	@Test
	void 새_장바구니의_handle은_bsk_와_22자이고_30분_뒤_만료된다() {
		BasketView basket = this.store.create("user");

		assertThat(basket.handle()).matches("bsk_[A-Za-z0-9_-]{22}");
		assertThat(basket.expiresAt()).isEqualTo(this.clock.instant().plus(Duration.ofMinutes(30)));
		assertThat(basket.items()).isEmpty();
	}

	@Test
	void 만들_때마다_다른_handle이다() {
		assertThat(this.store.create("user").handle()).isNotEqualTo(this.store.create("user").handle());
	}

	@Test
	void 같은_상품을_두_번_담으면_수량이_더해진다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 1);

		BasketView basket = this.store.addItem("user", handle, "p4", 2);

		assertThat(basket.items()).containsExactly(Map.entry("p4", 3));
	}

	@Test
	void 다른_사용자는_같은_handle을_찾지_못한다() {
		String handle = this.store.create("user").handle();

		assertThat(이유(() -> this.store.view("user2", handle))).isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(이유(() -> this.store.addItem("user2", handle, "p4", 1))).isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(이유(() -> this.store.checkout("user2", handle, this.reserved::add)))
				.isEqualTo(BasketException.Reason.NOT_FOUND);
		assertThat(this.reserved).isEmpty();
	}

	@Test
	void 다른_사용자의_handle과_모르는_handle은_같은_오류_문장이다() {
		String handle = this.store.create("user").handle();
		String unknown = "bsk_" + "A".repeat(22);

		BasketException other = catchBasket(() -> this.store.view("user2", handle));
		BasketException missing = catchBasket(() -> this.store.view("user2", unknown));

		assertThat(other.getMessage().replace(handle, "<id>")).isEqualTo(missing.getMessage().replace(unknown, "<id>"));
	}

	@Test
	void 만료된_장바구니는_만료_오류다() {
		String handle = this.store.create("user").handle();
		this.clock.지나감(Duration.ofMinutes(30));

		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.EXPIRED);
	}

	@Test
	void 만료되고_30분이_더_지나면_지워져_찾을_수_없다() {
		String handle = this.store.create("user").handle();
		this.clock.지나감(Duration.ofMinutes(60));

		assertThat(이유(() -> this.store.view("user", handle))).isEqualTo(BasketException.Reason.NOT_FOUND);
	}

	@Test
	void 결제한_장바구니는_다시_결제할_수_없다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p4", 1);

		String orderId = this.store.checkout("user", handle, this.reserved::add);
		BasketException again = catchBasket(() -> this.store.checkout("user", handle, this.reserved::add));

		assertThat(orderId).isEqualTo("ord-1001");
		assertThat(again.reason()).isEqualTo(BasketException.Reason.ORDERED);
		assertThat(again.getMessage()).contains("ord-1001");
		assertThat(this.reserved).containsExactly(Map.of("p4", 1));
	}

	@Test
	void 재고_확보가_실패하면_장바구니가_열린_채로_남는다() {
		String handle = this.store.create("user").handle();
		this.store.addItem("user", handle, "p6", 5);

		assertThatThrownBy(() -> this.store.checkout("user", handle, items -> {
			throw new IllegalStateException("재고가 모자라 주문할 수 없습니다: p6");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(this.store.view("user", handle).items()).containsExactly(Map.entry("p6", 5));
	}

	@Test
	void 빈_장바구니는_결제할_수_없다() {
		String handle = this.store.create("user").handle();

		assertThat(이유(() -> this.store.checkout("user", handle, this.reserved::add)))
				.isEqualTo(BasketException.Reason.EMPTY);
	}

	@Test
	void 사용자당_열린_장바구니는_5개까지다() {
		for (int i = 0; i < 5; i++) {
			this.store.create("user");
		}

		assertThat(이유(() -> this.store.create("user"))).isEqualTo(BasketException.Reason.LIMIT);
		assertThat(this.store.create("user2").handle()).startsWith("bsk_");
	}

	@Test
	void 결제했거나_만료된_장바구니는_개수에_세지_않는다() {
		String ordered = this.store.create("user").handle();
		this.store.addItem("user", ordered, "p4", 1);
		this.store.checkout("user", ordered, this.reserved::add);
		for (int i = 0; i < 4; i++) {
			this.store.create("user");
		}
		this.clock.지나감(Duration.ofMinutes(30));

		for (int i = 0; i < 5; i++) {
			this.store.create("user");
		}
	}

	@Test
	void 모양이_틀린_handle은_찾을_수_없다() {
		String handle = this.store.create("user").handle();

		for (String wrong : new String[] { " " + handle, handle + " ", "basket_1", "", null }) {
			assertThat(이유(() -> this.store.view("user", wrong))).isEqualTo(BasketException.Reason.NOT_FOUND);
		}
	}

	static BasketException catchBasket(Runnable call) {
		try {
			call.run();
		}
		catch (BasketException ex) {
			return ex;
		}
		throw new AssertionError("BasketException이 나야 한다");
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-stateless-handle/shop-mcp-server && ./gradlew test --tests '*BasketStoreTest*'
```

Expected: 컴파일 오류(`BasketStore` 없음).

- [ ] **Step 3: 구현한다**

`basket/BasketView.java`:

```java
package dev.starryeye.stateless.mcpserver.basket;

import java.time.Instant;
import java.util.Map;

/** tool에 돌려주는 장바구니의 한 시점 모습이다. {@code items}는 상품 ID → 수량이고 담은 순서를 지킨다. */
public record BasketView(String handle, Instant expiresAt, Map<String, Integer> items) {
}
```

`basket/BasketException.java`:

```java
package dev.starryeye.stateless.mcpserver.basket;

import java.util.regex.Pattern;

/**
 * 장바구니를 쓸 수 없는 이유다. 메시지는 모델이 읽고 다음 행동을 정할 수 있는 문장이다.
 *
 * <p>다른 사용자의 handle은 그 사용자의 key로 찾으므로 "모르는 handle"과 같은 {@link Reason#NOT_FOUND}가 된다.
 * 그래서 만료·결제 완료를 따로 알려도 다른 사용자에게 handle이 있는지 드러나지 않는다.
 */
public class BasketException extends RuntimeException {

	public enum Reason { NOT_FOUND, EXPIRED, ORDERED, EMPTY, LIMIT }

	static final Pattern HANDLE = Pattern.compile("bsk_[A-Za-z0-9_-]{22}");

	private final Reason reason;

	private BasketException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

	static BasketException notFound(String handle) {
		return new BasketException(Reason.NOT_FOUND,
				"장바구니 %s를 찾을 수 없습니다. createBasket으로 새 장바구니를 만드세요.".formatted(shown(handle)));
	}

	static BasketException expired(String handle) {
		return new BasketException(Reason.EXPIRED,
				"장바구니 %s는 만료되었습니다(만든 뒤 30분). createBasket으로 새 장바구니를 만드세요.".formatted(handle));
	}

	static BasketException ordered(String handle, String orderId) {
		return new BasketException(Reason.ORDERED,
				"장바구니 %s는 이미 주문했습니다(주문 번호 %s).".formatted(handle, orderId));
	}

	static BasketException empty(String handle) {
		return new BasketException(Reason.EMPTY,
				"장바구니 %s가 비어 있습니다. addItem으로 상품을 담으세요.".formatted(handle));
	}

	static BasketException limit() {
		return new BasketException(Reason.LIMIT,
				"열린 장바구니는 %d개까지 만들 수 있습니다. 쓰던 장바구니를 이어 쓰세요.".formatted(BasketStore.MAX_OPEN_PER_USER));
	}

	/** 모델이 보낸 값이 handle 모양이 아니면 그대로 되풀이하지 않는다. */
	private static String shown(String handle) {
		return (handle != null && HANDLE.matcher(handle).matches()) ? handle : "(올바르지 않은 ID)";
	}
}
```

`basket/BasketStore.java`:

```java
package dev.starryeye.stateless.mcpserver.basket;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 장바구니를 서버 메모리에 둔다. key는 {@code <sub>:<handle>}이다.
 *
 * <p>handle은 client에게 주는 이름일 뿐, 가졌다고 쓸 수 있는 권한이 아니다.
 * {@code sub}는 client가 보낸 값이 아니라 검증한 token에서 온다({@code McpCaller}).
 * 그래서 다른 사용자가 handle을 알아내도 자기 {@code sub}로 찾게 되어 아무것도 얻지 못한다(안내서 11장).
 *
 * <p>메모리 저장소라 서버 한 대에서만 맞다. 여러 대로 늘리면 Redis 같은 공유 저장소로 바꾼다.
 */
public class BasketStore {

	public static final Duration TTL = Duration.ofMinutes(30);

	/** 만료·결제된 장바구니를 이만큼 더 남겨 "만료"·"이미 주문"을 알려 준 뒤 지운다. */
	public static final Duration KEEP_AFTER_CLOSE = Duration.ofMinutes(30);

	public static final int MAX_OPEN_PER_USER = 5;

	private static final class Basket {

		final String handle;

		final String owner;

		final Instant expiresAt;

		final Map<String, Integer> items = new LinkedHashMap<>();

		String orderId;

		Instant closedAt;

		Basket(String handle, String owner, Instant expiresAt) {
			this.handle = handle;
			this.owner = owner;
			this.expiresAt = expiresAt;
		}

		boolean isOpen(Instant now) {
			return this.orderId == null && now.isBefore(this.expiresAt);
		}

		Instant endedAt() {
			return (this.orderId != null) ? this.closedAt : this.expiresAt;
		}

		BasketView view() {
			// 담은 순서를 지키는 읽기 전용 복사본이다(Map.copyOf는 순서를 지키지 않는다).
			return new BasketView(this.handle, this.expiresAt, Collections.unmodifiableMap(new LinkedHashMap<>(this.items)));
		}
	}

	private final Map<String, Basket> baskets = new HashMap<>();

	private final Clock clock;

	private final SecureRandom random;

	private long orderSequence = 1000;

	public BasketStore(Clock clock) {
		this(clock, new SecureRandom());
	}

	BasketStore(Clock clock, SecureRandom random) {
		this.clock = clock;
		this.random = random;
	}

	public synchronized BasketView create(String subject) {
		removeEnded();
		Instant now = this.clock.instant();
		long open = this.baskets.values().stream()
				.filter(basket -> basket.owner.equals(subject) && basket.isOpen(now))
				.count();
		if (open >= MAX_OPEN_PER_USER) {
			throw BasketException.limit();
		}
		Basket basket = new Basket(newHandle(), subject, now.plus(TTL));
		this.baskets.put(key(subject, basket.handle), basket);
		return basket.view();
	}

	public synchronized BasketView addItem(String subject, String handle, String productId, int quantity) {
		Basket basket = open(subject, handle);
		basket.items.merge(productId, quantity, Integer::sum);
		return basket.view();
	}

	public synchronized BasketView view(String subject, String handle) {
		return open(subject, handle).view();
	}

	/**
	 * 재고를 확보하고 장바구니를 닫는다.
	 * {@code reserveStock}이 예외를 던지면 장바구니는 열린 채로 남는다.
	 * 이 메서드 전체가 한 lock 안이라, 같은 장바구니를 두 번 결제할 수 없다.
	 */
	public synchronized String checkout(String subject, String handle, Consumer<Map<String, Integer>> reserveStock) {
		Basket basket = open(subject, handle);
		if (basket.items.isEmpty()) {
			throw BasketException.empty(handle);
		}
		reserveStock.accept(Map.copyOf(basket.items));
		basket.orderId = "ord-" + (++this.orderSequence);
		basket.closedAt = this.clock.instant();
		return basket.orderId;
	}

	private Basket open(String subject, String handle) {
		removeEnded();
		if (handle == null || !BasketException.HANDLE.matcher(handle).matches()) {
			throw BasketException.notFound(handle);
		}
		Basket basket = this.baskets.get(key(subject, handle));
		if (basket == null) {
			throw BasketException.notFound(handle);
		}
		if (basket.orderId != null) {
			throw BasketException.ordered(handle, basket.orderId);
		}
		if (!this.clock.instant().isBefore(basket.expiresAt)) {
			throw BasketException.expired(handle);
		}
		return basket;
	}

	private void removeEnded() {
		Instant now = this.clock.instant();
		this.baskets.values().removeIf(basket -> !now.isBefore(basket.endedAt().plus(KEEP_AFTER_CLOSE)));
	}

	/** 128bit 무작위 값을 base64url로 적는다(22자). 추측으로 맞힐 수 없게 {@link SecureRandom}을 쓴다. */
	private String newHandle() {
		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		return "bsk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String key(String subject, String handle) {
		return subject + ":" + handle;
	}
}
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*BasketStoreTest*'
```

Expected: 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-stateless-handle/shop-mcp-server
git commit -m "feat(stateless): 장바구니를 <sub>:<handle> key로 두는 BasketStore

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 장바구니 tool 네 개와 결제의 `orders:write`

**Files:**
- Create: `practice/mcp-stateless-handle/shop-mcp-server/src/main/java/dev/starryeye/stateless/mcpserver/tool/BasketTools.java`
- Modify: `.../mcpserver/repository/ProductRepository.java`(`reserve`)
- Modify: `.../mcpserver/config/McpTransportConfig.java`(`BasketStore` bean, `ToolScopeRegistry.scan(productTools, basketTools)`)
- Modify: `practice/mcp-stateless-handle/shop-mcp-server/src/test/java/dev/starryeye/stateless/mcpserver/ShopMcpServerApplicationTests.java`(등록 tool 7개)
- Modify: `.../test/.../tool/ToolScopeRegistryTest.java`
- Create: `.../test/.../tool/BasketToolsTest.java`
- Create: `.../test/.../BasketFlowTest.java`

**Interfaces:**
- Consumes: `McpCaller.from(McpTransportContext)`(Task 3), `BasketStore`·`BasketView`·`BasketException`(Task 4).
- Produces:
  - tool `createBasket()` → text + `structuredContent {"basketId": "...", "expiresAt": "..."}`.
  - tool `addItem(basketId, productId, quantity)`, `getBasket(basketId)` → text.
  - tool `checkout(basketId)`(`@RequiredScope("orders:write")`) → text + `structuredContent {"orderId": "ord-…"}`.
  - 쓸 수 없는 경우는 모두 `isError: true`와 한국어 문장.
  - `ProductRepository#reserve(Map<String, Integer> quantities)` — 하나라도 모자라면 `IllegalStateException("재고가 모자라 주문할 수 없습니다: p6")`이고 아무것도 줄이지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다 — tool 단위**

`tool/BasketToolsTest.java`:

```java
package dev.starryeye.stateless.mcpserver.tool;

import dev.starryeye.stateless.mcpserver.basket.BasketStore;
import dev.starryeye.stateless.mcpserver.repository.ProductRepository;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasketToolsTest {

	ProductRepository products = new ProductRepository();

	BasketTools tools = new BasketTools(new BasketStore(Clock.systemUTC()), this.products);

	static McpTransportContext 사용자(String subject) {
		return McpTransportContext.create(Map.of("sub", subject, "client_id", "test-client"));
	}

	static String 글(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	@SuppressWarnings("unchecked")
	String 새_장바구니(String subject) {
		CallToolResult created = this.tools.createBasket(사용자(subject));
		return (String) ((Map<String, Object>) created.structuredContent()).get("basketId");
	}

	int 재고(String productId) {
		return this.products.findById(productId).orElseThrow().stock();
	}

	@Test
	void createBasket은_handle을_글과_structuredContent로_돌려준다() {
		CallToolResult created = this.tools.createBasket(사용자("user"));

		assertThat(created.isError()).isFalse();
		String handle = 새_장바구니("user");
		assertThat(handle).startsWith("bsk_");
		assertThat(글(created)).contains("bsk_").contains("만료");
	}

	@Test
	void 담고_보면_상품과_합계가_보인다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p4", 2);

		CallToolResult basket = this.tools.getBasket(사용자("user"), handle);

		assertThat(basket.isError()).isFalse();
		assertThat(글(basket)).contains("p4").contains("인체공학 마우스").contains("118,000원");
	}

	@Test
	void 모르는_상품과_1보다_작은_수량은_tool_오류다() {
		String handle = 새_장바구니("user");

		assertThat(this.tools.addItem(사용자("user"), handle, "p999", 1).isError()).isTrue();
		assertThat(this.tools.addItem(사용자("user"), handle, "p4", 0).isError()).isTrue();
	}

	@Test
	void 다른_사용자의_handle은_찾을_수_없다는_tool_오류다() {
		String handle = 새_장바구니("user");

		CallToolResult result = this.tools.getBasket(사용자("user2"), handle);

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("찾을 수 없습니다");
	}

	@Test
	void 결제하면_재고가_줄고_주문_번호가_온다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p9", 2);
		int before = 재고("p9");

		CallToolResult ordered = this.tools.checkout(사용자("user"), handle);

		assertThat(ordered.isError()).isFalse();
		assertThat(글(ordered)).contains("ord-");
		assertThat(재고("p9")).isEqualTo(before - 2);
	}

	@Test
	void 같은_handle로_두_번_결제하면_두_번째는_이미_주문이다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p9", 1);
		this.tools.checkout(사용자("user"), handle);
		int after = 재고("p9");

		CallToolResult again = this.tools.checkout(사용자("user"), handle);

		assertThat(again.isError()).isTrue();
		assertThat(글(again)).contains("이미 주문");
		assertThat(재고("p9")).isEqualTo(after);
	}

	@Test
	void 재고가_모자라면_아무것도_줄지_않고_장바구니가_열려_있다() {
		String handle = 새_장바구니("user");
		this.tools.addItem(사용자("user"), handle, "p4", 1);
		this.tools.addItem(사용자("user"), handle, "p6", 5);
		int p4 = 재고("p4");

		CallToolResult result = this.tools.checkout(사용자("user"), handle);

		assertThat(result.isError()).isTrue();
		assertThat(글(result)).contains("p6");
		assertThat(재고("p4")).isEqualTo(p4);
		assertThat(this.tools.getBasket(사용자("user"), handle).isError()).isFalse();
	}
}
```

p4는 59,000원이라 2개면 118,000원이다. p6은 재고가 3개라 5개를 결제할 수 없다.

`ToolScopeRegistryTest`에 더한다:

```java
	@Test
	void 결제만_orders_write이고_나머지_장바구니_tool은_기본_scope다() {
		ToolScopeRegistry registry = ToolScopeRegistry.scan(new BasketTools(null, null));

		assertThat(registry.scopeFor("checkout")).isEqualTo("orders:write");
		assertThat(registry.scopeFor("createBasket")).isEqualTo("products:read");
		assertThat(registry.scopeFor("addItem")).isEqualTo("products:read");
		assertThat(registry.scopeFor("getBasket")).isEqualTo("products:read");
	}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다 — 실제 요청 흐름**

`BasketFlowTest.java`:

```java
package dev.starryeye.stateless.mcpserver;

import dev.starryeye.stateless.mcpserver.repository.ProductRepository;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * session 없이 handle만으로 장바구니를 이어 쓰는 흐름을 실제 filter chain과 transport로 본다.
 * 다른 사용자의 token, 결제의 {@code orders:write}, 두 번째 결제를 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class BasketFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	static String 토큰(String subject, String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, subject, Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	static String 호출(String tool, String arguments) {
		return """
				{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
				.formatted(tool, arguments);
	}

	String 응답(String token, String body) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void handle로_담고_보고_결제한다() throws Exception {
		String read = 토큰("user", "products:read");
		String handle = JsonPath.read(응답(read, 호출("createBasket", "{}")), "$.result.structuredContent.basketId");
		응답(read, 호출("addItem", "{\"basketId\":\"%s\",\"productId\":\"p4\",\"quantity\":1}".formatted(handle)));
		int before = this.productRepository.findById("p4").orElseThrow().stock();

		String basket = 응답(read, 호출("getBasket", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(basket).contains("p4").doesNotContain("\"isError\":true");

		// 조회 token으로는 결제할 수 없다. 서버는 모자란 scope만 알린다.
		this.mockMvc.perform(McpScopeTest.mcp(read, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle))))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"orders:write\", "
								+ "resource_metadata=\"http://localhost:8151/.well-known/oauth-protected-resource/mcp\""));

		String write = 토큰("user", "products:read", "orders:write");
		String ordered = 응답(write, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(ordered).contains("ord-").doesNotContain("\"isError\":true");
		assertThat(this.productRepository.findById("p4").orElseThrow().stock()).isEqualTo(before - 1);

		String again = 응답(write, 호출("checkout", "{\"basketId\":\"%s\"}".formatted(handle)));
		assertThat(again).contains("\"isError\":true").contains("이미 주문");
	}

	@Test
	void 다른_사용자의_token으로는_handle을_써도_찾을_수_없다() throws Exception {
		String handle = JsonPath.read(응답(토큰("user", "products:read"), 호출("createBasket", "{}")),
				"$.result.structuredContent.basketId");

		String other = 응답(토큰("user2", "products:read"), 호출("getBasket", "{\"basketId\":\"%s\"}".formatted(handle)));

		assertThat(other).contains("\"isError\":true").contains("찾을 수 없습니다").doesNotContain("p4");
	}
}
```

`ShopMcpServerApplicationTests`의 기대값을 바꾼다:

```java
		assertThat(toolNames).containsExactlyInAnyOrder("searchProducts", "getStock", "updateStock",
				"createBasket", "addItem", "getBasket", "checkout");
```

- [ ] **Step 3: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*BasketToolsTest*' --tests '*BasketFlowTest*' --tests '*ToolScopeRegistryTest*' --tests '*ShopMcpServerApplicationTests*'
```

Expected: 컴파일 오류(`BasketTools` 없음).

- [ ] **Step 4: 구현한다**

`ProductRepository`에 더한다:

```java
    /**
     * 주문할 수량만큼 재고를 한꺼번에 줄인다.
     * 하나라도 모자라거나 없는 상품이면 아무것도 줄이지 않고 예외를 던진다.
     */
    public synchronized void reserve(Map<String, Integer> quantities) {
        List<String> shortages = quantities.entrySet().stream()
                .filter(entry -> {
                    Product product = store.get(entry.getKey());
                    return product == null || product.stock() < entry.getValue();
                })
                .map(Map.Entry::getKey)
                .toList();
        if (!shortages.isEmpty()) {
            throw new IllegalStateException("재고가 모자라 주문할 수 없습니다: " + String.join(", ", shortages));
        }
        quantities.forEach((id, quantity) -> store.computeIfPresent(id, (key, product) ->
                new Product(product.id(), product.name(), product.category(), product.price(),
                        product.stock() - quantity)));
    }
```

`tool/BasketTools.java`:

```java
package dev.starryeye.stateless.mcpserver.tool;

import dev.starryeye.stateless.mcpserver.basket.BasketException;
import dev.starryeye.stateless.mcpserver.basket.BasketStore;
import dev.starryeye.stateless.mcpserver.basket.BasketView;
import dev.starryeye.stateless.mcpserver.domain.Product;
import dev.starryeye.stateless.mcpserver.repository.ProductRepository;
import dev.starryeye.stateless.mcpserver.security.McpCaller;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 호출 사이에 남는 상태(장바구니)를 session이 아니라 handle로 다루는 tool이다(SEP-2567의 설계 패턴).
 *
 * <p>{@code createBasket}이 handle을 돌려주고, 모델은 그것을 다음 tool의 인자로 넘긴다.
 * 누가 부르는지는 인자가 아니라 {@link McpTransportContext}의 token 사용자에서 꺼낸다({@link McpCaller}).
 * 결과는 {@link CallToolResult}로 직접 만든다.
 * 그래야 handle을 {@code structuredContent}로도 주고, 쓸 수 없는 경우를 {@code isError}로 알릴 수 있다.
 */
@Component
public class BasketTools {

	private static final Logger log = LoggerFactory.getLogger(BasketTools.class);

	private final BasketStore baskets;

	private final ProductRepository products;

	public BasketTools(BasketStore baskets, ProductRepository products) {
		this.baskets = baskets;
		this.products = products;
	}

	@McpTool(name = "createBasket",
			description = "새 장바구니를 만들고 장바구니 ID(basketId)를 돌려준다. "
					+ "상품을 담거나(addItem) 보거나(getBasket) 주문할(checkout) 때 이 ID를 넘긴다. "
					+ "장바구니는 만든 뒤 30분이 지나면 만료되고, 한 사용자는 열린 장바구니를 5개까지 가진다. "
					+ "이미 쓰던 장바구니가 있으면 새로 만들지 말고 그 ID를 이어 쓴다.")
	@RequiredScope("products:read")
	public CallToolResult createBasket(McpTransportContext context) {
		McpCaller caller = McpCaller.from(context);
		log.info("createBasket 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			BasketView basket = this.baskets.create(caller.subject());
			return CallToolResult.builder()
					.addTextContent("장바구니 %s를 만들었습니다. %s에 만료됩니다.".formatted(basket.handle(), basket.expiresAt()))
					.structuredContent(Map.of("basketId", basket.handle(), "expiresAt", basket.expiresAt().toString()))
					.build();
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "addItem",
			description = "장바구니에 상품을 담는다. 같은 상품을 다시 담으면 수량이 더해진다. "
					+ "basketId는 createBasket이 준 값이다.")
	@RequiredScope("products:read")
	public CallToolResult addItem(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId,
			@McpToolParam(description = "상품 ID. 예: p1", required = true)
			String productId,
			@McpToolParam(description = "담을 수량. 1 이상의 정수", required = true)
			int quantity) {
		McpCaller caller = McpCaller.from(context);
		log.info("addItem 호출 (사용자={}, productId={}, quantity={})", caller.subject(), productId, quantity);
		if (quantity < 1) {
			return error("수량은 1 이상이어야 합니다. (받은 값: %d)".formatted(quantity));
		}
		if (this.products.findById(productId).isEmpty()) {
			return error("상품 %s를 찾을 수 없습니다. searchProducts로 상품 ID를 확인하세요.".formatted(productId));
		}
		try {
			return text(describe(this.baskets.addItem(caller.subject(), basketId, productId, quantity)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "getBasket", description = "장바구니에 담긴 상품, 수량, 합계, 만료 시각을 보여 준다.")
	@RequiredScope("products:read")
	public CallToolResult getBasket(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId) {
		McpCaller caller = McpCaller.from(context);
		log.info("getBasket 호출 (사용자={})", caller.subject());
		try {
			return text(describe(this.baskets.view(caller.subject(), basketId)));
		}
		catch (BasketException ex) {
			return error(ex.getMessage());
		}
	}

	@McpTool(name = "checkout",
			description = "장바구니를 주문한다. 재고를 줄이고 주문 번호를 돌려주며, 장바구니는 닫힌다. "
					+ "사용자가 주문이나 결제를 분명히 요청할 때만 사용한다.")
	@RequiredScope("orders:write")
	public CallToolResult checkout(McpTransportContext context,
			@McpToolParam(description = "장바구니 ID. createBasket이 준 bsk_로 시작하는 값", required = true)
			String basketId) {
		McpCaller caller = McpCaller.from(context);
		log.info("checkout 호출 (사용자={}, client_id={})", caller.subject(), caller.clientId());
		try {
			String orderId = this.baskets.checkout(caller.subject(), basketId, this.products::reserve);
			return CallToolResult.builder()
					.addTextContent("주문 %s를 접수했습니다.".formatted(orderId))
					.structuredContent(Map.of("orderId", orderId))
					.build();
		}
		catch (BasketException | IllegalStateException ex) {
			return error(ex.getMessage());
		}
	}

	private String describe(BasketView basket) {
		if (basket.items().isEmpty()) {
			return "장바구니 %s는 비어 있습니다. %s에 만료됩니다.".formatted(basket.handle(), basket.expiresAt());
		}
		long total = 0;
		StringBuilder lines = new StringBuilder("장바구니 %s:\n".formatted(basket.handle()));
		for (Map.Entry<String, Integer> item : basket.items().entrySet()) {
			Product product = this.products.findById(item.getKey()).orElseThrow();
			long amount = (long) product.price() * item.getValue();
			total += amount;
			lines.append("- [%s] %s × %d = %,d원\n".formatted(product.id(), product.name(), item.getValue(), amount));
		}
		return lines.append("합계 %,d원. %s에 만료됩니다.".formatted(total, basket.expiresAt())).toString();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addTextContent(text).build();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}
}
```

`@McpTool`·`@McpToolParam`의 package는 `ProductTools`가 쓰는 import를 그대로 따른다.

`McpTransportConfig`에 bean을 더하고 registry를 바꾼다:

```java
	/** 장바구니 저장소다. 만료 계산은 {@link Clock}으로 한다(테스트가 시간을 돌릴 수 있게). */
	@Bean
	public BasketStore basketStore() {
		return new BasketStore(Clock.systemUTC());
	}

	@Bean
	public ToolScopeRegistry toolScopeRegistry(ProductTools productTools, BasketTools basketTools) {
		return ToolScopeRegistry.scan(productTools, basketTools);
	}
```

- [ ] **Step 5: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS. `McpScopeTest`의 UTF-8 회귀 테스트가 기대하는 p1 재고 7은 이 task의 테스트가 p1을 쓰지 않으므로 그대로다.
`BasketToolsTest`의 `createBasket은_handle을_글과_structuredContent로_돌려준다`는 장바구니를 두 개 만든다(개수 제한 5에 걸리지 않는다).

- [ ] **Step 6: 커밋**

```bash
git add practice/mcp-stateless-handle/shop-mcp-server
git commit -m "feat(stateless): 장바구니 tool 네 개, checkout만 orders:write

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: agent가 tool 결과까지 사용자별로 기억한다

**Files:**
- Create: `practice/mcp-stateless-handle/shop-agent/src/main/java/dev/starryeye/stateless/agent/config/ChatMemoryConfig.java`
- Modify: `.../agent/config/ChatClientConfig.java`
- Modify: `.../agent/controller/ChatController.java`
- Test: `practice/mcp-stateless-handle/shop-agent/src/test/java/dev/starryeye/stateless/agent/ChatMemoryToolResultTest.java`

**Interfaces:**
- Produces:
  - `ChatMemoryConfig.MAX_MESSAGES = 20`, `ChatMemoryConfig.MEMORY_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100`.
  - bean `ChatMemory chatMemory()`(`MessageWindowChatMemory` + `InMemoryChatMemoryRepository`), bean `ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager)`(`disableInternalConversationHistory()`).
  - `ChatController`가 요청마다 `ChatMemory.CONVERSATION_ID` = 로그인 사용자의 이름(`sub`)을 advisor parameter로 넣는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ChatMemoryToolResultTest.java`(authz의 `StepUpChatStreamTest`처럼 context의 `ChatClient` bean을 그대로 쓰고, 모델과 tool만 바꾼다):

```java
package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 대화 기억이 tool loop 안에 있어, tool 호출과 결과(장바구니 handle)가 기억에 남는지 본다.
 * 모델은 첫 요청에서 {@code createBasket}을 부르고, tool 결과를 받으면 답을 쓴다.
 */
@SpringBootTest
class ChatMemoryToolResultTest {

	static final ToolDefinition CREATE_BASKET = DefaultToolDefinition.builder()
			.name("createBasket").description("장바구니를 만든다").inputSchema("{\"type\":\"object\"}").build();

	static final ToolCallback createBasket = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return CREATE_BASKET;
		}

		@Override
		public String call(String toolInput) {
			return "장바구니 bsk_test를 만들었습니다.";
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	ChatClient chatClient;

	@Autowired
	ChatMemory chatMemory;

	List<Prompt> prompts = new CopyOnWriteArrayList<>();

	static ChatResponse 응답(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message)));
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "createBasket", "{}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			this.prompts.add(prompt);
			List<Message> messages = prompt.getInstructions();
			if (messages.get(messages.size() - 1) instanceof ToolResponseMessage) {
				return Flux.just(응답(new AssistantMessage("장바구니를 만들었어요")));
			}
			if (prompt.getUserMessage().getText().contains("담아")) {
				return Flux.just(응답(new AssistantMessage("그 장바구니에 담을게요")));
			}
			return Flux.just(응답(toolCall));
		});
	}

	void 묻는다(String conversationId, String question) {
		this.chatClient.prompt()
				.user(question)
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
				.toolCallbacks(createBasket)
				.stream()
				.content()
				.collectList()
				.block(Duration.ofSeconds(10));
	}

	@Test
	void tool_결과의_handle이_대화_기억에_남는다() {
		묻는다("memory-a", "장바구니 만들어 줘");

		List<Message> remembered = this.chatMemory.get("memory-a");
		assertThat(remembered).anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(
				AssistantMessage.class, assistant -> assertThat(assistant.hasToolCalls()).isTrue()));
		assertThat(remembered).anySatisfy(message -> assertThat(message).isInstanceOfSatisfying(
				ToolResponseMessage.class,
				response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test")));
	}

	@Test
	void 다음_turn의_모델은_이전_tool_결과를_본다() {
		묻는다("memory-b", "장바구니 만들어 줘");
		this.prompts.clear();

		묻는다("memory-b", "p4 하나 담아 줘");

		assertThat(this.prompts.get(0).getInstructions()).anySatisfy(message -> assertThat(message)
				.isInstanceOfSatisfying(ToolResponseMessage.class,
						response -> assertThat(response.getResponses().get(0).responseData()).contains("bsk_test")));
	}

	@Test
	void 사용자끼리_대화_기억이_섞이지_않는다() {
		묻는다("memory-c", "장바구니 만들어 줘");

		assertThat(this.chatMemory.get("memory-d")).isEmpty();
	}
}
```

`DiscoveryFixtures`·`McpAuthorizationDiscovery`의 package와 이름은 authz 테스트(`StepUpChatStreamTest`)에서 쓰는 것을 그대로 따른다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-stateless-handle/shop-agent && ./gradlew test --tests '*ChatMemoryToolResultTest*'
```

Expected: `ChatMemory` bean이 없어 context가 뜨지 않거나, 뜬다면 기억이 비어 FAIL.

- [ ] **Step 3: 구현한다**

`config/ChatMemoryConfig.java`:

```java
package dev.starryeye.stateless.agent.config;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사용자별 대화 기억이다. tool 호출과 결과까지 남긴다(안내서 11장).
 *
 * <p>Spring AI의 기본 배치에서는 대화 기억 advisor가 tool loop 바깥에 있어, 사용자 질문과 마지막 답만 남는다.
 * 그러면 다음 turn의 모델은 이전 tool 결과에 있던 장바구니 handle을 보지 못한다.
 * 그래서 {@code ToolCallingAdvisor}의 내부 history를 끄고, 대화 기억 advisor를 그 안쪽(더 큰 order)에 둔다.
 * tool loop의 한 단계마다 기억을 거치므로 tool 호출과 결과가 차례로 저장된다.
 *
 * <p>둘 중 하나만 바꾸면 조용히 틀어진다.
 * 내부 history만 끄면 tool 결과가 어디에도 남지 않고, 기억만 안쪽에 두면 history가 두 번 들어간다.
 */
@Configuration
public class ChatMemoryConfig {

	/** 최근 메시지 20개만 기억한다. 로컬 모델의 context가 짧기 때문이다. */
	public static final int MAX_MESSAGES = 20;

	/** {@link ToolCallingAdvisor#DEFAULT_ORDER}보다 커야 tool loop 안에 들어간다. */
	public static final int MEMORY_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

	@Bean
	public ChatMemory chatMemory() {
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.maxMessages(MAX_MESSAGES)
				.build();
	}

	/** 자동 구성의 같은 bean({@code @ConditionalOnMissingBean})을 대신한다. 바꾸는 것은 내부 history 끄기뿐이다. */
	@Bean
	public ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager toolCallingManager) {
		return ToolCallingAdvisor.builder()
				.toolCallingManager(toolCallingManager)
				.disableInternalConversationHistory();
	}
}
```

`ChatClientConfig`의 bean에 대화 기억 advisor를 더한다:

```java
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .order(ChatMemoryConfig.MEMORY_ADVISOR_ORDER)
                        .build());
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
```

(import `org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor`, `org.springframework.ai.chat.memory.ChatMemory`)

`ChatController`:

```java
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session,
            Authentication authentication) {
        // 대화 기억은 로그인 사용자(sub)마다 따로 둔다.
        String conversationId = authentication.getName();
        return ChatEvents.of(chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content(), StepUpState.of(session));
    }
```

(import `org.springframework.security.core.Authentication`, `org.springframework.ai.chat.memory.ChatMemory`)

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS. 기존 `ChatCsrfTest`·`StepUpChatStreamTest`도 통과해야 한다(`@WithMockUser`의 이름이 conversation ID가 된다).
`다음_turn의_모델은_이전_tool_결과를_본다`가 실패하면 기억 advisor의 order와 `disableInternalConversationHistory()`를 먼저 본다. streaming 경로의 advisor 변경은 Spring AI 2.0.1(#6737)에서 고쳐졌으므로, 2.0.1이 아니면 실패할 수 있다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-stateless-handle/shop-agent
git commit -m "feat(stateless): agent가 tool 결과까지 사용자별로 기억한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: step-up으로 끊긴 turn 되돌리기, 새 대화, system prompt

**Files:**
- Modify: `.../agent/controller/ChatEvents.java`
- Modify: `.../agent/controller/ChatController.java`
- Modify: `.../agent/config/ChatClientConfig.java`(system prompt)
- Modify: `practice/mcp-stateless-handle/shop-agent/src/main/resources/static/index.html`
- Test: `.../test/.../agent/StepUpMemoryRollbackTest.java`, `.../test/.../agent/ChatResetTest.java`
- Modify: `.../test/.../agent/controller/ChatEventsTest.java`(새 overload)

**Interfaces:**
- Consumes: `ChatMemory` bean(Task 6).
- Produces:
  - `ChatEvents.of(Flux<String> content, StepUpState state, Runnable onStepUp)` — step-up event를 만들기 직전에 `onStepUp`을 부른다. 기존 `of(content, state)`는 `of(content, state, () -> {})`다.
  - `POST /api/chat/reset` → `204`, 로그인 사용자의 대화 기억을 지운다. CSRF 검사를 받는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다 — 끊긴 turn 되돌리기**

`StepUpMemoryRollbackTest.java`:

```java
package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.controller.ChatController;
import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.stateless.agent.security.StepUpRequiredException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 결제에서 step-up이 나면 tool 호출이 결과 없이 끊긴다.
 * 그 turn에서 기억에 들어간 질문과 tool 호출을 되돌려, consent 뒤 다시 보낸 질문이 깨끗한 turn으로 시작하는지 본다.
 */
@SpringBootTest
class StepUpMemoryRollbackTest {

	static final ToolDefinition CHECKOUT = DefaultToolDefinition.builder()
			.name("checkout").description("주문한다").inputSchema("{\"type\":\"object\"}").build();

	/** MCP Server가 403 insufficient_scope를 준 것처럼 던진다. */
	static final ToolCallback 권한이_모자란_checkout = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return CHECKOUT;
		}

		@Override
		public String call(String toolInput) {
			throw new ToolExecutionException(CHECKOUT, new RuntimeException("MCP SDK가 감쌈",
					new StepUpRequiredException(List.of("orders:write"), null)));
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	ChatController chatController;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[] { 권한이_모자란_checkout });
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-9", "function", "checkout",
						"{\"basketId\":\"bsk_x\"}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(new ChatResponse(List.of(new Generation(toolCall)))));
	}

	@Test
	void step_up으로_끊긴_turn은_기억에서_되돌린다() {
		List<Message> earlier = List.of(new UserMessage("장바구니 만들어 줘"), new AssistantMessage("bsk_x를 만들었어요"));
		this.chatMemory.add("rollback-user", earlier);

		List<ServerSentEvent<String>> events = this.chatController
				.chat("결제해 줘", new MockHttpSession(), new TestingAuthenticationToken("rollback-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(events).extracting(ServerSentEvent::event).last().isEqualTo("step-up");
		assertThat(this.chatMemory.get("rollback-user")).extracting(Message::getText)
				.containsExactly("장바구니 만들어 줘", "bsk_x를 만들었어요");
	}

	@Test
	void 기억이_비어_있던_사용자도_끊긴_turn이_남지_않는다() {
		this.chatController
				.chat("결제해 줘", new MockHttpSession(), new TestingAuthenticationToken("rollback-empty", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(this.chatMemory.get("rollback-empty")).isEmpty();
	}
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다 — 새 대화**

`ChatResetTest.java`:

```java
package dev.starryeye.stateless.agent;

import dev.starryeye.stateless.agent.discovery.DiscoveryFixtures;
import dev.starryeye.stateless.agent.discovery.McpAuthorizationDiscovery;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 화면의 "새 대화" 버튼이 부르는 {@code /api/chat/reset}을 본다. CSRF 검사를 받고, 로그인 사용자의 기억만 지운다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatResetTest {

	/** Boot 4.1의 {@code @AutoConfigureMockMvc}는 springSecurity()를 붙이지 않는다. {@code @WithMockUser}에 필요하다. */
	@TestConfiguration
	static class SecurityMockMvcSupport {

		@Bean
		MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
			return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
		}
	}

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
	ToolCallbackProvider toolCallbackProvider;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[0]);
	}

	@Test
	@WithMockUser("reset-user")
	void 새_대화는_로그인_사용자의_기억을_지운다() throws Exception {
		this.chatMemory.add("reset-user", List.of(new UserMessage("장바구니 만들어 줘")));
		this.chatMemory.add("reset-bystander", List.of(new UserMessage("다른 사용자의 질문")));
		Cookie token = this.mockMvc.perform(get("/index.html")).andReturn().getResponse().getCookie("XSRF-TOKEN");

		this.mockMvc.perform(post("/api/chat/reset").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
				.andExpect(status().isNoContent());

		assertThat(this.chatMemory.get("reset-user")).isEmpty();
		assertThat(this.chatMemory.get("reset-bystander")).hasSize(1);
	}

	@Test
	@WithMockUser("reset-other")
	void CSRF_토큰_없이_새_대화를_누르면_403이고_기억은_그대로다() throws Exception {
		this.chatMemory.add("reset-other", List.of(new UserMessage("장바구니 만들어 줘")));

		this.mockMvc.perform(post("/api/chat/reset")).andExpect(status().isForbidden());

		assertThat(this.chatMemory.get("reset-other")).hasSize(1);
	}
}
```

`MockMvcBuilderCustomizer`의 package는 authz `ChatCsrfTest`의 import를 그대로 따른다.

- [ ] **Step 3: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*StepUpMemoryRollbackTest*' --tests '*ChatResetTest*'
```

Expected: 되돌리기 테스트는 기억에 "결제해 줘"와 tool 호출이 남아 FAIL, reset 테스트는 `404`로 FAIL.

- [ ] **Step 4: 구현한다**

`ChatEvents`:

```java
    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state) {
        return of(content, state, () -> {
        });
    }

    /**
     * {@code onStepUp}은 step-up event를 만들기 직전에 부른다.
     * agent는 여기서 끊긴 turn을 대화 기억에서 되돌린다.
     */
    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state, Runnable onStepUp) {
        return content.map(text -> event(MESSAGE, JSON.writeValueAsString(text)))
                .onErrorResume(error -> StepUpRequiredException.find(error).isPresent(), error -> {
                    onStepUp.run();
                    return Flux.just(stepUp(StepUpRequiredException.find(error).orElseThrow(), state));
                });
    }
```

`ChatController`:

```java
    private final ChatClient chatClient;

    private final ChatMemory chatMemory;

    public ChatController(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session,
            Authentication authentication) {
        // 대화 기억은 로그인 사용자(sub)마다 따로 둔다.
        String conversationId = authentication.getName();
        // step-up으로 끊기면 이 turn에서 기억에 들어간 질문과 결과 없는 tool 호출을 되돌린다.
        // consent 뒤 browser가 같은 질문을 다시 보내므로, 그 turn이 깨끗하게 시작해야 한다.
        List<Message> before = List.copyOf(this.chatMemory.get(conversationId));
        Flux<String> content = this.chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
        return ChatEvents.of(content, StepUpState.of(session), () -> restore(conversationId, before));
    }

    /** 화면의 "새 대화" 버튼이 부른다. 로그인 사용자의 대화 기억을 지운다. */
    @PostMapping("/api/chat/reset")
    public ResponseEntity<Void> reset(Authentication authentication) {
        this.chatMemory.clear(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    private void restore(String conversationId, List<Message> before) {
        this.chatMemory.clear(conversationId);
        if (!before.isEmpty()) {
            this.chatMemory.add(conversationId, before);
        }
    }
```

(import `org.springframework.ai.chat.messages.Message`, `org.springframework.http.ResponseEntity`, `java.util.List`)
같은 사용자가 두 tab에서 동시에 결제하다 한쪽이 step-up으로 끊기면, 다른 tab이 그 사이에 쌓은 기억도 되돌려진다.
이 practice는 이 경우를 다루지 않는다는 것을 `restore`의 주석에 한 줄로 적는다.

`ChatClientConfig`의 `SYSTEM_PROMPT` 끝에 더한다:

```text
            장바구니 작업은 장바구니 툴로 합니다.
            장바구니가 없으면 createBasket으로 만들고, 앞선 툴 결과에 나온 basketId를 이어서 씁니다.
            장바구니를 찾을 수 없거나 만료되었다는 결과가 오면 createBasket으로 새로 만듭니다.
            주문(checkout)은 사용자가 주문이나 결제를 분명히 요청할 때만 합니다.
```

`index.html`: `send` 버튼 옆에 "새 대화" 버튼을 둔다.

```html
<button id="send">보내기</button>
<button id="reset">새 대화</button>
```

```javascript
    const reset = document.getElementById('reset');
    // 대화 기억을 지운다. 장바구니 handle을 기억하는 대화도 함께 사라진다.
    reset.addEventListener('click', async () => {
        sessionStorage.removeItem(PENDING);
        const res = await fetch('/api/chat/reset', {method: 'POST', headers: csrfHeaders()});
        card.hidden = true;
        out.textContent = res.ok ? '대화를 비웠습니다.' : '오류: HTTP ' + res.status;
    });
```

`ChatEventsTest`의 기존 호출은 두 인자 overload를 그대로 쓰므로 바꾸지 않아도 된다. 새 overload에 한 테스트를 더한다:

```java
    @Test
    void step_up_event를_보내기_전에_onStepUp을_부른다() {
        AtomicBoolean called = new AtomicBoolean();
        Flux<String> content = Flux.error(new StepUpRequiredException(List.of("orders:write"), "checkout"));

        List<ServerSentEvent<String>> events = ChatEvents.of(content, new StepUpState(), () -> called.set(true))
                .collectList().block();

        assertThat(called).isTrue();
        assertThat(events).extracting(ServerSentEvent::event).containsExactly("step-up");
    }
```

- [ ] **Step 5: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS.

- [ ] **Step 6: 커밋**

```bash
git add practice/mcp-stateless-handle/shop-agent
git commit -m "feat(stateless): step-up으로 끊긴 turn을 기억에서 되돌리고 새 대화 버튼을 둔다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `local-client`의 장바구니 시나리오

**Files:**
- Modify: `practice/mcp-stateless-handle/local-client/src/main/java/dev/starryeye/stateless/localclient/McpCalls.java`
- Modify: `practice/mcp-stateless-handle/local-client/src/test/java/dev/starryeye/stateless/localclient/FakeMcpServer.java`
- Modify: `practice/mcp-stateless-handle/local-client/src/test/java/dev/starryeye/stateless/localclient/McpCallsTest.java`

**Interfaces:**
- Consumes: 서버 tool 이름과 인자(Task 5), `createBasket`의 `structuredContent.basketId`.
- Produces: `McpCalls.run(...)`의 출력(Task 9 캡처와 문서가 인용한다):

```text
    getStock(p1): …
    createBasket: 장바구니 bsk_…를 만들었습니다. …에 만료됩니다.
    addItem(p4, 1): 장바구니 bsk_…: …
    addItem(p9, 2): …
    getBasket: 장바구니 bsk_…: …
    getBasket(모르는 ID): [오류] 장바구니 bsk_AAAAAAAAAAAAAAAAAAAAAA를 찾을 수 없습니다. …
    403 insufficient_scope — 필요한 scope: orders:write
[6] step-up: products:read orders:write로 다시 authorization을 받는다
…
[7] 새 token으로 같은 요청을 새로 보낸다
    checkout: 주문 ord-…를 접수했습니다.
```

- [ ] **Step 1: fake server와 실패하는 테스트를 바꾼다**

`FakeMcpServer`:
- `Recorded`에 `Map<String, Object> arguments`를 더한다(`tools/call`이면 `params.arguments`, 아니면 `Map.of()`).
- `initialize`는 session 없이 답한다(`respondResult(..., null)`) — stateless 서버와 같다.
- `handleToolCall`을 바꾼다:

```java
	static final String BASKET_ID = "bsk_fake0000000000000000";

	private void handleToolCall(HttpExchange exchange, Object id, String toolName, Map<String, Object> arguments,
			String authorization) throws IOException {
		if ("checkout".equals(toolName) && "Bearer read-token".equals(authorization)) {
			respond(exchange, 403,
					Map.of("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"orders:write\""), null);
			return;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		switch (toolName) {
			case "createBasket" -> {
				result.put("content", List.of(Map.of("type", "text", "text", "장바구니 " + BASKET_ID + "를 만들었습니다.")));
				result.put("structuredContent", Map.of("basketId", BASKET_ID));
			}
			case "getBasket" -> {
				boolean known = BASKET_ID.equals(arguments.get("basketId"));
				result.put("content", List.of(Map.of("type", "text",
						"text", known ? "장바구니 " + BASKET_ID + ": p4 × 1" : "장바구니를 찾을 수 없습니다.")));
				result.put("isError", !known);
			}
			case "checkout" -> result.put("content", List.of(Map.of("type", "text", "text", "주문 ord-1001를 접수했습니다.")));
			default -> result.put("content", List.of(Map.of("type", "text", "text", toolName + " 완료")));
		}
		respondResult(exchange, id, result, null);
	}
```

`McpCallsTest`를 장바구니 시나리오에 맞게 바꾼다(`setUp`·`tearDown`·`stepUp(...)` helper는 그대로 둔다):

```java
	List<FakeMcpServer.Recorded> calls(String tool) {
		return this.mcp.requests.stream().filter(r -> tool.equals(r.toolName())).toList();
	}

	String printed() {
		return this.printed.toString(StandardCharsets.UTF_8);
	}

	@Test
	void step_up_뒤_checkout을_새_token을_실은_새_요청으로_다시_보낸다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out);

		List<FakeMcpServer.Recorded> checkouts = calls("checkout");
		assertThat(checkouts).extracting(FakeMcpServer.Recorded::authorization)
				.containsExactly("Bearer read-token", "Bearer write-token");
		assertThat(this.requested).containsExactly(Set.of("products:read", "orders:write"));
		assertThat(printed()).contains("[7]").contains("checkout: 주문 ord-1001를 접수했습니다.");
	}

	@Test
	void createBasket이_준_basketId를_다음_호출에_넘긴다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(calls("addItem")).extracting(r -> r.arguments().get("basketId"))
				.containsOnly(FakeMcpServer.BASKET_ID);
		assertThat(calls("checkout")).extracting(r -> r.arguments().get("basketId"))
				.containsOnly(FakeMcpServer.BASKET_ID);
	}

	@Test
	void 모르는_장바구니는_오류로_찍고_이어_간다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(printed()).contains("getBasket(모르는 ID): [오류] 장바구니를 찾을 수 없습니다.");
	}

	@Test
	void session이_없으면_끝날_때_DELETE를_보내지_않는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(this.mcp.requests).extracting(FakeMcpServer.Recorded::httpMethod).doesNotContain("DELETE");
	}

	@Test
	void authorizer가_orders_write를_못_주면_실패하고_checkout을_다시_보내지_않는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));
		StepUp stepUp = stepUp(holder, new TokenResponse("still-read-token", 300, "products:read"));

		assertThatThrownBy(
				() -> McpCalls.run(this.mcp.origin() + "/mcp", holder, stepUp, Duration.ofSeconds(20), this.out))
				.isInstanceOf(LocalClientException.class)
				.hasMessage("orders:write 권한을 받지 못했다");

		assertThat(calls("checkout")).hasSize(1);
	}
```

authz의 `updateStock` 테스트 두 개는 지운다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-stateless-handle/local-client && ./gradlew test --tests '*McpCallsTest*'
```

Expected: FAIL(`McpCalls`가 아직 `updateStock`을 부른다).

- [ ] **Step 3: 시나리오를 바꾼다**

`McpCalls.run`의 `try` 블록을 바꾼다:

```java
		try {
			McpSchema.InitializeResult initialized = callOnce(client::initialize, out);
			out.println("    initialize: protocolVersion=" + initialized.protocolVersion()
					+ ", server=" + initialized.serverInfo().name());
			callOnce(client::listTools, out).tools().forEach(tool -> out.println("    tool: " + tool.name()));
			print(out, "getStock(p1)", callOnce(() -> call(client, "getStock", Map.of("productId", "p1")), out));

			// handle은 서버가 만든다. 이 앱은 받은 handle을 다음 호출의 인자로 넘긴다(안내서 11장).
			McpSchema.CallToolResult created = callOnce(() -> call(client, "createBasket", Map.of()), out);
			print(out, "createBasket", created);
			String basketId = basketId(created);
			print(out, "addItem(p4, 1)", callOnce(() -> call(client, "addItem",
					Map.of("basketId", basketId, "productId", "p4", "quantity", 1)), out));
			print(out, "addItem(p9, 2)", callOnce(() -> call(client, "addItem",
					Map.of("basketId", basketId, "productId", "p9", "quantity", 2)), out));
			print(out, "getBasket", callOnce(() -> call(client, "getBasket", Map.of("basketId", basketId)), out));
			// 이 사용자의 것이 아닌 handle은 "찾을 수 없다"다. 가진 것만으로는 쓸 수 없다.
			print(out, "getBasket(모르는 ID)",
					callOnce(() -> call(client, "getBasket", Map.of("basketId", UNKNOWN_BASKET)), out));
			// 주문은 orders:write가 필요하다. 조회 token이면 403 → 그 자리에서 step-up → 새 요청으로 다시 보낸다.
			print(out, "checkout", callOnce(() -> call(client, "checkout", Map.of("basketId", basketId)), out));
		}
```

같은 클래스에 더한다:

```java
	/** 이 사용자가 만든 적 없는 handle이다. 모양은 맞지만 서버에 없다. */
	static final String UNKNOWN_BASKET = "bsk_" + "A".repeat(22);

	private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
		return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	/** tool 결과를 한 줄씩 찍는다. {@code isError}면 앞에 {@code [오류]}를 붙인다. */
	private static void print(PrintStream out, String label, McpSchema.CallToolResult result) {
		String prefix = Boolean.TRUE.equals(result.isError()) ? "[오류] " : "";
		result.content().forEach(content -> out.println("    " + label + ": " + prefix
				+ (content instanceof McpSchema.TextContent text ? text.text() : content)));
	}

	/** {@code createBasket}의 {@code structuredContent}에서 handle을 꺼낸다. */
	static String basketId(McpSchema.CallToolResult created) {
		if (created.structuredContent() instanceof Map<?, ?> content && content.get("basketId") instanceof String id) {
			return id;
		}
		throw new LocalClientException("createBasket이 basketId를 돌려주지 않았다");
	}
```

클래스 javadoc을 장바구니 시나리오로 고친다(`updateStock` 언급을 지운다).

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test
```

Expected: 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-stateless-handle/local-client
git commit -m "feat(stateless): local-client가 장바구니를 만들고 checkout에서 step-up한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: 실제로 띄워 캡처하고 웹 agent를 browser로 확인한다

**Files:**
- Create: `docs/superpowers/captures/mcp-stateless-walkthrough.sh`
- Create: `docs/superpowers/captures/stateless-local-client-run.sh`
- Create: `docs/superpowers/captures/2026-10-01-stateless-walkthrough.txt`(날짜는 실행한 날)
- Create: `docs/superpowers/captures/2026-10-01-stateless-local-client.txt`
- (고칠 것이 나오면) 해당 module

**Interfaces:**
- Consumes: Task 1–8의 네 module.
- Produces: Task 10–12가 인용할 캡처 두 개와 browser 확인 결과(보고서).

- [ ] **Step 1: walkthrough 스크립트를 만든다**

`docs/superpowers/captures/mcp-authz-walkthrough.sh`를 `mcp-stateless-walkthrough.sh`로 복사하고 바꾼다.
- 머리 주석: "mcp-stateless-handle practice의 stateless 서버와 장바구니 handle 흐름을 curl로 한 단계씩 기록한다(S 번호)."
- 기본값: `AS=http://localhost:9040`, `MCP_BASE=http://localhost:8151`, `CLIENT_ID=stateless-shop-agent`, `CLIENT_SECRET=stateless-shop-agent-secret`, `REDIRECT_URI=http://localhost:8150/login/oauth2/code/authserver`.
- `authorize`·`consent`·`token`·`mcp`·`tidy`·`payload` helper는 그대로 쓴다. 두 번째 사용자를 위해 login helper를 함수로 뺀다: `login USERNAME`(쓰는 `JAR`는 호출 전에 바꾼다).
- `tool()` helper: `tool TOKEN NAME ARGS_JSON` → `mcp "$1" "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"`.
- 단계(모든 MCP 요청에 `Mcp-Session-Id`를 보내지 않는다):

```text
S1  token 없이 initialize → 401, WWW-Authenticate(resource_metadata, scope="products:read")
S2  user login → authorize "openid products:read" → consent products:read → token(READ_TOKEN)
S3  READ_TOKEN으로 initialize → 응답 header 전체. Mcp-Session-Id가 있으면 fail "Mcp-Session-Id가 있다"
S4  GET /mcp(Accept: text/event-stream) → 첫 줄(405). DELETE /mcp → 첫 줄
S5  createBasket → 응답 본문. HANDLE=$(… | sed -n 's/.*"basketId":"\(bsk_[^"]*\)".*/\1/p' | head -1), 없으면 fail
S6  addItem(HANDLE, p4, 1) → 본문. getBasket(HANDLE) → 본문
S7  user2 login(새 JAR) → authorize "openid products:read" → consent products:read → token(USER2_TOKEN)
    → getBasket(HANDLE) with USER2_TOKEN → 본문("isError":true, 찾을 수 없습니다)
S8  checkout(HANDLE) with READ_TOKEN → 403, WWW-Authenticate(scope="orders:write")
S9  user의 JAR로 돌아와 step-up: authorize "openid products:read orders:write" → consent orders:write → token
    → checkout(HANDLE) → 본문(ord-…)
S10 같은 HANDLE로 다시 checkout → 본문("isError":true, 이미 주문했습니다)
```

- [ ] **Step 2: local-client 실행 스크립트를 만든다**

`docs/superpowers/captures/authz-local-client-run.sh`를 `stateless-local-client-run.sh`로 복사하고 바꾼다.
- `AS` 기본값 `http://localhost:9040`, `CLIENT_DIR`는 `practice/mcp-stateless-handle/local-client`.
- 머리 주석의 practice 이름·포트.
- 두 번째 consent(step-up)는 `products:read orders:write`를 체크한다(public client라 consent가 저장되지 않아 두 scope를 모두 묻는다).
- 출력 머리 줄: `# mcp-stateless-handle local-client 실행 — 날짜 (stateless-local-client-run.sh, browser 대신 curl)`.

```bash
chmod +x docs/superpowers/captures/mcp-stateless-walkthrough.sh docs/superpowers/captures/stateless-local-client-run.sh
```

- [ ] **Step 3: 서버를 띄우고 walkthrough를 기록한다**

```bash
cd practice/mcp-stateless-handle && (nohup ./run.sh > /tmp/stateless-run.out 2>&1 &) ; cd -
until grep -qE "\[실패\]|준비됨\] shop-agent" /tmp/stateless-run.out; do sleep 2; done; tail -3 /tmp/stateless-run.out
cd docs/superpowers/captures && ./mcp-stateless-walkthrough.sh > 2026-10-01-stateless-walkthrough.txt; echo "exit=$?"
```

Expected(파일을 열어 확인한다):
- S3에 `Mcp-Session-Id`가 없다.
- S4의 GET은 `405`, DELETE는 `2xx`가 아니다.
- S5에 `bsk_`로 시작하는 handle과 `structuredContent`가 있다.
- S7에서 user2는 `"isError":true`와 "찾을 수 없습니다"를 받는다.
- S8은 `403`과 `scope="orders:write"`다.
- S9는 `ord-`로 시작하는 주문 번호를 받는다.
- S10은 "이미 주문했습니다"다.
- `practice/mcp-stateless-handle/logs/shop-mcp-server.log`에 `checkout 호출 (사용자=user, client_id=stateless-shop-agent)`와 `scope 부족 — … tool=checkout, 필요한 scope=orders:write`가 있다.

- [ ] **Step 4: 서버를 다시 띄우고 local-client를 기록한다**

재고와 consent를 처음 상태로 돌린다.

```bash
cd practice/mcp-stateless-handle && ./stop.sh && (nohup ./run.sh > /tmp/stateless-run.out 2>&1 &) ; cd -
until grep -qE "\[실패\]|준비됨\] shop-agent" /tmp/stateless-run.out; do sleep 2; done
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
cd docs/superpowers/captures && ./stateless-local-client-run.sh > 2026-10-01-stateless-local-client.txt; echo "exit=$?"
```

Expected: `createBasket` → `addItem` 두 번 → `getBasket` → `getBasket(모르는 ID): [오류] …` → `403 insufficient_scope — 필요한 scope: orders:write` → `[6] step-up: products:read orders:write로 …` → `[3]`·`[4]`(scope에 `orders:write`) → `[7]` → `checkout: 주문 ord-…를 접수했습니다.` 순서다. 끝에 `실패:`가 없다.

- [ ] **Step 5: 웹 agent를 browser로 확인한다(controller가 한다)**

`http://localhost:8150`에서 `user`/`password`로 login하고 다음을 확인한다. 로컬 모델이라 답 하나에 30\~100초 걸린다.
1. login consent의 체크박스는 `products:read` 하나다.
2. "장바구니 만들고 인체공학 마우스 하나 담아 줘" → `createBasket`과 `addItem`이 불리고 답에 장바구니가 나온다.
3. "휴대용 SSD도 두 개 담아 줘" → 같은 handle로 `addItem`이 불린다(MCP Server 로그의 사용자와 handle 확인. 새 `createBasket`이 불리면 대화 기억이 handle을 잇지 못한 것이다).
4. "결제해 줘" → 채팅에 consent 카드(`orders:write`) → consent 화면의 새 체크박스는 `orders:write` 하나 → 허락 → 질문이 다시 가서 주문 번호가 나온다.
5. "새 대화"를 누르고 "장바구니 보여 줘"라고 하면 새 장바구니를 만들거나 장바구니를 모른다고 답한다(기억이 지워졌다).

3이 실패하면 `spring.ai.ollama.chat.num-ctx: 8192`를 agent `application.yml`에 넣고 다시 확인한다. 넣었다면 이유를 주석에 적는다.
확인이 끝나면 `./stop.sh`로 내리고 포트(9040, 8151, 8150)가 비었는지 본다.

- [ ] **Step 6: 커밋**

```bash
git add docs/superpowers/captures/mcp-stateless-walkthrough.sh docs/superpowers/captures/stateless-local-client-run.sh \
  docs/superpowers/captures/2026-10-01-stateless-walkthrough.txt docs/superpowers/captures/2026-10-01-stateless-local-client.txt
git commit -m "docs(captures): mcp-stateless-handle의 stateless·handle curl 캡처와 local-client 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: practice README

**Files:**
- Create: `practice/mcp-stateless-handle/README.md`, `practice/mcp-stateless-handle/diagrams/`

**Interfaces:**
- Consumes: Task 9의 캡처와 browser 확인 결과, 안내서 11장 파일 이름 `practice/mcp-guide/11-stateless-and-handle.md`(Task 11에서 만든다. 이 task의 검사에서 그 링크만 `link` 위반으로 남는 것은 허용한다).

- [ ] **Step 1: 저장소 스킬과 기준 README를 읽는다**

`.claude/skills/writing-practice-docs/SKILL.md`, `templates.md`, `practice/mcp-security-authz/README.md`(가장 가까운 기준), `practice/mcp-security-authn-official/README.md`(실행·코드 지도 형식).

- [ ] **Step 2: README를 쓴다**

구성(authz README와 같은 형식):
1. 소개 3\~4줄: authz를 복사해 MCP Server를 session 없이 돌리고, 장바구니 상태를 handle로 다루는 practice. 안내서 11장 링크.
2. `## mcp-security-authz와 다른 점` — 표(바뀐 곳 · authz · 이 practice · 안내서 절):
   - MCP Server: `protocol: STATELESS`(session header 없음, GET `405`, DELETE 없음), `McpCaller`와 transport의 `contextExtractor`, `BasketStore`(`<sub>:<handle>`, TTL, 개수 제한), `BasketTools` 네 개와 `checkout`의 `orders:write`, `CallToolResult`(`structuredContent`, `isError`)
   - auth-server: `orders:write`, `user2`
   - agent: 공유 MCP client가 그대로 맞는 이유, 대화 기억(`ChatMemoryConfig`, tool loop 안쪽), 끊긴 turn 되돌리기, "새 대화"
   - local-client: 장바구니 시나리오(`updateStock` 대신)
   - 이어서 짧은 절 셋: "session 없는 서버"(mermaid 한 장: 같은 MCP client로 user와 user2가 번갈아 부르는 모습, 요청마다 token만), "handle과 소유권"(오류 표), "대화 기억과 끊긴 turn".
3. `## 실행` — 포트(9040/8151/8150), 계정 둘, `./run.sh`/`./stop.sh`, `local-client` 실행, JAVA_HOME 한 줄. authz README와 같은 문장 형식.
4. `## 코드 지도` — 이 practice에만 있거나 authz와 다른 클래스(module · 클래스 · 하는 일 · 안내서). 나머지는 authz README 코드 지도 링크.
5. `## 직접 확인할 것` — 표(할 일 · 기대 결과): Task 9 Step 3·4·5의 기대 결과를 캡처 값으로 쓴다.
6. `## 더 읽을 것` — 안내서 11장, 6장 6.7, 9장, 준수표 부록, 로드맵의 다음 practice(scope별 tool 목록) 한 줄.

- [ ] **Step 3: 검사한다**

```bash
MMDC=/Users/starryeye/.npm/_npx/668c188756b835f3/node_modules/.bin/mmdc \
  python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-stateless-handle/README.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-stateless-handle/README.md
```

Expected: `11-stateless-and-handle.md`로 가는 `link` 위반만 남는다.

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-stateless-handle/README.md practice/mcp-stateless-handle/diagrams
git commit -m "docs(stateless): mcp-stateless-handle README

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 안내서 11장 — stateless와 handle

**Files:**
- Create: `practice/mcp-guide/11-stateless-and-handle.md`, `practice/mcp-guide/diagrams/11-stateless-and-handle-*.png`, `practice/mcp-guide/diagrams/.sources.json`(갱신)

**Interfaces:**
- Consumes: Task 9의 캡처 두 개, Task 1–8의 코드, spec 1절의 개념.

- [ ] **Step 1: 읽는다**

스킬(`SKILL.md`, `templates.md`), 기준 장 `practice/mcp-guide/03-discovery.md`, 이웃 장 `06-mcp-call-and-validation.md`(6.7 session과 사용자), `09-versions.md`(2026-07-28), `10-scope-and-step-up.md`, spec 1절, 캡처 두 개.
명세 원문: 2025-11-25 Transports(Streamable HTTP의 session, GET, DELETE), 2025-11-25 Security Best Practices(Session Hijacking), 2026-07-28 changelog, SEP-2567(`seps/2567-sessionless-mcp.md`), 2026-07-28 Security Best Practices(State Handle Hijacking), 2025-11-25 server/tools(`structuredContent`, `isError`).
raw 원문은 `https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/`에서 받는다. Security Best Practices는 `docs/docs/<version>/tutorials/security/security_best_practices.mdx`에 있다(`docs/specification/` 아래가 아니다).

- [ ] **Step 2: 장을 쓴다**

제목 `# 11. stateless와 handle — session 없이 상태를 다룬다`. 장의 틀을 따른다. 아래 제목은 README가 앵커로 링크하므로 그대로 쓴다.
1. `## 11.1 stateless의 필요성` — session이 하는 일, 공유 MCP client에서 session에 상태를 두면 사용자끼리 섞이는 문제와 종료 `DELETE`(준수표 36번), 서버를 여러 대로 늘리기, 2026-07-28의 session 제거. 6장 6.7과 이어지는 점.
2. `## 11.2 시퀀스 다이어그램` — token만으로 도는 요청, `createBasket` → handle → `addItem` → `checkout`(`403 orders:write` → step-up → 다시). mermaid + PNG 링크.
3. `## 11.3 1단계: session 없는 서버의 요청과 응답` — 캡처 S3·S4의 header(session header 없음), GET `405`, DELETE, SDK client가 `405`를 받으면 요청·응답 방식으로 도는 것, 종료 `DELETE`가 없는 것. 요청 형식은 2025-11-25 그대로라는 점(2026-07-28의 `initialize` 제거는 다루지 않음).
4. `## 11.4 2단계: handle을 만들고 넘긴다` — 캡처 S5·S6. `createBasket`의 text와 `structuredContent`, 모델·앱이 handle을 인자로 넘기는 것, handle은 protocol 개념이 아니라 tool 설계 패턴이라는 점(SEP-2567), tool 설명에 수명을 적는 이유.
5. `## 11.5 3단계: handle을 사용자에게 묶는다` — 캡처 S7(user2 거절), `<sub>:<handle>` key, `sub`는 token에서 온다(`McpCaller`, `contextExtractor`), handle을 가진 것만으로 인증하지 않는 이유, 무작위 128bit, 오류 표(찾을 수 없음·만료·이미 주문·개수)와 다른 사용자에게 존재가 드러나지 않는 이유, 결제는 한 번만(S10).
6. `## 11.6 서명 handle과 session을 쓰지 않는 이유` — 두 방식의 비교 표와 짧은 설명(spec의 비교 절).
7. `## 11.7 웹 agent: tool 결과까지 기억하는 대화` — 기본 배치에서 tool 결과가 기억에 남지 않는 이유, 내부 history 끄기 + tool loop 안쪽 기억, 대화 기억은 `sub`마다, step-up으로 끊긴 turn 되돌리기, "새 대화". ChatGPT·Claude도 대화 기록에 tool 결과를 둔다는 점은 확인한 사실만 한 문장으로(확인하지 못했으면 쓰지 않는다).
8. `## 11.8 사용자 기기의 앱: 코드가 handle을 들고 다닌다` — `local-client` 출력(캡처) 인용, `structuredContent`에서 handle을 꺼내는 코드, `checkout`의 step-up(10장과 같은 흐름).
9. `## 11.9 서버 코드에서 보기` — `McpTransportConfig`의 stateless transport bean, `McpCaller`, `BasketStore#open`, `BasketTools#checkout`. 코드 인용은 짧게, `/* ... */`로 줄인다.
10. `## 11.10 client 코드에서 보기` — agent의 `ChatMemoryConfig`, `ChatController`(되돌리기), `local-client`의 `McpCalls`.
11. `## 11.11 다루지 않는 것` — 2026-07-28의 요청 형식(`initialize` 제거와 `_meta`, `server/discover`, `Mcp-Name`, `subscriptions/listen`, SSE 재개 제거), 영속 저장과 서버 여러 대, scope별 tool 목록(다음 practice). 한 줄씩.
12. `## 11.12 직접 해 보기` — 저장소 최상위에서 실행할 수 있는 명령: `practice/mcp-stateless-handle/run.sh`, token 없이 되는 curl(`401`), 캡처 스크립트, 웹 agent 질문 순서.
13. `## 11.13 정리` — 4\~5줄.
14. `## 11.14 명세 근거` — 표(내용 · 명세 · 요구 수준): 2025-11-25 Transports의 session(서버는 session ID를 줄 수 있다), GET(`text/event-stream` 아니면 `405`), session 종료 DELETE; 2025-11-25 Security Best Practices Session Hijacking(`<user_id>:<session_id>`, session으로 인증하지 않음); 2026-07-28 changelog(session 제거); SEP-2567(handle은 규칙이 아니라 권하는 설계 — 요구 수준 칸에 "권고(규칙 아님)"); 2026-07-28 Security Best Practices State Handle Hijacking(가진 것만으로 인증하지 않음, 안전한 무작위, `<user_id>:<handle>`); 2025-11-25 server/tools(`structuredContent`, tool 실행 오류는 `isError`). 원문을 확인하고 요구 수준을 적는다.
15. 끝 줄: `[← 10장](10-scope-and-step-up.md) · [목차](README.md) · [부록: API 레퍼런스 →](reference-api.md)`.

문체 규칙(Global Constraints)을 지킨다. 요청·응답은 캡처 값을 그대로 쓰고, JSON은 들여쓰고 핵심 field만 남긴다. 길이는 `10-scope-and-step-up.md`보다 짧게(500줄 안팎) 한다.

- [ ] **Step 3: 다이어그램과 검사**

```bash
MMDC=/Users/starryeye/.npm/_npx/668c188756b835f3/node_modules/.bin/mmdc \
  python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-guide/11-stateless-and-handle.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/11-stateless-and-handle.md practice/mcp-stateless-handle/README.md
```

Expected: 위반 0(README의 11장 링크도 이제 풀린다).

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-guide/11-stateless-and-handle.md practice/mcp-guide/diagrams
git commit -m "docs(guide): 11장 stateless와 handle

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 안내서와 부록, 입구 문서를 잇는다

**Files:**
- Modify: `practice/mcp-guide/README.md`, `06-mcp-call-and-validation.md`, `09-versions.md`, `10-scope-and-step-up.md`, `reference-api.md`, `reference-compliance.md`
- Modify: `README.md`(저장소 최상위), `practice/mcp-security-authz/README.md`(더 읽을 것 한 줄), `.claude/skills/writing-practice-docs/SKILL.md`(예시 값 예외, 장 목록)

- [ ] **Step 1: 안내서 입구와 장 사이 링크**

- `practice/mcp-guide/README.md`: 장 목록 표에 11장 행(한 줄 요약 · 직접 해 보는 것), 읽는 순서에 "11장은 10장 다음에 읽는다" 한 줄, 다른 practice 목록에 `mcp-stateless-handle` 한 줄, "1장부터 10장까지" 같은 개수 표현을 11장으로.
- 6장 6.7(session과 사용자) 끝: "session 없이 도는 서버와 handle은 [11장](11-stateless-and-handle.md)에서 `mcp-stateless-handle` practice로 본다" 식의 한 문장.
- 9장의 2026-07-28 session 제거를 설명하는 곳: 11장을 가리키는 한 문장.
- 10장 끝 nav 줄의 `[부록: API 레퍼런스 →](reference-api.md)`를 `[11장 →](11-stateless-and-handle.md)`로 바꾼다.

- [ ] **Step 2: 부록**

- `reference-api.md`: Streamable HTTP(session header, GET, DELETE) 설명 행에 "stateless practice" 동작을 더한다(캡처 S 번호로 인용. 부록은 캡처 번호를 써도 된다). 끝 nav 줄 앞 링크를 `[← 11장](11-stateless-and-handle.md)`으로 바꾼다.
- `reference-compliance.md`: "`mcp-security-authz`에서 달라지는 행" 절 다음에 `## mcp-stateless-handle에서 달라지는 행` 절을 더한다. 표(행 번호 · 항목 · authz · stateless · 근거 · 요구 수준): 36번(agent 종료 `DELETE`에 token 없음 → session이 없어 DELETE를 보내지 않음), session 관련 행(session ID 생성·묶기가 있다면 그 행), 새 항목(handle을 사용자에 묶기, handle을 가진 것만으로 인증하지 않음, 안전한 무작위 handle, 만료). 근거는 클래스 이름과 캡처 S 번호. 판정 어휘는 부록의 기존 표를 따른다.

- [ ] **Step 3: 저장소 README, authz README, 문서 스킬**

- 저장소 `README.md`의 practice 목록에 `mcp-stateless-handle` 행(stateless와 handle, 네 module).
- `practice/mcp-security-authz/README.md` "더 읽을 것"의 다음 practice 줄을 `[mcp-stateless-handle](../mcp-stateless-handle/README.md)` 링크로 바꾼다.
- `.claude/skills/writing-practice-docs/SKILL.md`: 장 목록을 `11-stateless-and-handle.md`까지로, 예시 값 예외에 "11장은 `mcp-stateless-handle` 값(`http://localhost:9040`, `http://localhost:8151/mcp`, `http://localhost:8150`)과 그 캡처를 쓴다"를 더한다.

- [ ] **Step 4: 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md practice/mcp-stateless-handle/README.md
python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
```

Expected: 위반 0, 스킬 테스트 통과.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-guide README.md practice/mcp-security-authz/README.md .claude/skills/writing-practice-docs/SKILL.md
git commit -m "docs(guide): 11장과 stateless practice를 안내서·부록·입구 문서에 잇기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 마지막 확인

- [ ] **Step 1: 모든 테스트**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for d in practice/mcp-stateless-handle/{auth-server,shop-mcp-server,shop-agent,local-client} \
         practice/mcp-security-authz/{auth-server,shop-mcp-server,shop-agent,local-client}; do
  (cd "$d" && ./gradlew -q cleanTest test && echo "$d OK")
done
python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
```

Expected: 여덟 줄 모두 `OK`, 스킬 테스트 통과. authz는 바뀌지 않았어야 한다(`git diff main -- practice/mcp-security-authz`에는 README 한 줄만).

- [ ] **Step 2: 문서 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md practice/mcp-stateless-handle/README.md
```

Expected: 위반 0, 다이어그램 PNG 최신.

- [ ] **Step 3: 커밋할 것이 남았는지 본다**

```bash
git status --short
```

Expected: 비어 있다(`logs/`, `build/`는 `.gitignore`가 가린다).
