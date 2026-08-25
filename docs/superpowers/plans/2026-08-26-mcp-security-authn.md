# mcp-security-authn 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `org.springaicommunity` 의 MCP 보안 3개 모듈을 authorization_code + servlet 으로 최소 연동해, 토큰 없이는 MCP 서버를 호출할 수 없고 브라우저로 로그인한 사용자를 대신해서만 툴이 호출되는 것을 실제로 보여준다.

**Architecture:** 독립 Spring Boot 앱 3개. `auth-server`(:9000)가 토큰을 발급하고, `shop-mcp-server`(:8101)가 `Bearer` 토큰을 검증하며, `shop-agent`(:8100)가 브라우저 로그인으로 얻은 사용자 토큰을 MCP 호출에 붙인다. 전 구간 servlet 스택이고 MCP 서버/클라이언트 모두 `SYNC` 다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Spring Security 7.1.0, `org.springaicommunity:*:0.1.14`, Ollama `qwen3:8b`, Gradle (프로젝트별 wrapper)

**Spec:** [docs/superpowers/specs/2026-08-25-mcp-security-authn-design.md](../specs/2026-08-25-mcp-security-authn-design.md)

---

## Global Constraints

프로젝트 전역 요구사항이다. 모든 태스크의 요구사항에 암묵적으로 포함된다.

### 절대 건드리지 않을 것

- `practice/agent-mcp/**` 와 `practice/agent-mcps/**` 는 **한 줄도 수정하지 않는다.** 읽기만 허용.
- 루트 `README.md`, 루트 `.gitignore`, `legacy-0.8/**` 도 수정하지 않는다.
- 이 저장소에는 루트 `settings.gradle` 이 **없다.** 각 프로젝트가 자체 wrapper 를 갖는 독립 빌드다.
  따라서 이 practice 는 공용 파일을 **하나도** 고칠 필요가 없다. 고치고 싶어지면 잘못 가고 있는 것이다.
- `.superpowers/` 는 절대 `git add` 하지 않는다.

### 버전 고정

- Java 21 (`languageVersion = JavaLanguageVersion.of(21)`). 시스템 기본은 Java 17 이므로 toolchain 필수.
- Spring Boot `4.1.0`, `io.spring.dependency-management` `1.1.7`
- Spring AI BOM `2.0.0`
- `org.springaicommunity:*` 는 **`0.1.14`** 고정. BOM 이 없으므로 버전을 직접 적는다.
  (`0.1.x` 가 Spring AI `2.0.x` 전용이다. `1.1.x` 용은 `0.0.6`.)
- `group = 'dev.starryeye'`

### 반환 타입 — 앞 practice 와 정반대다

`agent-mcp` / `agent-mcps` 의 계획서는 "`@McpTool` 은 **반드시** `Mono` 를 반환" 이라고 못 박았다.
**이 practice 에서는 그 지시가 틀렸다.**

| | agent-mcp / agent-mcps | **여기** |
|---|---|---|
| MCP 서버 타입 | `type: ASYNC` | **`type: SYNC`** |
| `@McpTool` 반환 | `Mono<String>` | **`String`** (평문) |

SYNC 서버는 리액티브 반환 타입을 **조용히 걸러낸다.** `Mono<String>` 을 반환하면 오류 없이 툴이
등록에서 빠진다. 앞 practice 의 코드를 복사해 올 때 가장 먼저 확인할 지점이다.

### 조용히 죽는 스위치 — 소스 실측으로 확인한 것들

이 모듈들은 조건이 안 맞으면 **오류 없이 비활성화된다.** 전부 실제 소스에서 확인했다.

| 스위치 | 안 지키면 | 근거 |
|---|---|---|
| `spring.ai.mcp.client.type: SYNC` | 클라이언트 보안 자동설정 전체가 사라진다 → 토큰이 안 붙는다 | `McpOAuth2ClientAutoConfiguration` 의 `@ConditionalOnProperty(name="type", havingValue="SYNC")` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` 설정 | MCP 서버 보안 자동설정이 **아예 안 뜬다** → `/mcp` 가 무방비로 열린다 | `McpServerSecurityAutoConfiguration` 의 `@ConditionalOnProperty(..., matchIfMissing = false)` |
| `shop-mcp-server` / `auth-server` 에 **직접 `SecurityFilterChain` 빈을 만들지 않는다** | `@ConditionalOnDefaultWebSecurity` 가 꺼져 모듈 설정이 통째로 물러난다 | 두 자동설정 클래스의 `@ConditionalOnDefaultWebSecurity` |
| `spring.security.oauth2.client.registration.*` 이 **정확히 1개** | 0개나 2개 이상이면 WARN 한 줄 남기고 **아무 일도 안 하는 커스터마이저**가 붙는다 | `HttpClientStreamableHttpTransportAutoConfiguration.preRegisteredClientCustomizer` |
| 스트리밍에 `.contextWrite(...)` | 토큰이 안 붙어 MCP 서버가 401 | `AuthenticationMcpTransportContextProvider` javadoc + `fromThreadLocals()` |

마지막 항목이 이 practice 의 핵심이다. 아래에서 따로 다룬다.

### 스트리밍에는 `.contextWrite` 가 반드시 필요하다

`AuthenticationMcpTransportContextProvider` 는 `SecurityContextHolder` 와 `RequestContextHolder`
**thread-local** 에서 인증 정보를 읽는다. `ChatClient.stream()` 의 리액터 체인은 요청 스레드가
아닌 곳에서 실행되므로 thread-local 이 비어 있다. 그러면:

1. `McpTransportContext.EMPTY` 가 만들어지고
2. `OAuth2AuthorizationCodeSyncHttpRequestCustomizer.customize()` 가
   `"No authentication or request context found: not requesting token"` 을 **DEBUG 로만** 찍고 그냥 return 하고
3. `Authorization` 헤더 없이 요청이 나가 MCP 서버가 401 을 준다

라이브러리 javadoc 이 직접 지시하는 해법을 그대로 쓴다:

```java
chatClient.prompt().user(message).stream().content()
        .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext())
