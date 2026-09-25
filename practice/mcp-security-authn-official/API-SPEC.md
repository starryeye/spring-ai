# mcp-security-authn-official API 명세

`auth-server`·`shop-mcp-server` 가 구현하는 표준 endpoint 는 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 가 필드 단위로 정의한다. 이 문서는 그 표준 endpoint 를 이 practice 의 값으로 잇는 인덱스와, 표준에 없는 `shop-agent` 고유 endpoint·MCP tool 의 전체 명세를 담는다.

## 목차

| 앵커 | 내용 |
|---|---|
| [`auth-server`](#auth-server) | Authorization Server(`:9010`) endpoint 인덱스 |
| [`mcp-server`](#mcp-server) | MCP Server(`:8111`) endpoint 인덱스 |
| [`agent`](#agent) | Agent(`:8110`) 고유 endpoint 인덱스 |
| [`agent-login`](#agent-login) | Agent 로그인 두 endpoint 전체 명세 |
| [`api-chat`](#api-chat) | `POST /api/chat` 전체 명세 |
| [`tool-search-products`](#tool-search-products) | MCP tool `searchProducts` |
| [`tool-get-stock`](#tool-get-stock) | MCP tool `getStock` |

---

<a id="auth-server"></a>

## Authorization Server(`:9010`) endpoint

각 행의 표준 명세는 [MCP-API-SPEC.md](../MCP-API-SPEC.md) 에 필드 단위로 있다. 여기서는 endpoint 와 이 practice 의 값만 잇는다.

| endpoint | 역할 | 이 practice 값 | 명세 |
|---|---|---|---|
| `GET /.well-known/oauth-authorization-server` | Authorization Server Metadata 제공 | `issuer=http://localhost:9010`, `code_challenge_methods_supported=["S256"]`, `token_endpoint_auth_methods_supported` 에 `none` 추가(`AuthorizationServerConfig`) | [`as-metadata`](../MCP-API-SPEC.md#as-metadata) |
| `GET /.well-known/openid-configuration` | OIDC provider metadata 제공 | `AuthorizationServerConfig` 가 `.oidc(...)` 로 직접 켠다(filter chain 을 직접 정의해 Boot 기본 chain 이 물러나므로, [README](README.md#filter-chain-을-직접-정의하면-oidc-discovery-도-직접-켠다)). 위 값에 `userinfo_endpoint`·`end_session_endpoint` 추가 | [`oidc-discovery`](../MCP-API-SPEC.md#oidc-discovery) |
| `GET /oauth2/authorize` | authorization request 접수, PKCE·`resource`·scope 검증 | `require-proof-key: true`. `ResourceIndicatorValidator` 가 `resource` 를, `PublicClientScopeValidator` 가 public client 의 scope 를 검사 | [`authorize`](../MCP-API-SPEC.md#authorize) |
| `POST /oauth2/authorize` | public client 의 consent 제출 | `PublicClientConsentService` 가 public client 의 consent 를 저장하지 않아 매 요청 consent 화면을 거친다 | [`authorize-consent`](../MCP-API-SPEC.md#authorize-consent) |
| authorization response(redirect) | code·`state`·`iss` 전달 | `IssuerIdentifyingAuthorizationResponseHandler` 가 성공·오류 모두에 `iss` 를 싣는다 | [`authorization-response`](../MCP-API-SPEC.md#authorization-response) |
| `POST /oauth2/token`(`authorization_code`) | access token·refresh token·ID token 발급 | `ResourceAudienceTokenCustomizer` 가 access token `aud` 를 `resource` 로 지정하고, token request 의 `resource` 를 authorization request 와 대조 | [`token-authorization-code`](../MCP-API-SPEC.md#token-authorization-code) |
| `POST /oauth2/token`(`refresh_token`) | token 갱신 | 같은 커스터마이저 재적용. public client 는 발급하지 않음(`refresh_token` 없음) | [`token-refresh`](../MCP-API-SPEC.md#token-refresh) |
| `GET /oauth2/jwks` | 서명 key 공개 | Boot 자동설정 기본값 그대로 | [`jwks`](../MCP-API-SPEC.md#jwks) |
| client 인증 실패 응답 | `WWW-Authenticate` challenge | `ClientAuthenticationChallengeFailureHandler` 가 `Authorization` 헤더로 시도한 실패에 붙인다 | [4.7](../MCP-AUTHORIZATION.md#s4-7) |
| Client ID Metadata Document | client 자기 등록 | 쓰지 않음 — `https` `client_id` 가 전제라 이 practice 는 구현하지 않는다 | [`cimd-document`](../MCP-API-SPEC.md#cimd-document) |
| `POST /register`(Dynamic Client Registration) | 동적 client 등록 | 쓰지 않음 — 명세 기준 endpoint 이고 켜지 않는다 | [`dcr-register`](../MCP-API-SPEC.md#dcr-register) |

**오류**

표준 오류 전체는 [`authorize`](../MCP-API-SPEC.md#authorize)·[`token-authorization-code`](../MCP-API-SPEC.md#token-authorization-code) 에 있다. 여기서는 이 practice 의 클래스가 내는 오류만 적는다.

| 상황 | 응답 | 이 practice |
|---|---|---|
| authorization request 의 `resource` 가 허용 목록 밖 | redirect 로 `error=invalid_target`(C16) | `ResourceIndicatorValidator` |
| authorization request 의 `resource` 가 여러 개 | redirect 로 `error=invalid_target` | `ResourceIndicatorValidator` — `McpResourceProperties#isAllowed` 는 문자열 하나만 받는다. 테스트 `인가_요청의_resource_가_여러_개면_invalid_target_이다` |
| public client 가 scope 를 생략하거나 `openid` 하나만 요청 | redirect 로 `error=invalid_scope`, `error_uri` 는 RFC 6749 §3.3(P8-1) | `PublicClientScopeValidator` — consent 판정 전에 거부한다 |
| token request 의 `resource` 가 authorization request 와 다름 | `400` `invalid_target`(S6) | `ResourceAudienceTokenCustomizer` |
| authorization request 에 없던 `resource` 를 token request 에서 보냄 | `400` `invalid_target` | `ResourceAudienceTokenCustomizer` — 테스트 `인가_요청에_없던_resource_를_토큰_요청에서_정하면_invalid_target_이다` |
| token request 의 `resource` 가 여러 개 | `400` `invalid_target` | `ResourceAudienceTokenCustomizer` — 여러 값은 authorization request 의 값과 같지 않다. 테스트 `토큰_요청의_resource_가_여러_개면_invalid_target_이다` |
| `Authorization` 헤더로 시도한 client 인증 실패 | `401` `invalid_client` + `WWW-Authenticate: Basic`(S8) | `ClientAuthenticationChallengeFailureHandler` |

---

<a id="mcp-server"></a>

## MCP Server(`:8111`) endpoint

| endpoint | 역할 | 이 practice 값 | 명세 |
|---|---|---|---|
| `POST /mcp`(token 없음) | token 없는 요청에 `401` | `WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"`(C1) | [`mcp-unauthenticated`](../MCP-API-SPEC.md#mcp-unauthenticated) |
| `GET /.well-known/oauth-protected-resource[/mcp]` | Protected Resource Metadata 제공 | `resource=http://localhost:8111/mcp`, `authorization_servers=["http://localhost:9010"]`(C2) | [`prm`](../MCP-API-SPEC.md#prm) |
| `POST /mcp`(Bearer) | `initialize`·`notifications/initialized`·`tools/list`·`tools/call` | `McpTransportSecurityFilter`(인증 전) → `SecurityConfig`(서명·`iss`·`aud`·`exp`) → `McpProtocolVersionFilter` 순서로 검증한다 | [`mcp-post`](../MCP-API-SPEC.md#mcp-post) |
| `GET /mcp` | 서버발 메시지용 SSE stream | `POST /mcp` 와 같은 filter 를 거친다 | [`mcp-get`](../MCP-API-SPEC.md#mcp-get) |
| `DELETE /mcp` | session 종료 | `200`(C18), 끝난 session ID 로 다시 요청하면 `404`(S16) | [`mcp-delete`](../MCP-API-SPEC.md#mcp-delete) |

**오류**

검사 순서는 시퀀스 [`mcp-server-validation`](SEQUENCES.md#mcp-server-validation) 에, 표준 오류 전체는 [`mcp-post`](../MCP-API-SPEC.md#mcp-post) 에 있다.

| 상황 | 응답 | 이 practice |
|---|---|---|
| `Origin` 이 있는데 허용 목록에 없음 | `403` `Invalid Origin header`(C13). token 이 없어도 `401` 보다 먼저다 | `McpTransportSecurityFilter` — 허용 Origin 을 두지 않는다. 테스트 `token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다` |
| `Host` 가 `localhost:8111`·`127.0.0.1:8111` 이 아님 | `421` `Invalid Host header`(S14). token 이 없어도 `401` 보다 먼저다 | `McpTransportSecurityFilter` — 테스트 `token_이_없어도_허용되지_않은_Host_는_인증보다_먼저_421이다` |
| `Authorization` 헤더 없음 | `401` + `resource_metadata`(C1) | `SecurityConfig#resourceMetadataEntryPoint` |
| 서명·`iss`·`aud`·`exp` 중 하나라도 틀림 | `401` `error="invalid_token"`(S3·C12) | `SecurityConfig` 의 `JwtDecoder` — `issuer-uri`·`audiences` 설정 |
| 지원하지 않는 `MCP-Protocol-Version` | `400` `-32600`(C15) | `McpProtocolVersionFilter` |
| 다른 사용자가 연 `Mcp-Session-Id` | token 이 유효하면 통과한다 | session 을 사용자에 묶지 않는다([허브 5.8](../MCP-AUTHORIZATION.md#s5-8), 준수표 28번) |

---

<a id="agent"></a>

## Agent(`:8110`) 고유 endpoint

표준에 없는, 이 practice 의 UI·로그인 배선이다.

| endpoint | 역할 | 자세히 |
|---|---|---|
| `GET /` | 정적 화면(`index.html`) 제공. 미로그인이면 `SecurityConfig` 가 `/oauth2/authorization/authserver` 로 redirect 한다 | [`agent-login`](#agent-login) |
| `GET /oauth2/authorization/authserver` | authorization request 시작 | [`agent-login`](#agent-login) |
| `GET /login/oauth2/code/authserver` | authorization response callback, `iss` 검증 | [`agent-login`](#agent-login) |
| `POST /api/chat` | 채팅 메시지 처리, MCP tool 호출. `X-XSRF-TOKEN` 헤더가 없으면 `403` | [`api-chat`](#api-chat) |

---

<a id="agent-login"></a>

## Agent 로그인 — `GET /oauth2/authorization/authserver` · `GET /login/oauth2/code/authserver`

Agent 는 사용자를 Authorization Server 로 보내고, 돌아온 authorization response 의 `iss` 를 코드 교환 전에 검증한다.
두 endpoint 모두 표준 endpoint 가 아니라 `SecurityConfig`·`AuthorizationResponseIssuerFilter` 가 만드는 Agent 쪽 진입점이다.

근거: [4.5](../MCP-AUTHORIZATION.md#s4-5), [4.6](../MCP-AUTHORIZATION.md#s4-6), 시퀀스 [`agent-login`](SEQUENCES.md#agent-login)

### `GET /oauth2/authorization/authserver`

| 호출자 | 언제 | 응답 |
|---|---|---|
| Browser | 미로그인 상태로 아무 경로나 열어 `SecurityConfig` 의 `loginPage` 로 왔을 때, 또는 이 경로를 직접 열었을 때 | `302 Location`: Authorization Server 의 `/oauth2/authorize`, `code_challenge_method=S256`·`resource=http://localhost:8111/mcp` 포함(S17) |

### `GET /login/oauth2/code/authserver`

| 호출자 | 언제 | 응답 |
|---|---|---|
| Browser | Authorization Server 가 authorization response 로 redirect 했을 때 | 정상: `302 Location: http://localhost:8110/`(S20). `iss` 불일치: `401`(S18). `iss` 없음(광고된 경우): `401`(S19) |

`iss` 검증은 `AuthorizationResponseIssuerFilter` 가 `OAuth2LoginAuthenticationFilter` 앞에서 하고, 실패하면 `LoginFailureHandler` 가 `401` 과 `text/plain;charset=UTF-8` 본문으로 끝낸다.
성공하면 code 는 `RestClientAuthorizationCodeTokenResponseClient`(`resource` 포함)로 교환된다.

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| callback 의 `iss` 가 기록한 issuer 와 다름 | `401`, `로그인 실패: iss mismatch: expected <issuer> but got <iss>`(S18) | `AuthorizationResponseIssuerFilter` — RFC 9207 §2.4 검증은 MUST |
| Authorization Server 가 `iss` 지원을 광고했는데 callback 에 없음 | `401`, `로그인 실패: iss is missing although the authorization server advertises it`(S19) | 같음 |

**예시** (S18)

```http
GET /login/oauth2/code/authserver?code=...&state=...&iss=http://evil.example HTTP/1.1
```

```http
HTTP/1.1 401
Content-Type: text/plain;charset=UTF-8

로그인 실패: iss mismatch: expected http://localhost:9010 but got http://evil.example
```

---

<a id="api-chat"></a>

## `POST /api/chat`

채팅 메시지를 받아 `ChatClient` 로 LLM 에 전달하고, tool 호출을 거친 답을 스트리밍으로 돌려준다.
MCP 표준이 아니라 이 practice 의 UI 배선이고, 인증은 `SecurityConfig` 의 `anyRequest().authenticated()`, CSRF 는 `csrf.spa()` 를 따른다.

근거: `ChatController`, `SecurityConfig`, 시퀀스 [`mcp-call`](SEQUENCES.md#mcp-call)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 본문 | 본문 | 필수 — 이 practice 고유 API | 사용자 메시지 평문(JSON 아님) | 씀 — `index.html` 의 `fetch('/api/chat', {method: 'POST', headers: csrfHeaders(), body: message})` |
| `X-XSRF-TOKEN` | 헤더 | 필수 — 이 practice 고유 API | `XSRF-TOKEN` 쿠키 값. `csrf.spa()` 가 쿠키를 싣고, `index.html` 의 `csrfHeaders()` 가 헤더로 되돌려 보낸다 | 씀 — 테스트 `ChatCsrfTest#페이지가_준_XSRF_TOKEN_을_헤더로_보내면_채팅이_시작된다` |

원문 필드: 없음(이 practice 고유 API)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200` + `Content-Type: text/plain;charset=UTF-8` | 표시 없음 | `ChatClient.stream().content()` 의 텍스트를 그대로 스트리밍 | 씀 — `ChatController#chat` |

원문 필드: 없음(이 practice 고유 API)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `X-XSRF-TOKEN` 헤더가 없거나 쿠키 값과 다름 | `403` | `SecurityConfig` 의 `csrf.spa()` — `/api/chat` 도 예외 없이 검사한다. 테스트 `ChatCsrfTest#CSRF_토큰_없이_채팅하면_403` |
| CSRF 는 통과했지만 session 없이 호출(미로그인) | `302 Location: /oauth2/authorization/authserver` | `SecurityConfig` 가 `/api/chat` 도 `anyRequest().authenticated()` 로 막아, 로그인 진입점과 같은 응답이 온다 |

**예시** (browser 화면)

```
POST /api/chat
X-XSRF-TOKEN: <XSRF-TOKEN 쿠키 값>

노트북 재고 있어?
```

```
노트북 관련 상품 재고 정보입니다:

1. [p1] 게이밍 노트북 15인치 - 1,890,000원 / 재고 7개
2. [p2] 사무용 노트북 14인치 - 990,000원 / 재고 23개

필요하신 상품이 있으시면 구체적인 상품명이나 ID를 알려주세요.
```

---

<a id="tool-search-products"></a>

## MCP tool `searchProducts`

판매 중인 상품을 이름·카테고리 키워드로 검색한다. `ProductTools#searchProducts` 가 구현하고, SYNC MCP Server 라 반환 타입은 평문 `String` 이다.

근거: `ProductTools.java` 의 `@McpTool`·`@McpToolParam`

**입력 스키마** (C9)

| 이름 | 타입 | required | 설명 |
|---|---|---|---|
| `keyword` | `string` | 아니오 | 상품명 또는 카테고리명의 일부. 가격·재고 같은 조건이나 문장을 넣으면 안 된다. 특정 상품을 지목하지 않는 질문이면 생략한다 |

annotation(C9, 코드가 지정하지 않은 SDK 기본값): `readOnlyHint=false`·`destructiveHint=true`·`idempotentHint=false`·`openWorldHint=true`.

**응답**

`content[0].text` 에 평문 결과가 담긴다. 일치하는 상품이 없으면 `'<keyword>' 에 해당하는 상품이 없습니다.`, 있으면 한 줄에 하나씩 `- [<id>] <name> (<category>) / <price>원 / 재고 <stock>개` 형식이다.

**예시** (코드 기준 — 캡처 없음. `ProductRepository` seed data 에 `keyword=노트북` 적용)

```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"- [p1] 게이밍 노트북 15인치 (노트북) / 1,890,000원 / 재고 7개\n- [p2] 사무용 노트북 14인치 (노트북) / 990,000원 / 재고 23개"}],"isError":false}}
```

---

<a id="tool-get-stock"></a>

## MCP tool `getStock`

상품 ID 로 현재 재고 수량을 조회한다. `ProductTools#getStock` 이 구현한다.

근거: `ProductTools.java` 의 `@McpTool`·`@McpToolParam`

**입력 스키마** (C9)

| 이름 | 타입 | required | 설명 |
|---|---|---|---|
| `productId` | `string` | 예 | 상품 ID. 예: p1 |

annotation(C9, 코드가 지정하지 않은 SDK 기본값): `readOnlyHint=false`·`destructiveHint=true`·`idempotentHint=false`·`openWorldHint=true`.

**응답**

`content[0].text` 에 평문 결과가 담긴다. 재고가 0 이면 `상품 <id> (<name>) 는 현재 품절입니다. (재고 0개)`, 있으면 `상품 <id> (<name>) 의 현재 재고는 <stock>개입니다.`, 모르는 ID 면 `상품 <id> 를 찾을 수 없습니다.`

**예시** (C10, `productId=p1`)

```json
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"상품 p1 (게이밍 노트북 15인치) 의 현재 재고는 7개입니다."}],"isError":false}}
```
