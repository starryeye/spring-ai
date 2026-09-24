# 인증이 포함된 MCP — 표준으로 배우기

이 문서는 인증이 포함된 MCP(Model Context Protocol) 호출을 표준 문서 기준으로 처음부터 끝까지 설명한다.
설명의 기준은 명세 본문이고, 세 practice 에서 실제로 주고받은 요청·응답을 그 옆에 대조용으로 붙인다.

- [`mcp-security-authn-official`](mcp-security-authn-official) — Spring Security · Spring Authorization Server · Spring AI MCP 를 직접 조립
- [`mcp-security-authn-chat-memory`](mcp-security-authn-chat-memory) — official 과 같은 구조에 사용자별 대화 기억을 더한 것
- [`mcp-security-authn-community`](mcp-security-authn-community) — spring-ai-community `mcp-security` 모듈(0.1.14) 자동설정 위에서 구현

## 목차

1. [이 문서의 범위](#s1)
2. [등장인물과 신뢰 관계](#s2)
3. [전체 흐름 한눈에](#s3) — 다이어그램 ①
4. [단계별](#s4)
   - [4.1 토큰 없는 요청과 401 챌린지](#s4-1) — 다이어그램 ②
   - [4.2 보호 리소스 메타데이터 발견](#s4-2)
   - [4.3 인가 서버 메타데이터 발견](#s4-3)
   - [4.4 클라이언트 등록](#s4-4)
   - [4.5 인가 요청 — PKCE 와 resource](#s4-5) — 다이어그램 ③
   - [4.6 콜백과 iss 검증 — mix-up 공격](#s4-6) — 다이어그램 ④
   - [4.7 토큰 요청과 access token 의 구조](#s4-7) — 다이어그램 ⑤
   - [4.8 인증된 MCP 호출](#s4-8) — 다이어그램 ⑥
   - [4.9 MCP 서버의 토큰 검증](#s4-9)
   - [4.10 만료와 refresh](#s4-10) — 다이어그램 ⑦
   - [4.11 오류 응답 모음](#s4-11)
5. [엔드포인트 명세](#s5) — E1~E10
6. [2026-07-28 에서 달라지는 것](#s6) — 다이어그램 ⑧
7. [보안 고려사항](#s7)
8. [준수표](#s8)
9. [이 practice 에서 다루지 않는 것](#s9)
10. [출처](#s10)

---

<a id="s1"></a>

## 1. 이 문서의 범위

### 1.1 기준 리비전

MCP 명세는 날짜로 리비전을 구분한다. 이 문서는 두 리비전을 계층별로 나눠 따른다.

| 계층 | 기준 리비전 | 이유 |
|---|---|---|
| 전송·수명주기 (Streamable HTTP, `initialize`, 세션) | **2025-11-25** | 세 practice 가 쓰는 MCP Java SDK 2.0.0 의 `ProtocolVersions` 는 `2024-11-05` · `2025-03-26` · `2025-06-18` · `2025-11-25` 까지만 안다. 실제로 협상된 `protocolVersion` 도 `2025-11-25` 다([관측] C7). 2026-07-28 전송(stateless)은 SDK 가 구현하지 않는다. |
| 인가 (발견, 클라이언트 등록, PKCE, `resource`, 토큰 검증) | **2025-11-25 + 2026-07-28 추가분** | 인가 규칙은 HTTP 계층에서 동작하므로 전송 리비전과 따로 적용할 수 있다. 2026-07-28 에서 추가된 두 가지 — 인가 응답의 `iss` 검증([Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation), RFC 9207)과 자격증명의 issuer 바인딩([Authorization Server Binding](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#authorization-server-binding)) — 까지 따른다. |

2026-07-28 의 전송·수명주기 변화(`initialize` 제거, 세션 제거, 요청마다 `_meta`)는 [6절](#s6)에서 차이만 설명한다.

### 1.2 표시 규칙

- **[명세]** 표준 문서 본문에서 온 내용이다. 원문 링크를 붙인다. MCP 명세 페이지는 화면에서 절 번호가 자동으로 매겨지므로 **절 제목(앵커 링크)** 으로 가리키고, RFC·OAuth 2.1 초안·OpenID 문서는 **절 번호**로 가리킨다. 짧게 옮긴 문장은 번역이며, 대문자 요구 수준(`MUST`, `SHOULD`, `MAY`)은 원문 그대로 둔다.
- **[관측]** practice 를 실제로 띄워 주고받은 요청·응답이다. 출처 표기는 다음과 같다.
  - `C<n>` — [`docs/superpowers/captures/2026-09-12-<practice>.txt`](../docs/superpowers/captures) 의 n 번 단계. 스크립트는 [`mcp-authorization-walkthrough.sh`](../docs/superpowers/captures/mcp-authorization-walkthrough.sh). practice 를 밝히지 않으면 세 practice 가 포트·client_id·사용자 이름만 다르고 같다는 뜻이다.
  - `S<n>` — [`docs/superpowers/captures/2026-09-16-official-supplement.txt`](../docs/superpowers/captures/2026-09-16-official-supplement.txt) 의 n 번 단계(official 만 관측). 스크립트는 [`mcp-authorization-supplement.sh`](../docs/superpowers/captures/mcp-authorization-supplement.sh).
  - `테스트:` — 캡처에 없고 각 practice 의 테스트로 고정된 사실. `클래스#메서드` 로 적는다.
  - `[관측]` 블록에 실은 요청 줄(`curl`, HTTP 요청 라인 등)은 캡처 파일에 없다. `curl -si` 는 응답(상태줄·헤더·본문)만 남기므로, 요청 내용은 위 캡처 스크립트의 해당 단계 명령에서 그대로 옮긴 것이다.
- **[구현]** 이 practice 가 그 규칙을 어느 클래스·설정으로 지키는지 적는다. 클래스 이름은 [2.5 구현 위치 지도](#s2-5)와 같다.
- 토큰(JWT)은 앞 20자 + `...` 로 줄였다. refresh_token 과 인가 코드는 앞 12자 + `...` 로 줄였다. 응답의 공통 보안 헤더(`X-Content-Type-Options`, `X-XSS-Protection`, `X-Frame-Options`, `Expires`)와 `Date` 는 생략했다.
- 엔드포인트 표([5절](#s5))의 **표시** 열은 원문 표기를 그대로 옮긴다: `REQUIRED` / `RECOMMENDED` / `OPTIONAL`. 원문이 조건을 붙였으면 조건까지 적는다. 원문이 표기 없이 `MUST`·`SHOULD`·`MAY` 문장으로만 규정하면 그 단어를 적고, 요구 수준 표기가 전혀 없으면 "표시 없음"이라고 적는다.
- **이 practice** 열은 `씀`, `씀(조건)` 또는 `이 practice 에서는 쓰지 않음` 이다.

> 캡처 원본(`2026-09-12-*.txt`)에는 access/refresh/id 토큰 원문이 그대로 남아 있다. 모두 5분(access)·30분(id) 수명의 로컬 학습용 토큰이고 인가 서버를 내리면 서명 키도 사라지지만, 원본을 다른 곳에 옮길 때는 줄여서 옮긴다.

---

<a id="s2"></a>

## 2. 등장인물과 신뢰 관계

### 2.1 네 역할

[명세] [MCP 2025-11-25 Authorization — Roles](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#roles):
보호된 MCP 서버는 OAuth 2.1 resource server, MCP 클라이언트는 OAuth 2.1 client 다. 인가 서버는 (필요하면) 사용자와 상호작용하고 MCP 서버에서 쓸 access token 을 발급하며, "resource server 와 같이 둘 수도 있고 별도 개체일 수도 있다". 인가 서버의 내부 구현은 MCP 명세의 범위 밖이다.

| 역할 | OAuth 용어 | 이 practice 의 프로젝트 | 하는 일 |
|---|---|---|---|
| 사용자 브라우저 | user-agent (resource owner 가 조작) | 브라우저 | 에이전트 화면을 열고, 인가 서버에서 로그인하고, 인가 코드가 담긴 리다이렉트를 에이전트로 나른다. 토큰은 보지 않는다. |
| MCP 클라이언트(에이전트) | client — **confidential** ([OAuth 2.1 §2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.1)) | `shop-agent` | 사용자를 대신해 MCP 서버를 부른다. 인가 서버를 발견하고, 인가 코드를 토큰으로 바꾸고, 토큰을 서버 쪽에 보관한다. 토큰 엔드포인트에서 `client_secret_basic` 으로 인증한다. |
| MCP 서버 | resource server (보호 리소스) | `shop-mcp-server` | 보호 리소스 메타데이터를 공개하고, 매 요청의 토큰을 검증하고, 툴(`getStock`, `searchProducts`)을 실행한다. |
| 인가 서버 | authorization server | `auth-server` | 사용자를 로그인시키고 토큰을 발급한다. |

에이전트는 브라우저 안의 앱이 아니라 서버에서 도는 웹 앱이다. 사용자는 브라우저로 에이전트에 말을 걸고, MCP 서버를 부르는 쪽은 에이전트의 백엔드다. 그래서 에이전트는 비밀(client_secret)을 안전하게 가질 수 있는 confidential client 이고, MCP 서버로 가는 요청에는 브라우저의 `Origin` 헤더가 붙지 않는다.

### 2.2 신뢰 관계

| 누가 | 미리 아는 것(설정) | 실행 중에 알아내거나 검증하는 것 |
|---|---|---|
| 에이전트 | MCP 서버 URL(`mcp.authorization.resource-url`), 사전 등록 자격증명(client_id / secret / redirect_uri / scope), 그 자격증명이 등록된 인가 서버(`mcp.authorization.credentials-issuer`) | 인가 서버 위치와 엔드포인트는 **설정에 없다**. MCP 서버에게서 발견한다([4.2](#s4-2), [4.3](#s4-3)). 발견한 issuer 가 `credentials-issuer` 와 다르면 자격증명을 보내지 않는다([4.4](#s4-4)). |
| MCP 서버 | 신뢰할 인가 서버(`issuer-uri`), 자기 리소스 식별자(audience) | 토큰 서명키는 인가 서버 메타데이터의 `jwks_uri` 에서 받는다. 매 요청 서명·`iss`·`aud`·`exp` 를 검증한다([4.9](#s4-9)). |
| 인가 서버 | 등록된 클라이언트(client_id, secret, redirect_uri, grant, scope, PKCE 필수), 토큰을 발급해 줄 리소스 목록(`mcp.authorization.resources`), 사용자 계정 | 인가 요청의 `resource` 가 목록에 있는지, 토큰 요청의 `resource` 가 인가 요청과 같은지 본다([4.5](#s4-5), [4.7](#s4-7)). |
| 브라우저 | 없음 | 인가 서버와 에이전트 각각의 세션 쿠키만 가진다. |

### 2.3 MCP 서버가 인가 서버를 겸하지 않는 이유

[명세] [MCP 2025-03-26 Authorization](https://modelcontextprotocol.io/specification/2025-03-26/basic/authorization) 에서는 인가 서버의 위치가 MCP 서버에 묶여 있었다.

- 클라이언트는 MCP 서버 URL 에서 경로를 버린 "authorization base URL" 에서 `/.well-known/oauth-authorization-server` 를 찾는다(MUST).
- 인가 서버 메타데이터를 지원하지 않는 서버는 그 base URL 기준의 기본 경로 `/authorize`, `/token`, `/register` 를 따라야 한다(MUST).
- 예시 흐름은 "MCP 서버가 인가 서버 역할도 한다고 가정"한다(별도 서비스로 둘 수도 있다고 덧붙인다).

[명세] [MCP 2025-06-18 Key Changes](https://modelcontextprotocol.io/specification/2025-06-18/changelog) 가 이 구조를 바꿨다.

- "MCP 서버를 OAuth Resource Server 로 분류하고, 대응하는 인가 서버를 발견하기 위한 protected resource metadata 를 추가"
- "악성 서버가 access token 을 얻지 못하도록 MCP 클라이언트가 RFC 8707 Resource Indicators 를 구현하도록 요구"

기준 리비전인 2025-11-25 와 2026-07-28 도 같은 구조다. 역할을 나누면 다음이 성립한다.

1. 인가 서버의 위치를 MCP 서버의 origin 에 묶지 않는다. MCP 서버는 보호 리소스 메타데이터로 "나를 지키는 인가 서버는 저기다"라고 알려 줄 뿐이다. 이미 운영 중인 인가 서버(사내 IdP 등)를 여러 MCP 서버가 함께 쓸 수 있다.
2. MCP 서버는 토큰을 **발급하지 않고 검증만** 한다. 서명 키와 클라이언트 비밀, 사용자 비밀번호는 인가 서버에만 있다.
3. 인가 서버 하나를 여러 리소스가 공유하면 "이 토큰은 어느 리소스용인가"를 구분해야 한다. 그래서 같은 리비전에서 `resource` 파라미터(RFC 8707)와 audience 검증이 필수가 되었다([4.7](#s4-7), [4.9](#s4-9)).

세 practice 는 인가 서버·MCP 서버·에이전트를 서로 다른 프로세스(포트)로 띄운다.

<a id="s2-4"></a>

### 2.4 practice 별 포트·계정

| | official | chat-memory | community |
|---|---|---|---|
| 인가 서버(issuer) | `http://localhost:9010` | `http://localhost:9020` | `http://localhost:9000` |
| MCP 서버 리소스 식별자 | `http://localhost:8111/mcp` | `http://localhost:8131/mcp` | `http://localhost:8101/mcp` |
| 에이전트 | `http://localhost:8110` | `http://localhost:8130` | `http://localhost:8100` |
| client_id | `official-shop-agent` | `memory-agent` | `shop-agent` |
| redirect_uri | `http://localhost:8110/login/oauth2/code/authserver` | `http://localhost:8130/login/oauth2/code/authserver` | `http://localhost:8100/login/oauth2/code/authserver` |
| 로그인 계정 | `user` / `password` | `alice` / `alice`, `bob` / `bob` | `user` / `password` |
| 구성 방식 | 필터체인·빈을 직접 정의 | official 과 같은 클래스 구성 | 모듈 자동설정 + 확장점, 막히는 곳만 직접 정의 |

<a id="s2-5"></a>

### 2.5 구현 위치 지도

chat-memory 의 인가 서버·MCP 서버 클래스는 official 과 패키지만 다르고 내용이 같다(`dev.starryeye.memoryauthn.*`). 에이전트도 같은 이름의 클래스로 같은 일을 한다. 이후 절의 **[구현]** 은 이 표의 클래스 이름으로 가리킨다.

| 관심사 | official · chat-memory | community |
|---|---|---|
| 보호 리소스 메타데이터 공개 | `shop-mcp-server` `SecurityConfig` — `oauth2ResourceServer().protectedResourceMetadata(...)` | `shop-mcp-server` `SecurityConfig` — 모듈 `McpServerOAuth2Configurer#protectedResourceMetadataCustomizer` |
| 401 챌린지의 `resource_metadata` | `SecurityConfig#resourceMetadataEntryPoint` (Spring `BearerTokenAuthenticationEntryPoint`) | `SecurityConfig#resourceMetadataEntryPoint` — 모듈 진입점 대신 Spring `BearerTokenAuthenticationEntryPoint` 를 건다 |
| 토큰 검증(서명·`iss`·`aud`·`exp`) | `application.yml` 의 `spring.security.oauth2.resourceserver.jwt.issuer-uri` · `audiences` | `SecurityConfig` — Boot 가 `issuer-uri` 로 만든 `JwtDecoder` + 모듈 `validateAudienceClaim(true)`(`AudienceValidationJwtDecoder` 가 요청 URL 로 계산한 `http://localhost:8101/mcp` 를 `aud` 에서 찾는다) |
| `Origin`·`Host` 검증 | `McpTransportConfig` — SDK `DefaultServerTransportSecurityValidator`(허용 Origin 없음, 허용 Host `localhost:<port>`·`127.0.0.1:<port>`) | `SecurityConfig` — 모듈 `allowedOrigins`(`http://localhost:8101`) · `allowedHosts` → 모듈 `OriginValidationFilter`(내부에서 같은 SDK 검증기 사용) |
| `MCP-Protocol-Version` 검증 | `McpProtocolVersionFilter` + `McpTransportConfig#mcpProtocolVersionFilter` | `McpProtocolVersionFilter` + `McpProtocolVersionFilterConfig` |
| 인가 서버 설정 진입점 | `AuthorizationServerConfig` (필터체인 직접 정의) | `McpAuthorizationStandardConfig` (`Customizer<McpAuthorizationServerConfigurer>`), `OidcDiscoveryConfig` |
| PKCE 강제 | `auth-server` `application.yml` — `require-proof-key: true` | 같음 |
| `resource` 허용 목록과 인가 요청 검증 | `McpResourceProperties`, `ResourceIndicatorValidator` | 같음 |
| access token `aud` 발급과 토큰 요청 `resource` 검증 | `ResourceAudienceTokenCustomizer` | `ResourceAudienceTokenCustomizer` — 모듈 기본 커스터마이저가 id_token `aud` 에 넣은 resource 를 client_id 로 되돌리는 분기 포함 |
| 인가 응답의 `iss`, 메타데이터 광고(`iss` 지원 여부 + 클라이언트 인증 서명 알고리즘 세 claim) | `IssuerIdentifyingAuthorizationResponseHandler` + `AuthorizationServerConfig` | `IssuerIdentifyingAuthorizationResponseHandler` + `McpAuthorizationStandardConfig` |
| 클라이언트 인증 실패의 `WWW-Authenticate` 챌린지 | `ClientAuthenticationChallengeFailureHandler` + `AuthorizationServerConfig#clientAuthentication` | `ClientAuthenticationChallengeFailureHandler` + `McpAuthorizationStandardConfig#clientAuthentication`(모듈 확장점) |
| 동적 클라이언트 등록(DCR) | 켜지 않음 — Spring Authorization Server 기본값. 메타데이터에 `registration_endpoint` 가 없다(C3) | 끔 — `application.yml` 의 `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` |
| 보호 리소스·인가 서버 발견 | `McpAuthorizationDiscovery`, `DiscoveredAuthorization` | `McpAuthorizationDiscovery`(인가 서버 메타데이터) + 모듈 `McpMetadataDiscoveryService`(401 챌린지와 보호 리소스 메타데이터) |
| 사전 등록 자격증명과 issuer 바인딩 | `DiscoveredClientRegistrationRepository`, `McpAuthorizationProperties` | 같음 |
| 인가 요청의 PKCE·`resource` | `SecurityConfig#authorizationRequestResolver`, `ResourceIndicators` | 같음 |
| 토큰·갱신 요청의 `resource` | `McpSecurityConfig` — `RestClientAuthorizationCodeTokenResponseClient`, `RestClientRefreshTokenTokenResponseClient` + `ResourceIndicators` | 같음 |
| 인가 응답 `iss` 검증 | `AuthorizationResponseIssuerFilter` (+ `LoginFailureHandler`) | 같음 |
| MCP 요청에 토큰 부착 | `OAuth2TokenAttachingRequestCustomizer`, `SecurityMcpTransportContextProvider` | 모듈 `HttpClientStreamableHttpTransportAutoConfiguration#preRegisteredClientCustomizer`(→ `OAuth2AuthorizationCodeSyncHttpRequestCustomizer`), `ChatController` 의 `AuthenticationMcpTransportContextProvider.writeToReactorContext()` |
| 만료 토큰 갱신 | `McpSecurityConfig#authorizedClientManager` (`AuthorizedClientServiceOAuth2AuthorizedClientManager`) | `McpSecurityConfig#authorizedClientManager` (`DefaultOAuth2AuthorizedClientManager`) |
| MCP 전송(클라이언트) | MCP Java SDK `HttpClientStreamableHttpTransport` — `Accept`·`Mcp-Session-Id`·`MCP-Protocol-Version` 헤더를 붙인다 | 같음 |

---

<a id="s3"></a>

## 3. 전체 흐름 한눈에

다이어그램 ① — 로그인부터 첫 툴 호출까지. 에이전트는 로그인하지 않은 사용자를 곧바로 인가 요청으로 보내고, 인가 서버의 위치는 그 첫 인가 요청을 만들 때 발견한다.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    B->>C: GET / (로그인 전)
    C-->>B: 302 /oauth2/authorization/authserver
    B->>C: GET /oauth2/authorization/authserver
    opt 첫 요청일 때만 발견한다 (성공한 결과는 캐시)
        C->>M: POST /mcp (토큰 없음)
        M-->>C: 401 WWW-Authenticate: Bearer resource_metadata="..."
        C->>M: GET /.well-known/oauth-protected-resource/mcp
        M-->>C: 200 resource, authorization_servers
        C->>A: GET /.well-known/oauth-authorization-server
        A-->>C: 200 issuer, 엔드포인트, code_challenge_methods_supported
        Note over C: resource 일치, issuer 일치, S256 지원,<br/>자격증명의 issuer 일치를 확인
    end
    Note over C: code_verifier, state, nonce 를 만들어<br/>세션의 인가 요청 기록에 저장
    C-->>B: 302 인가 엔드포인트 (code_challenge, resource, state, nonce)
    B->>A: GET /oauth2/authorize
    A-->>B: 302 /login (아직 로그인하지 않음)
    B->>A: GET /login 후 POST /login (계정 입력)
    A-->>B: 302 /oauth2/authorize (저장해 둔 인가 요청)
    B->>A: GET /oauth2/authorize
    A-->>B: 302 redirect_uri?code=...&state=...&iss=...
    B->>C: GET /login/oauth2/code/authserver?code&state&iss
    Note over C: state 로 저장된 인가 요청을 찾고<br/>iss 를 검증한 뒤에야 코드를 교환
    C->>A: POST /oauth2/token (Basic 인증, code, code_verifier, resource)
    A-->>C: 200 access_token(aud=MCP 서버), refresh_token, id_token
    C-->>B: 302 /
    B->>C: POST /api/chat
    C->>M: POST /mcp initialize (Authorization: Bearer)
    M-->>C: 200 InitializeResult + Mcp-Session-Id
    C->>M: notifications/initialized, tools/list, tools/call (매 요청 Bearer)
    M-->>C: 202, 결과
    C-->>B: 답변
```

| 단계(번호) | 무엇을 하나 | 설명 | 엔드포인트 명세 |
|---|---|---|---|
| 1–3 | 로그인하지 않은 사용자를 인가 요청으로 보낸다 | [4.5](#s4-5) | — |
| 4–5 | 토큰 없는 요청과 401 챌린지 | [4.1](#s4-1) | [E1](#e1) |
| 6–7 | 보호 리소스 메타데이터 | [4.2](#s4-2) | [E2](#e2) |
| 8–9 | 인가 서버 메타데이터 | [4.3](#s4-3) | [E3](#e3), [E4](#e4) |
| (설정) | 사전 등록 자격증명 | [4.4](#s4-4) | — |
| 10–15 | 인가 요청과 로그인 | [4.5](#s4-5) | [E5](#e5) |
| 16–17 | 인가 응답(리다이렉트)과 `iss` 검증 | [4.6](#s4-6) | [E6](#e6) |
| 18–20 | 토큰 요청 | [4.7](#s4-7) | [E7](#e7) |
| 21–26 | 인증된 MCP 호출과 서버의 토큰 검증 | [4.8](#s4-8), [4.9](#s4-9) | [E9](#e9), [E10](#e10) |
| (5분 뒤) | 만료와 갱신 | [4.10](#s4-10) | [E8](#e8) |

에이전트의 MCP 클라이언트는 기동 시점에 `initialize` 를 하지 않는다(`spring.ai.mcp.client.initialized: false`). 기동 시점에는 대신 호출해 줄 사용자가 없어 MCP 서버가 401 을 주기 때문이다. 핸드셰이크는 로그인한 사용자의 첫 채팅 요청을 처리하는 도중에 일어난다.

---

<a id="s4"></a>

## 4. 단계별

각 절은 같은 순서로 쓴다 — **[명세]** 무엇이 요구되는가 → **[관측]** 실제로 무엇이 오갔는가 → **[구현]** 어디서 지키는가. 요청·응답 필드 전체 목록은 [5절 엔드포인트 명세](#s5)에 모았고, 여기서는 흐름을 이해하는 데 필요한 것만 다룬다.

<a id="s4-1"></a>

### 4.1 토큰 없는 요청과 401 챌린지

MCP 클라이언트가 처음 아는 것은 MCP 서버의 URL 하나다. 토큰 없이 그 URL 을 부르면 서버가 "나는 보호되어 있고, 나에 대한 설명서는 저기 있다"고 답한다. 이 답이 발견의 출발점이다.

**[명세]**

- [MCP 2025-11-25 Authorization — Protected Resource Metadata Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)
  - MCP 서버는 다음 둘 중 하나를 **MUST** 구현한다. (1) `401 Unauthorized` 응답의 `WWW-Authenticate` 헤더에 `resource_metadata` 로 메타데이터 URL 을 싣는다([RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1)). (2) well-known URI 에 메타데이터를 둔다 — MCP 엔드포인트 경로를 붙인 경로형 또는 루트형.
  - MCP 클라이언트는 두 방식을 모두 지원해야 하고(**MUST**), 헤더에 URL 이 있으면 그것을 쓰고, 없으면 경로형 → 루트형 순서로 well-known URI 를 만들어 요청해야 한다(**MUST**).
  - MCP 서버는 필요한 scope 를 알리는 `scope` 파라미터를 챌린지에 넣는 것이 좋다(**SHOULD**, [RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)). `scope` 가 없으면 클라이언트는 [Scope Selection Strategy](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-selection-strategy) 를 따른다(**SHOULD**) — 보호 리소스 메타데이터의 `scopes_supported` 전체를 요청하고, 그것도 없으면 `scope` 파라미터를 생략한다.
  - MCP 클라이언트는 `WWW-Authenticate` 를 해석하고 401 에 알맞게 대응할 수 있어야 한다(**MUST**).
- [RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3), [OAuth 2.1 §5.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.3.1)
  - 요청에 인증 정보가 없거나 접근을 허용하는 토큰이 없으면 리소스 서버는 `WWW-Authenticate` 를 **MUST** 포함한다. 스킴은 `Bearer` 이고 하나 이상의 auth-param 이 뒤따른다.
  - `realm` 은 MAY, `scope` 는 OPTIONAL 이다.
  - 요청에 인증 정보가 아예 없었다면 `error` 등 오류 정보를 넣지 않는 것이 좋다(**SHOULD NOT**, [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1)).
- [RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2) — `auth-param = token BWS "=" BWS ( token / quoted-string )`. `token` 에는 구분자(`"(),/:;<=>?@[\]{}`)가 들어갈 수 없으므로([§5.6.2](https://www.rfc-editor.org/rfc/rfc9110#section-5.6.2)) `:` 와 `/` 를 담은 URL 값은 `quoted-string`(큰따옴표)으로 보내야 한다.

**다이어그램 ②** — 401 챌린지와 발견의 세 갈래. 이 practice 의 MCP 서버는 헤더에 URL 을 싣기 때문에 에이전트는 항상 첫 갈래를 탄다. 나머지 갈래는 에이전트 코드와 테스트에 구현되어 있다.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    B->>C: GET /oauth2/authorization/authserver
    Note over C: 캐시된 발견 결과가 없다
    C->>M: POST /mcp (Authorization 헤더 없음)
    M-->>C: 401 WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
    alt 헤더에 resource_metadata 가 있다 (이 practice 의 경로)
        C->>M: GET resource_metadata 가 가리키는 URL
        M-->>C: 200 보호 리소스 메타데이터
        Note over C: resource 가 요청한 URL http://localhost:8111/mcp 와 같은지 확인
    else 헤더에 resource_metadata 가 없다
        C->>M: GET /.well-known/oauth-protected-resource/mcp (경로형)
        alt 경로형 메타데이터가 있다
            M-->>C: 200 보호 리소스 메타데이터
            Note over C: resource 가 http://localhost:8111/mcp 인지 확인
        else 경로형이 없다
            M-->>C: 404
            C->>M: GET /.well-known/oauth-protected-resource (루트형)
            alt 루트형 메타데이터가 있다
                M-->>C: 200 보호 리소스 메타데이터
                Note over C: resource 가 http://localhost:8111 인지 확인
            else 루트형도 없다
                M-->>C: 404
                Note over C: 발견 실패. 인가 요청을 만들지 않는다
            end
        end
    end
    Note over C: authorization_servers 의 첫 값을 issuer 로 고른다
    C->>A: GET /.well-known/oauth-authorization-server (RFC 8414)
    alt RFC 8414 메타데이터가 있다 (이 practice 의 경로)
        A-->>C: 200 인가 서버 메타데이터
    else 없다
        A-->>C: 404
        C->>A: GET /.well-known/openid-configuration (OIDC Discovery)
        A-->>C: 200 OpenID Provider 메타데이터
    end
    Note over C: issuer 일치, code_challenge_methods_supported 에 S256,<br/>credentials-issuer 일치를 확인
```

**[관측]** C1 — 세 practice 모두 같은 형태다(포트만 다르다).

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",...}}
```

```http
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Length: 0
```

| practice | 관측한 `WWW-Authenticate` (C1) |
|---|---|
| official | `Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"` |
| chat-memory | `Bearer resource_metadata="http://localhost:8131/.well-known/oauth-protected-resource/mcp"` |
| community | `Bearer resource_metadata="http://localhost:8101/.well-known/oauth-protected-resource/mcp"` |

명세와 대조한 결과:

- 값이 큰따옴표로 감싸져 있다 — RFC 9110 auth-param 문법에 맞다.
- 인증 정보가 없는 요청이므로 `error` 가 없다 — RFC 6750 §3.1 의 SHOULD NOT 에 맞다.
- `realm` 은 없다(MAY).
- `scope` 가 없다 — MCP 의 SHOULD 를 따르지 않는다. 이 practice 들은 scope 를 설계하지 않았기 때문이다([9절](#s9)). 보호 리소스 메타데이터에도 `scopes_supported` 가 없어서, 명세의 Scope Selection Strategy 대로라면 클라이언트는 `scope` 를 생략해야 한다. 에이전트는 로그인(OpenID Connect)으로 토큰을 받으므로 설정의 `openid profile` 을 요청한다([4.5](#s4-5)).

**[구현]**

- MCP 서버: official·chat-memory 는 `SecurityConfig#resourceMetadataEntryPoint` 가 Spring Security 의 `BearerTokenAuthenticationEntryPoint` 에 `resourceMetadataParameterResolver` 를 걸어, 요청 경로 `/mcp` 앞에 `/.well-known/oauth-protected-resource` 를 끼운 URL 을 만든다([RFC 9728 §3.1](https://www.rfc-editor.org/rfc/rfc9728#section-3.1) 의 변환 규칙). community 도 같은 진입점을 모듈 설정기의 `oauth2ResourceServer` 커스터마이저로 걸어 모듈 기본 진입점을 대체한다. 테스트: `McpAuthorizationStandardTest#토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다`(세 practice).
- 에이전트: official·chat-memory 의 `McpAuthorizationDiscovery` 는 토큰 없이 `initialize` 를 POST 하고, 401 이 아니면 발견을 멈춘다. `resource_metadata="..."` 를 정규식으로 꺼낸다. community 는 모듈 `McpMetadataDiscoveryService#getWwwAuthenticateParameters` 가 본문 없는 POST 로 같은 일을 한다.

<a id="s4-2"></a>

### 4.2 보호 리소스 메타데이터 발견

보호 리소스 메타데이터(Protected Resource Metadata, 이하 PRM)는 "이 리소스는 무엇이고, 어느 인가 서버가 이 리소스용 토큰을 발급하는가"를 적은 JSON 문서다.

**[명세]**

- [MCP 2025-11-25 Authorization — Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location)
  - MCP 서버는 RFC 9728 을 **MUST** 구현하고, PRM 에 인가 서버를 하나 이상 담은 `authorization_servers` 를 **MUST** 넣는다. (RFC 9728 자체에서 `authorization_servers` 는 OPTIONAL 이지만 MCP 가 필수로 올렸다.)
  - 여러 인가 서버 중 무엇을 쓸지는 클라이언트가 [RFC 9728 §7.6](https://www.rfc-editor.org/rfc/rfc9728#section-7.6) 에 따라 정한다. [2026-07-28 Authorization Server Discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery#authorization-server-location) 는 여기에 "나열된 인가 서버는 각각 독립된 인가 서버이므로 클라이언트는 인가 서버마다 등록 상태(자격증명·토큰)를 따로 가져야 하고(**MUST**), 한 인가 서버의 자격증명이 다른 곳에서도 통한다고 가정하면 안 된다(**MUST NOT**)"를 더했다.
- [RFC 9728 §3](https://www.rfc-editor.org/rfc/rfc9728#section-3), [§3.1](https://www.rfc-editor.org/rfc/rfc9728#section-3.1) — 메타데이터 URL 은 리소스 식별자의 host 와 path 사이에 `/.well-known/oauth-protected-resource` 를 끼워 만든다. 리소스 식별자에 경로가 있으면 host 뒤의 끝 `/` 를 먼저 지운다. 요청은 `GET` 이다(**MUST**).
- [RFC 9728 §3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2) — 성공 응답은 `200 OK` + `application/json` 이다(**MUST**). 값이 없는 파라미터는 응답에서 빼야 하고(**MUST**), 모르는 파라미터는 무시해야 한다(**MUST**).
- [RFC 9728 §3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3) — 검증 두 가지. (1) 응답의 `resource` 는 메타데이터 URL 을 만드는 데 쓴 리소스 식별자와 **정확히 같아야** 한다. (2) `WWW-Authenticate` 의 `resource_metadata` 로 받아 온 메타데이터라면 `resource` 는 클라이언트가 리소스 서버에 요청한 URL 과 **정확히 같아야** 한다. 다르면 응답의 내용을 쓰면 안 된다(**MUST NOT**). 공격자가 다른 리소스의 메타데이터를 내밀어 엉뚱한 인가 서버로 유도하는 사칭([§7.3](https://www.rfc-editor.org/rfc/rfc9728#section-7.3))을 막는다.
- [MCP 2025-11-25 Authorization — Canonical Server URI](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#canonical-server-uri) — MCP 서버의 canonical URI 는 RFC 8707 의 리소스 식별자이며 RFC 9728 의 `resource` 와 같다. scheme 이 있어야 하고 fragment 가 없어야 한다. 끝 `/` 는 의미가 없으면 붙이지 않는 것이 좋다(**SHOULD**).

PRM 의 전체 필드와 표시는 [E2](#e2) 에 있다.

**[관측]** C2 — 경로형 PRM.

```http
GET /.well-known/oauth-protected-resource/mcp HTTP/1.1
Host: localhost:8111
```

```http
HTTP/1.1 200
Content-Type: application/json
Content-Length: 179

{
  "resource": "http://localhost:8111/mcp",
  "bearer_methods_supported": ["header"],
  "tls_client_certificate_bound_access_tokens": false,
  "authorization_servers": ["http://localhost:9010"]
}
```

| practice | `resource` | `authorization_servers` | 그 밖의 필드 |
|---|---|---|---|
| official (C2) | `http://localhost:8111/mcp` | `["http://localhost:9010"]` | `bearer_methods_supported`, `tls_client_certificate_bound_access_tokens` |
| chat-memory (C2) | `http://localhost:8131/mcp` | `["http://localhost:9020"]` | 같음 |
| community (C2) | `http://localhost:8101/mcp` | `["http://localhost:9000"]` | 같음 + `"resource_name": "shop-mcp-server"` |

S2 — official 의 루트형 PRM. 루트형 URL 에서 만든 리소스 식별자는 서버 루트이므로 `resource` 도 루트다.

```http
HTTP/1.1 200
Content-Type: application/json
Content-Length: 175

{"resource":"http://localhost:8111","bearer_methods_supported":["header"],"tls_client_certificate_bound_access_tokens":false,"authorization_servers":["http://localhost:9010"]}
```

명세와 대조한 결과:

- `resource` 가 챌린지를 받은 요청 URL(`http://localhost:8111/mcp`)과 정확히 같다 — RFC 9728 §3.3 을 만족한다.
- `authorization_servers` 에 인가 서버가 하나 있다 — MCP 의 MUST 를 만족한다.
- `scopes_supported`(RECOMMENDED)가 없다 — scope 설계가 범위 밖이다.
- `resource_name`(RECOMMENDED)은 community 에만 있다.

> **각주 — `tls_client_certificate_bound_access_tokens` 가 PRM 에서는 `false`, 인가 서버 메타데이터([4.3](#s4-3))에서는 `true` 인 이유.**
> 이름은 같지만 서로 다른 문서의 서로 다른 필드다. PRM 의 필드([RFC 9728 §2](https://www.rfc-editor.org/rfc/rfc9728#section-2))는 "이 **보호 리소스**가 mTLS 인증서에 묶인 access token 을 지원하는가"이고, 인가 서버 메타데이터의 필드([RFC 8705 §3.3](https://www.rfc-editor.org/rfc/rfc8705#section-3.3))는 "이 **인가 서버**가 그런 토큰을 발급할 수 있는가"다. Spring Authorization Server 는 발급 능력으로 `true` 를 광고하고, MCP 서버는 그런 토큰을 요구하지도 검증하지도 않으므로 `false` 로 설정했다(Spring 의 PRM 기본값이 `true` 라서 명시적으로 끈다). 둘은 모순이 아니다.

**[구현]**

- MCP 서버: official·chat-memory 는 `SecurityConfig` 의 `protectedResourceMetadata(...)` 로 `authorizationServer(issuer)` 와 `tlsClientCertificateBoundAccessTokens(false)` 를 설정한다. Spring Security 가 요청 경로에 맞춰 경로형·루트형 두 곳에 문서를 낸다. community 는 모듈 `McpServerOAuth2Configurer#protectedResourceMetadataCustomizer` 에서 같은 값과 `resourceName` 을 준다. 테스트: `McpAuthorizationStandardTest#보호_리소스_메타데이터를_경로형으로_공개한다`(세 practice).
- 에이전트(official·chat-memory): `McpAuthorizationDiscovery#protectedResourceMetadata` 가 챌린지 URL → 경로형 → 루트형 순서로 시도한다. 챌린지·경로형에서 받은 문서는 `resource` 가 설정의 `resource-url` 과 같아야 하고, 루트형에서 받은 문서는 origin 과 같아야 한다. 인가 서버는 `authorization_servers` 의 첫 값을 쓴다. 테스트: `McpAuthorizationDiscoveryTest#챌린지가_가리키는_메타데이터를_따라간다`, `#챌린지에_위치가_없으면_경로형_well_known_을_먼저_본다`, `#경로형이_없으면_루트_well_known_으로_간다`, `#메타데이터의_resource_가_요청한_URL_과_다르면_실패한다`.
- 에이전트(community): 모듈 `McpMetadataDiscoveryService#getMcpMetadata` 가 같은 순서와 같은 `resource` 비교를 한다(루트형은 루트 URL 과 비교). 인가 서버 선택부터는 community 의 `McpAuthorizationDiscovery` 가 이어받는다. 테스트: `McpAuthorizationDiscoveryTest#챌린지에서_인가_서버까지_찾아낸다`.

<a id="s4-3"></a>

### 4.3 인가 서버 메타데이터 발견

PRM 이 알려 주는 것은 인가 서버의 **issuer 식별자**(URL)뿐이다. 인가·토큰 엔드포인트 주소와 지원 기능은 인가 서버 메타데이터에서 얻는다.

**[명세]**

- [MCP 2025-11-25 Authorization — Overview](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#overview) — 인가 서버는 RFC 8414 와 OpenID Connect Discovery 1.0 중 하나 이상을 **MUST** 제공하고, 클라이언트는 둘 다 **MUST** 지원한다.
- [MCP 2025-11-25 Authorization — Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery) — 클라이언트가 시도할 순서(**MUST**):

  | issuer 형태 | 1순위 | 2순위 | 3순위 |
  |---|---|---|---|
  | 경로 있음 `https://auth.example.com/tenant1` | `/.well-known/oauth-authorization-server/tenant1` (RFC 8414, 경로 삽입) | `/.well-known/openid-configuration/tenant1` (OIDC, 경로 삽입) | `/tenant1/.well-known/openid-configuration` (OIDC, 경로 뒤에 붙임) |
  | 경로 없음 `https://auth.example.com` | `/.well-known/oauth-authorization-server` | `/.well-known/openid-configuration` | — |

- [MCP 2026-07-28 Authorization Server Discovery — Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery#authorization-server-metadata-discovery) — 받은 문서의 `issuer` 는 well-known URL 을 만드는 데 쓴 issuer 식별자와 **같아야** 하고(**MUST**, [RFC 8414 §3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3) · [OIDC Discovery §4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation)), 다르면 그 문서를 쓰면 안 된다(**MUST NOT**). 2025-11-25 도 RFC 8414 §3.3 을 통해 같은 검증을 요구하지만, 2026-07-28 이 MCP 본문에 명시했다.
- [MCP 2025-11-25 Authorization — Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)
  - OAuth 2.1 과 PKCE 에는 PKCE 지원을 알아내는 방법이 없으므로 클라이언트는 메타데이터로 확인해야 한다(**MUST**).
  - RFC 8414 메타데이터에 `code_challenge_methods_supported` 가 없으면 PKCE 미지원으로 보고 진행을 거부해야 한다(**MUST**).
  - OpenID Provider 메타데이터에도 이 필드가 있는지 확인해 없으면 거부해야 한다(**MUST**). OIDC Discovery 를 제공하는 인가 서버는 이 필드를 넣어야 한다(**MUST**).
  - 가능하면 `S256` 을 써야 한다(**MUST**).
- [RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3) — `authorization_response_iss_parameter_supported`: 인가 응답에 `iss` 를 싣는지 여부. 생략하면 `false`.

**RFC 8414 를 먼저 시도하는 이유.** MCP 가 순서를 고정했고, 그 근거로 [RFC 8414 §5](https://www.rfc-editor.org/rfc/rfc8414#section-5) 를 든다. RFC 8414 는 OpenID Connect 에 한정되지 않은 일반 OAuth 인가 서버 메타데이터다. RFC 8414 §5 는 경로가 있는 issuer 에서 두 규격의 URL 변환이 다르다고 설명한다 — RFC 8414 는 경로 앞에 끼우고, OIDC Discovery 는 경로 뒤에 붙인다. 그리고 앞으로는 RFC 8414 의 변환을 먼저 시도하고, 실패할 때만 OIDC 방식으로 넘어가라고 권한다. 두 문서의 필드 차이는 [E4](#e4) 에 정리했다. OIDC Discovery 는 OIDC 전용 필수 필드(`subject_types_supported`, `id_token_signing_alg_values_supported` 등)를 요구하고, PKCE 필드(`code_challenge_methods_supported`)를 정의하지 않는다.

**[관측]** C3 — RFC 8414 메타데이터(official). chat-memory·community 는 issuer 와 엔드포인트의 포트만 다르고 필드 구성과 `Content-Length: 1742` 가 같다.

```http
GET /.well-known/oauth-authorization-server HTTP/1.1
Host: localhost:9010
```

```json
{
  "issuer": "http://localhost:9010",
  "authorization_endpoint": "http://localhost:9010/oauth2/authorize",
  "token_endpoint": "http://localhost:9010/oauth2/token",
  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "client_secret_jwt", "private_key_jwt", "tls_client_auth", "self_signed_tls_client_auth"],
  "jwks_uri": "http://localhost:9010/oauth2/jwks",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token", "urn:ietf:params:oauth:grant-type:token-exchange"],
  "revocation_endpoint": "http://localhost:9010/oauth2/revoke",
  "revocation_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "client_secret_jwt", "private_key_jwt", "tls_client_auth", "self_signed_tls_client_auth"],
  "introspection_endpoint": "http://localhost:9010/oauth2/introspect",
  "introspection_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "client_secret_jwt", "private_key_jwt", "tls_client_auth", "self_signed_tls_client_auth"],
  "code_challenge_methods_supported": ["S256"],
  "tls_client_certificate_bound_access_tokens": true,
  "dpop_signing_alg_values_supported": ["RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"],
  "authorization_response_iss_parameter_supported": true,
  "token_endpoint_auth_signing_alg_values_supported": ["HS256", "HS384", "HS512", "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512"],
  "revocation_endpoint_auth_signing_alg_values_supported": ["HS256", "HS384", "HS512", "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512"],
  "introspection_endpoint_auth_signing_alg_values_supported": ["HS256", "HS384", "HS512", "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512"]
}
```

S1 — 같은 인가 서버의 OpenID Provider 메타데이터(`/.well-known/openid-configuration`). RFC 8414 문서에 비해 다음 필드가 더 있다.

```json
{
  "userinfo_endpoint": "http://localhost:9010/userinfo",
  "end_session_endpoint": "http://localhost:9010/connect/logout",
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"],
  "scopes_supported": ["openid"]
}
```

명세와 대조한 결과:

- `issuer` 가 요청한 issuer 식별자(`http://localhost:9010`)와 같다.
- `code_challenge_methods_supported` 에 `S256` 이 있다 — 두 문서 모두.
- `authorization_response_iss_parameter_supported: true` 가 있다 — 두 문서 모두.
- `registration_endpoint` 가 없다 — DCR 을 제공하지 않는다([4.4](#s4-4)).
- `client_id_metadata_document_supported` 가 없다 — CIMD 를 지원하지 않는다([4.4](#s4-4)).
- `grant_types_supported` 에 `client_credentials`·`token-exchange` 가 있는 것은 Spring Authorization Server 의 서버 전체 능력이다. 이 practice 의 클라이언트에 등록된 grant 는 `authorization_code`·`refresh_token` 뿐이다.
- `token_endpoint_auth_signing_alg_values_supported`·`revocation_endpoint_auth_signing_alg_values_supported`·`introspection_endpoint_auth_signing_alg_values_supported` 가 세 엔드포인트 모두에 있다. `token_endpoint_auth_methods_supported` 등이 `client_secret_jwt`·`private_key_jwt` 를 광고하므로 RFC 8414 §2 의 조건부 MUST 대상이고, 12개 서명 알고리즘(`none` 제외)으로 채워 만족한다([8절](#s8) 20번).

**[구현]**

- 에이전트(세 practice): `McpAuthorizationDiscovery#metadataUrls` 가 위 표의 순서로 URL 을 만들고, 200 이 오는 첫 문서에 대해 `issuer` 일치와 `code_challenge_methods_supported` 의 `S256` 을 확인한다. 둘 중 하나라도 어긋나면 `McpDiscoveryException` 으로 멈춘다(다음 후보로 넘어가지 않는다). 테스트: `McpAuthorizationDiscoveryTest#메타데이터의_issuer_가_다르면_실패한다`, `#PKCE_S256_을_광고하지_않으면_진행하지_않는다`(세 practice), `#RFC8414_가_없으면_OIDC_디스커버리로_간다`(official·chat-memory).
- [RFC 9728 §5.2](https://www.rfc-editor.org/rfc/rfc9728#section-5.2) 는 리소스 서버가 메타데이터가 바뀌었음을 알리려고 새 챌린지를 보낼 수 있고(MAY), 클라이언트는 그때 PRM 을 다시 받아 검증하는 것이 좋다(SHOULD)고 한다. 에이전트는 성공한 발견 결과를 프로세스 수명 동안 캐시하므로, 실행 중 MCP 서버가 보내는 401 로 발견을 다시 하지는 않는다([4.10](#s4-10)).
- 인가 서버: official·chat-memory 는 `AuthorizationServerConfig` 가, community 는 `McpAuthorizationStandardConfig` 가 두 메타데이터 문서에 `authorization_response_iss_parameter_supported: true` 를 추가한다. community 의 모듈 자동설정은 OIDC 엔드포인트를 켜지 않으므로 `OidcDiscoveryConfig` 가 확장점으로 `oidc()` 를 켠다. 같은 클래스가 세 서명 알고리즘 claim 도 함께 심는다. 값은 지어낸 목록이 아니라 Spring Authorization Server 의 `JwtClientAssertionDecoderFactory` 가 `client_secret_jwt`·`private_key_jwt` 인증에서 실제로 만들어내는 알고리즘 전부(대칭키 `MacAlgorithm` HS256/384/512 + 비대칭 `SignatureAlgorithm` RS/ES/PS 256/384/512, `JwsAlgorithms` 상수 참조)이고, 최신 Spring Security(7.2.0-M1 포함)에는 이 claim 상수 자체가 없어 문자열 리터럴로 직접 심는다. 테스트: `AuthorizationServerStandardTest#메타데이터가_PKCE_S256_과_RFC9207_iss_지원을_광고한다`, `#메타데이터에_클라이언트_인증_서명_알고리즘이_광고된다`, `#OIDC_디스커버리에도_token_revocation_introspection_인증_서명_알고리즘이_모두_있다`(세 practice).

<a id="s4-4"></a>

### 4.4 클라이언트 등록

인가 요청을 만들려면 그 인가 서버가 아는 `client_id` 가 있어야 한다.

**[명세]** [MCP 2025-11-25 Authorization — Client Registration Approaches](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#client-registration-approaches), [MCP 2026-07-28 Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration)

모든 방식을 지원하는 클라이언트의 우선순위(**SHOULD**):

| 순위 | 방식 | 언제 쓰는가 | 요구 수준 | 이 practice |
|---|---|---|---|---|
| 1 | 사전 등록(pre-registration) | 클라이언트가 그 서버용 정보를 이미 가지고 있을 때 | 클라이언트는 정적 자격증명 옵션을 지원하는 것이 좋다(**SHOULD**) | **씀** |
| 2 | Client ID Metadata Document (CIMD) | 인가 서버가 메타데이터에 `client_id_metadata_document_supported: true` 를 광고할 때 | 인가 서버·클라이언트가 지원하는 것이 좋다(**SHOULD**) | 이 practice 에서는 쓰지 않음 |
| 3 | Dynamic Client Registration (DCR, RFC 7591) | 인가 서버 메타데이터에 `registration_endpoint` 가 있을 때 | **MAY**. 2026-07-28 에서 deprecated | 이 practice 에서는 쓰지 않음 |
| 4 | 사용자 입력 | 위 방법이 모두 없을 때 | — | 이 practice 에서는 쓰지 않음 |

- **사전 등록** — client_id(와 필요하면 자격증명)를 클라이언트에 미리 넣어 두거나, 사용자가 직접 등록한 뒤 UI 로 입력하게 한다.
- **CIMD** — 클라이언트가 자기 메타데이터 JSON 을 HTTPS URL 에 올리고 그 URL 자체를 `client_id` 로 쓴다([draft-ietf-oauth-client-id-metadata-document-00 §3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3)). 서로 모르는 클라이언트와 서버 사이의 기본 방식으로 권장된다.
  - 클라이언트: `client_id` URL 은 `https` 이고 경로가 있어야 한다(**MUST**, [draft-ietf-oauth-client-id-metadata-document-00 §3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3)). 문서에는 최소 `client_id`, `client_name`, `redirect_uris` 가 있어야 하고(**MUST**, [MCP 2025-11-25 Authorization — Client ID Metadata Documents — Implementation Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#implementation-requirements)), 문서의 `client_id` 는 문서 URL 과 정확히 같아야 한다(**MUST**, [draft-ietf-oauth-client-id-metadata-document-00 §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)).
  - 인가 서버: URL 형태의 client_id 를 만나면 문서를 가져오고(**SHOULD**), `client_id` 일치·redirect URI·문서 구조를 검증한다(**MUST**). 임의 URL 을 가져오는 SSRF 위험을 고려한다(**SHOULD**).
- **DCR** — 사용자 개입 없이 `POST /register` 로 client_id 를 받는다. 이전 MCP 리비전과의 하위 호환용이다. 2026-07-28 은 deprecated 로 표시하고 새 구현은 CIMD 를 쓰라고 한다. 그대로 쓰는 클라이언트는 알맞은 `application_type` 을 지정해야 한다(**MUST**, OIDC 인가 서버의 redirect URI 제약 충돌 방지).

[명세] [MCP 2026-07-28 Client Registration — Authorization Server Binding](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#authorization-server-binding)

- 사전 등록 자격증명이나 DCR 로 받아 저장한 자격증명은 그것을 발급한 인가 서버의 `issuer` 를 키로 묶어야 한다(**MUST**).
- 인가 서버가 바뀌면(갱신된 PRM 으로 감지) 다른 인가 서버의 자격증명을 재사용하면 안 되고(**MUST NOT**), 새 인가 서버에 다시 등록해야 한다(**MUST**).
- 사전 등록 자격증명이 PRM 이 가리키는 인가 서버와 맞지 않으면, 몰래 시도하지 말고 오류를 드러내는 것이 좋다(**SHOULD**).
- CIMD 의 client_id 는 인가 서버 사이에서 이식 가능하므로 재등록이 필요 없다.

**[관측]**

- C3 · S1 — 인가 서버 메타데이터에 `registration_endpoint` 와 `client_id_metadata_document_supported` 가 없다. 이 인가 서버에서 가능한 등록 방식은 사전 등록뿐이다.
- 세 practice 의 client_id 는 인가 서버 설정(`spring.security.oauth2.authorizationserver.client.*`)에 미리 등록되어 있다([2.4](#s2-4)).

**[구현]**

- 에이전트의 설정에는 자격증명과 그 자격증명이 묶인 issuer 만 있다(official 예).

  ```yaml
  spring.security.oauth2.client.registration.authserver:
    client-id: official-shop-agent
    client-secret: official-shop-agent-secret
    authorization-grant-type: authorization_code
    redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
    scope: [openid, profile]
  mcp.authorization:
    resource-url: http://localhost:8111/mcp       # 발견의 출발점
    credentials-issuer: http://localhost:9010      # 위 자격증명을 발급한 인가 서버
  ```

- `DiscoveredClientRegistrationRepository#registration` 은 발견한 issuer 가 `credentials-issuer` 와 다르면 `McpDiscoveryException` 을 던진다. 이때 `ClientRegistration` 을 만들지 않으므로 client_secret 이 어디로도 나가지 않는다. 테스트: `DiscoveredClientRegistrationRepositoryTest#자격증명이_묶인_인가_서버가_아니면_쓰지_않는다`.
- 발견은 처음 필요할 때 한 번 하고, 성공한 결과만 캐시한다. 실패는 캐시하지 않아 다음 요청에서 다시 시도한다(`#발견은_한_번만_한다`, `#실패는_캐시하지_않는다`). 캐시가 프로세스 수명 동안 유지되므로, 실행 중에 PRM 의 인가 서버가 바뀌는 것은 에이전트를 다시 시작할 때 감지된다. 그때 issuer 가 다르면 위 검사로 오류가 난다.
- community 인가 서버는 모듈이 기본으로 켜는 DCR 을 `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` 로 끈다. 테스트: `AuthorizationServerStandardTest#동적_클라이언트_등록은_켜지_않는다`.

<a id="s4-5"></a>

### 4.5 인가 요청 — PKCE 와 resource

**[명세]**

- [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1)
  - `response_type=code`(REQUIRED), `client_id`(REQUIRED), `code_challenge`(REQUIRED 또는 RECOMMENDED — §7.5.1), `code_challenge_method`(OPTIONAL, 기본 `plain`), `redirect_uri`(등록이 하나면 OPTIONAL, 여럿이면 REQUIRED), `scope`(OPTIONAL), `state`(OPTIONAL).
  - 인가 서버는 `redirect_uri` 를 등록값과 단순 문자열 비교로 정확히 대조해야 한다(**MUST**).
  - [RFC 6749 §4.1.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.1) 에서 `state` 는 RECOMMENDED 였다.
- [RFC 7636 §4.1–§4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.1)
  - `code_verifier` 는 43~128자의 비예약 문자로 된 고엔트로피 무작위 문자열이다. `S256` 일 때 `code_challenge = BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))` 다.
  - `S256` 을 쓸 수 있는 클라이언트는 `S256` 을 써야 한다(**MUST**).
  - PKCE 를 요구하는 서버는 `code_challenge` 가 없는 요청에 `invalid_request` 를 돌려줘야 한다(**MUST**, [§4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1)).
- [OAuth 2.1 §7.5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.5.2) — confidential client 이면서 OIDC `nonce` 를 올바르게 쓴다는 확신이 있을 때만 PKCE 를 생략할 수 있다. 그 경우에도 PKCE 는 RECOMMENDED 다. MCP 는 이 예외 없이 PKCE 를 요구한다.
- [MCP 2025-11-25 Authorization — Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) — 클라이언트는 PKCE 를 구현하고(**MUST**) 진행 전에 지원을 확인한다(**MUST**, [4.3](#s4-3)).
- [MCP 2025-11-25 Authorization — Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)
  - 클라이언트는 RFC 8707 을 구현해야 한다(**MUST**).
  - `resource` 는 인가 요청과 토큰 요청 **둘 다**에 넣는다(**MUST**). 값은 토큰을 쓸 MCP 서버를 가리키는 canonical URI 다(**MUST**).
  - 인가 서버가 지원하는지와 **무관하게** 보낸다(**MUST**).
- [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1)
  - `resource` 는 절대 URI 여야 하고 fragment 를 포함하면 안 된다(**MUST**).
  - 인가 서버가 값을 해석할 수 없거나 받아들일 수 없으면 `invalid_target` 으로 거부한다.
  - 인가 요청에서 `resource` 를 생략하면 인가 서버는 기본값으로 처리할 수도(**MAY**), `invalid_target` 으로 거부할 수도(**MAY**) 있다.
- [MCP 2025-11-25 Authorization — Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection)
  - 클라이언트는 redirect URI 를 등록해야 하고(**MUST**), `state` 를 쓰고 검증하는 것이 좋다(**SHOULD**).
  - 인가 서버는 등록값과 정확히 대조해야 한다(**MUST**).
- [RFC 6749 §4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1) — redirect URI 가 없거나 틀리거나, client_id 가 없거나 틀리면 인가 서버는 그 URI 로 자동 리다이렉트하면 안 된다(**MUST NOT**).
- [OpenID Connect Core 1.0 §3.1.2.1](https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest) — `nonce`(OPTIONAL): 클라이언트 세션과 ID Token 을 묶어 재전송을 막는 값.

**다이어그램 ③** — 인가 요청.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    B->>C: GET /oauth2/authorization/authserver
    Note over C: code_verifier = 무작위 43자 이상<br/>code_challenge = BASE64URL(SHA256(code_verifier))<br/>state, nonce 도 무작위로 만든다
    Note over C: 세션의 인가 요청 기록에 code_verifier, state, nonce,<br/>registrationId 를 저장한다
    C-->>B: 302 인가 엔드포인트<br/>response_type=code, client_id, scope=openid profile, state,<br/>redirect_uri, nonce, code_challenge, code_challenge_method=S256,<br/>resource=http://localhost:8111/mcp
    B->>A: GET /oauth2/authorize?...
    Note over A: client_id 등록 여부와 redirect_uri 정확 일치를 먼저 본다
    alt client_id 나 redirect_uri 가 틀림
        A-->>B: 400 (redirect_uri 로 보내지 않는다)
    else scope, code_challenge, resource 중 하나가 틀림
        A-->>B: 302 redirect_uri?error=invalid_request 또는 invalid_scope 또는 invalid_target, state, iss
    else 요청이 올바름
        A-->>B: 302 /login
        B->>A: 로그인 (user / password)
        Note over A: 동의 화면은 생략 (require-authorization-consent: false)
        Note over A: 인가 코드에 client_id, redirect_uri, code_challenge,<br/>resource, 사용자를 묶어 둔다
        A-->>B: 302 redirect_uri?code=..., state=..., iss=http://localhost:9010
    end
```

**[관측]** S17 — 에이전트가 발견 결과로 만든 인가 요청(official). 읽기 쉽게 파라미터를 한 줄에 하나씩 풀었다.

```http
HTTP/1.1 302
Location: http://localhost:9010/oauth2/authorize
  ?response_type=code
  &client_id=official-shop-agent
  &scope=openid%20profile
  &state=vyMgahSxFKsbVLT6s41V2M7bHqehuDoToU8JTTPst5o%3D
  &redirect_uri=http://localhost:8110/login/oauth2/code/authserver
  &nonce=4SgkKmhfqjp9G_dLZfTpLJ-HLj0vUbm0IhCKt0Rqlpw
  &code_challenge=5tPg093jM6Nk0oOa1O3CYuUcYYPL92lzwX0p9ADgBHI
  &code_challenge_method=S256
  &resource=http://localhost:8111/mcp
```

- 인가 엔드포인트 주소는 설정이 아니라 발견한 메타데이터(C3)의 `authorization_endpoint` 다.
- `code_challenge` 는 43자 base64url, `code_challenge_method=S256`, `resource` 는 PRM 의 `resource` 와 같다.
- `nonce` 는 `openid` scope 로 OIDC 로그인을 하기 때문에 Spring Security 가 붙인다.

C5 — 캡처 스크립트가 같은 형태의 요청(RFC 7636 부록 B 의 예시 `code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`)을 보냈을 때 받은 성공 응답:

```http
HTTP/1.1 302
Location: http://localhost:8110/login/oauth2/code/authserver?code=3S_GaQLdjO5v...&state=walkthrough-state&iss=http%3A%2F%2Flocalhost%3A9010
```

잘못된 인가 요청에 대한 응답(세 practice 동일, 포트만 다름):

| 요청 | 관측 | 명세와 대조 |
|---|---|---|
| `code_challenge` 없음 (C17) | `302 ...?error=invalid_request&error_description=OAuth%202.0%20Parameter%3A%20code_challenge&error_uri=https%3A%2F%2Fdatatracker.ietf.org%2Fdoc%2Fhtml%2Frfc7636%23section-4.4.1&state=walkthrough-state&iss=http%3A%2F%2Flocalhost%3A9010` | RFC 7636 §4.4.1 의 `invalid_request` 와 맞다. 오류 응답에도 `iss` 가 있다(RFC 9207 §2) |
| 허용 목록에 없는 `resource=http://localhost:9999/mcp` (C16) | `302 ...?error=invalid_target&error_description=The%20requested%20resource%20is%20not%20a%20known%20protected%20resource&error_uri=https%3A%2F%2Fwww.rfc-editor.org%2Frfc%2Frfc8707%23section-2&state=walkthrough-state&iss=http%3A%2F%2Flocalhost%3A9010` | RFC 8707 §2.1 의 `invalid_target` 과 맞다 |
| 등록되지 않은 `redirect_uri=http://evil.example/callback` (S4, official) | `400`, 본문 `{"timestamp":"...","status":400,"error":"Bad Request","path":"/oauth2/authorize"}`, `Location` 없음 | RFC 6749 §4.1.2.1 의 MUST NOT(리다이렉트 금지)과 맞다 |

**[구현]**

- 에이전트: `SecurityConfig#authorizationRequestResolver` 가 Spring Security 의 `OAuth2AuthorizationRequestCustomizers.withPkce()`(confidential client 에도 PKCE 를 강제)와 `ResourceIndicators.authorizationRequest(...)`(발견한 `resource`)를 잇는다. 인가 요청 기록은 `HttpSessionOAuth2AuthorizationRequestRepository` 에 저장된다. 테스트: `ShopAgentApplicationTests#인가_요청에_PKCE_와_resource_가_실린다`(세 practice).
- 인가 서버: `require-proof-key: true` 가 `code_challenge` 없는 요청을 거부한다. `ResourceIndicatorValidator` 가 기본 검증(redirect_uri·scope) 뒤에 이어져 `resource` 가 `mcp.authorization.resources` 에 없으면 `invalid_target` 을 던진다. `resource` 가 없는 인가 요청은 거부하지 않는다(RFC 8707 §2.1 의 MAY). `IssuerIdentifyingAuthorizationResponseHandler` 는 redirect_uri 를 신뢰할 수 없는 오류면 리다이렉트하지 않고 400 을 보낸다. 테스트: `AuthorizationServerStandardTest#PKCE_없는_인가_요청은_거부된다`, `#등록되지_않은_resource_는_invalid_target_이다`.

<a id="s4-6"></a>

### 4.6 콜백과 iss 검증 — mix-up 공격

**mix-up 공격이란.** 클라이언트가 인가 서버를 둘 이상 다루고 그중 하나가 공격자 손에 있으면, 공격자는 다른(정직한) 인가 서버가 발급한 인가 코드나 토큰을 자기에게 보내게 만들 수 있다([RFC 9207 §1](https://www.rfc-editor.org/rfc/rfc9207#section-1)). 원인은 인가 응답(`code`, `state`)에 "누가 발급했는가"가 없다는 데 있다. 사용자 브라우저를 거쳐 돌아온 응답만 봐서는 어느 인가 서버의 응답인지 알 수 없다. MCP 에서는 클라이언트가 **MCP 서버가 알려 주는** 인가 서버로 가기 때문에, 악성 MCP 서버의 PRM 이 공격자 인가 서버를 가리키는 식으로 이 조건이 쉽게 생긴다([MCP 2026-07-28 Authorization Security Considerations — Mix-Up Attacks](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations#mix-up-attacks)).

**[명세]**

- [RFC 6749 §4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2), [OAuth 2.1 §4.1.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2) — 성공 응답은 `code`(REQUIRED), `state`(요청에 있었으면 REQUIRED). OAuth 2.1 은 `iss`(OPTIONAL)를 추가했다. 클라이언트는 모르는 응답 파라미터를 무시해야 한다(**MUST**).
- [RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2) — RFC 9207 을 지원하는 인가 서버는 성공·오류 응답 **모두**에 `iss` 를 넣어야 한다(**MUST**). 값은 인가 서버의 issuer 식별자다.
- [RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3) — RFC 8414 메타데이터를 내는 인가 서버라면 메타데이터의 `issuer` 가 `iss` 와 같아야 하고(**MUST**), `authorization_response_iss_parameter_supported: true` 를 광고해야 한다(**MUST**).
- [RFC 9207 §2.4](https://www.rfc-editor.org/rfc/rfc9207#section-2.4)
  - 클라이언트는 `iss` 를 `application/x-www-form-urlencoded` 디코딩한 뒤, 요청을 보낸 인가 서버의 issuer 와 단순 문자열 비교해야 한다(**MUST**). 다르면 응답을 거부하고 그 그랜트로 진행하면 안 된다(**MUST NOT**).
  - 오류 응답에서는 그 오류가 의도한 인가 서버에서 왔다고 가정하면 안 된다(**MUST NOT**).
  - `iss` 를 지원하는 인가 서버의 응답에 `iss` 가 없으면 거부해야 한다(**MUST**).
- [OAuth 2.1 §2.3.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.3.4) — 인가 서버를 둘 이상 다루는 클라이언트는 mix-up 을 막아야 한다(**MUST**).
- [MCP 2026-07-28 Authorization — Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)
  - 리다이렉트 전에, 검증한 메타데이터의 `issuer` 를 PKCE code_verifier(와 `state`)를 저장하는 **같은 요청별 기록**에 함께 저장해야 한다(**MUST**).
  - MCP 인가 서버는 오류 응답을 포함해 `iss` 를 넣는 것이 좋다(**SHOULD**). 넣는다면 메타데이터로 광고해야 한다(**MUST**). 이후 리비전에서 MUST 로 올릴 예정이라고 적혀 있다.
  - 클라이언트는 인가 코드를 어떤 토큰 엔드포인트로 보내기 **전에** 아래 표대로 검증해야 한다(**MUST**).

    | 메타데이터 `authorization_response_iss_parameter_supported` | 응답의 `iss` | 클라이언트 동작 |
    |---|---|---|
    | `true` | 있음 | 기록한 issuer 와 단순 문자열 비교 |
    | `true` | 없음 | 응답 거부 |
    | `false` 또는 없음 | 있음 | 기록한 issuer 와 단순 문자열 비교 |
    | `false` 또는 없음 | 없음 | 진행 |

  - 비교 전에 scheme·host 대소문자 통일, 기본 포트 생략, 끝 `/`, 퍼센트 인코딩 정규화를 하면 안 된다(**MUST NOT**).
  - 오류 응답에도 같이 적용되며, 불일치하면 `error`, `error_description`, `error_uri` 를 따르거나 보여 주면 안 된다(**MUST NOT**).
- [MCP 2025-11-25 Authorization — Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection) — `state` 가 없거나 원래 값과 다른 결과는 버리는 것이 좋다(**SHOULD**).

**다이어그램 ④** — 정상 경로와, 다른 인가 서버의 응답이 섞였을 때 거부되는 경로.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    Note over C: 인가 요청 전에 가지고 있는 것<br/>state, code_verifier (세션의 인가 요청 기록)<br/>발견으로 검증한 issuer = http://localhost:9010<br/>authorization_response_iss_parameter_supported = true
    alt 정상 경로
        A-->>B: 302 redirect_uri?code=c1, state=s1, iss=http://localhost:9010
        B->>C: GET /login/oauth2/code/authserver?code=c1, state=s1, iss=...
        Note over C: state 로 인가 요청 기록을 찾는다<br/>iss 를 디코딩해 issuer 와 문자열 그대로 비교한다. 같다
        C->>A: POST /oauth2/token (code=c1, code_verifier, resource)
        A-->>C: 200 토큰
    else 다른 인가 서버의 응답이 섞임 (mix-up)
        Note over B: 공격자가 제어하는 인가 서버 evil.example 의 응답이<br/>이 클라이언트의 콜백으로 전달된다
        B->>C: GET /login/oauth2/code/authserver?code=forged, state=s1, iss=http://evil.example
        Note over C: iss 가 기록한 issuer 와 다르다
        C-->>B: 401 로그인 실패: iss mismatch
        Note over C: 인가 요청 기록을 지운다<br/>코드를 어느 토큰 엔드포인트에도 보내지 않는다
    else iss 가 없는 응답
        B->>C: GET /login/oauth2/code/authserver?code=forged, state=s2
        Note over C: 인가 서버가 iss 지원을 광고했는데 iss 가 없다
        C-->>B: 401 로그인 실패: iss is missing
    end
```

**[관측]**

- C5 — 성공 응답에 `iss=http%3A%2F%2Flocalhost%3A9010` 이 있다(디코딩하면 `http://localhost:9010`, C3 의 `issuer` 와 같다).
- C16 · C17 — 오류 응답에도 `iss` 가 있다(세 practice).
- S18 — 조작한 `iss` 로 콜백. 에이전트가 코드를 교환하지 않고 거부한다. 응답 본문의 메시지로 거부 사유가 iss 검사임을 알 수 있다.

  ```http
  GET /login/oauth2/code/authserver?code=forged-code&state=<에이전트가 발급한 state>&iss=http%3A%2F%2Fevil.example

  HTTP/1.1 401
  Content-Type: text/plain;charset=UTF-8

  로그인 실패: iss mismatch: expected http://localhost:9010 but got http://evil.example
  ```

- S19 — `iss` 없는 콜백.

  ```http
  HTTP/1.1 401
  Content-Type: text/plain;charset=UTF-8

  로그인 실패: iss is missing although the authorization server advertises it
  ```

- S20 — 정상 왕복. 인가 서버가 `...?code=tXQ2qA0_QjMQ...&state=I2byMp2w...%3D&iss=http%3A%2F%2Flocalhost%3A9010` 로 보내고, 에이전트는 코드를 교환한 뒤 `302 Location: http://localhost:8110/` 으로 로그인을 끝낸다.

**[구현]**

- 인가 서버: `IssuerIdentifyingAuthorizationResponseHandler` 를 인가 엔드포인트의 `authorizationResponseHandler`·`errorResponseHandler` 로 걸어 성공·오류 리다이렉트 모두에 `iss` 를 붙인다. 값은 `AuthorizationServerContextHolder` 의 issuer 로, 메타데이터의 `issuer` 와 같다. 테스트: `AuthorizationServerStandardTest#인가_응답에_code_state_iss_가_실린다`.
- 에이전트: `AuthorizationResponseIssuerFilter` 가 `OAuth2LoginAuthenticationFilter`(코드 교환) **앞에** 선다.
  1. 콜백 요청의 `state` 로 세션의 인가 요청 기록을 꺼낸다.
  2. 기록에 담긴 `registrationId` 로 발견 결과의 `ClientRegistration` 을 찾는다.
  3. 그 등록의 `issuerUri`(발견 때 검증한 issuer)와 메타데이터의 `authorization_response_iss_parameter_supported` 로 위 표를 적용한다. `request.getParameter("iss")` 는 서블릿이 이미 URL 디코딩한 값이고, 비교는 `String.equals` 다.
  4. 실패하면 인가 요청 기록을 지우고 `LoginFailureHandler` 가 401 을 보낸다. 인가 서버가 보낸 `error` 계열 파라미터는 보여 주지 않는다.

  테스트: `AuthorizationResponseIssuerFilterTest#iss_가_다르면_코드를_교환하지_않는다`, `#지원한다고_광고했는데_iss_가_없으면_거부한다`, `#광고하지_않은_인가_서버라면_iss_없이도_통과시킨다`.
- 기록 방식의 차이: 2026-07-28 은 issuer 를 code_verifier 와 **같은 요청별 기록**에 넣으라고 한다. 이 practice 는 요청별 기록에 `registrationId` 를 넣고, issuer 는 그 등록(발견 결과, 프로세스 수명 동안 캐시)에서 꺼낸다. 인가 서버가 하나이고 발견 결과가 실행 중에 바뀌지 않으므로 비교 대상은 같지만, 문구 그대로의 구현은 아니다([8절](#s8) 10번).

---

<a id="s4-7"></a>

### 4.7 토큰 요청과 access token 의 구조

`iss` 검증을 통과한 콜백에서 에이전트는 인가 코드를 토큰으로 바꾼다. 이 요청은 브라우저를 거치지 않는 서버 간(back-channel) 요청이다.

**[명세]**

- [OAuth 2.1 §3.2.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.2) · [§4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3)
  - 요청 파라미터는 `grant_type=authorization_code`(REQUIRED), `code`(REQUIRED), `code_verifier`(인가 요청에 `code_challenge` 가 있었으면 REQUIRED, 없었으면 쓰면 안 됨), `client_id`(클라이언트 인증을 하지 않을 때 REQUIRED)다.
  - confidential client 는 인증해야 한다(**MUST**).
  - 인가 서버는 다음을 해야 한다(**MUST**): 한 코드로 토큰을 한 번만 발급한다. `code_verifier` 가 `code_challenge` 가 있었을 때에만 오는지 확인한다. 둘을 대조한다. `code_challenge` 없이 발급된 코드의 토큰 요청은 거부한다.
- [RFC 6749 §4.1.3](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.3) — 인가 요청에 `redirect_uri` 가 있었다면 토큰 요청에도 같은 값으로 REQUIRED 다. OAuth 2.1 은 이 파라미터를 목록에서 뺐고, 하위 호환은 §10.2 에서 다룬다.
- [RFC 7636 §4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6) — `BASE64URL-ENCODE(SHA256(ASCII(code_verifier))) == code_challenge` 가 아니면 `invalid_grant` 를 돌려줘야 한다(**MUST**).
- [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) · [MCP Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)
  - 토큰 요청의 `resource` 는 토큰을 쓸 대상을 가리킨다. `authorization_code`·`refresh_token` 그랜트에서는 원래 허가된 리소스로 제한할 수 있다.
  - 인가 서버는 발급하는 access token 의 audience 를 `resource` 로 제한하는 것이 좋다(**SHOULD**, [§2](https://www.rfc-editor.org/rfc/rfc8707#section-2)). JWT 에서는 `aud` 클레임이다.
  - MCP 클라이언트는 토큰 요청에도 `resource` 를 넣어야 한다(**MUST**).
- [OAuth 2.1 §3.2.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.3)
  - 응답은 `access_token`(REQUIRED), `token_type`(REQUIRED), `expires_in`(RECOMMENDED), `scope`(요청과 같으면 RECOMMENDED, 다르면 REQUIRED), `refresh_token`(OPTIONAL)이다.
  - 토큰을 담은 응답에는 `Cache-Control: no-store` 가 있어야 한다(**MUST**). [RFC 6749 §5.1](https://www.rfc-editor.org/rfc/rfc6749#section-5.1) 은 `Pragma: no-cache` 도 요구했다.
  - OpenID Connect 흐름이면 `id_token` 이 함께 온다([OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)).
- [OAuth 2.1 §3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4) · [RFC 6749 §5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2) — 오류는 기본 400 이다. 단, 클라이언트가 `Authorization` 헤더로 인증을 시도했다가 실패한 `invalid_client` 는 **401 과 그 스킴에 맞는 `WWW-Authenticate`** 로 응답해야 한다(**MUST**).
- [RFC 9068 §2.1](https://www.rfc-editor.org/rfc/rfc9068#section-2.1) · [§2.2](https://www.rfc-editor.org/rfc/rfc9068#section-2.2) — JWT 형식 access token 의 표준 프로파일.
  - 헤더 `typ` 에 `at+jwt` 를 넣어야 한다(**MUST**, 값은 SHOULD `at+jwt`).
  - 클레임 `iss`·`exp`·`aud`·`sub`·`client_id`·`iat`·`jti` 가 REQUIRED 다.
  - scope 가 요청됐으면 `scope` 클레임이 있는 것이 좋다(SHOULD). 형식은 공백으로 구분한 **문자열**이다([RFC 8693 §4.2](https://www.rfc-editor.org/rfc/rfc8693#section-4.2)).
  - MCP 명세는 audience 검증을 설명하면서 이 프로파일을 예로 드는 데 그친다. access token 형식을 RFC 9068 로 정하지는 않는다([Access Token Privilege Restriction](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-privilege-restriction), [OAuth 2.1 §5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.2)).
- [OpenID Connect Core 1.0 §2](https://openid.net/specs/openid-connect-core-1_0.html#IDToken) — ID Token 의 `aud` 에는 클라이언트의 `client_id` 가 있어야 한다(**MUST**). 다른 audience 를 더할 수는 있다(MAY).

**다이어그램 ⑤** — 토큰 요청.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    Note over C: iss 검증을 통과한 콜백 (code, state)
    C->>A: POST /oauth2/token<br/>Authorization: Basic base64(client_id:client_secret)<br/>grant_type=authorization_code, code, redirect_uri,<br/>code_verifier, resource=http://localhost:8111/mcp
    Note over A: 1. 클라이언트 인증 (client_secret_basic)
    Note over A: 2. 코드가 이 클라이언트에게 발급됐고 유효한가, redirect_uri 가 같은가
    Note over A: 3. BASE64URL(SHA256(code_verifier)) 가 저장한 code_challenge 와 같은가
    Note over A: 4. resource 가 인가 요청의 resource 와 같고 허용 목록에 있는가
    alt 검사 실패
        A-->>C: 401 invalid_client 또는 400 invalid_grant, invalid_target
    else 검사 통과
        Note over A: access token 발급, aud = resource<br/>id_token 발급, aud = client_id
        A-->>C: 200 access_token, token_type=Bearer, expires_in=299,<br/>refresh_token, scope, id_token
    end
    Note over C: id_token 의 서명, iss, aud, nonce 를 검증하고 로그인 완료<br/>access token 과 refresh token 은 에이전트 프로세스에 보관
    C-->>B: 302 /
```

**[관측]** C6 — 캡처 스크립트의 토큰 요청. 에이전트가 보내는 요청도 파라미터가 같다(테스트: `AuthorizationCodeTokenRequestTest#코드_교환_요청에_resource_를_실어_보낸다`, 세 practice).

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9010
Authorization: Basic <base64(official-shop-agent:official-shop-agent-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=3S_GaQLdjO5v...&redirect_uri=http%3A%2F%2Flocalhost%3A8110%2Flogin%2Foauth2%2Fcode%2Fauthserver&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

S5 — 응답 헤더와 본문(official). C6 의 세 practice 본문도 같은 필드를 같은 순서로 담는다.

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json;charset=UTF-8
Content-Length: 1702

{"access_token":"eyJraWQiOiIyYjk5ZGJl...","refresh_token":"GYoLPa20WGKQ...","scope":"openid profile","id_token":"eyJraWQiOiIyYjk5ZGJl...","token_type":"Bearer","expires_in":299}
```

C6-1 · C6-2 — 두 토큰의 페이로드(official). JOSE 헤더는 두 토큰 모두 `{"kid":"e00b5681-d97e-4a9e-a585-f3aa79c19541","alg":"RS256"}` 이다.

```json
// access token
{"sub":"user","aud":"http://localhost:8111/mcp","nbf":1789312661,"scope":["openid","profile"],"iss":"http://localhost:9010","exp":1789312961,"iat":1789312661,"jti":"6e3172d5-e25a-4ebe-a87f-a1a370c6ee34"}

// id_token
{"sub":"user","aud":"official-shop-agent","azp":"official-shop-agent","auth_time":1789312660,"iss":"http://localhost:9010","exp":1789314461,"iat":1789312661,"jti":"e36a67d8-075d-494a-b8e5-db7615273831","sid":"WjKmKpCSA788yFThT88znWWdvK2uSIFwL7mx0Ee9spQ"}
```

| practice | access token `aud` | id_token `aud` | access token 수명(`exp - iat`) |
|---|---|---|---|
| official (C6-1, C6-2) | `http://localhost:8111/mcp` | `official-shop-agent` | 300초 |
| chat-memory | `http://localhost:8131/mcp` | `memory-agent` | 300초 |
| community | `http://localhost:8101/mcp` | `shop-agent` | 300초 |

access token 을 RFC 9068 프로파일과 대조하면 다음과 같다.

| 항목 | RFC 9068 | 관측 | 판단 |
|---|---|---|---|
| 헤더 `typ` | `at+jwt` MUST | 없음 | 프로파일 불일치 |
| `iss` | REQUIRED | `http://localhost:9010` | 맞음 |
| `exp` | REQUIRED | 있음 | 맞음 |
| `aud` | REQUIRED | MCP 서버의 리소스 식별자 | 맞음 |
| `sub` | REQUIRED | `user` (자원 소유자) | 맞음 |
| `client_id` | REQUIRED | 없음 | 프로파일 불일치 |
| `iat` | REQUIRED | 있음 | 맞음 |
| `jti` | REQUIRED | 있음 | 맞음 |
| `scope` | SHOULD, 공백 구분 문자열 | `["openid","profile"]` (JSON 배열) | 형식 불일치 |
| `nbf` | 정의 없음 | 있음 | 추가 클레임 |

즉 Spring Authorization Server 가 발급하는 access token 은 서명된 JWT 이고 MCP 가 요구하는 `aud` 를 담지만, RFC 9068 프로파일을 따르지는 않는다. MCP 명세가 이 프로파일을 요구하지 않으므로 MCP 준수에는 영향이 없다. 다만 RFC 9068 을 전제로 한 리소스 서버와는 호환되지 않는다([4.9](#s4-9), [8절](#s8) 17번).

잘못된 토큰 요청(official):

| 요청 | 관측 | 명세와 대조 |
|---|---|---|
| 인가 요청과 다른 `resource=http://localhost:9999/mcp` (S6) | `400` `{"error_description":"The requested resource does not match the authorization request","error":"invalid_target","error_uri":"https://www.rfc-editor.org/rfc/rfc8707#section-2"}` | RFC 8707 의 `invalid_target` 과 맞다 |
| 틀린 `code_verifier` (S7) | `400` `{"error":"invalid_grant"}` | RFC 7636 §4.6 의 MUST 와 맞다 |
| 틀린 client_secret 으로 Basic 인증 (S8) | `401` `WWW-Authenticate: Basic realm="http://localhost:9010"` `{"error":"invalid_client"}` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 의 MUST 와 맞다 — `Authorization` 헤더로 인증을 시도했으므로 그 스킴(`Basic`)에 맞는 `WWW-Authenticate` 가 realm(issuer)과 함께 실린다([8절](#s8) 18번) |

**[구현]**

- 에이전트: `McpSecurityConfig#authorizationCodeTokenResponseClient` 가 Spring Security 의 `RestClientAuthorizationCodeTokenResponseClient` 에 `ResourceIndicators.tokenRequest(...)` 를 파라미터 변환기로 더해 발견한 `resource` 를 싣는다. `code_verifier` 와 `redirect_uri` 는 Spring Security 가 저장된 인가 요청에서 꺼내 넣는다. 인증 방식은 `DiscoveredClientRegistrationRepository` 가 `CLIENT_SECRET_BASIC` 으로 정한다.
- 인가 서버: `ResourceAudienceTokenCustomizer` 가 access token 을 만들 때만 동작한다.
  1. 토큰 요청의 `resource`(requested)와 인가 요청 때 저장된 `resource`(authorized)를 꺼낸다.
  2. 둘 다 있고 다르면 `invalid_target` 을 던진다.
  3. 쓸 값(requested, 없으면 authorized)이 허용 목록에 없으면 `invalid_target` 을 던진다.
  4. 통과하면 `aud` 를 그 값 하나로 설정한다.
  5. 둘 다 없으면 `aud` 를 바꾸지 않는다. 그 토큰은 MCP 서버의 audience 검증에서 거부된다.

  테스트: `AuthorizationServerStandardTest#access_token_의_aud_는_resource_이고_id_token_은_client_id_다`, `#토큰_요청의_resource_가_인가_요청과_다르면_invalid_target_이다`, `#토큰_요청에_resource_가_없으면_인가_요청의_resource_로_발급한다`.
- community 인가 서버: 모듈의 `ResourceIdentifierAudienceTokenCustomizer` 는 "access token 이면서 `openid` scope" 인 경우만 건너뛰고, 그 밖의 토큰에는 `resource` 가 있으면 `aud` 를 덮어쓴다 — id_token 도 포함된다. 그래서 community 의 `ResourceAudienceTokenCustomizer` 는 모듈 것 뒤에 실행되어 두 가지를 한다. access token 의 `aud` 를 위 규칙대로 채우고, id_token 의 `aud` 를 client_id 로 되돌린다(OIDC Core §2). C6-2 에서 community id_token 의 `aud` 가 `shop-agent` 인 것이 그 결과다.
- 클라이언트 인증 실패의 `WWW-Authenticate`: Spring 인가 서버의 `OAuth2ClientAuthenticationFilter#onAuthenticationFailure` 는 이 헤더를 붙이지 않는다(코드에 TODO 로만 남아 있다 — spring-security 이슈 [#18285](https://github.com/spring-projects/spring-security/issues/18285), 미해결. 7.2.0-M1 에서도 동일). 세 practice 모두 `ClientAuthenticationChallengeFailureHandler` 를 새로 작성해 `clientAuthentication(clientAuthentication -> clientAuthentication.errorResponseHandler(...))` 로 걸었다 — official·chat-memory 는 `AuthorizationServerConfig`, community 는 모듈 확장점을 쓰는 `McpAuthorizationStandardConfig` 에서 배선한다. 이 핸들러는 요청의 `Authorization` 헤더에서 클라이언트가 실제로 쓴 스킴 토큰을 꺼내(RFC 7230 §3.2.6 `token` 문법에 맞지 않으면 — 즉 따옴표·공백 등 헤더 주입에 쓰일 문자가 섞이면 — `Basic` 으로 폴백) `<스킴> realm="<issuer>"` 를 실어 보낸다. issuer 는 `AuthorizationServerContextHolder` 에서 얻고, 얻지 못하면 realm 없이 스킴만 돌려준다(RFC 9110 §11.6.1 상 realm 은 챌린지의 필수 파라미터가 아니다). `Authorization` 헤더 없이 인증에 실패한 경우(예: `client_secret_post` 로 폼 파라미터만 보낸 경우)에는 스킴을 알 수 없으므로 헤더를 붙이지 않는다 — RFC 요구가 "Authorization 헤더로 시도한 경우"에 한정되기 때문이다. 테스트: `AuthorizationServerStandardTest#Basic_인증_실패시_스킴에_맞는_WWW_Authenticate_가_실린다`, `#Basic_아닌_스킴으로_인증_실패시_그_스킴이_그대로_반영된다`, `#스킴_토큰에_따옴표가_섞이면_Basic_으로_폴백하고_주입되지_않는다`, `#Authorization_헤더_없이_실패하면_WWW_Authenticate_가_없다`(세 practice).

<a id="s4-8"></a>

### 4.8 인증된 MCP 호출

토큰을 가진 에이전트는 사용자의 채팅 요청을 처리하면서 MCP 세션을 연다. 인가는 HTTP 계층의 일이므로 JSON-RPC 메시지는 인증이 없을 때와 같다. 달라지는 것은 **모든 HTTP 요청**에 붙는 `Authorization` 헤더다.

**[명세]**

- [MCP 2025-11-25 Lifecycle — Initialization](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization)
  - 초기화는 클라이언트와 서버의 첫 상호작용이어야 한다(**MUST**). 클라이언트는 `initialize` 요청에 지원하는 `protocolVersion`, `capabilities`, `clientInfo` 를 담는다.
  - 서버는 자기 `protocolVersion`·`capabilities`·`serverInfo`(선택 `instructions`)로 응답한다(**MUST**). 요청 버전을 지원하면 같은 버전으로 응답해야 한다(**MUST**, [Version Negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#version-negotiation)).
  - 성공한 뒤 클라이언트는 `notifications/initialized` 를 보내야 한다(**MUST**).
- [MCP 2025-11-25 Transports — Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server)
  - 클라이언트가 보내는 JSON-RPC 메시지는 각각 새 HTTP POST 여야 한다(**MUST**).
  - `Accept` 에 `application/json` 과 `text/event-stream` 을 모두 넣어야 한다(**MUST**).
  - 알림·응답을 받아들이면 서버는 본문 없는 `202 Accepted` 를 돌려줘야 한다(**MUST**).
  - 요청에는 `Content-Type: application/json` 이나 `text/event-stream` 으로 응답해야 한다(**MUST**). 클라이언트는 둘 다 처리해야 한다(**MUST**).
- [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)
  - 서버는 `InitializeResult` 를 담은 응답에 `MCP-Session-Id` 헤더로 세션 ID 를 줄 수 있다(MAY).
  - ID 는 전역에서 유일하고 암호학적으로 안전한 것이 좋다(**SHOULD**). 보이는 ASCII(0x21–0x7E)만 써야 한다(**MUST**).
  - 받았다면 클라이언트는 이후 모든 요청에 넣어야 한다(**MUST**).
  - 세션 ID 를 요구하는 서버는 초기화 외의 요청에 ID 가 없으면 400 으로 답하는 것이 좋다(**SHOULD**).
  - 끝난 세션의 ID 로 온 요청에는 404 를 돌려줘야 한다(**MUST**). 404 를 받은 클라이언트는 새 `initialize` 로 새 세션을 시작해야 한다(**MUST**).
  - HTTP 헤더 이름은 대소문자를 구분하지 않는다([RFC 9110 §5.1](https://www.rfc-editor.org/rfc/rfc9110#section-5.1)). 명세 본문의 `MCP-Session-Id` 와 관측의 `Mcp-Session-Id` 는 같은 헤더다.
- [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header)
  - HTTP 를 쓰는 클라이언트는 **이후** 모든 요청에 `MCP-Protocol-Version: <protocol-version>` 을 넣어야 한다(**MUST**). 값은 협상한 버전이 좋다(**SHOULD**).
  - 헤더가 없고 버전을 알 다른 방법도 없으면 서버는 `2025-03-26` 으로 가정하는 것이 좋다(**SHOULD**).
  - 값이 유효하지 않거나 지원하지 않는 버전이면 `400 Bad Request` 로 답해야 한다(**MUST**).
- [MCP 2025-11-25 Transports — Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning)
  - 서버는 모든 연결의 `Origin` 을 검증해야 한다(**MUST**). `Origin` 이 있는데 유효하지 않으면 403 이어야 하며(**MUST**), 본문은 `id` 없는 JSON-RPC 오류일 수 있다(MAY).
  - 로컬 실행 시 127.0.0.1 에만 바인딩하는 것이 좋다(**SHOULD**). 모든 연결에 인증을 두는 것이 좋다(**SHOULD**).
- [MCP 2025-11-25 Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements)
  - `Authorization: Bearer <access-token>` 을 써야 한다(**MUST**, [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1)).
  - 같은 논리 세션에 속하더라도 **모든 HTTP 요청**에 넣어야 한다(**MUST**). URI 쿼리에 넣으면 안 된다(**MUST NOT**).

**다이어그램 ⑥** — MCP 세션. 매 요청에 붙는 헤더를 함께 적었다.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    B->>C: POST /api/chat (에이전트 세션 쿠키)
    Note over C: 로그인한 사용자의 access token 을 꺼낸다<br/>만료됐거나 60초 안에 만료되면 먼저 갱신한다
    C->>M: POST /mcp initialize<br/>Authorization: Bearer, Accept: application/json, text/event-stream<br/>Content-Type: application/json, MCP-Protocol-Version: 2025-11-25
    Note over M: 토큰 검증 (서명, iss, aud, exp)과 Origin, Host 검증
    M-->>C: 200 application/json InitializeResult (protocolVersion 2025-11-25)<br/>Mcp-Session-Id: c46d6911-...
    C->>M: POST /mcp notifications/initialized<br/>Authorization: Bearer, Mcp-Session-Id, MCP-Protocol-Version
    M-->>C: 202 (본문 없음)
    C->>M: GET /mcp (Accept: text/event-stream, Authorization, Mcp-Session-Id)
    Note over M: 서버발 메시지용 SSE 스트림<br/>보낼 메시지가 생길 때까지 응답 헤더가 나가지 않는다
    C->>M: POST /mcp tools/list<br/>Authorization: Bearer, Mcp-Session-Id, MCP-Protocol-Version
    M-->>C: 200 text/event-stream, event: message, data: tools 목록
    C->>M: POST /mcp tools/call getStock(productId=p1)<br/>Authorization: Bearer, Mcp-Session-Id, MCP-Protocol-Version
    M-->>C: 200 text/event-stream, data: 재고 7개
    C-->>B: 모델이 툴 결과로 만든 답변
```

**[관측]** C7 — `initialize`(세 practice 모두 협상 버전 `2025-11-25`).

```http
HTTP/1.1 200
Mcp-Session-Id: c46d6911-2172-4076-a3d6-1d441b9652ce
Content-Type: application/json
Content-Length: 291

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"completions":{},"logging":{},"prompts":{"listChanged":true},"resources":{"subscribe":false,"listChanged":true},"tools":{"listChanged":true}},"serverInfo":{"name":"official-shop-mcp-server","version":"0.0.1"}}}
```

C8 — `notifications/initialized` → `HTTP/1.1 202`, `Content-Length: 0`.

C9 · C10 — `tools/list` 와 `tools/call` 은 SSE 로 온다. `tools/call` 본문은 다음과 같다.

```http
POST /mcp HTTP/1.1
Authorization: Bearer eyJraWQiOiJlMDBiNTY4...
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: c46d6911-2172-4076-a3d6-1d441b9652ce
MCP-Protocol-Version: 2025-11-25

{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getStock","arguments":{"productId":"p1"}}}
```

```http
HTTP/1.1 200
Cache-Control: no-cache
Content-Type: text/event-stream
Transfer-Encoding: chunked

id:c46d6911-2172-4076-a3d6-1d441b9652ce
event:message
data:{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"상품 p1 (게이밍 노트북 15인치) 의 현재 재고는 7개입니다."}],"isError":false}}
```

명세와 대조한 결과:

- 초기화 응답에 세션 ID 가 오고, 알림에 202, 요청에 JSON 또는 SSE 로 응답한다 — 명세대로다.
- SSE 이벤트의 `id` 가 세션 ID 와 같다. 그래서 C9 와 C10 의 두 이벤트가 **같은 `id`** 를 가진다. [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery) 는 SSE 이벤트 `id` 를 붙일 수 있게 하되(MAY), 붙였다면 세션 안의 모든 스트림에서 전역으로 유일해야 한다(**MUST**)고 한다. MCP Java SDK 서버 전송의 동작이 이와 다르다([8절](#s8) 19번). 이 practice 에서 고치지 않은 이유: 이벤트 `id` 를 정하는 `WebMvcStreamableServerTransportProvider#sendMessage`(`this.sseBuilder.id(messageId != null ? messageId : this.sessionId)`)는 `messageId` 를 넘기지 않는 이 SDK 의 호출 경로에서 항상 세션 ID 로 떨어지는데, 그 로직을 쥔 내부 세션 전송 클래스(`WebMvcStreamableMcpSessionTransport`)는 `private` 이고 바깥의 `WebMvcStreamableServerTransportProvider` 자체가 `public final` 이라 상속으로 갈아끼울 수 없다 — 고치려면 이 practice 가 쓰는 2.0.0 기준 838줄짜리 전송 구현 전체를 그대로 복사해 포크해야 한다. 이 문제의 원인이 되는 그 줄(`sseBuilder.id(messageId != null ? messageId : this.sessionId)`, 2.0.0 650행)은 2.0.1(766행)에서도 동일하다 — 다만 이는 그 한 줄에 한정된 확인이고, 전송 구현 전체나 줄 수가 두 버전에서 같다는 뜻은 아니다.
- SSE 스트림을 열 때 보내는 것이 좋다고 한 "이벤트 ID + 빈 `data`" 준비 이벤트(**SHOULD**)는 없다. 첫 이벤트가 곧 응답이다.

전송 계층의 경계 조건(official 만 관측, S 번호):

| 요청 | 관측 | 명세와 대조 |
|---|---|---|
| `MCP-Protocol-Version` 없이 `tools/list` (S10) | `200` SSE 응답 | 헤더가 없으면 서버가 버전을 가정한다(SHOULD) — 거부 사유가 아니다 |
| `MCP-Protocol-Version: 1999-01-01` (C15, 세 practice) | `400`, `{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported MCP-Protocol-Version: 1999-01-01"}}` | MUST 400 과 맞다 |
| `Mcp-Session-Id` 없이 `tools/list` (C14, 세 practice) | `400`, 본문 `{"jsonRpcError":{"code":-32601,"message":"Session ID missing"},...,"stackTrace":[...]}` | SHOULD 400 과 맞다. 본문에 Java 스택트레이스가 노출된다([7절](#s7)) |
| 존재하지 않는 세션 ID (S13) | `404`, `{"jsonRpcError":{"code":-32603,"message":"Session not found: 00000000-..."},...,"stackTrace":[...]}` | MUST 404 와 맞다. 스택트레이스 노출은 같다 |
| `DELETE` 로 끝낸 세션의 ID (S16) | `DELETE` → `200`, 이어진 `tools/list` → `404` | MUST 404 와 맞다 |
| `Accept: application/json` 만 (S15) | `400`, `{"jsonRpcError":{"code":-32601,"message":"Invalid Accept headers. Expected TEXT_EVENT_STREAM and APPLICATION_JSON"},...,"stackTrace":[...]}` | 클라이언트 쪽 MUST 위반 요청을 서버가 거부한다 |
| `Origin: http://evil.example` (C13) | official·chat-memory: `403`, `text/plain`, `Invalid Origin header` / community: `403`, `application/json`, `{"jsonrpc":"2.0","error":{"code":-32000,"message":"Invalid Origin header"},"id":null}` | 두 형태 모두 MUST 403 과 맞다. 본문은 MAY 이므로 형식 차이는 허용된다 |
| `Host: evil.example:8111` (S14) | `421 Misdirected Request`, `text/plain`, `Invalid Host header` | MCP 전송 명세에 Host 규칙은 없다. DNS 리바인딩 방어로 SDK 검증기가 막는다([RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20)) |

JSON-RPC 오류 코드는 [JSON-RPC 2.0 §5.1](https://www.jsonrpc.org/specification#error_object) 의 `-32600`(Invalid Request)·`-32601`(Method not found)·`-32603`(Internal error)과 구현이 정하는 서버 오류 범위(`-32000`~`-32099`)다. 세션 누락에 `-32601` 을 쓰는 것은 SDK 의 선택이다.

community 의 Host 거부는 테스트로 421 상태만 확인했다(`McpAuthorizationStandardTest#허용되지_않은_Host_는_421이다`). 모듈 `OriginValidationFilter` 소스상 본문은 Origin 거부와 같은 JSON-RPC `-32000` 형식이다.

**[구현]**

- 에이전트: MCP Java SDK 의 `HttpClientStreamableHttpTransport` 가 POST 마다 `Accept: application/json, text/event-stream`, `Content-Type: application/json; charset=utf-8`, `Cache-Control: no-cache`, `MCP-Protocol-Version`(협상 버전, 협상 전에는 SDK 가 아는 최신 `2025-11-25`), 세션이 있으면 `Mcp-Session-Id` 를 붙인다. 세션 ID 를 처음 받으면 서버발 메시지를 받으려고 `GET /mcp` 스트림을 연다. `Authorization` 은 HTTP 요청마다 호출되는 요청 커스터마이저가 붙인다.
  - official·chat-memory: `OAuth2TokenAttachingRequestCustomizer` 가 `SecurityMcpTransportContextProvider` 가 담아 둔 인증으로 토큰을 꺼낸다.
  - community: 모듈 `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` 가 같은 일을 한다.

  토큰은 로그인한 **사용자**의 것이다(`sub` 가 사용자). 에이전트 자신의 토큰(client credentials)을 쓰지 않는다. 테스트: `OAuth2TokenAttachingRequestCustomizerTest#인증이_있으면_토큰을_Bearer_로_헤더에_붙인다`(official·chat-memory).
- MCP 서버: Spring AI 의 `WebMvcStreamableServerTransportProvider` 가 세션을 만들고(`Mcp-Session-Id` 는 UUID) 400/404 를 판단한다.
  - `McpProtocolVersionFilter` 가 SDK 가 하지 않는 `MCP-Protocol-Version` 검증을 한다. 헤더가 없으면 통과, SDK 가 아는 버전(`2024-11-05`~`2025-11-25`)이 아니면 400 이다.
  - `Origin`·`Host` 는 official·chat-memory 에서 SDK `DefaultServerTransportSecurityValidator` 가, community 에서 모듈 `OriginValidationFilter` 가 검증한다. `Origin` 이 없는 요청(서버 간 호출)은 통과한다.
  - 두 검증의 순서가 practice 마다 반대다. official·chat-memory 는 `McpProtocolVersionFilter` 가 서블릿 필터로 먼저 걸리고 `Origin`/`Host` 검증은 그 뒤(전송 빈 안)에서 일어난다. community 는 `OriginValidationFilter` 가 시큐리티 필터 체인 안에서 먼저 걸리고 `MCP-Protocol-Version` 검증은 그 뒤다. 그래서 지원하지 않는 프로토콜 버전과 허용되지 않은 `Origin` 을 한 요청에 같이 실으면 official·chat-memory 는 `400`(프로토콜 버전 위반이 먼저 걸림), community 는 `403`(Origin 위반이 먼저 걸림)이 된다.
  - 허용 Origin 은 official·chat-memory 가 없음(Origin 이 실리면 전부 403), community 가 `http://localhost:8101` 이다.
  - 테스트(세 practice): `McpAuthorizationStandardTest#허용되지_않은_Origin_은_403이다`, `#허용되지_않은_Host_는_421이다`, `#Origin_없는_서버간_요청은_통과한다`, `#지원하는_MCP_Protocol_Version_헤더는_통과한다`, `#지원하지_않는_MCP_Protocol_Version_헤더는_400이다`, `#MCP_Protocol_Version_헤더가_없으면_명세대로_통과한다`.

<a id="s4-9"></a>

### 4.9 MCP 서버의 토큰 검증

**[명세]**

- [MCP 2025-11-25 Authorization — Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling)
  - MCP 서버는 [OAuth 2.1 §5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.2) 대로 access token 을 검증해야 한다(**MUST**).
  - 토큰이 **자기를 audience 로** 발급됐는지 확인해야 한다(**MUST**, RFC 8707 §2).
  - 검증에 실패하면 OAuth 2.1 §5.3 대로 응답해야 한다(**MUST**). 유효하지 않거나 만료된 토큰에는 401 이어야 한다(**MUST**).
  - 자기 리소스에 유효한 토큰만 받아야 하고(**MUST**), 다른 토큰은 받거나 전달하면 안 된다(**MUST NOT**).
- [MCP 2025-11-25 Authorization — Access Token Privilege Restriction](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-privilege-restriction) — 요청을 처리하기 **전에** 검증해야 하고(**MUST**), audience 에 자기가 없는 토큰은 거부해야 한다(**MUST**).
- [OAuth 2.1 §5.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.2) — 리소스 서버는 토큰이 만료되지 않았는지, 요청한 리소스에 권한이 있는지, 알맞은 scope 로 발급됐는지, 그 밖의 정책을 만족하는지 확인해야 한다(**MUST**). 자기 포함형 토큰의 표준 형식으로 RFC 9068 을 든다.
- [RFC 9068 §4](https://www.rfc-editor.org/rfc/rfc9068#section-4) — JWT access token 검증.
  - `typ` 가 `at+jwt` 또는 `application/at+jwt` 인지 확인한다(**MUST**).
  - `iss` 가 issuer 와 정확히 같은지 확인한다(**MUST**).
  - `aud` 에 자기 리소스 식별자가 있는지 확인한다(**MUST**).
  - 인가 서버의 키로 서명을 검증하고 `alg: none` 은 거부한다(**MUST**).
  - 현재 시각이 `exp` 이전인지 확인한다(**MUST**, 몇 분 이내의 허용 오차 MAY).
  - 실패하면 `invalid_token` 이다.
- [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) — `invalid_token` 은 401 이다(**SHOULD**). 클라이언트는 새 토큰을 받아 재시도할 수 있다(MAY).

| 검증 항목 | 명세 | official · chat-memory | community | 관측 |
|---|---|---|---|---|
| 서명 | RFC 9068 §4 MUST | `issuer-uri` 로 인가 서버 메타데이터를 읽어 `jwks_uri` 의 키로 검증(Spring Boot `NimbusJwtDecoder.withIssuerLocation`) | Boot 가 만든 같은 디코더를 모듈에 넘긴다 | S3: `not-a-jwt` → 401 `invalid_token`, `Malformed token` |
| `iss` | MUST 정확히 일치 | `JwtIssuerValidator(issuer-uri)` | 같음 | 테스트: `McpAuthorizationStandardTest#iss_가_다른_토큰은_거부한다`(세 practice) |
| `aud` | MCP MUST, RFC 9068 MUST | `spring.security.oauth2.resourceserver.jwt.audiences: http://localhost:8111/mcp` → `aud` 에 이 값이 있어야 한다 | 모듈 `AudienceValidationJwtDecoder` → `JwtResourceValidator` 가 **요청 URL** 로 계산한 `http://localhost:8101/mcp`(`ResourceIdentifier`)가 `aud` 에 있어야 한다 | C12: `aud` 가 client_id 인 id_token → 401 `The aud claim is not valid`(세 practice) |
| `exp`·`nbf` | MUST | Spring Security `JwtTimestampValidator`(허용 오차 60초) | 같음 | 캡처 없음 |
| `typ` | RFC 9068 MUST `at+jwt` | Spring Security `JwtTypeValidator.jwt()` — `typ` 가 없거나 `JWT` 일 때만 통과. `at+jwt` 토큰은 거부된다 | 같음 | C7: `typ` 없는 토큰이 통과 |
| scope | OAuth 2.1 §5.2 MUST(알맞은 scope) | 검사하지 않는다. 인증된 요청이면 모든 툴을 허용 | 같음 | — |

C12 — `aud` 가 다른 토큰(official):

```http
POST /mcp HTTP/1.1
Authorization: Bearer eyJraWQiOiJlMDBiNTY4...   (id_token, aud = official-shop-agent)

HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1", resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
Content-Length: 0
```

명세와 대조한 결과:

- 서명이 유효하고 `iss` 도 맞는 같은 인가 서버의 토큰이라도 `aud` 가 다르면 401 이다 — MCP 의 audience MUST 를 만족한다.
- 이 id_token 은 RFC 9068 을 따르는 리소스 서버라면 `typ` 검사에서도 걸렸을 토큰이다. RFC 9068 §5 가 ID Token 을 access token 으로 받아들이지 않도록 경고하는 바로 그 경우다. 이 practice 들은 `aud` 검사로 막는다.
- 오류 응답에도 `resource_metadata` 가 남아 있어, 클라이언트는 이 응답만 보고도 발견을 다시 할 수 있다.

**[구현]** 위 표 참고. 요청 URL 로 기대 audience 를 계산하는 community 방식은 `Host` 에 따라 기대값이 바뀐다. 허용 Host 인 `127.0.0.1:8101` 로 부르면 기대값이 `http://127.0.0.1:8101/mcp` 가 되어 `localhost` 로 발급된 토큰이 거부된다(소스 확인, 캡처 없음). 거부되는 쪽으로 어긋나므로 안전하지만, official 처럼 기대값을 설정으로 고정하는 방식이 canonical URI 하나를 기준으로 삼는다는 명세의 뜻에 더 가깝다.

<a id="s4-10"></a>

### 4.10 만료와 refresh

access token 수명은 300초다(C6-1, `expires_in: 299`). 5분이 지나면 새 토큰이 필요하다.

**[명세]**

- [OAuth 2.1 §4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1) · [RFC 6749 §6](https://www.rfc-editor.org/rfc/rfc6749#section-6)
  - `grant_type=refresh_token`(REQUIRED), `refresh_token`(REQUIRED), `scope`(OPTIONAL — 원래 허가된 범위를 넘으면 안 되고 생략하면 원래 범위)을 보낸다.
  - confidential client 는 인증해야 한다(**MUST**).
  - 인가 서버는 refresh token 과 클라이언트의 묶임, 그랜트가 살아 있는지, refresh token 자체를 검증해야 한다(**MUST**).
  - public client 에는 refresh token 회전이나 sender-constrained 토큰을 써야 한다(**MUST**).
- [OAuth 2.1 §4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2) — 인가 서버는 새 refresh token 을 줄 수 있고(MAY), 그러면 클라이언트는 옛것을 버려야 한다(**MUST**).
- [OAuth 2.1 §3.2.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.3) — refresh token 은 자원 소유자가 동의한 scope 와 리소스 서버에 묶여야 한다(**MUST**). 클라이언트는 `expires_in` 동안 토큰이 반드시 유효하리라 기대하면 안 된다(**MUST NOT**).
- [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) · [MCP Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation) — `resource` 는 모든 그랜트의 토큰 요청에 쓸 수 있다. MCP 는 토큰 요청에 `resource` 를 요구하므로(**MUST**) 갱신 요청에도 싣는다.
- [MCP 2026-07-28 Authorization — Refresh Tokens](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#refresh-tokens)
  - 클라이언트는 refresh token 을 전송·저장 중 기밀로 유지해야 한다(**MUST**).
  - 클라이언트 메타데이터 `grant_types` 에 `refresh_token` 을 넣는 것이 좋다(**SHOULD**). 인가 서버의 `scopes_supported` 에 `offline_access` 가 있으면 요청할 수 있다(MAY).
  - refresh token 이 발급되리라 가정하면 안 된다(**MUST NOT**).
  - MCP 서버는 `offline_access` 를 챌린지나 `scopes_supported` 에 넣지 않는 것이 좋다(**SHOULD NOT**).
- [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) — `invalid_token` 을 받은 클라이언트는 새 access token 을 받아 재시도할 수 있다(MAY).

**다이어그램 ⑦** — 만료와 갱신. 이 practice 는 보내기 전에 만료를 확인하는 첫 갈래를 쓴다. 두 번째 갈래는 명세가 허용하는 다른 방식이다.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    B->>C: POST /api/chat (5분 뒤)
    alt 이 practice: MCP 요청을 보내기 전에 만료를 확인
        Note over C: access token 이 만료됐거나 60초 안에 만료된다
        C->>A: POST /oauth2/token (Basic 인증)<br/>grant_type=refresh_token, refresh_token,<br/>resource=http://localhost:8111/mcp
        Note over A: refresh_token 이 이 클라이언트 것인가<br/>resource 가 원래 인가의 resource 와 같은가
        A-->>C: 200 새 access_token (aud 는 그대로 MCP 서버),<br/>refresh_token, id_token, expires_in=299
        C->>M: POST /mcp tools/call (Authorization: Bearer 새 토큰)
        M-->>C: 200
    else 명세가 허용하는 다른 방식: 401 을 받고 갱신
        C->>M: POST /mcp tools/call (Authorization: Bearer 만료된 토큰)
        M-->>C: 401 WWW-Authenticate: Bearer error="invalid_token", resource_metadata="..."
        C->>A: POST /oauth2/token grant_type=refresh_token, refresh_token, resource
        A-->>C: 200 새 access_token
        C->>M: POST /mcp tools/call (새 토큰으로 재시도)
        M-->>C: 200
    end
    Note over C,A: refresh 도 실패하면(만료, 폐기) 인가 코드 흐름부터 다시 시작해야 한다
```

**[관측]** C11 — 캡처 스크립트의 갱신 요청과 응답(official).

```http
POST /oauth2/token HTTP/1.1
Authorization: Basic <base64(official-shop-agent:official-shop-agent-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=Cog6N7Fv1qyO...&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

```json
{"access_token":"eyJraWQiOiJlMDBiNTY4...","refresh_token":"Cog6N7Fv1qyO...","scope":"openid profile","id_token":"eyJraWQiOiJlMDBiNTY4...","token_type":"Bearer","expires_in":299}
```

새 access token 의 페이로드:

```json
{"sub":"user","aud":"http://localhost:8111/mcp","nbf":1789312661,"scope":["openid","profile"],"iss":"http://localhost:9010","exp":1789312961,"iat":1789312661,"jti":"011c8498-d17c-45a2-b8cb-7cf16d9be7a7"}
```

- 새 토큰의 `aud` 가 그대로 MCP 서버다(세 practice). `jti` 가 달라 새로 발급된 토큰임을 알 수 있다.
- 응답의 `refresh_token` 은 처음 받은 값과 **완전히 같다**(세 practice, 캡처 원본 비교). 인가 서버가 refresh token 을 회전하지 않는다. OAuth 2.1 은 회전을 public client 에만 MUST 로 요구하므로, confidential client 인 이 practice 에서는 허용되는 동작이다.

**[구현]**

- 에이전트: MCP HTTP 요청마다 토큰 부착 커스터마이저가 `OAuth2AuthorizedClientManager#authorize` 를 부른다.
  - official·chat-memory 는 `AuthorizedClientServiceOAuth2AuthorizedClientManager`, community 는 `DefaultOAuth2AuthorizedClientManager` 를 쓴다. 두 매니저 모두 `McpSecurityConfig` 에서 refresh provider 에 `RestClientRefreshTokenTokenResponseClient` + `ResourceIndicators.tokenRequest(...)` 를 넣는다.
  - Spring Security 의 refresh provider 는 access token 이 만료됐거나 60초(clock skew) 안에 만료되면 갱신한다. 그래서 만료된 토큰이 MCP 서버로 나가기 전에 새 토큰으로 바뀐다.
  - 401 을 받은 뒤 재시도하는 로직은 없다.

  테스트: `TokenRefreshTest#만료된_토큰을_resource_를_실어_갱신한다`(세 practice).
- 인가 서버: refresh_token 그랜트에서도 `ResourceAudienceTokenCustomizer` 가 같은 규칙을 적용한다. 원래 인가의 `resource` 는 저장된 인가에 남아 있다. 테스트: `AuthorizationServerStandardTest#refresh_로_받은_access_token_도_같은_aud_다`.
- 발견 결과: 에이전트는 실행 중에 받은 401 로 PRM 을 다시 읽지 않는다. RFC 9728 §5.2 는 리소스 서버가 새 챌린지로 메타데이터 변경을 알리면 클라이언트가 다시 받아 검증하는 것이 좋다(SHOULD)고 한다. 이 practice 는 발견 결과를 프로세스 수명 동안 캐시한다([8절](#s8) 21번).

<a id="s4-11"></a>

### 4.11 오류 응답 모음

| # | 상황 | 응답하는 쪽 | 관측한 응답 | 명세 | 출처 |
|---|---|---|---|---|---|
| 1 | 토큰 없음 | MCP 서버 | `401`, `WWW-Authenticate: Bearer resource_metadata="..."`, 본문 없음 | MCP 401 · RFC 6750 §3 MUST, §3.1 오류 정보 SHOULD NOT | C1 (세 practice) |
| 2 | 형식이 잘못된 토큰 | MCP 서버 | `401`, `Bearer error="invalid_token", error_description="...Malformed token", error_uri="...", resource_metadata="..."` | MCP: 유효하지 않은 토큰 MUST 401 · RFC 6750 §3.1 | S3 |
| 3 | `aud` 가 다른 토큰 | MCP 서버 | `401`, `error="invalid_token"`, `The aud claim is not valid` | MCP Token Handling MUST | C12 (세 practice) |
| 4 | `iss` 가 다른 토큰 | MCP 서버 | `401` | RFC 9068 §4 MUST | 테스트 `McpAuthorizationStandardTest#iss_가_다른_토큰은_거부한다` |
| 5 | 만료된 토큰 | MCP 서버 | 관측 없음 (에이전트가 보내기 전에 갱신한다) | MCP: 만료 토큰 MUST 401 | — |
| 6 | scope 부족 | MCP 서버 | 이 practice 에서는 일어나지 않음 (scope 검사 없음) | MCP: `403` + `error="insufficient_scope"`, `scope`, `resource_metadata` SHOULD ([Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling)) | — |
| 7 | 허용되지 않은 `Origin` | MCP 서버 | official·chat-memory `403 text/plain Invalid Origin header` / community `403` JSON-RPC `-32000` | 전송 명세 MUST 403, 본문 MAY | C13 |
| 8 | 허용되지 않은 `Host` | MCP 서버 | `421 text/plain Invalid Host header` (official) / community `421`(본문 미관측) | RFC 9110 §15.5.20 (MCP 전송 명세에 규칙 없음) | S14, 테스트 |
| 9 | POST 에 `Mcp-Session-Id` 없음 | MCP 서버 | `400`, `McpError` 직렬화 + 스택트레이스 | 전송 명세 SHOULD 400 | C14 (세 practice) |
| 10 | GET 에 `Mcp-Session-Id` 없음 | MCP 서버 | `400 text/plain Session ID required in mcp-session-id header` | 전송 명세 SHOULD 400 | S12 |
| 11 | 없거나 끝난 세션 | MCP 서버 | `404`, `McpError(-32603)` + 스택트레이스 | 전송 명세 MUST 404 | S13, S16 |
| 12 | 지원하지 않는 `MCP-Protocol-Version` | MCP 서버 | `400`, `{"jsonrpc":"2.0","id":null,"error":{"code":-32600,...}}` | 전송 명세 MUST 400 | C15 (세 practice) |
| 13 | `Accept` 에 `text/event-stream` 없음 | MCP 서버 | `400`, `McpError(-32601)` + 스택트레이스 | 클라이언트 MUST (서버 응답은 규정 없음) | S15 |
| 14 | 인가 요청에 PKCE 없음 | 인가 서버 | `302 redirect_uri?error=invalid_request&...&state&iss` | RFC 7636 §4.4.1 MUST · OAuth 2.1 §4.1.2.1 | C17 (세 practice) |
| 15 | 인가 요청의 `resource` 가 허용 목록 밖 | 인가 서버 | `302 redirect_uri?error=invalid_target&...&state&iss` | RFC 8707 §2.1 | C16 (세 practice) |
| 16 | 인가 요청의 `redirect_uri` 가 등록값과 다름 | 인가 서버 | `400`, 리다이렉트 없음 | RFC 6749 §4.1.2.1 MUST NOT 리다이렉트 | S4 |
| 17 | 토큰 요청의 `resource` 가 인가 요청과 다름 | 인가 서버 | `400 {"error":"invalid_target",...}` | RFC 8707 §2.2 | S6, 테스트 |
| 18 | `code_verifier` 불일치 | 인가 서버 | `400 {"error":"invalid_grant"}` | RFC 7636 §4.6 MUST | S7 |
| 19 | 클라이언트 인증 실패(Basic) | 인가 서버 | `401`, `WWW-Authenticate: Basic realm="http://localhost:9010"`, `{"error":"invalid_client"}` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4: 401 **과 `WWW-Authenticate`** MUST 와 맞다 | S8 |
| 20 | 콜백의 `iss` 불일치 | 에이전트 | `401 text/plain 로그인 실패: iss mismatch: ...` | RFC 9207 §2.4 MUST 거부 · MCP 2026-07-28 | S18 |
| 21 | 콜백에 `iss` 없음(인가 서버가 광고함) | 에이전트 | `401 text/plain 로그인 실패: iss is missing ...` | RFC 9207 §2.4 MUST 거부 · MCP 2026-07-28 | S19 |
| 22 | 발견 실패 — PRM `resource` 불일치, 메타데이터 `issuer` 불일치, `S256` 미지원, 자격증명 issuer 불일치 | 에이전트 | `McpDiscoveryException` — 인가 요청 URL 을 만들지 않는다 | RFC 9728 §3.3 · RFC 8414 §3.3 · MCP PKCE MUST · MCP 2026-07-28 Authorization Server Binding | 테스트 `McpAuthorizationDiscoveryTest`, `DiscoveredClientRegistrationRepositoryTest` |

---

<a id="s5"></a>

## 5. 엔드포인트 명세

흐름에 등장하는 HTTP 엔드포인트 10개를 명세 기준으로 정리한다. 각 표는 근거 문서가 정의한 파라미터·헤더·응답 필드를 **전부** 싣는다. 이 practice 가 쓰지 않는 항목도 남겨 두었다.

열의 뜻:

- **표시** — 원문의 요구 수준. 괄호 안은 근거 조항. 두 문서가 다르게 규정하면 둘 다 적는다.
- **이 practice** — `씀` / `씀(조건)` / `이 practice 에서는 쓰지 않음`. 세 practice 가 다르면 나눠 적는다.
- **관측** — 캡처 단계 번호와 값. `—` 는 캡처에 없음.

| # | 엔드포인트 | 주체 | 근거 |
|---|---|---|---|
| [E1](#e1) | `POST /mcp` (토큰 없음) | 에이전트 → MCP 서버 | MCP 인가, RFC 6750 §3, RFC 9728 §5.1, RFC 9110 §11.2 |
| [E2](#e2) | `GET /.well-known/oauth-protected-resource[/path]` | 에이전트 → MCP 서버 | RFC 9728 §2, §3 |
| [E3](#e3) | `GET /.well-known/oauth-authorization-server` | 에이전트 → 인가 서버 | RFC 8414 §2, §3, RFC 9207 §3 |
| [E4](#e4) | `GET /.well-known/openid-configuration` | 에이전트 → 인가 서버 | OpenID Connect Discovery 1.0 §3, §4 |
| [E5](#e5) | `GET /oauth2/authorize` | 브라우저 → 인가 서버 | RFC 6749 §4.1.1, OAuth 2.1 §4.1.1, RFC 7636 §4.3, RFC 8707 §2.1 |
| [E6](#e6) | 인가 응답 (리다이렉트) | 인가 서버 → 브라우저 → 에이전트 | RFC 6749 §4.1.2, §4.1.2.1, OAuth 2.1 §4.1.2, RFC 9207 §2 |
| [E7](#e7) | `POST /oauth2/token` (authorization_code) | 에이전트 → 인가 서버 | RFC 6749 §4.1.3, §5.1, §5.2, OAuth 2.1 §3.2, §4.1.3, RFC 7636 §4.5, RFC 8707 §2.2 |
| [E8](#e8) | `POST /oauth2/token` (refresh_token) | 에이전트 → 인가 서버 | RFC 6749 §6, OAuth 2.1 §4.3, RFC 8707 §2.2 |
| [E9](#e9) | `POST /mcp` (Bearer) | 에이전트 → MCP 서버 | MCP 전송(Streamable HTTP), MCP 인가, RFC 6750 §2.1 |
| [E10](#e10) | `DELETE /mcp`, `GET /mcp` | 에이전트 → MCP 서버 | MCP 전송(Streamable HTTP) |

<a id="e1"></a>

### 5.1 E1 — `POST /mcp` (토큰 없음)

| 항목 | 내용 |
|---|---|
| URL | official `http://localhost:8111/mcp` · chat-memory `http://localhost:8131/mcp` · community `http://localhost:8101/mcp` |
| 목적 | 보호 여부 확인과 PRM 위치 발견. 에이전트는 발견 탐침으로 보낸다 |
| 근거 | [MCP 2025-11-25 Authorization — Protected Resource Metadata Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements) · [Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling) · [RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3), [§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) · [RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1) · [RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2) |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| `Authorization` | 헤더 | MUST — 모든 HTTP 요청 ([MCP Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements)) | 토큰이 아직 없으므로 이 요청에는 없다. 없을 때의 서버 동작이 이 엔드포인트의 내용이다 | 없음(발견 탐침) | C1 없음 |
| `Content-Type`, `Accept`, 본문 | 헤더·본문 | [E9](#e9) 와 같음 | official·chat-memory 에이전트는 `initialize` JSON 을, community 모듈은 빈 본문을 보낸다. 서버는 본문을 읽기 전에 401 을 준다 | 씀 | C1 `initialize` |

**응답**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| `401 Unauthorized` | 상태 | MUST ([MCP Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling): 인가 필요 또는 토큰 무효) | | 씀 | C1 `401` (세 practice) |
| `WWW-Authenticate` | 헤더 | MUST ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 스킴 `Bearer` + auth-param 하나 이상 | 씀 | C1 |
| `realm` | auth-param | MAY, 한 번만 ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 보호 범위 이름 | 이 practice 에서는 쓰지 않음 | C1 없음 |
| `scope` | auth-param | OPTIONAL ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) · SHOULD ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | 필요한 scope 목록(공백 구분). 클라이언트는 이 값을 이번 요청에 대한 권위 있는 값으로 다뤄야 한다(MUST) | 이 practice 에서는 쓰지 않음 (scope 범위 밖) | C1 없음 |
| `error` | auth-param | 인증 시도가 실패했으면 SHOULD, 인증 정보가 아예 없으면 SHOULD NOT ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3), [§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1)) | `invalid_request`(400) · `invalid_token`(401) · `insufficient_scope`(403) | 씀(토큰이 있고 실패했을 때만) | C1 없음 · C12, S3 `invalid_token` |
| `error_description` | auth-param | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 개발자용 설명 | 씀(조건 같음) | C12 `...The aud claim is not valid` |
| `error_uri` | auth-param | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 설명 페이지의 절대 URI | 씀(조건 같음) | C12 `https://tools.ietf.org/html/rfc6750#section-3.1` |
| `resource_metadata` | auth-param | 표시 없음(파라미터 정의, [RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1)) · 서버는 이것과 well-known URI 중 하나를 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | PRM URL. `:`·`/` 를 담으므로 quoted-string([RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2)) | 씀 | C1 `resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"` |
| 본문 | 본문 | 규정 없음 | | 비어 있음 | C1 `Content-Length: 0` |

<a id="e2"></a>

### 5.2 E2 — `GET /.well-known/oauth-protected-resource[/path]`

| 항목 | 내용 |
|---|---|
| URL | 경로형 `http://localhost:8111/.well-known/oauth-protected-resource/mcp` (C2) · 루트형 `http://localhost:8111/.well-known/oauth-protected-resource` (S2) |
| 목적 | 리소스 식별자와 인가 서버 위치 |
| 근거 | [RFC 9728 §2](https://www.rfc-editor.org/rfc/rfc9728#section-2), [§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2), [§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3) · [MCP Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location) |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| 메서드 `GET` | 요청줄 | MUST ([§3.1](https://www.rfc-editor.org/rfc/rfc9728#section-3.1)) | | 씀 | C2, S2 |
| 경로 | URL | MUST ([§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)) | 리소스 식별자의 host 와 path 사이에 `/.well-known/oauth-protected-resource` 삽입. 경로가 있으면 host 뒤 끝 `/` 제거 | 씀 | C2 `/.well-known/oauth-protected-resource/mcp` |

**응답**

| 이름 | 위치 | 표시 | 설명 | official · chat-memory | community | 관측 |
|---|---|---|---|---|---|---|
| `200 OK`, `Content-Type: application/json` | 상태·헤더 | MUST ([§3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2)) | 값이 없는 파라미터는 빼야 한다(MUST), 모르는 파라미터는 무시(MUST) | 씀 | 씀 | C2 |
| `resource` | 필드 | REQUIRED | 리소스 식별자. 메타데이터 URL 을 만든 식별자·요청 URL 과 같아야 한다([§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3)) | 씀 | 씀 | C2 `http://localhost:8111/mcp` · S2 `http://localhost:8111` |
| `authorization_servers` | 필드 | OPTIONAL (RFC 9728) · MUST, 하나 이상 (MCP) | 인가 서버 issuer 식별자 배열 | 씀 | 씀 | C2 `["http://localhost:9010"]` |
| `jwks_uri` | 필드 | OPTIONAL | 리소스 서버 자신의 JWK Set(응답 서명 등), https MUST | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `scopes_supported` | 필드 | RECOMMENDED | 이 리소스에 쓰는 scope 배열 | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `bearer_methods_supported` | 필드 | OPTIONAL | `header`·`body`·`query` 중 지원하는 전달 방식 | 씀 | 씀 | C2 `["header"]` |
| `resource_signing_alg_values_supported` | 필드 | OPTIONAL | 리소스 응답 서명용 JWS 알고리즘. `none` 금지 | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `resource_name` | 필드 | RECOMMENDED | 사용자에게 보일 이름. `#언어태그` 로 다국어 가능([§2.1](https://www.rfc-editor.org/rfc/rfc9728#section-2.1)) | 이 practice 에서는 쓰지 않음 | 씀 | community C2 `"shop-mcp-server"` |
| `resource_documentation` | 필드 | OPTIONAL | 개발자 문서 URL | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `resource_policy_uri` | 필드 | OPTIONAL | 데이터 사용 정책 URL | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `resource_tos_uri` | 필드 | OPTIONAL | 이용 약관 URL | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `tls_client_certificate_bound_access_tokens` | 필드 | OPTIONAL, 기본 `false` | mTLS 인증서에 묶인 토큰(RFC 8705) 지원 여부. [4.2 각주](#s4-2) 참고 | 씀(`false`) | 씀(`false`) | C2 `false` |
| `authorization_details_types_supported` | 필드 | OPTIONAL | RFC 9396 `authorization_details` 타입 목록 | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `dpop_signing_alg_values_supported` | 필드 | OPTIONAL | DPoP proof JWT 검증용 알고리즘(RFC 9449) | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `dpop_bound_access_tokens_required` | 필드 | OPTIONAL, 기본 `false` | DPoP 토큰만 받는가 | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `signed_metadata` | 필드 | OPTIONAL ([§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2)) | 메타데이터를 클레임으로 담은 서명 JWT. 지원하는 수신자에게는 평문 값보다 우선(MUST) | 이 practice 에서는 쓰지 않음 | 이 practice 에서는 쓰지 않음 | 없음 |

<a id="e3"></a>

### 5.3 E3 — `GET /.well-known/oauth-authorization-server`

| 항목 | 내용 |
|---|---|
| URL | `http://localhost:9010/.well-known/oauth-authorization-server` (issuer 에 경로가 없으므로 경로 삽입 없음) |
| 목적 | 인가 서버 엔드포인트와 지원 기능 |
| 근거 | [RFC 8414 §2](https://www.rfc-editor.org/rfc/rfc8414#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1), [§3](https://www.rfc-editor.org/rfc/rfc8414#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3) · [RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3) · [MCP Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery) · [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) |

**요청** — `GET`(MUST, [§3.1](https://www.rfc-editor.org/rfc/rfc8414#section-3.1)). 경로는 issuer 의 host 와 path 사이에 `/.well-known/oauth-authorization-server` 를 끼운다. 관측: C3.

**응답** — `200 OK` + `application/json`(MUST, [§3.2](https://www.rfc-editor.org/rfc/rfc8414#section-3.2)). 값이 없는 배열은 빼야 한다(MUST). 받은 쪽은 `issuer` 가 요청에 쓴 issuer 와 같은지 확인하고, 다르면 쓰면 안 된다(MUST NOT, [§3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3)). 세 practice 의 필드 구성은 같다(C3).

| 필드 | 표시 | 설명 | 이 practice | 관측 (official C3) |
|---|---|---|---|---|
| `issuer` | REQUIRED | https, query·fragment 없음 | 씀 | `http://localhost:9010` |
| `authorization_endpoint` | 인가 엔드포인트를 쓰는 그랜트가 없을 때를 빼고 REQUIRED | | 씀 | `http://localhost:9010/oauth2/authorize` |
| `token_endpoint` | implicit 만 지원할 때를 빼고 REQUIRED | | 씀 | `http://localhost:9010/oauth2/token` |
| `jwks_uri` | OPTIONAL | 인가 서버 서명 키. https MUST | 씀 (MCP 서버가 토큰 서명 검증에 사용) | `http://localhost:9010/oauth2/jwks` |
| `registration_endpoint` | OPTIONAL | DCR(RFC 7591) 엔드포인트 | 이 practice 에서는 쓰지 않음 | 없음 |
| `scopes_supported` | RECOMMENDED | | 이 practice 에서는 쓰지 않음 | 없음 (OIDC 문서에는 있음, E4) |
| `response_types_supported` | REQUIRED | | 씀 | `["code"]` |
| `response_modes_supported` | OPTIONAL, 기본 `["query","fragment"]` | | 이 practice 에서는 쓰지 않음 | 없음 |
| `grant_types_supported` | OPTIONAL, 기본 `["authorization_code","implicit"]` | 서버 전체 지원 그랜트 | 광고됨 (클라이언트는 `authorization_code`·`refresh_token` 만 등록) | `["authorization_code","client_credentials","refresh_token","urn:ietf:params:oauth:grant-type:token-exchange"]` |
| `token_endpoint_auth_methods_supported` | OPTIONAL, 기본 `client_secret_basic` | | 씀 (`client_secret_basic`) | 6개 방식 |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL — 단 `private_key_jwt`·`client_secret_jwt` 를 광고하면 **MUST** 포함 | | 씀 — 두 방식을 광고하므로 조건부 MUST 대상([8절](#s8) 20번) | `["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"]` |
| `service_documentation` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 없음 |
| `ui_locales_supported` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 없음 |
| `op_policy_uri` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 없음 |
| `op_tos_uri` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 없음 |
| `revocation_endpoint` | OPTIONAL | RFC 7009 | 이 practice 에서는 쓰지 않음 | `http://localhost:9010/oauth2/revoke` |
| `revocation_endpoint_auth_methods_supported` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 6개 방식 |
| `revocation_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 광고하면 **MUST** 포함 | | 씀 — 조건부 MUST 대상([8절](#s8) 20번) | `["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"]` |
| `introspection_endpoint` | OPTIONAL | RFC 7662 | 이 practice 에서는 쓰지 않음 | `http://localhost:9010/oauth2/introspect` |
| `introspection_endpoint_auth_methods_supported` | OPTIONAL | | 이 practice 에서는 쓰지 않음 | 6개 방식 |
| `introspection_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 광고하면 **MUST** 포함 | | 씀 — 조건부 MUST 대상([8절](#s8) 20번) | `["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"]` |
| `code_challenge_methods_supported` | OPTIONAL (RFC 8414, 없으면 PKCE 미지원) · 클라이언트는 이 필드를 확인하고 없으면 진행 거부 MUST (MCP) | | 씀 | `["S256"]` |
| `signed_metadata` | OPTIONAL ([§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1)) | | 이 practice 에서는 쓰지 않음 | 없음 |
| `authorization_response_iss_parameter_supported` | 표시 없음, 생략 시 `false` ([RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3)) · `iss` 를 보내는 서버는 `true` MUST ([RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3)) | | 씀 | `true` |
| `client_id_metadata_document_supported` | OPTIONAL ([draft-ietf-oauth-client-id-metadata-document-00 §5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5)) | CIMD 지원 여부 | 이 practice 에서는 쓰지 않음 | 없음 |

RFC 8414 §2 밖에서 정의되어 관측된 필드:

| 필드 | 정의 | 관측 |
|---|---|---|
| `tls_client_certificate_bound_access_tokens` | [RFC 8705 §3.3](https://www.rfc-editor.org/rfc/rfc8705#section-3.3) OPTIONAL, 기본 `false` — 인가 서버의 mTLS 인증서 바인딩 토큰 발급 지원 | `true` |
| `dpop_signing_alg_values_supported` | [RFC 9449 §5.1](https://www.rfc-editor.org/rfc/rfc9449#section-5.1) — 인가 서버가 지원하는 DPoP proof JWT 알고리즘 | `["RS256", ..., "ES512"]` 9개 |

<a id="e4"></a>

### 5.4 E4 — `GET /.well-known/openid-configuration`

| 항목 | 내용 |
|---|---|
| URL | `http://localhost:9010/.well-known/openid-configuration` |
| 목적 | OpenID Provider 메타데이터. MCP 클라이언트는 RFC 8414 문서가 없을 때 이것을 시도한다 |
| 근거 | [OpenID Connect Discovery 1.0 §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata), [§4](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig), [§4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation) · [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) · [RFC 8414 §5](https://www.rfc-editor.org/rfc/rfc8414#section-5) |

**요청** — `GET`. issuer 에 경로가 있으면 OIDC 는 경로 **뒤에** `/.well-known/openid-configuration` 을 붙인다(RFC 8414 는 앞에 끼운다). MCP 는 경로 있는 issuer 에 대해 둘 다 시도한다([4.3](#s4-3)). 관측: S1.

**응답** — `200` + JSON. `issuer` 는 요청에 쓴 Issuer URL 과 같아야 하고, 이 OP 가 발급한 ID Token 의 `iss` 와도 같아야 한다(MUST, [§4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation)).

RFC 8414 와의 핵심 차이는 다음과 같다.

- **REQUIRED 가 더 많다**: `jwks_uri`, `authorization_endpoint`, `subject_types_supported`, `id_token_signing_alg_values_supported`.
- **`code_challenge_methods_supported` 를 정의하지 않는다.** 그래서 MCP 가 "OIDC Discovery 를 제공하는 인가 서버는 이 필드를 넣어야 한다(MUST)"를 따로 요구한다.

| 필드 | 표시 (OIDC Discovery §3) | RFC 8414 와 비교 | 이 practice | 관측 (S1) |
|---|---|---|---|---|
| `issuer` | REQUIRED | 같음 | 씀 | `http://localhost:9010` |
| `authorization_endpoint` | REQUIRED | RFC 8414 는 조건부 | 씀 | `.../oauth2/authorize` |
| `token_endpoint` | implicit 만 쓸 때를 빼고 REQUIRED | 같음 | 씀 | `.../oauth2/token` |
| `userinfo_endpoint` | RECOMMENDED | OIDC 전용 | 이 practice 에서는 쓰지 않음 | `.../userinfo` |
| `jwks_uri` | REQUIRED | RFC 8414 는 OPTIONAL | 씀 | `.../oauth2/jwks` |
| `registration_endpoint` | RECOMMENDED | RFC 8414 는 OPTIONAL | 이 practice 에서는 쓰지 않음 | 없음 |
| `scopes_supported` | RECOMMENDED, `openid` 지원 MUST | 같음(RECOMMENDED) | 광고됨 | `["openid"]` |
| `response_types_supported` | REQUIRED | 같음 | 씀 | `["code"]` |
| `response_modes_supported` | OPTIONAL | 같음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `grant_types_supported` | OPTIONAL | 같음 | 광고됨 | E3 과 같음 |
| `acr_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `subject_types_supported` | REQUIRED | OIDC 전용 | 광고됨 | `["public"]` |
| `id_token_signing_alg_values_supported` | REQUIRED, `RS256` 포함 MUST | OIDC 전용 | 씀 (id_token 서명) | `["RS256"]` |
| `id_token_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `id_token_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `userinfo_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `userinfo_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `userinfo_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `request_object_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `request_object_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `request_object_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `token_endpoint_auth_methods_supported` | OPTIONAL | 같음 | 씀 | 6개 방식 |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL | RFC 8414 는 조건부 MUST | 씀 — 조건부 MUST 대상([8절](#s8) 20번) | `["HS256",...,"PS512"]` 12개(E3 와 같음) |
| `display_values_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `claim_types_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `claims_supported` | RECOMMENDED | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `service_documentation` | OPTIONAL | 같음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `claims_locales_supported` | OPTIONAL | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `ui_locales_supported` | OPTIONAL | 같음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `claims_parameter_supported` | OPTIONAL, 기본 `false` | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `request_parameter_supported` | OPTIONAL, 기본 `false` | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `request_uri_parameter_supported` | OPTIONAL, 기본 `true` | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `require_request_uri_registration` | OPTIONAL, 기본 `false` | OIDC 전용 | 이 practice 에서는 쓰지 않음 | 없음 |
| `op_policy_uri` | OPTIONAL | 같음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `op_tos_uri` | OPTIONAL | 같음 | 이 practice 에서는 쓰지 않음 | 없음 |
| `code_challenge_methods_supported` | (OIDC 에 정의 없음) · MCP: 인가 서버 MUST 포함, 클라이언트 MUST 확인 | RFC 8414 필드 | 씀 | `["S256"]` |
| `authorization_response_iss_parameter_supported` | (OIDC 에 정의 없음) · RFC 9207 §3 | RFC 8414 확장 | 씀 | `true` |

S1 에는 이 밖에 `end_session_endpoint`(OpenID Connect RP-Initiated Logout 1.0 의 필드)와 E3 에서 본 `revocation_*`·`introspection_*`·`tls_client_certificate_bound_access_tokens`·`dpop_signing_alg_values_supported` 가 있다.

<a id="e5"></a>

### 5.5 E5 — `GET /oauth2/authorize`

| 항목 | 내용 |
|---|---|
| URL | `http://localhost:9010/oauth2/authorize` (C3 의 `authorization_endpoint`) |
| 주체 | 에이전트가 만든 URL 로 브라우저가 이동한다 |
| 근거 | [RFC 6749 §4.1.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.1) · [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1) · [RFC 7636 §4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3) · [RFC 8707 §2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1) · [MCP Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation) · [OpenID Connect Core 1.0 §3.1.2.1](https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest) |

**요청 (쿼리 파라미터, `application/x-www-form-urlencoded`)**

| 이름 | 표시 | 설명 | 이 practice | 관측 (S17 에이전트 · C5 스크립트) |
|---|---|---|---|---|
| `response_type` | REQUIRED (RFC 6749 · OAuth 2.1) | `code` | 씀 | `code` |
| `client_id` | REQUIRED | | 씀 | `official-shop-agent` |
| `redirect_uri` | OPTIONAL (RFC 6749) · 등록이 하나면 OPTIONAL, 여럿이면 REQUIRED (OAuth 2.1) · REQUIRED (OIDC Core) | 등록값과 단순 문자열 비교로 정확히 일치해야 한다(AS MUST) | 씀 | `http://localhost:8110/login/oauth2/code/authserver` |
| `scope` | OPTIONAL (RFC 6749 · OAuth 2.1) · REQUIRED, `openid` 포함 (OIDC Core) | 공백 구분 | 씀 | `openid profile` |
| `state` | RECOMMENDED (RFC 6749) · OPTIONAL (OAuth 2.1) · RECOMMENDED (OIDC Core) · 사용·검증 SHOULD (MCP) | CSRF 방지·요청 상관 | 씀 | S17 무작위 값 · C5 `walkthrough-state` |
| `code_challenge` | REQUIRED ([RFC 7636 §4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3)) · REQUIRED 또는 RECOMMENDED (OAuth 2.1, §7.5.1) · MUST (MCP) | `BASE64URL(SHA256(code_verifier))`, 43~128자 | 씀 | S17 `5tPg093jM6Nk0oOa1O3CYuUcYYPL92lzwX0p9ADgBHI` |
| `code_challenge_method` | OPTIONAL, 기본 `plain` · 가능하면 `S256` MUST | | 씀 | `S256` |
| `resource` | 표시 없음 — 클라이언트가 넣을 수 있음(MAY, [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2)) · MUST (MCP) | 절대 URI, fragment 금지(MUST). 여러 번 넣어 여러 리소스 지정 가능(MAY) | 씀(하나) | `http://localhost:8111/mcp` |
| `nonce` | OPTIONAL (OIDC Core) | ID Token 재전송 방지 | 씀 (Spring Security 가 `openid` 요청에 붙임) | S17 무작위 값 · C5 없음 |
| `response_mode` | OPTIONAL (OIDC Core) | 응답 전달 방식 | 이 practice 에서는 쓰지 않음 | 없음 |
| `display` | OPTIONAL (OIDC Core) | 로그인 화면 표시 방식 | 이 practice 에서는 쓰지 않음 | 없음 |
| `prompt` | OPTIONAL (OIDC Core) | `none`·`login`·`consent`·`select_account` | 이 practice 에서는 쓰지 않음 | 없음 |
| `max_age` | OPTIONAL (OIDC Core) | 최대 인증 경과 시간 | 이 practice 에서는 쓰지 않음 | 없음 |
| `ui_locales` | OPTIONAL (OIDC Core) | 화면 언어 | 이 practice 에서는 쓰지 않음 | 없음 |
| `id_token_hint` | OPTIONAL (OIDC Core) | 이전 ID Token | 이 practice 에서는 쓰지 않음 | 없음 |
| `login_hint` | OPTIONAL (OIDC Core) | 로그인 식별자 힌트 | 이 practice 에서는 쓰지 않음 | 없음 |
| `acr_values` | OPTIONAL (OIDC Core) | 인증 수준 요청 | 이 practice 에서는 쓰지 않음 | 없음 |

`response_mode` 부터 `acr_values` 까지는 OpenID Connect 가 더한 파라미터로, MCP 인가 명세의 범위 밖이다. OIDC Core 는 이 밖에도 `claims`, `request`, `request_uri` 같은 인가 요청 파라미터를 다른 절에서 정의한다. 이 practice 의 범위 밖이다.

**응답** — [E6](#e6). 로그인 세션이 없으면 먼저 `302 /login` 이다(Spring Authorization Server 동작, 명세 범위 밖).

<a id="e6"></a>

### 5.6 E6 — 인가 응답 (리다이렉트)

| 항목 | 내용 |
|---|---|
| 형태 | `302 Found`, `Location: <redirect_uri>?<파라미터>` (쿼리 컴포넌트, `application/x-www-form-urlencoded`) |
| 받는 곳 | 에이전트 `GET /login/oauth2/code/authserver` |
| 근거 | [RFC 6749 §4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2), [§4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1) · [OAuth 2.1 §4.1.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2), [§4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1) · [RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2) · [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) · [MCP 2026-07-28 Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation) |

**성공 응답**

| 이름 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|
| `code` | REQUIRED | 짧은 수명(최대 10분 RECOMMENDED), 한 번만 사용, client_id·redirect_uri·code_challenge 에 묶임 | 씀 | C5 `3S_GaQLdjO5v...` · S20 `tXQ2qA0_QjMQ...` |
| `state` | 요청에 있었으면 REQUIRED, 받은 값 그대로 | | 씀 | C5 `walkthrough-state` |
| `iss` | OPTIONAL (OAuth 2.1) · RFC 9207 지원 서버는 MUST (RFC 9207 §2) · SHOULD (MCP 2026-07-28) | 인가 서버의 issuer 식별자 | 씀 | C5 `http%3A%2F%2Flocalhost%3A9010` |

클라이언트는 모르는 파라미터를 무시해야 한다(MUST). OIDC 흐름에서 `id_token` 이 인가 엔드포인트로 오면 `iss` 는 그 ID Token 의 `iss` 와 같아야 한다(MUST, RFC 9207 §2.4). 이 practice 는 코드 흐름이라 인가 응답에 ID Token 이 없다.

**오류 응답** — redirect URI 가 없거나 틀리거나, client_id 가 없거나 틀리면 리다이렉트하지 않는다(MUST NOT). 그 밖의 오류는 redirect_uri 로 보낸다.

| 이름 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|
| `error` | REQUIRED | 아래 코드 중 하나 | 씀 | C16 `invalid_target` · C17 `invalid_request` |
| `error_description` | OPTIONAL | ASCII 설명 | 씀 | C17 `OAuth 2.0 Parameter: code_challenge` |
| `error_uri` | OPTIONAL | 설명 페이지 URI | 씀 | C16 `https://www.rfc-editor.org/rfc/rfc8707#section-2` |
| `state` | 요청에 있었으면 REQUIRED | | 씀 | C16, C17 `walkthrough-state` |
| `iss` | OPTIONAL (OAuth 2.1) · MUST (RFC 9207 지원 서버, 오류 응답 포함) | | 씀 | C16, C17 `http%3A%2F%2Flocalhost%3A9010` |

| `error` 코드 | 정의 | 뜻 | 이 practice 에서 나오는 경우 |
|---|---|---|---|
| `invalid_request` | RFC 6749 §4.1.2.1 · RFC 7636 §4.4.1 | 필수 파라미터 누락·잘못된 값·중복. PKCE 필수인데 `code_challenge` 없음, 지원하지 않는 변환 방식 | `code_challenge` 없음 (C17) |
| `unauthorized_client` | RFC 6749 §4.1.2.1 | 이 클라이언트는 이 방식으로 코드를 요청할 수 없음 | 관측 없음 |
| `access_denied` | RFC 6749 §4.1.2.1 | 자원 소유자나 인가 서버가 거부 | 관측 없음 (동의 화면 없음) |
| `unsupported_response_type` | RFC 6749 §4.1.2.1 | 이 방식의 코드 발급 미지원 | 관측 없음 |
| `invalid_scope` | RFC 6749 §4.1.2.1 | scope 가 잘못됐거나 알 수 없음 | 관측 없음 |
| `server_error` | RFC 6749 §4.1.2.1 | 서버 내부 오류(리다이렉트로는 500 을 줄 수 없어서 정의) | 관측 없음 |
| `temporarily_unavailable` | RFC 6749 §4.1.2.1 | 일시적 과부하·점검 | 관측 없음 |
| `invalid_target` | RFC 8707 §2 | 요청한 리소스가 잘못됐거나 알 수 없음 | 허용 목록 밖 `resource` (C16) |

<a id="e7"></a>

### 5.7 E7 — `POST /oauth2/token` (authorization_code)

| 항목 | 내용 |
|---|---|
| URL | `http://localhost:9010/oauth2/token` (C3 의 `token_endpoint`) |
| 요청 형식 | `Content-Type: application/x-www-form-urlencoded`, UTF-8 |
| 근거 | [RFC 6749 §4.1.3](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.3), [§5.1](https://www.rfc-editor.org/rfc/rfc6749#section-5.1), [§5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2) · [OAuth 2.1 §3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)–[§3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4), [§4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3) · [RFC 7636 §4.5](https://www.rfc-editor.org/rfc/rfc7636#section-4.5), [§4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6) · [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) · [OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse) |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 (C6 · S5) |
|---|---|---|---|---|---|
| `Authorization: Basic` | 헤더 | confidential client 는 인증 MUST ([OAuth 2.1 §3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)) · `client_secret_basic` 은 [OAuth 2.1 §2.4.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.4.1) | `base64(client_id:client_secret)` | 씀 | C6 `-u official-shop-agent:...` |
| `grant_type` | 본문 | REQUIRED | `authorization_code` | 씀 | C6 |
| `code` | 본문 | REQUIRED | 인가 응답의 코드 | 씀 | C6 |
| `redirect_uri` | 본문 | 인가 요청에 있었으면 REQUIRED, 같은 값 (RFC 6749) · OAuth 2.1 목록에서 제외, 하위 호환은 §10.2 | | 씀 | C6 |
| `client_id` | 본문 | 클라이언트 인증을 하지 않을 때 REQUIRED | | 이 practice 에서는 쓰지 않음 (Basic 인증) | C6 없음 |
| `code_verifier` | 본문 | REQUIRED (RFC 7636 §4.5) · `code_challenge` 가 있었으면 REQUIRED, 없었으면 쓰면 안 됨 (OAuth 2.1) | 원래 무작위 문자열 | 씀 | C6 `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` |
| `resource` | 본문 | 표시 없음 (RFC 8707 §2.2) · MUST (MCP) | 토큰을 쓸 리소스 | 씀 | C6 `http://localhost:8111/mcp` |
| `scope` | 본문 | 이 그랜트에서는 정의 없음 | | 이 practice 에서는 쓰지 않음 | 없음 |

**성공 응답 (200)**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 (S5, C6) |
|---|---|---|---|---|---|
| `Content-Type: application/json` | 헤더 | MUST (RFC 6749 §5.1 · OAuth 2.1 부록 C.3) | | 씀 | `application/json;charset=UTF-8` |
| `Cache-Control: no-store` | 헤더 | MUST (RFC 6749 §5.1 · OAuth 2.1 §3.2.3) | | 씀 | `no-cache, no-store, max-age=0, must-revalidate` |
| `Pragma: no-cache` | 헤더 | MUST (RFC 6749 §5.1), OAuth 2.1 은 요구하지 않음 | | 씀 | `no-cache` |
| `access_token` | 필드 | REQUIRED | | 씀 | `eyJraWQiOiIyYjk5ZGJl...` |
| `token_type` | 필드 | REQUIRED, 대소문자 무시 · OIDC 는 `Bearer` MUST | | 씀 | `Bearer` |
| `expires_in` | 필드 | RECOMMENDED | 초 단위 수명 | 씀 | `299` |
| `refresh_token` | 필드 | OPTIONAL | | 씀 | `GYoLPa20WGKQ...` |
| `scope` | 필드 | 요청과 같으면 OPTIONAL (RFC 6749) · RECOMMENDED (OAuth 2.1), 다르면 REQUIRED | | 씀 | `openid profile` |
| `id_token` | 필드 | OIDC 토큰 응답에 포함 ([OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)) | `aud` 에 client_id MUST ([§2](https://openid.net/specs/openid-connect-core-1_0.html#IDToken)) | 씀 | `eyJraWQiOiIyYjk5ZGJl...`, `aud=official-shop-agent` (C6-2) |

**오류 응답** — 기본 `400`, JSON 본문에 `error`(REQUIRED)·`error_description`(OPTIONAL)·`error_uri`(OPTIONAL).

| `error` 코드 | 정의 | 뜻 | 관측 |
|---|---|---|---|
| `invalid_request` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 | 필수 파라미터 누락, 중복, 인증 방식 여러 개, `code_challenge` 없이 `code_verifier` 전송 등 | — |
| `invalid_client` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 | 클라이언트 인증 실패. `Authorization` 헤더로 인증을 시도했다면 **401 + `WWW-Authenticate` MUST** | S8 `401`, `WWW-Authenticate: Basic realm="http://localhost:9010"`, `{"error":"invalid_client"}` |
| `invalid_grant` | RFC 6749 §5.2 · RFC 7636 §4.6 | 코드·refresh token 이 무효·만료·폐기, redirect_uri 불일치, 다른 클라이언트의 코드, `code_verifier` 불일치 | S7 `400 {"error":"invalid_grant"}` |
| `unauthorized_client` | RFC 6749 §5.2 | 이 클라이언트에 허용되지 않은 그랜트 | — |
| `unsupported_grant_type` | RFC 6749 §5.2 | 지원하지 않는 그랜트 | — |
| `invalid_scope` | RFC 6749 §5.2 | scope 가 잘못됐거나 허가 범위를 넘음 | — |
| `invalid_target` | RFC 8707 §2 | 리소스가 잘못됐거나 알 수 없음 | S6 `400`, `The requested resource does not match the authorization request` |

<a id="e8"></a>

### 5.8 E8 — `POST /oauth2/token` (refresh_token)

| 항목 | 내용 |
|---|---|
| URL · 형식 | E7 과 같음 |
| 근거 | [RFC 6749 §6](https://www.rfc-editor.org/rfc/rfc6749#section-6) · [OAuth 2.1 §4.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3)–[§4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2) · [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) · [MCP 2026-07-28 Refresh Tokens](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#refresh-tokens) |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 (C11) |
|---|---|---|---|---|---|
| `Authorization: Basic` | 헤더 | confidential client 는 인증 MUST | refresh token 은 발급받은 클라이언트에 묶인다 | 씀 | `-u official-shop-agent:...` |
| `grant_type` | 본문 | REQUIRED | `refresh_token` | 씀 | `refresh_token` |
| `refresh_token` | 본문 | REQUIRED | | 씀 | `Cog6N7Fv1qyO...` |
| `scope` | 본문 | OPTIONAL | 원래 허가 범위를 넘으면 안 됨(MUST NOT). 생략하면 원래 범위 | 이 practice 에서는 쓰지 않음 | 없음 |
| `resource` | 본문 | 표시 없음 (RFC 8707 §2.2, 모든 그랜트에 사용 가능) · 토큰 요청에 MUST (MCP) | 원래 허가된 리소스로 제한될 수 있음 | 씀 | `http://localhost:8111/mcp` |
| `client_id` | 본문 | 인증을 하지 않는 클라이언트일 때 사용 (OAuth 2.1 §3.2.2 OPTIONAL) | | 이 practice 에서는 쓰지 않음 | 없음 |

**응답** — E7 성공 응답과 같은 형식([OAuth 2.1 §4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2)). 새 refresh token 을 줄 수 있고(MAY), 주면 클라이언트는 옛것을 버려야 한다(MUST). public client 에는 회전이나 sender-constrained 토큰이 MUST 다([§4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1)). 오류 코드는 E7 과 같다.

| 필드 | 관측 (C11, 세 practice) |
|---|---|
| `access_token` | 새 토큰. 페이로드 `aud` 는 그대로 MCP 서버, `jti` 는 새 값 |
| `refresh_token` | 처음 받은 값과 같음 — 회전 없음(confidential client 이므로 허용) |
| `scope` | `openid profile` |
| `id_token` | 함께 재발급됨 |
| `token_type` · `expires_in` | `Bearer` · `299` |

<a id="e9"></a>

### 5.9 E9 — `POST /mcp` (Bearer)

| 항목 | 내용 |
|---|---|
| URL | E1 과 같음 (MCP 엔드포인트 하나가 POST·GET·DELETE 를 받는다) |
| 근거 | [MCP 2025-11-25 Transports — Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http) · [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) · [MCP Authorization — Access Token Usage](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-usage) · [RFC 6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1) · [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1) |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| 메서드 `POST` | 요청줄 | MUST — 클라이언트가 보내는 JSON-RPC 메시지마다 새 POST | | 씀 | C7–C10 |
| `Authorization: Bearer <token>` | 헤더 | MUST — 같은 세션이라도 모든 HTTP 요청 (MCP) · 리소스 서버는 이 방식 지원 MUST (RFC 6750 §2.1) · 쿼리 문자열 금지 MUST NOT (MCP) | `bearer` 는 대소문자 무시 | 씀 | C7–C10 |
| `Accept` | 헤더 | MUST — `application/json` 과 `text/event-stream` 둘 다 | | 씀 | C7 `application/json, text/event-stream` · S15 누락 시 400 |
| `Content-Type` | 헤더 | 표시 없음 (명세는 본문이 JSON-RPC 메시지이고 UTF-8 이어야 한다고만 규정) | | 씀 | `application/json` (SDK 는 `application/json; charset=utf-8`) |
| `MCP-Protocol-Version` | 헤더 | 초기화 이후 모든 요청에 MUST · 값은 협상 버전 SHOULD | 서버: 없으면 `2025-03-26` 가정 SHOULD, 무효·미지원이면 400 MUST | 씀 (SDK 는 `initialize` 에도 붙임) | C8–C10 `2025-11-25` · S10 없음 → 200 · C15 `1999-01-01` → 400 |
| `Mcp-Session-Id` | 헤더 | 서버가 발급했으면 이후 모든 요청에 MUST | 보이는 ASCII 만 | 씀 | C8–C10 · C14 누락 시 400 · S13 모르는 값 404 |
| `Origin` | 헤더 | 클라이언트 요구 없음 · 서버는 검증 MUST, 있고 무효면 403 MUST | 브라우저가 붙인다. 서버 간 호출에는 없다 | 에이전트는 보내지 않음 | C13 `http://evil.example` → 403 |
| 본문 | 본문 | MUST — 단일 JSON-RPC 요청·알림·응답 (2025-06-18 부터 배치 금지) | UTF-8 MUST | 씀 | C7 `initialize`, C8 `notifications/initialized`, C9 `tools/list`, C10 `tools/call` |

**응답**

| 상태·이름 | 표시 | 언제 | 이 practice | 관측 |
|---|---|---|---|---|
| `200` + `Content-Type: application/json` | 요청이면 이것 또는 SSE 중 하나 MUST, 클라이언트는 둘 다 처리 MUST | JSON 객체 하나 | 씀 (`initialize`) | C7 |
| `200` + `Content-Type: text/event-stream` | 위와 같음 | SSE 스트림. 요청 관련 요청·알림 뒤 응답, 응답 뒤 스트림 종료 SHOULD | 씀 (`tools/list`, `tools/call`) | C9, C10 |
| SSE 준비 이벤트(이벤트 ID + 빈 `data`) | SHOULD | SSE 시작 직후 | 보내지 않음 | C9, C10 첫 이벤트가 곧 응답 |
| SSE `id` | MAY, 있으면 세션 내 모든 스트림에서 전역 유일 MUST | 재개용 커서 | 세션 ID 를 그대로 씀 | C9, C10 모두 `id:c46d6911-...` — 유일성 MUST 와 다름 |
| SSE `retry` | 스트림을 끝내지 않고 연결을 닫기 전에 SHOULD | | 쓰지 않음 | — |
| `Mcp-Session-Id` 응답 헤더 | MAY — `InitializeResult` 응답에서 발급. 전역 유일·암호학적으로 안전 SHOULD | | 씀 (UUID) | C7 `c46d6911-2172-4076-a3d6-1d441b9652ce` |
| `202 Accepted`, 본문 없음 | 알림·응답을 받아들이면 MUST | | 씀 | C8, S9 |
| `400 Bad Request` | 받아들일 수 없는 알림·응답에 오류 상태 MUST, `id` 없는 JSON-RPC 오류 본문 MAY · 세션 ID 누락 SHOULD · 무효 `MCP-Protocol-Version` MUST | | 씀 | C14, C15, S15 |
| `401 Unauthorized` | 무효·만료 토큰 MUST (MCP) · `WWW-Authenticate` MUST (RFC 6750) | E1 응답 표 참고 | 씀 | C12, S3 |
| `403 Forbidden` (Origin) | 있는 `Origin` 이 무효면 MUST | | 씀 | C13 |
| `403 Forbidden` (scope) | `insufficient_scope` + `scope` + `resource_metadata` SHOULD (MCP Scope Challenge Handling) | | 이 practice 에서는 쓰지 않음 | — |
| `404 Not Found` | 끝난 세션 ID 에 MUST · 받은 클라이언트는 새 `initialize` MUST | | 씀 | S13, S16 |
| `421 Misdirected Request` (Host) | MCP 규정 없음 ([RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20)) | DNS 리바인딩 방어 | 씀 | S14 |

<a id="e10"></a>

### 5.10 E10 — `DELETE /mcp`, `GET /mcp`

| 항목 | 내용 |
|---|---|
| URL | E1 과 같음 |
| 근거 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) · [Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) · [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery) |

**`DELETE /mcp` — 세션 종료**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| 메서드 `DELETE` | 요청줄 | 더 쓰지 않을 세션은 SHOULD | | SDK 클라이언트가 세션을 닫을 때 보냄 | C18, S16 |
| `Mcp-Session-Id` | 헤더 | SHOULD (끝낼 세션 지정) | | 씀 | C18 |
| `Authorization` | 헤더 | MUST (모든 HTTP 요청) | | 씀 | C18 |
| `MCP-Protocol-Version` | 헤더 | MUST (이후 모든 요청) | | 씀 | C18 |
| 응답 성공 | 성공 상태 코드 규정 없음 | | `200` | C18, S16 `200`, 본문 없음 |
| `405 Method Not Allowed` | MAY — 클라이언트의 세션 종료를 허용하지 않을 때 | | 쓰지 않음 (`disallowDelete: false`) | — |
| 이후 같은 세션 요청 | 404 MUST | | 씀 | S16 `404` |

**`GET /mcp` — 서버발 메시지용 SSE 스트림**

| 이름 | 위치 | 표시 | 설명 | 이 practice | 관측 |
|---|---|---|---|---|---|
| 메서드 `GET` | 요청줄 | MAY | 클라이언트가 먼저 POST 하지 않아도 서버가 요청·알림을 보낼 수 있게 한다 | SDK 클라이언트가 세션 ID 를 받은 뒤 연다 | S11 |
| `Accept: text/event-stream` | 헤더 | MUST | | 씀 | S11 |
| `Authorization` | 헤더 | MUST | | 씀 | S11 |
| `Mcp-Session-Id` | 헤더 | 세션이 있으면 MUST | | 씀 | S11 · S12 누락 시 `400 text/plain` |
| `MCP-Protocol-Version` | 헤더 | MUST | | 씀 | S11 |
| `Last-Event-ID` | 헤더 | 끊긴 뒤 재개하려면 SHOULD | 서버는 끊긴 그 스트림의 메시지만 재전송 가능(MAY), 다른 스트림 것은 재전송 금지(MUST NOT) | 이 practice 에서는 쓰지 않음 | — |
| 응답 | 상태·헤더 | `Content-Type: text/event-stream` 을 돌려주거나 `405` MUST | 스트림에서 서버는 요청·알림을 보낼 수 있음(MAY). 재개가 아니면 JSON-RPC 응답을 보내면 안 됨(MUST NOT). 연결을 끊기 전 `retry` SHOULD | SSE 스트림 (응답 헤더는 첫 이벤트와 함께 나감) | S11: 5초 동안 상태줄·헤더·본문이 오지 않고 연결이 열린 채 유지(curl 종료 코드 28). 405 는 오지 않았다 |

S11 을 명세와 대조하면, 서버는 405 가 아니라 SSE 로 응답하려고 연결을 열어 두지만 보낼 메시지가 없어 상태줄조차 보내지 않는다. Spring AI 서버 전송은 `ServerResponse.sse(...)` 로 응답하므로 첫 이벤트를 보낼 때 헤더가 나간다. keep-alive 간격(`spring.ai.mcp.server.streamable-http.keep-alive-interval`)은 기본값이 없고 이 practice 도 설정하지 않았다. 명세의 두 선택지("SSE 로 응답" 또는 "405") 중 SSE 쪽이지만, 클라이언트는 응답이 시작됐는지 알 수 없다([8절](#s8) 22번).

2026-07-28 에서는 GET 스트림과 세션이 없어진다. 2026-07-28 만 지원하는 서버는 옛 클라이언트의 GET·DELETE 에 `405` 로 답하는 것이 좋다(SHOULD, [6절](#s6)).

---

<a id="s6"></a>

## 6. 2026-07-28 에서 달라지는 것 — 다이어그램 ⑧

[1.1](#s1)에서 정한 대로 이 문서의 전송·수명주기 기준은 2025-11-25 다. 인가 규칙 중 2026-07-28 이 더한 두 가지 — 인가 응답의 `iss` 검증([4.6](#s4-6))과 자격증명의 issuer 바인딩([4.4](#s4-4)) — 는 이미 기준에 포함해 앞 절에서 다뤘다. 이 절은 나머지 차이, 전송·수명주기를 stateless 로 바꾼 부분을 정리한다. 이 practice 의 MCP Java SDK 2.0.0 은 이 변화를 구현하지 않으므로([6.4](#s6-4)) 아래는 모두 **[명세]**이고 **[관측]**은 없다.

<a id="s6-1"></a>

### 6.1 무엇이 바뀌는가

**[명세]** [MCP 2026-07-28 Key Changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog) — "Major changes" 1~4, 9번.

- **세션 제거.** Streamable HTTP 전송에서 프로토콜 수준 세션과 `Mcp-Session-Id` 헤더를 없앤다. `tools/list`·`resources/list`·`prompts/list` 같은 목록 엔드포인트는 더 이상 연결마다 달라지지 않는다. 호출 사이에 상태가 필요한 서버는 세션 대신, 툴 인자로 주고받는 서버 발급 핸들을 쓴다.
- **stateless 화.** `initialize`/`notifications/initialized` 핸드셰이크를 없앤다. 모든 요청이 `_meta` 에 프로토콜 버전과 클라이언트 능력을 직접 싣는다 — `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientCapabilities`. 클라이언트는 매 요청에 자신을 알리는 것이 좋고(**SHOULD**, `io.modelcontextprotocol/clientInfo`), 서버는 매 결과의 `_meta` 에 자신을 알리는 것이 좋다(**SHOULD**, `io.modelcontextprotocol/serverInfo`). 버전이 안 맞으면 `UnsupportedProtocolVersionError` 를 돌려준다.
- **`server/discover` 신설.** 서버는 지원하는 프로토콜 버전·능력·신원을 알리는 이 RPC 를 구현해야 한다(**MUST**, [MCP 2026-07-28 Discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)). 클라이언트가 이걸 부르는 것은 선택이다(MAY) — 사전 버전 선택이나 STDIO 하위 호환 탐침에 쓴다. 응답의 `serverInfo` 는 자체 신고 값이라 클라이언트가 보안 판단에 쓰면 안 된다(**SHOULD NOT**).
- **GET 스트림 제거.** 서버가 언제든 보낼 수 있던 `GET /mcp` SSE 스트림과 `resources/subscribe`/`unsubscribe` 를, opt-in 알림 전용의 단일 장수명 `subscriptions/listen` 스트림으로 바꾼다. `notifications/progress`·`notifications/message` 같은 요청-스코프 알림은 그대로 그 요청의 응답 스트림으로 간다.
- **재개 제거.** SSE 스트림 재개와 메시지 재전송(`Last-Event-ID`, SSE 이벤트 `id`)을 없앤다. 응답 스트림이 끊기면 그 요청은 사라지고, 클라이언트는 새 요청 ID 로 다시 보내야 한다(**MUST**).
- **오류 코드 재번호(Minor changes 12번).** JSON-RPC 서버 오류 범위를 나눈다 — `-32000`~`-32019` 는 기존 SDK 관례를 그대로 인정하고, `-32020`~`-32099` 를 MCP 명세 전용으로 예약한다. 이 초안에서 새로 정의됐던 오류를 이 범위로 옮긴다: `HeaderMismatch` `-32001`→`-32020`, `MissingRequiredClientCapability` `-32003`→`-32021`, `UnsupportedProtocolVersion` `-32004`→`-32022`.

**[명세]** [MCP 2026-07-28 Streamable HTTP — Request Metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#request-metadata)

- `MCP-Protocol-Version` 은 여전히 매 POST 에 MUST 이지만, 이제 값이 본문 `_meta` 의 `io.modelcontextprotocol/protocolVersion` 과 **정확히 같아야** 한다(2025-11-25 에는 이런 이중 표기·일치 요구가 없다, [4.8](#s4-8)).
- 새 헤더 `Mcp-Method`(모든 요청 REQUIRED, 값은 `method` 필드)와 `Mcp-Name`(`tools/call`·`resources/read`·`prompts/get` 요청 REQUIRED, 값은 `params.name` 또는 `params.uri`)이 생긴다(changelog "Minor changes" 4번).
- 헤더 값이 본문 값과 다르거나 필수 헤더가 없으면 서버는 `400 Bad Request` + JSON-RPC 오류 코드 `-32020`(`HeaderMismatch`)으로 거부해야 한다(**MUST**).
- 이 버전만 지원하는 서버가 예전 클라이언트의 `GET`·`DELETE /mcp` 를 받으면 `405 Method Not Allowed` 로, `Mcp-Session-Id` 헤더는 무시하고 세션을 만들지 않는 것이 좋다(**SHOULD**, [Backward Compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#backward-compatibility)).

<a id="s6-2"></a>

### 6.2 표로 보는 차이

| 항목 | 2025-11-25(이 practice 의 기준) | 2026-07-28 |
|---|---|---|
| 핸드셰이크 | `initialize` → `notifications/initialized`([4.8](#s4-8)) | 없음. 모든 요청이 독립적 |
| 세션 | `Mcp-Session-Id` 발급·검증([4.8](#s4-8), [E9](#e9)) | 없음 |
| 프로토콜 버전 전달 | `MCP-Protocol-Version` 헤더만(없으면 구버전 가정, [E9](#e9)) | 헤더 + 본문 `_meta` 이중 표기, 불일치 시 `400 HeaderMismatch` |
| 클라이언트·서버 신원 | `initialize` 요청·응답의 `clientInfo`·`serverInfo`(한 번) | 매 요청 `_meta` 의 `clientInfo`(SHOULD), 매 결과 `_meta` 의 `serverInfo`(SHOULD) |
| 서버발 스트림 | `GET /mcp` 상시 SSE([E10](#e10)) | `subscriptions/listen`(opt-in, 알림 종류 선택) |
| 재개 | `Last-Event-ID`, SSE `id`(있으면 세션 내 유일 MUST, [4.8](#s4-8)) | 없음 — 끊기면 새 요청 ID 로 재발행 MUST |
| 메서드 라우팅 헤더 | 없음 | `Mcp-Method`, `Mcp-Name`(REQUIRED) |
| 버전·기능 사전 조회 | 없음(`initialize` 응답으로만 앎) | `server/discover`(서버 MUST 구현) |
| 세션 종료 | `DELETE /mcp`([E10](#e10)) | 없음(세션이 없으므로) |

<a id="s6-3"></a>

### 6.3 다이어그램 ⑧ — stateless 요청 흐름

인가([4.1](#s4-1)~[4.7](#s4-7))는 HTTP 계층의 일이라 2026-07-28 에서도 그대로다. 달라지는 것은 그 아래 MCP 호출 하나뿐이다.

```mermaid
sequenceDiagram
    autonumber
    participant B as 사용자 브라우저
    participant C as MCP 클라이언트(에이전트)
    participant M as MCP 서버
    participant A as 인가 서버

    Note over C: 토큰은 4~7절과 같은 방식으로 이미 마련되어 있다<br/>initialize 도, Mcp-Session-Id 도 없다
    opt 지원 버전을 먼저 확인하고 싶을 때 (선택)
        C->>M: POST /mcp server/discover (Authorization: Bearer)
        M-->>C: 200 supportedVersions, capabilities, serverInfo
    end
    C->>M: POST /mcp tools/call<br/>Authorization: Bearer<br/>MCP-Protocol-Version: 2026-07-28<br/>Mcp-Method: tools/call, Mcp-Name: getStock<br/>본문 params._meta: protocolVersion, clientCapabilities, clientInfo
    Note over M: MCP-Protocol-Version 헤더 == 본문 _meta.protocolVersion ?<br/>Mcp-Method, Mcp-Name 헤더 == 본문 method, params.name ?
    alt 헤더와 본문이 어긋나거나 필수 헤더가 없음
        M-->>C: 400 HeaderMismatch(-32020)
    else 서버가 그 protocolVersion 을 모름
        M-->>C: 400 UnsupportedProtocolVersionError(-32022), supportedVersions
    else 검증 통과
        M-->>C: 200 application/json 또는 text/event-stream (Mcp-Session-Id 없음)
    end
    Note over C: 다음 tools/call 도 완전히 새 요청이다<br/>이전 요청과 묶어 주는 세션이 없다
    C->>M: POST /mcp tools/call (같은 형태, 새 요청)
    M-->>C: 200 결과
```

<a id="s6-4"></a>

### 6.4 이 practice 가 이 절을 관측하지 않는 이유

세 practice 의 MCP 클라이언트·서버는 MCP Java SDK 2.0.0(`io.modelcontextprotocol.sdk`) 위에서 동작하고, 그 `ProtocolVersions` 상수는 `2024-11-05`·`2025-03-26`·`2025-06-18`·`2025-11-25` 까지만 정의한다([1.1](#s1)). `server/discover`, `_meta` 기반 stateless 요청, `Mcp-Method`/`Mcp-Name` 헤더를 SDK 가 보내거나 받지 않으므로 이 절의 내용은 캡처로 검증할 수 없다. 앱을 띄우지 않고 명세 원문(2026-07-28 Key Changes, Streamable HTTP, Discovery)만으로 작성했다.

---

<a id="s7"></a>

## 7. 보안 고려사항

이 절은 4~6절에서 다룬 개별 검증이 막는 공격을 한데 모은다. 이미 설명한 메커니즘은 다시 쓰지 않고 절 번호로 가리킨다.

<a id="s7-1"></a>

### 7.1 토큰 passthrough 금지

**[명세]** [MCP 2025-11-25 Authorization — Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling) — MCP 클라이언트는 자기 리소스 서버(MCP 서버)의 인가 서버가 발급하지 않은 토큰을 그 서버에 보내면 안 되고(**MUST NOT**), MCP 서버는 자기 리소스에 유효한 토큰만 받아야 하며(**MUST**) 다른 토큰을 받거나 전달하면 안 된다(**MUST NOT**). [MCP 2025-11-25 Security Best Practices — Token Passthrough](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#token-passthrough) 는 이를 안티패턴으로 규정한다 — MCP 서버가 클라이언트의 토큰이 자기 앞으로 발급됐는지 검증하지 않고 하류 API 에 그대로 넘기는 것. 보안 통제 우회, 감사 추적 단절, 신뢰 경계 붕괴로 이어진다고 설명하고, 대응으로 "MCP 서버는 자기 앞으로 명시적으로 발급되지 않은 어떤 토큰도 받으면 안 된다(**MUST NOT**)"를 든다.

이 practice 의 구조는 이 위험 자체가 성립하지 않는다. MCP 서버(`shop-mcp-server`)는 하류 API 를 호출하지 않는 순수 리소스 서버이고([2절](#s2)), 에이전트가 MCP 서버에 보내는 토큰은 오직 그 사용자가 로그인해 받은 access token 하나뿐이다([4.8](#s4-8) — "토큰은 로그인한 **사용자**의 것이다. 에이전트 자신의 토큰(client credentials)을 쓰지 않는다"). 전달할 다른 토큰이 애초에 없다.

<a id="s7-2"></a>

### 7.2 confused deputy 문제 — audience 검증이 막는 것

**[명세]** [MCP 2025-11-25 Security Best Practices — Confused Deputy Problem](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#confused-deputy-problem) 이 설명하는 시나리오는, MCP 서버가 **하류 제3자 API 에 대한 OAuth 프록시**이고 그 제3자 인가 서버에 **고정된 static client_id** 로 등록되어 있으며, 그 인가 서버가 **동의 쿠키**를 남기고, MCP 서버가 **클라이언트별 동의**를 따로 받지 않을 때 성립한다. 공격자는 동의 쿠키가 남은 사용자에게 조작된 리다이렉트 URI 를 심어, 사용자가 이미 내준 동의를 훔쳐 자기 앞으로 인가 코드를 받는다.

이 practice 는 이 조건을 만족하지 않는다 — MCP 서버가 프록시가 아니고, 제3자 인가 서버에 등록된 static client_id 도 없다([9절](#s9)). 대신 [MCP 2026-07-28 Authorization Security Considerations — Access Token Privilege Restriction](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations#access-token-privilege-restriction) 이 규정하는, 더 일반적인 형태의 방어가 이 practice 에 있다 — "MCP 서버는 자기 앞으로 발급된 토큰만 받아야 하고(**MUST**), audience 클레임에 자기가 없는 토큰은 거부해야 한다(**MUST**)."

이것이 [4.7](#s4-7)의 `aud` 발급(`ResourceAudienceTokenCustomizer`)과 [4.9](#s4-9)의 `aud` 검증이다. 인가 서버 하나가 여러 리소스의 토큰을 발급할 수 있는 구조에서, 이 검증이 없으면 어떤 리소스용으로 발급된 토큰이든 다른 리소스가 자기 것처럼 받아 처리해 버리는 "속은 대리인(confused deputy)"이 될 수 있다. audience 검증은 정확히 그 오용을 막는다. [4.9](#s4-9)의 C12 — id_token(`aud=official-shop-agent`)을 MCP 서버로 보내면 401 로 거부되는 것 — 이 그 방어가 동작하는 관측이다.

<a id="s7-3"></a>

### 7.3 mix-up 공격 — `iss` 검증이 막는 것

[4.6](#s4-6)에서 자세히 다뤘다. 요약하면, 클라이언트가 신뢰하는 인가 서버 자리에 공격자의 인가 서버가 섞여 들어가면 인가 코드·토큰이 엉뚱한 곳으로 흐를 수 있고, MCP 는 클라이언트가 **MCP 서버가 알려 주는** 인가 서버로 가기 때문에 악성 MCP 서버의 PRM 이 공격자 인가 서버를 가리키는 식으로 이 조건이 쉽게 만들어진다([RFC 9207 §1](https://www.rfc-editor.org/rfc/rfc9207#section-1)). `AuthorizationResponseIssuerFilter` 가 발견 때 검증한 issuer 와 콜백의 `iss` 를 문자열 그대로 비교해, 다르면 코드를 어떤 토큰 엔드포인트로도 보내지 않는다(S18, S19).

<a id="s7-4"></a>

### 7.4 발견 단계의 SSRF 와 신뢰 경계

**[명세]** [MCP 2025-11-25 Security Best Practices — Server-Side Request Forgery (SSRF)](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#server-side-request-forgery-ssrf) — 발견 과정에서 클라이언트가 여는 URL 은 전부 **MCP 서버가 알려 주는 값**이다: `WWW-Authenticate` 의 `resource_metadata`, PRM 의 `authorization_servers`, 인가 서버 메타데이터의 `token_endpoint`·`authorization_endpoint` 등. 악성 MCP 서버는 이 값들을 내부망 주소나 클라우드 메타데이터 엔드포인트(`http://169.254.169.254/...`)로 채워, 클라이언트가 자기 대신 그 주소에 요청하게 만들 수 있다. 문서는 대응으로 HTTPS 강제, 사설 IP 대역 차단([RFC 9728 §7.7](https://www.rfc-editor.org/rfc/rfc9728#section-7.7)), 리다이렉트 대상 검증, egress 프록시를 든다(전부 **SHOULD**).

official·chat-memory 는 이 SSRF 대응책(HTTPS 강제, 사설 IP 차단, egress 프록시)을 구현하지 **않는다** — `McpAuthorizationDiscovery` 가 401 챌린지의 `resource_metadata` 값과 인가 서버 메타데이터 URL 을 검증 없이 그대로 GET 한다. community 는 다르다 — `McpSecurityConfig`(`McpSecurityConfig.java:45-46`, 에이전트)가 모듈 `McpMetadataDiscoveryService` 를 `new DefaultUrlValidator(true)` 와 함께 구성하고, 그 서비스는 **보호 리소스 메타데이터(PRM) URL** 을 가져오기 **전에** `urlValidator.validateUrl()` 로 HTTPS 이거나 loopback(`localhost`·`127.0.0.1`·`::1`) HTTP 인지 검사한다(`mcp-security-common` 0.1.14 `DefaultUrlValidator`). `allowLoopback=true` 는 이 practice 가 로컬 HTTP 데모([7.8](#s7-8), [9절](#s9))이기 때문의 완화이고, loopback 이 아닌 주소는 이 검사만으로 이미 HTTPS 가 아니면 걸러진다. 다만 community 도 **인가 서버 메타데이터 URL**(`McpAuthorizationDiscovery` 가 이어받은 자기 코드, [4.3](#s4-3))에는 이 검증기를 태우지 않는다 — 그 경로는 official·chat-memory 와 같다. 즉 "community 는 PRM URL 만 검증하고, 세 practice 모두 인가 서버 메타데이터 URL 은 검증하지 않는다"가 정확한 서술이다.

이처럼 이 SSRF 대응책이 부분적이거나 없는 대신, 발견 각 단계에서 하는 두 **일치 검증**이 신뢰 범위를 좁힌다.

- PRM 의 `resource` 가 클라이언트가 실제로 요청한 MCP 서버 URL 과 정확히 같아야 한다(RFC 9728 §3.3, [4.2](#s4-2)). MCP 서버가 자기 자신이 아닌 다른 리소스의 메타데이터를 내밀어도 `resource` 가 다르면 그 문서는 버려진다.
- 인가 서버 메타데이터의 `issuer` 가 그 메타데이터를 요청하는 데 쓴 issuer 식별자와 정확히 같아야 한다(RFC 8414 §3.3, [4.3](#s4-3)). 공격자의 서버가 `https://attacker.example/.well-known/oauth-authorization-server` 에 `"issuer": "https://honest.example"` 를 내밀어도 거부된다 — [MCP 2026-07-28 Authorization Server Discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery#authorization-server-metadata-discovery) 가 이 예시를 그대로 든다.

두 검증 모두 **클라이언트가 그 URL 에 요청을 보내는 것 자체는 막지 못한다.** 이미 보낸 요청과 그 응답이 밖으로 새는 것(예: 오류 메시지에 담긴 내부망 정보)은 이 practice 의 범위 밖이다. 두 검증이 좁히는 것은 "받은 문서를 신뢰해 다음 단계(인가 요청, 토큰 요청)로 넘어갈지"이다 — SSRF 요청 자체가 아니라, SSRF 로 얻은 응답을 신뢰의 근거로 쓰는 것을 막는다.

<a id="s7-5"></a>

### 7.5 redirect URI 정확 일치와 PKCE

**[명세]** [MCP 2026-07-28 Authorization Security Considerations — Open Redirection](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations#open-redirection) — 인가 서버는 등록된 값과 `redirect_uri` 를 정확히 대조해야 한다(**MUST**). [같은 문서 — Authorization Code Protection](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations#authorization-code-protection) — PKCE 는 인가 코드를 가로챈 공격자가 `code_verifier` 없이는 그 코드를 토큰으로 바꿀 수 없게 한다.

이 practice 의 관측: S4([4.5](#s4-5))에서 등록되지 않은 `redirect_uri=http://evil.example/callback` 은 리다이렉트 자체가 되지 않는다(`400`, `Location` 없음) — 코드가 브라우저를 거쳐 공격자에게 갈 방법이 없다. `require-proof-key: true` 가 `code_challenge` 없는 인가 요청을 아예 거부하므로(C17), 이 practice 의 모든 인가 코드는 PKCE 로 보호된다.

<a id="s7-6"></a>

### 7.6 세션 오류 응답의 정보 노출

**[관측]** C14([4.8](#s4-8)) — `Mcp-Session-Id` 없이 `tools/list` 를 보내면 `400` 이 오는 것은 명세대로(**SHOULD**)지만, 응답 본문에 `stackTrace` 필드로 서버 내부의 자바 스택트레이스가 그대로 담긴다. S13 의 존재하지 않는 세션 `404` 응답도 같다. MCP 전송 명세는 이런 오류의 본문 형식을 규정하지 않으므로(`id` 없는 JSON-RPC 오류는 MAY) 이것이 명세 위반은 아니지만, 클래스 이름·패키지 경로·호출 스택이 응답에 그대로 노출되는 것은 정보 노출(information disclosure) 관점의 약점이다 — 내부 구현 세부가 공격 표면 파악에 쓰일 수 있다. Spring AI MCP 서버 전송의 오류 직렬화 방식이며, 이 practice 가 별도로 감싸지 않는다.

<a id="s7-7"></a>

### 7.7 Origin·Host 검증과 DNS 리바인딩

**[명세]** [MCP 2025-11-25 Transports — Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning) — 서버는 모든 연결의 `Origin` 을 검증해야 하고(**MUST**), 로컬 실행 시 127.0.0.1 에만 바인딩하는 것이 좋다(**SHOULD**). 이 요구가 막으려는 것은 DNS 리바인딩이다 — 공격자의 웹페이지가 피해자의 브라우저를 거쳐 `localhost` 의 MCP 서버에 요청을 보내게 만드는 공격이다. `Origin` 검증은 브라우저가 자동으로 붙이는 이 헤더를 보고 낯선 origin 의 요청을 걸러낸다. [4.8](#s4-8)의 C13(`Origin: http://evil.example` → 403)이 이 검증이고, S14(`Host: evil.example:8111` → 421)는 명세에 규정은 없지만 SDK 의 `DefaultServerTransportSecurityValidator` 가 Host 헤더까지 검증해 같은 부류의 공격(리버스 프록시 뒤에서 Host 를 속여 라우팅을 오인시키는 시도, [RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20))을 막는다.

<a id="s7-8"></a>

### 7.8 localhost 에서의 HTTP 사용은 명세 위반이다

**[명세]** [MCP 2026-07-28 Authorization Security Considerations — Communication Security](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations#communication-security) (OAuth 2.1 §1.5 를 그대로 따른다):

> "모든 인가 서버 엔드포인트는 HTTPS 로 제공해야 한다(**MUST**). 모든 redirect URI 는 `localhost` 이거나 HTTPS 를 써야 한다(**MUST**)."

이 문장은 두 대상을 다르게 다룬다 — **인가 서버 엔드포인트는 예외 없이 HTTPS MUST** 이고, **redirect URI 만** `localhost` 로 그 요구를 면한다. 이 practice 의 인가 서버는 `http://localhost:9010`(과 9020, 9000)이며, "`localhost` 라서" HTTPS 요구를 벗어날 수 없다 — 명세가 예외를 두는 곳은 redirect URI 뿐이다. 세 practice 모두 인가 엔드포인트·토큰 엔드포인트·MCP 서버를 전부 평문 HTTP 로 노출하며, 이는 로컬 학습 데모를 위한 의도된 선택이다([9절](#s9), [8절](#s8) 12번). 평문 HTTP 에서는 access token·refresh token·client_secret 이 네트워크 경로에 그대로 실린다.

<a id="s7-9"></a>

### 7.9 캡처 원본의 토큰 노출

[1.2](#s1) 각주에서 이미 밝혔듯, `docs/superpowers/captures/2026-09-12-*.txt`·`2026-09-16-official-supplement.txt` 원본에는 access token·refresh token·id_token·인가 코드가 줄이지 않은 원문으로 남아 있다. 이 문서에 인용할 때는 토큰을 앞 20자(코드·refresh_token 은 앞 12자) + `...` 로 줄였지만, 캡처 원본 자체는 그렇지 않다. 모두 로컬 학습용 인가 서버가 발급한 짧은 수명(5~30분)의 토큰이고 인가 서버를 내리면 서명 검증에 쓸 키도 함께 사라지지만, 이 원본 파일을 저장소 밖으로 옮기거나 공유할 때는 그 사실이 사라지지 않는다.

---

<a id="s8"></a>

## 8. 준수표

행은 명세 항목, 열은 세 practice 다. 각 칸은 "예/아니오 + 근거(클래스 이름 또는 캡처 단계)"로 채운다. official 과 chat-memory 는 클래스 구성이 같으므로([2.5](#s2-5)) 근거도 대부분 같다.

| # | 명세 항목 | official | chat-memory | community |
|---|---|---|---|---|
| 1 | PRM 제공(MCP MUST, 필드 정의는 RFC 9728 §2), `resource` 일치(RFC 9728 §3.3) | 예 — `SecurityConfig#protectedResourceMetadata`, C2 `resource` 일치 | 예 — 같음 | 예 — 모듈 `McpServerOAuth2Configurer#protectedResourceMetadataCustomizer`, C2 |
| 2 | 401 의 `resource_metadata`(MCP MUST) | 예 — `resourceMetadataEntryPoint`, C1 | 예 — 같음 | 예 — 모듈 진입점 대신 같은 Spring 진입점, C1 |
| 3 | 클라이언트의 PRM 발견과 fallback 순서(MUST) | 예 — `McpAuthorizationDiscovery#protectedResourceMetadata`(챌린지→경로형→루트형), `McpAuthorizationDiscoveryTest` | 예 — 같음 | 예 — 모듈 `McpMetadataDiscoveryService#getMcpMetadata`, 같은 순서 |
| 4 | AS 메타데이터 발견 순서와 `issuer` 검증(MUST) | 예 — `McpAuthorizationDiscovery#metadataUrls`(RFC 8414→OIDC), `#메타데이터의_issuer_가_다르면_실패한다` | 예 — 같음 | 예 — `McpAuthorizationDiscovery` 가 이어받음, 같은 테스트 |
| 5 | `code_challenge_methods_supported` 확인(MUST) | 예 — `#PKCE_S256_을_광고하지_않으면_진행하지_않는다` | 예 — 같음 | 예 — 같음 |
| 6 | PKCE S256(MUST) | 예 — `withPkce()` + `require-proof-key: true`, C17 | 예 — 같음 | 예 — 같음(인가 서버 설정 공유) |
| 7 | `resource` 파라미터 — 인가·토큰·갱신(MUST) | 예 — `ResourceIndicators`(세 지점), C5·C6·C11 | 예 — 같음 | 예 — 같음 |
| 8 | 토큰 audience 발급과 검증(MUST) | 예 — `ResourceAudienceTokenCustomizer` + `audiences` 설정, C6-1·C12 | 예 — 같음 | 예 — `ResourceAudienceTokenCustomizer` + 모듈 `AudienceValidationJwtDecoder`(기대값을 요청 URL 로 계산 — official 방식과 다름, [4.9](#s4-9) 각주) |
| 9 | RFC 9207 `iss` — 보내기(SHOULD, MCP 2026-07-28)·AS 광고(보내면 MUST, RFC 9207 §2.3)·클라이언트 검증(present 면 MUST) | 예 — `IssuerIdentifyingAuthorizationResponseHandler`(광고), `AuthorizationResponseIssuerFilter`(검증), S18·S19 | 예 — 같음 | 예 — 같음(`McpAuthorizationStandardConfig`) |
| 10 | 자격증명의 issuer 바인딩(2026-07-28) | 예(기록 방식은 문구와 다름) — `DiscoveredClientRegistrationRepository`, `#자격증명이_묶인_인가_서버가_아니면_쓰지_않는다`. 명세는 issuer 를 `code_verifier` 와 같은 요청별 기록에 넣으라고 하지만, 이 practice 는 요청별 기록에 `registrationId` 만 넣고 issuer 는 그 등록(발견 결과, 프로세스 수명 동안 캐시)에서 꺼낸다. 인가 서버가 하나이고 발견 결과가 실행 중에 바뀌지 않아 비교 대상은 같다([4.6](#s4-6)) | 예(기록 방식은 문구와 다름) — 같음 | 예(기록 방식은 문구와 다름) — 같음(모듈이 이어받음) |
| 11 | `Origin` 검증(MUST), `Host` 검증 | 예 — SDK `DefaultServerTransportSecurityValidator`, C13·S14 | 예 — 같음 | 예 — 모듈 `OriginValidationFilter`(내부는 같은 SDK 검증기), 테스트로 421 확인 |
| 12 | HTTPS(MUST, [7.8](#s7-8)) | **아니오** — 인가 서버·MCP 서버·에이전트 전부 `http://localhost`. 로컬 데모 목적([9절](#s9)) | **아니오** — 같음 | **아니오** — 같음 |
| 13 | 토큰 passthrough 금지(MUST, [7.1](#s7-1)) | 예 — 하류 API 가 없고 사용자 토큰만 부착(`OAuth2TokenAttachingRequestCustomizer`) | 예 — 같음 | 예 — 모듈 `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` |
| 14 | Dynamic Client Registration(RFC 7591, 2026-07-28 deprecated) | 다루지 않음 — 켜지 않음(Spring Authorization Server 기본값), C3 `registration_endpoint` 없음 | 다루지 않음 — 같음 | 다루지 않음 — `dynamic-client-registration.enabled: false` 로 명시적으로 끔 |
| 15 | Client ID Metadata Document(CIMD) | 다루지 않음 — HTTPS `client_id` 가 전제([9절](#s9)) | 다루지 않음 — 같음 | 다루지 않음 — 같음 |
| 16 | scope 설계·step-up 인가 | 다루지 않음 — 인증된 요청은 모든 툴 허용([4.9](#s4-9)), 다음 practice(`mcp-security-authz`) 범위([9절](#s9)) | 다루지 않음 — 같음 | 다루지 않음 — 같음 |
| 17 | RFC 9068 액세스 토큰 프로파일 (MCP 은 요구하지 않음 — 참고) | 아니오(불일치) — `client_id` 클레임 없음, `scope` 가 공백 구분 문자열이 아닌 JSON 배열([4.7](#s4-7)). MCP 이 이 프로파일을 요구하지 않으므로 MCP 준수에는 영향 없음, RFC 9068 을 전제로 한 리소스 서버와는 비호환 | 아니오(불일치) — 같음 | 아니오(불일치) — 같음(토큰 발급이 같은 Spring Authorization Server 기본 동작) |
| 18 | `invalid_client` 401 의 `WWW-Authenticate`(RFC 6749 §5.2 · OAuth 2.1 §3.2.4 **MUST**) | **예** — `AuthorizationServerConfig` 가 `clientAuthentication(...).errorResponseHandler(new ClientAuthenticationChallengeFailureHandler())` 로 새로 건다. Spring Authorization Server 의 `OAuth2ClientAuthenticationFilter#onAuthenticationFailure` 는 이 요구를 TODO 로만 남기고 헤더를 붙이지 않으므로(spring-security 이슈 [#18285](https://github.com/spring-projects/spring-security/issues/18285), 미해결, 7.2.0-M1 도 동일) practice 가 직접 구현했다. `WWW-Authenticate: Basic realm="http://localhost:9010"`, S8([4.7](#s4-7)) | **예** — 같음(`AuthorizationServerConfig`) | **예** — 모듈 확장점을 쓰는 `McpAuthorizationStandardConfig` 가 같은 핸들러를 건다 |
| 19 | SSE 이벤트 `id` 의 세션 내 전역 유일성(MCP 2025-11-25 Resumability and Redelivery **MUST**) | **아니오** — 이벤트 `id` 로 세션 ID 를 그대로 써서 한 세션의 여러 이벤트가 같은 `id` 를 가진다(C9·C10). 고치려면 이벤트 `id` 를 정하는 내부 세션 전송 클래스(`WebMvcStreamableMcpSessionTransport`, `private`)를 갈아끼워야 하는데, 이를 감싼 `WebMvcStreamableServerTransportProvider` 자체가 `public final` 이라 상속으로 확장할 수 없다 — 이 practice 가 쓰는 2.0.0 기준 838줄짜리 전송 구현 전체를 practice 안에 복사(라이브러리 포크)해야 고칠 수 있다. 원인이 되는 그 줄(`sseBuilder.id(messageId != null ? messageId : this.sessionId)`, 2.0.0 650행)은 2.0.1(766행)에서도 동일하지만, 이는 그 한 줄에 한정된 확인이다([4.8](#s4-8)) | **아니오** — 같음(같은 SDK) | **아니오** — 같음(같은 SDK) |
| 20 | `token_endpoint_auth_signing_alg_values_supported`·`revocation_endpoint_auth_signing_alg_values_supported`·`introspection_endpoint_auth_signing_alg_values_supported` — `private_key_jwt`·`client_secret_jwt` 를 광고하면 포함해야 하는 조건부 **MUST**(RFC 8414 §2) | **예** — `AuthorizationServerConfig` 가 AS 메타데이터·OIDC 디스커버리 양쪽의 `authorizationServerMetadataCustomizer`/`providerConfigurationCustomizer` 에 세 claim 을 추가한다. 값은 지어낸 목록이 아니라 Spring Authorization Server 의 `JwtClientAssertionDecoderFactory` 가 `client_secret_jwt`·`private_key_jwt` 인증에서 실제로 검증기를 만들어내는 알고리즘 전부(`MacAlgorithm` HS256/384/512 + `SignatureAlgorithm` RS/ES/PS 256/384/512, `none` 제외)다. 최신 Spring Security 에는 이 claim 상수 자체가 없어 문자열 리터럴로 직접 심었다([E3](#e3), [E4](#e4)) | **예** — 같음(`AuthorizationServerConfig`) | **예** — `McpAuthorizationStandardConfig` 가 모듈 확장점으로 같은 claim 을 심는다 |
| 21 | 발견 결과 재검증 — 리소스 서버가 새 챌린지로 메타데이터 변경을 알리면 클라이언트가 다시 받아 검증(RFC 9728 §5.2 **SHOULD**) | **아니오** — 발견 결과(PRM·AS 메타데이터)를 프로세스 수명 동안 캐시하고, 실행 중 받은 401 로 다시 읽지 않는다([4.10](#s4-10)) | **아니오** — 같음 | **아니오** — 같음(같은 캐시 전략) |
| 22 | `GET /mcp` SSE 스트림의 응답 시작을 클라이언트가 확인할 수 있는가 (명세에 규정 없음 — 참고) | 해당 없음 — 서버는 명세대로 SSE 로 응답하거나 405 를 고를 수 있고 SSE 를 골랐지만(위반 아님), `ServerResponse.sse(...)` 가 첫 이벤트를 보낼 때에야 헤더를 내보내 보낼 메시지가 없으면 5초 넘게 상태줄조차 오지 않는다(S11, curl 종료 코드 28)([E10](#e10)) | 해당 없음 — 같음 | 해당 없음 — 같음(같은 서버 전송) |

12번(HTTPS)은 세 practice 모두 위반이다. 이유는 로컬에서 인가 서버·MCP 서버·에이전트를 각각 다른 포트로 띄우고 TLS 종단 없이 요청·응답을 그대로 관측하기 위해서다([9절](#s9)). 19번(SSE 이벤트 `id` 유일성)도 세 practice 모두 MUST 를 지키지 않는다 — 원인이 MCP Java SDK 서버 전송의 `public final`·`private` 클래스 구조에 있어(위 19번 근거) 이 practice 가 라이브러리를 포크하지 않는 한 고칠 수 없다. 18번(`invalid_client` 의 `WWW-Authenticate`)과 20번(조건부 `..._auth_signing_alg_values_supported` 세 필드)은 Spring Authorization Server 가 구현하지 않는 부분이었지만 세 practice 모두 `ClientAuthenticationChallengeFailureHandler` 와 메타데이터 커스터마이저로 직접 메워 이제 MUST 를 지킨다. 21번(발견 결과 재검증)은 SHOULD 를 이행하지 않는다. 17번(RFC 9068 프로파일)과 22번(GET 스트림 응답 시작 확인)은 MCP 이 요구하지 않거나 명세에 규정이 없는 참고 항목이라 MUST/SHOULD 위반으로 세지 않는다. 남은 MUST 위반은 12번(HTTPS)과 19번(SSE 이벤트 `id`) 둘뿐이고, 나머지 MUST 항목(1~11, 13, 18, 20)은 세 practice 모두 관측 또는 테스트로 확인했다.

---

<a id="s9"></a>

## 9. 이 practice 에서 다루지 않는 것

| 항목 | 다루지 않는 이유 |
|---|---|
| 2026-07-28 전송(stateless) | 세 practice 가 쓰는 MCP Java SDK 2.0.0 의 `ProtocolVersions` 가 `2025-11-25` 까지만 안다([1.1](#s1), [6.4](#s6-4)). `_meta` 기반 요청, `server/discover`, `Mcp-Method`/`Mcp-Name` 헤더를 SDK 가 만들거나 받지 않는다. |
| Client ID Metadata Document(CIMD) | `client_id` 로 쓰는 문서 URL 이 `https` 스킴이어야 한다(**MUST**, [4.4](#s4-4)). 이 practice 는 인가 서버·에이전트를 전부 `http://localhost` 로 띄우므로 이 전제를 만족할 수 없다. |
| Dynamic Client Registration(DCR) | 2026-07-28 에서 deprecated 로 표시되고 CIMD 로 대체됐다([4.4](#s4-4)). 사전 등록만으로도 흐름을 다 보일 수 있어 새로 켜지 않았다. |
| HTTPS | 인가 서버·MCP 서버·에이전트를 로컬에서 여러 포트로 띄워 요청·응답을 그대로 관측하는 것이 이 practice 들의 목적이다. TLS 종단을 두면 관측 스크립트(`curl`)와 설정이 늘어나는 대신, 이 문서가 다루는 인가 흐름 자체에는 새로운 것이 생기지 않는다([7.8](#s7-8), [8절](#s8) 12번). |
| scope 설계·step-up 인가 | 이 practice 의 MCP 서버는 인증된 요청이면 모든 툴을 허용한다([4.9](#s4-9)) — scope 를 나누지 않는다. scope 최소화, 런타임 `insufficient_scope` 챌린지, step-up 재인가는 다음 practice(`mcp-security-authz`)의 범위다. |

---

<a id="s10"></a>

## 10. 출처

### MCP 명세

- 2025-11-25 Authorization: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- 2025-11-25 Transports: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- 2025-11-25 Lifecycle: https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
- 2025-11-25 Security Best Practices: https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices
- 2025-11-25 Changelog: https://modelcontextprotocol.io/specification/2025-11-25/changelog
- 2025-03-26 Authorization(비교용, [2.3](#s2)): https://modelcontextprotocol.io/specification/2025-03-26/basic/authorization
- 2025-06-18 Changelog(비교용, [2.3](#s2)): https://modelcontextprotocol.io/specification/2025-06-18/changelog
- 2026-07-28 Key Changes: https://modelcontextprotocol.io/specification/2026-07-28/changelog
- 2026-07-28 Streamable HTTP: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- 2026-07-28 Authorization: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
- 2026-07-28 Authorization Server Discovery: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery
- 2026-07-28 Client Registration: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration
- 2026-07-28 Authorization Security Considerations: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations
- 2026-07-28 server/discover(Discovery): https://modelcontextprotocol.io/specification/2026-07-28/server/discover

### RFC · OAuth · OpenID

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

### 그 밖에 본문에서 인용한 문서

- RFC 9110 HTTP Semantics(auth-param 문법, 헤더 대소문자, Misdirected Request): https://www.rfc-editor.org/rfc/rfc9110
- RFC 8705 mTLS Client Authentication(`tls_client_certificate_bound_access_tokens`): https://www.rfc-editor.org/rfc/rfc8705
- RFC 9449 DPoP(`dpop_signing_alg_values_supported`): https://www.rfc-editor.org/rfc/rfc9449
- RFC 9396 Rich Authorization Requests(`authorization_details_types_supported`): https://www.rfc-editor.org/rfc/rfc9396
- RFC 8693 OAuth 2.0 Token Exchange(`scope` 클레임 형식): https://www.rfc-editor.org/rfc/rfc8693
- OpenID Connect Core 1.0: https://openid.net/specs/openid-connect-core-1_0.html
- JSON-RPC 2.0: https://www.jsonrpc.org/specification

---