```

`writeToReactorContext()` 는 **호출되는 그 순간의** thread-local 을 캡처한다. 반드시 컨트롤러
메서드 본문(요청 스레드)에서 호출해야 한다.

> 앞 practice 의 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 감싸기는 **여기서 필요 없다.**
> 그건 WebFlux 이벤트 루프를 블로킹하지 않으려던 조치인데, servlet 에는 이벤트 루프가 없다.
> 오히려 그렇게 감싸면 thread-local 캡처 시점이 달라져 해가 된다.

### 앞 practice 에서 이미 값을 치른 것들

다시 발견하지 말 것.

| 항목 | 내용 |
|---|---|
| 툴 배선 | `defaultTools(provider)` 로 직접 꽂아야 한다. 자동으로 안 붙는다 |
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` |
| 응답 | `text/plain;charset=UTF-8` (SSE 는 토큰마다 `data:` 가 붙어 읽을 수 없다) |
| ollama | `spring.ai.ollama.chat.think: low` (미지정은 수 분, `false` 는 툴 설명을 못 따른다) |
| 애노테이션 | `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}` |
| 속성 | `spring.ai.ollama.chat.model` (`chat.options.*` 아님) |
| 스크립트 | 백그라운드 프로세스는 `< /dev/null` + `disown`. 안 하면 스크립트가 호출자를 붙잡는다 |
| 테스트 | 한국어 메서드명, AssertJ 사용 |

### 기동 순서

`auth-server` → `shop-mcp-server` → `shop-agent`.

> **정정 (Task 2 실측):** 이 자리에 원래 "JWT 디코더가 지연 조회라 auth-server 없이도
> 기동된다"고 적었는데 **틀렸다.** `NimbusJwtDecoder.withIssuerLocation(issuer).build()` 의
> `.build()` 가 issuer 메타데이터를 즉시 HTTP 로 가져온다. `SecurityFilterChain` 빈을 만드는
> 시점에 일어나므로, auth-server 가 떠 있지 않으면 `shop-mcp-server` 는 `ConnectException` 으로
> **기동 자체가 실패하고 `@SpringBootTest` 도 실패한다.**
>
> 결론(auth-server 를 먼저 띄운다)은 그대로지만 이유가 다르다. 그리고 부수 효과가 하나 있다 —
> **`shop-mcp-server` 의 테스트를 돌리려면 auth-server 가 먼저 떠 있어야 한다.**

---

## File Structure

```
practice/mcp-security-authn/
├── README.md                         # 설명 + 관측된 검증 결과
├── run.sh / stop.sh                  # 3개 앱 기동/종료
├── .gitignore                        # logs/
│
├── auth-server/                      # :9000 — 토큰 발급
│   ├── build.gradle
│   └── src/main/java/dev/starryeye/authserver/
│       ├── AuthServerApplication.java
│       └── UserConfig.java           # in-memory 사용자 1명
│   └── src/main/resources/application.yml
│
├── shop-mcp-server/                  # :8101 — 보호된 MCP 서버
│   ├── build.gradle
│   └── src/main/java/dev/starryeye/shopmcpserver/
│       ├── ShopMcpServerApplication.java
│       ├── Product.java              # record
│       ├── ProductRepository.java    # 시드 10건 (앞 practice 와 동일)
│       └── ProductTools.java         # @McpTool x2, String 반환
│   └── src/main/resources/application.yml
│
└── shop-agent/                       # :8100 — 브라우저 UI + LLM + MCP 클라이언트
    ├── build.gradle
    └── src/main/java/dev/starryeye/shopagent/
        ├── ShopAgentApplication.java
        ├── SecurityConfig.java       # oauth2Login + oauth2Client
        ├── ChatClientConfig.java     # 시스템 프롬프트 + defaultTools
        └── ChatController.java       # /api/chat, contextWrite
    └── src/main/resources/
        ├── application.yml
        └── static/index.html         # 입력창 + 버튼 + <pre>
```

책임 분리: 각 앱은 보안 3요소(발급 / 검증 / 사용) 중 하나만 담당한다. `shop-mcp-server` 는
LLM 을 모르고, `auth-server` 는 MCP 를 모른다.

---

## Task 1: auth-server — 토큰 발급

**Files:**
- Create: `practice/mcp-security-authn/auth-server/build.gradle`
- Create: `practice/mcp-security-authn/auth-server/src/main/java/dev/starryeye/authserver/AuthServerApplication.java`
- Create: `practice/mcp-security-authn/auth-server/src/main/java/dev/starryeye/authserver/UserConfig.java`
- Create: `practice/mcp-security-authn/auth-server/src/main/resources/application.yml`
- Test: `practice/mcp-security-authn/auth-server/src/test/java/dev/starryeye/authserver/AuthServerApplicationTests.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `http://localhost:9000` 에서 OAuth2 인가 서버.
  - 등록 클라이언트 `client-id=shop-agent`, `client-secret=shop-agent-secret`
  - redirect URI `http://localhost:8100/login/oauth2/code/authserver`
  - scopes `openid`, `profile`
  - 사용자 `user` / `password`
  - 메타데이터: `GET /.well-known/openid-configuration` → 200

- [ ] **Step 1: Initializr 로 뼈대 생성 (wrapper·gitignore 확보)**

```bash
mkdir -p practice/mcp-security-authn && cd practice/mcp-security-authn
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=auth-server -d name=auth-server \
  -d packageName=dev.starryeye.authserver -d javaVersion=21 \
  -d dependencies=web \
  -o auth-server.zip && unzip -q auth-server.zip -d auth-server && rm auth-server.zip
```

`bootVersion` 은 반드시 `4.1.0` (`4.1.0.RELEASE` 는 HTTP 500).

- [ ] **Step 2: build.gradle 을 아래 내용으로 교체**

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.0'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.starryeye'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	// mcp-authorization-server-spring-boot 가 spring-boot-security-oauth2-authorization-server 와
	// mcp-authorization-server(코어) 를 전이 의존으로 끌어온다.
	implementation 'org.springaicommunity:mcp-authorization-server-spring-boot:0.1.14'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 9000

spring:
  application:
    name: auth-server
  security:
    oauth2:
      authorizationserver:
        issuer: http://localhost:9000
        client:
          shop-agent:
            registration:
              client-id: shop-agent
              client-secret: "{noop}shop-agent-secret"
              client-authentication-methods:
                - client_secret_basic
              authorization-grant-types:
                - authorization_code
                - refresh_token
              redirect-uris:
                - http://localhost:8100/login/oauth2/code/authserver
              scopes:
                - openid
                - profile
            # 학습용이므로 동의 화면을 건너뛴다. 켜두면 로그인 후 화면이 하나 더 뜬다.
            require-authorization-consent: false

