# 부록: API 레퍼런스 — endpoint별 field 사전

## 읽는 법

이 부록은 MCP authorization 흐름의 HTTP endpoint마다 명세가 정한 parameter·header·field를 모두 표로 모은 사전이다.
개념 설명은 각 절의 "설명" 줄이 가리키는 장에 있다.
"요구 수준" 칸은 원문 단어(REQUIRED, MUST 등)를 그대로 쓰고, 단어가 없으면 "표시 없음"이라고 쓰며, 명세마다 다르면 모두 적는다.
"official" 칸은 official practice(agent `shop-agent`와 public client `local-client` 포함)의 동작이고, chat-memory·community practice와 다른 점은 [준수표](reference-compliance.md)에 있다.
캡처 번호 `C<n>`·`S<n>`·`P<n>`은 [walkthrough](../../docs/superpowers/captures/2026-09-12-official.txt)·[supplement](../../docs/superpowers/captures/2026-09-16-official-supplement.txt)·[public client](../../docs/superpowers/captures/2026-09-25-official-public-client.txt) 캡처의 단계 번호다.
요청 줄과 요청 header는 캡처에 없어서, [`mcp-authorization-walkthrough.sh`](../../docs/superpowers/captures/mcp-authorization-walkthrough.sh)·[`mcp-authorization-supplement.sh`](../../docs/superpowers/captures/mcp-authorization-supplement.sh)·[`mcp-authorization-public-client.sh`](../../docs/superpowers/captures/mcp-authorization-public-client.sh)의 같은 단계 curl 명령으로 적는다.
JWT는 앞 20자, code와 refresh token은 앞 12자만 적는다.
기준 버전은 transport·lifecycle이 MCP 2025-11-25, authorization이 2025-11-25에 2026-07-28 추가분(`iss`, issuer binding)을 더한 것이다([9장](09-versions.md)).

## endpoint 목차

