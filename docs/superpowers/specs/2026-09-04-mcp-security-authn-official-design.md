# mcp-security-authn-official — 공식 라이브러리만으로 같은 것을 만든다

작성일: 2026-09-04

## 배경

`practice/mcp-security-authn-community` 는 `org.springaicommunity` 의 MCP 보안 모듈 3종으로
"사용자가 로그인하고 에이전트가 그 사용자를 대신해 보호된 MCP 서버를 호출"하는 것을 구현했다.
그 과정에서 이 모듈들이 **Spring 공식 산출물이 아니라는 것**이 확인됐다.

> "This is a community-driven project and is not officially endorsed by Spring AI or the MCP project."
> — spring-ai-community/mcp-security README

개발자는 한 명(kehrlann, Broadcom Spring Commercial 팀), 버전은 `0.1.14`,
Spring AI 마이너 버전마다 별도 라인을 파야 할 만큼 내부 API 에 밀착해 있다.

이 practice 는 **같은 것을 공식 라이브러리만으로** 만든다. 목적은 둘이다.

1. 그 커뮤니티 모듈이 대신 해주던 일이 **정확히 무엇인지** 드러낸다
2. 그것 없이도 되는지, 된다면 대가가 얼마인지 **실측한다**

## 무엇이 "공식"인가

| 허용 | 이유 |
|---|---|
| `org.springframework.*` (Boot, Security, Authorization Server) | Spring 공식 |
| `org.springframework.ai:*` | Spring 공식 |
| `io.modelcontextprotocol.sdk:*` | **MCP 표준 Java SDK.** Anthropic 의 MCP 명세를 구현한 공식 SDK 이고 Spring 팀과 공동 개발한다. 새로 들이는 게 아니라 `spring-ai-starter-mcp-client` 가 이미 전이 의존으로 끌어온다 — 커뮤니티 모듈을 전부 걷어내도 남는다 |

| 금지 |
|---|
| `org.springaicommunity:*` — 이 practice 의 존재 이유 자체다 |

`org.springframework.ai...internal` 패키지는 회색지대다. 공식 아티팩트이되 "예고 없이 바꾼다"고
표시된 곳이다. **쓰지 않는 것을 목표로 하되, 필요하면 쓰고 그 사실을 기록한다** (아래 위험 항목).

## 대체 관계 — 조사로 확인한 것

| 커뮤니티 모듈이 하던 일 | 공식 대체재 | 상태 |
|---|---|---|
| 인가 서버 (`mcp-authorization-server`) | `spring-boot-starter-oauth2-authorization-server` | 그대로 대체 |
| JWT 검증 + 401 (`mcp-server-security`) | `spring-boot-starter-oauth2-resource-server` | 그대로 대체 |
| RFC 9728 보호 리소스 메타데이터 | **`OAuth2ResourceServerConfigurer.protectedResourceMetadata(...)`** | **Spring Security 7.1 에 이미 있다** |
| 토큰을 MCP 요청에 부착 | `McpSyncHttpClientRequestCustomizer` (MCP SDK) + `OAuth2AuthorizedClientManager` | **직접 작성** |
| 인증을 전송 계층에 전달 | `McpClientCustomizer` (Spring AI) + `transportContextProvider` (MCP SDK) | **직접 작성** |

`protectedResourceMetadata` 가 공식 Spring Security 에 이미 있다는 것이 가장 큰 발견이다.
커뮤니티 모듈의 간판 기능 하나가 이미 상류에 흡수돼 있다는 뜻이고, 이 라이브러리가
임시 정거장 성격이라는 방증이다. (`spring-security-config-7.1.0.jar` 를 `javap` 로 확인)

**직접 쓸 코드는 클래스 2개, 70줄 안팎이다.** 나머지는 설정이다.

> **정정 (Task 4 실측):** 위 추정은 틀렸다. 실제로 직접 쓴 파일은 5개(위 표의 "직접 작성"
> 두 행 각각이 여러 클래스로 나뉘었다 — `SecurityMcpTransportContextProvider`,
> `OAuth2TokenAttachingRequestCustomizer`, `McpSecurityConfig`, 그리고 두 `SecurityConfig`),
> 총 235줄이다. 자세한 내역은 README §4 참고.