logging:
  level:
    org.springframework.security: INFO
```

> `{noop}` 은 평문 비밀번호 인코딩 표시다. 학습용이라 그대로 둔다.

- [ ] **Step 4: UserConfig 작성 (in-memory 사용자 1명)**

```java
package dev.starryeye.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명. user / password 로 로그인한다.
 *
 * <p>여기에 {@code SecurityFilterChain} 빈을 추가하면 안 된다.
 * {@code McpAuthorizationServerAutoConfiguration} 이 {@code @ConditionalOnDefaultWebSecurity}
 * 라서, 직접 만든 필터체인이 있으면 인가 서버 설정이 통째로 물러난다.
 * 필터체인(인가 서버용 + 폼 로그인용) 두 개는 그 자동설정이 이미 제공한다.
 */
@Configuration
public class UserConfig {

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername("user")
                        .password("{noop}password")
                        .roles("USER")
                        .build()
        );
    }
}
```

- [ ] **Step 5: 실패하는 테스트 작성**

`src/test/java/dev/starryeye/authserver/AuthServerApplicationTests.java` 를 아래로 교체:

```java
package dev.starryeye.authserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthServerApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RegisteredClientRepository registeredClientRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void OIDC_메타데이터를_공개한다() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9000"));
	}

	@Test
	void shop_agent_클라이언트가_등록되어_있다() {
		var client = registeredClientRepository.findByClientId("shop-agent");

		assertThat(client).isNotNull();
		assertThat(client.getRedirectUris())
				.containsExactly("http://localhost:8100/login/oauth2/code/authserver");
	}

	@Test
	void 로그인_화면이_제공된다() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
	}
}
```

- [ ] **Step 6: 테스트가 통과하는지 실행**

```bash
cd practice/mcp-security-authn/auth-server && ./gradlew test
```

Expected: 4개 테스트 모두 PASS.

실패하면: `OIDC_메타데이터를_공개한다` 가 404 면 `spring.security.oauth2.authorizationserver.issuer` 오타를,
`shop_agent_클라이언트가_등록되어_있다` 가 null 이면 `client.shop-agent.registration.*` 들여쓰기를 본다.

- [ ] **Step 7: 실제로 기동해 확인**

```bash
cd practice/mcp-security-authn/auth-server && ./gradlew bootRun
```

다른 터미널에서:

```bash
curl -s http://localhost:9000/.well-known/openid-configuration | python3 -m json.tool | head -20
```

Expected: `issuer`, `authorization_endpoint`, `token_endpoint`, `jwks_uri` 가 보인다.
확인 후 `Ctrl-C` 로 종료.

- [ ] **Step 8: 커밋**

```bash
git add practice/mcp-security-authn/auth-server
git commit -m "feat: mcp-security-authn auth-server — MCP 인가 서버로 토큰 발급"
```

---

## Task 2: shop-mcp-server — 보호된 MCP 서버

이 practice 의 **핵심 태스크**다. `agent-mcps` 에서 열려 있던 구멍이 닫히는 지점이다.

**Files:**
- Create: `practice/mcp-security-authn/shop-mcp-server/build.gradle`
- Create: `practice/mcp-security-authn/shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/ShopMcpServerApplication.java`
- Create: `practice/mcp-security-authn/shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/Product.java`
- Create: `practice/mcp-security-authn/shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/ProductRepository.java`
- Create: `practice/mcp-security-authn/shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/ProductTools.java`
- Create: `practice/mcp-security-authn/shop-mcp-server/src/main/resources/application.yml`
- Test: `practice/mcp-security-authn/shop-mcp-server/src/test/java/dev/starryeye/shopmcpserver/ShopMcpServerApplicationTests.java`
- Test: `practice/mcp-security-authn/shop-mcp-server/src/test/java/dev/starryeye/shopmcpserver/ProductToolsTest.java`

**Interfaces:**
- Consumes: Task 1 의 issuer `http://localhost:9000` (기동 시점에는 불필요 — 지연 조회)
- Produces: `http://localhost:8101/mcp` 에 툴 2개를 노출하는 **인증 필수** MCP 서버
  - `searchProducts(String keyword) -> String` (keyword 는 `required = false`)
  - `getStock(String productId) -> String` (productId 는 `required = true`)
  - `ProductRepository.findByKeyword(String) -> List<Product>`, `findById(String) -> Optional<Product>`
  - `Product(String id, String name, String category, int price, int stock)`

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
cd practice/mcp-security-authn
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=shop-mcp-server -d name=shop-mcp-server \
  -d packageName=dev.starryeye.shopmcpserver -d javaVersion=21 \
  -d dependencies=web \
  -o shop-mcp-server.zip && unzip -q shop-mcp-server.zip -d shop-mcp-server && rm shop-mcp-server.zip
```

- [ ] **Step 2: build.gradle 교체**

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.0'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.starryeye'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

ext {
	set('springAiVersion', "2.0.0")
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	// webflux 가 아니라 webmvc 다 — servlet 스택이므로.
	implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
	// mcp-server-security(코어) 와 spring-boot-security-oauth2-resource-server 를 전이 의존으로 끌어온다.
	implementation 'org.springaicommunity:mcp-server-security-spring-boot:0.1.14'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.security:spring-security-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
	imports {
		mavenBom "org.springframework.ai:spring-ai-bom:${springAiVersion}"
	}
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 8101

spring:
  application:
    name: shop-mcp-server
  ai:
    mcp:
      server:
        name: shop-mcp-server
        version: 0.0.1
        protocol: STREAMABLE
        # SYNC 다. ASYNC 였던 앞 practice 와 반대이고,
        # @McpTool 이 Mono 를 반환하면 조용히 등록에서 빠진다.
        type: SYNC
  security:
    oauth2:
      resourceserver:
        jwt:
          # 이 줄이 없으면 McpServerSecurityAutoConfiguration 이 아예 뜨지 않아
          # /mcp 가 무방비로 열린다. @ConditionalOnProperty(matchIfMissing = false).
          issuer-uri: http://localhost:9000

logging:
  level:
    org.springframework.ai: INFO
    org.springaicommunity.mcp.security: DEBUG
```

