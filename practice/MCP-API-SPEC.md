# MCP Authorization API 명세

인증이 포함된 MCP 호출에 등장하는 HTTP endpoint 를 명세 기준으로 모았다. 각 절은 원문이 정의한 파라미터·헤더·필드를 빠짐없이 싣고, 세 practice(`mcp-security-authn-official`·`-chat-memory`·`-community`)가 무엇을 쓰는지 "이 practice" 열에 적는다.
전송·수명주기는 MCP 2025-11-25, authorization 은 2025-11-25 에 2026-07-28 추가분(`iss` 검증, 자격증명의 issuer binding)을 더한 것을 기준으로 한다.

## endpoint 목차

| 구분 | endpoint | 앵커 |
|---|---|---|
| 공통 | 표기법, MCP 요청 헤더 | [`common`](#common) |
| MCP Server | `POST /mcp` — token 없음 → `401` | [`mcp-unauthenticated`](#mcp-unauthenticated) |
| MCP Server | `GET /.well-known/oauth-protected-resource[/mcp]` | [`prm`](#prm) |
| MCP Server | `POST /mcp` — Bearer (`initialize`, `notifications/initialized`, `tools/list`, `tools/call`) | [`mcp-post`](#mcp-post) |
| MCP Server | `GET /mcp` | [`mcp-get`](#mcp-get) |
| MCP Server | `DELETE /mcp` | [`mcp-delete`](#mcp-delete) |
| Authorization Server | `GET /.well-known/oauth-authorization-server` | [`as-metadata`](#as-metadata) |
| Authorization Server | `GET /.well-known/openid-configuration` | [`oidc-discovery`](#oidc-discovery) |
| Authorization Server | `GET /oauth2/authorize` | [`authorize`](#authorize) |
| Authorization Server | `POST /oauth2/authorize` — consent 제출 | [`authorize-consent`](#authorize-consent) |
| Authorization Server | Authorization Response (redirect) | [`authorization-response`](#authorization-response) |
| Authorization Server | `POST /oauth2/token` — `authorization_code` | [`token-authorization-code`](#token-authorization-code) |
| Authorization Server | `POST /oauth2/token` — `refresh_token` | [`token-refresh`](#token-refresh) |
| Authorization Server | `GET /oauth2/jwks` | [`jwks`](#jwks) |
| 명세만, 미구현 | Client ID Metadata Document (client 가 호스팅) | [`cimd-document`](#cimd-document) |
| 명세만, 미구현 | `POST /register` — DCR, deprecated | [`dcr-register`](#dcr-register) |

---

<a id="common"></a>

## 공통 — 표기법과 MCP 요청 헤더

모든 절이 같은 표 열과 같은 요구 수준 표기를 쓴다. MCP endpoint 하나(`/mcp`)가 받는 공통 요청 헤더는 여기서 한 번만 정리하고, endpoint 절은 이 절을 가리킨다.

### 표 열의 뜻

- **이름·위치** — 파라미터·헤더·필드 이름과, 그것이 실리는 곳(쿼리·본문·헤더·요청 줄).
- **표시** — 원문이 그 항목에 붙인 요구 수준 단어와 근거 조항. 두 문서가 다르게 규정하면 둘 다 적는다.
- **이 practice** — `씀`, `씀(조건)`, `쓰지 않음`. 세 practice 가 다르면 나눠 적고, 관측값은 짧게 덧붙인다.

### 요구 수준 표기

- 원문이 `REQUIRED`·`RECOMMENDED`·`OPTIONAL` 로 표시했으면 그 단어를 그대로 쓴다. 원문이 조건을 붙였으면 조건까지 적는다.
- 원문이 표시 없이 `MUST`·`SHOULD`·`MAY` 문장으로만 규정하면 그 단어를 쓴다. 요구 수준 단어가 전혀 없으면 "표시 없음"이라고 쓴다.
- MCP 명세 페이지는 절 제목(앵커 링크)으로, RFC·OAuth 2.1 draft-13·OpenID·CIMD 문서는 절 번호로 가리킨다.

### 예시 표기

- 응답은 캡처 원문이다. `C<n>` 은 [`2026-09-12-<practice>.txt`](../docs/superpowers/captures/2026-09-12-official.txt), `S<n>` 은 [`2026-09-16-official-supplement.txt`](../docs/superpowers/captures/2026-09-16-official-supplement.txt), `P<n>` 은 [`2026-09-25-<practice>-public-client.txt`](../docs/superpowers/captures/2026-09-25-official-public-client.txt) 의 n 번 단계다.
- 요청 줄과 요청 헤더는 캡처에 없다. [`mcp-authorization-walkthrough.sh`](../docs/superpowers/captures/mcp-authorization-walkthrough.sh), [`mcp-authorization-supplement.sh`](../docs/superpowers/captures/mcp-authorization-supplement.sh), [`mcp-authorization-public-client.sh`](../docs/superpowers/captures/mcp-authorization-public-client.sh) 의 해당 단계 `curl` 명령을 따른다.
- 공통 보안 헤더(`X-Content-Type-Options`, `X-XSS-Protection`, `X-Frame-Options`, `Expires`)와 `Date` 는 싣지 않는다. JWT 는 앞 20자 + `...`, refresh token 과 authorization code 는 캡처 스크립트와 같이 앞 12자 + `...` 로 줄여 적는다.

### practice 별 주소

예시는 official 값이다. 다른 practice 는 포트·client_id·사용자 이름만 다르고, 그 값은 [허브 포트·계정 표](MCP-AUTHORIZATION.md#s2-ports)에 있다.

### 이동 링크

endpoint 절마다 근거 목록 아래에 "이동:" 줄을 둔다. 허브 절은 규칙과 이 practice 의 클래스·테스트를, 시퀀스 절은 이 endpoint 가 흐름의 어디에 오는지를 담는다.

### MCP 요청 헤더

[MCP 2025-11-25 Transports — Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http) 가 `/mcp` 요청에 요구하는 헤더다. `Authorization` 은 [`POST /mcp`](#mcp-post) 에 적는다.

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `MCP-Protocol-Version` | 헤더 | MUST — 초기화 이후 모든 요청 · 값은 협상한 버전 SHOULD ([Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header)) | 서버는 헤더가 없고 달리 알 방법도 없으면 `2025-03-26` 으로 가정 SHOULD, 무효·미지원 값이면 `400` MUST | 씀 — SDK 는 `initialize` 에도 붙인다. C15 `1999-01-01` → `400` · S10 헤더 없음 → `200` |
| `MCP-Session-Id` | 헤더 | MUST — 서버가 초기화 때 발급했으면 이후 모든 HTTP 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)) | 보이는 ASCII(0x21~0x7E)만 MUST. 헤더 이름은 대소문자를 가리지 않는다(캡처는 `Mcp-Session-Id`) | 씀 — C14 없음 → `400` · S13 모르는 값 → `404` |
| `Origin` | 헤더 | client 요구 없음 · 서버는 모든 연결에서 검증 MUST, 있는데 무효면 `403` MUST ([Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning)) | browser 가 붙인다. 서버 사이 호출에는 없다 | agent 는 보내지 않음. C13 `http://evil.example` → `403`, token 이 없어도 인증 전에 나온다 |
| `Accept` | 헤더 | MUST — POST 는 `application/json` 과 `text/event-stream` 둘 다, GET 은 `text/event-stream` ([Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server)) | | 씀 — S15 `text/event-stream` 없음 → `400` |
| `Content-Type` | 헤더 | 표시 없음 — 명세는 JSON-RPC 메시지가 UTF-8 이어야 한다(MUST)고만 규정 ([Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)) | | 씀 — 캡처 스크립트는 `application/json`, SDK client(`HttpClientStreamableHttpTransport`, 버전은 [허브 1절](MCP-AUTHORIZATION.md#s1))는 `application/json; charset=utf-8` |

---

<a id="mcp-unauthenticated"></a>

## `POST /mcp` — token 없음

token 없는 요청에 MCP Server 는 `401` 과 `WWW-Authenticate` challenge 로 Protected Resource Metadata(PRM) 위치를 알린다. agent 는 이 요청을 discovery 의 첫 탐침으로 보낸다.

근거:
- MCP 2025-11-25 Authorization: [Protected Resource Metadata Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements), [Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling), [Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling)
- RFC: [RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3), [§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1), [RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1), [RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2)

이동: 허브 [4.1 401 challenge](MCP-AUTHORIZATION.md#s4-1) · [5.7 Origin·Host](MCP-AUTHORIZATION.md#s5-7) · 시퀀스 [Discovery](MCP-SEQUENCES.md#rt-discovery)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `Authorization` | 헤더 | MUST — 모든 HTTP 요청 ([MCP Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements)) | token 이 아직 없어 이 요청에는 없다. 없을 때의 서버 동작이 이 절의 내용이다 | 보내지 않음(discovery 탐침) |
| `Content-Type`·`Accept`·본문 | 헤더·본문 | [공통](#common)·[`POST /mcp`](#mcp-post) 와 같음 | 서버는 본문을 읽기 전에 `401` 을 준다 | 씀 — official·chat-memory agent 는 `initialize` JSON, community module 은 빈 본문 |

원문 필드: 없음(MCP 는 이 요청의 필드 목록을 따로 정의하지 않는다)

**응답 — 상태와 헤더**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `401 Unauthorized` | MUST — "Authorization required or token invalid" ([MCP Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#error-handling)) | | 씀 (세 practice) |
| `WWW-Authenticate` | MUST — 요청에 인증 정보가 없거나 접근을 허용하지 않는 token 일 때 ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 스킴 `Bearer` 뒤에 auth-param 하나 이상(MUST) | 씀 |
| 본문 | 규정 없음 | | 비어 있음(`Content-Length: 0`) |

원문 필드: 없음(상태·헤더는 목록이 아니라 문장으로 규정된다)

**응답 — `WWW-Authenticate` 의 auth-param**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `realm` | MAY, 두 번 이상 나오면 안 됨 MUST NOT ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 보호 범위 이름 | 쓰지 않음 |
| `scope` | OPTIONAL ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) · SHOULD ([MCP PRM Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | 필요한 scope 목록(공백 구분). client 는 이 값을 이번 요청에 대한 권위 있는 값으로 다뤄야 한다(MUST, MCP) | 쓰지 않음 — scope 는 이 practice 범위 밖 |
| `error` | SHOULD — token 이 있었고 인증에 실패했을 때 ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) · SHOULD NOT — 인증 정보가 아예 없을 때 ([§3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1)) | `invalid_request`(400) · `invalid_token`(401) · `insufficient_scope`(403) | 씀(token 이 있고 실패했을 때만) — C12·S3 `invalid_token` |
| `error_description` | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 개발자용 설명 | 씀(`error` 와 같은 조건) — C12 `...The aud claim is not valid` |
| `error_uri` | MAY ([RFC 6750 §3](https://www.rfc-editor.org/rfc/rfc6750#section-3)) | 설명 페이지의 절대 URI | 씀(같은 조건) — C12 `https://tools.ietf.org/html/rfc6750#section-3.1` |
| `resource_metadata` | 표시 없음 — 파라미터 정의 ([RFC 9728 §5.1](https://www.rfc-editor.org/rfc/rfc9728#section-5.1)) · 서버는 이 방식과 well-known URI 중 하나를 구현 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)) | PRM URL. `:`·`/` 를 담으므로 quoted-string ([RFC 9110 §11.2](https://www.rfc-editor.org/rfc/rfc9110#section-11.2)) | 씀 |

원문 필드: RFC 6750 §3 과 RFC 9728 §5.1 에 정의된 6개 → 표 6행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| token 형식이 잘못됨 | `401`, `error="invalid_token"` + `resource_metadata` (S3) | [RFC 6750 §3.1](https://www.rfc-editor.org/rfc/rfc6750#section-3.1) |
| `aud` 가 이 MCP Server 가 아닌 token | `401`, `error="invalid_token"`, `error_description="...The aud claim is not valid"` (C12) | [MCP Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling) — audience 검증 MUST, 무효·만료 token 은 `401` MUST |
| 권한(scope) 부족 | `403`, `error="insufficient_scope"` + `scope` + `resource_metadata` SHOULD | [MCP Runtime Insufficient Scope Errors](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#runtime-insufficient-scope-errors) — 이 practice 는 쓰지 않음 |
| token 없는 요청의 `Origin` 이 무효, `Host` 가 허용 목록 밖 | `401` 이 아니라 `403`·`421` — 세 practice 는 `Origin`·`Host` 를 인증 전에 본다 | [Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning) — `Origin` 검증은 모든 연결에 MUST |

관측: C1 세 practice 모두 `401` 과 `resource_metadata` 하나만 싣는다. C12·S3 는 `error`·`error_description`·`error_uri`·`resource_metadata` 를 함께 싣는다.

**예시** (C1, official)

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
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Length: 0
```

---

<a id="prm"></a>

## `GET /.well-known/oauth-protected-resource[/mcp]` — Protected Resource Metadata

MCP Server 가 자기 resource 식별자와 자기를 지키는 Authorization Server 를 알린다. client 는 여기서 `authorization_servers` 를 읽어 Authorization Server Metadata 로 넘어간다.

근거:
- RFC: [RFC 9728 §2](https://www.rfc-editor.org/rfc/rfc9728#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc9728#section-2.1), [§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2), [§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3)
- MCP 2025-11-25 Authorization: [Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location), [PRM Discovery Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements)

이동: 허브 [4.2 PRM](MCP-AUTHORIZATION.md#s4-2) · 시퀀스 [Discovery](MCP-SEQUENCES.md#rt-discovery)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§3.1](https://www.rfc-editor.org/rfc/rfc9728#section-3.1)) | | 씀 |
| 경로 | URL | MUST ([§3](https://www.rfc-editor.org/rfc/rfc9728#section-3)) | resource 식별자의 host 와 path 사이에 `/.well-known/oauth-protected-resource` 를 넣는다. path 가 있으면 host 뒤의 끝 `/` 를 없앤다(MUST, §3.1). MCP client 는 `WWW-Authenticate` 의 URL 을 먼저 쓰고, 없으면 경로형 → 루트형 순서로 시도 MUST | 경로형(C2)·루트형(S2) 둘 다 응답 |

원문 필드: 없음(요청은 파라미터 없는 GET 이다)

**응답 — 상태와 헤더**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200 OK` + `Content-Type: application/json` | MUST ([§3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2)) | 값이 없는 파라미터는 빼야 하고(MUST), 모르는 파라미터는 무시해야 한다(MUST) | 씀 |

원문 필드: 없음(상태·헤더는 문장으로 규정된다)

**응답 — 필드**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `resource` | REQUIRED | resource 식별자. metadata URL 을 만든 식별자와 같아야 하고, `resource_metadata` 로 받았으면 client 가 요청한 URL 과 같아야 한다(MUST, [§3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3)) | 씀 — C2 `http://localhost:8111/mcp` · S2 `http://localhost:8111` |
| `authorization_servers` | OPTIONAL (RFC 9728) · MUST, 하나 이상 ([MCP Authorization Server Location](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-location)) | Authorization Server issuer 식별자 배열 | 씀 — C2 `["http://localhost:9010"]` |
| `jwks_uri` | OPTIONAL | Protected Resource 자신의 JWK Set(응답 서명 등). https MUST | 쓰지 않음 |
| `scopes_supported` | RECOMMENDED | 이 resource 에 쓰는 scope 배열 | 쓰지 않음 |
| `bearer_methods_supported` | OPTIONAL | `header`·`body`·`query` 중 지원하는 전달 방식 | 씀 — `["header"]` |
| `resource_signing_alg_values_supported` | OPTIONAL | resource 응답 서명용 JWS 알고리즘. `none` 금지(MUST NOT) | 쓰지 않음 |
| `resource_name` | RECOMMENDED | 사용자에게 보일 이름. `#언어태그` 로 다국어 가능([§2.1](https://www.rfc-editor.org/rfc/rfc9728#section-2.1)) | community 만 씀 — `"shop-mcp-server"` |
| `resource_documentation` | OPTIONAL | 개발자 문서 URL | 쓰지 않음 |
| `resource_policy_uri` | OPTIONAL | 데이터 사용 정책 URL | 쓰지 않음 |
| `resource_tos_uri` | OPTIONAL | 이용 약관 URL | 쓰지 않음 |
| `tls_client_certificate_bound_access_tokens` | OPTIONAL, 생략 시 `false` | mTLS 인증서에 묶인 token(RFC 8705) 지원 여부 | 씀 — `false` |
| `authorization_details_types_supported` | OPTIONAL | RFC 9396 `authorization_details` 타입 목록 | 쓰지 않음 |
| `dpop_signing_alg_values_supported` | OPTIONAL | DPoP proof JWT 검증용 알고리즘(RFC 9449) | 쓰지 않음 |
| `dpop_bound_access_tokens_required` | OPTIONAL, 생략 시 `false` | DPoP token 만 받는가 | 쓰지 않음 |
| `signed_metadata` | OPTIONAL ([§2.2](https://www.rfc-editor.org/rfc/rfc9728#section-2.2)) | metadata 를 claim 으로 담은 서명 JWT. 지원하는 수신자에게는 평문 값보다 우선(MUST) | 쓰지 않음 |

원문 필드: RFC 9728 §2·§2.2 에 정의된 15개 → 표 15행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받은 `resource` 가 요청에 쓴 식별자와 다름 | client 는 그 응답을 쓰면 안 된다(MUST NOT) | [RFC 9728 §3.3](https://www.rfc-editor.org/rfc/rfc9728#section-3.3) |
| 그 밖의 실패 | 해당 HTTP 상태 코드 | [RFC 9728 §3.2](https://www.rfc-editor.org/rfc/rfc9728#section-3.2) |

관측: C2 세 practice 모두 `resource`·`authorization_servers`·`bearer_methods_supported`·`tls_client_certificate_bound_access_tokens` 를 싣고, community 만 `resource_name` 을 더한다. S2 루트형 문서의 `resource` 는 `http://localhost:8111` 이다.

**예시** (C2, official)

```http
GET /.well-known/oauth-protected-resource/mcp HTTP/1.1
Host: localhost:8111
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json
Content-Length: 179

{"resource":"http://localhost:8111/mcp","bearer_methods_supported":["header"],"tls_client_certificate_bound_access_tokens":false,"authorization_servers":["http://localhost:9010"]}
```

---

<a id="mcp-post"></a>

## `POST /mcp` — Bearer

access token 을 실어 JSON-RPC 메시지를 하나씩 보낸다. 한 MCP session 은 `initialize` → `notifications/initialized` → `tools/list` → `tools/call` 순서로 진행한다.

근거:
- MCP 2025-11-25 Transports: [Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery)
- MCP 2025-11-25: [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle), [Authorization — Access Token Usage](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#access-token-usage), [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)
- OAuth: [RFC 6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1), [OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1)

이동: 허브 [4.8 MCP 호출과 session](MCP-AUTHORIZATION.md#s4-8) · [5.7 Origin·Host](MCP-AUTHORIZATION.md#s5-7) · [5.8 MCP session](MCP-AUTHORIZATION.md#s5-8) · 시퀀스 [MCP session](MCP-SEQUENCES.md#rt-mcp-session) · [요청 검증](MCP-SEQUENCES.md#rt-token-validation)

**메시지**

| 메서드 | 규칙 | 응답 | 관측 |
|---|---|---|---|
| `initialize` | 첫 상호작용이어야 한다(MUST, [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)) | `200` `application/json`, `Mcp-Session-Id` 발급 | C7 `protocolVersion` `2025-11-25` |
| `notifications/initialized` | `initialize` 성공 뒤 보내야 한다(MUST) | `202`, 본문 없음 | C8 |
| `tools/list` | | `200` `text/event-stream` | C9 |
| `tools/call` | | `200` `text/event-stream` | C10 `getStock` |

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `POST` | 요청 줄 | MUST — client 가 보내는 JSON-RPC 메시지마다 새 POST | | 씀 |
| `Authorization: Bearer <token>` | 헤더 | MUST — 같은 session 이라도 모든 HTTP 요청 (MCP) · Resource Server 는 이 방식 지원 MUST ([RFC 6750 §2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1)) · URI query string 에 싣기 금지 MUST NOT (MCP) | `Bearer` 스킴 뒤 access token. 스킴 이름 `bearer` 는 대소문자를 가리지 않는다([OAuth 2.1 §5.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-5.1.1), [RFC 9110 §11.1](https://www.rfc-editor.org/rfc/rfc9110#section-11.1)) | 씀 |
| 공통 헤더 | 헤더 | [공통](#common) | `Accept`·`Content-Type`·`MCP-Protocol-Version`·`MCP-Session-Id`·`Origin` | 씀 |
| 본문 | 본문 | MUST — JSON-RPC request·notification·response 하나 | UTF-8 MUST. JSON-RPC 배치는 2025-06-18 부터 지원하지 않는다([MCP 2025-06-18 Key Changes](https://modelcontextprotocol.io/specification/2025-06-18/changelog)) | 씀 — C7 `initialize`, C8 `notifications/initialized`, C9 `tools/list`, C10 `tools/call` |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200` + `Content-Type: application/json` | request 이면 이것과 SSE 중 하나 MUST, client 는 둘 다 처리 MUST | JSON 객체 하나 | 씀 (`initialize`, C7) |
| `200` + `Content-Type: text/event-stream` | 위와 같음 | SSE stream. 원래 request 와 관련된 request·notification 뒤 response, response 뒤 stream 종료 SHOULD | 씀 (`tools/list`·`tools/call`, C9·C10) |
| SSE 준비 이벤트(이벤트 ID + 빈 `data`) | SHOULD — SSE 시작 직후 | client 가 `Last-Event-ID` 로 다시 붙을 수 있게 한다 | 보내지 않음 — C9·C10 첫 이벤트가 곧 response |
| SSE `id` | MAY, 있으면 session 안 모든 stream 에서 전역 유일 MUST ([Resumability](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery)) | 재개용 커서 | session ID 를 그대로 씀 — C9·C10 모두 `id:7c324e98-...`, 유일성 MUST 와 다름 |
| SSE `retry` | SHOULD — stream 을 끝내지 않고 연결을 닫기 전 | | 쓰지 않음 |
| `MCP-Session-Id` 응답 헤더 | MAY — `InitializeResult` 응답에서 발급 · 전역 유일·암호학적으로 안전 SHOULD · 보이는 ASCII 만 MUST | | 씀 (UUID) — C7 `7c324e98-8854-4b88-9243-7a97e94fd80a` |
| `202 Accepted`, 본문 없음 | MUST — notification·response 를 받아들였을 때 | | 씀 — C8, S9 |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받아들일 수 없는 notification·response | 오류 상태 코드 MUST(예: `400`), `id` 없는 JSON-RPC 오류 본문 MAY | [Sending Messages](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) |
| `MCP-Session-Id` 없음(초기화 제외) | `400` SHOULD — C14 | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| 무효·미지원 `MCP-Protocol-Version` | `400` MUST — C15 | [Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) |
| `Accept` 에 `text/event-stream` 없음 | `400` — S15 | [Sending Messages](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) (client MUST) |
| 무효·만료 token | `401` MUST + `WWW-Authenticate` MUST — [token 없음](#mcp-unauthenticated) 의 오류 표 | [MCP Token Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling), RFC 6750 §3 |
| 있는 `Origin` 이 무효 | `403` MUST — C13 `Invalid Origin header`. token 과 무관하게 인증 전에 나온다 | [Security Warning](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning) |
| scope 부족 | `403` + `insufficient_scope`·`scope`·`resource_metadata` SHOULD — 이 practice 는 쓰지 않음 | [MCP Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling) |
| 끝난 session 의 ID | `404` MUST, 받은 client 는 새 `initialize` MUST — S13, S16 | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| 허용하지 않은 `Host` | `421 Misdirected Request` — MCP 규정 없음, DNS rebinding 방어 — S14. token 과 무관하게 인증 전에 나온다 | [RFC 9110 §15.5.20](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.20) |
| 다른 사용자가 연 `Mcp-Session-Id` (chat-memory) | `403` — `McpSessionBindingFilter` 가 session 을 연 사용자(token 의 `sub`)와 비교한다. 테스트 `McpAuthorizationStandardTest#다른_사용자가_남의_MCP_session_ID_를_쓰면_403이다` | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) — 사용자에 묶기 SHOULD |

검사 순서는 `Origin`·`Host` → token → session binding(chat-memory) → `MCP-Protocol-Version` → transport(`Accept`·session)다([시퀀스](MCP-SEQUENCES.md#rt-token-validation)).
`Origin`·`Host` 를 인증 앞에서 보는 테스트는 `McpAuthorizationStandardTest#token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다` 다.

관측: C7 이 `Mcp-Session-Id` 를 발급하고, C8 이후 요청은 그 값과 `MCP-Protocol-Version: 2025-11-25` 를 싣는다. C9·C10 은 SSE 로 응답한다.

**예시** (C7, official)

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
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json
Content-Length: 291

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"completions":{},"logging":{},"prompts":{"listChanged":true},"resources":{"subscribe":false,"listChanged":true},"tools":{"listChanged":true}},"serverInfo":{"name":"official-shop-mcp-server","version":"0.0.1"}}}
```

---

<a id="mcp-get"></a>

## `GET /mcp` — 서버발 메시지용 SSE stream

client 가 먼저 POST 하지 않아도 서버가 request·notification 을 보낼 수 있게 SSE stream 을 연다. 서버는 SSE 로 응답하거나 `405` 로 거절해야 한다.

근거:
- MCP 2025-11-25 Transports: [Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server), [Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery), [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management)
- MCP 2026-07-28: [Streamable HTTP — Earlier Streamable HTTP Revisions](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#earlier-streamable-http-revisions)

이동: 허브 [4.8 MCP 호출과 session](MCP-AUTHORIZATION.md#s4-8) · 시퀀스 [MCP session](MCP-SEQUENCES.md#rt-mcp-session)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MAY | | SDK client 가 session ID 를 받은 뒤 연다 — S11 |
| `Accept: text/event-stream` | 헤더 | MUST | | 씀 |
| `Authorization` | 헤더 | MUST (모든 HTTP 요청) | | 씀 |
| `MCP-Session-Id` | 헤더 | MUST — 서버가 발급했으면 이후 모든 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) 2번) | | 씀 — S12 없음 → `400 text/plain` |
| `MCP-Protocol-Version` | 헤더 | MUST (초기화 이후 모든 요청) | | 씀 |
| `Last-Event-ID` | 헤더 | SHOULD — 끊긴 뒤 재개할 때 | 서버는 끊긴 그 stream 의 메시지만 재전송할 수 있고(MAY), 다른 stream 것은 재전송하면 안 된다(MUST NOT) | 쓰지 않음 |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `Content-Type: text/event-stream` 또는 `405 Method Not Allowed` | MUST — 둘 중 하나 | stream 에서 서버는 request·notification 을 보낼 수 있다(MAY). 재개가 아니면 JSON-RPC response 를 보내면 안 된다(MUST NOT). 연결을 닫기 전 `retry` SHOULD | SSE 쪽 — 응답 헤더는 첫 이벤트와 함께 나간다 |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `MCP-Session-Id` 없음 | `400` SHOULD — S12 `Session ID required in mcp-session-id header` | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| SSE stream 을 제공하지 않는 서버 | `405` MUST | [Listening for Messages](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) |
| 다른 사용자가 연 `Mcp-Session-Id` (chat-memory) | `403` — [`POST /mcp`](#mcp-post) 와 같은 `McpSessionBindingFilter` | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) |

관측: S11 은 5초 동안 상태 줄·헤더·본문이 오지 않고 연결이 열린 채 유지됐다(curl 종료 코드 28). Spring AI 서버 전송은 `ServerResponse.sse(...)` 로 응답해 첫 이벤트를 보낼 때 헤더가 나가고, `spring.ai.mcp.server.streamable-http.keep-alive-interval` 은 기본값이 없어 이 practice 도 설정하지 않았다. 명세의 두 선택지 중 SSE 쪽이지만, client 는 응답이 시작됐는지 알 수 없다.

2026-07-28 에서는 GET stream 과 session 이 없어진다. 그 리비전만 지원하는 서버는 옛 client 의 GET·DELETE 에 `405` 로 답하는 것이 좋다(SHOULD).

**예시** (S12, official)

```http
GET /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiI1ZDI5YjQ2...
Accept: text/event-stream
MCP-Protocol-Version: 2025-11-25
```

```http
HTTP/1.1 400
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: text/plain;charset=UTF-8
Content-Length: 44

Session ID required in mcp-session-id header
```

---

<a id="mcp-delete"></a>

## `DELETE /mcp` — session 종료

더 쓰지 않을 session 을 client 가 명시적으로 끝낸다. 서버는 이 요청을 거절할 수 있다(`405`).

근거: [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management), [MCP Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-requirements), [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking)

이동: 허브 [4.8 MCP 호출과 session](MCP-AUTHORIZATION.md#s4-8) · [5.8 MCP session](MCP-AUTHORIZATION.md#s5-8) · 시퀀스 [MCP session](MCP-SEQUENCES.md#rt-mcp-session)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `DELETE` | 요청 줄 | SHOULD — 더 쓰지 않을 session | | SDK client 가 session 을 닫을 때 보낸다 — C18, S16 |
| `MCP-Session-Id` | 헤더 | MUST — 서버가 발급했으면 이후 모든 요청 ([Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) 2번) · 이 헤더를 실은 DELETE 로 session 을 끝내는 것은 SHOULD (같은 절 5번) | 끝낼 session 을 가리킨다 | 씀 |
| `Authorization` | 헤더 | MUST (모든 HTTP 요청) | | 씀 |
| `MCP-Protocol-Version` | 헤더 | MUST (초기화 이후 모든 요청) | | 씀 |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| 성공 응답 | 성공 상태 코드 규정 없음 | | `200`, 본문 없음 — C18, S16 |
| `405 Method Not Allowed` | MAY — client 의 session 종료를 허용하지 않을 때 | | 쓰지 않음 (`disallowDelete: false`) |

원문 필드: 없음(Transports 는 필드 목록 없이 문장으로 규정한다)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 끝낸 session ID 로 다시 요청 | `404` MUST — S16 `Session not found` | [Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) |
| 다른 사용자가 연 session 의 `DELETE` (chat-memory) | `403`, session 은 끝나지 않는다 — 테스트 `다른_사용자는_남의_MCP_session_을_끝낼_수_없다` | [Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) |

chat-memory 에서 session 을 연 사용자의 `DELETE` 가 성공하면 `McpSessionBindingFilter` 가 그 session 의 묶음을 지운다.

관측: S16 은 `DELETE` 에 `200` 을 받고, 같은 session 의 `tools/list` 에 `404` 를 받는다.

**예시** (C18, official)

```http
DELETE /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Mcp-Session-Id: 7c324e98-8854-4b88-9243-7a97e94fd80a
MCP-Protocol-Version: 2025-11-25
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Length: 0
```

---

<a id="as-metadata"></a>

## `GET /.well-known/oauth-authorization-server` — Authorization Server Metadata

Authorization Server 의 endpoint 와 지원 기능을 알린다. MCP client 는 여기서 PKCE 지원(`code_challenge_methods_supported`)과 `iss` 지원을 확인한다.
세 practice 의 Agent 는 PRM 의 issuer 가 자격증명의 issuer 일 때만 이 문서를 요청한다([Issuer binding](MCP-SEQUENCES.md#issuer-binding)).

근거:
- RFC·draft: [RFC 8414 §2](https://www.rfc-editor.org/rfc/rfc8414#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1), [§3](https://www.rfc-editor.org/rfc/rfc8414#section-3)–[§3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3), [RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3), [§3](https://www.rfc-editor.org/rfc/rfc9207#section-3), [CIMD draft-00 §5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5)
- MCP 2025-11-25 Authorization: [Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery), [Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)
- MCP 2025-11-25: [Security Best Practices — OAuth Authorization URL Validation](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#oauth-authorization-url-validation)
- MCP 2026-07-28: [Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)

이동: 허브 [4.3 Authorization Server Metadata](MCP-AUTHORIZATION.md#s4-3) · [5.4 Discovery SSRF](MCP-AUTHORIZATION.md#s5-4) · 시퀀스 [Discovery](MCP-SEQUENCES.md#rt-discovery) · [Issuer binding](MCP-SEQUENCES.md#issuer-binding)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§3.1](https://www.rfc-editor.org/rfc/rfc8414#section-3.1)) | | 씀 |
| 경로 | URL | MUST ([§3](https://www.rfc-editor.org/rfc/rfc8414#section-3)) | issuer 의 host 와 path 사이에 `/.well-known/oauth-authorization-server` 를 끼운다. path 가 있으면 끝 `/` 를 없앤다(MUST, §3.1). MCP client 는 path 없는 issuer 에 이 URL → `openid-configuration` 순서로 시도 MUST | issuer 에 path 가 없어 끼울 path 가 없다 |

원문 필드: 없음(요청은 파라미터 없는 GET 이다)

**응답 — 상태와 헤더**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200 OK` + `Content-Type: application/json` | MUST ([§3.2](https://www.rfc-editor.org/rfc/rfc8414#section-3.2)) | 원소가 없는 claim 은 빼야 한다(MUST). 다른 claim 을 더할 수 있다(MAY) | 씀 |

원문 필드: 없음(상태·헤더는 문장으로 규정된다)

**응답 — 필드**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `issuer` | REQUIRED | https, query·fragment 없음 | 씀 — `http://localhost:9010` |
| `authorization_endpoint` | authorization endpoint 를 쓰는 grant 가 없을 때를 빼고 REQUIRED | | 씀 — `http://localhost:9010/oauth2/authorize` |
| `token_endpoint` | implicit grant 만 지원할 때를 빼고 REQUIRED | | 씀 — `http://localhost:9010/oauth2/token` |
| `jwks_uri` | OPTIONAL | Authorization Server 서명 key 의 JWK Set. https MUST. 서명·암호화 key 가 함께 있으면 모든 key 에 `use` REQUIRED | 씀 — MCP Server 가 token 서명 검증에 쓴다. [`GET /oauth2/jwks`](#jwks) |
| `registration_endpoint` | OPTIONAL | DCR(RFC 7591) endpoint | 쓰지 않음 — [`POST /register`](#dcr-register) |
| `scopes_supported` | RECOMMENDED | | 쓰지 않음 — OIDC 문서에는 있다 |
| `response_types_supported` | REQUIRED | | 씀 — `["code"]` |
| `response_modes_supported` | OPTIONAL, 생략 시 `["query", "fragment"]` | | 쓰지 않음 |
| `grant_types_supported` | OPTIONAL, 생략 시 `["authorization_code", "implicit"]` | 서버 전체가 지원하는 grant | 광고됨 — 4개. client 는 `authorization_code`·`refresh_token` 만 등록 |
| `token_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | 값은 [RFC 7591 §2](https://www.rfc-editor.org/rfc/rfc7591#section-2) 의 `token_endpoint_auth_method` 이름. `none` 은 public client | 씀 — `client_secret_basic`(confidential client), `none`(public client). 광고는 Spring 고정 6개 + `none`(P1) |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL — `private_key_jwt`·`client_secret_jwt` 를 광고하면 MUST 포함 | `none` 금지(MUST NOT) | 광고됨 — 두 방식을 광고하므로 조건부 MUST 충족. 12개 |
| `service_documentation` | OPTIONAL | | 쓰지 않음 |
| `ui_locales_supported` | OPTIONAL | | 쓰지 않음 |
| `op_policy_uri` | OPTIONAL | | 쓰지 않음 |
| `op_tos_uri` | OPTIONAL | | 쓰지 않음 |
| `revocation_endpoint` | OPTIONAL | RFC 7009 | 광고됨, 쓰지 않음 — `http://localhost:9010/oauth2/revoke` |
| `revocation_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | | 광고됨 — 6개 |
| `revocation_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 광고하면 MUST 포함 | | 광고됨 — 조건부 MUST 충족. 12개 |
| `introspection_endpoint` | OPTIONAL | RFC 7662 | 광고됨, 쓰지 않음 — `http://localhost:9010/oauth2/introspect` |
| `introspection_endpoint_auth_methods_supported` | OPTIONAL | | 광고됨 — 6개 |
| `introspection_endpoint_auth_signing_alg_values_supported` | OPTIONAL — JWT 인증 방식을 광고하면 MUST 포함 | | 광고됨 — 조건부 MUST 충족. 12개 |
| `code_challenge_methods_supported` | OPTIONAL, 생략하면 PKCE 미지원 (RFC 8414) · 없으면 client 는 진행 거부 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | | 씀 — `["S256"]` |
| `signed_metadata` | OPTIONAL ([§2.1](https://www.rfc-editor.org/rfc/rfc8414#section-2.1)) | | 쓰지 않음 |
| `authorization_response_iss_parameter_supported` | 표시 없음, 생략 시 `false` ([RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3)) · `iss` 를 주는 서버는 `true` MUST ([RFC 9207 §2.3](https://www.rfc-editor.org/rfc/rfc9207#section-2.3), [MCP 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)) | | 씀 — `true` |
| `client_id_metadata_document_supported` | OPTIONAL ([CIMD draft-00 §5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5)) — 같은 절은 CIMD 를 지원하는 서버가 이 property 를 넣어야 한다(MUST)고 쓴다 | CIMD 지원 여부 | 쓰지 않음 — [Client ID Metadata Document](#cimd-document) |

원문 필드: RFC 8414 §2·§2.1, RFC 9207 §3, CIMD draft-00 §5 에 정의된 25개 → 표 25행

RFC 8414 §2 밖에서 정의되어 응답에 나오는 필드:

| 이름 | 정의 | 관측 |
|---|---|---|
| `tls_client_certificate_bound_access_tokens` | [RFC 8705 §3.3](https://www.rfc-editor.org/rfc/rfc8705#section-3.3) OPTIONAL, 생략 시 `false` — mTLS 인증서에 묶인 token 발급 지원 | `true` |
| `dpop_signing_alg_values_supported` | [RFC 9449 §5.1](https://www.rfc-editor.org/rfc/rfc9449#section-5.1) — DPoP proof JWT 알고리즘 | 9개 |

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 받은 `issuer` 가 요청에 쓴 issuer 와 다름 | client 는 그 응답을 쓰면 안 된다(MUST NOT) | [RFC 8414 §3.3](https://www.rfc-editor.org/rfc/rfc8414#section-3.3) |
| `code_challenge_methods_supported` 없음 | MCP client 는 진행 거부 MUST | [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) |
| `authorization_endpoint`·`token_endpoint` 가 `https` 도, loopback 주소의 `http` 도 아님 | client 는 거부 MUST — Agent 는 `McpAuthorizationDiscovery#requireHttpUrl` 에서 멈춘다 | [Security Best Practices — OAuth Authorization URL Validation](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#oauth-authorization-url-validation) |

관측: C3 세 practice 의 필드 구성이 같다. P1 에서 `token_endpoint_auth_methods_supported` 는 `none` 을 더한 7개다.

**예시** (C3, official — 이 캡처에는 `none` 이 없다. 현재 설정은 P1 의 7개)

```http
GET /.well-known/oauth-authorization-server HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json
Content-Length: 1742

{"issuer":"http://localhost:9010","authorization_endpoint":"http://localhost:9010/oauth2/authorize","token_endpoint":"http://localhost:9010/oauth2/token","token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"jwks_uri":"http://localhost:9010/oauth2/jwks","response_types_supported":["code"],"grant_types_supported":["authorization_code","client_credentials","refresh_token","urn:ietf:params:oauth:grant-type:token-exchange"],"revocation_endpoint":"http://localhost:9010/oauth2/revoke","revocation_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"introspection_endpoint":"http://localhost:9010/oauth2/introspect","introspection_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"code_challenge_methods_supported":["S256"],"tls_client_certificate_bound_access_tokens":true,"dpop_signing_alg_values_supported":["RS256","RS384","RS512","PS256","PS384","PS512","ES256","ES384","ES512"],"authorization_response_iss_parameter_supported":true,"token_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"],"revocation_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"],"introspection_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"]}
```

---

<a id="oidc-discovery"></a>

## `GET /.well-known/openid-configuration` — OpenID Provider Metadata

OpenID Connect Discovery 문서다. MCP client 는 RFC 8414 문서가 없을 때 이것을 시도한다.

근거:
- OpenID·RFC: [OpenID Connect Discovery 1.0 §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata), [§4](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig), [§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest), [§4.2](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationResponse), [§4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation), [RFC 8414 §5](https://www.rfc-editor.org/rfc/rfc8414#section-5)
- MCP 2025-11-25 Authorization: [Authorization Server Metadata Discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-server-metadata-discovery), [Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)

이동: 허브 [4.3 Authorization Server Metadata](MCP-AUTHORIZATION.md#s4-3) · 시퀀스 [Discovery](MCP-SEQUENCES.md#rt-discovery)

RFC 8414 와의 차이는 두 가지다.

- REQUIRED 가 더 많다: `jwks_uri`, `authorization_endpoint`, `subject_types_supported`, `id_token_signing_alg_values_supported`.
- `code_challenge_methods_supported` 를 정의하지 않는다. 그래서 MCP 가 "OIDC Discovery 를 제공하는 Authorization Server 는 이 필드를 넣어야 한다(MUST)"를 따로 요구한다.

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | MUST ([§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest)) | | 씀 |
| 경로 | URL | MUST ([§4](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig)) | issuer 뒤에 `/.well-known/openid-configuration` 을 붙인다(RFC 8414 는 앞에 끼운다). issuer path 끝의 `/` 는 없앤다(MUST, [§4.1](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest)). MCP client 는 path 있는 issuer 에 두 방식을 모두 시도 MUST | 씀 — S1 |

원문 필드: 없음(요청은 파라미터 없는 GET 이다)

**응답 — 상태와 헤더**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `200 OK` + `Content-Type: application/json` | MUST ([§4.2](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationResponse)) | 원소가 없는 claim 은 빼야 한다(MUST) | 씀 |

원문 필드: 없음(상태·헤더는 문장으로 규정된다)

**응답 — 필드**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `issuer` | REQUIRED | RFC 8414 와 같음. ID token 의 `iss` 와도 같아야 한다(MUST) | 씀 — `http://localhost:9010` |
| `authorization_endpoint` | REQUIRED | RFC 8414 는 조건부 | 씀 |
| `token_endpoint` | implicit flow 만 쓸 때를 빼고 REQUIRED | 같음 | 씀 |
| `userinfo_endpoint` | RECOMMENDED | OIDC 전용 | 광고됨, 쓰지 않음 — `http://localhost:9010/userinfo` |
| `jwks_uri` | REQUIRED | RFC 8414 는 OPTIONAL. JWK Set 에 private·symmetric key 값을 담으면 안 된다(MUST NOT) | 씀 |
| `registration_endpoint` | RECOMMENDED | RFC 8414 는 OPTIONAL | 쓰지 않음 |
| `scopes_supported` | RECOMMENDED, `openid` 지원 MUST | 같음(RECOMMENDED) | 광고됨 — `["openid"]` |
| `response_types_supported` | REQUIRED | 같음 | 씀 — `["code"]` |
| `response_modes_supported` | OPTIONAL | 같음 | 쓰지 않음 |
| `grant_types_supported` | OPTIONAL | 같음 | 광고됨 — AS metadata 와 같음 |
| `acr_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `subject_types_supported` | REQUIRED | OIDC 전용 | 광고됨 — `["public"]` |
| `id_token_signing_alg_values_supported` | REQUIRED, `RS256` 포함 MUST | OIDC 전용 | 씀 (ID token 서명) — `["RS256"]` |
| `id_token_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `id_token_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `userinfo_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `userinfo_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `userinfo_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `request_object_signing_alg_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `request_object_encryption_alg_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `request_object_encryption_enc_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `token_endpoint_auth_methods_supported` | OPTIONAL, 생략 시 `client_secret_basic` | 같음 | 씀 — AS metadata 와 같은 7개(P2) |
| `token_endpoint_auth_signing_alg_values_supported` | OPTIONAL | RFC 8414 는 조건부 MUST. `none` 금지(MUST NOT) | 광고됨 — 12개, AS metadata 와 같음 |
| `display_values_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `claim_types_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `claims_supported` | RECOMMENDED | OIDC 전용 | 쓰지 않음 |
| `service_documentation` | OPTIONAL | 같음 | 쓰지 않음 |
| `claims_locales_supported` | OPTIONAL | OIDC 전용 | 쓰지 않음 |
| `ui_locales_supported` | OPTIONAL | 같음 | 쓰지 않음 |
| `claims_parameter_supported` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 쓰지 않음 |
| `request_parameter_supported` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 쓰지 않음 |
| `request_uri_parameter_supported` | OPTIONAL, 생략 시 `true` | OIDC 전용 | 쓰지 않음 |
| `require_request_uri_registration` | OPTIONAL, 생략 시 `false` | OIDC 전용 | 쓰지 않음 |
| `op_policy_uri` | OPTIONAL | 같음 | 쓰지 않음 |
| `op_tos_uri` | OPTIONAL | 같음 | 쓰지 않음 |
| `code_challenge_methods_supported` | OIDC 에 정의 없음 · Authorization Server 는 넣어야 한다 MUST, client 는 확인 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | RFC 8414 필드 | 씀 — `["S256"]` |
| `authorization_response_iss_parameter_supported` | OIDC 에 정의 없음 · 표시 없음, 생략 시 `false` ([RFC 9207 §3](https://www.rfc-editor.org/rfc/rfc9207#section-3)) | RFC 8414 확장 | 씀 — `true` |

원문 필드: OIDC Discovery 1.0 §3 의 35개와 MCP·RFC 9207 이 더하는 2개, 모두 37개 → 표 37행

S1 에는 이 밖에 `end_session_endpoint`(OpenID Connect RP-Initiated Logout 1.0 의 필드)와 AS metadata 에서 본 `revocation_*`·`introspection_*`·`tls_client_certificate_bound_access_tokens`·`dpop_signing_alg_values_supported` 가 있다.

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `issuer` 가 요청한 Issuer URL 과 다름 | 그 정보를 쓰면 안 된다(MUST NOT) | [OIDC Discovery §4.3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationValidation) |
| `code_challenge_methods_supported` 없음 | MCP client 는 진행 거부 MUST | [MCP Authorization Code Protection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection) |

관측: S1 은 `code_challenge_methods_supported`·`authorization_response_iss_parameter_supported` 를 싣는다. P2 에서 `token_endpoint_auth_methods_supported` 는 `none` 을 더한 7개다.

**예시** (S1, official — 이 캡처에는 `none` 이 없다. 현재 설정은 P2 의 7개)

```http
GET /.well-known/openid-configuration HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json
Content-Length: 1974

{"issuer":"http://localhost:9010","authorization_endpoint":"http://localhost:9010/oauth2/authorize","token_endpoint":"http://localhost:9010/oauth2/token","token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"jwks_uri":"http://localhost:9010/oauth2/jwks","userinfo_endpoint":"http://localhost:9010/userinfo","end_session_endpoint":"http://localhost:9010/connect/logout","response_types_supported":["code"],"grant_types_supported":["authorization_code","client_credentials","refresh_token","urn:ietf:params:oauth:grant-type:token-exchange"],"revocation_endpoint":"http://localhost:9010/oauth2/revoke","revocation_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"introspection_endpoint":"http://localhost:9010/oauth2/introspect","introspection_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post","client_secret_jwt","private_key_jwt","tls_client_auth","self_signed_tls_client_auth"],"code_challenge_methods_supported":["S256"],"tls_client_certificate_bound_access_tokens":true,"dpop_signing_alg_values_supported":["RS256","RS384","RS512","PS256","PS384","PS512","ES256","ES384","ES512"],"subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],"scopes_supported":["openid"],"authorization_response_iss_parameter_supported":true,"token_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"],"revocation_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"],"introspection_endpoint_auth_signing_alg_values_supported":["HS256","HS384","HS512","RS256","RS384","RS512","ES256","ES384","ES512","PS256","PS384","PS512"]}
```

---

<a id="authorize"></a>

## `GET /oauth2/authorize` — authorization request

client 가 만든 URL 로 browser 가 이동한다. PKCE 의 `code_challenge` 와 대상 MCP Server 를 가리키는 `resource` 를 싣는다.

근거:
- OAuth: [RFC 6749 §3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3), [§4.1.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.1), [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1), [§4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1), [§7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1)
- RFC: [RFC 7636 §4.2](https://www.rfc-editor.org/rfc/rfc7636#section-4.2), [§4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [§2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1), [RFC 8252 §7.3](https://www.rfc-editor.org/rfc/rfc8252#section-7.3), [§8.4](https://www.rfc-editor.org/rfc/rfc8252#section-8.4)
- MCP 2025-11-25 Authorization: [Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation), [Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection)
- OpenID: [OpenID Connect Core 1.0 §3.1.2.1](https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest)

이동: 허브 [4.5 Authorization request 와 consent](MCP-AUTHORIZATION.md#s4-5) · [5.5 Redirect URI 와 PKCE](MCP-AUTHORIZATION.md#s5-5) · [5.6 re-consent](MCP-AUTHORIZATION.md#s5-6) · 시퀀스 [confidential](MCP-SEQUENCES.md#rt-authz-confidential) · [public](MCP-SEQUENCES.md#rt-authz-public)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `response_type` | 쿼리 | REQUIRED (RFC 6749 · OAuth 2.1 · OIDC Core) | `code` | 씀 |
| `client_id` | 쿼리 | REQUIRED | | 씀 — `official-shop-agent`(S17) · `local-mcp-client`(P3) |
| `redirect_uri` | 쿼리 | OPTIONAL (RFC 6749) · 하나만 등록됐으면 OPTIONAL, 여럿이면 REQUIRED (OAuth 2.1) · REQUIRED (OIDC Core) | 등록값과 simple string comparison 으로 같아야 한다(AS MUST, [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1)). 루프백 redirect 는 포트만 요청 값을 허용해야 한다(AS MUST, [RFC 8252 §7.3](https://www.rfc-editor.org/rfc/rfc8252#section-7.3) · [§8.4](https://www.rfc-editor.org/rfc/rfc8252#section-8.4) · [OAuth 2.1 §8.4.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-8.4.2)) | 씀 — P10 `http://127.0.0.1:9999/callback`(포트만 다름) 통과 |
| `scope` | 쿼리 | OPTIONAL (RFC 6749 · OAuth 2.1) · REQUIRED, `openid` 포함 MUST (OIDC Core) | 공백 구분 | 씀 — `openid profile` |
| `state` | 쿼리 | RECOMMENDED (RFC 6749) · OPTIONAL (OAuth 2.1) · RECOMMENDED (OIDC Core) · client 는 사용·검증 SHOULD ([MCP Open Redirection](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#open-redirection)) | CSRF 방지·요청 상관 | 씀 |
| `code_challenge` | 쿼리 | REQUIRED ([RFC 7636 §4.3](https://www.rfc-editor.org/rfc/rfc7636#section-4.3)) · REQUIRED or RECOMMENDED (OAuth 2.1, §7.5.1 참고) · client 는 PKCE 구현 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#authorization-code-protection)) | `BASE64URL(SHA256(code_verifier))`, 43~128자 | 씀 |
| `code_challenge_method` | 쿼리 | OPTIONAL, 생략 시 `plain` (RFC 7636 §4.3 · OAuth 2.1) · 가능하면 `S256` MUST ([RFC 7636 §4.2](https://www.rfc-editor.org/rfc/rfc7636#section-4.2) · OAuth 2.1 · MCP) | | 씀 — `S256` |
| `resource` | 쿼리 | 표시 없음 — client 가 넣을 수 있다 MAY ([RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2)) · MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | 절대 URI(MUST), fragment 금지(MUST NOT). 여러 번 넣어 여러 resource 지정 가능(MAY) | 씀(하나) — `http://localhost:8111/mcp` |
| `nonce` | 쿼리 | OPTIONAL (OIDC Core) | ID token 재전송 방지 | 씀 — Spring Security 가 `openid` 요청에 붙인다(S17). 캡처 스크립트는 보내지 않음 |
| `response_mode` | 쿼리 | OPTIONAL (OIDC Core) | 응답 전달 방식 | 쓰지 않음 |
| `display` | 쿼리 | OPTIONAL (OIDC Core) | 로그인 화면 표시 방식 | 쓰지 않음 |
| `prompt` | 쿼리 | OPTIONAL (OIDC Core) | `none`·`login`·`consent`·`select_account` | 쓰지 않음 |
| `max_age` | 쿼리 | OPTIONAL (OIDC Core) | 최대 인증 경과 시간 | 쓰지 않음 |
| `ui_locales` | 쿼리 | OPTIONAL (OIDC Core) | 화면 언어 | 쓰지 않음 |
| `id_token_hint` | 쿼리 | OPTIONAL (OIDC Core) | 이전 ID token | 쓰지 않음 |
| `login_hint` | 쿼리 | OPTIONAL (OIDC Core) | 로그인 식별자 힌트 | 쓰지 않음 |
| `acr_values` | 쿼리 | OPTIONAL (OIDC Core) | 인증 수준 요청 | 쓰지 않음 |

원문 필드: RFC 6749·OAuth 2.1 §4.1.1, RFC 7636 §4.3, RFC 8707 §2, OIDC Core §3.1.2.1 에 정의된 17개 → 표 17행

`response_mode` 부터 `acr_values` 까지는 OpenID Connect 가 더한 파라미터로, MCP authorization 명세의 범위 밖이다. OIDC Core 는 이 밖에도 `claims`, `request`, `request_uri` 같은 파라미터를 다른 절에서 정의하고, 이 practice 는 다루지 않는다.

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `302` → redirect URI | 표시 없음 — 결정이 나면 HTTP redirect 등으로 client 에 돌려보낸다(OAuth 2.1 §4.1.1) | [Authorization Response](#authorization-response) | confidential client 는 곧장 이것(C5, P13) |
| `200` consent 화면 | 표시 없음 — AS 는 resource owner 를 인증하고 결정을 얻는다(OAuth 2.1 §4.1.1). client·scope·수명 정보를 보여 주는 것이 좋다(SHOULD, [§7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3)) | 제출은 [`POST /oauth2/authorize`](#authorize-consent) | public client 는 매 요청마다(P3, P8) |
| `302 /login` | 명세 범위 밖(Spring 기본 동작) | 로그인 session 이 없을 때 | 씀 |

원문 필드: 없음(응답 형식은 문장으로 규정된다)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| redirect URI 가 없거나 틀림, client_id 가 없거나 틀림 | redirect 하지 않음(MUST NOT) — S4, P10-1 `400` JSON | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1) |
| `code_challenge` 없음 | redirect 로 `error=invalid_request` — C17, P9. public client 요청은 거부 MUST, 그 밖의 client 요청도 다른 방법으로 code injection 을 막는다는 합리적 확신이 없으면 거부 MUST | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1), [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1) |
| 지원하지 않는 `code_challenge_method` | redirect 로 `error=invalid_request` MUST | [OAuth 2.1 §4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1) |
| 허용 목록 밖 `resource` | redirect 로 `error=invalid_target` — C16 | [RFC 8707 §2.1](https://www.rfc-editor.org/rfc/rfc8707#section-2.1) |
| `resource` 가 여러 개 | redirect 로 `error=invalid_target` — 이 Authorization Server 는 resource 하나만 받는다. 테스트 `인가_요청의_resource_가_여러_개면_invalid_target_이다` | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) — 여러 개는 MAY, 받을 수 없으면 `invalid_target` |
| public client 가 scope 를 생략하거나 `openid` 하나만 요청 | redirect 로 `error=invalid_scope` — P8-1. `PublicClientScopeValidator` 가 consent 판정 전에 거부한다 | [RFC 6749 §3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3) — scope 생략은 기본값 처리나 `invalid_scope` MUST · [OAuth 2.1 §7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1) |
| 그 밖의 오류 | redirect 로 오류 파라미터 | [Authorization Response](#authorization-response) |

관측: S17 agent 가 만든 URL 에는 `nonce` 와 무작위 `state`·`code_challenge` 가 있다. 캡처 스크립트는 RFC 7636 부록 B 의 예시 `code_challenge` 를 쓴다.

**예시** (P3, official — public client, 로그인 session 이 있는 상태)

```http
GET /oauth2/authorize?response_type=code&client_id=local-mcp-client&redirect_uri=http%3A%2F%2F127.0.0.1%3A8123%2Fcallback&scope=openid%20profile&state=public-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp HTTP/1.1
Host: localhost:9010
```

```text
HTTP/1.1 200
Content-Type: text/html;charset=UTF-8
Content-Length: 2388

<title>Consent required</title>
<p><span class="font-weight-bold text-primary">local-mcp-client</span> wants to access your account <span class="font-weight-bold">user</span></p>
<form name="consent_form" method="post" action="/oauth2/authorize">
<input type="hidden" name="client_id" value="local-mcp-client">
<input type="hidden" name="state" value="tphddfAWPNDWbZiRo9UHe4c13B885u_TCoCxPoy72TU=">
<input class="form-check-input" type="checkbox" name="scope" value="profile" id="profile">
```

---

<a id="authorize-consent"></a>

## `POST /oauth2/authorize` — consent 제출

consent 화면의 폼이 같은 URI 로 사용자의 결정을 제출한다. 명세는 이 폼의 형식을 정하지 않는다. [OAuth 2.1 §7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3) 은 resource owner 를 명시적으로 인증하고 client·scope·수명 정보를 보여 주는 것이 좋다(SHOULD)고만 쓰므로, 아래 필드는 Spring Authorization Server 기본 폼의 것이다.

근거: [OAuth 2.1 §7.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3), [§7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1), [RFC 6749 §4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1)

이동: 허브 [4.5 Authorization request 와 consent](MCP-AUTHORIZATION.md#s4-5) · [5.6 re-consent](MCP-AUTHORIZATION.md#s5-6) · 시퀀스 [public](MCP-SEQUENCES.md#rt-authz-public)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `client_id` | 본문 | 표시 없음 (Spring 기본 폼) | 폼의 hidden 필드 | 씀 — `local-mcp-client` |
| `state` | 본문 | 표시 없음 (Spring 기본 폼) | 대기 중인 authorization 을 찾으려고 서버가 consent 화면에 새로 발급한 값. 원래 authorization request 의 `state` 가 아니다 | 씀 |
| `scope` | 본문 | 표시 없음 (Spring 기본 폼) | 고른 scope 마다 하나. `openid` 는 consent 대상이 아니라 체크박스가 없고, 서버가 다시 붙인다 | 씀 — `profile` |

원문 필드: 없음(명세가 consent 제출 형식을 정하지 않는다)

**응답**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `302` → redirect URI | 표시 없음 | code·원래 요청의 `state`·`iss` 를 싣는다. [Authorization Response](#authorization-response) | 씀 — P4 |

원문 필드: 없음(응답은 Authorization Response 절의 필드를 쓴다)

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 사용자가 거부 | redirect 로 `error=access_denied` — 관측 없음 | [RFC 6749 §4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1) |
| 같은 public client 의 다음 요청 | 이전에 consent 했어도 다시 consent 화면 — client 신원을 확인할 수 없으면 처음처럼 처리 SHOULD | [OAuth 2.1 §7.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-7.3.1) |

관측: P4 의 redirect 에는 원래 요청의 `state=public-state` 가 돌아온다. P8 두 번째 요청도 `200` consent 화면이다(`PublicClientConsentService`).

**예시** (P4, official)

```http
POST /oauth2/authorize HTTP/1.1
Host: localhost:9010
Content-Type: application/x-www-form-urlencoded

client_id=local-mcp-client&state=tphddfAWPNDWbZiRo9UHe4c13B885u_TCoCxPoy72TU%3D&scope=profile
```

```http
HTTP/1.1 302
Location: http://127.0.0.1:8123/callback?code=1sEzkjWnBLuu...&state=public-state&iss=http%3A%2F%2Flocalhost%3A9010
```

---

<a id="authorization-response"></a>

## Authorization Response — redirect

Authorization Server 가 `302` 로 browser 를 client 의 redirect URI 로 보내며 code 나 오류를 쿼리에 싣는다. client 는 code 를 token endpoint 로 보내기 전에 `state` 와 `iss` 를 검증한다.

근거:
- OAuth: [RFC 6749 §4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2), [§4.1.2.1](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1), [OAuth 2.1 §4.1.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2), [§4.1.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.2.1)
- RFC: [RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2), [§2.4](https://www.rfc-editor.org/rfc/rfc9207#section-2.4), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1)
- MCP 2026-07-28: [Authorization Response Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)

이동: 허브 [4.6 Callback 과 `iss`](MCP-AUTHORIZATION.md#s4-6) · [5.3 Mix-up](MCP-AUTHORIZATION.md#s5-3) · 시퀀스 [confidential](MCP-SEQUENCES.md#rt-authz-confidential) · [주요 오류 경로](MCP-SEQUENCES.md#rt-errors)

형태는 `302 Found`, `Location: <redirect_uri>?<파라미터>` 이고 파라미터는 쿼리 컴포넌트에 `application/x-www-form-urlencoded` 로 싣는다. 받는 곳은 agent 의 `GET /login/oauth2/code/authserver`, public client 는 `http://127.0.0.1:8123/callback`(P4)이다.

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| redirect URI | `Location` 헤더 | 등록값과 같아야 한다(AS MUST, [OAuth 2.1 §4.1.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.1)) | 요청 파라미터는 [`GET /oauth2/authorize`](#authorize) 에 있다 | 씀 |

원문 필드: 없음(이 절은 응답이다)

**응답 — 성공**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `code` | REQUIRED | 짧은 수명(최대 10분 RECOMMENDED), 한 번만 사용, client_id·redirect URI·code challenge 에 묶인다 | 씀 — C5 `M1Q5-XA-2DoF...` |
| `state` | 요청에 있었으면 REQUIRED, 받은 값 그대로 | | 씀 — C5 `walkthrough-state` |
| `iss` | OPTIONAL (OAuth 2.1) · RFC 9207 을 지원하는 서버는 MUST ([RFC 9207 §2](https://www.rfc-editor.org/rfc/rfc9207#section-2)) · SHOULD ([MCP 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#authorization-response-validation)) | Authorization Server 의 issuer 식별자 | 씀 — C5 `http%3A%2F%2Flocalhost%3A9010` |

원문 필드: RFC 6749 §4.1.2·OAuth 2.1 §4.1.2 에 정의된 3개 → 표 3행

client 는 모르는 파라미터를 무시해야 한다(MUST). OIDC 흐름에서 authorization endpoint 가 ID token 을 돌려주면 `iss` 는 그 ID token 의 `iss` 와 같아야 한다(MUST, RFC 9207 §2.4). 이 practice 는 code 흐름이라 Authorization Response 에 ID token 이 없다.

**응답 — 오류**

redirect URI 가 없거나 틀리거나, client_id 가 없거나 틀리면 redirect 하지 않는다(MUST NOT). 그 밖의 오류는 redirect URI 로 보낸다.

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `error` | REQUIRED | 아래 코드 중 하나 | 씀 — C16 `invalid_target` · C17 `invalid_request` · P8-1 `invalid_scope` |
| `error_description` | OPTIONAL | ASCII 설명 | 씀 — C17 `OAuth%202.0%20Parameter%3A%20code_challenge` |
| `error_uri` | OPTIONAL | 설명 페이지 URI | 씀 — C16 `https%3A%2F%2Fwww.rfc-editor.org%2Frfc%2Frfc8707%23section-2` |
| `state` | 요청에 있었으면 REQUIRED | | 씀 — C16, C17 `walkthrough-state` |
| `iss` | OPTIONAL (OAuth 2.1) · RFC 9207 을 지원하는 서버는 오류 응답에도 MUST | | 씀 — C16, C17 `http%3A%2F%2Flocalhost%3A9010` |

원문 필드: OAuth 2.1 §4.1.2.1 에 정의된 5개 → 표 5행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| `invalid_request` | 필수 파라미터 누락·잘못된 값·중복. PKCE 가 필요한데 `code_challenge` 없음, 지원하지 않는 변환 방식 — C17 | RFC 6749 §4.1.2.1 · [RFC 7636 §4.4.1](https://www.rfc-editor.org/rfc/rfc7636#section-4.4.1) |
| `unauthorized_client` | 이 client 는 이 방식으로 code 를 요청할 수 없음 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `access_denied` | resource owner 나 Authorization Server 가 거부 — 관측 없음(public client 의 consent 거부) | RFC 6749 §4.1.2.1 |
| `unsupported_response_type` | 이 방식의 code 발급 미지원 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `invalid_scope` | scope 가 잘못됐거나 알 수 없음 — P8-1 public client 의 `openid` 단독 요청 | RFC 6749 §4.1.2.1 · [§3.3](https://www.rfc-editor.org/rfc/rfc6749#section-3.3) |
| `server_error` | 서버 내부 오류(redirect 로는 500 을 줄 수 없어서 정의) — 관측 없음 | RFC 6749 §4.1.2.1 |
| `temporarily_unavailable` | 일시적 과부하·점검 — 관측 없음 | RFC 6749 §4.1.2.1 |
| `invalid_target` | 요청한 resource 가 잘못됐거나 알 수 없음 — C16 | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) |

client 쪽 검증도 이 응답에 걸린다. `iss` 가 기록해 둔 issuer 와 다르거나, `authorization_response_iss_parameter_supported: true` 인데 `iss` 가 없으면 응답을 거부해야 한다(MUST, MCP 2026-07-28 · RFC 9207 §2.4). 오류 응답에서 `iss` 가 다르면 `error`·`error_description`·`error_uri` 를 쓰거나 보여 주면 안 된다(MUST NOT).

관측: S18 조작한 `iss` 와 S19 `iss` 없는 callback 을 agent 가 `401` 로 거부하고 code 를 교환하지 않는다. S20 agent 의 정상 왕복에도 `iss` 가 있다.

**예시** (C5, official — confidential client)

```http
GET /oauth2/authorize?response_type=code&client_id=official-shop-agent&redirect_uri=http%3A%2F%2Flocalhost%3A8110%2Flogin%2Foauth2%2Fcode%2Fauthserver&scope=openid%20profile&state=walkthrough-state&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 302
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Location: http://localhost:8110/login/oauth2/code/authserver?code=M1Q5-XA-2DoF...&state=walkthrough-state&iss=http%3A%2F%2Flocalhost%3A9010
Content-Length: 0
```

---

<a id="token-authorization-code"></a>

## `POST /oauth2/token` — `authorization_code`

authorization code 를 access token 으로 바꾼다. confidential client 는 client 인증을, public client 는 `client_id` 와 PKCE 의 `code_verifier` 를 싣는다.

근거:
- OAuth: [RFC 6749 §4.1.3](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.3), [§5.1](https://www.rfc-editor.org/rfc/rfc6749#section-5.1), [§5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2), [OAuth 2.1 §2.4.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.4.1), [§3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)–[§3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4), [§4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3), [§10.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-10.2)
- RFC: [RFC 7636 §4.5](https://www.rfc-editor.org/rfc/rfc7636#section-4.5), [§4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6), [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2), [§2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)
- MCP·OpenID: [MCP 2025-11-25 Resource Parameter Implementation](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation), [OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)

이동: 허브 [4.7 Token request](MCP-AUTHORIZATION.md#s4-7) · 시퀀스 [Token request](MCP-SEQUENCES.md#rt-token) · [주요 오류 경로](MCP-SEQUENCES.md#rt-errors)

요청은 `Content-Type: application/x-www-form-urlencoded`, UTF-8 본문이다(OAuth 2.1 §3.2.2).

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `Authorization: Basic` | 헤더 | confidential client 는 인증 MUST ([OAuth 2.1 §3.2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.1)) · `client_secret_basic` 은 [§2.4.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-2.4.1) | `base64(client_id:client_secret)` | 씀 (confidential client) · public client 는 보내지 않음(P5) |
| `grant_type` | 본문 | REQUIRED | `authorization_code` | 씀 |
| `code` | 본문 | REQUIRED | Authorization Response 의 code | 씀 |
| `redirect_uri` | 본문 | authorization request 에 있었으면 REQUIRED, 같은 값 MUST (RFC 6749) · OAuth 2.1 은 목록에서 뺐고, 하위 호환은 [§10.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-10.2) | | 씀 |
| `client_id` | 본문 | client 인증을 하지 않을 때 REQUIRED ([OAuth 2.1 §4.1.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.1.3)) | | public client 만 씀 — P5 `local-mcp-client`. confidential client 는 Basic 인증이라 보내지 않음 |
| `code_verifier` | 본문 | REQUIRED ([RFC 7636 §4.5](https://www.rfc-editor.org/rfc/rfc7636#section-4.5)) · `code_challenge` 가 있었으면 REQUIRED, 없었으면 쓰면 안 됨 MUST NOT (OAuth 2.1) | 원래 무작위 문자열 | 씀 |
| `resource` | 본문 | 표시 없음 ([RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)) · token request 에 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | token 을 쓸 resource | 씀 — `http://localhost:8111/mcp` |

원문 필드: OAuth 2.1 §2.4.1·§3.2.2·§4.1.3, RFC 6749 §4.1.3, RFC 7636 §4.5, RFC 8707 §2.2 에 정의된 7개 → 표 7행

`scope` 는 이 grant 의 token request 파라미터로 정의되지 않았고, 이 practice 도 보내지 않는다.

**응답 — 헤더**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `Content-Type: application/json` | 표시 없음 — 본문은 `application/json` 을 쓴다 (RFC 6749 §5.1 · OAuth 2.1 §3.2.3) | | 씀 — `application/json;charset=UTF-8` |
| `Cache-Control: no-store` | MUST (RFC 6749 §5.1 · OAuth 2.1 §3.2.3) | token 이 든 모든 응답 | 씀 — `no-cache, no-store, max-age=0, must-revalidate` |
| `Pragma: no-cache` | MUST (RFC 6749 §5.1) · OAuth 2.1 은 요구하지 않음 | | 씀 — `no-cache` |

원문 필드: RFC 6749 §5.1·OAuth 2.1 §3.2.3 에 정의된 3개 → 표 3행

**응답 — 필드 (200)**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `access_token` | REQUIRED | | 씀 — `aud` 는 `resource` 값(C6-1, P5-1) |
| `token_type` | REQUIRED, 대소문자 무시 · OIDC 는 다른 타입을 협상하지 않았으면 `Bearer` MUST | | 씀 — `Bearer` |
| `expires_in` | RECOMMENDED | 초 단위 수명 | 씀 — `299` |
| `refresh_token` | OPTIONAL | 발급 여부는 AS 가 위험 평가와 정책으로 정하는 것이 좋다(SHOULD, [OAuth 2.1 §3.2.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.3)) | confidential client 만 — public client 에는 발급하지 않음(P5, P7) |
| `scope` | 요청과 같으면 OPTIONAL (RFC 6749) · RECOMMENDED (OAuth 2.1), 다르면 REQUIRED | | 씀 — `openid profile` |
| `id_token` | MUST — OIDC token 응답 ([OIDC Core §3.1.3.3](https://openid.net/specs/openid-connect-core-1_0.html#TokenResponse)) | `aud` 에 client_id MUST ([§2](https://openid.net/specs/openid-connect-core-1_0.html#IDToken)) | 씀 — `aud=official-shop-agent`(C6-2) |

원문 필드: RFC 6749 §5.1 의 5개와 OIDC Core §3.1.3.3 의 1개, 모두 6개 → 표 6행

**오류**

오류 응답은 기본 `400` 이고, JSON 본문에 `error`(REQUIRED)·`error_description`(OPTIONAL)·`error_uri`(OPTIONAL)를 싣는다(OAuth 2.1 §3.2.4).

| 상황 | 응답 | 근거 |
|---|---|---|
| `invalid_request` | 필수 파라미터 누락, 중복, 인증 방식 여러 개, `code_challenge` 없이 `code_verifier` 전송 등 — 관측 없음 | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 |
| `invalid_client` | client 인증 실패. `Authorization` 헤더로 인증을 시도했다면 `401` + `WWW-Authenticate` MUST. S8 `401` + `WWW-Authenticate: Basic realm="http://localhost:9010"` · P12 public client 가 Basic 으로 비밀을 보냄 → 같은 `401` · P14 confidential client 가 `client_id` 만 보냄 → `401`, `WWW-Authenticate` 없음 | RFC 6749 §5.2 · [OAuth 2.1 §3.2.4](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.4) |
| `invalid_grant` | code·refresh token 이 무효·만료·폐기, redirect URI 불일치, 다른 client 의 code, `code_verifier` 불일치 — S7, P11 `{"error":"invalid_grant"}` | RFC 6749 §5.2 · [RFC 7636 §4.6](https://www.rfc-editor.org/rfc/rfc7636#section-4.6) |
| `unauthorized_client` | 이 client 에 허용되지 않은 grant — 관측 없음 | RFC 6749 §5.2 |
| `unsupported_grant_type` | 지원하지 않는 grant — 관측 없음 | RFC 6749 §5.2 |
| `invalid_scope` | scope 가 잘못됐거나 허가 범위를 넘음 — 관측 없음 | RFC 6749 §5.2 |
| `invalid_target` — 다른 resource | authorization request 와 다른 `resource` — S6 `The requested resource does not match the authorization request` | [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) — 원래 허가된 resource 로 제한할 수 있다 |
| `invalid_target` — 없던 resource | authorization request 에 없던 `resource` 를 token request 에서 정함 — 테스트 `인가_요청에_없던_resource_를_토큰_요청에서_정하면_invalid_target_이다` | [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2) |
| `invalid_target` — resource 여러 개 | `resource` 를 두 번 이상 보냄 — 테스트 `토큰_요청의_resource_가_여러_개면_invalid_target_이다`. community 는 `SingleResourceTokenRequestConverter` 가 module customizer 의 `(String)` 캐스트(`500`)보다 먼저 거부한다 | [RFC 8707 §2](https://www.rfc-editor.org/rfc/rfc8707#section-2) |

관측: P5 public client 는 `Authorization` 헤더 없이 `client_id=local-mcp-client` 와 `code_verifier` 로 token 을 받고, 응답에 `refresh_token` 이 없다. C6-1·P5-1 의 access token `aud` 는 두 client 모두 `http://localhost:8111/mcp` 다.

**예시** (S5, official — confidential client)

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
Content-Length: 1702

{"access_token":"eyJraWQiOiI1ZDI5YjQ2...","refresh_token":"c2EWpcuQtqtf...","scope":"openid profile","id_token":"eyJraWQiOiI1ZDI5YjQ2...","token_type":"Bearer","expires_in":299}
```

---

<a id="token-refresh"></a>

## `POST /oauth2/token` — `refresh_token`

만료된 access token 을 refresh token 으로 다시 받는다. `resource` 를 다시 실어 새 token 의 audience 를 같은 MCP Server 로 유지한다.

근거:
- OAuth: [RFC 6749 §6](https://www.rfc-editor.org/rfc/rfc6749#section-6), [OAuth 2.1 §4.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3)–[§4.3.3](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.3), [§3.2.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.2), [RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)
- MCP·OpenID: [MCP 2026-07-28 Refresh Tokens](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#refresh-tokens), [OIDC Core §12.2](https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse)

이동: 허브 [4.10 만료와 refresh](MCP-AUTHORIZATION.md#s4-10) · 시퀀스 [만료와 refresh](MCP-SEQUENCES.md#rt-refresh)

URL 과 요청 형식은 [`authorization_code`](#token-authorization-code) 와 같다.

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `Authorization: Basic` | 헤더 | confidential client 는 인증 MUST ([OAuth 2.1 §4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1)) · `client_secret_basic` 은 §2.4.1 | refresh token 은 발급받은 client 에 묶인다 | 씀 |
| `grant_type` | 본문 | REQUIRED | `refresh_token` | 씀 |
| `refresh_token` | 본문 | REQUIRED | | 씀 |
| `scope` | 본문 | OPTIONAL | 원래 허가 범위를 넘으면 안 됨(MUST NOT). 생략하면 원래 범위 | 쓰지 않음 |
| `resource` | 본문 | 표시 없음 — 모든 grant 에 쓸 수 있음 ([RFC 8707 §2.2](https://www.rfc-editor.org/rfc/rfc8707#section-2.2)) · token request 에 MUST ([MCP](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#resource-parameter-implementation)) | 원래 허가된 resource 로 제한될 수 있다 | 씀 — `http://localhost:8111/mcp` |
| `client_id` | 본문 | OPTIONAL ([OAuth 2.1 §3.2.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-3.2.2)) | 인증에 필요하거나 public client 를 식별해야 할 때 | 쓰지 않음 — public client 는 refresh token 을 받지 않는다(P7) |

원문 필드: RFC 6749 §6, OAuth 2.1 §2.4.1·§3.2.2·§4.3.1, RFC 8707 §2.2 에 정의된 6개 → 표 6행

**응답 — 필드 (200)**

응답 형식은 `authorization_code` 의 성공 응답과 같다([OAuth 2.1 §4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2)).

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `access_token` | REQUIRED | | 씀 — 새 token. `aud` 는 그대로 MCP Server, `jti` 는 새 값(C11) |
| `token_type` | REQUIRED | | 씀 — `Bearer` |
| `expires_in` | RECOMMENDED | | 씀 — `299` |
| `refresh_token` | OPTIONAL — 새로 줄 수 있고(MAY), 주면 client 는 옛것을 버려야 한다(MUST) ([§4.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.2)) · public client 에는 회전이나 sender-constrained token 을 써야 한다(MUST, [§4.3.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-13#section-4.3.1)) | | 씀 — 처음 받은 값과 같다(회전 없음, confidential client 라 허용) |
| `scope` | 요청과 같으면 RECOMMENDED (OAuth 2.1), 다르면 REQUIRED | | 씀 — `openid profile` |
| `id_token` | 표시 없음 — 없을 수 있다 ([OIDC Core §12.2](https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse)) | 있으면 `iss`·`sub`·`aud` 가 처음 ID token 과 같아야 한다(MUST) | 씀 — 함께 재발급된다 |

원문 필드: OAuth 2.1 §3.2.3 의 5개와 OIDC Core §12.2 의 1개, 모두 6개 → 표 6행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| refresh token 무효·만료·폐기 | `400` `invalid_grant` | RFC 6749 §5.2 · OAuth 2.1 §3.2.4 |
| 그 밖 | [`authorization_code`](#token-authorization-code) 의 오류 표와 같다 | RFC 6749 §5.2 |

MCP 2026-07-28 은 client 가 refresh token 발급을 가정하면 안 된다고 한다(MUST NOT). 이 practice 의 public client 는 refresh token 자체를 받지 않아 회전 규칙이 해당하지 않는다.

관측: C11 세 practice 모두 새 access token 과 ID token 을 받고, refresh token 은 처음 값 그대로다.

**예시** (C11, official)

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9010
Authorization: Basic <base64(official-shop-agent:official-shop-agent-secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=UqY5N0Bi3YAG...&resource=http%3A%2F%2Flocalhost%3A8111%2Fmcp
```

```json
{"access_token":"eyJraWQiOiJlZDY1ZWFl...","refresh_token":"UqY5N0Bi3YAG...","scope":"openid profile","id_token":"eyJraWQiOiJlZDY1ZWFl...","token_type":"Bearer","expires_in":299}
```

---

<a id="jwks"></a>

## `GET /oauth2/jwks` — JWK Set

Authorization Server 의 서명 공개키를 JWK Set 으로 공개한다. MCP Server 는 metadata 의 `jwks_uri` 로 이 문서를 받아 access token 서명을 검증한다.

근거: [RFC 8414 §2](https://www.rfc-editor.org/rfc/rfc8414#section-2) (`jwks_uri`), [OpenID Connect Discovery 1.0 §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata) (`jwks_uri`), [RFC 7517 §4](https://www.rfc-editor.org/rfc/rfc7517#section-4), [§5](https://www.rfc-editor.org/rfc/rfc7517#section-5), [RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)

이동: 허브 [4.9 Token 검증](MCP-AUTHORIZATION.md#s4-9) · 시퀀스 [요청 검증](MCP-SEQUENCES.md#rt-token-validation)

**요청**

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| 메서드 `GET` | 요청 줄 | 표시 없음 | `jwks_uri` 의 URL. https MUST (RFC 8414 §2) | MCP Server 가 씀 — `http://localhost:9010/oauth2/jwks` |

원문 필드: 없음(RFC 7517 은 가져오는 요청을 정의하지 않는다)

**응답 — JWK Set**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `keys` | MUST ([§5](https://www.rfc-editor.org/rfc/rfc7517#section-5)) | JWK 배열. 순서는 선호를 뜻하지 않는다([§5.1](https://www.rfc-editor.org/rfc/rfc7517#section-5.1)) | 씀 — key 하나 |

원문 필드: RFC 7517 §5 에 정의된 1개 → 표 1행

**응답 — JWK**

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `kty` | MUST — JWK 에 있어야 한다 ([§4.1](https://www.rfc-editor.org/rfc/rfc7517#section-4.1)) | key 종류(`RSA`, `EC` 등) | 씀 — `RSA` |
| `use` | OPTIONAL ([§4.2](https://www.rfc-editor.org/rfc/rfc7517#section-4.2)) — §4.3 은 `use` 와 `key_ops` 를 함께 쓰지 않을 것(SHOULD NOT). 서명·암호화 key 를 함께 공개하면 모든 key 에 REQUIRED (RFC 8414 §2 `jwks_uri`) | `sig`·`enc` | 쓰지 않음 — 서명 key 만 있다 |
| `key_ops` | OPTIONAL ([§4.3](https://www.rfc-editor.org/rfc/rfc7517#section-4.3)) | 허용 연산 배열 | 쓰지 않음 |
| `alg` | OPTIONAL ([§4.4](https://www.rfc-editor.org/rfc/rfc7517#section-4.4)) | 이 key 로 쓸 알고리즘 | 쓰지 않음 |
| `kid` | OPTIONAL ([§4.5](https://www.rfc-editor.org/rfc/rfc7517#section-4.5)) — JWK Set 안의 key 는 서로 다른 `kid` SHOULD | JWS 헤더의 `kid` 와 맞춰 key 를 고른다 | 씀 — UUID |
| `x5u` | OPTIONAL ([§4.6](https://www.rfc-editor.org/rfc/rfc7517#section-4.6)) | X.509 인증서 URL | 쓰지 않음 |
| `x5c` | OPTIONAL ([§4.7](https://www.rfc-editor.org/rfc/rfc7517#section-4.7)) | X.509 인증서 체인 | 쓰지 않음 |
| `x5t` | OPTIONAL ([§4.8](https://www.rfc-editor.org/rfc/rfc7517#section-4.8)) | 인증서 SHA-1 지문 | 쓰지 않음 |
| `x5t#S256` | OPTIONAL ([§4.9](https://www.rfc-editor.org/rfc/rfc7517#section-4.9)) | 인증서 SHA-256 지문 | 쓰지 않음 |
| `n` | MUST — RSA 공개키 ([RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)) | modulus, Base64urlUInt | 씀 |
| `e` | MUST — RSA 공개키 ([RFC 7518 §6.3.1](https://www.rfc-editor.org/rfc/rfc7518#section-6.3.1)) | exponent, Base64urlUInt | 씀 — `AQAB` |

원문 필드: RFC 7517 §4 의 9개와 RFC 7518 §6.3.1 의 RSA 공개키 2개, 모두 11개 → 표 11행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 모르는 `kty`, 필수 멤버가 없는 key | 받는 쪽은 그 JWK 를 무시 SHOULD | [RFC 7517 §5](https://www.rfc-editor.org/rfc/rfc7517#section-5) |
| JWK Set 에 private·symmetric key 값 | 담으면 안 된다(MUST NOT) | [OIDC Discovery §3](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata) `jwks_uri` |

official Authorization Server 는 `JWKSource` bean 을 정의하지 않아, Spring Boot 자동 구성이 기동할 때 RSA key 를 만든다. 그래서 `kid`·`n` 은 기동할 때마다 다르다.

응답 `Content-Type` 은 `application/json;charset=ISO-8859-1` 이다. `NimbusJwkSetEndpointFilter`(spring-security-oauth2-authorization-server 7.1.0)가 `setContentType("application/json")` 뒤에 `getWriter()` 를 불러 servlet 기본 charset 이 붙는다.

**예시** (official — 캡처 스크립트에 없는 요청, `kid`·`n` 은 기동마다 다르다)

```http
GET /oauth2/jwks HTTP/1.1
Host: localhost:9010
```

```http
HTTP/1.1 200
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Content-Type: application/json;charset=ISO-8859-1
Content-Length: 429

{"keys":[{"kty":"RSA","e":"AQAB","kid":"a7cd7941-573b-4f3c-aee4-6b04a7c3724a","n":"rmHQMY49cwJObpj_4tA-..."}]}
```

---

<a id="cimd-document"></a>

## Client ID Metadata Document — 명세만, 미구현

client 가 자기 metadata JSON 을 HTTPS URL 에 올리고 그 URL 자체를 `client_id` 로 쓴다. Authorization Server 는 URL 형태의 `client_id` 를 만나면 그 문서를 가져와 검증한다. 서로 미리 알지 못하는 client 와 서버 사이의 기본 등록 방식으로 MCP 가 권장한다.

근거:
- draft·RFC: [CIMD draft-00 §3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3), [§4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4), [§4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1), [§4.3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.3), [§4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4), [§5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-5), [§6.5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.5), [§6.6](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.6), [RFC 7591 §2](https://www.rfc-editor.org/rfc/rfc7591#section-2)
- MCP: [2025-11-25 Client ID Metadata Documents](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#client-id-metadata-documents), [2026-07-28 Client Registration — Client ID Metadata Documents](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#client-id-metadata-documents)

이동: 허브 [4.4 Client 등록](MCP-AUTHORIZATION.md#s4-4) · 시퀀스 [CIMD](MCP-SEQUENCES.md#reg-cimd)

이 practice 는 CIMD 를 구현하지 않는다. Authorization Server 는 `client_id_metadata_document_supported` 를 광고하지 않고, 두 client 는 pre-registration 으로 등록되어 있다.

**요청** (Authorization Server → client 가 호스팅하는 URL)

| 이름 | 위치 | 표시 | 설명 | 이 practice |
|---|---|---|---|---|
| `client_id` URL | URL | MUST — `https` scheme, path 포함. dot segment·fragment·username·password 는 MUST NOT, query 는 SHOULD NOT, port 는 MAY ([§3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-3)) | 짧고 자주 바뀌지 않는 URL RECOMMENDED | 쓰지 않음 |
| 메서드 `GET` | 요청 줄 | 표시 없음 — Authorization Server 는 문서를 가져오는 것이 좋다 SHOULD ([§4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4) · MCP) | authorization request 에서 URL 형태의 `client_id` 를 만났을 때 | 쓰지 않음 |

원문 필드: 없음(가져오는 요청의 형식은 URL 제약 말고는 정의되지 않는다)

**응답 — metadata document 필드**

문서의 값은 RFC 7591 §2 의 client metadata 이름을 쓴다(§4.1). RFC 7591 §2 의 필드는 따로 적지 않으면 OPTIONAL 이다.

| 이름 | 표시 | 설명 | 이 practice |
|---|---|---|---|
| `client_id` | MUST — 문서 URL 과 simple string comparison 으로 같아야 한다 MUST ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | | 쓰지 않음 |
| `client_name` | OPTIONAL (RFC 7591) · MUST (MCP Implementation Requirements) | consent 화면에 보일 이름 | 쓰지 않음 |
| `redirect_uris` | OPTIONAL (RFC 7591, redirect 흐름은 등록 MUST) · MUST (MCP) | Authorization Server 는 요청의 redirect URI 를 이 목록과 대조 MUST (MCP) | 쓰지 않음 |
| `token_endpoint_auth_method` | OPTIONAL — 공유 비밀 방식(`client_secret_post`·`client_secret_basic`·`client_secret_jwt` 등)은 MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | `none` 이면 public client. `private_key_jwt` MAY (MCP) | 쓰지 않음 |
| `grant_types` | OPTIONAL | | 쓰지 않음 |
| `response_types` | OPTIONAL | | 쓰지 않음 |
| `client_uri` | OPTIONAL | client 정보 페이지 | 쓰지 않음 |
| `logo_uri` | OPTIONAL | 로고 | 쓰지 않음 |
| `scope` | OPTIONAL | | 쓰지 않음 |
| `contacts` | OPTIONAL | | 쓰지 않음 |
| `tos_uri` | OPTIONAL | | 쓰지 않음 |
| `policy_uri` | OPTIONAL | | 쓰지 않음 |
| `jwks_uri` | OPTIONAL — `jwks` 와 함께 쓰면 안 됨 MUST NOT | `private_key_jwt` 용 공개키 | 쓰지 않음 |
| `jwks` | OPTIONAL — `jwks_uri` 와 함께 쓰면 안 됨 MUST NOT | | 쓰지 않음 |
| `software_id` | OPTIONAL | | 쓰지 않음 |
| `software_version` | OPTIONAL | | 쓰지 않음 |
| `client_secret` | MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | 공유 비밀을 세울 방법이 없다 | 쓰지 않음 |
| `client_secret_expires_at` | MUST NOT ([CIMD §4.1](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.1)) | | 쓰지 않음 |

원문 필드: CIMD draft-00 §4.1 의 3개와 RFC 7591 §2 의 15개, 모두 18개 → 표 18행

**오류**

| 상황 | 응답 | 근거 |
|---|---|---|
| 문서를 가져오지 못함 | authorization request 중단 SHOULD | [CIMD §4.3](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.3) |
| 문서의 `client_id` 가 URL 과 다름, JSON 구조가 잘못됨, 필수 필드 없음 | 검증 MUST (MCP Implementation Requirements). 실패하면 `error=invalid_client` 또는 `invalid_request` (MCP 흐름 다이어그램) | [MCP Implementation Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#implementation-requirements), [Client ID Metadata Documents Flow](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#client-id-metadata-documents-flow) |
| 요청의 redirect URI 가 문서에 없음 | 검증 MUST | MCP Implementation Requirements |
| 오류 응답·잘못된 문서 | cache 하면 안 된다(MUST NOT) | [CIMD §4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4) |
| 정상 문서의 cache | cache 할 수 있고(MAY), cache 할 때 HTTP cache 헤더를 따른다(SHOULD, CIMD §4.4) · HTTP cache 헤더를 따라 cache SHOULD (MCP) | [CIMD §4.4](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-4.4), [MCP Implementation Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#implementation-requirements) |
| 사설·루프백 주소 URL | 가져오지 않는 것이 좋다(SHOULD, SSRF) | [CIMD §6.5](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.5) |
| 큰 응답 | 크기 제한 SHOULD, 권장 최대 5KB | [CIMD §6.6](https://www.ietf.org/archive/id/draft-ietf-oauth-client-id-metadata-document-00.html#section-6.6) |

관측: C3·S1 metadata 에 `client_id_metadata_document_supported` 가 없다.

**예시** (MCP 2025-11-25 · 2026-07-28 명세의 예시 문서 — CIMD draft-00 은 §6.2 에 일부 필드만 보인다)

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

---

<a id="dcr-register"></a>

## `POST /register` — Dynamic Client Registration, deprecated

client 가 사용자 개입 없이 `POST` 로 client_id 를 받는 방식이다. 등록 endpoint 는 JSON 본문의 HTTP POST 를 받아야 한다(MUST, [RFC 7591 §3](https://www.rfc-editor.org/rfc/rfc7591#section-3)).

[MCP 2026-07-28 Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration#dynamic-client-registration) 은 이 방식을 deprecated 로 표시하고 새 구현은 CIMD 를 쓰라고 한다. 그래도 쓰는 client 는 알맞은 `application_type` 을 지정해야 한다(MUST).

이 practice 는 DCR 을 켜지 않는다. official·chat-memory 는 Spring Authorization Server 기본값(꺼짐)이고, community 는 `spring.ai.mcp.authorizationserver.dynamic-client-registration.enabled: false` 로 끈다. metadata 에 `registration_endpoint` 가 없다(C3).

이동: 허브 [4.4 Client 등록](MCP-AUTHORIZATION.md#s4-4) · 시퀀스 [DCR](MCP-SEQUENCES.md#reg-dcr)
