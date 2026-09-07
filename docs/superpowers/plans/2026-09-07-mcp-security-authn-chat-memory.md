# mcp-security-authn-chat-memory 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mcp-security-authn-official` 과 `chat-memory` 를 합쳐, `conversationId` 를 클라이언트가 아니라 **서버가 인증에서 파생**시킴으로써 사용자별 대화 격리를 만들고, 툴 호출 결과가 대화 기록에 남는지를 실측한다.

**Architecture:** `mcp-security-authn-official` 을 통째로 복사해 포트·쿠키·패키지만 바꾼 뒤, `shop-agent` 에만 메모리를 얹는다. `conversationId = authentication.getName() + ":" + sanitize(label)` — 접두사는 서버가 강제하고 label 만 클라이언트가 고른다. 조회 API 는 전체 ID 를 받지 않고 label 만 받으며, 목록은 현재 사용자 접두사로 거른다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7.1.0, Spring Authorization Server, Spring AI 2.0.0, MCP Java SDK 2.0.0, Ollama `qwen3:8b`

**Spec:** [docs/superpowers/specs/2026-09-07-mcp-security-authn-chat-memory-design.md](../specs/2026-09-07-mcp-security-authn-chat-memory-design.md)

---

## Global Constraints

### 절대 건드리지 않을 것

**다섯 개 practice 전부 읽기 전용이다.** 복사는 하되 쓰기는 안 된다.

- `practice/agent-mcp/**`
- `practice/agent-mcps/**`
- `practice/mcp-security-authn-community/**`
- `practice/mcp-security-authn-official/**` ← **복사 원본. 읽기만.**
- `practice/chat-memory/**` ← **복사 원본. 읽기만.**

그 외: `legacy-0.8/**`, 루트 `.gitignore` 수정 금지. 루트 `settings.gradle` 은 이 저장소에 **없다**(만들지 않는다). `.superpowers/` 와 `docs/superpowers/**` 는 커밋하지 않는다.

예외: **Task 4 에서 루트 `README.md` 의 practice 목록에 행을 추가한다.** 추가만, 기존 5행 유지.

### 금지 의존성

`org.springaicommunity:*` 를 쓰지 않는다. 베이스가 `-official` 이므로 공식 라이브러리 + MCP 표준 SDK 만 쓴다.

### 포트·쿠키 — 전수 확인된 값

기존 사용 중: `8080 8081 8082 8090 8091 8092 8100 8101 8110 8111 8120 9000 9010`

| 앱 | 포트 | 세션 쿠키 |
|---|---|---|
| `auth-server` | **9020** | `MEMAUTHSESSIONID` |
| `shop-agent` | **8130** | `MEMAGENTSESSIONID` |
| `shop-mcp-server` | **8131** | (무상태, 없음) |

**8120 은 `chat-memory` 가 쓴다.** 쓰지 말 것.
기존 쿠키 이름 `AUTHSERVERSESSIONID`, `SHOPAGENTSESSIONID`, `OFFICIALAUTHSESSIONID`, `OFFICIALAGENTSESSIONID` 와도 겹치면 안 된다 — 쿠키는 호스트만 보고 포트를 구분하지 않아, 겹치면 practice 들이 서로의 로그인을 깨뜨린다.

### 패키지·버전

- 패키지 `dev.starryeye.memoryauthn` (세 앱 공통 접두, 앱별 하위: `.authserver`, `.mcpserver`, `.agent`)
- Java 21 toolchain 필수(시스템 기본 17). **`gradle.properties` 에 JDK 절대경로 금지**
- Spring Boot `4.1.0`, dependency-management `1.1.7`, Spring AI BOM `2.0.0`, `group = 'dev.starryeye'`

### 앞 practice 에서 값을 치른 것들 — 다시 발견하지 말 것