- [ ] **Step 4: Product / ProductRepository 작성 (시드 10건은 앞 practice 와 동일)**

`Product.java`:

```java
package dev.starryeye.shopmcpserver;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
```

`ProductRepository.java`:

```java
package dev.starryeye.shopmcpserver;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 시드 데이터는 agent-mcp / agent-mcps 와 의도적으로 동일하다.
 * 세 practice 를 오가며 같은 질문의 답을 비교하기 위해서다.
 */
@Repository
public class ProductRepository {

    private final Map<String, Product> store = new LinkedHashMap<>();

    public ProductRepository() {
        List.of(
                new Product("p1", "게이밍 노트북 15인치", "노트북", 1_890_000, 7),
                new Product("p2", "사무용 노트북 14인치", "노트북", 990_000, 23),
                new Product("p3", "무선 기계식 키보드", "주변기기", 149_000, 0),
                new Product("p4", "인체공학 마우스", "주변기기", 59_000, 145),
                new Product("p5", "27인치 4K 모니터", "모니터", 549_000, 12),
                new Product("p6", "34인치 울트라와이드 모니터", "모니터", 899_000, 3),
                new Product("p7", "노이즈 캔슬링 헤드폰", "음향", 379_000, 41),
                new Product("p8", "USB-C 도킹 스테이션", "주변기기", 219_000, 18),
                new Product("p9", "휴대용 SSD 1TB", "저장장치", 139_000, 62),
                new Product("p10", "웹캠 1080p", "주변기기", 89_000, 0)
        ).forEach(product -> store.put(product.id(), product));
    }

    public List<Product> findByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.copyOf(store.values());
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return store.values().stream()
                .filter(product -> product.name().toLowerCase(Locale.ROOT).contains(normalized)
                        || product.category().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    public Optional<Product> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }
}
```

- [ ] **Step 5: ProductTools 작성 — 반환은 평문 `String`**

```java
package dev.starryeye.shopmcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * SYNC 서버이므로 반환 타입은 평문 {@code String} 이다.
 * {@code Mono<String>} 으로 바꾸면 오류 없이 툴 등록에서 빠진다 —
 * {@code ShopMcpServerApplicationTests} 의 등록 테스트가 그 사고를 잡는다.
 */
@Component
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    private final ProductRepository productRepository;

    public ProductTools(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @McpTool(
            name = "searchProducts",
            description = "판매 중인 상품을 검색한다. "
                    + "각 상품의 ID, 이름, 카테고리, 가격, 재고 수량을 함께 반환한다. "
                    + "키워드는 상품명과 카테고리명에만 부분일치로 적용된다. "
                    + "따라서 가격·재고처럼 이름이 아닌 조건으로 거르려면 "
                    + "(예: '10만원 넘는 상품', '품절 아닌 것') 키워드 없이 호출해 전체 목록을 받은 뒤 "
                    + "그 결과를 직접 판단해야 한다. 조건을 키워드로 넘기면 아무것도 찾지 못한다."
    )
    public String searchProducts(
            @McpToolParam(description = "상품명 또는 카테고리명의 일부. "
                    + "가격·재고 같은 조건이나 문장을 넣으면 안 된다. "
                    + "특정 상품을 지목하지 않는 질문이면 생략한다.", required = false)
            String keyword) {
        log.info("searchProducts 호출 (keyword={}, 사용자={})", keyword, currentUser());

        String joined = productRepository.findByKeyword(keyword).stream()
                .map(product -> "- [%s] %s (%s) / %,d원 / 재고 %d개"
                        .formatted(product.id(), product.name(), product.category(),
                                product.price(), product.stock()))
                .collect(Collectors.joining("\n"));

        return joined.isBlank()
                ? "'%s' 에 해당하는 상품이 없습니다.".formatted(keyword)
                : joined;
    }

    @McpTool(
            name = "getStock",
            description = "상품 ID로 현재 재고 수량을 조회한다. "
                    + "사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. "
                    + "상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다."
    )
    public String getStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)
            String productId) {
        log.info("getStock 호출 (productId={}, 사용자={})", productId, currentUser());

        return productRepository.findById(productId)
                .map(product -> product.stock() == 0
                        ? "상품 %s (%s) 는 현재 품절입니다. (재고 0개)"
                                .formatted(productId, product.name())
                        : "상품 %s (%s) 의 현재 재고는 %d개입니다."
                                .formatted(productId, product.name(), product.stock()))
                .orElse("상품 %s 를 찾을 수 없습니다.".formatted(productId));
    }

    /**
     * 누가 이 툴을 호출했는지 로그에 남긴다. 검증 시나리오 4번이 이 값을 본다.
     * 토큰의 {@code sub} 가 그대로 principal 이름이 되므로, 에이전트가 아니라
     * <em>사용자</em> 가 찍히는 것이 이 practice 의 관찰 포인트다.
     */
    private String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "(인증정보 없음)" : authentication.getName();
    }
}
```

- [ ] **Step 6: 실패하는 보안 테스트 작성**

`src/test/java/dev/starryeye/shopmcpserver/ShopMcpServerApplicationTests.java` 를 아래로 교체:

```java
package dev.starryeye.shopmcpserver;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopMcpServerApplicationTests {

	/**
	 * 자동설정은 {@code List<SyncToolSpecification>} 빈을 둘 만든다
	 * (어노테이션 스캔용, {@code ToolCallback} 변환용) — 둘 다 모으려면
	 * 단일 {@code List} 대신 {@link ObjectProvider} 로 받아야 한다.
	 */
	@Autowired
	ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> toolSpecificationLists;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 이 practice 의 존재 이유. agent-mcps 에서는 이 요청이 통했다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		mockMvc.perform(post("/"))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * SYNC 서버는 리액티브 반환 타입을 조용히 걸러낸다.
	 * ProductTools 의 반환 타입을 Mono<String> 으로 바꾸면 이 테스트만 깨진다.
	 */
	@Test
	void MCP_툴이_실제로_등록된다() {
		List<String> toolNames = toolSpecificationLists.orderedStream()
				.flatMap(List::stream)
				.map(spec -> spec.tool().name())
				.toList();

		assertThat(toolNames).containsExactlyInAnyOrder("searchProducts", "getStock");
	}
}
```

- [ ] **Step 7: 테스트를 실행해 실패를 확인**

```bash
cd practice/mcp-security-authn/shop-mcp-server && ./gradlew test
```

