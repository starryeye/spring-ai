# mcp-security-authn-official 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `practice/mcp-security-authn-community` 와 동일한 것을 `org.springaicommunity` 없이 Spring 공식 + MCP 표준 SDK 만으로 구현해, 그 모듈들이 대신 해주던 일이 정확히 무엇이고 없이는 대가가 얼마인지 실측한다.

**Architecture:** 독립 Spring Boot 앱 3개(`auth-server` :9010, `shop-mcp-server` :8111, `shop-agent` :8110). community 와 1:1 대응시키고 **보안 배선만** 다르게 한다. 인가 서버·리소스 서버는 공식 스타터로 그대로 대체되고, 토큰 부착과 인증 전달만 직접 쓴다(클래스 2개).

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security 7.1.0, Spring Authorization Server, Spring AI 2.0.0, MCP Java SDK 2.0.0, Ollama `qwen3:8b`

**Spec:** [docs/superpowers/specs/2026-09-04-mcp-security-authn-official-design.md](../specs/2026-09-04-mcp-security-authn-official-design.md)

---

## Global Constraints

### 금지 의존성 — 이 practice 의 존재 이유다

**`org.springaicommunity:*` 를 어떤 형태로도 쓰지 않는다.** `build.gradle` 에 나타나면 실패다.

허용:

| 그룹 | 비고 |
|---|---|
| `org.springframework.*` | Boot, Security, Authorization Server |
| `org.springframework.ai:*` | Spring 공식 |
| `io.modelcontextprotocol.sdk:*` | MCP 표준 Java SDK. **새 의존성이 아니다** — `spring-ai-starter-mcp-client` 가 이미 전이 의존으로 끌어온다. 직접 선언할 필요 없이 `import` 만 하면 된다 |

`org.springframework.ai...internal` 패키지는 **회색지대**다. 쓰지 않는 것이 목표이고,
쓰게 되면 왜 불가피했는지를 기록한다 (Task 3 참조).

### 절대 건드리지 않을 것

- `practice/agent-mcp/**`, `practice/agent-mcps/**`, **`practice/mcp-security-authn-community/**`** — 한 줄도 수정 금지. 읽기만 허용.
- `legacy-0.8/**`, 루트 `.gitignore`
- 루트 `settings.gradle` 은 이 저장소에 **없다.** 만들지 않는다. 각 프로젝트가 독립 빌드다.
- `.superpowers/` 는 절대 `git add` 하지 않는다.
- 예외: **Task 4 에서 루트 `README.md` 의 practice 목록에 항목을 추가한다.** 추가만, 기존 항목 유지.

### 버전 고정

- Java 21 (`languageVersion = JavaLanguageVersion.of(21)`). 시스템 기본은 17이라 toolchain 필수.
  **`gradle.properties` 에 JDK 절대경로를 넣지 않는다** — Gradle 자동탐지가 이 머신의 sdkman JDK 를 찾는다(검증됨).
- Spring Boot `4.1.0`, `io.spring.dependency-management` `1.1.7`, Spring AI BOM `2.0.0`
- `group = 'dev.starryeye'`

### 포트 — community 와 동시에 띄운다

| 앱 | official | community (충돌 금지) |
|---|---|---|
| auth-server | **9010** | 9000 |
| shop-mcp-server | **8111** | 8101 |
| shop-agent | **8110** | 8100 |

세션 쿠키 이름도 community 와 겹치면 안 된다 — 쿠키는 호스트만 보고 포트를 구분하지 않는다.
6개 앱이 동시에 떠도 로그인이 서로 깨지지 않아야 한다.

| 앱 | 쿠키 이름 |
|---|---|
| auth-server | `OFFICIALAUTHSESSIONID` |
| shop-agent | `OFFICIALAGENTSESSIONID` |

### community 와 동일하게 유지할 것 — 비교가 성립하려면

시드 상품 10건, 툴 2개(`searchProducts`/`getStock`)와 그 description, 시스템 프롬프트,
사용자 `user`/`password`, `index.html`. **달라지는 것은 보안 배선뿐이어야 한다.**
`practice/mcp-security-authn-community/` 에서 그대로 복사해 오되, 그 폴더를 수정하지는 않는다.

### community 에서 값을 치른 것들 — 다시 발견하지 말 것

