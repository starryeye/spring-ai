# mcp-tool-visibility (tool 목록과 권한) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mcp-stateless-handle`을 복사해, 사용자가 원래 할 수 없는 tool은 `tools/list`에서 숨기고 할 수 있지만 아직 허락하지 않은 tool은 보여 준 뒤 step-up하는 `practice/mcp-tool-visibility`와 안내서 12장을 만든다. agent는 사용자별 tool 목록을 access token별로 cache한다.

**Architecture:** MCP Server는 역할 표(`user` = 점원, 그 밖 = 손님)와 tool별 scope(`@RequiredScope`)로 보이는 tool을 정한다(`ToolVisibility`). SDK server와 WebMvc transport 사이에 `@Primary`로 끼운 `ToolVisibilityTransport`가 `tools/list` 결과를 거르고, 숨긴 tool의 `tools/call`에는 SDK가 없는 tool에 주는 것과 같은 JSON-RPC 오류를 만든다. `ToolScopeFilter`는 숨긴 tool이면 scope 검사를 건너뛴다. agent는 자동 tool provider를 끄고 `UserToolCatalog`(key = token의 SHA-256, TTL 5분)에서 질문마다 그 사용자의 목록을 꺼내 넣는다. 목록에 없는 tool을 모델이 부르면 turn을 되돌리고 `tool-unavailable` event를 보낸다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7.1.1(Spring Authorization Server 포함), Spring AI 2.0.1, MCP Java SDK 2.0.1, Jackson 3(`tools.jackson`), JUnit 5, MockMvc, bash + curl(캡처).

**Spec:** `docs/superpowers/specs/2026-10-05-mcp-tool-visibility-design.md`

## Global Constraints

- practice 폴더: `practice/mcp-tool-visibility/`, module 네 개: `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`.
- package: `dev.starryeye.visibility.authserver`, `dev.starryeye.visibility.mcpserver`, `dev.starryeye.visibility.agent`, `dev.starryeye.visibility.localclient`. 하위 package는 `mcp-stateless-handle`과 같다.
- 포트: auth-server 9050, shop-mcp-server 8161(`/mcp`), shop-agent 8160. `local-client`의 loopback redirect 등록값은 `http://127.0.0.1:8123/callback`(그대로).
- `client_id`: `visibility-shop-agent`(secret `visibility-shop-agent-secret`, confidential), `local-mcp-client`(public). cookie: `VISIBILITYAUTHSESSIONID`, `VISIBILITYAGENTSESSIONID`. 계정 `user`/`password`(점원), `user2`/`password`(손님). agent의 registration id는 `authserver`.
- scope는 그대로다: `products:read`(기본, 모든 MCP 요청), `products:write`(`updateStock`), `orders:write`(`checkout`). PRM의 `scopes_supported`와 `401`의 `scope`는 `products:read`만.
- 역할: 점원(`STAFF`)이 받을 수 있는 scope는 `products:read`, `products:write`, `orders:write`. 손님(`CUSTOMER`)은 `products:read`, `orders:write`. 역할 표에는 `user` → 점원만 있고, 표에 없는 사용자(`user2` 포함)와 `sub`가 없는 요청은 손님이다. Authorization Server는 역할을 모른다.
- 숨긴 tool의 `tools/call` 응답은 SDK 2.0.1이 없는 tool에 주는 응답과 같다: HTTP 200, `application/json`, `WWW-Authenticate` 없음, 본문 `{"jsonrpc":"2.0","id":<id>,"error":{"code":-32602,"message":"Unknown tool: invalid_tool_name","data":"Tool not found: <이름>"}}`.
- `tools/call` 검사 순서: 기본 scope → 보이는 tool인가(아니면 scope 검사 없이 transport로 넘겨 "모르는 tool") → tool의 scope(없으면 `403 insufficient_scope`).
- agent의 tool 목록 cache: key는 access token 값의 SHA-256 hex, TTL 5분, 만료 항목은 꺼낼 때 지운다, "모르는 tool" 오류면 그 key를 버린다. 자동 tool provider는 `spring.ai.mcp.client.toolcallback.enabled: false`로 끈다.
- 버전: Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1(`mcp-bom` 2.0.1). 다른 practice의 코드와 버전은 건드리지 않는다.
- 요청 형식은 2025-11-25 그대로다. 2026-07-28의 `ttlMs`·`cacheScope` field, `_meta`, `server/discover`, `subscriptions/listen`은 코드로 다루지 않는다.
- Gradle은 `JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)`로 돌린다.
- 서버를 띄웠으면 끝난 뒤 포트로 내린다: `lsof -ti tcp:PORT -sTCP:LISTEN`(`-sTCP:LISTEN` 없이 쓰면 연결된 다른 process까지 죽는다). `run.sh`는 background로 돌린다. filesystem 전체를 뒤지는 `find /`는 쓰지 않는다.
- `.superpowers/`, `logs/`, `build/`, `.gradle/`은 절대 `git add`하지 않는다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 코드 주석·javadoc·문서는 저장소 스킬 `.claude/skills/writing-practice-docs/SKILL.md` 문체를 따른다: 기술 용어는 흔한 말도 영어(token, client, scope, consent, login, browser, header, parameter, session, handle, cache…), 영어·코드 뒤 조사는 붙여 쓴다("token을"), 번역 어투 금지(싣다, 걸다, 배선, 드러나다, 내놓다, 형식을 뜻하는 "모양"), 한 줄에 한 문장, 이유를 규칙보다 먼저, 계획의 task 번호를 주석에 쓰지 않는다. 문서의 범위 `~`는 `\~`로 쓴다.
- 안내서 장 본문에는 MUST 같은 요구 수준 단어·캡처 번호·테스트 메서드 이름·HTML을 쓰지 않는다. 요구 수준은 장 끝 "명세 근거" 표에만. 부록(`reference-*.md`)은 예외.
- 제품 예시는 OpenAI(ChatGPT, Codex CLI)와 Anthropic(claude.ai, Claude Desktop, Claude Code(CLI)) 것만 든다. MCP Server 사례(GitHub)는 10장에 이미 인용한 사실만 쓴다.

## Review Focus

1. 손님이 숨긴 tool(`updateStock`)을 부르면 `403`이나 step-up이 아니라 "모르는 tool"이 오고, 같은 길이의 정말 없는 이름과 응답이 구별되지 않는다. `products:write`가 든 token이어도 같다. — Task 4 `손님이_숨긴_tool을_부르면_없는_tool과_같은_응답이다`, `손님은_products_write가_있어도_숨긴_tool을_부를_수_없다`.
2. 점원이 조회 token으로 `updateStock`을 부르면 여전히 `403 insufficient_scope`(`products:write`)다. 숨기기가 step-up 입구를 막지 않는다. — Task 4 `점원의_조회_token은_updateStock에서_403_step_up이다`.
3. 두 사용자가 번갈아 채팅해도 서로의 tool 목록을 받지 않는다. — Task 5 `token이_다르면_목록을_따로_둔다`, Task 6 `질문마다_그_사용자의_tool_목록을_넣는다`.
4. TTL이 지난 목록, step-up으로 바뀐 token, "모르는 tool" 뒤에는 목록을 다시 받고, 만료 항목이 쌓이지 않는다. — Task 5 `TTL이_지나면_다시_받고_만료_항목은_지운다`, `step_up으로_token이_바뀌면_다시_받는다`, `모르는_tool_오류가_나면_그_token의_목록을_버린다`.
5. 모델이 목록에 없는 tool을 불러도 채팅 stream이 오류로 깨지지 않고, 안내 event가 가며 그 turn이 기억에서 되돌려진다. — Task 7 `목록에_없는_tool을_부른_turn은_되돌리고_안내한다`.

---

### Task 1: `mcp-stateless-handle`을 복사해 `practice/mcp-tool-visibility`를 만든다

**Files:**
- Create: `practice/mcp-tool-visibility/**` (`mcp-stateless-handle`의 `auth-server`, `shop-mcp-server`, `shop-agent`, `local-client`, `run.sh`, `stop.sh`, `.gitignore`. `README.md`·`diagrams/`는 복사하지 않는다 — Task 10에서 새로 쓴다)

**Interfaces:**
- Produces: 이름·포트·package가 바뀐 네 module. `mcp-stateless-handle`과 같은 테스트가 모두 통과한다(auth-server 44, shop-mcp-server 91, shop-agent 95, local-client 52).

- [ ] **Step 1: 복사하고 package 폴더를 옮긴다**

저장소 최상위 폴더에서:

```bash
set -euo pipefail
SRC=practice/mcp-stateless-handle
DST=practice/mcp-tool-visibility
test ! -e "$DST"
rsync -a --exclude build --exclude .gradle --exclude .idea --exclude logs --exclude out --exclude bin \
  --exclude README.md --exclude diagrams "$SRC/" "$DST/"
for m in auth-server shop-mcp-server shop-agent local-client; do
  for s in main test; do
    d="$DST/$m/src/$s/java/dev/starryeye"
    if [ -d "$d/stateless" ]; then mv "$d/stateless" "$d/visibility"; fi
  done
done
```

- [ ] **Step 2: 이름과 포트를 바꾼다**

`protocol: STATELESS`, "stateless transport" 같은 낱말은 이 practice에서도 맞는 설명이라 바꾸지 않는다. 바꾸는 것은 package, client·application 이름, cookie, 화면 제목, 포트뿐이다.

```bash
python3 - <<'EOF'
import pathlib, re
root = pathlib.Path('practice/mcp-tool-visibility')
rules = [
    (r'dev\.starryeye\.stateless\.', 'dev.starryeye.visibility.'),
    (r'stateless-shop-agent-secret', 'visibility-shop-agent-secret'),
    (r'stateless-shop-agent', 'visibility-shop-agent'),
    (r'stateless_shop_agent_', 'visibility_shop_agent_'),
    (r'stateless-shop-mcp-server', 'visibility-shop-mcp-server'),
    (r'stateless-auth-server', 'visibility-auth-server'),
    (r'STATELESSAUTHSESSIONID', 'VISIBILITYAUTHSESSIONID'),
    (r'STATELESSAGENTSESSIONID', 'VISIBILITYAGENTSESSIONID'),
    (r'<title>stateless shop-agent</title>', '<title>visibility shop-agent</title>'),
    (r'(?<!\d)9040(?!\d)', '9050'),
    (r'(?<!\d)8151(?!\d)', '8161'),
    (r'(?<!\d)8150(?!\d)', '8160'),
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
grep -rIn "dev\.starryeye\.stateless\|stateless-shop\|stateless-auth\|STATELESS[A-Z]*SESSIONID\|9040\|8151\|8150" \
  practice/mcp-tool-visibility --exclude-dir=build --exclude-dir=.gradle || echo "남은 것 없음"
```

Expected: `남은 것 없음`. 주석에서 "stateless practice"나 "안내서 11장"을 가리키는 설명은 남아도 된다. 이 practice 자신을 가리키는 설명이면 "이 practice"로 고친다.

- [ ] **Step 3: 네 module의 테스트를 돌린다**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for m in auth-server shop-mcp-server shop-agent local-client; do
  (cd practice/mcp-tool-visibility/$m && ./gradlew -q test && echo "$m OK")
done
```

Expected: 네 줄 모두 `OK`. 테스트 개수는 auth-server 44, shop-mcp-server 91, shop-agent 95, local-client 52다(보고서에 적는다).

- [ ] **Step 4: 커밋**

```bash
git status --short practice/mcp-tool-visibility | grep -E '/(build|logs|\.gradle|\.idea)/' && echo "제외할 파일이 섞였다" || true
git add practice/mcp-tool-visibility
git commit -m "feat(visibility): mcp-stateless-handle을 복사해 mcp-tool-visibility practice 시작

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 역할로 보이는 tool을 정하는 `ToolVisibility`

**Files:**
- Create: `practice/mcp-tool-visibility/shop-mcp-server/src/main/java/dev/starryeye/visibility/mcpserver/tool/ToolVisibility.java`
- Modify: `.../mcpserver/security/McpCaller.java`(`subject(McpTransportContext)`)
- Modify: `.../mcpserver/config/McpTransportConfig.java`(`ToolVisibility` bean)
- Test: `.../test/.../mcpserver/tool/ToolVisibilityTest.java`, `.../test/.../mcpserver/security/McpCallerTest.java`(test 하나 추가)

**Interfaces:**
- Consumes: `ToolScopeRegistry`(`all()`, `scopeFor(String)`).
- Produces:
  - `ToolVisibility(ToolScopeRegistry registry, Map<String, ToolVisibility.Role> roleBySubject)`
  - `enum ToolVisibility.Role { STAFF, CUSTOMER }`, `Set<String> grantable(Role)`, `Role roleOf(String subject)`, `boolean hidden(String subject, String tool)`, `List<McpSchema.Tool> visible(String subject, List<McpSchema.Tool> tools)`
  - `static Optional<String> McpCaller.subject(McpTransportContext)`
  - bean `ToolVisibility toolVisibility(ToolScopeRegistry)` — 역할 표 `Map.of("user", Role.STAFF)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tool/ToolVisibilityTest.java`:

```java
package dev.starryeye.visibility.mcpserver.tool;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자가 원래 받을 수 없는 scope의 tool만 숨기는지 본다.
 * 받을 수는 있지만 아직 없는 scope의 tool은 숨기지 않는다(그 tool은 부를 때 step-up한다).
 */
class ToolVisibilityTest {

	ToolScopeRegistry registry = ToolScopeRegistry.scan(new ProductTools(new ProductRepository()),
			new BasketTools(null, null));

	ToolVisibility visibility = new ToolVisibility(this.registry, Map.of("user", ToolVisibility.Role.STAFF));

	Set<String> 숨긴_tool(String subject) {
		return this.registry.all().keySet().stream()
				.filter(tool -> this.visibility.hidden(subject, tool))
				.collect(Collectors.toSet());
	}

	@Test
	void 점원에게는_어떤_tool도_숨기지_않는다() {
		assertThat(숨긴_tool("user")).isEmpty();
	}

	@Test
	void 손님에게는_재고를_바꾸는_updateStock만_숨긴다() {
		assertThat(숨긴_tool("user2")).containsExactly("updateStock");
	}

	@Test
	void 역할_표에_없는_사용자와_sub가_없는_요청은_손님이다() {
		assertThat(this.visibility.roleOf("stranger")).isEqualTo(ToolVisibility.Role.CUSTOMER);
		assertThat(this.visibility.roleOf(null)).isEqualTo(ToolVisibility.Role.CUSTOMER);
		assertThat(this.visibility.hidden(null, "updateStock")).isTrue();
	}

	@Test
	void 등록되지_않은_이름은_숨긴_tool이_아니다() {
		// 없는 tool은 SDK가 "모르는 tool"로 답한다. 숨긴 tool도 같은 답을 받게 하므로 여기서는 구분만 한다.
		assertThat(this.visibility.hidden("user2", "noSuchTool")).isFalse();
	}

	@Test
	void 손님은_주문_scope는_받을_수_있고_재고_변경_scope는_받을_수_없다() {
		assertThat(this.visibility.grantable(ToolVisibility.Role.CUSTOMER))
				.containsExactlyInAnyOrder("products:read", "orders:write");
		assertThat(this.visibility.grantable(ToolVisibility.Role.STAFF))
				.containsExactlyInAnyOrder("products:read", "products:write", "orders:write");
	}
}
```

`security/McpCallerTest.java`에 test 하나를 더한다(같은 package라 `SUBJECT`를 쓸 수 있다):

```java
	@Test
	void subject는_인증된_sub만_돌려준다() {
		assertThat(McpCaller.subject(McpTransportContext.create(java.util.Map.of(McpCaller.SUBJECT, "user2"))))
				.contains("user2");
		assertThat(McpCaller.subject(McpTransportContext.EMPTY)).isEmpty();
		assertThat(McpCaller.subject(McpTransportContext.create(java.util.Map.of(McpCaller.SUBJECT, " "))))
				.isEmpty();
	}
```

(`McpTransportContext`·`assertThat` import가 그 파일에 없으면 더한다.)

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-tool-visibility/shop-mcp-server
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
./gradlew test --tests '*ToolVisibilityTest*' --tests '*McpCallerTest*'
```

Expected: 컴파일 실패(`ToolVisibility`, `McpCaller.subject`가 없다).

- [ ] **Step 3: 구현한다**

`tool/ToolVisibility.java`:

```java
package dev.starryeye.visibility.mcpserver.tool;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 권한(역할)으로 보이는 tool을 정한다(안내서 12장).
 *
 * <p>지금 token에 없는 scope의 tool을 숨기면 모델이 그 tool을 몰라 부르지 않고, step-up이 시작될 계기가 사라진다.
 * 그래서 "이 사용자가 언젠가 받을 수 있는 scope인가"로 숨길지를 정한다.
 * 받을 수 없으면 숨기고, 받을 수 있지만 아직 없으면 보여 준 뒤 부를 때 {@code 403}으로 step-up한다.
 *
 * <p>권한은 MCP Server가 자기 표로 판단한다. token은 누구인지({@code sub})만 알려 준다.
 * tool별 scope는 {@link ToolScopeRegistry}의 것을 그대로 쓴다.
 */
public final class ToolVisibility {

	/** 점원은 재고를 바꿀 수 있고, 손님은 바꿀 수 없다. */
	public enum Role {
		STAFF, CUSTOMER
	}

	private static final Map<Role, Set<String>> GRANTABLE = Map.of(
			Role.STAFF, Set.of("products:read", "products:write", "orders:write"),
			Role.CUSTOMER, Set.of("products:read", "orders:write"));

	private final ToolScopeRegistry registry;

	private final Map<String, Role> roleBySubject;

	public ToolVisibility(ToolScopeRegistry registry, Map<String, Role> roleBySubject) {
		this.registry = registry;
		this.roleBySubject = Map.copyOf(roleBySubject);
	}

	/** 이 역할이 받을 수 있는 scope다. */
	public Set<String> grantable(Role role) {
		return GRANTABLE.get(role);
	}

	/** 권한을 모르는 사용자에게 넓은 권한을 주지 않으려고, 표에 없으면 손님으로 본다. */
	public Role roleOf(String subject) {
		return (subject == null) ? Role.CUSTOMER : this.roleBySubject.getOrDefault(subject, Role.CUSTOMER);
	}

	/**
	 * 등록된 tool인데 역할이 그 scope를 받을 수 없으면 숨긴다.
	 * 등록되지 않은 이름은 숨긴 tool이 아니다. SDK가 "모르는 tool"로 답한다.
	 */
	public boolean hidden(String subject, String tool) {
		return this.registry.all().containsKey(tool)
				&& !grantable(roleOf(subject)).contains(this.registry.scopeFor(tool));
	}

	/** client cache와 모델의 prompt cache가 맞도록, 순서는 SDK가 준 순서 그대로 둔다. */
	public List<McpSchema.Tool> visible(String subject, List<McpSchema.Tool> tools) {
		return tools.stream().filter(tool -> !hidden(subject, tool.name())).toList();
	}
}
```

`security/McpCaller.java`에 메서드를 더한다(`java.util.Optional` import):

```java
	/** stateless transport가 넣어 둔 token의 sub다. 인증이 없으면 비어 있다. */
	public static Optional<String> subject(McpTransportContext context) {
		return (context.get(SUBJECT) instanceof String subject && !subject.isBlank())
				? Optional.of(subject) : Optional.empty();
	}
```

`config/McpTransportConfig.java`에 bean을 더한다(`ToolVisibility`, `java.util.Map` import):

```java
	/**
	 * 학습용 역할 표다. {@code user}만 점원이고, 표에 없는 사용자({@code user2} 포함)는 손님이다.
	 * 실제 서비스라면 이 표는 가게의 사용자 저장소에 있다.
	 */
	@Bean
	public ToolVisibility toolVisibility(ToolScopeRegistry registry) {
		return new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF));
	}
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*ToolVisibilityTest*' --tests '*McpCallerTest*'
./gradlew test
```

Expected: 모두 PASS. module 전체 97개(91 + `ToolVisibilityTest` 5 + `McpCallerTest` 1).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-mcp-server
git commit -m "feat(visibility): 역할로 보이는 tool을 정하는 ToolVisibility

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `tools/list`를 역할로 거르는 `ToolVisibilityTransport`

**Files:**
- Create: `.../mcpserver/config/ToolVisibilityTransport.java`
- Modify: `.../mcpserver/config/McpTransportConfig.java`(`@Primary` bean)
- Modify: `.../mcpserver/tool/ProductTools.java`(`updateStock` 설명), `.../mcpserver/tool/BasketTools.java`(`checkout` 설명)
- Test: `.../test/.../mcpserver/ToolListVisibilityTest.java`

**Interfaces:**
- Consumes: `ToolVisibility`(Task 2), `McpCaller.subject`(Task 2), 기존 `WebMvcStatelessServerTransport` bean.
- Produces: `public final class ToolVisibilityTransport implements McpStatelessServerTransport`, 생성자 `ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility)`. bean 이름 `toolVisibilityTransport`(`@Primary`). Task 4가 생성자에 `McpJsonMapper`를 더한다.

배경(설계 8절에서 확인): `WebMvcStatelessServerTransport`는 `final`이라 상속할 수 없다. 자동 구성의 `mcpStatelessSyncServer`는 transport를 `McpStatelessServerTransport` 타입으로 받고, router(`webMvcStatelessServerRouterFunction`)는 `WebMvcStatelessServerTransport` 타입으로 받는다. 그래서 감싸는 bean을 `@Primary`로 두면 server는 감싼 쪽을, router는 원래 bean을 쓴다. server는 생성 때 `setMcpHandler`를 한 번 부르고 `protocolVersions()`를 읽으므로 둘 다 위임한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ToolListVisibilityTest.java`:

```java
package dev.starryeye.visibility.mcpserver;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 서버가 사용자 역할에 따라 다른 tool 목록을 주는지 본다.
 * 목록은 역할로만 달라지고, token의 scope나 요청 횟수로는 달라지지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class ToolListVisibilityTest {

	static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":3,"method":"tools/list"}""";

	@Autowired
	MockMvc mockMvc;

	static String 토큰(String subject, String... scopes) {
		return McpAuthorizationStandardTest.토큰(McpAuthorizationStandardTest.ISSUER,
				McpAuthorizationStandardTest.RESOURCE, subject, Instant.now().plusSeconds(300),
				McpAuthorizationStandardTest.KEY, List.of(scopes));
	}

	String 응답(String token) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, TOOLS_LIST))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	List<String> 목록(String token) throws Exception {
		return JsonPath.read(응답(token), "$.result.tools[*].name");
	}

	@Test
	void 점원은_tool_7개를_모두_받는다() throws Exception {
		assertThat(목록(토큰("user", "products:read"))).containsExactlyInAnyOrder("searchProducts", "getStock",
				"updateStock", "createBasket", "addItem", "getBasket", "checkout");
	}

	@Test
	void 손님은_updateStock만_빠진_목록을_같은_순서로_받는다() throws Exception {
		List<String> staff = 목록(토큰("user", "products:read"));
		List<String> customer = 목록(토큰("user2", "products:read"));

		// 절대 순서는 실행 환경마다 다를 수 있어서, 점원 목록에서 updateStock만 뺀 것과 비교한다.
		assertThat(customer).isEqualTo(staff.stream().filter(name -> !name.equals("updateStock")).toList());
	}

	@Test
	void 같은_사용자는_몇_번을_받아도_같은_순서다() throws Exception {
		String token = 토큰("user2", "products:read");

		assertThat(목록(token)).isEqualTo(목록(token));
	}

	@Test
	void 목록은_역할로만_정해지고_token의_scope로는_바뀌지_않는다() throws Exception {
		assertThat(목록(토큰("user2", "products:read", "products:write", "orders:write")))
				.isEqualTo(목록(토큰("user2", "products:read")));
		assertThat(목록(토큰("user", "products:read", "products:write")))
				.isEqualTo(목록(토큰("user", "products:read")));
	}

	@Test
	void 처음_부르면_권한을_묻는_tool은_설명에_그렇게_적는다() throws Exception {
		String body = 응답(토큰("user", "products:read"));
		List<Map<String, Object>> tools = JsonPath.read(body, "$.result.tools[?(@.name in ['updateStock','checkout'])]");

		assertThat(tools).hasSize(2)
				.allSatisfy(tool -> assertThat((String) tool.get("description")).contains("처음 부르면"));
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*ToolListVisibilityTest*'
```

Expected: `손님은_updateStock만_빠진…`과 `…설명에_그렇게_적는다`가 FAIL(손님도 7개, 설명에 문장이 없다).

- [ ] **Step 3: 구현한다**

`config/ToolVisibilityTransport.java`:

```java
package dev.starryeye.visibility.mcpserver.config;

import dev.starryeye.visibility.mcpserver.security.McpCaller;
import dev.starryeye.visibility.mcpserver.tool.ToolVisibility;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SDK server와 WebMvc transport 사이에 끼어, {@code tools/list} 결과를 사용자 역할로 거른다(안내서 12장).
 *
 * <p>SDK와 Spring AI에는 사용자마다 목록을 거르는 hook이 없다.
 * {@code WebMvcStatelessServerTransport}는 {@code final}이라 상속할 수 없어서 감싼다.
 * server가 생성될 때 넘기는 handler를 감싸, 결과가 JSON으로 바뀌기 전에 {@code ListToolsResult}를 거른다.
 */
public final class ToolVisibilityTransport implements McpStatelessServerTransport {

	private static final Logger log = LoggerFactory.getLogger(ToolVisibilityTransport.class);

	private final McpStatelessServerTransport delegate;

	private final ToolVisibility visibility;

	public ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility) {
		this.delegate = delegate;
		this.visibility = visibility;
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler handler) {
		this.delegate.setMcpHandler(new Handler(handler));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	private final class Handler implements McpStatelessServerHandler {

		private final McpStatelessServerHandler sdk;

		Handler(McpStatelessServerHandler sdk) {
			this.sdk = sdk;
		}

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
				McpSchema.JSONRPCRequest request) {
			String subject = McpCaller.subject(context).orElse(null);
			Mono<McpSchema.JSONRPCResponse> response = this.sdk.handleRequest(context, request);
			if (!McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
				return response;
			}
			return response.map(r -> {
				if (!(r.result() instanceof McpSchema.ListToolsResult list)) {
					return r;
				}
				List<McpSchema.Tool> visible = visibility.visible(subject, list.tools());
				log.info("tools/list — 사용자={}, 역할={}, 보인 tool={}/{}", subject, visibility.roleOf(subject),
						visible.size(), list.tools().size());
				return McpSchema.JSONRPCResponse.result(r.id(), McpSchema.ListToolsResult.builder(visible)
						.nextCursor(list.nextCursor())
						.meta(list.meta())
						.build());
			});
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext context,
				McpSchema.JSONRPCNotification notification) {
			return this.sdk.handleNotification(context, notification);
		}
	}
}
```

`config/McpTransportConfig.java`에 bean을 더한다(`org.springframework.context.annotation.Primary` import). 기존 `webMvcStatelessServerTransport` bean은 그대로 둔다:

```java
	/**
	 * 자동 구성의 server는 transport를 {@code McpStatelessServerTransport} 타입으로 받으므로, {@code @Primary}인 이 bean이 간다.
	 * router는 {@code WebMvcStatelessServerTransport} 타입으로 받으므로 원래 bean이 그대로 간다.
	 */
	@Bean
	@Primary
	public ToolVisibilityTransport toolVisibilityTransport(WebMvcStatelessServerTransport transport,
			ToolVisibility visibility) {
		return new ToolVisibilityTransport(transport, visibility);
	}
```

tool 설명에 한 문장을 더한다. 모델이 권한이 아직 없다는 이유로 이 tool을 피하지 않게 하려는 것이다.
- `ProductTools.updateStock`: `+ "바뀐 뒤의 재고를 반환한다."` 뒤에 `+ " 처음 부르면 사용자에게 재고 변경 권한(products:write)을 묻는다."`
- `BasketTools.checkout`: `+ "사용자가 주문이나 결제를 분명히 요청할 때만 사용한다."` 뒤에 `+ " 처음 부르면 사용자에게 주문 권한(orders:write)을 묻는다."`

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*ToolListVisibilityTest*'
./gradlew test
```

Expected: 모두 PASS. 기존 route·scope·장바구니 테스트도 그대로 통과한다(router가 원래 transport를 쓰는지 확인하는 셈이다).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-mcp-server
git commit -m "feat(visibility): tools/list를 사용자 역할로 거르는 transport

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 숨긴 tool의 `tools/call`은 "모르는 tool"이다

**Files:**
- Modify: `.../mcpserver/config/ToolVisibilityTransport.java`(숨긴 tool 응답, 생성자에 `McpJsonMapper`)
- Modify: `.../mcpserver/filter/ToolScopeFilter.java`(검사 순서)
- Modify: `.../mcpserver/config/McpTransportConfig.java`(두 bean의 생성자 인자)
- Modify: `.../test/.../mcpserver/filter/ToolScopeFilterTest.java`(생성자 인자)
- Test: `.../test/.../mcpserver/HiddenToolCallTest.java`

**Interfaces:**
- Consumes: `ToolVisibility.hidden(String, String)`(Task 2), `ToolVisibilityTransport`(Task 3).
- Produces:
  - `ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility, McpJsonMapper jsonMapper)`, 상수 `ToolVisibilityTransport.UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name"`
  - `ToolScopeFilter(ToolScopeRegistry registry, ToolVisibility visibility, JsonMapper jsonMapper, Function<HttpServletRequest, String> resourceMetadataUrl)`

배경(설계 8절에서 확인): SDK 2.0.1은 없는 tool에 `-32602`, message `Unknown tool: invalid_tool_name`(고정 문자열), data `Tool not found: <이름>`으로 답한다. 이름 검사가 입력 검증보다 먼저라서 인자는 상관없다. tool spec 단위로 감싸면 입력 검증이 먼저 돌아 숨긴 tool이 드러나므로 handler에서 답한다. filter는 숨긴 tool이면 tool별 scope 검사를 건너뛰어, 정말 없는 tool과 같은 뒤 단계(버전 검사, transport, 직렬화, Security header)를 지나게 한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`HiddenToolCallTest.java`:

```java
package dev.starryeye.visibility.mcpserver;

import dev.starryeye.visibility.mcpserver.repository.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 손님이 숨긴 tool을 부르면 정말 없는 tool과 구별되지 않는 "모르는 tool" 오류가 온다.
 * scope를 늘려도 쓸 수 없는 tool이라 403(step-up)을 보내지 않는다.
 * 점원은 같은 tool에서 여전히 step-up한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(McpAuthorizationStandardTest.StubAuthorizationServer.class)
class HiddenToolCallTest {

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
				{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
				.formatted(tool, arguments);
	}

	MockHttpServletResponse 응답(String token, String body) throws Exception {
		return this.mockMvc.perform(McpScopeTest.mcp(token, body)).andReturn().getResponse();
	}

	static Map<String, List<String>> header들(MockHttpServletResponse response) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (String name : response.getHeaderNames()) {
			headers.put(name, response.getHeaders(name));
		}
		return headers;
	}

	@Test
	void 손님이_숨긴_tool을_부르면_없는_tool과_같은_응답이다() throws Exception {
		String token = 토큰("user2", "products:read");
		String arguments = "{\"productId\":\"p1\",\"quantity\":10}";
		// 이름이 data와 Content-Length에 들어가므로, 같은 길이의 없는 이름(updateStack)과 비교한다.
		MockHttpServletResponse hidden = 응답(token, 호출("updateStock", arguments));
		MockHttpServletResponse unknown = 응답(token, 호출("updateStack", arguments));

		assertThat(hidden.getStatus()).isEqualTo(200).isEqualTo(unknown.getStatus());
		assertThat(header들(hidden)).isEqualTo(header들(unknown)).doesNotContainKey("WWW-Authenticate");
		assertThat(hidden.getContentAsString(StandardCharsets.UTF_8).replace("updateStock", "NAME"))
				.isEqualTo(unknown.getContentAsString(StandardCharsets.UTF_8).replace("updateStack", "NAME"));
		assertThat(hidden.getContentAsString(StandardCharsets.UTF_8))
				.contains("\"code\":-32602", "\"message\":\"Unknown tool: invalid_tool_name\"",
						"\"data\":\"Tool not found: updateStock\"");
	}

	@Test
	void 손님은_products_write가_있어도_숨긴_tool을_부를_수_없다() throws Exception {
		int before = this.productRepository.findById("p1").orElseThrow().stock();

		String body = 응답(토큰("user2", "products:read", "products:write"),
				호출("updateStock", "{\"productId\":\"p1\",\"quantity\":0}")).getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("Unknown tool: invalid_tool_name");
		assertThat(this.productRepository.findById("p1").orElseThrow().stock()).isEqualTo(before);
	}

	@Test
	void 숨긴_tool은_인자가_틀려도_모르는_tool이다() throws Exception {
		// 입력 검증이 먼저 돌면 "그런 tool은 있다"가 드러난다.
		String body = 응답(토큰("user2", "products:read"), 호출("updateStock", "{}"))
				.getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("Unknown tool: invalid_tool_name");
	}

	@Test
	void 점원의_조회_token은_updateStock에서_403_step_up이다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(토큰("user", "products:read"),
						호출("updateStock", "{\"productId\":\"p1\",\"quantity\":10}")))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer error=\"insufficient_scope\", scope=\"products:write\", "
								+ "resource_metadata=\"http://localhost:8161/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void 손님도_받을_수_있는_scope의_tool은_step_up한다() throws Exception {
		this.mockMvc.perform(McpScopeTest.mcp(토큰("user2", "products:read"),
						호출("checkout", "{\"basketId\":\"bsk_AAAAAAAAAAAAAAAAAAAAAA\"}")))
				.andExpect(status().isForbidden())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("scope=\"orders:write\"")));
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*HiddenToolCallTest*'
```

Expected: 손님의 세 test가 FAIL(지금은 `403 insufficient_scope`, `products:write`가 있으면 재고가 바뀐다). 점원 test와 `checkout` test는 PASS.

- [ ] **Step 3: 구현한다**

`config/ToolVisibilityTransport.java`를 고친다.
1. import를 더한다: `io.modelcontextprotocol.json.McpJsonMapper`, `io.modelcontextprotocol.json.TypeRef`.
2. 상수와 field, 생성자를 바꾼다:

```java
	/**
	 * SDK 2.0.1이 없는 tool에 주는 message다(이름은 넣지 않는 고정 문자열이다).
	 * 숨긴 tool도 이 값으로 답해야 정말 없는 tool과 구별되지 않는다. SDK를 올리면 다시 확인한다.
	 */
	static final String UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name";

	private final McpStatelessServerTransport delegate;

	private final ToolVisibility visibility;

	private final McpJsonMapper jsonMapper;

	public ToolVisibilityTransport(McpStatelessServerTransport delegate, ToolVisibility visibility,
			McpJsonMapper jsonMapper) {
		this.delegate = delegate;
		this.visibility = visibility;
		this.jsonMapper = jsonMapper;
	}
```

3. `Handler.handleRequest`의 `String subject = …` 바로 뒤에 숨긴 tool 처리를 넣는다:

```java
			if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
				String name = toolName(request.params());
				if (name != null && visibility.hidden(subject, name)) {
					// 받을 수 없는 권한의 tool이라 step-up하지 않는다. 있다는 사실도 알리지 않는다.
					log.info("숨긴 tool 호출 — 사용자={}, 역할={}, tool={}", subject, visibility.roleOf(subject), name);
					return Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS,
									UNKNOWN_TOOL_MESSAGE, "Tool not found: " + name)));
				}
			}
```

4. `Handler`에 메서드를 더한다:

```java
		/** SDK와 같은 변환으로 이름을 읽는다. 읽을 수 없으면 SDK가 같은 params로 오류를 내도록 넘긴다. */
		private String toolName(Object params) {
			try {
				return jsonMapper.convertValue(params, new TypeRef<McpSchema.CallToolRequest>() {
				}).name();
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
```

`config/McpTransportConfig.java`:
- `toolVisibilityTransport(...)`에 `@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper`를 더하고 `new ToolVisibilityTransport(transport, visibility, new JacksonMcpJsonMapper(jsonMapper))`로 만든다.
- `toolScopeFilter(...)`에 `ToolVisibility visibility`를 더하고 `new ToolScopeFilter(registry, visibility, jsonMapper, ResourceMetadataUrl::of)`로 만든다.

`filter/ToolScopeFilter.java`:
- import `dev.starryeye.visibility.mcpserver.tool.ToolVisibility`.
- field `private final ToolVisibility visibility;`와 생성자 `ToolScopeFilter(ToolScopeRegistry registry, ToolVisibility visibility, JsonMapper jsonMapper, Function<HttpServletRequest, String> resourceMetadataUrl)`.
- POST 분기의 `String missing = …` 계산을 바꾼다(기본 scope 검사가 앞에서 `token == null`을 이미 돌려보냈으므로 여기서 `token`은 null이 아니다):

```java
			String tool = toolName(message);
			// 숨긴 tool은 scope를 늘려도 쓸 수 없으니 step-up을 시작하면 안 된다.
			// 그래서 scope를 보지 않고 transport로 넘겨, 정말 없는 tool과 같은 "모르는 tool" 응답을 받게 한다.
			boolean hidden = tool != null && this.visibility.hidden(token.getToken().getSubject(), tool);
			String missing = (tool != null && !hidden && !granted.contains(this.registry.scopeFor(tool)))
					? this.registry.scopeFor(tool) : null;
```

- 클래스 javadoc 첫 단락 뒤에 한 단락을 더한다: 검사 순서는 기본 scope → 보이는 tool인가 → tool의 scope이고, 숨긴 tool은 `ToolVisibilityTransport`가 답한다는 것.