| 항목 | 내용 |
|---|---|
| 기동 순서 | **auth-server 먼저.** `NimbusJwtDecoder.withIssuerLocation(...).build()` 가 issuer 메타데이터를 즉시 가져온다 |
| 테스트 전제 | 위 이유로 mcp-server·agent 테스트는 **auth-server 가 떠 있어야** 통과 |
| MCP 핸드셰이크 | `spring.ai.mcp.client.initialized: false` 없으면 부팅 시 401 로 컨텍스트가 죽는다 |
| 조용한 스위치 | `spring.ai.mcp.client.type: SYNC` / OAuth2 등록 정확히 1개 / `Hooks.enableAutomaticContextPropagation()` — 셋 다 없으면 토큰이 조용히 안 붙는다 |
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` |
| 응답 | `text/plain;charset=UTF-8` (SSE 는 토큰마다 `data:` 가 붙어 못 읽는다) |
| ollama | `spring.ai.ollama.chat.think: low` |
| ChatMemory 자동설정 | `ChatMemory`·`ChatMemoryRepository` 는 자동설정이 준다(둘 다 `@ConditionalOnMissingBean`, 모델 스타터가 전이 의존으로 끌어옴). `maxMessages` 통제를 위해 **`ChatMemory` 만** 직접 정의하고 저장소는 자동설정 것을 쓴다 |
| 스트리밍 저장 시점 | assistant 텍스트는 스트림 완료 후 **원자적으로** 기록된다(`MessageAggregator` 가 `doOnComplete` 에서 저장). 중간에 끊으면 assistant 턴이 안 남는다 |
| curl | **`-H 'Content-Type: text/plain'` 필수.** 없으면 Tomcat 이 본문을 폼 파라미터로 소비해 400 |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure`. `testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 필요 |
| MockMvc | 401 을 보려면 POST 에 `.with(csrf())` 필요 |
| Initializr | `bootVersion=4.1.0` (`4.1.0.RELEASE` 는 HTTP 500). 생성된 `HELP.md`·`application.properties` 삭제 |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`. `stop.sh` 는 `sleep` 후 `kill -9`. **`run.sh` 는 기동 실패 시 로그를 tail 하고 `exit 1`** |
| 테스트 | 한국어 메서드명, AssertJ |

---

## File Structure

```
practice/mcp-security-authn-chat-memory/
├── README.md                      # 설명 + 관측된 검증 결과
├── run.sh / stop.sh
├── .gitignore                     # logs/
│
├── auth-server/                   # :9020 — 부모에서 복사, 사용자 2명으로 변경
│   └── .../memoryauthn/authserver/
│       ├── AuthServerApplication.java
│       └── UserConfig.java        # ★ alice, bob
│
├── shop-mcp-server/               # :8131 — 부모에서 복사, 포트만 변경
│   └── .../memoryauthn/mcpserver/
│       ├── ShopMcpServerApplication.java
│       ├── SecurityConfig.java
│       ├── Product.java / ProductRepository.java / ProductTools.java
│
└── shop-agent/                    # :8130 — 부모 + 메모리
    └── .../memoryauthn/agent/
        ├── ShopAgentApplication.java
        ├── SecurityConfig.java
        ├── McpSecurityConfig.java
        ├── SecurityMcpTransportContextProvider.java
        ├── OAuth2TokenAttachingRequestCustomizer.java
        ├── ChatMemoryConfig.java          # ★ chat-memory 에서
        ├── ConversationId.java            # ★ 신규 — 격리의 전부
        ├── ChatClientConfig.java          # ★ 어드바이저 추가
        ├── ChatController.java            # ★ Authentication 받아 파생
        ├── ConversationController.java    # ★ 접두사 강제 + 목록 필터
        └── MessageView.java
    └── resources/{application.yml, static/index.html}
```

★ 표시가 이 practice 에서 새로 쓰거나 바꾸는 것이다. 나머지는 복사다.

`ConversationId` 를 별도 클래스로 뽑는 이유: **격리의 전부가 이 한 곳**이므로 단위 테스트로 고정하고, 컨트롤러 둘이 같은 규칙을 쓰게 하기 위해서다. 인라인하면 두 컨트롤러가 갈라질 수 있다.

---

## Task 1: 부모 복사 + 사용자 2명 + 툴 결과 저장 여부 실측

**Files:**
- Create: `practice/mcp-security-authn-chat-memory/auth-server/**` (부모에서 복사)
- Create: `practice/mcp-security-authn-chat-memory/shop-mcp-server/**` (부모에서 복사)
- Create: `practice/mcp-security-authn-chat-memory/shop-agent/**` (부모에서 복사, 메모리는 Task 2)
- Test: 부모의 테스트를 패키지·포트만 바꿔 가져온다

**Interfaces:**
- Consumes: 없음
- Produces:
  - auth-server `http://localhost:9020`, 사용자 `alice`/`alice`, `bob`/`bob`
  - 클라이언트 `memory-agent` / `memory-agent-secret`, redirect `http://localhost:8130/login/oauth2/code/authserver`, scopes `openid`,`profile`
  - shop-mcp-server `http://localhost:8131/mcp` (토큰 없으면 401)
  - shop-agent `http://localhost:8130`, `POST /api/chat`

- [ ] **Step 1: 부모를 통째로 복사하고 이름을 바꾼다**

```bash
cd practice
cp -R mcp-security-authn-official mcp-security-authn-chat-memory
cd mcp-security-authn-chat-memory
rm -rf logs README.md
find . -name build -type d -prune -exec rm -rf {} + 2>/dev/null || true
find . -name .gradle -type d -prune -exec rm -rf {} + 2>/dev/null || true
```

**`practice/mcp-security-authn-official/` 은 읽기만 했다.** 수정하지 않았음을 확인:

```bash
cd /Users/starryeye/study/spring-ai
git status --short practice/mcp-security-authn-official
```

Expected: 출력 없음.

- [ ] **Step 2: 패키지를 옮긴다**