Expected: 이 시점에는 아직 통과해야 정상이다 (설정이 이미 다 들어갔으므로).
**중요:** `토큰_없이_MCP_엔드포인트를_호출하면_401이다` 가 통과하는 것이 실제 보안 때문인지
확인하려면, `application.yml` 의 `issuer-uri` 줄을 잠시 주석 처리하고 다시 실행한다.

> **정정 (실측):** 여기에 "401 테스트가 실패한다 (200 또는 다른 코드)"고 적었는데 **틀렸다.**
> `spring-boot-starter-security` 가 클래스패스에 있으면 `issuer-uri` 를 지워도 Boot 기본
> 보안(HTTP Basic + 무작위 비밀번호)이 대신 활성화되어 **여전히 401 을 준다.**
> 즉 상태 코드만 보는 테스트는 MCP 보안 모듈이 통째로 빠져도 초록이다.
>
> 실제로 갈라지는 신호는 `WWW-Authenticate` 스킴이다:
>
> | 상태 | `WWW-Authenticate` |
> |---|---|
> | 모듈 활성 | `Bearer resource_metadata=...` |
> | `issuer-uri` 없음 (Boot 기본 보안) | `Basic realm="Realm"` |
>
> 그래서 401 테스트는 **헤더가 `Bearer` 로 시작하는지까지 검사한다.** 이 실험은 그 강화된
> 테스트가 실제로 빨간불이 되는지를 확인하는 절차다. 확인 후 주석을 되돌린다.
>
> "설정 한 줄을 빼면 무방비로 열린다"가 아니라 **"의도와 다른 보안으로 조용히 바뀐다"** 가
> 진짜 관측 결과다 — 더 나쁜 종류의 실패다. 겉으로는 잠겨 보이기 때문이다.

- [ ] **Step 8: 툴 단위 테스트 작성**

`src/test/java/dev/starryeye/shopmcpserver/ProductToolsTest.java`:

```java
package dev.starryeye.shopmcpserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYNC 라 반환이 평문 String 이다 — StepVerifier 도, 스텁도 필요 없다.
 */
class ProductToolsTest {

    private final ProductTools tools = new ProductTools(new ProductRepository());

    @Test
    void 키워드로_상품을_검색한다() {
        String text = tools.searchProducts("노트북");

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("사무용 노트북 14인치")
                .doesNotContain("웹캠");
    }

    @Test
    void 키워드를_생략하면_전체를_반환한다() {
        String text = tools.searchProducts(null);

        assertThat(text)
                .contains("게이밍 노트북 15인치")
                .contains("웹캠 1080p");
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        assertThat(tools.searchProducts("없는상품")).contains("없습니다");
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        assertThat(tools.getStock("p1")).contains("7");
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        assertThat(tools.getStock("p3")).contains("품절");
    }

    @Test
    void 없는_상품이면_예외_대신_문장을_반환한다() {
        assertThat(tools.getStock("없는ID")).contains("찾을 수 없습니다");
    }
}
```

- [ ] **Step 9: 전체 테스트 실행**

```bash
cd practice/mcp-security-authn/shop-mcp-server && ./gradlew test
```

Expected: 10개 테스트 모두 PASS.

- [ ] **Step 10: 실제로 기동해 401 을 눈으로 확인**

```bash
cd practice/mcp-security-authn/shop-mcp-server && ./gradlew bootRun
```

다른 터미널에서 (auth-server 는 안 떠 있어도 된다 — 지연 조회이므로):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8101/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expected: `401`. 응답 헤더의 `WWW-Authenticate` 도 확인:

```bash
curl -s -D - -o /dev/null -X POST http://localhost:8101/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -i 'www-authenticate'
```

Expected: `Bearer ... resource_metadata=...` 형태. 확인 후 `Ctrl-C`.

- [ ] **Step 11: 커밋**

```bash
git add practice/mcp-security-authn/shop-mcp-server
git commit -m "feat: mcp-security-authn shop-mcp-server — 토큰 없이는 401 인 MCP 서버"
```

---

## Task 3: shop-agent — 브라우저 로그인 + LLM + MCP 클라이언트

**Files:**
- Create: `practice/mcp-security-authn/shop-agent/build.gradle`
- Create: `practice/mcp-security-authn/shop-agent/src/main/java/dev/starryeye/shopagent/ShopAgentApplication.java`
- Create: `practice/mcp-security-authn/shop-agent/src/main/java/dev/starryeye/shopagent/SecurityConfig.java`
- Create: `practice/mcp-security-authn/shop-agent/src/main/java/dev/starryeye/shopagent/ChatClientConfig.java`
- Create: `practice/mcp-security-authn/shop-agent/src/main/java/dev/starryeye/shopagent/ChatController.java`
- Create: `practice/mcp-security-authn/shop-agent/src/main/resources/application.yml`
- Create: `practice/mcp-security-authn/shop-agent/src/main/resources/static/index.html`
- Test: `practice/mcp-security-authn/shop-agent/src/test/java/dev/starryeye/shopagent/ShopAgentApplicationTests.java`

**Interfaces:**
- Consumes:
  - Task 1: auth-server `http://localhost:9000`, client `shop-agent` / `shop-agent-secret`
  - Task 2: MCP 서버 `http://localhost:8101` (엔드포인트는 기본 `/mcp`)
- Produces: `http://localhost:8100/` 브라우저 UI, `POST /api/chat` (본문=질문 평문, 응답=`text/plain` 스트리밍)

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
cd practice/mcp-security-authn
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=shop-agent -d name=shop-agent \
  -d packageName=dev.starryeye.shopagent -d javaVersion=21 \
  -d dependencies=web \
  -o shop-agent.zip && unzip -q shop-agent.zip -d shop-agent && rm shop-agent.zip