`filter/ToolScopeFilterTest.java`: 생성자를 부르는 곳마다 두 번째 인자로 `new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF))`를 넣는다. 이 test의 token `sub`는 `user`라 점원이므로 기존 기대값이 그대로 맞는다.

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*HiddenToolCallTest*' --tests '*ToolScopeFilterTest*'
./gradlew test
```

Expected: 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-mcp-server
git commit -m "feat(visibility): 숨긴 tool 호출은 step-up 없이 모르는 tool로 답한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: agent의 token별 tool 목록 보관소 `UserToolCatalog`

**Files:**
- Create: `practice/mcp-tool-visibility/shop-agent/src/main/java/dev/starryeye/visibility/agent/mcp/UnknownToolAwareToolCallback.java`
- Create: `.../agent/mcp/UserToolCatalog.java`
- Test: `.../test/.../agent/mcp/UnknownToolAwareToolCallbackTest.java`, `.../test/.../agent/mcp/UserToolCatalogTest.java`

**Interfaces:**
- Produces:
  - `UnknownToolAwareToolCallback(ToolCallback delegate, Runnable onUnknownTool) implements ToolCallback`, `static boolean isUnknownTool(Throwable)`, 상수 `UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name"`
  - `UserToolCatalog(UserToolCatalog.ToolLoader loader, Function<Authentication, String> accessToken, Clock clock)`
  - `@FunctionalInterface interface UserToolCatalog.ToolLoader { List<ToolCallback> load(Runnable onUnknownTool); }`
  - `static UserToolCatalog.ToolLoader UserToolCatalog.mcp(McpSyncClient client)`
  - `List<ToolCallback> callbacks(Authentication user)`, `static final Duration TTL = Duration.ofMinutes(5)`, package-private `int size()`, `void invalidate(String key)`, `static String key(String accessToken)`

배경(설계 8절에서 확인): Spring AI 2.0.1의 `SyncMcpToolCallback`은 `McpError`를 `ToolExecutionException`으로 감싸지 않고 그대로 던진다. `DefaultToolCallingManager`는 `ToolExecutionException`만 잡으므로, "모르는 tool" 오류는 `ToolExecutionExceptionProcessor`에 닿지 않고 채팅 stream을 깨뜨린다. 그래서 callback마다 감싸서 목록을 버리고 `ToolExecutionException`으로 바꾼다. 그러면 기존 processor가 오류 문장을 모델에게 tool 결과로 돌려주고 turn이 이어진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`mcp/UnknownToolAwareToolCallbackTest.java`:

```java
package dev.starryeye.visibility.agent.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnknownToolAwareToolCallbackTest {

	static final ToolDefinition UPDATE_STOCK = DefaultToolDefinition.builder()
			.name("updateStock").description("재고를 바꾼다").inputSchema("{\"type\":\"object\"}").build();

	AtomicInteger invalidated = new AtomicInteger();

	static ToolCallback 던지는_tool(RuntimeException error) {
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return UPDATE_STOCK;
			}

			@Override
			public String call(String toolInput) {
				throw error;
			}
		};
	}

	static McpError 오류(int code, String message, Object data) {
		return new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
	}

	@Test
	void 모르는_tool_오류면_목록을_버리고_모델에게_돌려줄_ToolExecutionException으로_바꾼다() {
		ToolCallback callback = new UnknownToolAwareToolCallback(
				던지는_tool(오류(-32602, "Unknown tool: invalid_tool_name", "Tool not found: updateStock")),
				this.invalidated::incrementAndGet);

		assertThatThrownBy(() -> callback.call("{}"))
				.isInstanceOf(ToolExecutionException.class)
				.hasMessageContaining("Unknown tool: invalid_tool_name");
		assertThat(this.invalidated).hasValue(1);
	}

	@Test
	void 다른_JSON_RPC_오류는_그대로_던지고_목록을_버리지_않는다() {
		McpError other = 오류(-32602, "Invalid arguments", null);
		ToolCallback callback = new UnknownToolAwareToolCallback(던지는_tool(other), this.invalidated::incrementAndGet);

		assertThatThrownBy(() -> callback.call("{}")).isSameAs(other);
		assertThat(this.invalidated).hasValue(0);
	}

	@Test
	void 성공하면_결과와_정의를_그대로_쓴다() {
		ToolCallback ok = new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return UPDATE_STOCK;
			}

			@Override
			public String call(String toolInput) {
				return "재고를 10개로 바꿨습니다.";
			}
		};
		ToolCallback callback = new UnknownToolAwareToolCallback(ok, this.invalidated::incrementAndGet);

		assertThat(callback.call("{}")).isEqualTo("재고를 10개로 바꿨습니다.");
		assertThat(callback.getToolDefinition()).isSameAs(UPDATE_STOCK);
		assertThat(this.invalidated).hasValue(0);
	}
}
```

`mcp/UserToolCatalogTest.java`:

```java
package dev.starryeye.visibility.agent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자마다 다른 tool 목록을 access token별로 cache하는지 본다(2026-07-28 Caching의 "private" 규칙).
 */
class UserToolCatalogTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계다. */
	static final class 시계 extends Clock {

		private Instant now = Instant.parse("2026-10-05T00:00:00Z");

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

	/** 사용자 이름 → 지금 그 사용자의 access token이다. step-up은 값을 바꿔 흉내 낸다. */
	Map<String, String> tokens = new HashMap<>(Map.of("a", "token-a", "b", "token-b"));

	/** 목록을 받을 때마다 그때의 token을 남긴다. */
	List<String> loads = new ArrayList<>();

	String current;

	Runnable lastOnUnknownTool;

	UserToolCatalog catalog = new UserToolCatalog(onUnknownTool -> {
		this.loads.add(this.current);
		this.lastOnUnknownTool = onUnknownTool;
		return List.of(도구("tool-for-" + this.current));
	}, user -> {
		this.current = this.tokens.get(user.getName());
		return this.current;
	}, this.clock);

	static Authentication 사용자(String name) {
		return new TestingAuthenticationToken(name, null);
	}

	static ToolCallback 도구(String name) {
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	static List<String> 이름(List<ToolCallback> callbacks) {
		return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
	}

	@Test
	void 같은_token이면_TTL_안에서는_다시_받지_않는다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(Duration.ofMinutes(4).plusSeconds(59));
		this.catalog.callbacks(사용자("a"));

		assertThat(this.loads).containsExactly("token-a");
	}

	@Test
	void token이_다르면_목록을_따로_둔다() {
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("tool-for-token-a");
		assertThat(이름(this.catalog.callbacks(사용자("b")))).containsExactly("tool-for-token-b");
		assertThat(이름(this.catalog.callbacks(사용자("a")))).containsExactly("tool-for-token-a");
		assertThat(this.loads).containsExactly("token-a", "token-b");
	}

	@Test
	void TTL이_지나면_다시_받고_만료_항목은_지운다() {
		this.catalog.callbacks(사용자("a"));
		this.clock.지나감(UserToolCatalog.TTL);
		this.catalog.callbacks(사용자("b"));

		// a의 항목은 만료되어 b를 꺼낼 때 함께 지워졌다.
		assertThat(this.catalog.size()).isEqualTo(1);
		this.catalog.callbacks(사용자("a"));
		assertThat(this.loads).containsExactly("token-a", "token-b", "token-a");
	}

	@Test
	void step_up으로_token이_바뀌면_다시_받는다() {
		this.catalog.callbacks(사용자("a"));
		this.tokens.put("a", "token-a-after-step-up");
		this.catalog.callbacks(사용자("a"));

		assertThat(this.loads).containsExactly("token-a", "token-a-after-step-up");
	}

	@Test
	void 모르는_tool_오류가_나면_그_token의_목록을_버린다() {
		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		this.lastOnUnknownTool.run();

		this.catalog.callbacks(사용자("a"));
		this.catalog.callbacks(사용자("b"));
		assertThat(this.loads).containsExactly("token-a", "token-b", "token-b");
	}

	@Test
	void key는_token_값을_그대로_두지_않는다() {
		String key = UserToolCatalog.key("token-a");

		assertThat(key).hasSize(64).isNotEqualTo("token-a").isEqualTo(UserToolCatalog.key("token-a"));
		assertThat(UserToolCatalog.key("token-b")).isNotEqualTo(key);
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-tool-visibility/shop-agent
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
./gradlew test --tests '*UnknownToolAwareToolCallbackTest*' --tests '*UserToolCatalogTest*'
```

Expected: 컴파일 실패(두 클래스가 없다).

- [ ] **Step 3: 구현한다**

`mcp/UnknownToolAwareToolCallback.java`:

```java
package dev.starryeye.visibility.agent.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * MCP tool 호출이 "모르는 tool" 오류로 끝나면, 이 사용자의 tool 목록이 낡았다고 보고 버린다(안내서 12장).
 *
 * <p>Spring AI 2.0.1의 {@code SyncMcpToolCallback}은 JSON-RPC 오류({@link McpError})를 감싸지 않고 그대로 던진다.
 * tool loop는 {@link ToolExecutionException}만 잡으므로, 그대로 두면 채팅 stream이 오류로 끝난다.
 * 그래서 여기서 받아 목록을 버리고 {@link ToolExecutionException}으로 바꾼다.
 * 그러면 tool 실행 예외 처리가 오류 문장을 모델에게 tool 결과로 돌려주고 turn이 이어진다.
 */
public final class UnknownToolAwareToolCallback implements ToolCallback {

	/** MCP Java SDK 2.0.1이 없는 tool에 주는 message다. SDK를 올리면 다시 확인한다. */
	static final String UNKNOWN_TOOL_MESSAGE = "Unknown tool: invalid_tool_name";

	private final ToolCallback delegate;

	private final Runnable onUnknownTool;

	public UnknownToolAwareToolCallback(ToolCallback delegate, Runnable onUnknownTool) {
		this.delegate = delegate;
		this.onUnknownTool = onUnknownTool;
	}

	/** code {@code -32602}는 인자 오류에도 쓰이므로 message까지 본다. */
	public static boolean isUnknownTool(Throwable error) {
		return error instanceof McpError mcpError && mcpError.getJsonRpcError() != null
				&& mcpError.getJsonRpcError().code() == McpSchema.ErrorCodes.INVALID_PARAMS
				&& UNKNOWN_TOOL_MESSAGE.equals(mcpError.getJsonRpcError().message());
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		try {
			return this.delegate.call(toolInput, toolContext);
		}
		catch (McpError ex) {
			if (!isUnknownTool(ex)) {
				throw ex;
			}
			this.onUnknownTool.run();
			throw new ToolExecutionException(getToolDefinition(), ex);
		}
	}
}
```

`mcp/UserToolCatalog.java`:

```java
package dev.starryeye.visibility.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 사용자마다 다른 MCP tool 목록을 access token별로 들고 있는다(안내서 12장).
 *
 * <p>MCP Server는 사용자 역할로 목록을 거른다.
 * 그래서 모든 사용자가 같이 쓰는 목록 하나를 cache하면, 먼저 받은 사람의 목록이 다른 사람에게 간다.
 * MCP 2026-07-28 Caching은 사용자마다 다른 결과를 {@code "private"}로 보고, 다른 access token과 cache를 나누지 못하게 한다.
 * 이 class는 그 규칙을 지금 버전(2025-11-25)에서 미리 따른다.
 *
 * <p>2025-11-25 서버는 목록의 유효 시간({@code ttlMs})을 알려 주지 않으므로 client가 정한다.
 * TTL 안이면 다시 받지 않고, 지나면 다음 질문 때 다시 받는다.
 * 주기적으로 미리 받지는 않는다.
 */
public class UserToolCatalog {

	private static final Logger log = LoggerFactory.getLogger(UserToolCatalog.class);

	/** 2026-07-28 Caching의 예시 값과 같다. 2026-07-28로 올리면 서버의 {@code ttlMs}를 쓴다. */
	public static final Duration TTL = Duration.ofMinutes(5);

	/** 지금 thread의 사용자 token으로 {@code tools/list}를 받아 callback으로 만든다. */
	@FunctionalInterface
	public interface ToolLoader {

		/** {@code onUnknownTool}은 이 목록의 tool이 "모르는 tool" 오류로 끝날 때 부른다. */
		List<ToolCallback> load(Runnable onUnknownTool);
	}

	private record Entry(List<ToolCallback> callbacks, Instant expiresAt) {
	}

	private final ToolLoader loader;

	private final Function<Authentication, String> accessToken;

	private final Clock clock;

	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	public UserToolCatalog(ToolLoader loader, Function<Authentication, String> accessToken, Clock clock) {
		this.loader = loader;
		this.accessToken = accessToken;
		this.clock = clock;
	}

	/**
	 * MCP client로 목록을 받는다.
	 * 모델에게 보이는 이름이 서버가 준 이름과 같도록 {@code prefixedToolName}을 그대로 넣는다.
	 */
	public static ToolLoader mcp(McpSyncClient client) {
		return onUnknownTool -> client.listTools().tools().stream()
				.<ToolCallback>map(tool -> new UnknownToolAwareToolCallback(SyncMcpToolCallback.builder()
						.mcpClient(client)
						.tool(tool)
						.prefixedToolName(tool.name())
						.build(), onUnknownTool))
				.toList();
	}

	/**
	 * 이 사용자의 tool 목록이다.
	 * 요청 thread에서 부른다. 목록을 새로 받을 때 그 thread의 SecurityContext로 사용자 token이 붙는다.
	 */
	public List<ToolCallback> callbacks(Authentication user) {
		String key = key(this.accessToken.apply(user));
		Instant now = this.clock.instant();
		this.entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
		Entry cached = this.entries.get(key);
		if (cached != null) {
			log.info("tool 목록을 cache에서 꺼낸다 (사용자={}, {}개)", user.getName(), cached.callbacks().size());
			return cached.callbacks();
		}
		List<ToolCallback> callbacks = this.loader.load(() -> invalidate(key));
		this.entries.put(key, new Entry(callbacks, now.plus(TTL)));
		log.info("tool 목록을 새로 받았다 (사용자={}, {}개)", user.getName(), callbacks.size());
		return callbacks;
	}

	int size() {
		return this.entries.size();
	}

	void invalidate(String key) {
		if (this.entries.remove(key) != null) {
			log.info("모르는 tool 오류로 이 token의 tool 목록을 버린다");
		}
	}

	/** token 값을 그대로 key로 두지 않는다. 로그나 heap dump에서 token이 그대로 보이지 않게 하려는 것이다. */
	static String key(String accessToken) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(accessToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*UnknownToolAwareToolCallbackTest*' --tests '*UserToolCatalogTest*'
./gradlew test
```