## 덤으로 드러나는 대비

| | community | official |
|---|---|---|
| OIDC discovery | 자동설정이 `.oidc()` 를 **안 켠다** → `OidcDiscoveryConfig` 필요 | Boot 자동설정이 **켠다** → 불필요 |
| `SecurityFilterChain` | 직접 정의하면 모듈이 물러난다 (`@ConditionalOnDefaultWebSecurity`) | 내가 전부 쓴다 — 숨은 동작이 없다 |
| 조용히 죽는 스위치 | 5개 | 사실상 없다. 내가 부르지 않으면 안 도는 게 코드에 보인다 |
| 코드량 | 적다 | 많다 (클래스 2개 추가) |

> **정정 (Task 4 실측):** "조용히 죽는 스위치 사실상 없다"는 틀렸다. 실제로 지우고 재현한
> 결과 official 에도 최소 2개가 있다 — `Hooks.enableAutomaticContextPropagation()` 누락과
> `spring.ai.mcp.client.type: ASYNC` 전환. 후자는 조건부 자동설정이 아니라 제네릭 타입
> 매칭 실패로 커스터마이저가 조용히 걸러지는 것이라 원리는 다르지만 증상(DEBUG 로그 한 줄,
> 토큰 미부착)은 같다. 나머지 3개 범주만 구조적으로 대응물이 없다. 자세한 실측은
> README §7·§7.1 참고.

**코드는 늘고 마법은 준다.** 이 교환을 눈으로 보는 것이 이 practice 의 산출물이다.

## 레이아웃

`community` 와 **1:1 대응**시킨다. 같은 이름, 같은 구조, 포트만 다르다.
나란히 놓고 `diff` 를 떠서 볼 수 있어야 한다.

```
practice/mcp-security-authn-official/
├── auth-server/       :9010
├── shop-mcp-server/   :8111
└── shop-agent/        :8110
```

포트는 community(9000/8101/8100)와 겹치지 않는다. **두 practice 를 동시에 띄워
같은 질문을 던지고 결과를 비교할 수 있어야 한다.** 이것이 포트를 나누는 이유다.

시드 상품 10건, 툴 2개(`searchProducts`/`getStock`), 시스템 프롬프트, 사용자 `user`/`password` —
전부 community 와 동일하게 간다. 달라지는 것은 **보안 배선뿐**이어야 비교가 성립한다.

## 컴포넌트

### 1. auth-server (:9010)

의존성: `spring-boot-starter-oauth2-authorization-server`, `spring-boot-starter-web`, `-security`

community 와 거의 같다. 차이는 **`OidcDiscoveryConfig` 가 없다는 것** — 공식 Boot 자동설정
(`OAuth2AuthorizationServerWebSecurityConfiguration`)이 `.oidc(withDefaults())` 를 호출한다.
(해당 jar 를 `javap -c` 로 확인)

`UserDetailsService` 빈 하나와 `application.yml` 의 클라이언트 등록이 전부다.

### 2. shop-mcp-server (:8111)

의존성: `spring-ai-starter-mcp-server-webmvc`, `spring-boot-starter-oauth2-resource-server`, `-security`, `-web`

`SecurityFilterChain` 을 **직접 쓴다**:

```java
http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2ResourceServer(rs -> rs
        .jwt(Customizer.withDefaults())
        .protectedResourceMetadata(Customizer.withDefaults()))   // RFC 9728, 공식
    .csrf(csrf -> csrf.disable())   // 무상태 리소스 서버
```

`issuer-uri` 는 `spring.security.oauth2.resourceserver.jwt.issuer-uri` 로 준다.

**검증 목표:** 토큰 없이 `POST /mcp` → 401. `WWW-Authenticate` 헤더가 어떻게 나오는지
**관측해서 기록한다.** community 는 `Bearer resource_metadata=...` 였다. 공식 구성에서
동일한 챌린지가 나오는지가 이 태스크의 핵심 관측이다 — 나오지 않으면 그 차이를 기록한다.

툴 2개는 community 와 동일 (SYNC, 평문 `String` 반환).

### 3. shop-agent (:8110)