```

- [ ] **Step 2: build.gradle 교체**

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '4.1.0'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.starryeye'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

ext {
	set('springAiVersion', "2.0.0")
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
	// webflux 가 아닌 기본(HttpClient 기반) MCP 클라이언트 스타터다.
	// mcp-client-security 는 HttpClientStreamableHttpTransport 를 커스터마이즈한다.
	implementation 'org.springframework.ai:spring-ai-starter-mcp-client'
	implementation 'org.springaicommunity:mcp-client-security-spring-boot:0.1.14'
	implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.security:spring-security-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
	imports {
		mavenBom "org.springframework.ai:spring-ai-bom:${springAiVersion}"
	}
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 8100

spring:
  application:
    name: shop-agent
  ai:
    model:
      chat: ollama
      # chat 외의 모델 자동설정을 끄지 않으면 다른 스타터가 API 키를 요구해 기동이 실패한다.
      audio:
        speech: none
        transcription: none
      embedding: none
      image: none
      moderation: none
    ollama:
      chat:
        model: qwen3:8b
        temperature: 0.1
        # 미지정이면 수 분이 걸리고, false 면 툴 설명을 못 따른다.
        think: low
    mcp:
      client:
        enabled: true
        # SYNC 여야 한다. ASYNC 면 mcp-client-security 자동설정이 통째로 사라져
        # 토큰이 안 붙고, 증상은 조용한 401 뿐이다.
        type: SYNC
        streamable-http:
          connections:
            shop:
              url: "http://localhost:8101"
  security:
    oauth2:
      client:
        registration:
          # 등록은 정확히 하나여야 한다. 둘 이상이면 transport 커스터마이저가
          # WARN 한 줄 남기고 아무 것도 하지 않는다.
          authserver:
            client-id: shop-agent
            client-secret: shop-agent-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile
        provider:
          authserver:
            issuer-uri: http://localhost:9000

logging:
  level:
    org.springframework.ai: INFO
    # 토큰이 안 붙는 증상은 DEBUG 로만 보인다. 켜 둔다.
    org.springaicommunity.mcp.security: DEBUG
```

- [ ] **Step 4: SecurityConfig 작성**

```java
package dev.starryeye.shopagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 에이전트는 모든 요청에 로그인을 요구한다. 브라우저로 "/" 에 들어오면
 * auth-server 로 튕겨 로그인하고 돌아온다.
 *
 * <p>여기서 만드는 {@code OAuth2AuthorizedClient} 가 그대로 MCP 호출에 쓰인다 —
 * 즉 사용자가 로그인해서 받은 토큰이 MCP 서버로 간다. 이것이
 * authorization_code 를 고른 이유다.
 *
 * <p>MCP 서버·인가 서버와 달리 에이전트에는 {@code @ConditionalOnDefaultWebSecurity}
 * 제약이 없다. 여기서는 필터체인을 직접 정의해도 된다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                // MCP 호출 시 토큰을 얻으려면 oauth2Client 가 필요하다.
                .oauth2Client(Customizer.withDefaults())
                // 학습용 단순화: index.html 의 fetch 가 CSRF 토큰을 싣지 않으므로
                // 이 엔드포인트만 예외로 둔다. 실제 서비스라면 토큰을 실어 보내야 한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }
}
```

- [ ] **Step 5: ChatClientConfig 작성**

```java
package dev.starryeye.shopagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            당신은 온라인 쇼핑몰의 상담 도우미입니다.
            상품과 재고에 대한 질문에는 반드시 제공된 툴을 사용해 실제 데이터를 조회한 뒤 답하세요.
            기억이나 추측으로 답하지 마세요.
            조회 결과가 없으면 없다고 그대로 알려주세요.
            답변은 한국어로 간결하게 합니다.
            """;

    /**
     * MCP 툴은 자동으로 모델에 전달되지 않는다. MCP 클라이언트 자동설정이 만들어 주는 것은
     * {@link ToolCallbackProvider} 빈까지이고, {@code defaultTools(...)} 로 직접 꽂아야
     * LLM 이 툴 정의를 받는다. 이 줄을 지우면 기동도 되고 답변도 오지만 —
     * 모델은 툴 없이 기억으로만 답한다.
     */
    @Bean
    public ChatClient shopChatClient(ChatClient.Builder builder,
                                     ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
        toolCallbackProvider.ifAvailable(configured::defaultTools);
        return configured.build();
    }
}
```

- [ ] **Step 6: ChatController 작성 — `contextWrite` 가 핵심이다**

```java
package dev.starryeye.shopagent;

import org.springaicommunity.mcp.security.client.sync.AuthenticationMcpTransportContextProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * SSE 가 아니라 {@code text/plain} 이다. SSE 는 Flux 원소마다 프레임을 붙이는데
     * {@code .content()} 는 토큰 단위로 방출하므로 읽을 수 없는 출력이 된다.
     *
     * <p><b>{@code contextWrite} 를 지우면 안 된다.</b>
     * {@code AuthenticationMcpTransportContextProvider} 는 {@code SecurityContextHolder} 와
     * {@code RequestContextHolder} 의 thread-local 에서 인증 정보를 읽는데, 리액터 체인은
     * 요청 스레드가 아닌 곳에서 실행되므로 그대로면 비어 있다. 그러면 토큰이 붙지 않고
     * MCP 서버가 401 을 주는데, 원인은 DEBUG 로그 한 줄
     * ("No authentication or request context found") 에만 남는다.
     *
     * <p>{@code writeToReactorContext()} 는 <em>호출되는 그 순간의</em> thread-local 을
     * 캡처하므로 반드시 이 메서드 본문(요청 스레드) 안에서 불러야 한다.
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext());
    }
}
```

- [ ] **Step 7: static/index.html 작성**

UI 연습이 아니다. 프레임워크·빌드도구 없이 파일 한 장이다.

```html
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <title>shop-agent</title>
    <style>
        body { font-family: system-ui, sans-serif; max-width: 720px; margin: 40px auto; padding: 0 16px; }
        #q { width: 100%; padding: 8px; font-size: 15px; box-sizing: border-box; }
        button { margin-top: 8px; padding: 8px 16px; font-size: 15px; }
        pre { background: #f4f4f4; padding: 12px; white-space: pre-wrap; min-height: 4em; }
    </style>
</head>
<body>
<h1>shop-agent</h1>
<p>로그인한 사용자를 대신해 MCP 서버의 툴을 호출합니다. 예: <code>노트북 재고 있어?</code></p>
<input id="q" placeholder="질문을 입력하세요" autofocus>
<button id="send">보내기</button>
<pre id="out"></pre>
<script>
    const out = document.getElementById('out');
    const send = document.getElementById('send');
    const q = document.getElementById('q');

    async function ask() {
        const message = q.value.trim();
        if (!message) return;
        send.disabled = true;
        out.textContent = '생각 중... (로컬 모델은 30~100초 걸립니다)';
        try {
            const res = await fetch('/api/chat', {method: 'POST', body: message});
            if (!res.ok) {
                out.textContent = '오류: HTTP ' + res.status;
                return;
            }
            out.textContent = '';
            const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
            for (;;) {
                const {value, done} = await reader.read();
                if (done) break;
                out.textContent += value;
            }
        } catch (e) {
            out.textContent = '오류: ' + e;
        } finally {
            send.disabled = false;
        }
    }

    send.addEventListener('click', ask);
    q.addEventListener('keydown', e => { if (e.key === 'Enter') ask(); });
</script>
</body>
</html>
```