Expected: 모두 PASS(module 95 + 9).

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-agent
git commit -m "feat(visibility): agent가 tool 목록을 access token별로 cache하는 UserToolCatalog

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 질문마다 그 사용자의 tool 목록을 넣는다

**Files:**
- Create: `.../agent/config/ToolCatalogConfig.java`
- Modify: `.../agent/config/ChatClientConfig.java`(`defaultTools` 빼기)
- Modify: `.../agent/controller/ChatController.java`(`UserToolCatalog`)
- Modify: `practice/mcp-tool-visibility/shop-agent/src/main/resources/application.yml`(`toolcallback.enabled: false`)
- Modify: `ToolCallbackProvider`를 mock으로 두던 test 일곱 개: `ShopAgentApplicationTests`, `StepUpAuthorizationFilterChainTest`, `StepUpChatStreamTest`, `ChatCsrfTest`, `ChatResetTest`, `StepUpMemoryRollbackTest`, `ChatMemoryToolResultTest`
- Test: `.../test/.../agent/UserToolsPerRequestTest.java`

**Interfaces:**
- Consumes: `UserToolCatalog`(Task 5), `McpSecurityConfig.REGISTRATION_ID`, `OAuth2AuthorizedClientManager` bean.
- Produces: bean `UserToolCatalog userToolCatalog(List<McpSyncClient>, OAuth2AuthorizedClientManager)`, `static String ToolCatalogConfig.accessToken(OAuth2AuthorizedClientManager, Authentication)`, `ChatController(ChatClient, ChatMemory, UserToolCatalog)`.

배경(설계 8절에서 확인): `toolcallback.enabled: false`는 자동 provider만 끄고, MCP client는 `List<McpSyncClient>` bean(`mcpSyncClients`)으로 남는다. 질문마다 `.tools(...)`로 넣은 callback만 tool loop가 쓴다. `listTools()`를 요청 thread에서 부르면 그 사용자의 token이 붙는다. cache key의 token은 token을 붙이는 customizer와 같은 `authorize(...)` 호출로 얻어서, 만료로 갱신된 token도 key와 실제로 붙는 값이 같다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`UserToolsPerRequestTest.java`:

```java
package dev.starryeye.visibility.agent;

import dev.starryeye.visibility.agent.controller.ChatController;
import dev.starryeye.visibility.agent.discovery.DiscoveryFixtures;
import dev.starryeye.visibility.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

/**
 * 질문마다 그 사용자의 tool 목록이 모델에게 가는지 본다.
 * 앱을 시작할 때 고정한 목록이나 다른 사용자의 목록이 섞이면 안 된다.
 */
@SpringBootTest
class UserToolsPerRequestTest {

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatController chatController;

	@Autowired
	ApplicationContext context;

	List<Prompt> prompts = new CopyOnWriteArrayList<>();

	static ToolCallback 도구(String name) {
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	static Authentication 사용자(String name) {
		return new TestingAuthenticationToken(name, null);
	}

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		given(this.chatModel.stream(any(Prompt.class))).willAnswer(invocation -> {
			this.prompts.add(invocation.getArgument(0));
			return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("네")))));
		});
		given(this.toolCatalog.callbacks(argThat(user -> user != null && "staff".equals(user.getName()))))
				.willReturn(List.of(도구("getStock"), 도구("updateStock")));
		given(this.toolCatalog.callbacks(argThat(user -> user != null && "customer".equals(user.getName()))))
				.willReturn(List.of(도구("getStock")));
	}

	List<String> 받은_tool(Prompt prompt) {
		return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
				.map(callback -> callback.getToolDefinition().name()).toList();
	}

	@Test
	void 질문마다_그_사용자의_tool_목록을_넣는다() {
		this.chatController.chat("재고 알려 줘", new MockHttpSession(), 사용자("staff"))
				.collectList().block(Duration.ofSeconds(10));
		this.chatController.chat("재고 알려 줘", new MockHttpSession(), 사용자("customer"))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(this.prompts).hasSize(2);
		assertThat(받은_tool(this.prompts.get(0))).containsExactly("getStock", "updateStock");
		assertThat(받은_tool(this.prompts.get(1))).containsExactly("getStock");
	}

	@Test
	void 자동_구성의_tool_provider는_없다() {
		assertThat(this.context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*UserToolsPerRequestTest*'
```

Expected: FAIL(controller가 catalog를 쓰지 않고, 자동 provider가 있다).

- [ ] **Step 3: 구현한다**

`application.yml`의 `spring.ai.mcp.client` 아래(기존 `initialized: false`와 같은 단계)에 더한다:

```yaml
        # 자동 구성의 tool 목록 provider를 끈다.
        # 그 provider는 처음 받은 목록 하나를 모든 사용자에게 같이 쓰는데, 이 서버는 사용자마다 다른 목록을 준다.
        # MCP client(List<McpSyncClient> bean "mcpSyncClients")는 그대로 남는다.
        toolcallback:
          enabled: false
```

`config/ToolCatalogConfig.java`:

```java
package dev.starryeye.visibility.agent.config;

import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.time.Clock;
import java.util.List;

/** 사용자마다 다른 MCP tool 목록을 access token별로 들고 있는 보관소를 만든다(안내서 12장). */
@Configuration
public class ToolCatalogConfig {

	@Bean
	public UserToolCatalog userToolCatalog(List<McpSyncClient> mcpSyncClients,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return new UserToolCatalog(UserToolCatalog.mcp(mcpSyncClients.get(0)),
				user -> accessToken(authorizedClientManager, user), Clock.systemUTC());
	}

	/**
	 * MCP 요청에 token을 붙이는 customizer와 같은 호출이다.
	 * 만료된 token이면 여기서 갱신되므로, cache key의 token과 실제로 붙는 token이 같다.
	 */
	static String accessToken(OAuth2AuthorizedClientManager manager, Authentication user) {
		OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
				.withClientRegistrationId(McpSecurityConfig.REGISTRATION_ID)
				.principal(user)
				.build());
		if (client == null) {
			throw new IllegalStateException("로그인한 사용자의 access token이 없다");
		}
		return client.getAccessToken().getTokenValue();
	}
}
```

`config/ChatClientConfig.java`: `shopChatClient`의 `ObjectProvider<ToolCallbackProvider> toolCallbackProvider` 인자와 `toolCallbackProvider.ifAvailable(configured::defaultTools);` 줄을 지운다. 쓰지 않게 된 import도 지운다. 메서드 javadoc을 바꾼다:

```java
    /**
     * MCP tool은 앱을 시작할 때 고정하지 않는다.
     * 서버가 사용자마다 다른 목록을 주므로, {@code ChatController}가 질문마다 그 사용자의 목록을 넣는다.
     */
```

`controller/ChatController.java`:
- import `dev.starryeye.visibility.agent.mcp.UserToolCatalog`, `org.springframework.ai.tool.ToolCallback`.
- field `private final UserToolCatalog toolCatalog;`, 생성자 `ChatController(ChatClient chatClient, ChatMemory chatMemory, UserToolCatalog toolCatalog)`.
- `chat(...)`에서 `List<Message> before = …` 다음 줄에 목록을 꺼내고, prompt에 넣는다:

```java
        // 이 사용자의 tool 목록이다. 요청 thread에서 꺼내야 목록을 새로 받을 때 이 사용자의 token이 붙는다.
        List<ToolCallback> tools = this.toolCatalog.callbacks(authentication);
        Flux<String> content = this.chatClient.prompt()
                .user(message)
                .tools(tools.toArray())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
```

test 일곱 개를 고친다. 규칙은 하나다.
- `@MockitoBean ToolCallbackProvider toolCallbackProvider;`(answers 옵션이 있어도)를 `@MockitoBean UserToolCatalog toolCatalog;`로 바꾼다.
- `given(this.toolCallbackProvider.getToolCallbacks()).willReturn(new ToolCallback[] { … })`를 `given(this.toolCatalog.callbacks(any())).willReturn(List.of(…))`로 바꾼다(빈 배열이면 `List.of()`).
- `org.springframework.ai.tool.ToolCallbackProvider` import를 지우고 `dev.starryeye.visibility.agent.mcp.UserToolCatalog`를 더한다.
- `ShopAgentApplicationTests`가 tool provider bean을 확인하는 test를 갖고 있으면, `UserToolCatalog` bean이 있고 `ToolCallbackProvider` bean이 없다는 확인으로 바꾼다.

controller를 직접 부르는 test(`StepUpMemoryRollbackTest`, `ChatMemoryToolResultTest`의 사용자 구분 test)는 이 mock 덕분에 MCP Server 없이 돈다.

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*UserToolsPerRequestTest*'
./gradlew test
```

Expected: 모두 PASS. 개수를 보고서에 적는다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-agent
git commit -m "feat(visibility): agent가 질문마다 그 사용자의 tool 목록을 넣는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 목록에 없는 tool을 모델이 부르면 turn을 되돌리고 안내한다

**Files:**
- Modify: `.../agent/controller/ChatEvents.java`
- Modify: `practice/mcp-tool-visibility/shop-agent/src/main/resources/static/index.html`
- Modify: `.../test/.../agent/controller/ChatEventsTest.java`
- Test: `.../test/.../agent/ToolUnavailableTurnTest.java`

**Interfaces:**
- Consumes: `ChatController`의 되돌리기(`restore`)가 `ChatEvents.of`의 세 번째 인자로 넘어가는 구조.
- Produces: `ChatEvents.TOOL_UNAVAILABLE = "tool-unavailable"`, `static Optional<String> ChatEvents.unavailableTool(Throwable)`. `ChatEvents.of(Flux<String>, StepUpState, Runnable onInterrupted)`의 세 번째 인자 이름이 `onInterrupted`로 바뀐다(step-up과 이 경우 모두 부른다).

배경(설계 4.3의 6): 모델이 이 질문의 목록에 없는 tool을 부르면 Spring AI 2.0.1이 MCP 요청 없이 `IllegalStateException("No ToolCallback found for tool name: <이름>")`을 던지고 stream이 끝난다. 그대로 두면 화면에는 오류만 남고, 대화 기억에는 결과 없는 tool 호출이 남는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`controller/ChatEventsTest.java`에 test 둘을 더한다(파일의 기존 import·helper를 따른다):

```java
	@Test
	void 목록에_없는_tool을_부르면_되돌리고_tool_unavailable_event를_보낸다() {
		AtomicBoolean interrupted = new AtomicBoolean();
		Flux<String> content = Flux.concat(Flux.just("잠시만요"),
				Flux.error(new IllegalStateException("No ToolCallback found for tool name: updateStock")));

		List<ServerSentEvent<String>> events = ChatEvents.of(content, new StepUpState(), () -> interrupted.set(true))
				.collectList().block();

		assertThat(events).extracting(ServerSentEvent::event).containsExactly("message", "tool-unavailable");
		assertThat(events.get(1).data()).isEqualTo("{\"tool\":\"updateStock\"}");
		assertThat(interrupted).isTrue();
	}

	@Test
	void 다른_IllegalStateException은_그대로_오류다() {
		AtomicBoolean interrupted = new AtomicBoolean();
		Flux<String> content = Flux.error(new IllegalStateException("다른 문제"));

		assertThatThrownBy(() -> ChatEvents.of(content, new StepUpState(), () -> interrupted.set(true))
				.collectList().block()).hasMessageContaining("다른 문제");
		assertThat(interrupted).isFalse();
	}
```

`ToolUnavailableTurnTest.java`:

```java
package dev.starryeye.visibility.agent;

