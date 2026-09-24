# mcp-security-authn-official

**`org.springaicommunity` 없이, 공식 라이브러리(Spring Security + Spring AI + MCP Java SDK)만으로
[`mcp-security-authn-community`](../mcp-security-authn-community)와 똑같은 것을 만든다.**

사용자가 브라우저로 로그인하고, 에이전트가 그 사용자를 대신해 보호된 MCP 서버를 호출하는 흐름은
community 와 동일하다. 다른 것은 그 흐름을 만드는 재료뿐이다 — 커뮤니티 모듈 3종이 자동으로
해주던 배선과, MCP 인가 표준(발견·PKCE·`resource`·`aud`·`iss`)이 요구하는 배선을 여기서는
31개 Java 파일(`src/main/java` 기준 1,902줄)로 손으로 짠다.

**결론부터: 성공했다.** 세 앱 모두 뜨고, 401 이 나오고, 재고 숫자가 나오고, 로그의 `사용자=user`
도 나온다. `mcp-security-authn-community` 가 이미 밝힌 것들(기동 순서, `initialized: false`,
세션 쿠키 분리) 중 라이브러리와 무관했던 부분은 여기서도 그대로 재현됐고, 라이브러리에 묶여
있던 부분(OIDC discovery, `protectedResourceMetadata`, `WWW-Authenticate` 값, 조용히 죽는
스위치의 개수)은 실제로 달라졌다. 아래 7절이 그 대비표다.

> 인증이 포함된 MCP 스펙 자체를 배우려면 [MCP 인가 표준 문서](../MCP-AUTHORIZATION.md) 를
> 먼저 읽는다. 이 README 는 그 표준을 이 practice 가 어떻게 구현했는지를 다룬다.

## MCP 인가 표준 준수

- **에이전트**는 인가 서버 주소를 설정에 두지 않는다. `shop-agent` 는 MCP 서버에 토큰 없이
  요청해 401 챌린지를 받고, 보호 리소스 메타데이터 → 인가 서버 메타데이터 순으로 발견한다
  (`McpAuthorizationDiscovery`, `DiscoveredClientRegistrationRepository`). `application.yml`
  에는 자격증명과 그 자격증명이 묶인 인가 서버(`mcp.authorization.credentials-issuer`)만
  남는다. 인가 요청에는 PKCE(S256)와 RFC 8707 `resource`(`ResourceIndicators`)가 실리고,
  콜백의 `iss` 는 코드 교환 전에 검증하며(`AuthorizationResponseIssuerFilter`), 토큰 갱신
  요청에도 `resource` 를 싣는다.
- **인가 서버**는 PKCE 를 강제하고, access token 의 `aud` 를 요청한 `resource` 로 발급하며
  (`ResourceAudienceTokenCustomizer`), 모르는 resource 는 `invalid_target` 으로 거부하고
  (`ResourceIndicatorValidator`), 인가 응답(성공·오류)에 `iss` 를 싣고 메타데이터에
  `authorization_response_iss_parameter_supported` 를 광고한다
  (`IssuerIdentifyingAuthorizationResponseHandler`). 필터체인 두 개를 `AuthorizationServerConfig`
  로 직접 정의한다.