각 앱의 소스 디렉터리를 새 패키지로 이동하고 선언을 바꾼다.

| 원본 패키지 | 새 패키지 |
|---|---|
| `dev.starryeye.officialauthserver` | `dev.starryeye.memoryauthn.authserver` |
| `dev.starryeye.officialmcpserver` | `dev.starryeye.memoryauthn.mcpserver` |
| `dev.starryeye.officialagent` | `dev.starryeye.memoryauthn.agent` |

`src/main` 과 `src/test` 양쪽 다. `package` 선언과 `import` 를 모두 고친다.

- [ ] **Step 3: 포트·쿠키·클라이언트 이름을 바꾼다**

| 파일 | 바꿀 것 |
|---|---|
| `auth-server/.../application.yml` | `port: 9010` → `9020`, 쿠키 `OFFICIALAUTHSESSIONID` → `MEMAUTHSESSIONID`, issuer `:9010` → `:9020`, client-id `official-shop-agent` → `memory-agent`, secret `{noop}official-shop-agent-secret` → `{noop}memory-agent-secret`, redirect `:8110` → `:8130` |
| `shop-mcp-server/.../application.yml` | `port: 8111` → `8131`, `issuer-uri: :9010` → `:9020` |
| `shop-agent/.../application.yml` | `port: 8110` → `8130`, 쿠키 `OFFICIALAGENTSESSIONID` → `MEMAGENTSESSIONID`, client-id/secret 위와 동일하게, provider `issuer-uri: :9010` → `:9020`, MCP url `:8111` → `:8131` |

부모의 테스트에도 `9010`/`8110`/`8111`/`official-shop-agent` 문자열이 있다. 전부 바꾼다.

```bash
grep -rn '9010\|8110\|8111\|official' practice/mcp-security-authn-chat-memory/ --include='*.yml' --include='*.java'
```

Expected: 출력 없음(모두 치환된 뒤).

- [ ] **Step 4: 사용자를 2명으로 바꾼다**

`auth-server/.../authserver/UserConfig.java` 의 빈을 아래로 교체:

```java
    /**
     * 사용자 <b>두 명</b>이다. 부모 practice 는 한 명이었다.
     * 대화 격리는 사용자가 둘 이상이어야 관측할 수 있다 —
     * 한 명으로는 "격리되었다"와 "격리 코드가 없다"를 구분하지 못한다.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice")
                        .password("{noop}alice")
                        .roles("USER")
                        .build(),
                User.withUsername("bob")
                        .password("{noop}bob")
                        .roles("USER")
                        .build()
        );
    }
```

부모 테스트에 `user`/`password` 를 단언하는 곳이 있으면 `alice` 로 바꾼다.

- [ ] **Step 5: 세 앱 테스트를 돌린다**

```bash
cd practice/mcp-security-authn-chat-memory/auth-server && ./gradlew bootRun -q &
# /.well-known/openid-configuration 이 응답할 때까지 대기 후
cd ../shop-mcp-server && ./gradlew test
cd ../shop-agent && ./gradlew test
cd ../auth-server && ./gradlew test
```

Expected: 부모와 같은 개수(auth-server 4, shop-mcp-server 10, shop-agent 5)가 전부 통과.
개수가 다르면 복사가 불완전한 것이다.

- [ ] **Step 6: ★ 툴 결과가 메모리에 남는지 실측한다 — 이 태스크의 핵심**

**아직 메모리를 얹기 전이다.** 그래서 이 단계에서는 *메모리가 없을 때* MCP 툴 호출이
정상 동작하는지만 확인하고, **실제 측정은 Task 2 Step 8 로 미룬다.**

지금 확인할 것: 세 앱을 띄우고 `alice` 로 로그인해 `노트북 재고 있어?` 를 물어
실제 재고 숫자(p1 7개, p2 23개)가 나오는지. 부모에서 동작하던 것이 복사 후에도
동작하는지 확인하는 회귀 점검이다.

```bash
grep '호출' logs/shop-mcp-server.log
```

Expected: `searchProducts 호출 (keyword=노트북, 사용자=alice)` — **사용자가 `alice` 로 찍혀야 한다.**
`user` 로 찍히면 Step 4 가 반영되지 않은 것이다.

확인 후 `./stop.sh`(부모에서 복사된 것, 포트만 고쳐서). 서버를 남기지 않는다.

- [ ] **Step 7: 커밋**

```bash
git add practice/mcp-security-authn-chat-memory
git commit -m "feat: mcp-security-authn-chat-memory 베이스 — official 복사 + 사용자 2명"
```

---

## Task 2: 메모리 배선 + conversationId 파생

이 practice 의 본론이다.

