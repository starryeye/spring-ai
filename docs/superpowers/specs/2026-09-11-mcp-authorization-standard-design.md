# MCP 인가(Authorization) 표준 준수 — 설계

## 목표

1. 인증 practice 세 개(`mcp-security-authn-community`, `mcp-security-authn-official`,
   `mcp-security-authn-chat-memory`)를 MCP 인가 명세에 맞게 고친다.
2. `practice/MCP-AUTHORIZATION.md` 한 문서만 읽으면 인증이 포함된 MCP 명세를 처음부터
   끝까지 배울 수 있게 쓴다. 세 practice 의 README 는 이 문서로 링크한다.

## 기준 명세

| 계층 | 기준 리비전 | 이유 |
|---|---|---|
| 전송·수명주기 (Streamable HTTP, initialize, 세션) | **2025-11-25** | MCP Java SDK 2.0.0 의 `ProtocolVersions` 가 2025-11-25 까지만 안다. 2026-07-28 전송은 SDK 가 지원하지 않는다. |
| 인가 (discovery, PKCE, resource, 토큰 검증) | **2025-11-25 + 2026-07-28 추가분** | 인가 규칙은 전송과 무관하게 HTTP 계층에서 구현할 수 있다. 2026-07-28 에서 추가된 RFC 9207 `iss` 검증과 issuer-bound 자격증명까지 반영한다. |

문서는 두 시대(2025-11-25 / 2026-07-28)를 모두 가르치되, practice 에서 **관측한 것**과
**명세 본문에서 온 것**을 표시해 구분한다.

## 현재 상태와 차이

| 항목 | 명세 | 현재 (official / chat-memory) | 현재 (community) |
|---|---|---|---|
| PRM (RFC 9728) | MUST | 루트 경로만 (`/.well-known/oauth-protected-resource`) | 모듈 기본값 (Task 1 에서 측정) |
| 클라이언트가 PRM → AS 를 발견 | MUST | 안 함. `issuer-uri` 하드코딩 | 안 함. `issuer-uri` 하드코딩 |
| PKCE S256 | MUST | 안 보냄 (confidential client) | 안 보냄 |
| AS 메타데이터의 `code_challenge_methods_supported` 확인 | MUST | 안 함 | 안 함 |
| `resource` 파라미터 (RFC 8707) | MUST | 안 보냄 | 모듈이 보냄 (단, 등록 경로에 따라 다름 — Task 1 측정) |
| 서버의 audience 검증 | MUST | 안 함 | 안 함 (`validateAudienceClaim` 기본 false) |
| AS 가 `aud` 를 resource 로 발급 | 필요 | 안 함 | 모듈이 하지만 `openid` 스코프가 있으면 건너뜀 |
| RFC 9207 `iss` (2026-07-28) | AS SHOULD / 클라 MUST(있으면) | 없음 | 없음 |
| Origin 검증 (Streamable HTTP) | MUST | 없음 | 없음 |

## 범위 밖 (문서에 이유와 함께 명시)

| 항목 | 이유 |
|---|---|
| 2026-07-28 전송 (stateless, `_meta`, `Mcp-Method` 헤더, `server/discover`) | SDK 미지원. 문서에서 시퀀스와 차이만 설명한다. |
| CIMD (Client ID Metadata Document) | `client_id` 가 HTTPS URL 이어야 한다. localhost HTTP 로는 성립하지 않는다. |
| DCR (RFC 7591) | 2026-07-28 에서 deprecated. 사전 등록(pre-registered)을 쓴다. |
| HTTPS | 학습용 localhost. 명세 위반임을 준수표에 표시한다. |
| scope 설계, step-up (403 `insufficient_scope`) | 다음 practice(`mcp-security-authz`) 주제. 문서에서는 흐름만 설명한다. |

## 변경 설계 — official (auth 9010 / mcp 8111 / agent 8110)

chat-memory(9020 / 8131 / 8130)는 official 의 복사본이므로 같은 변경을 포트만 바꿔 적용한다.
chat-memory 고유 기능(대화 격리, CSRF)은 건드리지 않고 기존 25개 테스트가 계속 통과해야 한다.