의존성: `spring-ai-starter-mcp-client`, `spring-boot-starter-oauth2-client`, `-security`, `-web`,
`spring-ai-starter-model-ollama`

**여기가 이 practice 의 본론이다.** 직접 쓸 클래스 둘:

**(a) `SecurityMcpTransportContextProvider implements Supplier<McpTransportContext>`**
`SecurityContextHolder` 에서 현재 `Authentication` 을 꺼내 `McpTransportContext` 에 담는다.
`McpClientCustomizer` 로 모든 MCP 클라이언트에 꽂는다.

**(b) `OAuth2TokenAttachingRequestCustomizer implements McpSyncHttpClientRequestCustomizer`**
(a)가 담아둔 `Authentication` 으로 `OAuth2AuthorizedClientManager.authorize()` 를 호출해
액세스 토큰을 얻고 `Authorization: Bearer <token>` 을 붙인다.
`HttpClientStreamableHttpTransport.Builder.httpRequestCustomizer(...)` 로 전송에 꽂는다.

`spring.ai.mcp.client.initialized: false` 는 community 와 동일하게 필요하다 —
부팅 시점에는 대신할 사용자가 없다는 사실은 라이브러리와 무관하다.

## 미확인 위험 — 1번 태스크로 먼저 실측한다

**스트리밍 응답에서 인증이 스레드를 넘어가는가.**

`SecurityContextHolder` 는 thread-local 이고 `ChatClient.stream()` 의 리액터 체인은
요청 스레드 밖에서 돈다. community 는 이것을 `writeToReactorContext()` 로 해결했는데,
그 구현이 `org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder` 를 쓴다.

**공식 경로가 있을 것으로 보인다.** 조사에서 확인한 것:

```
spring-security-core-7.1.0.jar
  org/springframework/security/core/context/SecurityContextHolderThreadLocalAccessor.class
  META-INF/services/io.micrometer.context.ThreadLocalAccessor    ← 자동 등록
```

`io.micrometer:context-propagation` 도 이미 클래스패스에 있다. 따라서 Reactor 의
자동 컨텍스트 전파(`Hooks.enableAutomaticContextPropagation()`)를 켜면 `SecurityContext` 가
스레드를 넘어 따라갈 가능성이 높다 — internal 패키지 없이.

걸림돌: community 의 커스터마이저는 `Authentication` 뿐 아니라 `HttpServletRequest` 도
요구했다(`DefaultOAuth2AuthorizedClientManager` 가 리다이렉트 가능한 흐름을 위해 필요로 함).
`RequestAttributes` 용 공식 accessor 는 찾지 못했다. 우회안:
**`AuthorizedClientServiceOAuth2AuthorizedClientManager`** 를 쓰면 서블릿 요청 없이
`Authentication` 만으로 이미 인가된 클라이언트를 꺼낼 수 있다
(`oauth2Login` 이 `OAuth2AuthorizedClientService` 에 저장하도록 빈을 두면 된다).

**전부 미검증이다.** 이번 조사에서 가정으로 네 번 틀렸으므로 단정하지 않는다.

### 1번 태스크의 형태

구현 순서를 이렇게 잡는다.

1. 공식 경로(`Hooks.enableAutomaticContextPropagation()` +
   `AuthorizedClientServiceOAuth2AuthorizedClientManager`)로 **먼저 시도**한다
2. 되면 internal 패키지 없이 끝낸다
3. 안 되면 `ToolCallReactiveContextHolder` 를 쓰고, **왜 공식만으로는 안 되는지**를
   재현 가능한 형태로 README 에 기록한다

**어느 쪽이 되든 결과가 산출물이다.** 되면 "Spring Security 가 이미 다리를 놔줬다",
안 되면 "공식 표면이 아직 이 지점을 못 덮는다" — 둘 다 이 practice 가 던진 질문의 답이다.
예측을 적지 않고 관측한 것만 적는다.

## 검증 시나리오

community 와 **같은 4가지**를 그대로 수행하고, 결과를 나란히 비교한다.