**Files:**
- Create: `shop-agent/.../agent/ConversationId.java`
- Create: `shop-agent/.../agent/ChatMemoryConfig.java`
- Create: `shop-agent/.../agent/MessageView.java`
- Create: `shop-agent/.../agent/ConversationController.java`
- Modify: `shop-agent/.../agent/ChatClientConfig.java`
- Modify: `shop-agent/.../agent/ChatController.java`
- Modify: `shop-agent/src/main/resources/application.yml`
- Modify: `shop-agent/src/main/resources/static/index.html`
- Test: `shop-agent/.../agent/ConversationIdTest.java`
- Test: `shop-agent/.../agent/ConversationControllerTest.java`

**Interfaces:**
- Consumes: Task 1 의 세 앱, 사용자 `alice`/`bob`
- Produces:
  - `ConversationId.of(Authentication, String label) -> String`
  - `ConversationId.prefixOf(Authentication) -> String`
  - `POST /api/chat?label=<label>` (label 생략 시 `default`)
  - `GET /api/conversations` → `List<String>` (현재 사용자 것만)
  - `GET /api/conversations/{label}` → `List<MessageView>`
  - `DELETE /api/conversations/{label}` → 204
  - `MessageView(String role, String text)`
  - 속성 `chat.memory.max-messages` (기본 20)

- [ ] **Step 1: 실패하는 테스트부터 — ConversationIdTest**

`shop-agent/src/test/java/dev/starryeye/memoryauthn/agent/ConversationIdTest.java`:

```java
package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 practice 의 격리는 전부 이 클래스 한 곳에 있다.
 * label 에 무엇을 넣어도 접두사를 벗어나지 못해야 한다.
 */
class ConversationIdTest {

    private Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    @Test
    void 사용자_이름이_접두사가_된다() {
        assertThat(ConversationId.of(user("alice"), "default")).isEqualTo("alice:default");
    }

    @Test
    void label_이_없으면_default_다() {
        assertThat(ConversationId.of(user("alice"), null)).isEqualTo("alice:default");
        assertThat(ConversationId.of(user("alice"), "")).isEqualTo("alice:default");
        assertThat(ConversationId.of(user("alice"), "   ")).isEqualTo("alice:default");
    }

    /** 핵심. bob 이 alice 의 네임스페이스를 노려도 자기 것으로 강제된다. */
    @Test
    void label_에_구분자를_넣어도_남의_네임스페이스로_못_간다() {
        String id = ConversationId.of(user("bob"), "alice:default");

        assertThat(id).startsWith("bob:");
        assertThat(id).doesNotContain("alice:");
    }

    @Test
    void 경로_문자를_넣어도_벗어나지_못한다() {
        String id = ConversationId.of(user("bob"), "../alice/default");

        assertThat(id).startsWith("bob:");
        assertThat(id).doesNotContain("..");
        assertThat(id).doesNotContain("/");
    }

    @Test
    void 접두사만_따로_얻을_수_있다() {
        assertThat(ConversationId.prefixOf(user("alice"))).isEqualTo("alice:");
    }

    @Test
    void 서로_다른_사용자는_서로_다른_ID_를_얻는다() {
        assertThat(ConversationId.of(user("alice"), "default"))
                .isNotEqualTo(ConversationId.of(user("bob"), "default"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test --tests '*ConversationIdTest'
```

Expected: 컴파일 실패 — `ConversationId` 클래스가 없다.

- [ ] **Step 3: ConversationId 작성**