| 서버 | endpoint | 설명하는 장 |
|---|---|---|
| MCP Server | [MCP 요청 header](#mcp-요청-header) | [1장](01-mcp-basics.md), [6장](06-mcp-call-and-validation.md) |
| MCP Server | [`POST /mcp` — token 없는 요청](#post-mcp--token-없는-요청) | [3장](03-discovery.md), [6장](06-mcp-call-and-validation.md) |
| MCP Server | [`GET /.well-known/oauth-protected-resource[/mcp]`](#get-well-knownoauth-protected-resourcemcp--protected-resource-metadata) | [3장](03-discovery.md) |
| MCP Server | [`POST /mcp` — Bearer token](#post-mcp--bearer-token) | [1장](01-mcp-basics.md), [6장](06-mcp-call-and-validation.md), [7장](07-local-client.md) |
| MCP Server | [`GET /mcp`](#get-mcp--서버가-보내는-메시지의-sse-stream) | [1장](01-mcp-basics.md) |
| MCP Server | [`DELETE /mcp`](#delete-mcp--session-종료) | [1장](01-mcp-basics.md) |
| Authorization Server | [`GET /.well-known/oauth-authorization-server`](#get-well-knownoauth-authorization-server--authorization-server-metadata) | [3장](03-discovery.md), [4장](04-client-registration.md) |
| Authorization Server | [`GET /.well-known/openid-configuration`](#get-well-knownopenid-configuration--openid-provider-metadata) | [3장](03-discovery.md) |
| Authorization Server | [`GET /oauth2/authorize`](#get-oauth2authorize--authorization-request) | [5장](05-authorization-and-token.md), [4장](04-client-registration.md) |
| Authorization Server | [`POST /oauth2/authorize` — consent 제출](#post-oauth2authorize--consent-제출) | [5장](05-authorization-and-token.md) |
| Authorization Server | [Authorization Response](#authorization-response--redirect) | [5장](05-authorization-and-token.md), [8장](08-security.md) |
| Authorization Server | [`POST /oauth2/token` — `authorization_code`](#post-oauth2token--authorization_code) | [5장](05-authorization-and-token.md), [7장](07-local-client.md) |
| Authorization Server | [`POST /oauth2/token` — `refresh_token`](#post-oauth2token--refresh_token) | [5장](05-authorization-and-token.md) |
| Authorization Server | [`GET /oauth2/jwks`](#get-oauth2jwks--jwk-set) | [6장](06-mcp-call-and-validation.md) |
| 명세에만 있음 | [Client ID Metadata Document](#client-id-metadata-document--official에-없음) | [4장](04-client-registration.md) |
| 명세에만 있음 | [`POST /register` — DCR](#post-register--dynamic-client-registration) | [4장](04-client-registration.md), [9장](09-versions.md) |

## MCP 요청 header

MCP endpoint `/mcp`가 받는 모든 요청에 공통인 header다.
[MCP 2025-11-25 Transports — Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http)가 정하고, `Authorization`은 [`POST /mcp` — Bearer token](#post-mcp--bearer-token)에 있다.

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `MCP-Protocol-Version` | MUST — 초기화 뒤 모든 요청 · 값은 협상한 버전 SHOULD ([Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header)) | header가 없고 달리 알 방법도 없으면 서버는 `2025-03-26`으로 여긴다(SHOULD). 무효·미지원 값에는 `400`(MUST) | MCP Java SDK 2.0.0 client(agent, `local-client`)는 `initialize`에도 붙인다. `McpProtocolVersionFilter`는 `1999-01-01`(C15)과 `2026-07-28`에 `400`, header가 없으면(S10) 통과 |
| `MCP-Session-Id` | MUST — 서버가 초기화 때 발급했으면 이후 모든 HTTP 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)) | 보이는 ASCII(0x21~0x7E)만 쓴다(MUST). header 이름은 대소문자를 가리지 않아서, official 응답의 `Mcp-Session-Id`와 같은 header다 | 씀 — 없으면 `400`(C14), 모르는 값이면 `404`(S13) |
| `Origin` | client 쪽 요구 없음 · 서버는 모든 연결에서 검증 MUST, 있는데 무효면 `403` MUST ([Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning)) | browser가 붙이고, 서버 사이 호출에는 없다 | agent·`local-client`는 보내지 않는다. 허용한 `Origin`이 없어서 `Origin`이 붙은 요청은 token이 없어도 모두 `403`이다(C13 `http://evil.example`) |
| `Host` | MCP 규정 없음 · 이 서버로 올 요청이 아니면 `421 Misdirected Request` ([RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20)) | DNS rebinding을 막는 데 쓴다 | `localhost:8111`·`127.0.0.1:8111`만 받고, 나머지는 token이 없어도 `421`(S14) |
| `Accept` | MUST — `POST`는 `application/json`과 `text/event-stream` 둘 다, `GET`은 `text/event-stream` ([Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server)) | | 씀 — `text/event-stream`이 없으면 `400`(S15) |
| `Content-Type` | 표시 없음 — 명세는 JSON-RPC 메시지가 UTF-8이어야 한다(MUST)고만 정한다 ([Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)) | | 캡처 스크립트는 `application/json`, SDK의 `HttpClientStreamableHttpTransport`는 `application/json; charset=utf-8` |

`Origin`·`Host`는 `McpTransportSecurityFilter`가 Spring Security보다 먼저 검사한다([6장](06-mcp-call-and-validation.md)).
2026-07-28이 더한 `Mcp-Method`·`Mcp-Name` header는 official이 쓰지 않는다([9장](09-versions.md)).

## `POST /mcp` — token 없는 요청

token 없는 요청에 MCP Server는 `401`과 `WWW-Authenticate` challenge로 Protected Resource Metadata(PRM)의 주소를 알린다.
client는 이 요청을 discovery의 첫 요청으로 보낸다.

설명: [3장](03-discovery.md) · [6장](06-mcp-call-and-validation.md)

근거:

- MCP 2025-11-25 Authorization: [Protected Resource Metadata Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements), [Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling), [Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling)
- RFC·draft: [RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3), [§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1), [OAuth 2.1 §5.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.3.1), [RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1), [RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| `Authorization` | header | MUST — 모든 HTTP 요청 ([MCP Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements)) | token이 아직 없어 이 요청에는 없다. 없을 때의 서버 동작이 이 절의 내용이다 | 보내지 않는다 — agent와 `local-client`의 discovery 첫 요청 |
| `Content-Type`·`Accept`·본문 | header·본문 | [MCP 요청 header](#mcp-요청-header)·[Bearer token](#post-mcp--bearer-token)과 같다 | 서버는 본문을 읽기 전에 `401`로 답한다 | agent·`local-client` 모두 `initialize` JSON을 보낸다 |

**응답 — 상태와 header**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `401 Unauthorized` | MUST — "Authorization required or token invalid" ([MCP Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling)) | | 씀 |
| `WWW-Authenticate` | MUST — 요청에 인증 정보가 없거나 접근을 허용하지 않는 token일 때 ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | scheme `Bearer` 뒤에 auth-param을 하나 이상 둔다(MUST) | 씀 |
| 본문 | 규정 없음 | | 비어 있다(`Content-Length: 0`) |

**응답 — `WWW-Authenticate`의 auth-param** (RFC 6750 §3과 RFC 9728 §5.1이 정한 6개 전부)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `realm` | MAY, 두 번 이상은 MUST NOT ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 보호 범위의 이름 | 쓰지 않음 |
| `scope` | OPTIONAL ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) · SHOULD ([MCP PRM Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | 필요한 scope 목록(공백 구분). MCP client는 이 값을 이번 요청에 필요한 scope의 기준으로 삼는다(MUST, MCP) | 쓰지 않음 — 그래서 `local-client`는 scope를 스스로 정한다([7장](07-local-client.md)) |
| `error` | SHOULD — token이 있었고 인증에 실패했을 때 ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) · SHOULD NOT — 인증 정보가 아예 없을 때 ([§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1)) | `invalid_request`(400) · `invalid_token`(401) · `insufficient_scope`(403) | token이 있고 실패했을 때만 — `invalid_token`(C12, S3) |
| `error_description` | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 개발자가 읽는 설명 | `error`와 같은 조건 — `...The aud claim is not valid`(C12) |
| `error_uri` | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 설명 page의 절대 URI | `error`와 같은 조건 — `https://tools.ietf.org/html/rfc6750#section-3.1`(C12) |
| `resource_metadata` | 표시 없음 — parameter 정의 ([RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1)) · 서버는 이것과 well-known URI 중 하나를 구현 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | PRM 주소. `:`·`/`가 들어 있어 quoted-string으로 쓴다([RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2)) | 씀 — `http://localhost:8111/.well-known/oauth-protected-resource/mcp` |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| token 형식이 잘못됨 | `401`, `error="invalid_token"`과 `resource_metadata`(S3) | [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) |
| `aud`가 이 MCP Server가 아닌 token | `401`, `error="invalid_token"`, `error_description="...The aud claim is not valid"`(C12) | [MCP Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling) — audience 검증 MUST, 무효·만료 token은 `401` MUST |
| 권한(scope) 부족 | `403`, `error="insufficient_scope"`와 `scope`·`resource_metadata` SHOULD — official은 쓰지 않음 | [MCP Runtime Insufficient Scope Errors](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#runtime-insufficient-scope-errors) |
| `Origin`이 있거나 `Host`가 허용 목록 밖 | `401`이 아니라 `403`·`421` — official은 `Origin`·`Host`를 인증보다 먼저 본다 | [Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning) — `Origin` 검증은 모든 연결에 MUST |
| 모르는 `MCP-Protocol-Version` | `400`이 아니라 `401` — `McpProtocolVersionFilter`는 Spring Security 뒤에서 돈다 | [Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) |

**예시** (C1)

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"walkthrough","version":"1.0.0"}}}
```

```http
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8111/.well-known/oauth-protected-resource/mcp"
Content-Length: 0
```

## `GET /.well-known/oauth-protected-resource[/mcp]` — Protected Resource Metadata

MCP Server가 자기 resource 식별자와, 자기를 지키는 Authorization Server를 알린다.
client는 여기서 `authorization_servers`를 읽고 Authorization Server Metadata로 간다.

설명: [3장](03-discovery.md)

근거:

- RFC: [RFC 9728 §2](https://www.rfc-editor.org/rfc/rfc9728#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc9728#section-2.1), [§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2), [§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3)
- MCP 2025-11-25 Authorization: [Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location), [PRM Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§3.1](https://www.rfc-editor.org/rfc/rfc9728#section-3.1)) | parameter가 없다 | 씀 |
| 경로 | URL | MUST ([§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)) | resource 식별자의 host와 path 사이에 `/.well-known/oauth-protected-resource`를 넣고, host 뒤의 끝 `/`는 뺀다(MUST, §3.1). MCP client는 `resource_metadata`를 먼저 쓰고, 없으면 경로형 → 루트형 순서로 시도한다(MUST) | 경로형(C2)과 루트형(S2) 모두 답한다. agent·`local-client`는 `resource_metadata`를 따라간다 |

**응답 — 상태와 header**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `200 OK`와 `Content-Type: application/json` | MUST ([§3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2)) | 값이 없는 parameter는 빼고(MUST), 모르는 parameter는 무시한다(MUST) | 씀 |

**응답 — field** (RFC 9728 §2·§2.2가 정한 15개 전부)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `resource` | REQUIRED | resource 식별자. metadata 주소를 만든 식별자와 같아야 하고, `resource_metadata`로 받았으면 client가 요청한 주소와 같아야 한다(MUST, [§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3)) | 경로형 `http://localhost:8111/mcp`(C2) · 루트형 `http://localhost:8111`(S2) |
| `authorization_servers` | OPTIONAL (RFC 9728) · MUST, 하나 이상 ([MCP Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location)) | Authorization Server issuer 식별자의 배열 | `["http://localhost:9010"]` |
| `jwks_uri` | OPTIONAL | protected resource 자신의 JWK Set(응답 signature 등). https MUST | 없음 |
| `scopes_supported` | RECOMMENDED | 이 resource에 쓰는 scope의 배열 | 없음 — 그래서 `local-client`는 scope를 스스로 정한다([7장](07-local-client.md)) |
| `bearer_methods_supported` | OPTIONAL | `header`·`body`·`query` 중 지원하는 전달 방식 | `["header"]` |
| `resource_signing_alg_values_supported` | OPTIONAL | resource 응답 signature에 쓰는 JWS 알고리즘. `none`은 MUST NOT | 없음 |
| `resource_name` | RECOMMENDED | 사용자에게 보일 이름. `#언어태그`로 여러 언어를 둘 수 있다([§2.1](https://www.rfc-editor.org/rfc/rfc9728#section-2.1)) | 없음 |
| `resource_documentation` | OPTIONAL | 개발자 문서 주소 | 없음 |
| `resource_policy_uri` | OPTIONAL | 데이터 사용 정책 주소 | 없음 |
| `resource_tos_uri` | OPTIONAL | 이용 약관 주소 | 없음 |
| `tls_client_certificate_bound_access_tokens` | OPTIONAL, 생략 시 `false` | mTLS 인증서에 묶인 token(RFC 8705)을 지원하는지 | `false` |
| `authorization_details_types_supported` | OPTIONAL | RFC 9396 `authorization_details`의 타입 목록 | 없음 |
| `dpop_signing_alg_values_supported` | OPTIONAL | DPoP proof JWT 검증에 쓰는 알고리즘(RFC 9449) | 없음 |
| `dpop_bound_access_tokens_required` | OPTIONAL, 생략 시 `false` | DPoP token만 받는지 | 없음 |
| `signed_metadata` | OPTIONAL ([§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2)) | metadata를 claim으로 담은 signed JWT. 지원하는 수신자에게는 평문 값보다 우선한다(MUST) | 없음 |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받은 `resource`가 요청에 쓴 식별자와 다름 | client는 그 응답을 쓰지 않는다(MUST NOT) — agent·`local-client`는 여기서 멈춘다 | [RFC 9728 §3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3) |
| 그 밖의 실패 | 알맞은 HTTP 상태 코드 | [RFC 9728 §3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2) |

**예시** (C2)

```http
GET /.well-known/oauth-protected-resource/mcp HTTP/1.1
Host: localhost:8111
```

```http
HTTP/1.1 200
Content-Type: application/json

{
  "resource": "http://localhost:8111/mcp",
  "bearer_methods_supported": ["header"],
  "tls_client_certificate_bound_access_tokens": false,
  "authorization_servers": ["http://localhost:9010"]
}
```

## `POST /mcp` — Bearer token

access token을 붙여 JSON-RPC 메시지를 하나씩 보낸다.
한 MCP session은 `initialize` → `notifications/initialized` → `tools/list` → `tools/call` 순서로 간다.

설명: [1장](01-mcp-basics.md) · [6장](06-mcp-call-and-validation.md) · [7장](07-local-client.md)

근거:

- MCP 2025-11-25 Transports: [Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery)
- MCP 2025-11-25: [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle), [Authorization — Access Token Usage](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-usage), [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)
- OAuth: [RFC 6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1), [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1)

**메시지**

| 메서드 | 규칙 | 응답 | 관측 |
|---|---|---|---|
| `initialize` | 첫 상호작용이어야 한다(MUST, [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)) | `200` `application/json`, `Mcp-Session-Id` 발급 | `protocolVersion`은 `2025-11-25`(C7). `2026-07-28`을 요청해도 `2025-11-25`로 답한다([9장](09-versions.md)) |
| `notifications/initialized` | `initialize`가 성공한 뒤 보낸다(MUST) | `202`, 본문 없음 | C8 |
| `tools/list` | | `200` `text/event-stream` | C9 |
| `tools/call` | | `200` `text/event-stream` | `getStock`(C10) |

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `POST` | 요청 줄 | MUST — client가 보내는 JSON-RPC 메시지마다 새 `POST` | | 씀 |
| `Authorization: Bearer <token>` | header | MUST — 같은 session이라도 모든 HTTP 요청 (MCP) · resource server는 이 방식 지원 MUST ([RFC 6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1)) · URI query string에는 MUST NOT (MCP) | `Bearer` scheme 뒤에 access token을 둔다. scheme 이름은 대소문자를 가리지 않는다([OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1), [RFC 9110 §11.1](https://www.rfc-editor.org/rfc/rfc9110#section-11.1)) | agent는 요청마다 그 사용자의 token을 찾아 붙인다(`OAuth2TokenAttachingRequestCustomizer`). `local-client`는 transport의 기본 요청에 한 번 넣는다(`McpCalls`) |
| 공통 header | header | [MCP 요청 header](#mcp-요청-header) | `Accept`·`Content-Type`·`MCP-Protocol-Version`·`MCP-Session-Id`·`Origin`·`Host` | 씀 |
| 본문 | 본문 | MUST — JSON-RPC request·notification·response 하나 | UTF-8이어야 한다(MUST). JSON-RPC batch는 2025-06-18부터 지원하지 않는다([MCP 2025-06-18 Key Changes](https://modelcontextprotocol.io/specification/2025-06-18/changelog)) | `initialize`(C7), `notifications/initialized`(C8), `tools/list`(C9), `tools/call`(C10) |

**응답** (Transports는 field 목록 없이 문장으로 정한다)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `200`과 `Content-Type: application/json` | request면 이것과 SSE 중 하나 MUST, client는 둘 다 처리 MUST | JSON 객체 하나 | `initialize`(C7) |
| `200`과 `Content-Type: text/event-stream` | 위와 같음 | SSE stream. 관련 request·notification을 보낸 뒤 response를 보내고, response 뒤에는 stream을 닫는다(SHOULD) | `tools/list`·`tools/call`(C9, C10) |
| SSE 준비 event(event ID와 빈 `data`) | SHOULD — SSE 시작 직후 | client가 `Last-Event-ID`로 다시 붙을 수 있게 한다 | 보내지 않는다 — 첫 event가 곧 response다(C9, C10) |
| SSE `id` | MAY, 있으면 session 안 모든 stream에서 전역 유일 MUST ([Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery)) | 재개용 커서 | session ID를 그대로 쓴다(C9·C10 모두 `id:7c324e98-...`). 그래서 끊긴 stream을 이어 받을 위치를 가리킬 수 없다([8장](08-security.md)) |
| SSE `retry` | SHOULD — stream을 끝내지 않고 연결을 닫기 전 | | 쓰지 않음 |
| `MCP-Session-Id` 응답 header | MAY — `InitializeResult` 응답에서 발급 · 전역 유일하고 암호학적으로 안전 SHOULD · 보이는 ASCII만 MUST | | UUID — `7c324e98-8854-4b88-9243-7a97e94fd80a`(C7) |
| `202 Accepted`, 본문 없음 | MUST — notification·response를 받아들였을 때 | | 씀(C8, S9) |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받아들일 수 없는 notification·response | 오류 상태 코드 MUST(예: `400`), `id` 없는 JSON-RPC 오류 본문 MAY | [Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) |
| `MCP-Session-Id` 없음(초기화 제외) | `400` SHOULD — `Session ID missing`(C14). 본문은 JSON-RPC 오류 `-32601`과 Java `stackTrace`를 담는다([준수표](reference-compliance.md#준수표) 38번) | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), 오류 본문 [JSON-RPC 2.0 §5.1](https://www.jsonrpc.org/specification#error_object) |
| 무효·미지원 `MCP-Protocol-Version` | `400` MUST — JSON-RPC `-32600`, `id: null`(C15). `2026-07-28`도 같다([9장](09-versions.md)) | [Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) |
| `Accept`에 `text/event-stream` 없음 | `400` — `Invalid Accept headers`(S15) | [Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) (client MUST) |
| 무효·만료 token | `401` MUST와 `WWW-Authenticate` MUST — [token 없는 요청](#post-mcp--token-없는-요청)의 오류 표 | [MCP Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), RFC 6750 §3 |
| `Origin`이 있음(official은 허용한 `Origin`이 없다) | `403` MUST — `Invalid Origin header`(C13). token과 상관없이 인증 전에 나온다 | [Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning) |
| scope 부족 | `403`과 `insufficient_scope`·`scope`·`resource_metadata` SHOULD — official은 쓰지 않음 | [MCP Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling) |
| 끝났거나 모르는 session ID | `404` MUST, 받은 client는 새 `initialize` MUST — S13, S16. 본문은 JSON-RPC 오류 `-32603`과 Java `stackTrace`를 담는다 | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), 오류 본문 [JSON-RPC 2.0 §5.1](https://www.jsonrpc.org/specification#error_object) |
| 허용하지 않은 `Host` | `421 Misdirected Request` — MCP 규정은 없고 DNS rebinding을 막는다(S14). 인증 전에 나온다 | [RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20) |
| 다른 사용자의 token과 남의 session ID | official은 받는다 — session을 사용자에 묶지 않는다([6장](06-mcp-call-and-validation.md)) | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) — 사용자 정보에 묶기 SHOULD |

official의 검사 순서는 `Origin`·`Host` → token → `MCP-Protocol-Version` → `Accept` → session이다([6장](06-mcp-call-and-validation.md)).
`Accept`와 session은 Spring AI의 transport(`WebMvcStreamableServerTransportProvider`)가 본문을 읽기 전과 읽은 뒤에 차례로 검사한다.

**예시** (C7)

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"walkthrough","version":"1.0.0"}}}
```

```http
HTTP/1.1 200
Mcp-Session-Id: 7c324e98-8854-4b88-9243-7a97e94fd80a
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
    "capabilities": {"tools": {"listChanged": true}, "...": "그 밖의 field는 생략"},
    "serverInfo": {"name": "official-shop-mcp-server", "version": "0.0.1"}
  }
}
```

## `GET /mcp` — 서버가 보내는 메시지의 SSE stream

client가 먼저 `POST`하지 않아도 서버가 request·notification을 보낼 수 있게 SSE stream을 연다.
서버는 SSE로 답하거나 `405`로 거절한다.

설명: [1장](01-mcp-basics.md)

근거:

- MCP 2025-11-25 Transports: [Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server), [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)
- MCP 2026-07-28: [Streamable HTTP — Earlier Streamable HTTP Revisions](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#earlier-streamable-http-revisions)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MAY | | MCP Java SDK client(agent, `local-client`)가 session ID를 받은 뒤 연다 |
| `Accept: text/event-stream` | header | MUST | | 씀 |
| `Authorization` | header | MUST (모든 HTTP 요청) | | 씀 — `local-client`는 기본 요청의 header가 그대로 붙는다 |
| `MCP-Session-Id` | header | MUST — 서버가 발급했으면 이후 모든 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) 2번) | | 씀 — 없으면 `400 text/plain`(S12) |
| `MCP-Protocol-Version` | header | MUST (초기화 뒤 모든 요청) | | 씀 |
| `Last-Event-ID` | header | SHOULD — 끊긴 뒤 재개할 때 | 서버는 끊긴 그 stream의 메시지만 다시 보낼 수 있다(MAY). 다른 stream의 메시지는 다시 보내지 않는다(MUST NOT) | 쓰지 않음 |

**응답**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `Content-Type: text/event-stream` 또는 `405 Method Not Allowed` | MUST — 둘 중 하나 | stream에서 서버는 request·notification을 보낼 수 있고(MAY), 재개가 아니면 JSON-RPC response는 보내지 않는다(MUST NOT). 연결을 닫기 전에는 `retry`를 보낸다(SHOULD) | SSE 쪽 — 응답 header는 첫 event와 함께 나간다 |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `MCP-Session-Id` 없음 | `400` SHOULD — `Session ID required in mcp-session-id header`(S12) | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| SSE stream을 제공하지 않는 서버 | `405` MUST | [Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) |
| 다른 사용자의 token과 남의 session ID | official은 받는다 — session을 사용자에 묶지 않는다([6장](06-mcp-call-and-validation.md)) | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) — 사용자 정보에 묶기 SHOULD |

S11에서는 5초 동안 상태 줄·header·본문이 오지 않고 연결만 열려 있었다(curl 종료 코드 28).
Spring AI의 서버 transport는 `ServerResponse.sse(...)`로 답해서, 첫 event를 보낼 때 응답 header가 나간다.
`spring.ai.mcp.server.streamable-http.keep-alive-interval`은 기본값이 없고, official도 설정하지 않는다.
명세의 두 선택지 가운데 SSE 쪽이지만, client는 응답이 시작됐는지 알 수 없다.
2026-07-28에서는 `GET` stream과 session이 없어지고, 그 버전만 지원하는 서버는 옛 client의 `GET`·`DELETE`에 `405`로 답한다(SHOULD).

**예시** (S12)

```http
GET /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiI1ZDI5YjQ2...
Accept: text/event-stream
MCP-Protocol-Version: 2025-11-25
```

```http
HTTP/1.1 400
Content-Type: text/plain;charset=UTF-8

Session ID required in mcp-session-id header
```

## `DELETE /mcp` — session 종료

더 쓰지 않을 session을 client가 명시적으로 끝낸다.
서버는 이 요청을 `405`로 거절할 수 있다.

설명: [1장](01-mcp-basics.md)

근거: [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), [MCP Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements), [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `DELETE` | 요청 줄 | SHOULD — 더 쓰지 않을 session | | MCP Java SDK client는 닫을 때 보낸다. `local-client`는 `closeGracefully`에서 보낸다 |
| `MCP-Session-Id` | header | MUST — 서버가 발급했으면 이후 모든 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) 2번) · 이 header를 넣은 `DELETE`로 session을 끝내는 것은 SHOULD (같은 절 5번) | 끝낼 session을 가리킨다 | 씀 |
| `Authorization` | header | MUST (모든 HTTP 요청) | | `local-client`와 캡처 스크립트(C18)는 씀 — 없으면 `401`([7장](07-local-client.md)). agent가 앱 종료 때 보내는 `DELETE`에는 코드상 없다([준수표](reference-compliance.md#준수표) 36번) |
| `MCP-Protocol-Version` | header | MUST (초기화 뒤 모든 요청) | | 씀 |

**응답**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| 성공 응답 | 성공 상태 코드는 규정 없음 | | `200`, 본문 없음(C18, S16) |
| `405 Method Not Allowed` | MAY — client의 session 종료를 허용하지 않을 때 | | 쓰지 않음(`disallowDelete: false`) |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 끝낸 session ID로 다시 요청 | `404` MUST — `Session not found`(S16) | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| 다른 사용자의 token으로 남의 session을 `DELETE` | official은 받는다 — session을 사용자에 묶지 않는다([6장](06-mcp-call-and-validation.md)) | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) |

**예시** (C18)

```http
DELETE /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Mcp-Session-Id: 7c324e98-8854-4b88-9243-7a97e94fd80a
MCP-Protocol-Version: 2025-11-25
```

```http
HTTP/1.1 200
Content-Length: 0
```

## `GET /.well-known/oauth-authorization-server` — Authorization Server Metadata

Authorization Server가 자기 endpoint와 지원 기능을 알린다.
MCP client는 여기서 PKCE 지원(`code_challenge_methods_supported`)과 `iss` 지원을 확인한다.
agent와 `local-client`는 PRM의 issuer가 credentials를 발급한 issuer일 때만 이 문서를 요청한다([4장](04-client-registration.md)).

설명: [3장](03-discovery.md) · [4장](04-client-registration.md) · [8장](08-security.md)

근거:

- RFC·draft: [RFC 8414 §2](https://www.rfc-editor.org/rfc/rfc8414#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1), [§3](https://www.rfc-editor.org/rfc/rfc8414#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3), [RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3), [§3](https://www.rfc-editor.org/rfc/rfc9207#section-3), [CIMD draft-00 §5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5)
- MCP 2025-11-25 Authorization: [Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery), [Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)
- MCP 2025-11-25: [Security Best Practices — OAuth Authorization URL Validation](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#oauth-authorization-url-validation)
- MCP 2026-07-28: [Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§3.1](https://www.rfc-editor.org/rfc/rfc8414#section-3.1)) | parameter가 없다 | 씀 |
| 경로 | URL | MUST ([§3](https://www.rfc-editor.org/rfc/rfc8414#section-3)) | issuer의 host와 path 사이에 `/.well-known/oauth-authorization-server`를 넣고, path가 있으면 끝 `/`를 뺀다(MUST, §3.1). MCP client는 path 없는 issuer에 이 주소 → `openid-configuration` 순서로 시도한다(MUST) | issuer에 path가 없다. agent·`local-client`는 이 주소를 먼저 읽고, 없으면 `openid-configuration`을 읽는다 |

**응답 — 상태와 header**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `200 OK`와 `Content-Type: application/json` | MUST ([§3.2](https://www.rfc-editor.org/rfc/rfc8414#section-3.2)) | 원소가 없는 claim은 뺀다(MUST). 다른 claim을 더할 수 있다(MAY) | 씀 |

**응답 — field** (RFC 8414 §2·§2.1, RFC 9207 §3, CIMD draft-00 §5가 정한 25개 전부)

"official" 칸은 official이 알리는 값이고, 알리지 않는 field는 "없음"이다.

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `issuer` | REQUIRED | https이고 query·fragment가 없다 | `http://localhost:9010` |
| `authorization_endpoint` | authorization endpoint를 쓰는 grant가 없을 때를 빼고 REQUIRED | | `http://localhost:9010/oauth2/authorize` |
| `token_endpoint` | implicit grant만 지원할 때를 빼고 REQUIRED | | `http://localhost:9010/oauth2/token` |
| `jwks_uri` | OPTIONAL | Authorization Server signature key의 JWK Set. https MUST, signature key와 암호화 key가 함께 있으면 모든 key에 `use` REQUIRED | `http://localhost:9010/oauth2/jwks` — MCP Server가 token signature 검증에 쓴다([`GET /oauth2/jwks`](#get-oauth2jwks--jwk-set)) |
| `registration_endpoint` | OPTIONAL | DCR(RFC 7591) endpoint | 없음 — [`POST /register`](#post-register--dynamic-client-registration) |
| `scopes_supported` | RECOMMENDED | | 없음 — OpenID Connect 문서에는 있다 |
| `response_types_supported` | REQUIRED | | `["code"]` |
| `response_modes_supported` | OPTIONAL, 생략 시 `["query", "fragment"]` | | 없음 |
| `grant_types_supported` | OPTIONAL, 생략 시 `["authorization_code", "implicit"]` | 서버 전체가 지원하는 grant | 4개(`authorization_code`, `client_credentials`, `refresh_token`, token exchange). 두 client는 `authorization_code`·`refresh_token`만 등록한다 |
| `token_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | 값은 [RFC 7591 §2](https://www.rfc-editor.org/rfc/rfc7591#section-2)의 `token_endpoint_auth_method` 이름이고, `none`은 public client다 | Spring 기본 6개와 `none`(P1). agent는 `client_secret_basic`, `local-client`는 `none` |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL — `private_key_jwt`·`client_secret_jwt`를 알리면 MUST | `none`은 MUST NOT | 12개(HS·RS·ES·PS 각 256·384·512) |
| `service_documentation` | OPTIONAL | | 없음 |
| `ui_locales_supported` | OPTIONAL | | 없음 |
| `op_policy_uri` | OPTIONAL | | 없음 |
| `op_tos_uri` | OPTIONAL | | 없음 |
| `revocation_endpoint` | OPTIONAL | RFC 7009 | `http://localhost:9010/oauth2/revoke` — 알리기만 하고 client는 쓰지 않는다 |
| `revocation_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | | Spring 기본 6개 |
| `revocation_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 알리면 MUST | | 12개 |
| `introspection_endpoint` | OPTIONAL | RFC 7662 | `http://localhost:9010/oauth2/introspect` — 알리기만 하고 쓰지 않는다 |
| `introspection_endpoint_auth_methods_supported` | OPTIONAL | | Spring 기본 6개 |
| `introspection_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 알리면 MUST | | 12개 |
| `code_challenge_methods_supported` | OPTIONAL, 생략하면 PKCE 미지원 (RFC 8414) · 없으면 client는 진행 거부 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | | `["S256"]` |
| `signed_metadata` | OPTIONAL ([§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1)) | | 없음 |
| `authorization_response_iss_parameter_supported` | 표시 없음, 생략 시 `false` ([RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3)) · `iss`를 넣는 서버는 `true` MUST ([RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3), [MCP 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)) | | `true` |
| `client_id_metadata_document_supported` | OPTIONAL ([CIMD draft-00 §5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5)) — 같은 절은 CIMD를 지원하는 서버가 넣어야 한다(MUST)고 쓴다 | CIMD 지원 여부 | 없음 — [Client ID Metadata Document](#client-id-metadata-document--official에-없음) |

RFC 8414 §2 밖에서 정의되었지만 응답에 나오는 field:

| 이름 | 정의 | official |
|---|---|---|
| `tls_client_certificate_bound_access_tokens` | [RFC 8705 §3.3](https://www.rfc-editor.org/rfc/rfc8705#section-3.3) OPTIONAL, 생략 시 `false` — mTLS 인증서에 묶인 token을 발급할 수 있는지 | `true` |
| `dpop_signing_alg_values_supported` | [RFC 9449 §5.1](https://www.rfc-editor.org/rfc/rfc9449#section-5.1) — DPoP proof JWT 알고리즘 | 9개(RS·PS·ES 각 256·384·512) |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받은 `issuer`가 요청에 쓴 issuer와 다름 | client는 그 응답을 쓰지 않는다(MUST NOT) | [RFC 8414 §3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3) |
| `code_challenge_methods_supported`가 없거나 `S256`이 없음 | MCP client는 진행 거부 MUST — agent·`local-client`는 여기서 멈춘다 | [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) |
| `authorization_endpoint`·`token_endpoint`가 `https`도, loopback 주소의 `http`도 아님 | client는 거부 MUST — agent는 `McpAuthorizationDiscovery#requireHttpUrl`에서, `local-client`는 `Discovery`에서 멈춘다 | [Security Best Practices — OAuth Authorization URL Validation](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#oauth-authorization-url-validation) |

**예시** (C3, `token_endpoint_auth_methods_supported`만 `none`이 더해진 지금 설정의 P1 값)

```http
GET /.well-known/oauth-authorization-server HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Content-Type: application/json

{
  "issuer": "http://localhost:9010",
  "authorization_endpoint": "http://localhost:9010/oauth2/authorize",
  "token_endpoint": "http://localhost:9010/oauth2/token",
  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "client_secret_jwt",
                                            "private_key_jwt", "tls_client_auth", "self_signed_tls_client_auth", "none"],
  "jwks_uri": "http://localhost:9010/oauth2/jwks",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token",
                            "urn:ietf:params:oauth:grant-type:token-exchange"],
  "revocation_endpoint": "http://localhost:9010/oauth2/revoke",
  "introspection_endpoint": "http://localhost:9010/oauth2/introspect",
  "code_challenge_methods_supported": ["S256"],
  "tls_client_certificate_bound_access_tokens": true,
  "authorization_response_iss_parameter_supported": true,
  "...": "그 밖의 field는 생략"
}
```

## `GET /.well-known/openid-configuration` — OpenID Provider Metadata

OpenID Connect Discovery 문서다.
MCP client는 RFC 8414 문서가 없으면 이것을 읽는다.

설명: [3장](03-discovery.md)

근거:

- OpenID·RFC: [OpenID Connect Discovery 1.0 §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata), [§4](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig), [§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest), [§4.2](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationResponse), [§4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation), [RFC 8414 §5](https://www.rfc-editor.org/rfc/rfc8414#section-5)
- MCP 2025-11-25 Authorization: [Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery), [Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)

RFC 8414와 다른 점은 두 가지다.

- REQUIRED가 더 많다: `jwks_uri`, `authorization_endpoint`, `subject_types_supported`, `id_token_signing_alg_values_supported`.
- `code_challenge_methods_supported`를 정의하지 않는다. 그래서 MCP는 OpenID Connect discovery 문서를 제공하는 Authorization Server에게 이 field를 따로 요구한다(MUST).

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest)) | parameter가 없다 | 씀 |
| 경로 | URL | MUST ([§4](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig)) | issuer 뒤에 `/.well-known/openid-configuration`을 붙이고(RFC 8414는 앞에 넣는다), issuer path 끝의 `/`는 뺀다(MUST, [§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest)). MCP client는 path 있는 issuer에 두 방식을 모두 시도한다(MUST) | 씀(S1) |

**응답 — 상태와 header**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `200 OK`와 `Content-Type: application/json` | MUST ([§4.2](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationResponse)) | 원소가 없는 claim은 뺀다(MUST) | 씀 |

**응답 — field** (OpenID Connect Discovery 1.0 §3의 35개와 MCP·RFC 9207이 더하는 2개, 모두 37개)

"비교" 칸은 RFC 8414와의 차이다.

| 이름 | 요구 수준 | 비교 | official |
|---|---|---|---|
| `issuer` | REQUIRED | RFC 8414와 같다. ID token의 `iss`와도 같아야 한다(MUST) | `http://localhost:9010` |
| `authorization_endpoint` | REQUIRED | RFC 8414는 조건부 | `http://localhost:9010/oauth2/authorize` |
| `token_endpoint` | implicit flow만 쓸 때를 빼고 REQUIRED | 같다 | `http://localhost:9010/oauth2/token` |
| `userinfo_endpoint` | RECOMMENDED | OIDC 전용 | `http://localhost:9010/userinfo` — 알리기만 하고 쓰지 않는다 |
| `jwks_uri` | REQUIRED | RFC 8414는 OPTIONAL. JWK Set에 private key나 symmetric key 값을 담지 않는다(MUST NOT) | `http://localhost:9010/oauth2/jwks` |
| `registration_endpoint` | RECOMMENDED | RFC 8414는 OPTIONAL | 없음 |
| `scopes_supported` | RECOMMENDED, `openid` 지원 MUST | 같다(RECOMMENDED) | `["openid"]` |
| `response_types_supported` | REQUIRED | 같다 | `["code"]` |
| `response_modes_supported` | OPTIONAL | 같다 | 없음 |
| `grant_types_supported` | OPTIONAL | 같다 | Authorization Server Metadata와 같은 4개 |
| `acr_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `subject_types_supported` | REQUIRED | OIDC 전용 | `["public"]` |
| `id_token_signing_alg_values_supported` | REQUIRED, `RS256` 포함 MUST | OIDC 전용 | `["RS256"]` — ID token signature |
| `id_token_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `id_token_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `userinfo_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `userinfo_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `userinfo_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `request_object_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `request_object_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `request_object_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `token_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | 같다 | Authorization Server Metadata와 같은 7개(P2) |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL | RFC 8414는 조건부 MUST. `none`은 MUST NOT | 12개, Authorization Server Metadata와 같다 |
| `display_values_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `claim_types_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `claims_supported` | RECOMMENDED | OIDC 전용 | 없음 |
| `service_documentation` | OPTIONAL | 같다 | 없음 |
| `claims_locales_supported` | OPTIONAL | OIDC 전용 | 없음 |
| `ui_locales_supported` | OPTIONAL | 같다 | 없음 |
| `claims_parameter_supported` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 없음 |
| `request_parameter_supported` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 없음 |
| `request_uri_parameter_supported` | OPTIONAL, 생략 시 `true` | OIDC 전용 | 없음 |
| `require_request_uri_registration` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 없음 |
| `op_policy_uri` | OPTIONAL | 같다 | 없음 |
| `op_tos_uri` | OPTIONAL | 같다 | 없음 |
| `code_challenge_methods_supported` | OIDC에 정의 없음 · Authorization Server는 넣는다 MUST, client는 확인한다 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | RFC 8414 field | `["S256"]` |
| `authorization_response_iss_parameter_supported` | OIDC에 정의 없음 · 표시 없음, 생략 시 `false` ([RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3)) | RFC 8414 확장 | `true` |

S1에는 이 밖에 `end_session_endpoint`(`http://localhost:9010/connect/logout`, OpenID Connect RP-Initiated Logout 1.0의 field)가 있다.
Authorization Server Metadata에서 본 `revocation_*`·`introspection_*`·`tls_client_certificate_bound_access_tokens`·`dpop_signing_alg_values_supported`도 같은 값으로 있다.

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `issuer`가 요청한 Issuer URL과 다름 | 그 정보를 쓰지 않는다(MUST NOT) | [OIDC Discovery §4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation) |
| `code_challenge_methods_supported` 없음 | MCP client는 진행 거부 MUST | [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) |

**예시** (S1, `token_endpoint_auth_methods_supported`만 지금 설정의 P2 값)

```http
GET /.well-known/openid-configuration HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Content-Type: application/json

{
  "issuer": "http://localhost:9010",
  "authorization_endpoint": "http://localhost:9010/oauth2/authorize",
  "token_endpoint": "http://localhost:9010/oauth2/token",
  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "client_secret_jwt",
                                            "private_key_jwt", "tls_client_auth", "self_signed_tls_client_auth", "none"],
  "jwks_uri": "http://localhost:9010/oauth2/jwks",
  "userinfo_endpoint": "http://localhost:9010/userinfo",
  "end_session_endpoint": "http://localhost:9010/connect/logout",
  "code_challenge_methods_supported": ["S256"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"],
  "scopes_supported": ["openid"],
  "authorization_response_iss_parameter_supported": true,
  "...": "그 밖의 field는 생략"
}
```

## `GET /oauth2/authorize` — authorization request

client가 만든 주소로 browser가 이동한다.
주소에는 PKCE의 `code_challenge`와, 대상 MCP Server를 가리키는 `resource`가 들어 있다.

설명: [5장](05-authorization-and-token.md) · [4장](04-client-registration.md)(redirect URI와 loopback) · [7장](07-local-client.md)

근거:

- OAuth: [RFC 6749 §3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3), [§4.1.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.1), [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1), [§4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1), [§7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1)
- RFC: [RFC 7636 §4.2](https://www.rfc-editor.org/rfc/rfc7636#section-4.2), [§4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1), [RFC 8252 §7.3](https://www.rfc-editor.org/rfc/rfc8252#section-7.3), [§8.4](https://www.rfc-editor.org/rfc/rfc8252#section-8.4)
- MCP 2025-11-25 Authorization: [Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation), [Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection)
- OpenID: [OpenID Connect Core 1.0 §3.1.2.1](https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest)

**요청** (RFC 6749·OAuth 2.1 §4.1.1, RFC 7636 §4.3, RFC 8707 §2, OIDC Core §3.1.2.1이 정한 17개 전부)

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| `response_type` | query | REQUIRED (RFC 6749 · OAuth 2.1 · OIDC Core) | `code` | 씀 |
| `client_id` | query | REQUIRED | | agent `official-shop-agent`(S17) · `local-client` `local-mcp-client` |
| `redirect_uri` | query | OPTIONAL (RFC 6749) · 하나만 등록됐으면 OPTIONAL, 여럿이면 REQUIRED (OAuth 2.1) · REQUIRED (OIDC Core) | 등록값과 simple string comparison으로 같아야 한다(AS MUST, [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1)). loopback redirect는 포트만 요청 값을 받는다(AS MUST, [RFC 8252 §7.3](https://www.rfc-editor.org/rfc/rfc8252#section-7.3) · [§8.4](https://www.rfc-editor.org/rfc/rfc8252#section-8.4) · [OAuth 2.1 §8.4.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-8.4.2)) | agent `http://localhost:8110/login/oauth2/code/authserver` · `local-client` `http://127.0.0.1:<빈 포트>/callback`. 포트만 다른 `http://127.0.0.1:9999/callback`도 통과한다(P10) |
| `scope` | query | OPTIONAL (RFC 6749 · OAuth 2.1) · REQUIRED, `openid` 포함 MUST (OIDC Core) | 공백으로 나눈다 | agent·`local-client` 모두 `openid profile` |
| `state` | query | RECOMMENDED (RFC 6749) · OPTIONAL (OAuth 2.1) · RECOMMENDED (OIDC Core) · client는 쓰고 확인 SHOULD ([MCP Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection)) | CSRF를 막고 요청과 응답을 짝짓는다 | 씀 — 요청마다 무작위 값 |
| `code_challenge` | query | REQUIRED ([RFC 7636 §4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3)) · REQUIRED or RECOMMENDED (OAuth 2.1, §7.5.1 참고) · client는 PKCE 구현 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | `BASE64URL(SHA256(code_verifier))`, 43~128자 | 씀 — agent는 `withPkce()`, `local-client`는 `Pkce`로 만든다 |
| `code_challenge_method` | query | OPTIONAL, 생략 시 `plain` (RFC 7636 §4.3 · OAuth 2.1) · 가능하면 `S256` MUST ([RFC 7636 §4.2](https://www.rfc-editor.org/rfc/rfc7636#section-4.2) · OAuth 2.1 · MCP) | | `S256` |
| `resource` | query | 표시 없음 — client가 넣을 수 있다 MAY ([RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2)) · MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | 절대 URI(MUST)이고 fragment는 MUST NOT. 여러 번 넣어 resource를 여럿 지정할 수 있다(MAY) | 하나 — `http://localhost:8111/mcp` |
| `nonce` | query | OPTIONAL (OIDC Core) | ID token 재전송 방지 | agent만 — Spring Security가 `openid` 요청에 붙인다(S17). `local-client`와 캡처 스크립트는 보내지 않는다 |
| `response_mode` | query | OPTIONAL (OIDC Core) | 응답을 전달하는 방식 | 쓰지 않음 |
| `display` | query | OPTIONAL (OIDC Core) | login 화면을 보여 주는 방식 | 쓰지 않음 |
| `prompt` | query | OPTIONAL (OIDC Core) | `none`·`login`·`consent`·`select_account` | 쓰지 않음 |
| `max_age` | query | OPTIONAL (OIDC Core) | 인증 뒤 허용하는 최대 경과 시간 | 쓰지 않음 |
| `ui_locales` | query | OPTIONAL (OIDC Core) | 화면 언어 | 쓰지 않음 |
| `id_token_hint` | query | OPTIONAL (OIDC Core) | 이전에 받은 ID token | 쓰지 않음 |
| `login_hint` | query | OPTIONAL (OIDC Core) | login 식별자 힌트 | 쓰지 않음 |
| `acr_values` | query | OPTIONAL (OIDC Core) | 요청하는 인증 수준 | 쓰지 않음 |

`response_mode`부터 `acr_values`까지는 OpenID Connect가 더한 parameter로, MCP authorization 명세의 범위 밖이다.
OIDC Core는 `claims`, `request`, `request_uri` 같은 parameter도 다른 절에서 정하고, official은 다루지 않는다.

**응답**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `302` → redirect URI | 표시 없음 — 결정이 나면 HTTP redirect 등으로 client에 돌려보낸다(OAuth 2.1 §4.1.1) | [Authorization Response](#authorization-response--redirect) | confidential client는 consent 없이 곧장 이것(C5, P13) |
| `200` consent 화면 | 표시 없음 — AS는 resource owner를 인증하고 결정을 받는다(OAuth 2.1 §4.1.1). client·scope·수명 정보를 보여 주면 좋다(SHOULD, [§7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3)) | 제출은 [`POST /oauth2/authorize`](#post-oauth2authorize--consent-제출) | public client는 요청마다(P3, P8) — `PublicClientConsentService`가 consent를 저장하지 않는다 |
| `302` → `/login` | 명세 범위 밖(Spring 기본 동작) | login session이 없을 때 | 씀 — 요청 검증은 login보다 먼저 한다 |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| redirect URI가 없거나 틀림, `client_id`가 없거나 틀림 | redirect하지 않는다(MUST NOT). login한 browser는 `400` JSON(S4, P10-1), login하지 않은 browser는 `/error` page도 login을 요구해서 `302` → `/login` | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1) |
| `code_challenge` 없음 | redirect로 `error=invalid_request`(C17, P9). public client의 요청은 거부 MUST이고, 그 밖의 client도 다른 방법으로 code injection을 막는다는 확신이 없으면 거부 MUST | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1), [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1) |
| 지원하지 않는 `code_challenge_method` | redirect로 `error=invalid_request` MUST | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1) |
| 허용 목록 밖 `resource` | redirect로 `error=invalid_target`(C16) — `mcp.authorization.resources`에 없는 값 | [RFC 8707 §2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1) |
| `resource`가 여럿 | redirect로 `error=invalid_target` — official은 resource를 하나만 받는다(`AuthorizationServerStandardTest`) | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) — 여럿은 MAY, 받을 수 없으면 `invalid_target` |
| public client가 scope를 빼거나 `openid`만 요청 | redirect로 `error=invalid_scope`(P8-1) — `PublicClientScopeValidator`가 consent 판단 전에 거절한다 | [RFC 6749 §3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3) — scope 생략은 기본값 처리나 `invalid_scope` MUST · [OAuth 2.1 §7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1) |
| 그 밖의 오류 | redirect로 오류 parameter | [Authorization Response](#authorization-response--redirect) |

검증은 Spring의 `redirect_uri`·`scope` 확인 → `ResourceIndicatorValidator` → `PublicClientScopeValidator` → PKCE 순서다([5장](05-authorization-and-token.md)).
관측: S17의 agent 주소에는 `nonce`와 무작위 `state`·`code_challenge`가 있고, 캡처 스크립트는 RFC 7636 부록 B의 예시 `code_challenge`를 쓴다.

**예시 1** (P3, public client, login session이 있는 상태)

```http
GET /oauth2/authorize?response_type=code&client_id=local-mcp-client&redirect_uri=http%3A%2F%2F127.0.0.1%3A8123%2Fcallback&scope=openid%20profile&state=public-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp HTTP/1.1
Host: localhost:9010
```

응답 본문은 HTML이라, 화면에 보이는 글과 form field만 적었다.

```text
HTTP/1.1 200
Content-Type: text/html;charset=UTF-8

Consent required
local-mcp-client wants to access your account user
form  POST /oauth2/authorize
  client_id = local-mcp-client                               (hidden)
  state     = mStpfVCLBqQ-u6kX0Z7O_N9tGavdpL2_RdMBsvCXXBw=   (hidden)
  scope     = profile                                        (checkbox)
```

**예시 2** (`local-client`가 여는 주소, [7장](07-local-client.md)의 실행 결과)

```text
http://localhost:9010/oauth2/authorize?response_type=code&client_id=local-mcp-client&redirect_uri=http%3A%2F%2F127.0.0.1%3A59597%2Fcallback&scope=openid+profile&state=...&code_challenge=...&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

## `POST /oauth2/authorize` — consent 제출

consent 화면의 form이 같은 주소로 사용자의 결정을 보낸다.
명세는 이 form의 형식을 정하지 않는다.
[OAuth 2.1 §7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3)은 resource owner를 명시적으로 인증하고, client·scope·수명 정보를 보여 주면 좋다(SHOULD)고만 쓴다.
그래서 아래 field는 Spring Authorization Server 기본 form의 것이다.

설명: [5장](05-authorization-and-token.md)

근거: [OAuth 2.1 §7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3), [§7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1), [RFC 6749 §4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| `client_id` | 본문 | 표시 없음(Spring 기본 form) | form의 hidden field | `local-mcp-client` |
| `state` | 본문 | 표시 없음(Spring 기본 form) | 대기 중인 authorization을 찾으려고 서버가 consent 화면에 새로 발급한 값이다. 원래 authorization request의 `state`가 아니다 | 씀 |
| `scope` | 본문 | 표시 없음(Spring 기본 form) | 고른 scope마다 하나다. `openid`는 consent 대상이 아니라 checkbox가 없고, 서버가 다시 붙인다 | `profile` |

**응답**

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `302` → redirect URI | 표시 없음 | code, 원래 요청의 `state`, `iss`가 붙는다([Authorization Response](#authorization-response--redirect)) | 씀(P4) |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 사용자가 거부하거나, scope를 하나도 고르지 않고 제출 | redirect로 `error=access_denied` — `local-client`는 `실패: Authorization Server가 거절했다: access_denied ...`를 찍는다([7장](07-local-client.md)) | [RFC 6749 §4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1) |
| 같은 public client의 다음 요청 | 예전에 consent했어도 다시 consent 화면(P8) — client 신원을 확인할 수 없으면 처음처럼 처리 SHOULD | [OAuth 2.1 §7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1) |

**예시** (P3의 form을 제출한 P4)

```http
POST /oauth2/authorize HTTP/1.1
Host: localhost:9010
Content-Type: application/x-www-form-urlencoded

client_id=local-mcp-client&state=mStpfVCLBqQ-u6kX0Z7O_N9tGavdpL2_RdMBsvCXXBw%3D&scope=profile
```

```http
HTTP/1.1 302
Location: http://127.0.0.1:8123/callback?code=lMbNpgP99yyS...&state=public-state&iss=http%3A%2F%2Flocalhost%3A9010
```

## Authorization Response — redirect

Authorization Server가 `302`로 browser를 client의 redirect URI로 보내며, code나 오류를 query에 넣는다.
client는 code를 token endpoint로 보내기 전에 `state`와 `iss`를 확인한다.

설명: [5장](05-authorization-and-token.md) · [8장](08-security.md)(mix-up)

근거:

- OAuth: [RFC 6749 §4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2), [§4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1), [OAuth 2.1 §4.1.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2), [§4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1)
- RFC: [RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2), [§2.4](https://www.rfc-editor.org/rfc/rfc9207#section-2.4), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1)
- MCP 2026-07-28: [Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)

형식은 `302 Found`와 `Location: <redirect_uri>?<parameter>`이고, parameter는 query에 `application/x-www-form-urlencoded`로 넣는다.
받는 곳은 agent의 `GET /login/oauth2/code/authserver`와 `local-client`의 `http://127.0.0.1:<빈 포트>/callback`이고, 캡처 스크립트는 `http://127.0.0.1:8123/callback`을 쓴다(P4).

**redirect 대상**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| redirect URI | `Location` header | 등록값과 같아야 한다(AS MUST, [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1)) | 요청 parameter는 [`GET /oauth2/authorize`](#get-oauth2authorize--authorization-request)에 있다 | 씀 |

**응답 — 성공** (RFC 6749 §4.1.2·OAuth 2.1 §4.1.2가 정한 3개 전부)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `code` | REQUIRED | 수명이 짧고(최대 10분 RECOMMENDED) 한 번만 쓴다. client_id·redirect URI·code challenge에 묶인다 | `M1Q5-XA-2DoF...`(C5) |
| `state` | 요청에 있었으면 REQUIRED, 받은 값 그대로 | | `walkthrough-state`(C5) |
| `iss` | OPTIONAL (OAuth 2.1) · RFC 9207을 지원하는 서버는 MUST ([RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2)) · SHOULD ([MCP 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)) | Authorization Server의 issuer 식별자 | `http%3A%2F%2Flocalhost%3A9010`(C5) — `IssuerIdentifyingAuthorizationResponseHandler`가 넣는다 |

client는 모르는 parameter를 무시한다(MUST).
OpenID Connect 흐름에서 authorization endpoint가 ID token을 돌려주면, `iss`는 그 ID token의 `iss`와 같아야 한다(MUST, RFC 9207 §2.4).
official은 code 흐름이라 Authorization Response에 ID token이 없다.

**응답 — 오류** (OAuth 2.1 §4.1.2.1이 정한 5개 전부)

redirect URI나 `client_id`가 없거나 틀리면 redirect하지 않는다(MUST NOT).
그 밖의 오류는 redirect URI로 보낸다.

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `error` | REQUIRED | 아래 오류 코드 중 하나 | `invalid_target`(C16) · `invalid_request`(C17) · `invalid_scope`(P8-1) |
| `error_description` | OPTIONAL | ASCII 설명 | `OAuth%202.0%20Parameter%3A%20code_challenge`(C17) |
| `error_uri` | OPTIONAL | 설명 page의 주소 | `https%3A%2F%2Fwww.rfc-editor.org%2Frfc%2Frfc8707%23section-2`(C16) |
| `state` | 요청에 있었으면 REQUIRED | | `walkthrough-state`(C16, C17) |
| `iss` | OPTIONAL (OAuth 2.1) · RFC 9207을 지원하는 서버는 오류 응답에도 MUST | | `http%3A%2F%2Flocalhost%3A9010`(C16, C17) |

**오류 코드**

| `error` | 뜻 | 근거 |
|---|---|---|
| `invalid_request` | 필수 parameter 누락, 잘못된 값, 중복. PKCE가 필요한데 `code_challenge`가 없거나, 변환 방식을 지원하지 않는다(C17) | RFC 6749 §4.1.2.1 · [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1) |
| `unauthorized_client` | 이 client는 이 방식으로 code를 요청할 수 없다 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `access_denied` | resource owner나 Authorization Server가 거부했다 — consent 화면에서 scope를 고르지 않고 제출(`local-client`, [7장](07-local-client.md)) | RFC 6749 §4.1.2.1 |
| `unsupported_response_type` | 이 방식의 code 발급을 지원하지 않는다 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `invalid_scope` | scope가 잘못됐거나 모르는 값이다 — public client의 `openid` 단독 요청(P8-1) | RFC 6749 §4.1.2.1 · [§3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3) |
| `server_error` | 서버 내부 오류. redirect로는 `500`을 줄 수 없어서 따로 정의했다 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `temporarily_unavailable` | 일시적인 과부하나 점검 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `invalid_target` | 요청한 resource가 잘못됐거나 모르는 값이다(C16) | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) |

**client 쪽 확인**

client는 `iss`가 기록해 둔 issuer와 다르거나, `authorization_response_iss_parameter_supported: true`인데 `iss`가 없으면 응답을 거부한다(MUST, MCP 2026-07-28 · RFC 9207 §2.4).
오류 응답의 `iss`가 다르면 `error`·`error_description`·`error_uri`를 쓰거나 보여 주지 않는다(MUST NOT).

| client | 확인 순서 | 거부할 때 |
|---|---|---|
| agent | `AuthorizationResponseIssuerFilter`(login filter 바로 앞): `state`로 session의 요청 기록을 찾아 `iss`를 비교한다 → `OAuth2LoginAuthenticationFilter`: `state`를 확인하고 code를 token으로 바꾼다 | `iss`가 다르면 `401` `로그인 실패: iss mismatch: ...`(S18), 없으면 `401` `로그인 실패: iss is missing ...`(S19)이고, code는 token endpoint로 가지 않는다. 요청 기록을 찾지 못하면 login filter가 `authorization_request_not_found`로 거절한다 |
| `local-client` | `state` → `iss` → `error` → `code`(`AuthorizationResponse`) | `실패:`로 시작하는 한 줄을 찍고 끝난다([7장](07-local-client.md)) |

관측: S20의 agent 정상 왕복에도 `iss`가 있다.

**예시** (C5, confidential client)

```http
GET /oauth2/authorize?response_type=code&client_id=official-shop-agent&redirect_uri=http%3A%2F%2Flocalhost%3A8110%2Flogin%2Foauth2%2Fcode%2Fauthserver&scope=openid%20profile&state=walkthrough-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 302
Location: http://localhost:8110/login/oauth2/code/authserver?code=M1Q5-XA-2DoF...&state=walkthrough-state&iss=http%3A%2F%2Flocalhost%3A9010
Content-Length: 0
```

## `POST /oauth2/token` — `authorization_code`

authorization code를 access token으로 바꾼다.
confidential client는 client 인증을, public client는 `client_id`와 PKCE의 `code_verifier`를 보낸다.

설명: [5장](05-authorization-and-token.md) · [7장](07-local-client.md)

근거:

- OAuth: [RFC 6749 §4.1.3](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.3), [§5.1](https://www.rfc-editor.org/rfc/rfc6749#section-5.1), [§5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2), [OAuth 2.1 §2.4.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.4.1), [§3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)–[§3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4), [§4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3), [§10.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-10.2)
- RFC: [RFC 7636 §4.5](https://www.rfc-editor.org/rfc/rfc7636#section-4.5), [§4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [§2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)
- MCP·OpenID: [MCP 2025-11-25 Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation), [OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)

요청은 `Content-Type: application/x-www-form-urlencoded`이고 본문은 UTF-8이다(OAuth 2.1 §3.2.2).

**요청** (OAuth 2.1 §2.4.1·§3.2.2·§4.1.3, RFC 6749 §4.1.3, RFC 7636 §4.5, RFC 8707 §2.2가 정한 7개 전부)

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| `Authorization: Basic` | header | confidential client는 인증 MUST ([OAuth 2.1 §3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)) · `client_secret_basic`은 [§2.4.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.4.1) | `base64(client_id:client_secret)` | agent만 — `local-client`는 보내지 않는다(P5) |
| `grant_type` | 본문 | REQUIRED | `authorization_code` | 씀 |
| `code` | 본문 | REQUIRED | Authorization Response의 code | 씀 |
| `redirect_uri` | 본문 | authorization request에 있었으면 REQUIRED, 같은 값 MUST (RFC 6749) · OAuth 2.1은 목록에서 뺐고, 하위 호환은 [§10.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-10.2) | | 씀 — authorization request와 같은 주소 |
| `client_id` | 본문 | client 인증을 하지 않을 때 REQUIRED ([OAuth 2.1 §4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3)) | | `local-client`만 — `local-mcp-client`(P5). agent는 Basic 인증이라 보내지 않는다 |
| `code_verifier` | 본문 | REQUIRED ([RFC 7636 §4.5](https://www.rfc-editor.org/rfc/rfc7636#section-4.5)) · `code_challenge`가 있었으면 REQUIRED, 없었으면 MUST NOT (OAuth 2.1) | authorization request 전에 만든 무작위 문자열 | 씀 |
| `resource` | 본문 | 표시 없음 ([RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)) · token request에 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | token을 쓸 resource | 씀 — `http://localhost:8111/mcp` |

`scope`는 이 grant의 token request parameter로 정의되지 않았고, official의 두 client도 보내지 않는다.

**응답 — header** (RFC 6749 §5.1·OAuth 2.1 §3.2.3이 정한 3개 전부)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `Content-Type: application/json` | 표시 없음 — 본문은 `application/json`이다(RFC 6749 §5.1 · OAuth 2.1 §3.2.3) | | `application/json;charset=UTF-8` |
| `Cache-Control: no-store` | MUST (RFC 6749 §5.1 · OAuth 2.1 §3.2.3) | token이 든 모든 응답 | `no-cache, no-store, max-age=0, must-revalidate` |
| `Pragma: no-cache` | MUST (RFC 6749 §5.1) · OAuth 2.1은 요구하지 않음 | | `no-cache` |

**응답 — field (200)** (RFC 6749 §5.1의 5개와 OIDC Core §3.1.3.3의 1개, 모두 6개)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `access_token` | REQUIRED | | 씀 — `aud`는 `resource` 값(C6-1, P5-1) |
| `token_type` | REQUIRED, 대소문자 무시 · OIDC는 다른 타입을 협상하지 않았으면 `Bearer` MUST | | `Bearer` |
| `expires_in` | RECOMMENDED | 초 단위 수명 | `299` |
| `refresh_token` | OPTIONAL | 발급 여부는 Authorization Server가 위험 평가와 정책으로 정한다(SHOULD, [OAuth 2.1 §3.2.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.3)) | agent만 받는다 — public client에는 발급하지 않는다(P5, P7) |
| `scope` | 요청과 같으면 OPTIONAL (RFC 6749) · RECOMMENDED (OAuth 2.1), 다르면 REQUIRED | | `openid profile` |
| `id_token` | MUST — OIDC token 응답 ([OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)) | `aud`에 client_id MUST ([§2](https://openid.net/specs/openid-connect-core-1_0.html#IDToken)) | 씀 — `aud`는 요청한 client(C6-2 `official-shop-agent`) |

**오류**

오류 응답은 기본 `400`이고, JSON 본문에 `error`(REQUIRED)·`error_description`(OPTIONAL)·`error_uri`(OPTIONAL)를 넣는다(OAuth 2.1 §3.2.4).

| 상황 | 응답 | 근거 |
|---|---|---|
| `invalid_request` | 필수 parameter 누락, 중복, 인증 방식 여럿, `code_challenge` 없이 `code_verifier`를 보냄 등 — 관측 없음 | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 |
| `invalid_client` — 틀린 `client_secret` | `401`과 `WWW-Authenticate: Basic realm="http://localhost:9010"`(S8). `Authorization` header로 인증을 시도했으면 `WWW-Authenticate` MUST | RFC 6749 §5.2 · [OAuth 2.1 §3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4) |
| `invalid_client` — public client가 Basic으로 비밀을 보냄 | S8과 같은 `401`(P12) | RFC 6749 §5.2 |
| `invalid_client` — confidential client가 `client_id`만 보냄 | `401`, `WWW-Authenticate` 없음(P14) — `ClientAuthenticationChallengeFailureHandler`는 `Authorization` header가 있을 때만 challenge를 붙인다 | RFC 6749 §5.2 |
| `invalid_grant` | code·refresh token이 무효·만료·폐기, redirect URI 불일치, 다른 client의 code, `code_verifier` 불일치 — `{"error":"invalid_grant"}`(S7, P11) | RFC 6749 §5.2 · [RFC 7636 §4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6) |
| `unauthorized_client` | 이 client에 허용되지 않은 grant — 관측 없음 | RFC 6749 §5.2 |
| `unsupported_grant_type` | 지원하지 않는 grant — 관측 없음 | RFC 6749 §5.2 |
| `invalid_scope` | scope가 잘못됐거나 허가 범위를 넘음 — 관측 없음 | RFC 6749 §5.2 |
| `invalid_target` — 다른 resource | authorization request와 다른 `resource` — `The requested resource does not match the authorization request`(S6) | [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) — 원래 허가된 resource로 제한할 수 있다 |
| `invalid_target` — 없던 resource | authorization request에 없던 `resource`를 token request에서 처음 정함(`AuthorizationServerStandardTest`) | [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) |
| `invalid_target` — resource 여럿 | `resource`를 두 번 이상 보냄(`AuthorizationServerStandardTest`) | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) |

두 요청 모두 `resource`가 없으면 `aud`는 `client_id`로 남고, MCP Server는 그 token을 `401`로 거절한다([5장](05-authorization-and-token.md)).

**예시 1** (S5, confidential client `shop-agent`)

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9010
Authorization: Basic <base64(official-shop-agent:official-shop-agent-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=<authorization code>&redirect_uri=http%3A%2F%2Flocalhost%3A8110%2Flogin%2Foauth2%2Fcode%2Fauthserver&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json;charset=UTF-8

{
  "access_token": "eyJraWQiOiI1ZDI5YjQ2...",
  "refresh_token": "c2EWpcuQtqtf...",
  "scope": "openid profile",
  "id_token": "eyJraWQiOiI1ZDI5YjQ2...",
  "token_type": "Bearer",
  "expires_in": 299
}
```

**예시 2** (P5, public client — `local-client`도 같은 parameter를 보낸다)

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9010
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&client_id=local-mcp-client&code=lMbNpgP99yyS...&redirect_uri=http%3A%2F%2F127.0.0.1%3A8123%2Fcallback&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

```http
HTTP/1.1 200
Content-Type: application/json;charset=UTF-8

{
  "access_token": "eyJraWQiOiI5YTM3YTU4...",
  "scope": "openid profile",
  "id_token": "eyJraWQiOiI5YTM3YTU4...",
  "token_type": "Bearer",
  "expires_in": 299
}
```

## `POST /oauth2/token` — `refresh_token`

만료된 access token을 refresh token으로 다시 받는다.
`resource`를 다시 보내 새 token의 audience를 같은 MCP Server로 둔다.

설명: [5장](05-authorization-and-token.md)

근거:

- OAuth: [RFC 6749 §6](https://www.rfc-editor.org/rfc/rfc6749#section-6), [OAuth 2.1 §4.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3)–[§4.3.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.3), [§3.2.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.2), [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)
- MCP·OpenID: [MCP 2026-07-28 Refresh Tokens](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#refresh-tokens), [OIDC Core §12.2](https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse)

주소와 요청 형식은 [`authorization_code`](#post-oauth2token--authorization_code)와 같다.
official에서 refresh token을 받는 client는 agent 하나다.

**요청** (RFC 6749 §6, OAuth 2.1 §2.4.1·§3.2.2·§4.3.1, RFC 8707 §2.2가 정한 6개 전부)

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| `Authorization: Basic` | header | confidential client는 인증 MUST ([OAuth 2.1 §4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1)) · `client_secret_basic`은 §2.4.1 | refresh token은 발급받은 client에 묶인다 | 씀 |
| `grant_type` | 본문 | REQUIRED | `refresh_token` | 씀 |
| `refresh_token` | 본문 | REQUIRED | | 씀 |
| `scope` | 본문 | OPTIONAL | 원래 허가 범위를 넘으면 안 된다(MUST NOT). 생략하면 원래 범위다 | 쓰지 않음 |
| `resource` | 본문 | 표시 없음 — 모든 grant에 쓸 수 있다 ([RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)) · token request에 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | 원래 허가된 resource로 제한될 수 있다 | `http://localhost:8111/mcp`. 빠지면 official은 처음 authorization request의 값을 쓴다 |
| `client_id` | 본문 | OPTIONAL ([OAuth 2.1 §3.2.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.2)) | 인증에 필요하거나 public client를 식별해야 할 때 | 쓰지 않음 — public client는 refresh token을 받지 않는다(P7) |

**응답 — field (200)** (OAuth 2.1 §3.2.3의 5개와 OIDC Core §12.2의 1개, 모두 6개)

응답 형식은 `authorization_code`의 성공 응답과 같다([OAuth 2.1 §4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2)).

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `access_token` | REQUIRED | | 새 token — `aud`는 그대로 MCP Server, `jti`는 새 값(C11) |
| `token_type` | REQUIRED | | `Bearer` |
| `expires_in` | RECOMMENDED | | `299` |
| `refresh_token` | OPTIONAL — 새로 줄 수 있고(MAY), 주면 client는 옛것을 버린다(MUST) ([§4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2)) · public client에는 rotation이나 sender-constrained token MUST ([§4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1)) | | 처음 받은 값과 같다 — rotation 없음, confidential client라서 허용된다 |
| `scope` | 요청과 같으면 RECOMMENDED (OAuth 2.1), 다르면 REQUIRED | | `openid profile` |
| `id_token` | 표시 없음 — 없을 수 있다 ([OIDC Core §12.2](https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse)) | 있으면 `iss`·`sub`·`aud`가 처음 ID token과 같아야 한다(MUST) | 씀 — 함께 다시 발급된다 |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| refresh token이 무효·만료·폐기 | `400` `invalid_grant` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 |
| 그 밖 | [`authorization_code`](#post-oauth2token--authorization_code)의 오류 표와 같다 | RFC 6749 §5.2 |

MCP 2026-07-28은 client가 refresh token 발급을 가정하지 못하게 한다(MUST NOT).
`local-client`는 refresh token을 받지 않으므로, token이 만료되면 authorization request부터 다시 한다([7장](07-local-client.md)).

**예시** (C11)

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9010
Authorization: Basic <base64(official-shop-agent:official-shop-agent-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=UqY5N0Bi3YAG...&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

```json
{
  "access_token": "eyJraWQiOiJlZDY1ZWFl...",
  "refresh_token": "UqY5N0Bi3YAG...",
  "scope": "openid profile",
  "id_token": "eyJraWQiOiJlZDY1ZWFl...",
  "token_type": "Bearer",
  "expires_in": 299
}
```

## `GET /oauth2/jwks` — JWK Set

Authorization Server가 signature 확인용 public key를 JWK Set으로 공개한다.
MCP Server는 metadata의 `jwks_uri`로 이 문서를 받아 access token의 signature를 확인한다.

설명: [6장](06-mcp-call-and-validation.md)

근거: [RFC 8414 §2](https://www.rfc-editor.org/rfc/rfc8414#section-2) (`jwks_uri`), [OpenID Connect Discovery 1.0 §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata) (`jwks_uri`), [RFC 7517 §4](https://www.rfc-editor.org/rfc/rfc7517#section-4), [§5](https://www.rfc-editor.org/rfc/rfc7517#section-5), [RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)

**요청**

| 이름 | 위치 | 요구 수준 | 설명 | official |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | 표시 없음 — RFC 7517은 가져오는 요청을 정하지 않는다 | `jwks_uri`의 주소. https MUST (RFC 8414 §2) | MCP Server가 처음 token을 검증할 때 가져온다 — `http://localhost:9010/oauth2/jwks` |

**응답 — JWK Set** (RFC 7517 §5가 정한 1개)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `keys` | MUST ([§5](https://www.rfc-editor.org/rfc/rfc7517#section-5)) | JWK 배열. 순서는 선호를 뜻하지 않는다([§5.1](https://www.rfc-editor.org/rfc/rfc7517#section-5.1)) | key 하나 |

**응답 — JWK** (RFC 7517 §4의 9개와 RFC 7518 §6.3.1의 RSA public key 2개, 모두 11개)

| 이름 | 요구 수준 | 설명 | official |
|---|---|---|---|
| `kty` | MUST — JWK에 있어야 한다 ([§4.1](https://www.rfc-editor.org/rfc/rfc7517#section-4.1)) | key 종류(`RSA`, `EC` 등) | `RSA` |
| `use` | OPTIONAL ([§4.2](https://www.rfc-editor.org/rfc/rfc7517#section-4.2)) · `key_ops`와 함께 쓰지 않는다 SHOULD NOT (§4.3) · signature key와 암호화 key를 함께 공개하면 모든 key에 REQUIRED (RFC 8414 §2 `jwks_uri`) | `sig`·`enc` | 없음 — signature key만 있다 |
| `key_ops` | OPTIONAL ([§4.3](https://www.rfc-editor.org/rfc/rfc7517#section-4.3)) | 허용하는 연산의 배열 | 없음 |
| `alg` | OPTIONAL ([§4.4](https://www.rfc-editor.org/rfc/rfc7517#section-4.4)) | 이 key로 쓸 알고리즘 | 없음 |
| `kid` | OPTIONAL ([§4.5](https://www.rfc-editor.org/rfc/rfc7517#section-4.5)) — JWK Set 안의 key는 서로 다른 `kid` SHOULD | JWS header의 `kid`와 맞춰 key를 고른다 | UUID |
| `x5u` | OPTIONAL ([§4.6](https://www.rfc-editor.org/rfc/rfc7517#section-4.6)) | X.509 인증서 주소 | 없음 |
| `x5c` | OPTIONAL ([§4.7](https://www.rfc-editor.org/rfc/rfc7517#section-4.7)) | X.509 인증서 체인 | 없음 |
| `x5t` | OPTIONAL ([§4.8](https://www.rfc-editor.org/rfc/rfc7517#section-4.8)) | 인증서 SHA-1 지문 | 없음 |
| `x5t#S256` | OPTIONAL ([§4.9](https://www.rfc-editor.org/rfc/rfc7517#section-4.9)) | 인증서 SHA-256 지문 | 없음 |
| `n` | MUST — RSA public key ([RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)) | modulus, Base64urlUInt | 씀 |
| `e` | MUST — RSA public key ([RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)) | exponent, Base64urlUInt | `AQAB` |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 모르는 `kty`, 필수 멤버가 없는 key | 받는 쪽은 그 JWK를 무시한다 SHOULD | [RFC 7517 §5](https://www.rfc-editor.org/rfc/rfc7517#section-5) |
| JWK Set에 private key나 symmetric key 값 | 담지 않는다(MUST NOT) | [OIDC Discovery §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata) `jwks_uri` |

official의 Authorization Server는 `JWKSource` bean을 정의하지 않아서, Spring Boot 자동 구성이 기동할 때 RSA key를 만든다.
그래서 `kid`와 `n`은 기동할 때마다 다르다.
응답 `Content-Type`은 `application/json;charset=ISO-8859-1`이다.
`NimbusJwkSetEndpointFilter`(spring-security-oauth2-authorization-server 7.1.0)가 `setContentType("application/json")` 뒤에 `getWriter()`를 불러서 servlet 기본 charset이 붙는다.

**예시** (캡처 스크립트에 없는 요청, `kid`·`n`은 기동마다 다르다)

```http
GET /oauth2/jwks HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Content-Type: application/json;charset=ISO-8859-1

{
  "keys": [
    {
      "kty": "RSA",
      "e": "AQAB",
      "kid": "a7cd7941-573b-4f3c-aee4-6b04a7c3724a",
      "n": "rmHQMY49cwJObpj_4tA-..."
    }
  ]
}
```

## Client ID Metadata Document — official에 없음

client가 자기 metadata JSON을 `https` 주소에 올리고, 그 주소 자체를 `client_id`로 쓴다.
Authorization Server는 주소 형식의 `client_id`를 만나면 그 문서를 가져와 검증한다.
MCP는 서로 미리 알지 못하는 client와 서버 사이의 기본 등록 방식으로 이것을 권한다.

설명: [4장](04-client-registration.md)

근거:

- draft·RFC: [CIMD draft-00 §3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3), [§4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4), [§4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1), [§4.3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.3), [§4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4), [§5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5), [§6.5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.5), [§6.6](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.6), [RFC 7591 §2](https://www.rfc-editor.org/rfc/rfc7591#section-2)
- MCP: [2025-11-25 Client ID Metadata Documents](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#client-id-metadata-documents), [2026-07-28 Client Registration — Client ID Metadata Documents](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#client-id-metadata-documents)

official은 CIMD를 구현하지 않으므로, 아래 표에는 "official" 칸이 없다.
Authorization Server는 `client_id_metadata_document_supported`를 알리지 않고(C3, S1), 두 client는 pre-registration으로 등록되어 있다.

**요청** (Authorization Server → client가 올린 주소)

| 이름 | 위치 | 요구 수준 | 설명 |
|---|---|---|---|
| `client_id` 주소 | URL | MUST — `https` scheme, path 포함 · dot segment·fragment·username·password는 MUST NOT, query는 SHOULD NOT, port는 MAY ([§3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3)) | 짧고 자주 바뀌지 않는 주소를 권한다(RECOMMENDED) |
| 메서드 `GET` | 요청 줄 | 표시 없음 — Authorization Server는 문서를 가져온다 SHOULD ([§4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4) · MCP) | authorization request에서 주소 형식의 `client_id`를 만났을 때 |

**응답 — metadata 문서의 field** (CIMD draft-00 §4.1의 3개와 RFC 7591 §2의 15개, 모두 18개)

문서의 값은 RFC 7591 §2의 client metadata 이름을 쓴다(§4.1).
RFC 7591 §2의 field는 따로 적지 않으면 OPTIONAL이다.

| 이름 | 요구 수준 | 설명 |
|---|---|---|
| `client_id` | MUST — 문서 주소와 simple string comparison으로 같다 MUST ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | |
| `client_name` | OPTIONAL (RFC 7591) · MUST (MCP Implementation Requirements) | consent 화면에 보일 이름 |
| `redirect_uris` | OPTIONAL (RFC 7591, redirect 흐름은 등록 MUST) · MUST (MCP) | Authorization Server는 요청의 redirect URI를 이 목록과 비교한다(MUST, MCP) |
| `token_endpoint_auth_method` | OPTIONAL — 공유 비밀 방식(`client_secret_post`·`client_secret_basic`·`client_secret_jwt` 등)은 MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | `none`이면 public client다. `private_key_jwt`는 MAY (MCP) |
| `grant_types` | OPTIONAL | |
| `response_types` | OPTIONAL | |
| `client_uri` | OPTIONAL | client 정보 page |
| `logo_uri` | OPTIONAL | 로고 |
| `scope` | OPTIONAL | |
| `contacts` | OPTIONAL | |
| `tos_uri` | OPTIONAL | |
| `policy_uri` | OPTIONAL | |
| `jwks_uri` | OPTIONAL — `jwks`와 함께 쓰지 않는다 MUST NOT | `private_key_jwt`용 public key |
| `jwks` | OPTIONAL — `jwks_uri`와 함께 쓰지 않는다 MUST NOT | |
| `software_id` | OPTIONAL | |
| `software_version` | OPTIONAL | |
| `client_secret` | MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | 공유 비밀을 정할 방법이 없다 |
| `client_secret_expires_at` | MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 문서를 가져오지 못함 | authorization request를 멈춘다 SHOULD | [CIMD §4.3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.3) |
| 문서의 `client_id`가 주소와 다름, JSON 구조가 잘못됨, 필수 field 없음 | 검증 MUST (MCP Implementation Requirements). 실패하면 `error=invalid_client`나 `invalid_request`(MCP 흐름 다이어그램) | [MCP Implementation Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#implementation-requirements), [Client ID Metadata Documents Flow](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#client-id-metadata-documents-flow) |
| 요청의 redirect URI가 문서에 없음 | 검증 MUST | MCP Implementation Requirements |
| 오류 응답이나 잘못된 문서 | cache하지 않는다(MUST NOT) | [CIMD §4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4) |
| 정상 문서의 cache | cache할 수 있고(MAY), 그때는 HTTP cache header를 따른다(SHOULD, CIMD §4.4) · HTTP cache header를 따라 cache SHOULD (MCP) | [CIMD §4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4), [MCP Implementation Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#implementation-requirements) |
| 사설·loopback 주소 | 가져오지 않는다 SHOULD — SSRF | [CIMD §6.5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.5) |
| 큰 응답 | 크기 제한 SHOULD, 권장 최대 5KB | [CIMD §6.6](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.6) |

**예시** (MCP 2025-11-25·2026-07-28 명세의 예시 문서. CIMD draft-00은 §6.2에 일부 field만 보인다)

```json
{
  "client_id": "https://app.example.com/oauth/client-metadata.json",
  "client_name": "Example MCP Client",
  "client_uri": "https://app.example.com",
  "logo_uri": "https://app.example.com/logo.png",
  "redirect_uris": [
    "http://127.0.0.1:3000/callback",
    "http://localhost:3000/callback"
  ],
  "grant_types": ["authorization_code"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "none"
}
```

## `POST /register` — Dynamic Client Registration

client가 사용자 개입 없이 Authorization Server의 `registration_endpoint`에 자기 정보를 `POST`로 보내 `client_id`를 받는 방식이다.
MCP 2026-07-28은 이 방식을 deprecated로 정하고, 새 구현에는 CIMD를 쓰라고 한다.

설명: [4장](04-client-registration.md) · [9장](09-versions.md)

근거: [RFC 7591 §3](https://www.rfc-editor.org/rfc/rfc7591#section-3), [MCP 2025-11-25 Authorization — Dynamic Client Registration](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#dynamic-client-registration), [MCP 2026-07-28 Client Registration — Dynamic Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#dynamic-client-registration)

등록 endpoint는 JSON 본문의 HTTP `POST`를 받는다(MUST, RFC 7591 §3).
DCR은 선택 사항(MAY, MCP 2025-11-25)이고, 그래도 DCR을 쓰는 client는 알맞은 `application_type`을 넣는다(MUST, MCP 2026-07-28).

official은 DCR을 켜지 않는다.
Spring Authorization Server의 기본값이 꺼짐이고, metadata에 `registration_endpoint`가 없다(C3).

[← 9장](09-versions.md) · [목차](README.md) · [부록: 명세 준수표 →](reference-compliance.md)