import dev.starryeye.visibility.agent.controller.ChatController;
import dev.starryeye.visibility.agent.discovery.DiscoveryFixtures;
import dev.starryeye.visibility.agent.discovery.McpAuthorizationDiscovery;
import dev.starryeye.visibility.agent.mcp.UserToolCatalog;

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
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
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
 * 손님의 목록에는 updateStock이 없다.
 * 그래도 모델이 그 tool을 부르면, 채팅이 오류로 깨지지 않고 안내 event로 끝나며 그 turn이 기억에서 되돌려지는지 본다.
 */
@SpringBootTest
class ToolUnavailableTurnTest {

	static final ToolDefinition GET_STOCK = DefaultToolDefinition.builder()
			.name("getStock").description("재고를 본다").inputSchema("{\"type\":\"object\"}").build();

	static final ToolCallback getStock = new ToolCallback() {

		@Override
		public ToolDefinition getToolDefinition() {
			return GET_STOCK;
		}

		@Override
		public String call(String toolInput) {
			return "p1 재고 7개";
		}
	};

	@MockitoBean
	McpAuthorizationDiscovery discovery;

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	UserToolCatalog toolCatalog;

	@Autowired
	ChatController chatController;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void setUp() {
		given(this.discovery.discover(DiscoveryFixtures.RESOURCE, DiscoveryFixtures.ISSUER))
				.willReturn(DiscoveryFixtures.discovered());
		given(this.toolCatalog.callbacks(any())).willReturn(List.of(getStock));
		given(this.chatModel.getOptions()).willReturn(ToolCallingChatOptions.builder().build());
		AssistantMessage toolCall = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "updateStock",
						"{\"productId\":\"p1\",\"quantity\":10}")))
				.build();
		given(this.chatModel.stream(any(Prompt.class)))
				.willReturn(Flux.just(new ChatResponse(List.of(new Generation(toolCall)))));
	}

	@Test
	void 목록에_없는_tool을_부른_turn은_되돌리고_안내한다() {
		List<Message> earlier = List.of(new UserMessage("p1 재고 알려 줘"), new AssistantMessage("7개예요"));
		this.chatMemory.add("unavailable-user", earlier);

		List<ServerSentEvent<String>> events = this.chatController
				.chat("p1 재고를 10개로 바꿔 줘", new MockHttpSession(), new TestingAuthenticationToken("unavailable-user", null))
				.collectList().block(Duration.ofSeconds(10));

		assertThat(events).extracting(ServerSentEvent::event).last().isEqualTo("tool-unavailable");
		assertThat(events.get(events.size() - 1).data()).isEqualTo("{\"tool\":\"updateStock\"}");
		assertThat(this.chatMemory.get("unavailable-user")).extracting(Message::getText)
				.containsExactly("p1 재고 알려 줘", "7개예요");
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
./gradlew test --tests '*ChatEventsTest*' --tests '*ToolUnavailableTurnTest*'
```

Expected: 새 test들이 FAIL(오류가 그대로 stream을 끝낸다).

- [ ] **Step 3: 구현한다**

`controller/ChatEvents.java`:
- import `java.util.Optional`.
- 상수를 더한다:

```java
    static final String TOOL_UNAVAILABLE = "tool-unavailable";

    /** 목록에 없는 tool을 모델이 부를 때 Spring AI 2.0.1이 던지는 message의 앞부분이다. Spring AI를 올리면 다시 확인한다. */
    private static final String NO_TOOL_CALLBACK = "No ToolCallback found for tool name: ";
```

- 세 인자 `of(...)`를 바꾼다(두 인자 `of(...)`는 그대로 위임한다). javadoc도 고친다:

```java
    /**
     * {@code onInterrupted}는 tool 결과 없이 끊긴 turn을 끝내기 직전에 부른다.
     * step-up이 필요할 때와, 모델이 이 사용자의 목록에 없는 tool을 불렀을 때다.
     * agent는 여기서 끊긴 turn을 대화 기억에서 되돌린다.
     */
    public static Flux<ServerSentEvent<String>> of(Flux<String> content, StepUpState state, Runnable onInterrupted) {
        return content.map(text -> event(MESSAGE, JSON.writeValueAsString(text)))
                .onErrorResume(error -> StepUpRequiredException.find(error).isPresent(), error -> {
                    onInterrupted.run();
                    return Flux.just(stepUp(StepUpRequiredException.find(error).orElseThrow(), state));
                })
                .onErrorResume(error -> unavailableTool(error).isPresent(), error -> {
                    onInterrupted.run();
                    return Flux.just(event(TOOL_UNAVAILABLE,
                            JSON.writeValueAsString(Map.of("tool", unavailableTool(error).orElseThrow()))));
                });
    }

    /** 원인 사슬에서 "목록에 없는 tool" 오류를 찾아 그 tool 이름을 돌려준다. */
    static Optional<String> unavailableTool(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException && cause.getMessage() != null
                    && cause.getMessage().startsWith(NO_TOOL_CALLBACK)) {
                return Optional.of(cause.getMessage().substring(NO_TOOL_CALLBACK.length()).trim());
            }
        }
        return Optional.empty();
    }
```

- 클래스 javadoc에 한 줄을 더한다: 목록에 없는 tool을 모델이 부르면 `tool-unavailable` event로 끝낸다.

`index.html`의 `handle(...)`에 분기를 더한다(`value`는 이미 `JSON.parse`한 값이다):

```js
        else if (event === 'tool-unavailable') out.textContent = '이 계정에서는 ' + value.tool + '을(를) 쓸 수 없습니다.';
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*ChatEventsTest*' --tests '*ToolUnavailableTurnTest*'
./gradlew test
```

Expected: 모두 PASS. 기존 step-up 되돌리기 test도 그대로 통과한다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/shop-agent
git commit -m "feat(visibility): 목록에 없는 tool을 부른 turn은 되돌리고 안내한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `local-client`가 받은 tool 목록을 보여 주고 숨긴 tool을 불러 본다

**Files:**
- Modify: `practice/mcp-tool-visibility/local-client/src/main/java/dev/starryeye/visibility/localclient/McpCalls.java`
- Modify: `.../local-client/src/test/java/dev/starryeye/visibility/localclient/FakeMcpServer.java`
- Modify: `.../local-client/src/test/java/dev/starryeye/visibility/localclient/McpCallsTest.java`

**Interfaces:**
- Produces: `McpCalls.run(...)`의 출력(Task 9 캡처와 문서가 인용한다):

```text
    initialize: protocolVersion=2025-11-25, server=…
    tools: getStock, searchProducts, …(서버가 준 순서, 쉼표로)
    updateStock(목록에 없음): JSON-RPC 오류 -32602 Unknown tool: invalid_tool_name (Tool not found: updateStock)   ← 목록에 updateStock이 없을 때만
    getStock(p1): …
    …(장바구니 시나리오 그대로)…
    checkout: 주문 ord-…를 접수했습니다.
    tools: …   ← step-up으로 token이 바뀌었을 때만, 새 token으로 다시 받은 목록
```

배경(설계 8절에서 확인): SDK의 `callTool`은 JSON-RPC 오류를 `McpError`로 던지고, HTTP 200이라 step-up handler는 불리지 않는다. 바깥의 `catch (RuntimeException)`이 모든 예외를 `LocalClientException`으로 바꾸므로 숨긴 tool 호출 바로 둘레에서 `McpError`를 잡는다. step-up 뒤 새 `listTools()` 요청은 customizer가 새 token을 붙인다.

- [ ] **Step 1: fake 서버와 실패하는 테스트를 바꾼다**

`FakeMcpServer`:
- field 둘을 더한다:

```java
	/** 서버에 있는 tool이다. 이 순서로 목록을 준다. */
	List<String> tools = List.of("getStock", "createBasket", "addItem", "getBasket", "checkout", "updateStock");

	/** 이 사용자에게 숨긴 tool이다. 목록에서 빠지고, 부르면 "모르는 tool" 오류다. */
	Set<String> hidden = new HashSet<>();
```

- `tools/list` 응답을 바꾼다:

```java
			case "tools/list" -> respondResult(exchange, id, Map.of("tools", this.tools.stream()
					.filter(name -> !this.hidden.contains(name))
					.map(name -> Map.of("name", name, "inputSchema", Map.of("type", "object")))
					.toList()), null);
```

- `handleToolCall` 맨 앞에 숨긴 tool 응답을 넣는다(MCP Java SDK 2.0.1 서버와 같은 본문):

```java
		if (this.hidden.contains(toolName)) {
			String body = MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of(
					"code", -32602, "message", "Unknown tool: invalid_tool_name", "data", "Tool not found: " + toolName)));
			respond(exchange, 200, Map.of("Content-Type", "application/json"), body);
			return;
		}
```

(`java.util.HashSet`, `java.util.Set` import. `MAPPER`가 다른 이름이면 그 이름을 쓴다.)

`McpCallsTest`에 test 셋을 더한다:

```java
	@Test
	void 받은_tool_목록을_한_줄로_찍는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(printed()).contains("tools: getStock, createBasket, addItem, getBasket, checkout, updateStock")
				.doesNotContain("목록에 없음");
		assertThat(calls("updateStock")).isEmpty();
	}

	@Test
	void 목록에_updateStock이_없으면_불러_보고_모르는_tool_오류를_찍은_뒤_이어_간다() {
		this.mcp.hidden.add("updateStock");
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(printed()).contains("tools: getStock, createBasket, addItem, getBasket, checkout\n")
				.contains("updateStock(목록에 없음): JSON-RPC 오류 -32602 Unknown tool: invalid_tool_name "
						+ "(Tool not found: updateStock)")
				.contains("checkout: 주문 ord-1001를 접수했습니다.");
		assertThat(calls("updateStock")).extracting(FakeMcpServer.Recorded::authorization)
				.containsExactly("Bearer read-token");
		// 숨긴 tool은 step-up을 부르지 않는다. 권한을 더 받은 것은 checkout의 orders:write 한 번뿐이다.
		assertThat(this.requested).containsExactly(Set.of("products:read", "orders:write"));
	}

	@Test
	void step_up으로_token이_바뀌면_목록을_다시_받는다() {
		TokenHolder holder = new TokenHolder("read-token", Set.of("products:read"));

		McpCalls.run(this.mcp.origin() + "/mcp", holder,
				stepUp(holder, new TokenResponse("write-token", 300, "products:read orders:write")),
				Duration.ofSeconds(20), this.out);

		assertThat(this.mcp.requests).filteredOn(r -> "tools/list".equals(r.rpcMethod()))
				.extracting(FakeMcpServer.Recorded::authorization)
				.containsExactly("Bearer read-token", "Bearer write-token");
	}
```

`printed()`가 줄바꿈을 `\n`으로 남기지 않는 환경이면 둘째 test의 `"…checkout\n"`을 `System.lineSeparator()`로 바꾼다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

```bash
cd practice/mcp-tool-visibility/local-client
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
./gradlew test --tests '*McpCallsTest*'
```

Expected: 새 test 셋이 FAIL(지금은 tool마다 `tool: …` 한 줄씩 찍고, 숨긴 tool을 부르지 않고, 목록을 다시 받지 않는다).

- [ ] **Step 3: 시나리오를 바꾼다**

`McpCalls.java`:
- import `io.modelcontextprotocol.spec.McpError`, `java.util.List`.
- `run(...)`에서 `callOnce(client::listTools, out).tools().forEach(…)` 줄을 바꾼다:

```java
			// 서버는 사용자 역할로 목록을 거른다(안내서 12장).
			ToolList tools = new ToolList(client, holder, out);
			if (!tools.printIfTokenChanged().contains("updateStock")) {
				// 목록에 없는 tool을 일부러 불러 본다. 권한을 늘려도 쓸 수 없는 tool이라 step-up이 아니라 JSON-RPC 오류가 온다.
				callHidden(client, out);
			}
```

- `checkout` 줄 바로 뒤에 더한다:

```java
			// step-up으로 token이 바뀌었으면 목록을 다시 받는다. 새 요청이라 customizer가 새 token을 붙인다.
			tools.printIfTokenChanged();
```

- class javadoc 끝에 한 단락을 더한다: 서버가 사용자 역할로 tool 목록을 거르므로, 받은 목록을 출력하고, 목록에 없는 `updateStock`은 불러서 "모르는 tool" 오류를 보여 준다는 것(안내서 12장).
- class 안에 더한다:

```java
	/** 목록을 받은 token을 기억해 두고, token이 바뀌었을 때만 다시 받아 한 줄로 찍는다. */
	static final class ToolList {

		private final McpSyncClient client;

		private final TokenHolder holder;

		private final PrintStream out;

		private String listedWith;

		private List<String> names = List.of();

		ToolList(McpSyncClient client, TokenHolder holder, PrintStream out) {
			this.client = client;
			this.holder = holder;
			this.out = out;
		}

		List<String> printIfTokenChanged() {
			String token = this.holder.accessToken();
			if (!token.equals(this.listedWith)) {
				this.names = callOnce(this.client::listTools, this.out).tools().stream()
						.map(McpSchema.Tool::name).toList();
				this.listedWith = this.holder.accessToken();
				this.out.println("    tools: " + String.join(", ", this.names));
			}
			return this.names;
		}
	}

	/** 숨긴 tool 호출은 HTTP 200의 JSON-RPC 오류다. SDK는 이것을 {@link McpError}로 던진다. */
	private static void callHidden(McpSyncClient client, PrintStream out) {
		try {
			print(out, "updateStock(목록에 없음)", call(client, "updateStock", Map.of("productId", "p1", "quantity", 10)));
		}
		catch (McpError ex) {
			McpSchema.JSONRPCResponse.JSONRPCError error = ex.getJsonRpcError();
			out.println("    updateStock(목록에 없음): JSON-RPC 오류 " + error.code() + " " + error.message()
					+ " (" + error.data() + ")");
		}
	}
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