```java
package dev.starryeye.memoryauthn.agent;

import org.springframework.security.core.Authentication;

/**
 * {@code conversationId} 를 <b>서버가</b> 만든다. 클라이언트는 label 만 고른다.
 *
 * <p>부모 practice {@code chat-memory} 는 {@code conversationId} 를 통째로
 * 클라이언트에게 받았다. 사용자가 한 명이라 성립했던 것이고, 사용자가 둘이 되는 순간
 * 남의 대화 ID 를 넣으면 그대로 읽히는 구멍이 된다.
 *
 * <p>여기서는 접두사({@code <사용자>:})를 서버가 강제하므로 label 에 무엇을 넣어도
 * 자기 네임스페이스를 벗어나지 못한다. {@link #sanitize} 한 줄이 그 전부다 —
 * 구분자를 지우지 않으면 label 에 {@code :} 를 넣어 접두사를 위조할 수 있다.
 */
public final class ConversationId {

    private static final String SEPARATOR = ":";

    private static final String DEFAULT_LABEL = "default";

    private ConversationId() {
    }

    /** 현재 사용자의 대화 ID. label 이 비어 있으면 {@code default} 를 쓴다. */
    public static String of(Authentication authentication, String label) {
        return prefixOf(authentication) + sanitize(label);
    }

    /** 현재 사용자의 네임스페이스 접두사. 목록을 거를 때 쓴다. */
    public static String prefixOf(Authentication authentication) {
        return authentication.getName() + SEPARATOR;
    }

    /**
     * 구분자와 경로 문자를 제거한다. 이것이 격리의 전부이므로
     * {@code ConversationIdTest} 가 이 동작을 고정한다.
     */
    private static String sanitize(String label) {
        if (label == null || label.isBlank()) {
            return DEFAULT_LABEL;
        }
        String cleaned = label.trim().replaceAll("[^A-Za-z0-9가-힣_-]", "_");
        return cleaned.isBlank() ? DEFAULT_LABEL : cleaned;
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

```bash
cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test --tests '*ConversationIdTest'
```

Expected: 6개 PASS.

**공허하지 않은지 증명한다:** `sanitize` 의 `replaceAll(...)` 을 잠시 `return label.trim();` 으로
바꾸고 다시 실행한다.

Expected (그 상태): `label_에_구분자를_넣어도_남의_네임스페이스로_못_간다` 와
`경로_문자를_넣어도_벗어나지_못한다` 가 **실패**한다. 확인 후 되돌린다.

- [ ] **Step 5: ChatMemoryConfig 와 MessageView 작성**

`ChatMemoryConfig.java` — `chat-memory` 에서 가져오되 패키지만 바꾼다:

```java
package dev.starryeye.memoryauthn.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code ChatMemory} 와 {@code ChatMemoryRepository} 는 자동설정
 * ({@code ChatMemoryAutoConfiguration})이 공짜로 준다 — 둘 다
 * {@code @ConditionalOnMissingBean} 이라 여기서 정의하면 자동설정이 물러난다.
 * {@code maxMessages} 를 통제하려고 {@code ChatMemory} 만 직접 정의하고,
 * 저장소는 자동설정이 주는 {@code InMemoryChatMemoryRepository} 를 그대로 쓴다.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
```

`MessageView.java`:

```java
package dev.starryeye.memoryauthn.agent;