### 인가 서버 (auth-server)

1. **PKCE 강제** — 클라이언트 설정에 `require-proof-key: true`. `code_challenge` 없는 인가
   요청은 거부된다.
2. **RFC 8707 audience** — `OAuth2TokenCustomizer<JwtEncodingContext>` 빈 하나.
   - access token 에만 적용한다 (id_token 의 `aud` 는 client_id 그대로).
   - 토큰 요청의 `resource` 값을 `aud` 로 넣는다. `openid` 스코프 여부와 무관하게 적용한다.
   - 허용 목록(`mcp.authorization.resources`)에 없는 값은 `invalid_target` 으로 거부한다.
   - 인가 요청에 `resource` 가 있었다면 토큰 요청의 값과 같아야 한다.
   - refresh_token 그랜트에도 같은 규칙을 적용한다.
3. **RFC 9207 `iss`** — 인가 엔드포인트의 `authorizationResponseHandler` 가 성공 리다이렉트에
   `iss` 를 붙인다. 오류 리다이렉트(`errorResponseHandler`)에도 붙인다. AS 메타데이터에
   `authorization_response_iss_parameter_supported: true` 를 추가한다.
   (Spring Security 7.1 에 RFC 9207 지원이 없어서 직접 넣는다.)

### MCP 서버 (shop-mcp-server)

1. **PRM 경로 형태** — `GET /.well-known/oauth-protected-resource/mcp` 가 아래를 반환한다.
   ```json
   { "resource": "http://localhost:8111/mcp",
     "authorization_servers": ["http://localhost:9010"],
     "bearer_methods_supported": ["header"] }
   ```
   `scopes_supported` 는 넣지 않는다 (scope 설계는 범위 밖 — 문서에 이 경우 클라이언트 동작을 설명).
2. **401 챌린지** — `BearerTokenAuthenticationEntryPoint.setResourceMetadataParameterResolver`
   로 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"`.
3. **audience 검증** — `spring.security.oauth2.resourceserver.jwt.audiences: http://localhost:8111/mcp`.
   issuer 검증은 기존대로 유지한다.
4. **Origin 검증** — `WebMvcStreamableServerTransportProvider` 빈을 직접 정의한다
   (자동설정 빈이 `@ConditionalOnMissingBean` 이다). `securityValidator(...)` 에
   `DefaultServerTransportSecurityValidator` 를 넣는다. 에이전트는 서버 간 호출이라 Origin
   헤더가 없다. Origin 없는 요청의 처리는 Task 1 에서 측정한다.

### 에이전트 (shop-agent)

1. **issuer-uri 제거, 발견(discovery)으로 대체** — `ClientRegistrationRepository` 를 직접
   구현한다. 첫 조회 때 한 번 발견하고 결과를 캐시한다(지연 발견이라 MCP 서버가 늦게 떠도
   에이전트 기동은 실패하지 않는다).
   1. `POST http://localhost:8111/mcp` 를 토큰 없이 보낸다 → 401.
   2. `WWW-Authenticate` 의 `resource_metadata` 를 쓴다. 없으면
      `/.well-known/oauth-protected-resource/mcp` → `/.well-known/oauth-protected-resource`
      순서로 시도한다.
   3. PRM 의 `resource` 가 요청한 MCP 서버 URL 과 **정확히 같은지** 확인한다(RFC 9728 §3.3).
   4. `authorization_servers[0]` 에서 AS 메타데이터를 가져온다. 경로 없는 issuer 는
      `/.well-known/oauth-authorization-server` → `/.well-known/openid-configuration` 순서.
   5. 메타데이터의 `issuer` 가 요청한 issuer 와 같은지, `code_challenge_methods_supported`
      에 `S256` 이 있는지 확인한다. 없으면 진행하지 않는다.
   6. **issuer-bound 자격증명** — 설정의 사전 등록 자격증명은 issuer 와 묶여 있다
      (`mcp.client.credentials.issuer`). 발견한 issuer 와 다르면 자격증명을 쓰지 않고 실패한다.
   7. 발견한 엔드포인트로 `ClientRegistration` 을 만든다.