1. 토큰 없이 `POST :8111/mcp` → 401, `WWW-Authenticate` 헤더 **실제 값 기록**
2. 로그인 없이 브라우저로 `:8110` → auth-server 로그인 화면
3. 로그인 후 `노트북 재고 있어?` → 실제 재고 숫자(p1 7, p2 23), 품절 케이스(p3)
4. `logs/shop-mcp-server.log` → `사용자=user`

추가로 이 practice 고유의 것:

5. **두 practice 를 동시에 띄워 같은 질문을 던지고** 답과 로그를 비교한다
6. 세션 쿠키 이름은 community 와도 겹치면 안 된다 (호스트가 같으므로) —
   6개 앱이 동시에 떠도 로그인이 서로 깨지지 않아야 한다

## 테스트

community 와 동일한 수준을 유지한다.

- **토큰 없이 `/mcp` → 401 + `WWW-Authenticate` 스킴 검증** (가장 중요)
- 툴 2개 등록 검증 (SYNC 가 리액티브 반환을 조용히 거르는 것을 잡는다)
- 툴 단위 테스트
- 에이전트 컨텍스트 로드 + OAuth2 등록 검증
- **직접 쓴 두 클래스의 단위 테스트** — 이건 community 에 없던 것이다.
  라이브러리가 아니라 내 코드이므로 내가 검증해야 한다

LLM 실호출은 테스트하지 않는다.

## 앞 practice 에서 값을 치른 것들

다시 발견하지 않는다. 전부 `community` 에서 실측된 사실이다.

| 항목 | 내용 |
|---|---|
| 툴 배선 | `defaultTools(provider)` 로 직접 꽂아야 한다 |
| 모델 타입 | `spring.ai.model.{audio.*, embedding, image, moderation}: none` |
| 응답 | `text/plain` (SSE 는 토큰마다 `data:` 가 붙어 못 읽는다) |
| ollama | `think: low` |
| 애노테이션 | `org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}` |
| SYNC 반환 | 평문 `String` (`Mono` 는 조용히 등록에서 빠진다) |
| 기동 순서 | auth-server 먼저. JWT 디코더가 issuer 메타데이터를 **즉시** 가져온다 |
| 테스트 전제 | 위 이유로 mcp-server·agent 테스트는 auth-server 가 떠 있어야 한다 |
| MCP 핸드셰이크 | `spring.ai.mcp.client.initialized: false` 없으면 기동이 죽는다 |
| 세션 쿠키 | 앱마다 `server.servlet.session.cookie.name` 을 다르게 (쿠키는 포트를 구분 안 한다) |
| Boot 4.1 | `@AutoConfigureMockMvc` 는 `org.springframework.boot.webmvc.test.autoconfigure`, `spring-boot-webmvc-test` 의존 필요 |
| MockMvc | 401 을 보려면 POST 에 `.with(csrf())` 필요 |
| 스크립트 | 백그라운드는 `< /dev/null` + `disown`, `stop.sh` 는 `sleep` 후 `kill -9` |

## 기존 practice 는 건드리지 않는다

`practice/agent-mcp`, `practice/agent-mcps`, **`practice/mcp-security-authn-community`** 는
한 줄도 수정하지 않는다. 읽고 참고하는 것만 허용한다. 셋 다 종단 검증이 끝나 있다.

예외 하나: 작업 완료 후 **루트 `README.md` 의 practice 목록**에 이 practice 를 추가한다
(현재 `agent-mcp` 하나만 적혀 있어 이미 낡았다). 추가만 하고 기존 항목은 그대로 둔다.

## 비목표

- 스코프 검사, 툴 단위 인가 (후속 `mcp-security-authz`)
- DCR(동적 클라이언트 등록) — 사전 등록 하나로 간다
- 사용자 여러 명, 역할 분리
- UI 완성도 — community 의 `index.html` 과 동일한 최소 페이지
- 커뮤니티 모듈보다 "낫게" 만들기. 목표는 **같은 것을 공식으로** 만들어 차이를 드러내는 것이다

## 참고

- [community 설계](2026-08-25-mcp-security-authn-design.md)
- [community 구현 계획](../plans/2026-08-26-mcp-security-authn.md)
- [spring-ai-community/mcp-security](https://github.com/spring-ai-community/mcp-security)