- [ ] **Step 8: 테스트 작성**

`src/test/java/dev/starryeye/shopagent/ShopAgentApplicationTests.java` 를 아래로 교체:

```java
package dev.starryeye.shopagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 실호출은 테스트하지 않는다. 배선만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClientRegistrationRepository clientRegistrationRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void 로그인하지_않으면_로그인으로_보낸다() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection());
	}

	/**
	 * 등록이 정확히 하나여야 transport 커스터마이저가 토큰을 붙인다.
	 * 둘 이상이면 WARN 한 줄 남기고 조용히 아무 것도 하지 않는다.
	 */
	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("shop-agent");
		assertThat(registration.getAuthorizationGrantType().getValue())
				.isEqualTo("authorization_code");
	}
}
```

- [ ] **Step 9: 테스트 실행**

```bash
cd practice/mcp-security-authn/shop-agent && ./gradlew test
```

Expected: 3개 테스트 PASS.

`OAuth2_클라이언트_등록이_정확히_하나다` 가 실패하면 `provider.authserver.issuer-uri` 로
auth-server 메타데이터를 가져오려다 실패한 것일 수 있다 — **이 테스트는 auth-server 가
떠 있어야 통과한다** (`issuer-uri` 기반 provider 는 기동 시 메타데이터를 조회한다).
auth-server 를 먼저 띄우고 다시 실행한다.

- [ ] **Step 10: 커밋**

```bash
git add practice/mcp-security-authn/shop-agent
git commit -m "feat: mcp-security-authn shop-agent — 사용자 토큰으로 MCP 를 호출하는 에이전트"
```

---

## Task 4: 실행 스크립트 + 종단 검증 + README

**Files:**
- Create: `practice/mcp-security-authn/run.sh`
- Create: `practice/mcp-security-authn/stop.sh`
- Create: `practice/mcp-security-authn/.gitignore`
- Create: `practice/mcp-security-authn/README.md`

**Interfaces:**
- Consumes: Task 1·2·3 의 세 앱
- Produces: `./run.sh` 로 3개 앱 기동, `./stop.sh` 로 종료, README 에 **관측된** 검증 결과

- [ ] **Step 1: .gitignore 작성**

```
logs/
```

- [ ] **Step 2: run.sh 작성**

```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

# Java 21 확보 (시스템 기본은 17)
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  fi
fi
echo "JAVA_HOME=${JAVA_HOME:-(미설정)}"

# ollama 준비
if ! curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
  echo "ollama 를 시작합니다..."
  ( nohup ollama serve > logs/ollama.log 2>&1 < /dev/null & disown 2>/dev/null || true )
  for _ in $(seq 1 30); do
    curl -sf http://localhost:11434/api/tags > /dev/null 2>&1 && break
    sleep 1
  done
fi
if ! ollama list 2>/dev/null | grep -q 'qwen3:8b'; then
  echo "qwen3:8b 모델을 내려받습니다 (시간이 걸립니다)..."
  ollama pull qwen3:8b
fi

start() {
  local dir="$1" port="$2"
  if lsof -ti tcp:"$port" > /dev/null 2>&1; then
    echo "  [건너뜀] $dir — 포트 $port 가 이미 사용 중입니다"
    return
  fi
  echo "  [기동] $dir (:$port)"
  # < /dev/null 과 disown 이 없으면 이 스크립트가 호출자를 붙잡는다.
  ( cd "$dir" && nohup ./gradlew bootRun -q > "../logs/$dir.log" 2>&1 < /dev/null & disown 2>/dev/null || true )
}

wait_for() {
  local name="$1" url="$2"
  for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "$url" || [ "$(curl -s -o /dev/null -w '%{http_code}' "$url")" != "000" ]; then
      echo "  [준비됨] $name"
      return 0
    fi
    sleep 1
  done
  echo "  [실패] $name 이 뜨지 않았습니다. logs/$name.log 마지막 20줄:"
  tail -20 "logs/$name.log" || true
  return 1
}

echo "기동 순서: auth-server → shop-mcp-server → shop-agent"
start auth-server 9000
wait_for auth-server http://localhost:9000/.well-known/openid-configuration

start shop-mcp-server 8101
wait_for shop-mcp-server http://localhost:8101/mcp

start shop-agent 8100
wait_for shop-agent http://localhost:8100/

cat <<'EOF'

준비되었습니다.

  브라우저에서 http://localhost:8100/ 을 엽니다.
  로그인: user / password

  토큰 없이 MCP 서버를 직접 찔러보려면:
    curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8101/mcp \
      -H 'Content-Type: application/json' \
      -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
  → 401 이 나와야 합니다.

  종료: ./stop.sh
EOF
```

`chmod +x run.sh` 를 잊지 말 것.

- [ ] **Step 3: stop.sh 작성**

```bash
#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")"

for port in 8100 8101 9000; do
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "포트 $port 종료: $pids"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
  fi
done

if [ "${1:-}" = "--ollama" ]; then
  pkill -f 'ollama serve' 2>/dev/null && echo "ollama 종료" || true
fi

echo "완료."
```

`chmod +x stop.sh` 를 잊지 말 것.

- [ ] **Step 4: 세 앱을 띄운다**

```bash
cd practice/mcp-security-authn && ./run.sh
```

Expected: 세 앱이 모두 `[준비됨]` 으로 찍힌다.

- [ ] **Step 5: 검증 시나리오 1 — 토큰 없이 MCP 직접 호출**

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8101/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expected: `401`

**관측한 값을 그대로 기록한다.** 이것이 `agent-mcps` 와 갈라지는 지점이다 —
거기서는 같은 요청이 툴 목록을 그대로 내줬다.