2. **PKCE** — `OAuth2AuthorizationRequestCustomizers.withPkce()` (confidential client 도 강제).
3. **`resource` 파라미터** — 인가 요청(`additionalParameters`), 토큰 요청
   (`RestClientAuthorizationCodeTokenResponseClient.addParametersConverter`),
   refresh 요청(`RestClientRefreshTokenTokenResponseClient`) 모두에 PRM 의 `resource` 를 싣는다.
4. **`iss` 검증** — `/login/oauth2/code/*` 에서 `OAuth2LoginAuthenticationFilter` 앞에 필터를
   둔다. 저장된 인가 요청을 기준으로 `iss` 를 발견한 issuer 와 비교한다. AS 가
   `authorization_response_iss_parameter_supported: true` 를 광고했는데 `iss` 가 없어도 거부한다.
   거부 시 code 를 교환하지 않는다.

## 변경 설계 — community (auth 9000 / mcp 8101 / agent 8100)

community 는 모듈 자동설정을 쓰는 것이 이 practice 의 요점이므로, 자동설정을 유지하는 방향을
우선하고 막히는 곳만 직접 정의한다.

| 구성요소 | 방법 |
|---|---|
| 인가 서버 | 자동설정 유지. PKCE 는 `require-proof-key: true`. `aud` 는 사용자 `OAuth2TokenCustomizer` 빈을 추가한다(모듈이 기본 커스터마이저 뒤에 호출해 준다) — `openid` 스코프일 때 건너뛰는 모듈 동작을 보완한다. `iss` 와 메타데이터 claim 은 기존 `Customizer<McpAuthorizationServerConfigurer>` 확장점으로 넣는다. |
| MCP 서버 | 자동설정은 `validateAudienceClaim` 을 켤 수단이 없다. `SecurityFilterChain` 을 직접 정의하고 `McpServerOAuth2Configurer` 에 `validateAudienceClaim(true)` 를 준다(자동설정은 물러난다). Origin 검증은 official 과 같다. |
| 에이전트 | 발견은 모듈의 `McpMetadataDiscoveryService`(PRM `resource` 검증 포함)를 쓴다. PKCE·`resource`·`iss` 는 official 과 같은 방식으로 추가한다. 모듈이 이미 `resource` 를 싣는 경로를 쓸 수 있으면 그것을 쓴다(Task 1 측정). |

## 작업 순서

1. **측정 스파이크 (코드는 버림)** — 아래를 실제 실행으로 확인하고 결과를 계획에 반영한다.
   - Spring AS 가 인가·토큰 요청의 `resource` 를 토큰 커스터마이저 컨텍스트까지 전달하는가.
   - SDK 가 실제로 협상하는 `protocolVersion`, 그리고 `MCP-Protocol-Version`·`Mcp-Session-Id` 헤더.
   - `withPkce()` 적용 시 `code_challenge` 가 실제로 나가는가.
   - `authorizationResponseHandler` 로 `iss` 를 붙일 수 있는가.
   - `DefaultServerTransportSecurityValidator` 가 Origin 없는 요청을 어떻게 다루는가.
   - community 서버·에이전트의 현재 PRM 경로와 `resource` 전송 여부.
2. official — 인가 서버 → MCP 서버 → 에이전트 순서. 구성요소마다 테스트 포함.
3. official 종단 검증 — `run.sh` 로 띄우고 실제 요청·응답을 캡처한다(문서의 엔드포인트 명세 원본).
4. chat-memory — official 변경을 포팅. 기존 테스트 통과 확인.
5. community — 위 표대로. 종단 검증.
6. `practice/MCP-AUTHORIZATION.md` 작성.
7. 세 practice README 에서 바뀐 사실을 고치고 문서로 링크. 루트 README 에 문서 링크 추가.

## 테스트