| 항목 | 내용 |
|---|---|
| 툴 배선 | `defaultTools(provider)` 로 직접 꽂아야 한다. 자동으로 안 붙는다 |
| 모델 타입 | `spring.ai.model.{audio.speech, audio.transcription, embedding, image, moderation}: none` |
| 응답 | `text/plain;charset=UTF-8` (SSE 는 토큰마다 `data:` 가 붙어 못 읽는다) |
| ollama | `spring.ai.ollama.chat.think: low` |
| 애노테이션 | `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}` |
| SYNC 반환 | `@McpTool` 은 평문 **`String`**. `Mono` 는 조용히 등록에서 빠진다 |
| 기동 순서 | **auth-server 먼저.** JWT 디코더가 issuer 메타데이터를 즉시 가져온다 |
| 테스트 전제 | 위 이유로 mcp-server·agent 테스트는 **auth-server 가 떠 있어야** 통과한다 |
| MCP 핸드셰이크 | `spring.ai.mcp.client.initialized: false` 없으면 기동이 죽는다 (부팅 시점엔 대신할 사용자가 없다) |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure` 패키지. `testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 필요 |
| MockMvc | 401 을 보려면 POST 에 `.with(csrf())` 필요 (없으면 CsrfFilter 가 403) |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`. `stop.sh` 는 `sleep` 후 `kill -9` 로 잔존 프로세스 정리 |
| 테스트 | 한국어 메서드명, AssertJ |

---

## File Structure

```
practice/mcp-security-authn-official/
├── README.md                         # 설명 + 관측된 검증 결과 + community 와의 대비
├── run.sh / stop.sh
├── .gitignore                        # logs/
│
├── auth-server/                      # :9010
│   └── src/main/java/dev/starryeye/officialauthserver/
│       ├── AuthServerApplication.java
│       └── UserConfig.java           # UserDetailsService 만. OidcDiscoveryConfig 는 없다
│   └── src/main/resources/application.yml
│
├── shop-mcp-server/                  # :8111
│   └── src/main/java/dev/starryeye/officialmcpserver/
│       ├── ShopMcpServerApplication.java
│       ├── SecurityConfig.java       # ★ 직접 쓴다 (community 에는 없던 파일)
│       ├── Product.java
│       ├── ProductRepository.java
│       └── ProductTools.java
│   └── src/main/resources/application.yml
│
└── shop-agent/                       # :8110
    └── src/main/java/dev/starryeye/officialagent/
        ├── ShopAgentApplication.java
        ├── SecurityConfig.java
        ├── ChatClientConfig.java
        ├── ChatController.java
        ├── SecurityMcpTransportContextProvider.java   # ★ 직접 쓴다
        ├── OAuth2TokenAttachingRequestCustomizer.java # ★ 직접 쓴다
        └── McpSecurityConfig.java                     # ★ 위 둘을 MCP 클라이언트에 꽂는다
    └── src/main/resources/
        ├── application.yml
        └── static/index.html
```

★ 표시 4개가 커뮤니티 모듈이 대신 해주던 자리다. 이 practice 의 산출물이다.

---

## Task 1: auth-server (:9010)

**Files:**
- Create: `practice/mcp-security-authn-official/auth-server/build.gradle`
- Create: `.../auth-server/src/main/java/dev/starryeye/officialauthserver/AuthServerApplication.java`
- Create: `.../auth-server/src/main/java/dev/starryeye/officialauthserver/UserConfig.java`
- Create: `.../auth-server/src/main/resources/application.yml`
- Test: `.../auth-server/src/test/java/dev/starryeye/officialauthserver/AuthServerApplicationTests.java`

**Interfaces:**
- Consumes: 없음
- Produces: `http://localhost:9010` OAuth2 인가 서버
  - 클라이언트 `official-shop-agent` / `official-shop-agent-secret`
  - redirect URI `http://localhost:8110/login/oauth2/code/authserver`
  - scopes `openid`, `profile`
  - 사용자 `user` / `password`
  - `GET /.well-known/openid-configuration` → 200

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
mkdir -p practice/mcp-security-authn-official && cd practice/mcp-security-authn-official
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=auth-server -d name=auth-server \
  -d packageName=dev.starryeye.officialauthserver -d javaVersion=21 \
  -d dependencies=web \
  -o auth-server.zip && unzip -q auth-server.zip -d auth-server && rm auth-server.zip
```

`bootVersion` 은 반드시 `4.1.0` (`4.1.0.RELEASE` 는 HTTP 500). Initializr 가 만든 `HELP.md` 와
`application.properties` 는 삭제한다.

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

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	// 공식 Spring Authorization Server. community 버전의
	// mcp-authorization-server-spring-boot 를 대체한다.
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-authorization-server'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 9010
  servlet:
    session:
      cookie:
        # 쿠키는 호스트만 보고 포트를 구분하지 않는다. community practice(9000/8100)와
        # 동시에 띄워도 세션이 서로 덮어쓰지 않도록 이름을 분리한다.
        name: OFFICIALAUTHSESSIONID

spring:
  application:
    name: official-auth-server
  security:
    oauth2:
      authorizationserver:
        issuer: http://localhost:9010
        client:
          official-shop-agent:
            registration:
              client-id: official-shop-agent
              client-secret: "{noop}official-shop-agent-secret"
              client-authentication-methods:
                - client_secret_basic
              authorization-grant-types:
                - authorization_code
                - refresh_token
              redirect-uris:
                - http://localhost:8110/login/oauth2/code/authserver
              scopes:
                - openid
                - profile
            require-authorization-consent: false

logging:
  level:
    org.springframework.security: INFO
```

- [ ] **Step 4: UserConfig 작성**