- **공개 클라이언트** — 인가 서버에는 에이전트(기밀 클라이언트, `client_secret_basic`) 말고
  비밀이 없는 `local-mcp-client` 가 하나 더 사전 등록되어 있다(`application.yml`). 사용자 기기에서
  도는 MCP 클라이언트를 가정한 등록이다 — 인증 방식 `none`(토큰 요청에 `client_id` 만), PKCE 필수,
  루프백 리다이렉트 `http://127.0.0.1:8123/callback`(포트는 요청마다 달라도 된다, RFC 8252 §7.3),
  매 인가 요청마다 동의 화면(`PublicClientConsentService`, OAuth 2.1 §7.3.1). 메타데이터의
  `token_endpoint_auth_methods_supported` 에 `none` 을 더해 광고한다(Spring 은 넣지 않는다).
  Spring 은 공개 클라이언트에 refresh token 을 발급하지 않는다. 두 부류의 비교는
  [학습 문서 4.4.1](../MCP-AUTHORIZATION.md#s4-4-1).
- **MCP 서버**는 보호 리소스 메타데이터를 경로형(`/.well-known/oauth-protected-resource/mcp`)
  으로 제공하고, 401 챌린지가 그 URL 을 가리키며, 토큰의 `aud` 를 검증하고, `Origin`/`Host`
  (`McpTransportConfig`)와 `MCP-Protocol-Version`(`McpProtocolVersionFilter`)을 검증한다.

## 무엇이 "공식"인가

| 항목 | 허용 / 금지 | 이유 |
|---|---|---|
| `spring-boot-starter-security` / `-oauth2-authorization-server` / `-oauth2-resource-server` / `-oauth2-client` | 허용 | `spring-projects` 소유 |
| `spring-ai-starter-mcp-server-webmvc` / `spring-ai-starter-mcp-client` / `spring-ai-starter-model-ollama` | 허용 | `spring-projects` 소유 (Spring AI 2.0) |
| MCP Java SDK (`io.modelcontextprotocol.sdk`, `io.modelcontextprotocol.client.*`) | 허용 | Spring 소유는 아니지만(Model Context Protocol 표준 SDK), `spring-ai-starter-mcp-*` 가 끌어오는 **전이 의존**이다. 직접 `implementation` 선언을 추가하지 않아도 이미 클래스패스에 있다 — "새로 얹은 서드파티"가 아니라 Spring AI 공식 스타터의 일부로 들어오는 것 |
| `io.micrometer:context-propagation` | 허용 | Micrometer 공식. Spring Security 도 `SecurityContextHolderThreadLocalAccessor` 를 이 라이브러리의 `ThreadLocalAccessor` SPI 로 등록해 둔다 |
| `org.springaicommunity:*` (`mcp-authorization-server-spring-boot` 등 3종) | **금지** | 이 practice 가 대체하려는 대상 그 자체 |

## 대체 관계

| 커뮤니티 모듈 | 자동으로 하던 일 | 공식 대체재 |
|---|---|---|
| `mcp-authorization-server-spring-boot` | 토큰 발급, 로그인 폼, (별도 `OidcDiscoveryConfig` 필요) OIDC discovery | `spring-boot-starter-oauth2-authorization-server` — Boot 자동설정(`OAuth2AuthorizationServerWebSecurityConfiguration`)이 `.oidc(...)` 를 **기본으로** 켠다. 추가 설정 클래스 불필요 |
| `mcp-server-security-spring-boot` | JWT 검증, 401 + `WWW-Authenticate`, RFC 9728 보호 리소스 메타데이터 | `spring-boot-starter-oauth2-resource-server` + 직접 쓴 `SecurityConfig`. **`protectedResourceMetadata(Customizer.withDefaults())` 는 Spring Security 7.1 에 이미 있다** — 커뮤니티 모듈의 간판 기능 하나가 이미 상류로 흡수된 것 |
| `mcp-client-security-spring-boot` | 토큰 획득, 헤더 부착, thread-local → `McpTransportContext` 브리지 | 직접 쓴 `SecurityMcpTransportContextProvider` + `OAuth2TokenAttachingRequestCustomizer` + `McpSecurityConfig`, 그리고 `Hooks.enableAutomaticContextPropagation()` |

## 구성

| 프로젝트 | 포트 | 역할 |
|---|---|---|
| `auth-server` | 9010 | 토큰을 **발급**한다 (`spring-boot-starter-oauth2-authorization-server`) |
| `shop-mcp-server` | 8111 | 토큰을 **검증**한다. 없으면 401 (`spring-boot-starter-oauth2-resource-server`) |
| `shop-agent` | 8110 | 토큰을 **얻어서 붙인다**. 브라우저 UI + Ollama LLM (직접 쓴 배선) |

community 와 포트가 다르다(9000/8101/8100 → 9010/8111/8110) — 두 practice 를 동시에 띄워도
충돌하지 않도록 설계했다. 세 앱 모두 Spring Boot 4.1.0 · servlet 스택, 패키지는
`dev.starryeye.official*`. Spring AI 2.0.0 은 `shop-mcp-server` 와 `shop-agent` 에만
의존성으로 들어간다 — `auth-server` 에는 `resource`(RFC 8707) 파라미터 검증(`McpResourceProperties`,
`ResourceIndicatorValidator`)과 그 배선(`AuthorizationServerConfig`)이 있어 MCP 를 완전히
모르지는 않지만, Spring AI 의존성은 없다(위 build.gradle 참고).

## 직접 쓴 코드

community 에서 라이브러리 3개(전이 의존 포함 수십 개 자동설정 빈)가 하던 일과, MCP 인가
표준(PKCE·`resource`·`aud`·`iss`·전송 보안)이 요구하는 배선을, 여기서는 아래 파일들로
직접 짠다.

`shop-agent` — 발견·인가 흐름·토큰 부착:

| 파일 | 하는 일 |
|---|---|
| `SecurityMcpTransportContextProvider.java` | community 의 `AuthenticationMcpTransportContextProvider`. `SecurityContextHolder` 의 `Authentication` 을 MCP SDK 가 요구하는 `Supplier<McpTransportContext>` 로 옮긴다 |
| `OAuth2TokenAttachingRequestCustomizer.java` | community 의 `OAuth2AuthorizationCodeSyncHttpRequestCustomizer`. 컨텍스트에서 인증을 꺼내 `OAuth2AuthorizedClientManager` 로 토큰을 얻고 `Authorization` 헤더에 붙인다 |
| `McpSecurityConfig.java` | `mcp-client-security-spring-boot` 자동설정이 만들던 빈들을 직접 등록 |
| `SecurityConfig.java` | `oauth2Login` + `oauth2Client` 필터체인 |
| `McpAuthorizationDiscovery.java` | 인가 서버를 발견한다(MCP 인가 §2.3) — 401 → `resource_metadata` → RFC 8414/OIDC 메타데이터 순서 |
| `DiscoveredClientRegistrationRepository.java` | 설정에 인가 서버 주소를 두지 않고, 발견 결과로 `ClientRegistration` 을 만들어 캐시한다 |
| `DiscoveredAuthorization.java` | 발견 결과(리소스 식별자 + 인가 서버 메타데이터)를 담는 레코드 |
| `McpAuthorizationProperties.java` | `mcp.authorization.resource-url`/`credentials-issuer` 설정 바인딩 |
| `McpDiscoveryException.java` | 발견이 명세대로 끝나지 않았을 때 던진다 |
| `ResourceIndicators.java` | RFC 8707 `resource` 를 인가·토큰(갱신 포함) 요청에 싣는다 |
| `AuthorizationResponseIssuerFilter.java` | 콜백의 `iss`(RFC 9207)를 코드 교환 **전에** 검증한다 |
| `LoginFailureHandler.java` | 로그인 실패를 401 본문으로 그대로 알린다 |

`shop-mcp-server` — 보호 리소스·전송 보안:

| 파일 | 하는 일 |
|---|---|
| `SecurityConfig.java` | `mcp-server-security-spring-boot` 자동설정이 만들던 필터체인 전체(`anyRequest().authenticated()` + JWT 리소스 서버 + 경로형 `protectedResourceMetadata`) |
| `McpTransportConfig.java` | 전송 빈을 직접 만들어 `Origin`/`Host` 검증기(SDK 의 `DefaultServerTransportSecurityValidator`)를 단다 |
| `McpProtocolVersionFilter.java` | `MCP-Protocol-Version` 헤더 검증(MCP 전송 명세) — SDK 가 하지 않는 것을 보충한다 |

`auth-server` — MCP 인가 표준 준수(PKCE·`resource`·`aud`·`iss`·공개 클라이언트):

| 파일 | 하는 일 |
|---|---|
| `AuthorizationServerConfig.java` | 필터체인 두 개를 직접 정의해 PKCE 강제(`require-proof-key`)와 아래 확장점을 건다 |
| `ResourceIndicatorValidator.java` | 인가 요청의 `resource` 를 검사해 모르는 리소스면 `invalid_target` 으로 거부한다(RFC 8707 §2.1) |
| `ResourceAudienceTokenCustomizer.java` | access token 의 `aud` 를 요청한 `resource` 로 발급한다(RFC 8707 §2.2) |
| `IssuerIdentifyingAuthorizationResponseHandler.java` | 인가 응답(성공·오류)에 `iss` 를 싣는다(RFC 9207) |
| `McpResourceProperties.java` | 이 인가 서버가 토큰을 내줄 수 있는 보호 리소스(MCP 서버) 목록 |
| `ClientAuthenticationChallengeFailureHandler.java` | `Authorization` 헤더로 인증을 시도했다 실패한 `invalid_client` 에 `WWW-Authenticate` 를 붙인다(RFC 6749 §5.2) |
| `PublicClientConsentService.java` | 공개 클라이언트(`none`)의 동의를 기록하지 않아 매 인가 요청이 동의 화면을 거치게 한다(OAuth 2.1 §7.3.1) |

community 판에 있던 `OidcDiscoveryConfig` 는 official 에는 없다(Boot 자동설정이 `.oidc(...)`
를 기본으로 켜므로 필요 없다).

## 실행

준비물은 community 와 같다.

```bash
brew install ollama
ollama serve &
ollama pull qwen3:8b
```

```bash
cd practice/mcp-security-authn-official
./run.sh
```

`auth-server(:9010) → shop-mcp-server(:8111) → shop-agent(:8110)` 순서로 뜬다. 순서가 강제되는
이유는 community 와 동일(`NimbusJwtDecoder.withIssuerLocation(...).build()` 가 빈 생성 시점에
issuer 메타데이터를 즉시 조회한다) — 8절 학습 포인트 참고.

브라우저에서 `http://localhost:8110/` 을 열고 **`user` / `password`** 로 로그인한다.

공개 클라이언트(`local-mcp-client`) 흐름은 그 클라이언트 프로그램이 없으므로 스크립트로 밟는다.
인가 서버와 MCP 서버만 떠 있으면 되고, 기본값이 official 이다. 동의 화면 → 비밀 없는 토큰 요청 →
MCP 호출, 그리고 PKCE·루프백 포트·재동의 확인까지 P1~P14 단계를 찍는다
([기록](../../docs/superpowers/captures/2026-09-25-official-public-client.txt)).

```bash
../../docs/superpowers/captures/mcp-authorization-public-client.sh
```

```bash
./stop.sh
```

## 검증 결과 (실측)

2026-09-04~05, 세 앱(및 비교용 community 세 앱)을 실제로 띄우고 관측한 값이다. 예측값이 아니다.
브라우저 조작은 `mcp__Claude_Browser__*` (Claude Code 내장 브라우저 자동화)로 수행했다 — curl 로
우회하지 않았다.

### 시나리오 1 — 토큰 없이 MCP 직접 호출

```
$ curl -s -D - -o /dev/null -X POST http://localhost:8111/mcp \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
```

`WWW-Authenticate` 스킴이 `Bearer` — MCP 보안이 작동 중이라는 뜻이다(Boot 기본 보안이면
`Basic` 이 나왔을 것). `resource_metadata` 는 경로형(RFC 9728 §3.1) — `/mcp` 요청은
`/.well-known/oauth-protected-resource/mcp` 를 가리킨다. 값에 따옴표를 붙이는 것과 경로형인
것 둘 다 지금은 community 와 **같다** — MCP 인가 표준 준수 작업(Task 6)에서 community 도
같은 형태로 맞춰졌다(아래 7절 비교표 참고).

보호 리소스 메타데이터는 토큰 없이도 열려 있다:

```
$ curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8111/.well-known/oauth-protected-resource/mcp
200
```

`SecurityConfig` 는 `anyRequest().authenticated()` 하나뿐이고 `permitAll()` 을 어디에도 넣지
않았다. 그런데도 이 경로는 열린다 — `oauth2ResourceServer(...).protectedResourceMetadata(...)`
가 내부적으로 자기 자신을 위한 `permitAll` 매처를 등록하기 때문이다(Task 2 관측).

### 시나리오 2 — 로그인 없이 브라우저로 접근

`http://localhost:8110/` 에 세션 없이 접속하면 즉시 `http://localhost:9010` (auth-server)의
"Please sign in" 화면으로 리다이렉트됐다(브라우저로 직접 확인, 스크린샷상 `Please sign in` /
`Username` / `Password` / `Sign in` 버튼).

### 시나리오 3 — 로그인 후 질문

`user` / `password` 로 로그인 → `shop-agent` 채팅 화면 정상 진입(별도 세션 충돌 없음 —
community 에서 겪었던 쿠키 문제는 애초에 별도 쿠키명(`OFFICIALAUTHSESSIONID` /
`OFFICIALAGENTSESSIONID`)으로 설계했기 때문에 재현되지 않았다).

질문 1: `노트북 재고 있어?` (브라우저 화면 렌더링 그대로)

```
노트북 관련 상품 재고 정보입니다:

1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개
2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개

필요하신 상품이 있으시면 구체적인 상품명이나 ID를 알려주세요.
```

**p1=7개, p2=23개 — 실제 재고 숫자가 정확히 나왔다.**

질문 2 (품절 케이스): `무선 기계식 키보드 살 수 있어?`

```
현재 '무선 기계식 키보드'는 품절된 상태입니다. 재고가 있을 경우 안내드리겠습니다. 다른 상품을 찾아보시겠어요?
```

p3(재고 0)를 정확히 품절로 답했다.

### 시나리오 4 — 서버 로그에서 호출자 확인

```
$ grep '호출' logs/shop-mcp-server.log
searchProducts 호출 (keyword=노트북, 사용자=user)
searchProducts 호출 (keyword=무선 기계식 키보드, 사용자=user)
```

**`사용자=user`.** MCP 서버에 도착한 신원은 에이전트(`client_id=official-shop-agent`)가 아니라
로그인한 사람이다 — 이 practice 의 결론은 community 와 동일하다.

`shop-agent.log` 에도 토큰이 붙는 과정이 그대로 남는다:

```
DEBUG ...SecurityMcpTransportContextProvider    : 전송 컨텍스트에 인증을 담는다: user
DEBUG ...OAuth2TokenAttachingRequestCustomizer  : 토큰을 헤더에 붙였다 (사용자=user)
```

정상 실행에서 `토큰을 헤더에 붙였다` 가 **6회 전부 성공**, `인증 없음`은 **0회**였다
(`grep -c` 로 확인). 스레드 이름을 보면 첫 줄만 서블릿 요청 스레드(`nio-8110-exec-*`)이고
나머지는 MCP SDK 의 HTTP 클라이언트 워커 스레드(`...ient-1-Worker-*`)다 — `SecurityContext` 가
리액터 경계를 넘어 실제로 전파됐다는 뜻이다(8절 · Task 3 Step 10 핵심 결과).

### 시나리오 5 — ★ 두 practice 동시 기동 비교

`mcp-security-authn-community` 도 함께 띄워 **6개 앱을 동시에** 실행했다
(9000/8101/8100 + 9010/8111/8110, 전부 `lsof` 로 LISTEN 확인).

- 브라우저 탭 두 개에 각각 `:8100`(community) / `:8110`(official) 로 로그인 → **서로 깨지지
  않았다.** official 탭을 다시 로드해도 재로그인 없이 그대로 유지됐다. 세션 쿠키 이름이
  4개(`AUTHSERVERSESSIONID`/`SHOPAGENTSESSIONID`/`OFFICIALAUTHSESSIONID`/`OFFICIALAGENTSESSIONID`)
  모두 다르기 때문이다.
- 같은 질문(`노트북 재고 있어?`)을 양쪽에 던진 결과, **답변 내용은 동일했다**(둘 다 p1=7개,
  p2=23개 — 같은 시드 데이터, 같은 모델).
- `WWW-Authenticate` 를 나란히 비교했다(이 시나리오는 2026-09-04~05, MCP 인가 표준 준수
  작업 전 기록이다). 그 시점에는 인용부호·경로 접미사가 서로 달랐으나, 이후 community 의
  401 챌린지도 RFC 9110 문법대로 따옴표를 붙이도록 고쳐져(Task 6) 지금은 둘 다
  `Bearer resource_metadata="http://localhost:<port>/.well-known/oauth-protected-resource/mcp"`
  형태로 **같다**.

확인 후 두 practice 모두 `./stop.sh` 로 종료, 6개 포트 전부 free 확인했다.

## community 와의 대비

| 항목 | community | official |
|---|---|---|
| OIDC discovery | `OidcDiscoveryConfig` 를 직접 만들어 `.oidc(...)` 를 켜야 했다(자동설정이 안 켜줌) | **불필요** — Boot 4.1 자동설정이 기본으로 켠다(Task 1 실측: `userinfo_endpoint`, `end_session_endpoint` 포함 200 응답) |
| `SecurityFilterChain` (리소스 서버) | 라이브러리가 만든다. 직접 정의하면 `@ConditionalOnDefaultWebSecurity` 때문에 라이브러리 설정이 통째로 물러난다 | **내가 전부 쓴다.** 조건부 자동설정 자체가 없으므로 "정의하면 물러나는" 걱정이 없다 |
| `protectedResourceMetadata` (RFC 9728) | 라이브러리(`mcp-server-security-spring-boot`)가 제공 | **Spring Security 7.1 에 이미 있다** — `.protectedResourceMetadata(Customizer.withDefaults())` 한 줄. 커뮤니티 모듈의 간판 기능 하나가 상류로 흡수된 사례 |
| `WWW-Authenticate` 값 | `Bearer resource_metadata="http://localhost:8101/.well-known/oauth-protected-resource/mcp"` | `Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"` — MCP 인가 표준 준수 이후로는 인용부호·경로 접미사 모두 **같다**(위 5절 참고) |
| `issuer-uri` 누락 시 | **조용히 안 죽는다** — `@ConditionalOnProperty` 가 껐을 뿐, `spring-boot-starter-security` 의 기본 보안(HTTP Basic)이 대신 들어와 401 은 여전히 나온다. 상태 코드만 보는 테스트는 이 상황을 놓친다 | **fail-closed** — `SecurityConfig` 는 무조건 실행되는 `@Configuration` 이고 `issuer-uri` 는 `NimbusJwtDecoder` 생성에 쓰인다. 값이 없거나 틀리면 **기동 자체가 실패**한다(Task 2 실측). "조용한 오탐"이 "시끄러운 실패"로 바뀌었다 |
| 조용히 죽는 스위치 | **5개** (client type SYNC 게이트, issuer-uri 게이트, client registration 정확히 1개, 서버·인가서버의 `SecurityFilterChain` 직접 정의 시 자동설정 백오프, `.contextWrite(...)` 누락) | **직접 실측한 것은 2개** — `Hooks.enableAutomaticContextPropagation()` 을 지운 경우와 `type: ASYNC` 로 바꾼 경우, 둘 다 재현했다(아래 상세). 나머지 3개 범주는 애초에 official 구조에 대응물이 없다: 조건부 자동설정을 쓰지 않으므로 "조건이 어긋나 조용히 백오프"할 지점 자체가 없고, issuer-uri 항목은 위 행처럼 오히려 시끄러운 실패로 바뀌었다 |
| 코드량 (메인 소스만, MCP 인가 표준 준수 이후) | 28개 파일, 1,408줄 | 29개 파일, 1,619줄 (그중 보안 배선 전용 20개 파일이 1,335줄) |
| 스트리밍 인증 전파 | `AuthenticationMcpTransportContextProvider.writeToReactorContext()` 를 `ChatController` 에서 `.contextWrite(...)` 로 명시 호출해야 했다(Spring AI 의 `internal` 패키지 의존) | **`Hooks.enableAutomaticContextPropagation()`(부팅 시 1회) + `AuthorizedClientServiceOAuth2AuthorizedClientManager`** 조합이 **첫 시도에서** 통했다. `ChatController` 에는 아무 것도 안 붙였고, `internal` 패키지 클래스는 전혀 참조하지 않는다(Task 3 Step 10 실측) |

### 7.1 "조용히 죽는 스위치" 실측 상세

두 개를 실제로 재현했다. 둘 다 값을 바꾸고 재빌드·재기동·재로그인·재질문까지 실측한 뒤,
커밋 전에 원상복구하고 `git diff` 로 무변경을 확인했다.

#### 실험 1 — `Hooks.enableAutomaticContextPropagation()` 제거

community 의 학습 포인트 5번과 정확히 대응하는 것이 있는지 확인했다
(`ShopAgentApplication.main()`에서 그 한 줄을 주석 처리).

관측:

- **초기 핸드셰이크(`initialize`/`tools/list`)는 그래도 성공했다.** 로그인 직후 발생하는 이
  구간은 아직 요청 스레드(`nio-8110-exec-9`)에서 실행되고, MCP SDK 의 HTTP 워커 스레드
  (`...ient-1-Worker-1`)로 넘어간 뒤에도 토큰이 붙었다(`토큰을 헤더에 붙였다` 4회) — 이 구간은
  아직 리액터 경계를 넘지 않은 것으로 보인다.
- **약 40초 뒤, LLM 이 실제로 `searchProducts` 를 호출하려는 순간 실패했다.** 이 디스패치는
  `boundedElastic` 스레드 풀에서 일어나는데, 거기엔 `SecurityContext` 가 없었다:

  ```
  DEBUG ...SecurityMcpTransportContextProvider   : 인증 없음 — 빈 전송 컨텍스트를 만든다 (토큰이 붙지 않는다)
  DEBUG ...OAuth2TokenAttachingRequestCustomizer : 전송 컨텍스트에 인증이 없다 — 토큰을 붙이지 않는다 (POST http://localhost:8111/mcp)
  ```

- `shop-mcp-server.log` 에는 이 요청이 `AnonymousAuthenticationFilter` 로 처리된 흔적만 남고
  (`Set SecurityContextHolder to anonymous SecurityContext`), `searchProducts 호출` 로그는
  **끝내 찍히지 않았다** — 401 로 막혀 툴 자체가 실행되지 못한 것이다.
- 브라우저에는 예외도, 401도 노출되지 않았다. LLM 이 대신 이렇게 답했다:

  > "현재 노트북 관련 상품의 재고 정보를 확인할 수 없습니다. 시스템 오류로 인해 데이터 접근이
  > 제한되고 있습니다. 잠시 후 다시 확인해 주세요. 사과드립니다."

  — 근거 없는 사과문이다. 로그를 보지 않으면 무엇이 잘못됐는지 전혀 알 수 없다. community 의
  `.contextWrite(...)` 누락 실패(DEBUG 로그 한 줄만 남기고 조용히 실패)와 **증상이 완전히
  같다** — library 유무와 무관하게, thread-local 로 인증을 나르는 설계는 리액터 경계를 넘을 때
  똑같은 방식으로 조용히 실패한다는 뜻이다.
- 흥미로운 세부 사실: 실패 지점이 "MCP 클라이언트 호출 전체"가 아니라 **정확히 LLM 의 툴 실행
  디스패치가 `boundedElastic` 으로 넘어가는 순간**이라는 것까지 실측으로 좁혀졌다. 초기
  핸드셰이크는 같은 리액터 체인 안에서도 성공했기 때문이다.

#### 실험 2 — `spring.ai.mcp.client.type` 을 `ASYNC` 로 전환

리뷰에서 지적된 지점이다. `McpSecurityConfig.mcpAuthenticationCustomizer()` 는
`McpClientCustomizer<McpClient.SyncSpec>` 타입으로 등록된다. Spring AI 의
`McpSyncClientConfigurer` 는 `List<McpClientCustomizer<McpClient.SyncSpec>>` 만 적용하고
`McpAsyncClientConfigurer` 는 `AsyncSpec` 목록만 적용하므로, 제네릭 타입이 안 맞으면 **조건부
자동설정 없이도** 커스터마이저가 조용히 걸러진다 — community 의 "client type SYNC 게이트"와
증상은 같지만 원리는 다르다(자동설정 백오프가 아니라 제네릭 타입 매칭 실패).

`shop-agent/application.yml` 의 `type` 을 `SYNC` → `ASYNC` 로 바꾸고 재기동, 로그로
`AsyncMcpSamplingProvider`/`AsyncMcpElicitationProvider` 가 떠서 실제로 ASYNC 클라이언트가
활성화됐음을 먼저 확인한 뒤, 로그인해서 같은 질문을 던졌다.

관측 — **예상과 다르게, 더 시끄럽게 실패했다:**

- `mcpAuthenticationCustomizer`(SyncSpec 용)가 적용되지 않으므로
  `SecurityMcpTransportContextProvider.get()` 자체가 **한 번도 호출되지 않았다** — 로그에
  `전송 컨텍스트에 인증을 담는다`/`인증 없음` 어느 쪽도 안 찍혔다(둘 다 0회). 대신 전송 계층의
  기본(빈) 컨텍스트가 그대로 쓰였다.
- `mcpTokenAttachingCustomizer`(전송 빌더용, sync/async 무관하게 항상 적용됨)는 정상적으로
  붙었고, 그 안의 `OAuth2TokenAttachingRequestCustomizer` 가 빈 컨텍스트를 받아 딱 한 번
  로그를 남겼다:

  ```
  DEBUG ...OAuth2TokenAttachingRequestCustomizer : 전송 컨텍스트에 인증이 없다 — 토큰을 붙이지 않는다 (POST http://localhost:8111/mcp)
  ```

- 이 요청은 로그인 직후 **첫 MCP 핸드셰이크(`initialize`)** 자체였다(`initialized: false`
  라서 첫 채팅 요청 안에서 일어난다). `shop-mcp-server.log` 는 `AnonymousAuthenticationFilter`
  로 처리된 뒤 응답을 거부했고(`searchProducts 호출` 없음), `shop-agent.log` 에는 MCP SDK 가
  던진 예외가 그대로 잡혔다:

  ```
  ERROR ... Servlet.service() ... threw exception [Request processing failed: java.lang.RuntimeException: Client failed to initialize listing tools]
  Caused by: io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException: Authorization error when sending message
  ```

- **브라우저 화면은 `오류: HTTP 500` 이었다** — 실험 1(Hooks 제거)처럼 LLM 이 그럴싸한
  사과문을 지어내지 않았다. `initialized: false` 때문에 툴 목록 조회 자체가 이 요청 안에서
  실패해 예외가 곧바로 위로 튀어 올랐기 때문이다.

**즉 리뷰가 예상한 증상("LLM 이 답을 지어낸다")과 실제로 다르다** — 근본 원인(전송 컨텍스트
공급자가 조용히 안 붙는 것)은 리뷰의 지적대로 정확했고 DEBUG 로그 한 줄만 남긴다는 점도
같지만, 그 뒤에 이어지는 실패의 "소리 크기"는 실험 1과 다르다. `initialized: false` 조합에서는
초기 핸드셰이크 실패가 예외로 곧장 드러나 HTTP 500 이 되므로, 사용자 입장에서는 오히려 실험 1
(조용한 사과문)보다 **원인을 더 빨리 의심하게 된다** — 화면에 에러가 보이기 때문이다. "조용한
스위치"라는 표현은 로그 수준(DEBUG 한 줄, 예외 없음)에는 맞지만, 사용자에게 보이는 최종
증상까지 조용하다는 뜻은 아니었다.

## 학습 포인트

전부 **실제로 돌려봐야만** 드러난 것들이다.

### 1. `protectedResourceMetadata` 는 이미 공식이다

RFC 9728 보호 리소스 메타데이터는 community 판에서 라이브러리의 간판 기능처럼 보였다. 그런데
Spring Security 7.1 에는 이미 `.protectedResourceMetadata(Customizer.withDefaults())` 로 들어와
있다. 즉 **커뮤니티 모듈이 하던 일 중 하나가 이미 상류로 흡수됐다** — `org.springaicommunity`
가 자체 README 에서도 밝히듯 "공식이 아닌", 그리고 스스로도 말했듯 언젠가 상류에 흡수되거나
방치될 수 있는 **임시 정거장** 성격을 그대로 보여주는 사례다.

### 2. 공식으로 가면 코드는 늘고 마법은 준다

MCP 인가 표준 준수 이전에는 메인 소스가 353줄(11파일) → 522줄(14파일)이었다. 그 시점의
차액 235줄은 전부 보안 배선이었고, 그 배선을 읽으면 "무엇이 왜 켜지는지" 전부 소스에 보인다 —
조건부 자동설정이 없으므로 숨어서 켜지거나 꺼지는 부분이 없다. **어느 쪽이 낫다는 뜻이 아니다.**
커뮤니티 모듈은 그 235줄을 안 써도 되는 대신 그 배선이 하는 일을 신뢰해야 하고, 그 신뢰가
깨지는 지점(조용히 죽는 스위치 5개)을 직접 찾아야 한다. 공식판은 그 배선을 쓰는 대신 조건부
자동설정발 백오프 3개 범주가 통째로 사라지고, 남은 두 개의 진짜 위험 지점 — 리액터 경계에서의
thread-local 전파(실험 1), MCP 클라이언트 제네릭 타입과 커스터마이저 타입의 일치(실험 2) —
이 어디인지 실측으로 정확히 특정할 수 있다. **교환하는 것이 무엇인지가 요점이지, 승자를
가리는 게 아니다.**

이후 MCP 인가 표준(발견·PKCE·`resource`·`aud`·`iss`)을 준수하면서 양쪽 다 다시 커졌다
(공식 14→29파일·522→1,619줄, community 11→28파일·353→1,408줄) — 발견·PKCE·resource·iss
검증은 두 라이브러리 모두 아직 갖추지 못해, 공식이든 community 든 손으로 짜야 했기 때문이다.
이 표준 준수 코드만큼은 "라이브러리냐 공식이냐"의 차이가 거의 사라졌다는 뜻이다(7절 코드량
표 참고).

### 3. 공식 스트리밍 전파 경로가 첫 시도에서 통했다

Task 3 Step 10 의 핵심 결과: `Hooks.enableAutomaticContextPropagation()` +
`AuthorizedClientServiceOAuth2AuthorizedClientManager` 조합이 **대안이나 fallback 없이,
Spring AI 의 `internal` 패키지도 참조하지 않고** 첫 시도에서 성공했다. `ChatController` 에는
`.contextWrite(...)` 류의 코드가 전혀 없다. community 는 같은 문제를
Spring AI `internal` 패키지의 `writeToReactorContext()` 로 풀어야 했다 — 공식 API 만으로 되는
경로가 이미 있었다는 뜻이다. 단, 이 경로의 한계도 실측으로 확인했다(위 "실측 상세" 참고) —
`Hooks` 를 켜지 않으면 초기 핸드셰이크는 성공하지만 실제 툴 호출은 조용히 실패한다.

### 4. community 에서 이미 배운 것은 라이브러리와 무관하게 동일했다

기동 순서(`auth-server → shop-mcp-server → shop-agent`), `spring.ai.mcp.client.initialized:
false` 의 필요성, `localhost` 멀티 앱의 세션 쿠키 이름 분리 — 이 셋은 community 에서 처음
발견됐지만 여기서도 **똑같이 필요했고 똑같이 동작했다**(시나리오 5의 동시 기동 비교가 그
증거다). 즉 이것들은 `org.springaicommunity` 라이브러리의 특성이 아니라, "브라우저 로그인 +
JWT 리소스 서버 + 같은 호스트에 여러 OAuth2 앱"이라는 **문제 자체의 구조**에서 나오는 것이다.
라이브러리를 걷어내도 사라지지 않았다.

## 트러블슈팅

| 증상 | 확인할 곳 |
|---|---|
| `shop-mcp-server` 기동 실패 (`ConnectException`) | `auth-server`(:9010)를 먼저 띄웠는가 |
| `./gradlew test` 가 컨텍스트 로드부터 실패 | 같은 이유. `auth-server` 가 떠 있어야 한다 |
| 401 인데 애초에 서버가 안 뜬다 | `issuer-uri` 설정 확인 — official 은 이 값이 없으면 **기동 자체가 실패**한다(community 처럼 조용히 무방비가 되는 게 아니다) |
| 답변에 재고 숫자가 없고 "시스템 오류"라고 얼버무린다 | `logs/shop-agent.log` 에 `인증 없음` 이 있는지 확인 → `ShopAgentApplication` 의 `Hooks.enableAutomaticContextPropagation()` 누락 의심 |
| 브라우저에 `오류: HTTP 500` 이 뜬다(첫 질문에서) | `logs/shop-agent.log` 에 `McpHttpClientTransportAuthorizationException` 이 있는지 확인 → `application.yml` 의 `spring.ai.mcp.client.type` 이 `SYNC` 인지 확인(7.1절 실험 2) |
| 로그인이 `Invalid credentials` 로 튕김 | 세션 쿠키 이름이 겹쳤는가(community 와 동시 실행 시 특히) |
| 첫 질문이 유난히 느리다(약 100초) | 정상. 첫 요청에 MCP 핸드셰이크가 포함된다 |
| 포트가 이미 사용 중 | `./stop.sh` 후 재실행 |
| 401 의 `resource_metadata` 가 예상과 다른 URL 을 가리킨다 | 경로형(RFC 9728 §3.1)이 맞는지 확인 — `/mcp` 요청은 `/.well-known/oauth-protected-resource/mcp` 를 가리켜야 한다 |

## 비목표

- 스코프 검사, 툴 단위 인가 → 후속 `mcp-security-authz`
- 사용자 여러 명, 역할 분리
- 토큰 저장소 영속화 (in-memory 로 충분)
- 토큰 만료·리프레시 갱신 경로 — 세션이 짧아 실측하지 못했다. `AuthorizedClientServiceOAuth2AuthorizedClientManager` 는 리프레시를 지원하지만 이 practice 에서 만료를 실제로 겪어보지는 않았다
- UI 완성도 — `index.html` 은 OAuth 리다이렉트를 브라우저에 맡기기 위한 최소 장치다
- `client_credentials`, DCR(동적 클라이언트 등록)
- CIMD(Client ID Metadata Document) — `https` 문서 URL 이 전제라 별도 practice 로 미뤘다
- 공개 클라이언트 쪽 프로그램(루프백 콜백 서버, 토큰 보관) — 인가 서버 쪽만 다루고 클라이언트는 캡처 스크립트가 흉내 낸다

## 참고

- [설계 스펙](../../docs/superpowers/specs/2026-09-04-mcp-security-authn-official-design.md)
- [구현 계획](../../docs/superpowers/plans/2026-09-04-mcp-security-authn-official.md)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- 비교 대상: [`mcp-security-authn-community`](../mcp-security-authn-community)
- 앞 practice: [agent-mcp](../agent-mcp) · [agent-mcps](../agent-mcps)