```bash
./gradlew test --tests '*McpCallsTest*'
./gradlew test
```

Expected: 모두 PASS. 기존 test가 `tool: ` 줄을 기대했다면 새 출력(`tools: …`)에 맞게 기대값만 고치고 보고서에 적는다.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-tool-visibility/local-client
git commit -m "feat(visibility): local-client가 받은 tool 목록을 찍고 숨긴 tool을 불러 본다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 실제로 띄워 캡처하고 웹 agent를 browser로 확인한다

**Files:**
- Create: `docs/superpowers/captures/mcp-visibility-walkthrough.sh`
- Create: `docs/superpowers/captures/visibility-local-client-run.sh`
- Create: `docs/superpowers/captures/<실행한 날>-visibility-walkthrough.txt`
- Create: `docs/superpowers/captures/<실행한 날>-visibility-local-client-user.txt`, `<실행한 날>-visibility-local-client-user2.txt`
- (고칠 것이 나오면) 해당 module

**Interfaces:**
- Consumes: Task 1–8의 네 module.
- Produces: Task 10–12가 인용할 캡처 세 개와 browser 확인 결과(보고서).

- [ ] **Step 1: walkthrough 스크립트를 만든다**

`docs/superpowers/captures/mcp-stateless-walkthrough.sh`를 `mcp-visibility-walkthrough.sh`로 복사하고 바꾼다. 먼저 원본을 끝까지 읽는다.
- 머리 주석: "mcp-tool-visibility practice에서 사용자 역할로 거른 tool 목록과 숨긴 tool 호출을 curl로 한 단계씩 기록한다(V 번호)."
- 기본값: `AS=http://localhost:9050`, `MCP_BASE=http://localhost:8161`, `CLIENT_ID=visibility-shop-agent`, `CLIENT_SECRET=visibility-shop-agent-secret`, `REDIRECT_URI=http://localhost:8160/login/oauth2/code/authserver`.
- helper(`login`, `authorize`, `consent`, `token`, `mcp`, `tool`, `tidy`, `payload`)는 그대로 쓴다. `names()` helper를 더한다: `tools/list` 응답 본문에서 tool 이름을 순서대로 뽑아 쉼표로 잇는다(`python3 -c`로 JSON을 읽는다).
- 단계(모든 MCP 요청에 `Mcp-Session-Id`를 보내지 않는다):

```text
V1  user(점원) login → authorize "openid products:read" → consent products:read → token(STAFF_READ)
    → tools/list 본문과 names. 7개가 아니면 fail
V2  user2(손님) login(새 JAR) → 같은 흐름 → token(CUSTOMER_READ) → tools/list 본문과 names
    → names가 V1에서 updateStock만 뺀 것과 같지 않으면 fail
V3  CUSTOMER_READ로 updateStock 호출 → 응답 header 전체와 본문(200, WWW-Authenticate 없음, Unknown tool)
V4  CUSTOMER_READ로 updateStack(없는 이름, 같은 길이) 호출 → 응답 header 전체와 본문
    → V3 본문의 이름을 NAME으로 바꾼 값과 V4의 것이 다르면 fail
V5  STAFF_READ로 updateStock 호출 → 403, WWW-Authenticate(scope="products:write")
V6  STAFF_READ로 tools/list를 한 번 더 → names가 V1과 다르면 fail
V7  user2가 products:write까지 요청: authorize "openid products:read products:write" → consent products:write → token(CUSTOMER_WRITE)
    → tools/list names(V2와 같아야 한다) → updateStock 호출 → 여전히 Unknown tool, 재고 그대로(getStock p1 전후 비교)
```

- [ ] **Step 2: local-client 실행 스크립트를 만든다**

`docs/superpowers/captures/stateless-local-client-run.sh`를 `visibility-local-client-run.sh`로 복사하고 바꾼다.
- `AS` 기본값 `http://localhost:9050`, `CLIENT_DIR`는 `practice/mcp-tool-visibility/local-client`.
- login할 계정을 첫 인자로 받는다: `./visibility-local-client-run.sh user`, `./visibility-local-client-run.sh user2`(기본 `user`).
- 출력 머리 줄: `# mcp-tool-visibility local-client 실행(계정 <user>) — 날짜 (visibility-local-client-run.sh, browser 대신 curl)`.

```bash
chmod +x docs/superpowers/captures/mcp-visibility-walkthrough.sh docs/superpowers/captures/visibility-local-client-run.sh
```

- [ ] **Step 3: 서버를 띄우고 walkthrough를 기록한다**

LOG는 scratchpad 아래에 둔다(예: `$TMPDIR` 대신 작업자에게 주어진 scratchpad 경로).

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
cd practice/mcp-tool-visibility && (nohup ./run.sh > "$LOG" 2>&1 &) ; cd -
# 준비될 때까지 기다린다(5분 안). foreground sleep이 막히면 background 실행으로 기다린다.
until grep -qE "\[실패\]|준비됨\] shop-agent" "$LOG"; do sleep 3; done; tail -3 "$LOG"
cd docs/superpowers/captures && ./mcp-visibility-walkthrough.sh > "$(date +%F)-visibility-walkthrough.txt"; echo "exit=$?"
```

Expected(파일을 열어 확인한다):
- V1은 7개, V2는 `updateStock`만 빠진 6개이고 순서가 같다.
- V3과 V4는 둘 다 HTTP 200, `WWW-Authenticate`가 없고, 이름만 다른 같은 본문(`-32602`, `Unknown tool: invalid_tool_name`)이다.
- V5는 `403`과 `scope="products:write"`다.
- V6의 목록 순서가 V1과 같다.
- V7은 `products:write`가 든 token으로도 `Unknown tool`이고 재고가 그대로다.
- `practice/mcp-tool-visibility/logs/shop-mcp-server.log`에 `tools/list — 사용자=user2, 역할=CUSTOMER, 보인 tool=6/7`과 `숨긴 tool 호출 — 사용자=user2, 역할=CUSTOMER, tool=updateStock`이 있다.

- [ ] **Step 4: 서버를 다시 띄우고 local-client를 두 계정으로 기록한다**

재고와 consent를 처음 상태로 돌린다.

```bash
cd practice/mcp-tool-visibility && ./stop.sh && (nohup ./run.sh > "$LOG" 2>&1 &) ; cd -
until grep -qE "\[실패\]|준비됨\] shop-agent" "$LOG"; do sleep 3; done
cd docs/superpowers/captures
./visibility-local-client-run.sh user > "$(date +%F)-visibility-local-client-user.txt"; echo "exit=$?"
./visibility-local-client-run.sh user2 > "$(date +%F)-visibility-local-client-user2.txt"; echo "exit=$?"
```

Expected:
- `user`: `tools:` 줄에 7개, `목록에 없음` 줄이 없고, `checkout` step-up 뒤 `tools:` 줄이 한 번 더 나온다(같은 7개).
- `user2`: `tools:` 줄에 6개, `updateStock(목록에 없음): JSON-RPC 오류 -32602 Unknown tool: invalid_tool_name (Tool not found: updateStock)` 줄이 있고, step-up은 `checkout`의 `orders:write` 한 번뿐이다. 끝에 `실패:`가 없다.

- [ ] **Step 5: 웹 agent를 browser로 확인한다(controller가 한다)**

`http://localhost:8160`에서 확인한다. 로컬 모델이라 답 하나에 30\~100초 걸린다.
1. `user2`/`password`로 login한다. consent의 체크박스는 `products:read` 하나다.
2. "p1 재고를 10개로 바꿔 줘" → consent 카드가 뜨지 않고, 모델이 할 수 없다고 답한다. 모델이 그래도 `updateStock`을 부르면 "이 계정에서는 updateStock을(를) 쓸 수 없습니다." 안내가 나온다. 둘 중 어느 것인지 보고서에 적는다.
3. agent 로그(`logs/shop-agent.log`)에 `tool 목록을 새로 받았다 (사용자=user2, 6개)`가 있고, 5분 안에 다시 물으면 `tool 목록을 cache에서 꺼낸다`가 나온다.
4. logout하거나 다른 browser(시크릿 창)에서 `user`/`password`로 login한다. "p1 재고를 10개로 바꿔 줘" → `products:write` consent 카드가 뜬다(10장 흐름). 허락하면 재고가 바뀐다.
5. agent 로그에 `user`의 목록이 따로(7개) 받아졌고, step-up 뒤 새 token으로 한 번 더 받아졌다.

확인이 끝나면 `./stop.sh`로 내리고 포트(9050, 8161, 8160)가 비었는지 본다.

- [ ] **Step 6: 커밋**

```bash
git add docs/superpowers/captures/mcp-visibility-walkthrough.sh docs/superpowers/captures/visibility-local-client-run.sh \
  docs/superpowers/captures/*-visibility-walkthrough.txt docs/superpowers/captures/*-visibility-local-client-user.txt \
  docs/superpowers/captures/*-visibility-local-client-user2.txt
git commit -m "docs(captures): mcp-tool-visibility의 역할별 tool 목록 curl 캡처와 local-client 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: practice README

**Files:**
- Create: `practice/mcp-tool-visibility/README.md`, `practice/mcp-tool-visibility/diagrams/`

**Interfaces:**
- Consumes: Task 9의 캡처와 browser 확인 결과, 안내서 12장 파일 이름 `practice/mcp-guide/12-tool-visibility.md`와 Task 11의 제목(이 task의 검사에서 그 파일로 가는 `link` 위반만 남는 것은 허용한다).

- [ ] **Step 1: 저장소 스킬과 기준 README를 읽는다**

`.claude/skills/writing-practice-docs/SKILL.md`, `templates.md`, `practice/mcp-stateless-handle/README.md`(가장 가까운 기준), `practice/mcp-security-authn-official/README.md`(실행·코드 지도 형식).

- [ ] **Step 2: README를 쓴다**

구성(stateless README와 같은 형식):
1. 소개 3\~4줄: `mcp-stateless-handle`을 복사해, 사용자가 원래 할 수 없는 tool은 목록에서 숨기고 할 수 있지만 아직 허락하지 않은 tool은 보여 준 뒤 step-up하는 practice. 안내서 12장 링크.
2. `## mcp-stateless-handle과 다른 점` — 표(바뀐 곳 · stateless · 이 practice · 안내서 절):
   - MCP Server: 역할 표와 `ToolVisibility`, `ToolVisibilityTransport`(`tools/list` 거르기, 숨긴 tool은 "모르는 tool"), `ToolScopeFilter`의 검사 순서, tool 설명의 "처음 부르면 권한을 묻는다"
   - auth-server: 이름·포트만(역할을 모른다)
   - agent: 자동 tool provider 끄기, `UserToolCatalog`(token별 cache, TTL 5분), `UnknownToolAwareToolCallback`, `tool-unavailable` event
   - local-client: `tools:` 줄, 숨긴 tool 호출, step-up 뒤 목록 다시 받기
   - 이어서 짧은 절 둘: "숨기기와 step-up"(역할별 보이는 tool 표: 점원·손님), "사용자별 목록 cache"(mermaid 한 장: 같은 agent로 user와 user2가 번갈아 물을 때 token별로 목록을 받는 모습). 메커니즘 설명은 12장으로 링크하고 이 practice의 구체 값만 둔다.
3. `## 실행` — 포트(9050/8161/8160), 계정 둘(역할 함께), `./run.sh`/`./stop.sh`, `local-client` 실행(계정 둘), JAVA_HOME 한 줄.
4. `## 코드 지도` — stateless와 다른 클래스(module · 클래스 · 하는 일 · 안내서). 나머지는 stateless README 코드 지도 링크.
5. `## 직접 확인할 것` — 표(할 일 · 기대 결과): Task 9 Step 3·4·5의 기대 결과를 캡처 값으로 쓴다. 모델이 목록에 없는 tool을 불렀을 때의 안내와, "모르는 tool"이 agent 로그에 ERROR로 남을 수 있다는 점도 적는다.
6. `## 더 읽을 것` — 안내서 12장, 10장(scope와 step-up), 11장, 준수표 부록(`#mcp-tool-visibility에서-달라지는-행`), 로드맵의 다음 practice(CIMD) 한 줄.