```java
package dev.starryeye.officialauthserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 학습용 사용자 한 명. community 버전과 동일하다.
 *
 * <p>community 버전에는 여기에 {@code OidcDiscoveryConfig} 가 더 있었다.
 * mcp-authorization-server 자동설정이 {@code .oidc(...)} 를 켜주지 않아
 * {@code /.well-known/openid-configuration} 이 404 였기 때문이다.
 * <b>공식 Boot 자동설정({@code OAuth2AuthorizationServerWebSecurityConfiguration})은
 * 그것을 켜주므로 이 practice 에는 그 파일이 없다.</b> 이 차이가 Task 1 의 관측 대상이다.
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

- [ ] **Step 5: 테스트 작성**

`src/test/java/dev/starryeye/officialauthserver/AuthServerApplicationTests.java` 를 아래로 교체:

```java
package dev.starryeye.officialauthserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

	/**
	 * community 버전은 이 엔드포인트를 살리려고 OidcDiscoveryConfig 라는 파일을
	 * 따로 만들어야 했다. 공식 자동설정은 그냥 준다 — 그것을 확인하는 테스트다.
	 */
	@Test
	void OIDC_메타데이터를_추가_설정_없이_공개한다() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value("http://localhost:9010"));
	}

	@Test
	void official_shop_agent_클라이언트가_등록되어_있다() {
		var client = registeredClientRepository.findByClientId("official-shop-agent");

		assertThat(client).isNotNull();
		assertThat(client.getRedirectUris())
				.containsExactly("http://localhost:8110/login/oauth2/code/authserver");
	}

	@Test
	void 로그인_화면이_제공된다() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
	}
}
```

- [ ] **Step 6: 테스트 실행**

```bash
cd practice/mcp-security-authn-official/auth-server && ./gradlew test
```

Expected: 4개 PASS.

**`OIDC_메타데이터를_추가_설정_없이_공개한다` 가 통과하는 것이 이 태스크의 핵심 관측이다** —
community 에서는 별도 설정 클래스가 필요했던 것이 여기서는 공짜다. 결과를 리포트에 기록한다.

만약 이 테스트가 404 로 실패하면 공식 자동설정도 `.oidc()` 를 안 켠다는 뜻이므로,
**추측하지 말고 그 사실을 그대로 보고한다** — 스펙의 대비표가 틀린 것이 된다.

- [ ] **Step 7: 실제 기동 확인**

```bash
cd practice/mcp-security-authn-official/auth-server && ./gradlew bootRun
```

다른 터미널:

```bash
curl -s http://localhost:9010/.well-known/openid-configuration | python3 -m json.tool | head -20
```

확인 후 종료. 서버를 남기지 않는다.

- [ ] **Step 8: 커밋**

```bash
git add practice/mcp-security-authn-official/auth-server
git commit -m "feat: mcp-security-authn-official auth-server — 공식 Authorization Server"
```

---

## Task 2: shop-mcp-server (:8111)

**Files:**
- Create: `.../shop-mcp-server/build.gradle`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/ShopMcpServerApplication.java`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/SecurityConfig.java`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/Product.java`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/ProductRepository.java`
- Create: `.../shop-mcp-server/src/main/java/dev/starryeye/officialmcpserver/ProductTools.java`
- Create: `.../shop-mcp-server/src/main/resources/application.yml`
- Test: `.../shop-mcp-server/src/test/java/dev/starryeye/officialmcpserver/ShopMcpServerApplicationTests.java`
- Test: `.../shop-mcp-server/src/test/java/dev/starryeye/officialmcpserver/ProductToolsTest.java`

**Interfaces:**
- Consumes: Task 1 의 issuer `http://localhost:9010` (**기동 시 실제로 접속한다** — auth-server 를 먼저 띄울 것)
- Produces: `http://localhost:8111/mcp` — 인증 필수 MCP 서버
  - `searchProducts(String keyword) -> String` (`required = false`)
  - `getStock(String productId) -> String` (`required = true`)
  - `ProductRepository.findByKeyword(String) -> List<Product>`, `findById(String) -> Optional<Product>`
  - `Product(String id, String name, String category, int price, int stock)`

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
cd practice/mcp-security-authn-official
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=shop-mcp-server -d name=shop-mcp-server \
  -d packageName=dev.starryeye.officialmcpserver -d javaVersion=21 \
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
	// 공식 리소스 서버. community 버전의 mcp-server-security-spring-boot 를 대체한다.
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
	implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
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

`org.springaicommunity` 가 한 줄도 없어야 한다.

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 8111

spring:
  application:
    name: official-shop-mcp-server
  ai:
    mcp:
      server:
        name: official-shop-mcp-server
        version: 0.0.1
        protocol: STREAMABLE
        # SYNC. @McpTool 이 Mono 를 반환하면 조용히 등록에서 빠진다.
        type: SYNC
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9010

logging:
  level:
    org.springframework.ai: INFO
    org.springframework.security: DEBUG
```

> community 에서는 이 `issuer-uri` 한 줄이 **자동설정을 켜는 스위치**였다(없으면 보안이 아예
> 안 뜸). 여기서는 `SecurityConfig` 를 직접 쓰므로 이 값은 단순히 JWT 디코더가 읽는 설정일 뿐이다.
> **숨은 스위치가 사라진 것** 자체가 관측 대상이다.

- [ ] **Step 4: SecurityConfig 작성 — community 에는 없던 파일**

```java
package dev.starryeye.officialmcpserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * community 버전에는 이 파일이 <b>없었다</b>. mcp-server-security-spring-boot 의
 * 자동설정이 필터체인을 대신 만들어 줬고, 오히려 여기에 이런 빈을 정의하면
 * {@code @ConditionalOnDefaultWebSecurity} 때문에 그 자동설정이 통째로 물러났다.
 *
 * <p>공식 구성에서는 반대다 — 내가 전부 쓴다. 코드는 늘지만 숨은 동작이 없다.
 * 무엇이 켜지는지가 이 메서드 안에 전부 보인다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        // issuer-uri 로 JWK 를 받아 서명·issuer 를 검증한다.
                        .jwt(Customizer.withDefaults())
                        // RFC 9728 보호 리소스 메타데이터.
                        // community 는 이것을 위해 라이브러리를 썼는데,
                        // Spring Security 7.1 에 이미 들어와 있다.
                        .protectedResourceMetadata(Customizer.withDefaults()))
                // 무상태 리소스 서버다. 토큰으로만 인증하므로 CSRF 토큰을 쓰지 않는다.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