- [ ] **Step 6: 검증 시나리오 2·3 — 브라우저 로그인 후 질문**

브라우저(또는 브라우저 자동화)로 `http://localhost:8100/` 을 연다.

Expected 2: auth-server(`localhost:9000`)의 로그인 화면으로 이동한다.

`user` / `password` 로 로그인하면 `http://localhost:8100/` 으로 돌아온다.

질문: `노트북 재고 있어?`

Expected 3: 답변에 **실제 재고 숫자**(p1 7개, p2 23개)가 포함된다.
숫자가 없거나 "확인할 수 없다"고 하면 툴이 호출되지 않은 것이다.

품절 케이스도 해본다: `무선 기계식 키보드 살 수 있어?` → 품절(재고 0)이라고 답해야 한다.

- [ ] **Step 7: 검증 시나리오 4 — 서버 로그에서 호출자 확인**

```bash
grep '호출' logs/shop-mcp-server.log
```

Expected: `searchProducts 호출 (keyword=노트북, 사용자=user)` 형태.

**`사용자=user` 가 이 practice 의 결론이다** — MCP 서버에 도착한 신원이 에이전트가 아니라
로그인한 사람이다. `사용자=(인증정보 없음)` 이 찍히면 인증이 전달되지 않은 것이다.

- [ ] **Step 8: 토큰이 안 붙을 때의 진단 (필요할 때만)**

시나리오 3에서 401 이 나거나 툴이 호출되지 않으면 순서대로 확인한다:

```bash
grep -i 'not requesting token' logs/shop-agent.log
```

이 줄이 보이면 `ChatController` 의 `.contextWrite(...)` 가 빠졌거나, `writeToReactorContext()` 가
요청 스레드 밖에서 호출된 것이다.

```bash
grep -i 'client registrations but expected exactly 1' logs/shop-agent.log
```

이 줄이 보이면 `spring.security.oauth2.client.registration.*` 이 2개 이상이다.

```bash
grep -i 'McpOAuth2ClientAutoConfiguration\|transport customizer' logs/shop-agent.log
```

아무것도 안 보이면 `spring.ai.mcp.client.type` 이 `SYNC` 가 아닐 가능성이 높다.

**최후의 수단:** 스트리밍에서 인증 전달이 끝내 안 되면, 진단을 위해 컨트롤러를
비스트리밍으로 잠시 바꿔본다 (`.stream().content()` → `.call().content()`, 반환 타입 `String`).
요청 스레드에서 실행되므로 thread-local 이 그대로 살아 있다. 이때 통한다면 원인은
확실히 리액터 컨텍스트 전달이다. **진단용이며, 최종 코드는 스트리밍으로 되돌린다.**

- [ ] **Step 9: README 작성**

`practice/mcp-security-authn/README.md` 에 아래 내용을 담는다. **Step 5~7 에서 실제로
관측한 값만 적는다. 예상값을 적지 않는다.**

포함할 절:

1. **한 줄 소개** — 이 practice 가 `agent-mcps` 의 어떤 구멍을 메우는지
2. **구성** — 세 앱 표 (프로젝트 / 포트 / 역할 / 사용하는 보안 모듈)
3. **이름** — 왜 `agent-auth` 가 아니라 `mcp-security-authn` 인지 (스펙의 "이름에 대해" 요약)
4. **실행** — `./run.sh`, 브라우저 `http://localhost:8100/`, 로그인 `user`/`password`
5. **검증 결과** — 시나리오 1~4 의 **관측된** 출력. 시나리오 1의 401 은
   `agent-mcps` 의 같은 요청과 나란히 비교해서 보여준다
6. **학습 포인트** — 최소한 아래를 포함하고, 구현 중 실제로 겪은 것을 추가한다:
   - `authorization_code` 토큰은 `sub`=사용자 / `client_id`=에이전트다. MCP 서버 로그의
     `사용자=user` 가 그 증거다
   - 스트리밍에는 `.contextWrite(writeToReactorContext())` 가 필수다. 없으면 토큰이
     조용히 빠지고 DEBUG 로그 한 줄만 남는다
   - `spring.security.oauth2.resourceserver.jwt.issuer-uri` 가 없으면 MCP 서버 보안
     자동설정이 아예 안 뜬다 — 즉 **무방비로 열린다**. Task 2 Step 7 에서 직접 확인한 내용
   - `spring.ai.mcp.client.type` 이 `SYNC` 가 아니면 클라이언트 보안이 통째로 사라진다
   - OAuth2 클라이언트 등록이 2개 이상이면 커스터마이저가 조용히 no-op 이 된다
   - SYNC 서버는 `@McpTool` 반환이 평문 `String` 이다 — 앞 두 practice 와 정반대
   - 세 모듈 모두 `@ConditionalOnDefaultWebSecurity` 계열이라, 직접 `SecurityFilterChain` 을
     만들면 설정이 물러난다 (에이전트는 예외)
   - 감사 검증(`validateAudienceClaim`)은 기본 **꺼져** 있다. 최소 연동에서는 issuer 검증만 한다
7. **트러블슈팅** — Step 8 의 진단 절차를 표로
8. **비목표** — 스코프 검사·툴 단위 인가는 다음 practice(`mcp-security-authz`)

- [ ] **Step 10: 앞 practice 가 안 바뀌었는지 확인**

```bash
git status --short
git diff --stat main..HEAD -- practice/agent-mcp practice/agent-mcps
```

Expected: 두 번째 명령의 출력이 **비어 있어야** 한다. 뭔가 나오면 되돌린다.

- [ ] **Step 11: 커밋**

```bash
git add practice/mcp-security-authn
git commit -m "docs: mcp-security-authn 실행 스크립트와 README — 종단 검증 결과 기록"
```

---

## 완료 조건

- [ ] 세 앱이 `./run.sh` 로 함께 뜬다
- [ ] `curl -X POST localhost:8101/mcp` (토큰 없음) → **401**
- [ ] 브라우저에서 로그인 없이 `localhost:8100` → auth-server 로그인 화면
- [ ] 로그인 후 질문 → 답변에 실제 재고 숫자
- [ ] `logs/shop-mcp-server.log` 에 `사용자=user`
- [ ] 전 프로젝트 `./gradlew test` 통과
- [ ] `practice/agent-mcp`, `practice/agent-mcps` 무변경
- [ ] README 에 관측값(예측값 아님) 기록