| 구성요소 | 검증 |
|---|---|
| 인가 서버 | 메타데이터에 `authorization_response_iss_parameter_supported`·`S256` 존재 / PKCE 없는 인가 요청 거부 / 인가 응답에 `iss` 포함 / access token `aud` = resource (openid 스코프 포함 시에도) / 허용 목록 밖 resource → `invalid_target` / id_token `aud` 는 client_id |
| MCP 서버 | PRM 경로 형태 응답 본문 / 401 의 `resource_metadata` URL / `aud` 가 다른 토큰 → 401 / 올바른 토큰 → 통과 / 허용 안 된 Origin → 403 |
| 에이전트 | 발견 순서와 fallback / PRM `resource` 불일치 시 실패 / issuer 불일치·S256 미지원 시 실패 / 자격증명 issuer 불일치 시 실패 / 인가 요청에 `code_challenge`·`resource` / 토큰 요청에 `code_verifier`·`resource` / `iss` 불일치·누락 시 거부 |
| 종단 | 브라우저 로그인 → 채팅 → MCP 툴 호출 성공. chat-memory 는 alice/bob 격리가 여전히 성립. |

## 문서 `practice/MCP-AUTHORIZATION.md` 구성

작성 원칙: 시행착오·버그 수정 이야기를 넣지 않는다. 처음부터 올바른 흐름만 순서대로 설명한다.
시퀀스 다이어그램(mermaid)과 엔드포인트 명세는 필수다.

1. 이 문서의 범위 — 기준 리비전 두 개, 관측/명세 표시 규칙
2. 등장인물 — Resource Server(MCP 서버) / Authorization Server / MCP Client(에이전트) / 사용자 브라우저, practice 별 포트 표
3. 전체 흐름 한눈에 — 개요 시퀀스 다이어그램
4. 단계별 설명 (단계마다 시퀀스 다이어그램 + 요청·응답 + 근거 조항)
   1. 토큰 없는 요청과 401 `WWW-Authenticate`
   2. PRM 발견 (fallback 순서, `resource` 일치 검증)
   3. AS 메타데이터 발견 (RFC 8414 / OIDC 순서, issuer 일치, S256 확인)
   4. 클라이언트 등록 (사전 등록 → CIMD → DCR → 사용자 입력, 우선순위와 각 방식 개요)
   5. 인가 요청 (PKCE, `resource`, `state`)
   6. 콜백과 `iss` 검증 (mix-up 공격)
   7. 토큰 요청과 JWT access token 구조 (RFC 9068, `aud`)
   8. 인증된 MCP 요청 — initialize → notifications/initialized → tools/list → tools/call, `Mcp-Session-Id`, `MCP-Protocol-Version`
   9. MCP 서버의 토큰 검증 (서명, `iss`, `aud`, `exp`)
   10. 만료와 refresh
   11. 오류 응답 모음 (401 `invalid_token`, 403 `insufficient_scope`, `invalid_target`, 400/404 세션, 403 Origin)
5. 엔드포인트 명세 레퍼런스 — 엔드포인트별 메서드·URL·요청·응답·근거 조항 표
6. 2026-07-28 에서 달라지는 것 — stateless 시퀀스, 새 헤더, DCR deprecated, `iss`, issuer-bound 자격증명
7. 보안 고려사항 — token passthrough 금지, confused deputy, audience 검증 이유, discovery SSRF, redirect URI 정확 일치, localhost HTTP
8. 준수표 — MUST/SHOULD 항목 × 세 practice
9. 이 practice 들에서 다루지 않는 것
10. 출처

## 출처 (문서에 모두 싣는다)

- MCP 명세 2026-07-28, 2025-11-25 — basic/authorization, basic/transports, basic/lifecycle, basic/versioning, changelog, security best practices
- RFC 9728 OAuth 2.0 Protected Resource Metadata
- RFC 8414 OAuth 2.0 Authorization Server Metadata
- RFC 8707 Resource Indicators for OAuth 2.0
- RFC 9207 OAuth 2.0 Authorization Server Issuer Identification
- RFC 7636 PKCE
- RFC 6749 OAuth 2.0, RFC 6750 Bearer Token Usage
- RFC 7591 Dynamic Client Registration
- RFC 9068 JWT Profile for OAuth 2.0 Access Tokens
- OAuth 2.1 (draft-ietf-oauth-v2-1-13)
- OpenID Connect Discovery 1.0
- OAuth Client ID Metadata Document (draft-ietf-oauth-client-id-metadata-document-00)