```

- [ ] **Step 5: Product / ProductRepository / ProductTools 작성**

`practice/mcp-security-authn-community/shop-mcp-server/src/main/java/dev/starryeye/shopmcpserver/`
의 `Product.java`, `ProductRepository.java`, `ProductTools.java` 를 **그대로 복사**하고
패키지 선언만 `dev.starryeye.officialmcpserver` 로 바꾼다.

**community 폴더는 읽기만 한다. 수정하지 않는다.**

시드 상품 10건과 툴 description 이 동일해야 두 practice 비교가 성립한다.
반환 타입은 평문 `String` 이다 (SYNC).

`ProductTools.currentUser()` 도 그대로 가져온다 — `SecurityContextHolder` 에서 인증 주체를
읽어 로그에 남기는 메서드이고, 검증 시나리오 4가 이 값을 본다.

- [ ] **Step 6: 테스트 작성**

`src/test/java/dev/starryeye/officialmcpserver/ShopMcpServerApplicationTests.java`:

```java
package dev.starryeye.officialmcpserver;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopMcpServerApplicationTests {

	@Autowired
	ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> toolSpecificationLists;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 상태 코드만으로는 "이 리소스 서버 설정이 막은 것"인지 "Boot 기본 Basic 인증이
	 * 대신 막은 것"인지 구분할 수 없다 — 둘 다 401 이다(community 에서 실측했다).
	 * 스킴이 {@code Bearer} 여야 OAuth2 리소스 서버가 실제로 동작한다는 증거가 된다.
	 */
	@Test
	void 토큰_없이_MCP_엔드포인트를_호출하면_401이다() throws Exception {
		mockMvc.perform(post("/mcp")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jsonrpc":"2.0","id":1,"method":"tools/list"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	@Test
	void 아무_경로나_토큰_없이는_거부된다() throws Exception {
		mockMvc.perform(post("/").with(csrf()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
	}

	/**
	 * SYNC 서버는 리액티브 반환 타입을 조용히 걸러낸다.
	 * ProductTools 의 반환을 Mono<String> 으로 바꾸면 이 테스트만 깨진다.
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

- [ ] **Step 7: ProductToolsTest 작성**

`practice/mcp-security-authn-community/shop-mcp-server/src/test/java/dev/starryeye/shopmcpserver/ProductToolsTest.java`
를 그대로 복사하고 패키지 선언만 바꾼다. 6개 테스트다.

- [ ] **Step 8: 테스트 실행 (auth-server 를 먼저 띄운다)**

```bash
cd practice/mcp-security-authn-official/auth-server
nohup ./gradlew bootRun -q > /tmp/oauth.log 2>&1 < /dev/null & disown
# /.well-known/openid-configuration 이 응답할 때까지 대기 후
cd ../shop-mcp-server && ./gradlew test
```

Expected: 10개 PASS.

auth-server 없이 돌리면 `ConnectException` 으로 컨텍스트 로드부터 실패한다 —
JWT 디코더가 issuer 메타데이터를 즉시 가져오기 때문이다(community 에서 실측).

- [ ] **Step 9: 실제 기동해 `WWW-Authenticate` 를 관측한다**

```bash
cd practice/mcp-security-authn-official/shop-mcp-server && ./gradlew bootRun
```

다른 터미널:

```bash
curl -s -D - -o /dev/null -X POST http://localhost:8111/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -i 'www-authenticate'
```

**관측한 값을 그대로 기록한다.** community 는 이랬다:

```
WWW-Authenticate: Bearer resource_metadata=http://localhost:8101/.well-known/oauth-protected-resource/mcp
```

공식 구성에서 `resource_metadata` 파라미터까지 동일하게 나오는지가 이 태스크의 핵심 관측이다.
**다르면 다른 대로 적는다.** 보호 리소스 메타데이터 엔드포인트도 직접 확인한다:

```bash
curl -s http://localhost:8111/.well-known/oauth-protected-resource/mcp | python3 -m json.tool
```

확인 후 두 서버 모두 종료.

- [ ] **Step 10: 커밋**

```bash
git add practice/mcp-security-authn-official/shop-mcp-server
git commit -m "feat: mcp-security-authn-official shop-mcp-server — 공식 리소스 서버로 MCP 보호"
```

---

## Task 3: shop-agent (:8110) — 이 practice 의 본론

커뮤니티 모듈이 대신 해주던 배선을 직접 쓴다.

**Files:**
- Create: `.../shop-agent/build.gradle`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/ShopAgentApplication.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/SecurityConfig.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/SecurityMcpTransportContextProvider.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/OAuth2TokenAttachingRequestCustomizer.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/McpSecurityConfig.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/ChatClientConfig.java`
- Create: `.../shop-agent/src/main/java/dev/starryeye/officialagent/ChatController.java`
- Create: `.../shop-agent/src/main/resources/application.yml`
- Create: `.../shop-agent/src/main/resources/static/index.html`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/ShopAgentApplicationTests.java`
- Test: `.../shop-agent/src/test/java/dev/starryeye/officialagent/SecurityMcpTransportContextProviderTest.java`

**Interfaces:**
- Consumes: Task 1 (`http://localhost:9010`, 클라이언트 `official-shop-agent`), Task 2 (`http://localhost:8111`)
- Produces: `http://localhost:8110/` 브라우저 UI, `POST /api/chat`

### ★ 이 태스크의 미확인 위험 — 다른 코드보다 먼저 실측한다

`SecurityContextHolder` 는 thread-local 이고 `ChatClient.stream()` 의 리액터 체인은
요청 스레드 밖에서 돈다. community 는 이것을 Spring AI 의 **internal 패키지** 클래스
(`ToolCallReactiveContextHolder`)로 해결했다. 이 practice 는 그것을 피하려 한다.

**공식 후보 경로 (미검증):**

- `spring-security-core-7.1.0.jar` 안에 `SecurityContextHolderThreadLocalAccessor` 가 있고
  `META-INF/services/io.micrometer.context.ThreadLocalAccessor` 로 자동 등록된다 (jar 로 확인)
- `io.micrometer:context-propagation` 이 이미 클래스패스에 있다 (확인)
- 따라서 `Hooks.enableAutomaticContextPropagation()` 을 켜면 `SecurityContext` 가
  리액터 경계를 넘어 따라갈 가능성이 있다
- 서블릿 요청(`HttpServletRequest`)이 필요 없도록 **`AuthorizedClientServiceOAuth2AuthorizedClientManager`**
  를 쓴다. 이 매니저는 `ClientRegistrationRepository` + `OAuth2AuthorizedClientService` 만 받고
  `authorize()` 에 `Authentication` 만 있으면 된다 (`javap` 로 시그니처 확인)

**Step 순서를 이렇게 잡는다: 위 경로를 먼저 구현하고 종단으로 확인한 뒤, 안 되면 대안을 쓴다.**
어느 쪽이 되든 결과를 리포트에 기록한다. **예상을 적지 말고 관측을 적는다.**

- [ ] **Step 1: Initializr 로 뼈대 생성**

```bash
cd practice/mcp-security-authn-official
curl -sfL https://start.spring.io/starter.zip \
  -d type=gradle-project -d language=java -d bootVersion=4.1.0 \
  -d groupId=dev.starryeye -d artifactId=shop-agent -d name=shop-agent \
  -d packageName=dev.starryeye.officialagent -d javaVersion=21 \
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
	// HttpClient 기반 MCP 클라이언트. MCP 표준 SDK 를 전이 의존으로 끌어온다 —
	// io.modelcontextprotocol.sdk 의 클래스를 import 하는 데 별도 선언이 필요 없다.
	implementation 'org.springframework.ai:spring-ai-starter-mcp-client'
	implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
	// 리액터 컨텍스트 자동 전파용. Spring Security 가 ThreadLocalAccessor 를
	// ServiceLoader 로 등록해 두었고, 이 라이브러리가 그것을 읽는다.
	implementation 'io.micrometer:context-propagation'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-webmvc-test'
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

`org.springaicommunity` 가 한 줄도 없어야 한다.

- [ ] **Step 3: application.yml 작성**

```yaml
server:
  port: 8110
  servlet:
    session:
      cookie:
        name: OFFICIALAGENTSESSIONID

spring:
  application:
    name: official-shop-agent
  ai:
    model:
      chat: ollama
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
        think: low
    mcp:
      client:
        enabled: true
        type: SYNC
        # 부팅 시점에는 대신할 사용자가 없다. 서버가 모든 요청에 401 을 주므로
        # 기동 중 핸드셰이크가 실패해 컨텍스트가 죽는다. 첫 요청으로 미룬다.
        initialized: false
        streamable-http:
          connections:
            shop:
              url: "http://localhost:8111"
  security:
    oauth2:
      client:
        registration:
          authserver:
            client-id: official-shop-agent
            client-secret: official-shop-agent-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile
        provider:
          authserver:
            issuer-uri: http://localhost:9010

logging:
  level:
    org.springframework.ai: INFO
    dev.starryeye.officialagent: DEBUG
```

- [ ] **Step 4: SecurityMcpTransportContextProvider 작성 — ★ 직접 쓰는 클래스 (1/2)**

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Spring Security 와 MCP 전송 계층 사이의 다리.
 *
 * <p>MCP SDK 는 HTTP 요청을 보내기 직전에 이 {@link Supplier} 를 호출해
 * {@link McpTransportContext} 를 받아간다. 우리는 거기에 현재 인증을 담아
 * {@link OAuth2TokenAttachingRequestCustomizer} 가 꺼내 쓸 수 있게 한다.
 *
 * <p>community 버전에서 {@code AuthenticationMcpTransportContextProvider} 가
 * 하던 일이다. 그쪽은 {@code Authentication} 과 함께
 * {@code RequestAttributes}(서블릿 요청)도 담았는데, 이 practice 는
 * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} 를 쓰므로
 * 서블릿 요청이 필요 없다 — {@code Authentication} 하나면 된다.
 */
public class SecurityMcpTransportContextProvider implements Supplier<McpTransportContext> {

    /** 컨텍스트에 인증을 담을 때 쓰는 키. 커스터마이저가 같은 키로 꺼낸다. */
    public static final String AUTHENTICATION_KEY = Authentication.class.getName();

    private static final Logger log = LoggerFactory.getLogger(SecurityMcpTransportContextProvider.class);

    @Override
    public McpTransportContext get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            // 스트리밍 경로에서 컨텍스트 전파가 안 되면 여기로 떨어진다.
            // 그러면 토큰이 붙지 않고 MCP 서버가 401 을 준다.
            log.debug("인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)");
            return McpTransportContext.EMPTY;
        }

        log.debug("전송 컨텍스트에 인증을 담는다: {}", authentication.getName());
        return McpTransportContext.create(Map.of(AUTHENTICATION_KEY, authentication));
    }
}
```

- [ ] **Step 5: OAuth2TokenAttachingRequestCustomizer 작성 — ★ 직접 쓰는 클래스 (2/2)**

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.net.URI;
import java.net.http.HttpRequest;

/**
 * MCP 로 나가는 모든 HTTP 요청에 로그인한 사용자의 액세스 토큰을 붙인다.
 *
 * <p>community 버전에서 {@code OAuth2AuthorizationCodeSyncHttpRequestCustomizer} 가
 * 하던 일이고, 이 practice 가 직접 쓰는 두 클래스 중 하나다.
 *
 * <p>토큰은 <b>에이전트의 것이 아니라 사용자의 것</b>이다.
 * {@code authorization_code} 로 발급되어 {@code sub} 가 로그인한 사람이다.
 */
public class OAuth2TokenAttachingRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenAttachingRequestCustomizer.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    private final String clientRegistrationId;

    public OAuth2TokenAttachingRequestCustomizer(OAuth2AuthorizedClientManager authorizedClientManager,
                                                 String clientRegistrationId) {
        this.authorizedClientManager = authorizedClientManager;
        this.clientRegistrationId = clientRegistrationId;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
                          McpTransportContext context) {
        Object candidate = context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY);

        if (!(candidate instanceof Authentication authentication)) {
            log.debug("전송 컨텍스트에 인증이 없다 — 토큰을 붙이지 않는다 ({} {})", method, endpoint);
            return;
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(this.clientRegistrationId)
                .principal(authentication)
                .build();

        OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient == null) {
            log.debug("인가된 클라이언트를 찾지 못했다 ({}) — 토큰을 붙이지 않는다", this.clientRegistrationId);
            return;
        }

        builder.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + authorizedClient.getAccessToken().getTokenValue());
        log.debug("토큰을 헤더에 붙였다 (사용자={})", authentication.getName());
    }
}
```

- [ ] **Step 6: McpSecurityConfig 작성 — 위 둘을 MCP 클라이언트에 꽂는다**

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * community 버전에서 {@code mcp-client-security-spring-boot} 의 자동설정이
 * 통째로 해주던 배선이다. 공식 구성에서는 이 파일이 그 역할을 한다.
 */
@Configuration
public class McpSecurityConfig {

    /** application.yml 의 registration 키와 같아야 한다. */
    private static final String REGISTRATION_ID = "authserver";

    /**
     * 인가된 클라이언트를 <b>세션이 아니라 서비스</b>에 저장한다.
     *
     * <p>이 빈이 있으면 {@code oauth2Login} 이 로그인 성공 시 여기에 저장하고,
     * 나중에 {@code Authentication} 만으로 토큰을 꺼낼 수 있다.
     * 서블릿 요청·응답이 필요 없어지므로 리액터 스레드에서도 동작한다 —
     * 스트리밍 응답에서 토큰을 붙이려면 이 성질이 필요하다.
     */
    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} 는
     * {@code HttpServletRequest} 를 요구하지 않는다. community 가 쓰던
     * {@code DefaultOAuth2AuthorizedClientManager} 와 다른 점이고,
     * 그 차이가 이 practice 를 internal API 없이 가능하게 하는 열쇠다.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
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

- [ ] **Step 7: SecurityConfig 와 ShopAgentApplication 작성**

`SecurityConfig.java`:

```java
package dev.starryeye.officialagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .oauth2Client(Customizer.withDefaults())
                // 학습용 단순화. index.html 의 fetch 가 CSRF 토큰을 싣지 않는다.
                // 이 엔드포인트는 상태를 바꾸지 않는 조회성 질의만 받지만,
                // 실제 서비스라면 XSRF-TOKEN 쿠키를 읽어 헤더에 실어야 한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }
}
```

`ShopAgentApplication.java` — 리액터 자동 컨텍스트 전파를 켠다:

```java
package dev.starryeye.officialagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class ShopAgentApplication {

	public static void main(String[] args) {
		// SecurityContextHolder 는 thread-local 이고 ChatClient.stream() 의 리액터
		// 체인은 요청 스레드 밖에서 돈다. 이 훅을 켜면 micrometer context-propagation 이
		// Spring Security 의 SecurityContextHolderThreadLocalAccessor 를 통해
		// SecurityContext 를 리액터 경계 너머로 복원한다.
		// community 버전은 같은 문제를 Spring AI 의 internal 패키지 클래스로 풀었다.
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(ShopAgentApplication.class, args);
	}
}
```

- [ ] **Step 8: ChatClientConfig / ChatController / index.html 작성**

`ChatClientConfig.java` 는 community 의 것을 그대로 쓴다(패키지만 변경).
시스템 프롬프트가 같아야 비교가 성립한다.

`ChatController.java` — **community 와 달리 `.contextWrite(...)` 가 없다:**

```java
package dev.starryeye.officialagent;

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
     * community 버전은 여기에
     * {@code .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext())}
     * 가 있었다. 그 메서드가 Spring AI 의 internal 패키지에 의존한다.
     *
     * <p>이 practice 는 {@code Hooks.enableAutomaticContextPropagation()}(애플리케이션
     * 시작 시)으로 같은 일을 하려 한다 — Spring Security 가 제공하는 공식
     * {@code ThreadLocalAccessor} 를 쓰는 경로다.
     * <b>실제로 통하는지는 Step 10 에서 종단으로 확인한다.</b>
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chat(@RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
```

`static/index.html` 은 community 의 것을 그대로 복사하되 `<title>` 만
`official shop-agent` 로 바꾼다.

- [ ] **Step 9: 테스트 작성**

`SecurityMcpTransportContextProviderTest.java` — 직접 쓴 클래스이므로 직접 검증한다:

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMcpTransportContextProviderTest {

    private final SecurityMcpTransportContextProvider provider = new SecurityMcpTransportContextProvider();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증이_없으면_빈_컨텍스트를_준다() {
        assertThat(provider.get()).isEqualTo(McpTransportContext.EMPTY);
    }

    @Test
    void 인증이_있으면_컨텍스트에_담는다() {
        var authentication = new UsernamePasswordAuthenticationToken("user", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        McpTransportContext context = provider.get();

        assertThat(context).isNotEqualTo(McpTransportContext.EMPTY);
        assertThat(context.get(SecurityMcpTransportContextProvider.AUTHENTICATION_KEY))
                .isSameAs(authentication);
    }
}
```

`ShopAgentApplicationTests.java`:

```java
package dev.starryeye.officialagent;

import io.modelcontextprotocol.client.McpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAgentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClientRegistrationRepository clientRegistrationRepository;

	@Autowired
	OAuth2ClientProperties oAuth2ClientProperties;

	@Autowired
	OAuth2AuthorizedClientManager authorizedClientManager;

	@Autowired
	ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void 로그인하지_않으면_인가_엔드포인트로_보낸다() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/oauth2/authorization/**"));
	}

	@Test
	void OAuth2_클라이언트_등록이_정확히_하나다() {
		var registration = clientRegistrationRepository.findByRegistrationId("authserver");

		assertThat(registration).isNotNull();
		assertThat(registration.getClientId()).isEqualTo("official-shop-agent");
		assertThat(oAuth2ClientProperties.getRegistration()).hasSize(1);
	}

	/**
	 * 서블릿 요청 없이 Authentication 만으로 토큰을 꺼낼 수 있어야
	 * 리액터 스레드에서 토큰을 붙일 수 있다. 매니저 타입이 바뀌면 그 성질이 깨진다.
	 */
	@Test
	void 서블릿_요청이_필요없는_인가_매니저를_쓴다() {
		assertThat(authorizedClientManager)
				.isInstanceOf(org.springframework.security.oauth2.client
						.AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
	}

	@Test
	void MCP_클라이언트에_인증_커스터마이저가_꽂힌다() {
		assertThat(applicationContext.getBeansOfType(
				org.springframework.ai.mcp.customizer.McpClientCustomizer.class))
				.hasSizeGreaterThanOrEqualTo(2);
	}
}
```

- [ ] **Step 10: 테스트 실행 후 ★ 종단으로 컨텍스트 전파를 실측한다**

```bash
# auth-server, shop-mcp-server 를 먼저 띄운 상태에서
cd practice/mcp-security-authn-official/shop-agent && ./gradlew test
```

Expected: 7개 PASS.

그다음 **가장 중요한 확인** — 세 앱을 모두 띄우고 브라우저로 로그인해 질문한다.

```bash
grep -i '토큰을 헤더에 붙였다' logs/shop-agent.log
grep -i '인증 없음' logs/shop-agent.log
```

| 관측 | 의미 | 다음 |
|---|---|---|
| `토큰을 헤더에 붙였다 (사용자=user)` | **공식 경로로 전파가 된다** | 그대로 완료. internal API 없음 |
| `인증 없음 — 빈 전송 컨텍스트` | 전파 실패 | 아래 대안으로 간다 |

**전파가 실패한 경우의 대안** — `ChatController.chat()` 을 아래로 바꾼다:

```java
@PostMapping(value = "/api/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
public Flux<String> chat(@RequestBody String message) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return chatClient.prompt()
            .user(message)
            .stream()
            .content()
            .contextWrite(ctx -> ctx.put(
                    org.springframework.security.core.context.SecurityContext.class,
                    SecurityContextHolder.getContext()));
}
```

이것도 실패하면 community 와 같은 internal 클래스(`ToolCallReactiveContextHolder`)를 쓴다.

**어느 경로를 썼든, 시도한 것과 관측한 것을 전부 리포트에 적는다.**
"공식만으로 되는가"가 이 practice 의 질문이므로, 실패도 답이다. 추측을 적지 않는다.

- [ ] **Step 11: 커밋**

```bash
git add practice/mcp-security-authn-official/shop-agent
git commit -m "feat: mcp-security-authn-official shop-agent — 토큰 부착을 직접 배선"
```

---

## Task 4: 스크립트 · 종단 검증 · 두 practice 비교 · README

**Files:**
- Create: `practice/mcp-security-authn-official/run.sh`
- Create: `practice/mcp-security-authn-official/stop.sh`
- Create: `practice/mcp-security-authn-official/.gitignore`
- Create: `practice/mcp-security-authn-official/README.md`
- Modify: `README.md` (루트 — practice 목록에 **추가만**)

**Interfaces:**
- Consumes: Task 1·2·3
- Produces: `./run.sh` / `./stop.sh`, 관측된 검증 결과가 담긴 README

- [ ] **Step 1: .gitignore 작성**

```
logs/
```

- [ ] **Step 2: run.sh / stop.sh 작성**

`practice/mcp-security-authn-community/run.sh` 와 `stop.sh` 를 복사해 아래만 바꾼다:

- 포트: `9000→9010`, `8101→8111`, `8100→8110`
- 기동 순서는 그대로 `auth-server → shop-mcp-server → shop-agent`
- 안내 문구의 URL 을 `http://localhost:8110/` 로

`< /dev/null` + `disown`, `stop.sh` 의 `sleep` 후 `kill -9` 는 **그대로 유지한다.**
`chmod +x run.sh stop.sh` 를 잊지 않는다.

- [ ] **Step 3: 세 앱 기동**

```bash
cd practice/mcp-security-authn-official && ./run.sh
```

- [ ] **Step 4: 검증 시나리오 1~4 (community 와 동일)**

1. 토큰 없이 `POST :8111/mcp` → 401. **`WWW-Authenticate` 전체 값을 기록**
2. 로그인 없이 브라우저로 `:8110` → auth-server 로그인 화면
3. 로그인(`user`/`password`) 후 `노트북 재고 있어?` → 실제 재고(p1 7, p2 23),
   그리고 `무선 기계식 키보드 살 수 있어?` → 품절(p3)
4. `grep '호출' logs/shop-mcp-server.log` → `사용자=user`

시나리오 3은 **실제 브라우저**로 한다(`mcp__Claude_Browser__*`). curl 로 우회하지 않는다 —
community 에서 브라우저로만 드러난 결함(세션 쿠키 충돌)이 있었다.

- [ ] **Step 5: ★ 두 practice 동시 기동 비교**

```bash
cd ../mcp-security-authn-community && ./run.sh   # 9000/8101/8100
```

6개 앱이 동시에 뜬 상태에서:

- 두 브라우저 탭(`:8100`, `:8110`)에 각각 로그인이 **서로 깨지지 않는지** 확인
  (세션 쿠키 이름이 4개 모두 달라야 한다)
- 같은 질문(`노트북 재고 있어?`)을 양쪽에 던져 답을 비교
- 양쪽 `WWW-Authenticate` 헤더를 나란히 기록

확인 후 두 practice 모두 `./stop.sh`.

- [ ] **Step 6: README 작성**

`practice/mcp-security-authn-community/README.md` 의 구조를 따르되, 이 practice 고유의
내용을 중심에 둔다. **Step 4~5 에서 실제로 관측한 값만 적는다.**

포함할 절:

1. **한 줄 소개** — 공식 라이브러리만으로 같은 것을 만든다. `community` 로 링크
2. **무엇이 "공식"인가** — 허용/금지 표. MCP SDK 가 왜 허용인지(이미 전이 의존)
3. **대체 관계 표** — 커뮤니티 모듈이 하던 일 ↔ 공식 대체재. `protectedResourceMetadata` 가
   이미 Spring Security 7.1 에 있다는 것을 명시
4. **직접 쓴 코드** — `SecurityMcpTransportContextProvider`,
   `OAuth2TokenAttachingRequestCustomizer`, `McpSecurityConfig`, 두 `SecurityConfig`.
   각각 커뮤니티의 무엇을 대체하는지, 실제 줄 수는 얼마인지
5. **실행** — `./run.sh`, `http://localhost:8110/`, `user`/`password`
6. **검증 결과** — 시나리오 1~5 의 관측값
7. **community 와의 대비** — 표로. 최소한:
   - OIDC discovery: community 는 `OidcDiscoveryConfig` 필요 / official 은 불필요
   - `SecurityFilterChain`: community 는 쓰면 모듈이 물러남 / official 은 내가 전부 씀
   - 조용히 죽는 스위치: community 5개 / official 관측된 수
   - 코드량: 실제 파일 수와 줄 수
   - **스트리밍 인증 전파**: Task 3 Step 10 에서 어느 경로가 통했는지
8. **학습 포인트** — 최소한:
   - `protectedResourceMetadata` 는 이미 공식이다 → 커뮤니티 모듈의 간판 기능 하나가
     상류에 흡수됐다는 뜻이고, 이 라이브러리의 임시 정거장 성격을 보여준다
   - 공식으로 가면 **코드는 늘고 마법은 준다.** 어느 쪽이 나은지가 아니라 무엇을 교환하는지가 요점
   - Task 3 Step 10 의 결과(공식 전파 경로가 되는지)
   - community 에서 이미 배운 것(기동 순서, `initialized: false`, 세션 쿠키)은
     **라이브러리와 무관하게 동일했다**는 사실 — 그것들은 라이브러리 탓이 아니었다
9. **트러블슈팅** — community 의 표를 이 practice 포트에 맞춰
10. **비목표** — 스코프·툴 단위 인가는 후속 `mcp-security-authz`

- [ ] **Step 7: 루트 README 에 practice 목록 추가**

루트 `README.md` 의 practice 표에 행을 **추가**한다. 현재 `agent-mcp` 하나만 적혀 있어
이미 낡았으므로, `agent-mcps`, `mcp-security-authn-community`,
`mcp-security-authn-official` 세 개를 추가한다. **기존 행은 그대로 둔다.**

- [ ] **Step 8: 무변경 확인**

```bash
git status --short
git diff --stat main..HEAD -- practice/agent-mcp practice/agent-mcps practice/mcp-security-authn-community
```

두 번째 명령의 출력이 **비어 있어야 한다.**

`org.springaicommunity` 가 새 practice 어디에도 없어야 한다:

```bash
grep -rn 'springaicommunity' practice/mcp-security-authn-official/ || echo "✔ 없음"
```

- [ ] **Step 9: 커밋**

```bash
git add practice/mcp-security-authn-official README.md
git commit -m "docs: mcp-security-authn-official 실행 스크립트와 README — 종단 검증과 community 대비"
```

---

## 완료 조건

- [ ] `practice/mcp-security-authn-official/` 어디에도 `org.springaicommunity` 가 없다
- [ ] 세 앱이 `./run.sh` 로 함께 뜬다
- [ ] 토큰 없이 `POST :8111/mcp` → 401, `WWW-Authenticate` 스킴이 `Bearer`
- [ ] 브라우저 로그인 후 질문 → 실제 재고 숫자
- [ ] `logs/shop-mcp-server.log` 에 `사용자=user`
- [ ] 두 practice 를 동시에 띄워도 로그인이 서로 깨지지 않는다
- [ ] Task 3 Step 10 의 결과(공식 전파 경로 성공 여부)가 README 에 관측값으로 적혀 있다
- [ ] 전 프로젝트 `./gradlew test` 통과 (auth-server 를 띄운 상태에서)
- [ ] `agent-mcp`, `agent-mcps`, `mcp-security-authn-community` 무변경
- [ ] 루트 README 에 practice 4개가 모두 나열된다