- [ ] **Step 3: 검사한다**

```bash
MMDC=/Users/starryeye/.npm/_npx/668c188756b835f3/node_modules/.bin/mmdc \
  python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-tool-visibility/README.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-tool-visibility/README.md
```

Expected: `12-tool-visibility.md`로 가는 `link` 위반과, 아직 없는 준수표 앵커 위반만 남는다.

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-tool-visibility/README.md practice/mcp-tool-visibility/diagrams
git commit -m "docs(visibility): mcp-tool-visibility README

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 안내서 12장 — tool 목록과 권한

**Files:**
- Create: `practice/mcp-guide/12-tool-visibility.md`, `practice/mcp-guide/diagrams/12-tool-visibility-*.png`, `practice/mcp-guide/diagrams/.sources.json`(갱신)

**Interfaces:**
- Consumes: Task 9의 캡처, Task 1–8의 코드, spec 1절의 개념.

- [ ] **Step 1: 읽는다**

스킬(`SKILL.md`, `templates.md`), 기준 장 `practice/mcp-guide/03-discovery.md`, 이웃 장 `10-scope-and-step-up.md`(특히 10.1 "실제 MCP Server는 두 방식이 섞여 있다", 10.6), `11-stateless-and-handle.md`, spec 1절, 캡처.
명세 원문: 2026-07-28 server/tools(목록이 authorization에 따라 달라져도 되는 조건, 순서), 2026-07-28 server/utilities/caching(`ttlMs`, `cacheScope`, private cache 규칙, `ttlMs` 없을 때), 2025-11-25 server/tools(Error Handling의 없는 tool 예시), 2025-11-25 Authorization(Scope Challenge Handling), Security Best Practices(Scope Minimization).
raw 원문은 `https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/<version>/...`에서 받는다.

- [ ] **Step 2: 장을 쓴다**

제목 `# 12. tool 목록과 권한 — 받을 수 없는 tool은 숨기고, 받을 수 있는 tool은 step-up한다`. 장의 틀을 따른다. README가 앵커로 링크할 수 있으므로 아래 제목을 그대로 쓴다.
1. `## 12.1 권한별 tool 목록의 필요성` — 모델은 목록에 있는 tool만 부른다, 지금 token의 scope로 거르면 step-up 입구가 사라진다, 그래서 "언젠가 받을 수 있는가"와 "지금 있는가"를 나눈다, 권한(서버가 앎)과 scope(token에 있음), 2026-07-28이 목록을 authorization에 따라 달리하는 것을 허용한다는 점.
2. `## 12.2 시퀀스 다이어그램` — 점원과 손님이 같은 서버에 `tools/list`(7개/6개) → 손님의 `updateStock`(모르는 tool) → 점원의 `updateStock`(`403` → step-up). mermaid + PNG 링크.
3. `## 12.3 1단계: 역할로 거른 tools/list` — 캡처의 점원·손님 목록, 순서가 같은 이유(client cache와 prompt cache), 역할 표와 받을 수 있는 scope 표.
4. `## 12.4 2단계: 숨긴 tool을 부르면` — 캡처의 숨긴 tool과 없는 tool 응답 비교, 같아야 하는 이유(있다는 사실을 알리지 않음), `products:write`가 있어도 같다는 점.
5. `## 12.5 3단계: 받을 수 있는 tool은 그대로 step-up` — 점원의 `403`, 검사 순서(기본 scope → 보이는 tool → tool의 scope)와 거꾸로면 생기는 일.
6. `## 12.6 권한을 판단하는 곳` — MCP Server의 역할 표를 쓰는 이유, Authorization Server가 역할로 scope를 줄이는 방식과의 비교 표, GitHub 사례(10장 인용 사실만), client 등록 단위로 숨기는 변형.
7. `## 12.7 웹 agent: 사용자별 tool 목록 cache` — 공유 목록이 새는 문제, 2026-07-28 Caching(`ttlMs`·`cacheScope`, private 규칙, `ttlMs` 없는 서버), 이 practice의 client TTL 5분과 token별 key, 다시 받는 경우 셋.
8. `## 12.8 웹 agent: 목록에 없는 tool을 모델이 부를 때` — 낡은 목록의 "모르는 tool"(turn이 이어지고 목록을 버림), 목록에 아예 없는 이름(Spring AI가 MCP 요청 없이 끝냄 → 되돌리고 안내), browser 확인 결과.
9. `## 12.9 사용자 기기의 앱` — local-client 출력(캡처 두 계정) 인용, 숨긴 tool 호출이 step-up이 아닌 점, step-up 뒤 목록 다시 받기.
10. `## 12.10 서버 코드에서 보기` — `ToolVisibility`, `ToolVisibilityTransport`(handler 감싸기와 `@Primary`), `ToolScopeFilter`의 순서. 코드 인용은 짧게, `/* ... */`로 줄인다.
11. `## 12.11 client 코드에서 보기` — `UserToolCatalog`, `UnknownToolAwareToolCallback`, `ChatController`, `ChatEvents`, local-client `ToolList`.
12. `## 12.12 다루지 않는 것` — 2026-07-28의 실제 `ttlMs`·`cacheScope` field와 목록 변경 알림, tool별 scope 표준(SEP-1488)과 ChatGPT의 `securitySchemes`, 역할의 영속 저장과 변경, `resources`·`prompts` 목록. 한 줄씩.
13. `## 12.13 직접 해 보기` — 저장소 최상위에서 실행할 수 있는 명령: `practice/mcp-tool-visibility/run.sh`, 캡처 스크립트, 웹 agent 질문 순서(두 계정).
14. `## 12.14 정리` — 4\~5줄.
15. `## 12.15 명세 근거` — 표(내용 · 명세 · 요구 수준): 2026-07-28 server/tools(요청한 client가 쓸 수 있는 tool로 답한다, 연결마다·부수 효과로 달라지지 않는다, authorization에 따라 달라져도 된다, 순서는 늘 같게), 2026-07-28 Caching(hint를 넣는다, private은 다른 authorization context와 cache를 나누지 않는다, `ttlMs`가 없으면 0으로 본다, TTL을 polling 주기로 쓰지 않는다), 2025-11-25 server/tools(없는 tool은 protocol 오류), 2025-11-25 Authorization Scope Challenge Handling(`403 insufficient_scope`), Security Best Practices Scope Minimization. 원문을 확인하고 요구 수준을 적는다.
16. 끝 줄: `[← 11장](11-stateless-and-handle.md) · [목차](README.md) · [부록: API 레퍼런스 →](reference-api.md)`.

문체 규칙(Global Constraints)을 지킨다. 요청·응답은 캡처 값을 그대로 쓰고, JSON은 핵심 field만 남긴다. 길이는 11장과 비슷하게(650줄 안) 한다.

- [ ] **Step 3: 다이어그램과 검사**

```bash
MMDC=/Users/starryeye/.npm/_npx/668c188756b835f3/node_modules/.bin/mmdc \
  python3 .claude/skills/writing-practice-docs/scripts/render_diagrams.py practice/mcp-guide/12-tool-visibility.md
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/12-tool-visibility.md practice/mcp-tool-visibility/README.md
```

Expected: 12장은 위반 0. README는 아직 없는 준수표 앵커 위반만 남는다(Task 12에서 풀린다).

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-guide/12-tool-visibility.md practice/mcp-guide/diagrams
git commit -m "docs(guide): 12장 tool 목록과 권한

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 안내서와 부록, 입구 문서를 잇는다

**Files:**
- Modify: `practice/mcp-guide/README.md`, `10-scope-and-step-up.md`, `11-stateless-and-handle.md`, `reference-api.md`, `reference-compliance.md`
- Modify: `README.md`(저장소 최상위), `practice/mcp-stateless-handle/README.md`(더 읽을 것 한 줄), `.claude/skills/writing-practice-docs/SKILL.md`(예시 값 예외, 장 목록)

- [ ] **Step 1: 안내서 입구와 장 사이 링크**

- `practice/mcp-guide/README.md`: 장 목록 표에 12장 행(한 줄 요약 · 직접 해 보는 것), 읽는 순서에 "12장은 10·11장 다음에 읽는다" 한 줄, 다른 practice 목록에 `mcp-tool-visibility` 한 줄, "1장부터 11장까지" 같은 개수 표현을 12장으로.
- 10장 10.1 "실제 MCP Server는 두 방식이 섞여 있다" 끝: "받을 수 없는 tool은 숨기고 받을 수 있는 tool은 step-up하는 구성은 [12장](12-tool-visibility.md)에서 `mcp-tool-visibility` practice로 본다" 식의 한 문장.
- 11장 끝 nav 줄의 `[부록: API 레퍼런스 →](reference-api.md)`를 `[12장 →](12-tool-visibility.md)`로 바꾼다.

- [ ] **Step 2: 부록**

- `reference-api.md`: `tools/list`·`tools/call` 설명 행에 "visibility practice" 동작(역할로 거른 목록, 숨긴 tool의 "모르는 tool")을 더한다(캡처 V 번호로 인용. 부록은 캡처 번호를 써도 된다). 끝 nav 줄 앞 링크를 `[← 12장](12-tool-visibility.md)`으로 바꾼다.
- `reference-compliance.md`: "`mcp-stateless-handle`에서 달라지는 행" 절 다음에 `## mcp-tool-visibility에서 달라지는 행` 절을 더한다. 표의 열과 판정 어휘는 앞 절과 같게 한다. 새 항목: 목록이 authorization에 따라 달라짐(2026-07-28 MAY), 연결마다·부수 효과로 달라지지 않음(MUST NOT), 같은 순서(SHOULD), 없는 tool은 protocol 오류(2025-11-25), client의 목록 cache를 authorization context별로 나눔(2026-07-28 MUST NOT 공유, 이 practice는 2025-11-25라 agent가 미리 따름). 근거는 클래스 이름과 캡처 V 번호. 원문으로 요구 수준을 확인한다.

- [ ] **Step 3: 저장소 README, stateless README, 문서 스킬**

- 저장소 `README.md`의 practice 목록에 `mcp-tool-visibility` 행(tool 목록과 권한, 네 module, 포트 9050/8161/8160).
- `practice/mcp-stateless-handle/README.md` "더 읽을 것"의 다음 practice 줄을 `[mcp-tool-visibility](../mcp-tool-visibility/README.md)` 링크로 바꾼다.
- `.claude/skills/writing-practice-docs/SKILL.md`: 장 목록을 `12-tool-visibility.md`까지로, 예시 값 예외에 "12장은 `mcp-tool-visibility` 값(`http://localhost:9050`, `http://localhost:8161/mcp`, `http://localhost:8160`)과 그 캡처를 쓴다"를 더한다.
- `practice/mcp-tool-visibility/README.md`의 준수표 링크가 새 절 앵커(`#mcp-tool-visibility에서-달라지는-행`)로 가는지 확인한다.

- [ ] **Step 4: 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md practice/mcp-stateless-handle/README.md practice/mcp-tool-visibility/README.md
python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
```

Expected: 위반 0, 스킬 테스트 통과.

- [ ] **Step 5: 커밋**

```bash
git add practice/mcp-guide README.md practice/mcp-stateless-handle/README.md practice/mcp-tool-visibility/README.md .claude/skills/writing-practice-docs/SKILL.md
git commit -m "docs(guide): 12장과 visibility practice를 안내서·부록·입구 문서에 잇기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 마지막 확인

- [ ] **Step 1: 모든 테스트**

```bash
export JAVA_HOME=$(find $HOME/.sdkman/candidates/java -maxdepth 1 -type d -name '21.*' | sort -V | tail -1)
for d in practice/mcp-tool-visibility/{auth-server,shop-mcp-server,shop-agent,local-client} \
         practice/mcp-stateless-handle/{auth-server,shop-mcp-server,shop-agent,local-client}; do
  (cd "$d" && ./gradlew -q cleanTest test && echo "$d OK")
done
python3 -m unittest discover -s .claude/skills/writing-practice-docs/scripts -p 'test_*.py'
```

Expected: 여덟 줄 모두 `OK`, 스킬 테스트 통과. stateless는 바뀌지 않았어야 한다(`git diff main -- practice/mcp-stateless-handle`에는 README 한 줄만).

- [ ] **Step 2: 문서 검사**

```bash
python3 .claude/skills/writing-practice-docs/scripts/check_docs.py practice/mcp-guide/*.md practice/mcp-security-authn-*/README.md practice/mcp-security-authz/README.md practice/mcp-stateless-handle/README.md practice/mcp-tool-visibility/README.md
```

Expected: 위반 0, 다이어그램 PNG 최신.

- [ ] **Step 3: 커밋할 것이 남았는지 본다**

```bash
git status --short
```

Expected: 비어 있다(`logs/`, `build/`는 `.gitignore`가 가린다).