/** 저장소에 쌓인 메시지를 그대로 보여주기 위한 응답 타입. */
public record MessageView(String role, String text) {
}
```

- [ ] **Step 6: ChatClientConfig 에 어드바이저를 붙인다**

기존 `ChatClientConfig` 의 빈 메서드를 아래로 교체(시스템 프롬프트는 그대로 둔다):

```java
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
                                     ChatMemory chatMemory) {
        ChatClient.Builder configured = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
```

import 추가:

```java
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
```

- [ ] **Step 7: ChatController 가 Authentication 에서 대화를 파생시킨다**

기존 `chat(...)` 메서드를 아래로 교체:

```java
    /**
     * {@code conversationId} 를 클라이언트가 보내지 않는다. 서버가
     * {@link Authentication} 에서 파생시킨다 — 부모 practice {@code chat-memory} 와
     * 정확히 반대다. 클라이언트가 고를 수 있는 것은 label 뿐이고,
     * 접두사는 {@link ConversationId} 가 강제한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(Authentication authentication,
                             @RequestParam(required = false) String label,
                             @RequestBody String message) {
        String conversationId = ConversationId.of(authentication, label);
        log.debug("질문 수신 (conversationId={})", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
```

클래스 상단에 로거와 import 를 추가한다:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestParam;
```

```java
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
```

- [ ] **Step 8: ConversationController 작성 — 접두사 강제 + 목록 필터**

```java
package dev.starryeye.memoryauthn.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 저장소를 직접 들여다보는 경로다. LLM 을 부르지 않는다.
 *
 * <p>부모 practice {@code chat-memory} 의 같은 컨트롤러는 <b>아무 ID 나</b>
 * 조회할 수 있었다. 사용자가 한 명이라 문제가 없었을 뿐이다.
 * 여기서는 두 가지가 다르다:
 * <ul>
 *   <li>경로에서 <b>label 만</b> 받는다. 전체 ID 를 받는 API 는 두지 않는다 —
 *       있으면 그것이 곧 구멍이다.</li>
 *   <li>목록은 현재 사용자 접두사로 <b>거른다.</b> {@code findConversationIds()} 는
 *       저장소 전체를 알고 있으므로, 거르지 않으면 남의 대화 ID 가 노출된다.</li>
 * </ul>
 */
@RestController
public class ConversationController {

    private final ChatMemory chatMemory;

    private final ChatMemoryRepository chatMemoryRepository;

    public ConversationController(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** 현재 사용자의 대화 ID 목록. 남의 것은 보이지 않는다. */
    @GetMapping("/api/conversations")
    public List<String> conversationIds(Authentication authentication) {
        String prefix = ConversationId.prefixOf(authentication);
        return chatMemoryRepository.findConversationIds().stream()
                .filter(id -> id.startsWith(prefix))
                .toList();
    }

    /** 현재 사용자의 한 대화. label 만 받으므로 남의 것을 지목할 수 없다. */
    @GetMapping("/api/conversations/{label}")
    public List<MessageView> messages(Authentication authentication, @PathVariable String label) {
        return chatMemory.get(ConversationId.of(authentication, label)).stream()
                .map(message -> new MessageView(
                        message.getMessageType().getValue(),
                        message.getText()))
                .toList();
    }

    /** 현재 사용자의 한 대화를 비운다. */
    @DeleteMapping("/api/conversations/{label}")
    public ResponseEntity<Void> clear(Authentication authentication, @PathVariable String label) {
        chatMemory.clear(ConversationId.of(authentication, label));
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 9: application.yml 에 메모리 설정 추가**

`shop-agent/src/main/resources/application.yml` 의 최상위에 추가:

```yaml
chat:
  memory:
    # 줄여서 재기동하면 창이 밀리는 것을 관측할 수 있다.
    max-messages: 20
```

- [ ] **Step 10: ConversationControllerTest 작성**

```java
package dev.starryeye.memoryauthn.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장소 조회 경로는 LLM 을 부르지 않으므로 통합 테스트가 가능하다.
 * 이 practice 의 주장(사용자별 격리)이 여기서 검증된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void seed() {
		chatMemory.clear("alice:default");
		chatMemory.clear("bob:default");
		chatMemory.add("alice:default", List.of(
				new UserMessage("내 이름은 앨리스야"),
				new AssistantMessage("반가워요 앨리스님")));
	}

	@Test
	@WithMockUser(username = "alice")
	void 자기_대화는_읽힌다() throws Exception {
		mockMvc.perform(get("/api/conversations/default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].role").value("user"))
				.andExpect(jsonPath("$[0].text").value("내 이름은 앨리스야"));
	}

	/** 이 practice 의 핵심. bob 은 같은 label 로도 alice 의 것을 못 읽는다. */
	@Test
	@WithMockUser(username = "bob")
	void 남의_대화는_같은_label_로도_안_읽힌다() throws Exception {
		mockMvc.perform(get("/api/conversations/default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	/** 경로에 남의 전체 ID 를 밀어넣어도 자기 네임스페이스로 강제된다. */
	@Test
	@WithMockUser(username = "bob")
	void 경로에_남의_ID_를_넣어도_소용없다() throws Exception {
		mockMvc.perform(get("/api/conversations/alice:default"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@WithMockUser(username = "bob")
	void 목록에_남의_대화_ID_가_보이지_않는다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", everyItem(startsWith("bob:"))));
	}

	@Test
	@WithMockUser(username = "alice")
	void 목록에_자기_대화가_보인다() throws Exception {
		mockMvc.perform(get("/api/conversations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("alice:default")));
	}
}
```

`build.gradle` 에 `testImplementation 'org.springframework.security:spring-security-test'` 가
있는지 확인한다(부모에서 복사됐을 것이다). 없으면 추가한다.

- [ ] **Step 11: index.html 에 label 칸과 사용자 표시를 추가**

부모의 `index.html` 에 대화 label 입력칸을 넣는다. `chat-memory` 의 페이지를 참고하되
**전체 ID 가 아니라 label 만** 보내야 한다.

`fetch` 호출을 `'/api/chat?label=' + encodeURIComponent(label.value)` 로 바꾸고,
"저장 내용 보기"(`GET /api/conversations/{label}`)와 "이 대화 비우기"(`DELETE`) 버튼을 둔다.
`chat-memory` 에서 고쳤던 두 가지를 **여기서도 지킨다**: 전송 후 입력칸 비우기,
Enter 핸들러가 `send.disabled` 를 확인하기.

- [ ] **Step 12: 전체 테스트 실행**

```bash
# auth-server 를 띄운 상태에서
cd practice/mcp-security-authn-chat-memory/shop-agent && ./gradlew test
```

Expected: 부모의 5개 + `ConversationIdTest` 6개 + `ConversationControllerTest` 5개 = 16개 PASS.

- [ ] **Step 13: 커밋**

```bash
git add practice/mcp-security-authn-chat-memory/shop-agent
git commit -m "feat: conversationId 를 인증에서 파생 — 사용자별 대화 격리"
```

---

## Task 3: 종단 검증 + 툴 결과 저장 여부 실측

**Files:**
- Create: `practice/mcp-security-authn-chat-memory/run.sh`
- Create: `practice/mcp-security-authn-chat-memory/stop.sh`
- Create: `practice/mcp-security-authn-chat-memory/.gitignore`

**Interfaces:**
- Consumes: Task 1·2
- Produces: `./run.sh` / `./stop.sh`, 그리고 **검증 시나리오 6개의 관측 결과**

- [ ] **Step 1: .gitignore 작성**

```
logs/
```

- [ ] **Step 2: run.sh / stop.sh 를 부모에서 가져와 포트를 바꾼다**

`practice/mcp-security-authn-official/run.sh` 와 `stop.sh` 를 복사해 아래만 바꾼다:

- 포트: `9010`→`9020`, `8111`→`8131`, `8110`→`8130`
- 기동 순서는 그대로 `auth-server → shop-mcp-server → shop-agent`
- 안내 문구의 URL 을 `http://localhost:8130/` 으로, 로그인 안내를 `alice / alice` 로

**부모의 `run.sh` 에 있는 기동 실패 분기(`[실패]` 출력 + `tail -20` + `exit 1`)를 그대로 유지한다.**
`stop.sh` 의 `sleep` 후 `kill -9` 도 유지한다. `chmod +x` 를 잊지 말 것.

- [ ] **Step 3: 세 앱을 띄운다**

```bash
cd practice/mcp-security-authn-chat-memory && ./run.sh
```

- [ ] **Step 4: 시나리오 1 — 격리**

브라우저 두 개(또는 일반 창 + 시크릿 창)로 `http://localhost:8130/` 을 연다.

1. 창 A: `alice` / `alice` 로 로그인 → `내 이름은 앨리스야`
2. 창 B: `bob` / `bob` 으로 로그인 → `내 이름 뭐야?`

Expected: 창 B 는 모른다고 답한다.

**LLM 답변을 믿지 말고 저장소로 교차 확인한다** (창 B 에서):

```bash
curl -s -b <bob 쿠키> http://localhost:8130/api/conversations/default | python3 -m json.tool
```

브라우저에서 "저장 내용 보기" 버튼을 쓰는 편이 쉽다. 관측한 것을 그대로 기록한다.

- [ ] **Step 5: 시나리오 2 — 네임스페이스 강제**

창 B(`bob`)에서 label 칸에 `alice:default` 를 넣고 질문한다.

Expected: `bob:alice_default` 라는 **새 대화**가 생기고, `alice:default` 는 건드려지지 않는다.

확인:
- 창 B 의 대화 목록에 `bob:alice_default` 가 있고 `alice:` 로 시작하는 것은 없다
- 창 A(`alice`)의 `alice:default` 내용이 그대로다

- [ ] **Step 6: 시나리오 3 — 목록 필터**

창 A 와 창 B 에서 각각 `GET /api/conversations` 를 호출한다(브라우저 버튼 또는 curl).

Expected: 각자 자기 접두사로 시작하는 것만 보인다. 서로의 ID 가 보이지 않는다.

- [ ] **Step 7: 시나리오 4 — 한 사용자의 여러 대화**

창 A(`alice`)에서 label 을 `work` 로 바꿔 `내 취미는 등산이야` 라고 말한 뒤,
label 을 `default` 로 되돌려 `내 취미 뭐야?` 라고 묻는다.

Expected: `default` 대화는 취미를 모른다 — 같은 사용자라도 대화가 다르면 안 섞인다.

- [ ] **Step 8: ★ 시나리오 5 — 툴 결과가 메모리에 남는가**

**스펙이 미확인 위험으로 지목한 항목이다.** 이 태스크의 핵심 측정이다.

창 A(`alice`), label `tools` 로 **툴을 부르는 질문**을 던진다:

```
노트북 재고 있어?
```

답변이 끝난 뒤 저장소를 덤프한다:

```bash
curl -s -b <alice 쿠키> http://localhost:8130/api/conversations/tools | python3 -m json.tool
```

**관측할 것 — 어떤 `role` 들이 들어 있는가:**

| 관측 | 의미 |
|---|---|
| `user`, `assistant` 만 | 툴 원본 데이터는 안 남는다. 유출 표면이 작다 |
| `tool` 도 있다 | 조회 결과 원본이 대화 기록에 남는다. 토큰 수명이 끝나도 남고 이후 턴마다 LLM 에 다시 실린다 |

`assistant` 메시지의 `text` 안에 재고 숫자(7, 23)가 그대로 들어 있는지도 본다 —
`tool` 메시지가 없더라도 모델이 답변에 옮겨 적었다면 데이터는 남은 것이다.

**전체 JSON 을 그대로 리포트에 붙인다. 예측을 적지 않는다.**
결과가 어느 쪽이든 이 practice 의 결론이 된다.

- [ ] **Step 9: 시나리오 6 — 서버 로그 교차 확인**

```bash
grep '호출' logs/shop-mcp-server.log
```

Expected: `searchProducts 호출 (keyword=노트북, 사용자=alice)` — MCP 서버에 도착한 신원이
`alice` 다. 부모 practice 의 결론이 메모리를 얹은 뒤에도 유지되는지 확인하는 것이다.

- [ ] **Step 10: 종료 확인**

```bash
./stop.sh
for p in 9020 8130 8131; do lsof -ti tcp:$p || echo "포트 $p 해제됨"; done
```

- [ ] **Step 11: 커밋**

```bash
chmod +x run.sh stop.sh
git add practice/mcp-security-authn-chat-memory/run.sh \
        practice/mcp-security-authn-chat-memory/stop.sh \
        practice/mcp-security-authn-chat-memory/.gitignore
git commit -m "feat: mcp-security-authn-chat-memory 실행 스크립트"
```

---

## Task 4: README + 루트 등록

**Files:**
- Create: `practice/mcp-security-authn-chat-memory/README.md`
- Modify: `README.md` (루트 — **추가만**)

**Interfaces:**
- Consumes: Task 1·2·3 의 관측 결과
- Produces: 관측값이 담긴 README

- [ ] **Step 1: README 작성**

Task 3 에서 **실제로 관측한 값만** 적는다. 예측값을 적지 않는다.

포함할 절:

1. **한 줄 소개** — 두 부모를 합친 것. 각각으로 링크
2. **부모와 달라지는 것** — 표로. `conversationId` 파생, 조회 API 의 접두사 강제, 목록 필터, 사용자 2명
3. **핵심 — conversationId 는 클라이언트 것이 아니다**
   - `chat-memory` 는 통째로 받았고 여기서는 접두사를 강제한다는 대비
   - `ConversationId.sanitize` 가 격리의 전부라는 점과, 그것을 고정하는 테스트
   - 왜 `getName()` 하나로 안 두었는지 (label 을 안전하게 두는 법을 배우기 위해)
4. **실행** — `./run.sh`, `http://localhost:8130/`, `alice`/`alice` 와 `bob`/`bob`,
   창 두 개로 동시 로그인. curl 예시에는 **반드시 `-H 'Content-Type: text/plain'`**
5. **검증 결과** — 시나리오 1~6 의 관측값
6. **툴 결과가 메모리에 남는가** — Task 3 Step 8 의 덤프를 그대로. 이 practice 의 핵심 발견
7. **학습 포인트** — 최소한:
   - 대화를 가르는 키가 곧 격리 경계다. 그 키를 누가 정하느냐가 전부다
   - `findConversationIds()` 는 저장소 전체를 안다 — 거르지 않으면 남의 ID 가 샌다.
     `chat-memory` 에 그 필터가 없었던 것은 버그가 아니라 사용자 1명 가정의 결과였다
   - 전체 ID 를 받는 API 를 두지 않는 것이 가장 확실한 방어다
   - Task 3 Step 8 에서 관측한 것
   - 부모에서 그대로 유지된 것들(기동 순서, `initialized: false`, 조용한 스위치, 세션 쿠키)
8. **답하지 않는 질문** — 스펙의 "이 practice 가 답하지 않는 질문" 세 가지를
   **열린 질문으로** 싣는다. 정책을 만들지 않는다
9. **트러블슈팅** — 표로
10. **다음 practice 로** — `mcp-security-authz` (스코프·툴 단위 인가)

- [ ] **Step 2: 루트 README 에 행 추가**

루트 `README.md` 의 practice 표에 행을 **하나 추가**한다. 기존 5행은 그대로 둔다.

```
| [`practice/mcp-security-authn-chat-memory/`](practice/mcp-security-authn-chat-memory) | 위 둘을 합쳐 사용자별로 대화를 기억한다. `conversationId` 를 클라이언트가 아니라 `Authentication` 에서 파생시켜 격리한다. `auth-server` :9020 / `shop-mcp-server` :8131 / `shop-agent` :8130 |
```

- [ ] **Step 3: 무변경 확인**

```bash
git status --short
git diff --stat HEAD~3..HEAD -- practice/agent-mcp practice/agent-mcps \
    practice/mcp-security-authn-community practice/mcp-security-authn-official practice/chat-memory
```

두 번째 명령의 출력이 **비어 있어야 한다.**

```bash
grep -rn 'springaicommunity' practice/mcp-security-authn-chat-memory/ --include='*.gradle' \
  || echo "✔ 커뮤니티 의존성 없음"
```

- [ ] **Step 4: 커밋**

```bash
git add practice/mcp-security-authn-chat-memory/README.md README.md
git commit -m "docs: mcp-security-authn-chat-memory README — 관측된 격리 검증 결과"
```

---

## 완료 조건

- [ ] `./run.sh` 로 세 앱이 뜬다
- [ ] `alice` 와 `bob` 이 동시에 로그인해도 서로의 대화를 못 본다 (저장소 조회로 확인)
- [ ] `bob` 이 label 에 `alice:default` 를 넣어도 자기 네임스페이스로 강제된다
- [ ] `GET /api/conversations` 가 자기 접두사로 걸러진다
- [ ] 같은 사용자라도 label 이 다르면 대화가 안 섞인다
- [ ] **툴 호출 뒤 저장소에 무엇이 남는지가 README 에 관측값으로 적혀 있다**
- [ ] `logs/shop-mcp-server.log` 에 `사용자=alice`
- [ ] `./gradlew test` 통과 (auth-server 를 띄운 상태에서)
- [ ] `ConversationIdTest` 가 `sanitize` 를 무력화하면 실패하는 것을 확인했다
- [ ] 기존 practice 5개 무변경
- [ ] 루트 README 에 practice 6개가 나열된다
