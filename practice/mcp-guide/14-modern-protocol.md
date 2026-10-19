# 14. MCP 2026-07-28 — 요청 하나가 혼자 서면 authorization은 무엇이 달라지나

이 장은 [13장](13-cimd.md)의 practice에서 MCP 요청 형식만 2026-07-28로 올린 `mcp-2026-07-28` practice를 본다.
Authorization Server, 두 client type(ChatGPT형·Claude형), discovery, login과 consent는 13장과 같다.
달라지는 것은 agent와 MCP Server가 주고받는 MCP 요청과, 그 요청을 검사하는 MCP Server다.

## 14.1 2026-07-28 요청 형식의 필요성

2026-07-28에는 `initialize`와 session이 없다.
client는 요청마다 `_meta`에 버전·capability·client 정보를 넣고, HTTP header 세 개(`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`)를 붙인다.
명세가 왜 이렇게 바뀌었는지는 [9장](09-versions.md#97-initialize와-session의-제거-2026-07-28)에서 보았다.

10\~13장의 practice는 요청 형식이 2025-11-25였다.
그 practice가 쓰는 MCP Java SDK 2.0과 Spring AI 2.0이 2026-07-28을 모르기 때문이다([9장](09-versions.md#98-official이-따르는-기준)).
MCP Java SDK 2.2.0-M1에는 2026-07-28 서버가 들어 있어서, 이 practice의 MCP Server는 그 서버 위에 10\~13장의 장치를 그대로 얹는다.
SDK에는 아직 2026-07-28 client가 없으므로, agent는 MCP를 부르는 부분만 직접 만든 client로 바꾼다.
Spring AI `ChatClient`와 ollama 모델은 13장과 같다.

요청 형식이 바뀌면 authorization에서도 달라지는 것이 생긴다.
이 장에서 볼 것은 다음 여섯 가지다.

| 바뀌는 것 | 10\~13장(2025-11-25) | 이 practice(2026-07-28) | 절 |
|---|---|---|---|
| 요청이 혼자 서는가 | `initialize`로 버전과 capability를 한 번 협상한다 | 요청마다 `_meta`와 header를 보낸다. 서버가 받는 버전은 `server/discover`로 묻는다 | 14.3, 14.4 |
| scope 검사가 읽는 곳 | filter가 본문을 읽어 tool 이름을 찾는다 | filter는 `Mcp-Method`·`Mcp-Name` header만 읽고, header와 본문이 같은지는 SDK가 본다 | 14.6, 14.8 |
| 그래도 남는 틈 | filter가 본문을 transport와 같은 규칙으로 읽어 막는다 | 본문의 tool 이름이 문자열이 아니면 SDK가 대조를 건너뛴다. 실행하는 쪽이 이름을 한 번 더 본다 | 14.8 |
| challenge의 `scope` | 기본 scope가 모자라면 그것만 먼저 알린다 | 기본 scope와 tool scope가 모두 모자라면 한 challenge에 함께 적는다 | 14.7 |
| tool 목록의 cache 기간 | agent가 스스로 5분으로 정한다 | MCP Server가 `ttlMs`와 `cacheScope`로 정한다 | 14.5 |
| 옛 client | — | 2025-11-25 client는 이 MCP Server에 붙지 못한다 | 14.9 |

## 14.2 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber
    participant B as browser (user)
    participant G as shop-agent (8180)
    participant S as shop-mcp-server (8181)
    participant A as auth-server (9070)
    B->>G: GET /
    G->>S: POST /mcp server/discover (token 없음)
    S-->>G: 401 + resource_metadata
    Note over B,A: PRM, metadata, login, consent, token (13장과 같다)
    B->>G: 노트북 재고 있어?
    G->>S: server/discover + Bearer
    S-->>G: 200 supportedVersions
    G->>S: tools/list
    S-->>G: 200 tools, ttlMs 60000, cacheScope private
    G->>S: tools/call, Mcp-Name: searchProducts
    S-->>G: 200 content
    B->>G: p1 재고를 10개로 바꿔 줘
    G->>S: tools/call, Mcp-Name: updateStock
    S-->>G: 403 insufficient_scope, scope=products:write
    Note over B,A: consent 카드와 step-up (13장과 같다)
    G->>S: tools/list (새 token)
    S-->>G: 200 tools
    G->>S: tools/call, Mcp-Name: updateStock
    S-->>G: 200 content
```

[다이어그램 그림으로 보기](diagrams/14-modern-protocol-1.png)

| 단계 | 볼 값 |
|---|---|
| 1단계 (1)\~(3): token 없는 첫 요청 | 2026-07-28 header와 `_meta`, `401`의 `resource_metadata` |
| 2단계 (5)(6): `server/discover` | `supportedVersions`, 결과의 `_meta`에 든 서버 정보 |
| 3단계 (7)(8): `tools/list` | 서버가 정한 `ttlMs`와 `cacheScope` |
| 4단계 (9)(10): `tools/call` | `Mcp-Method`·`Mcp-Name` header |
| 5단계 (12)(13): scope 부족 | 한 challenge에 모은 `scope` |
| 6단계 (14)\~(17): step-up 뒤 | 새 token으로 다시 받는 목록과 실행된 `updateStock` |

agent가 MCP Server에 보내는 요청은 모두 `POST /mcp` 하나씩이고, 그림에서는 (2) 뒤로 `POST /mcp`를 줄였다.
(3)과 (4) 사이의 discovery·login·token 발급은 13장과 같아서 그림에서 뺐다.

아래 예시는 `practice/mcp-2026-07-28`을 실제로 띄워 받은 값이고, token이 필요한 요청은 agent 대신 캡처 스크립트가 curl로 보냈다.
Authorization Server는 `http://localhost:9070`, MCP Server는 `http://localhost:8181/mcp`, agent는 `http://localhost:8180`, 문서 host는 `https://localhost:8182`다.
조회 token은 ChatGPT형으로, 쓰기 token은 Claude형으로 받았고, JWT는 앞 20자만 남겼다.

## 14.3 1단계: token 없는 첫 요청

agent는 [3장](03-discovery.md)처럼 token 없이 MCP Server를 불러 discovery를 시작한다.
13장까지 이 첫 요청은 `initialize`였고, 이 practice에서는 2026-07-28 형식의 `server/discover`다.

```http
POST /mcp HTTP/1.1
Host: localhost:8181
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: server/discover

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "server/discover",
  "params": {
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientCapabilities": {},
      "io.modelcontextprotocol/clientInfo": {"name": "modern-shop-agent", "version": "0.0.1"}
    }
  }
}
```

```http
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8181/.well-known/oauth-protected-resource/mcp", scope="products:read"
```

2026-07-28 요청은 같은 정보를 header와 본문에 두 번 적는다.
header는 load balancer나 gateway처럼 본문을 읽지 않는 곳이 요청을 나누고 검사할 수 있게 하려는 것이다.
본문의 `_meta`는 2025-11-25에서 `initialize`가 한 번 협상하던 값을 요청마다 다시 알린다.

| 자리 | 이름 | 값 | 뜻 |
|---|---|---|---|
| header | `MCP-Protocol-Version` | `2026-07-28` | 이 요청의 버전이다. `_meta`의 버전과 같아야 한다 |
| header | `Mcp-Method` | `server/discover` | 본문의 `method`다. 모든 요청에 붙인다 |
| header | `Mcp-Name` | 이 요청에는 없다 | `tools/call`의 tool 이름(`params.name`)이다. 14.6에서 본다 |
| `_meta` | `io.modelcontextprotocol/protocolVersion` | `2026-07-28` | 2025-11-25에서 `initialize`로 협상하던 버전이다 |
| `_meta` | `io.modelcontextprotocol/clientCapabilities` | `{}` | client capability다. 이 agent는 elicitation·sampling·roots를 쓰지 않아서 빈 object다 |
| `_meta` | `io.modelcontextprotocol/clientInfo` | `modern-shop-agent` `0.0.1` | client의 이름과 버전이다 |

`401`과 PRM은 13장과 같고 주소만 다르다.
Spring Security가 본문을 읽기 전에 `401`로 답하므로, MCP Server의 SDK는 이 요청을 보지 않는다.
그래도 agent는 올바른 2026-07-28 요청을 보낸다.
요청이 올바르면, 서버가 무엇을 먼저 보든 실패할 이유는 token이 없다는 것 하나뿐이다.

```json
{
  "resource": "http://localhost:8181/mcp",
  "authorization_servers": ["http://localhost:9070"],
  "scopes_supported": ["products:read"],
  "...": "그 밖의 field는 생략"
}
```

## 14.4 2단계: `server/discover`로 서버가 받는 버전을 묻는다

agent는 기동한 뒤 처음 질문을 받으면, tool 목록을 받기 전에 질문한 사용자의 token으로 `server/discover`를 보낸다.
요청은 1단계와 같고 `Authorization: Bearer eyJraWQiOiI5YWQzNWE3...`만 더해진다.

```http
HTTP/1.1 200
Content-Type: application/json;charset=UTF-8

{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "supportedVersions": ["2026-07-28"],
    "capabilities": {"tools": {"listChanged": false}},
    "ttlMs": 0,
    "cacheScope": "private",
    "resultType": "complete",
    "_meta": {
      "io.modelcontextprotocol/serverInfo": {"name": "modern-shop-mcp-server", "version": "0.0.1"}
    }
  }
}
```

| field | 뜻 | agent가 할 일 |
|---|---|---|
| `supportedVersions` | 서버가 받는 버전의 목록이다 | `2026-07-28`이 없으면 tool을 부르기 전에 이유를 남기고 멈춘다 |
| `capabilities` | 서버가 가진 기능이다. 이 서버는 `tools`만 있고, `listChanged: false`는 목록 변경 알림을 보내지 않는다는 뜻이다 | 보지 않는다 |
| `_meta`의 `serverInfo` | 서버의 이름과 버전이다. 2025-11-25에서는 `initialize` 응답에 한 번 왔고, 이제 결과마다 온다 | 보지 않는다. 서버가 스스로 적은 값이라 동작을 정하는 데 쓰지 않는다 |
| `resultType` | `complete`는 결과가 끝났다는 뜻이다. 서버가 입력을 되물을 때는 `input_required`다 | `complete`가 아니면 오류로 본다. client capability가 없어서 서버가 되물을 일이 없기 때문이다 |
| `ttlMs`, `cacheScope` | 이 결과를 cache해도 되는 기간과 범위다. `0`은 cache하지 말라는 뜻이다 | 결과는 두지 않는다. 다음 절에서 본다 |

`server/discover`는 client가 꼭 보내야 하는 요청이 아니다.
서버가 모르는 버전이면 어느 요청에든 `400`과 `UnsupportedProtocolVersionError`(`-32022`)에 지원 버전 목록을 담아 답하기 때문이다.
agent가 먼저 묻는 것은, 모델이 tool을 고른 뒤가 아니라 질문을 처리하기 전에 분명한 이유로 멈추려는 것이다.
한 번 성공하면 agent process가 떠 있는 동안 다시 묻지 않고, 실패하면 다음 질문 때 다시 묻는다.
서버가 받는 버전은 요청마다 바뀌는 값이 아니므로, 결과의 `ttlMs`가 `0`이어도 2026-07-28을 받는다는 사실만은 기억한다.

`server/discover`도 MCP 요청이라 기본 scope `products:read`가 있는 token이어야 한다(14.7).

## 14.5 3단계: `tools/list`와 서버가 정하는 cache 기간

이어서 agent는 같은 token으로 `tools/list`를 보낸다.
header는 `Mcp-Method: tools/list`이고, 본문의 `params`에는 `_meta`만 있다.

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "tools": [
      "searchProducts (정의 생략)",
      "getStock (정의 생략)",
      {
        "name": "updateStock",
        "description": "상품 ID의 재고 수량을 quantity로 바꾼다. ... 처음 부르면 사용자에게 재고 변경 권한(products:write)을 묻는다.",
        "inputSchema": {"type": "object", "required": ["productId", "quantity"], "...": "그 밖의 field는 생략"}
      },
      "createBasket (정의 생략)",
      "addItem (정의 생략)",
      "getBasket (정의 생략)",
      "checkout (정의 생략)"
    ],
    "ttlMs": 60000,
    "cacheScope": "private",
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "modern-shop-mcp-server", "version": "0.0.1"}}
  }
}
```

tool 7개와 역할별 목록은 [12장](12-tool-visibility.md#123-1단계-역할로-거른-toolslist)과 같다.
점원 `user`는 7개를, 손님 `user2`는 `updateStock`을 뺀 6개를 받는다.
MCP Server 로그에는 `tools/list — 사용자=user, 역할=STAFF, 보인 tool=7/7`이 남는다.

**cache 기간을 서버가 정한다**

[12장](12-tool-visibility.md#127-web-agent-사용자별-tool-목록-cache)의 MCP Server는 2025-11-25라서 `ttlMs`와 `cacheScope`를 보내지 않았다.
그래서 agent가 TTL을 5분으로 스스로 정했다.
이 practice의 MCP Server는 목록에 두 값을 넣는다.
목록을 얼마나 오래, 누구와 같이 써도 되는지는 목록을 만든 쪽이 가장 잘 안다.
목록이 사용자의 역할로 달라지므로 `cacheScope`는 다른 access token과 나누면 안 되는 `private`이다.
역할 표는 서버가 떠 있는 동안 바뀌지 않아 목록을 잠시 cache해도 되므로, 이 서버는 `ttlMs`를 1분(`60000`)으로 정한다.

agent의 `UserToolCatalog`는 받은 값대로 목록을 둔다.

| 받은 값 | agent가 하는 일 |
|---|---|
| `ttlMs`가 `0`보다 크고 `cacheScope`가 `private` | 그 access token 값의 SHA-256 hex를 key로 `ttlMs` 동안 둔다 |
| `ttlMs`가 `0`보다 크고 `cacheScope`가 `public` | 사용자와 상관없는 목록이라 하나만 둔다. tool은 그래도 부르는 사용자의 token으로 부른다 |
| `ttlMs`가 `0`이거나 없음 | cache하지 않고 질문마다 다시 받는다 |
| 목록이 여러 쪽(`nextCursor`) | 모두 합치고, 가장 짧은 `ttlMs`와 가장 좁은 범위를 따른다 |
| tool 호출이 "모르는 tool" 오류 | TTL 전이라도 그 token의 목록을 버린다([12장](12-tool-visibility.md#128-web-agent-목록에-없는-tool을-모델이-부를-때)과 같다) |

key가 token이므로, step-up이나 refresh로 token이 바뀌면 목록을 다시 받는다.
그림의 (14)가 그 요청이다.
`private` 결과를 token마다 따로 두는 이유는 12장에서 본 것과 같다.

## 14.6 4단계: `tools/call`과 `Mcp-Name`

모델이 `searchProducts`를 고르면 agent는 `tools/call`을 보낸다.

```http
POST /mcp HTTP/1.1
Host: localhost:8181
Authorization: Bearer eyJraWQiOiI5YWQzNWE3...
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/call
Mcp-Name: searchProducts

{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "searchProducts",
    "arguments": {"keyword": "노트북"},
    "_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28", "...": "1단계와 같다"}
  }
}
```

```http
HTTP/1.1 200
Content-Type: application/json;charset=UTF-8

{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "content": [{"type": "text", "text": "- [p1] 게이밍 노트북 15인치 (노트북) / 1,890,000원 / 재고 7개\n- [p2] 사무용 노트북 14인치 (노트북) / 990,000원 / 재고 23개"}],
    "isError": false,
    "resultType": "complete",
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "modern-shop-mcp-server", "version": "0.0.1"}}
  }
}
```

`Mcp-Name`은 본문 `params.name`의 값을 header로 옮긴 것이다.
tool을 부르는 `tools/call`에만 붙고, `server/discover`와 `tools/list`에는 없다.
서버는 이 header를 풀어 본문의 이름과 맞춰 보므로, 이름은 header를 지나도 글자 그대로 남아야 한다.
그런데 header 값에는 출력할 수 있는 ASCII만 그대로 쓸 수 있고, HTTP는 header 값 앞뒤의 공백을 지울 수 있다.
그래서 ASCII 밖의 글자나 제어 문자가 있는 이름, 앞이나 뒤에 공백이 있는 이름은 UTF-8의 Base64로 바꿔 `=?base64?…?=`로 적는다.
`=?base64?`로 시작하고 `?=`로 끝나는 ASCII 이름도 Base64로 적는다.
그대로 보내면 서버가 그 이름을 Base64 표기로 보고 풀어 버리기 때문이다.
agent는 이 규칙을 따르고, 나머지 이름은 가운데 공백까지 바꾸지 않고 적는다.
예를 들어 이름이 `재고조회`인 tool이라면 `Mcp-Name: =?base64?7J6s6rOg7KGw7ZqM?=`다.
이 practice의 tool 이름은 모두 평범한 영문이라 그대로 간다.

응답에는 `Mcp-Session-Id`가 없다.
`Authorization` header는 전처럼 요청마다 붙이고, 서버가 사용자를 아는 방법은 token뿐이다([11장](11-stateless-and-handle.md#111-stateless의-필요성)).
이 서버는 `tools/call`에 `application/json` 본문 하나로 답하지만, agent는 서버가 SSE stream으로 답해도 읽는다.

## 14.7 5단계: scope가 모자라면 한 challenge에 모은다

`p1 재고를 10개로 바꿔 줘`를 받은 모델이 `updateStock`을 고르면, 조회 token으로 같은 모양의 `tools/call`이 간다.
header는 `Mcp-Name: updateStock`이고, 본문은 `"name": "updateStock"`과 `"arguments": {"productId": "p1", "quantity": 10}`이다.

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", scope="products:write", resource_metadata="http://localhost:8181/.well-known/oauth-protected-resource/mcp"
```

MCP Server 로그에는 다음 줄이 남는다.

```text
scope 부족 — 사용자=user, client_id=https://localhost:8182/oauth/client.json, tool=updateStock, 모자란 scope=products:write, 가진 scope=[openid, products:read]
```

`ToolScopeFilter`는 `Mcp-Name`에서 `updateStock`을 읽고, 이 tool에 필요한 `products:write`가 token에 없는 것을 본다.
이미 가진 `products:read`는 적지 않고, client가 다시 authorization을 받을 때 이전 scope와 합친다([10장](10-scope-and-step-up.md#105-3단계-합친-scope로-다시-authorization을-받는다)).

**기본 scope와 tool scope를 함께 적는다**

10\~13장의 filter는 본문을 읽기 전에 기본 scope `products:read`부터 보았다.
그래서 두 scope가 모두 없는 token으로 `checkout`을 부르면, 먼저 `scope="products:read"`가 오고 step-up 뒤에 `scope="orders:write"`가 다시 왔다.
동작 하나에 authorization을 두 번 받아야 하는 셈이다.
2026-07-28은 이번 동작에 필요한 scope를 challenge 한 번에 모두 적게 한다.
이 practice의 filter는 header에서 처음부터 tool 이름을 알므로, 모자란 scope를 모아 `scope="products:read orders:write"` 하나로 답한다.
agent의 token에는 첫 authorization 때부터 `products:read`가 있어서, browser 흐름에서는 이 경우가 생기지 않는다.

**step-up은 13장과 같다**

agent는 `403`을 받으면 채팅 아래에 `updateStock을(를) 하려면 products:write 권한이 더 필요합니다.`로 시작하는 카드와 "권한 허용" 버튼을 띄운다.
ChatGPT형의 consent 화면에서 새로 고를 항목은 `products:write` 하나이고, `products:read`는 "이미 허락한 권한" 아래에 나온다.
Claude형은 consent를 저장하지 않으므로 두 scope가 모두 새 항목으로 나온다([13장](13-cimd.md#136-3단계-consent-화면)).
허락하면 질문이 다시 가고, agent는 새 token으로 목록을 다시 받은 뒤 `updateStock`을 부른다.

캡처 스크립트는 `products:read`와 `products:write`를 함께 요청해 Claude형 쓰기 token을 받고, 같은 `tools/call`을 보낸다.

```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "result": {
    "content": [{"type": "text", "text": "상품 p1 (게이밍 노트북 15인치) 의 재고를 10개로 바꿨습니다."}],
    "isError": false,
    "resultType": "complete",
    "...": "그 밖의 field는 생략"
  }
}
```

MCP Server 로그에는 `updateStock 호출 (productId=p1, quantity=10, 사용자=user)`가 남는다.

## 14.8 MCP Server가 확인하는 것

요청 하나는 MCP Server 안에서 다음 순서로 검사를 지난다.

```mermaid
sequenceDiagram
    participant G as shop-agent
    participant T as McpTransportSecurityFilter
    participant Sec as Spring Security
    participant F as ToolScopeFilter
    participant X as HttpServletMcpTransport (SDK)
    participant R as ShopToolRepository
    G->>T: POST /mcp, header 셋, _meta
    Note over T: Origin, Host → 403, 421
    T->>Sec: 통과
    Note over Sec: token 검증 → 401
    Sec->>F: 통과
    Note over F: Mcp-Method, Mcp-Name으로 scope → 403, 호출 수 → 429
    F->>X: 통과
    Note over X: header와 본문 대조, 버전, 본문 크기 → 400, 413
    X->>R: find, call
    Note over R: primitiveName = Mcp-Name, 숨긴 tool → Unknown tool
    R-->>G: 200 결과
```

[다이어그램 그림으로 보기](diagrams/14-modern-protocol-2.png)

인증과 `401`·`403`·`429`는 SDK 앞의 servlet filter가 맡는다.
header와 본문의 대조, `_meta`와 버전 검사, 본문 상한은 SDK transport가 맡는다.

**header와 본문이 다르면 실행하지 않는다**

scope는 header로 보고, tool은 본문으로 실행한다.
아무도 둘을 맞춰 보지 않으면, header에는 조회 tool을, 본문에는 쓰기 tool을 적어 조회 token으로 쓰기 tool을 실행할 수 있다.
조회 token으로 `Mcp-Name: searchProducts`와 본문 `"name": "updateStock"`을 함께 보내면 다음과 같다.

```http
HTTP/1.1 400
Content-Type: application/json;charset=UTF-8

{"jsonrpc":"2.0","id":6,"error":{"code":-32020,"message":"Mcp-Name header does not match request name/uri"}}
```

filter는 `searchProducts`를 보고 `products:read`면 충분하다고 넘겼다.
그 뒤의 SDK transport가 header와 본문을 맞춰 보고 `-32020`(`HeaderMismatch`)으로 끝냈으므로 `updateStock`은 실행되지 않는다.
이 요청 뒤에 쓰기 token으로 부른 한 번(14.7)까지 합쳐도, 로그의 `updateStock 호출` 줄은 하나다.
2026-07-28은 본문을 처리하는 서버가 이렇게 맞춰 보게 한다.
그래서 이 서버의 filter는 본문을 읽지 않고 header만 믿을 수 있다.

**10장의 parser differential이 줄어드는 이유**

[10장](10-scope-and-step-up.md#108-서버-코드에서-보기)의 filter와 transport는 같은 본문을 따로 읽었다.
둘이 바이트를 다르게 읽으면 filter가 검사한 이름과 transport가 실행하는 이름이 달라지므로, filter는 transport와 같은 charset 규칙으로 본문을 읽어야 했다.
이 practice의 filter는 본문을 아예 읽지 않는다.
본문을 읽는 곳은 SDK transport 하나이고, 그 transport가 header와 다른 본문을 거절한다.
scope를 정하는 값과 실행하는 값을 서로 다른 parser가 만들 일이 구조상 줄어든다.
본문을 메모리에 담아 두던 `CachedBodyHttpServletRequest`도 필요 없고, 본문 상한(1MB)은 transport의 `maxRequestSize`가 `413`으로 지킨다.

**그래도 남는 틈: 문자열이 아닌 tool 이름**

조회 token으로 `Mcp-Name: searchProducts`를 붙이고 본문의 `name`을 숫자 `1`로 보내면 다음과 같다.

```http
HTTP/1.1 400
Content-Type: application/json;charset=UTF-8

{"jsonrpc":"2.0","id":7,"error":{"code":-32602,"message":"Unknown tool: 1"}}
```

이 응답은 header와 본문을 맞춰 본 결과가 아니다.
SDK 2.2.0-M1은 본문의 `name`이 문자열일 때만 `Mcp-Name`과 맞춰 보고, 숫자면 대조를 건너뛴다.
그 뒤 Jackson이 요청 객체를 만들면서 숫자 `1`을 문자열 `"1"`로 바꾼다.
이름이 `"1"`인 tool은 없으므로 SDK가 모르는 tool로 답한 것이다.

이 practice에서는 아무것도 실행되지 않았지만, 이름이 `"1"`인 tool이 있다면 이야기가 다르다.
filter는 header의 `searchProducts`로 scope를 보고 넘기는데, 실행되는 것은 tool `"1"`이 된다.
header만 보는 scope 검사가 우회되는 것이다.
그래서 `ShopToolRepository.call`은 SDK가 본문에서 그대로 꺼낸 이름(`ctx.primitiveName()`)이 있고, 그 이름이 filter가 본 `Mcp-Name`과 같을 때만 실행한다.
본문의 이름이 문자열이 아니면 `primitiveName`이 비어 있으므로 없는 tool과 같은 오류로 끝난다.
practice의 테스트는 이름이 `"1"`인 쓰기 tool을 따로 만들어, 숫자 이름의 호출이 그 tool을 실행하지 않는 것을 본다.

**header가 빠졌거나 풀 수 없을 때**

`Mcp-Name`이 없는 `tools/call`이면 filter는 tool scope를 정할 수 없어 기본 scope만 보고 넘기고, SDK가 `400`과 `-32020`으로 끝낸다.
`=?base64?…?=` 표기인데 풀 수 없는 `Mcp-Name`이면 filter가 `400`, `-32020`, `Mcp-Name header has a malformed Base64 value`로 직접 끝낸다.
SDK 2.2.0-M1은 `=?base64?=` 같은 값을 풀다 예외를 던져 `500`으로 답하기 때문이다.
`Mcp-Name`은 filter와 SDK가 같은 규칙으로 푼다(`McpCaller.decodeMcpName`).
푸는 규칙이 다르면 filter가 본 이름과 SDK가 대조한 이름이 어긋난다.

**버전 header를 filter가 따로 보지 않는 이유**

2026-07-28은 header로 정책을 정하는 gateway에게, `MCP-Protocol-Version`이 header와 본문을 맞춰 보는 버전인지 먼저 확인하게 한다.
옛 버전의 요청은 서버가 header를 본문과 맞춰 본다는 보장이 없기 때문이다.
이 서버의 SDK는 2026-07-28 요청만 받고, 실행하기 전에 버전과 header를 본문과 맞춰 본다.
SDK가 맞춰 보지 못하는 문자열이 아닌 이름은 앞에서 본 대로 `ShopToolRepository`가 막는다.
filter가 넘긴 요청이라도 이 두 검사를 지나지 못하면 실행되지 않으므로, filter는 버전을 따로 보지 않는다.

**오류의 HTTP 상태**

SDK 2.2.0-M1은 JSON-RPC 오류 code에 따라 HTTP 상태를 정한다.
`-32602`(Invalid params), `-32020`, `-32021`, `-32022`는 `400`이고, `-32601`(Method not found)은 `404`다.
그래서 `-32602`로 끝나는 숨긴 tool과 없는 tool의 호출은 [12장](12-tool-visibility.md#124-2단계-숨긴-tool을-부르면)에서는 HTTP `200`이었지만 이 practice에서는 `400`이다.
그 밖의 code는 HTTP `200`이다.
숨긴 tool은 `ShopToolRepository.find`가 `null`을 돌려주므로, 이 practice에서도 없는 tool과 같은 `Unknown tool: <이름>`을 받는다.
오류 본문에는 `code`와 `message`만 있고, tool이 던진 예외는 `-32603` `Internal error`가 되며 자세한 내용은 로그에만 남는다.

| 확인 | 어기면 생기는 일 |
|---|---|
| filter는 `Mcp-Name`으로 tool scope를 보고 본문은 읽지 않는다 | 본문을 따로 읽으면 filter와 SDK가 같은 바이트를 다른 이름으로 읽을 수 있다 |
| SDK는 header의 method·이름·버전이 본문과 같은지 본다 | header에는 조회 tool을, 본문에는 쓰기 tool을 적어 조회 token으로 쓰기 tool을 실행한다 |
| 실행하는 쪽은 SDK가 본문에서 그대로 꺼낸 이름이 `Mcp-Name`과 같을 때만 실행한다 | 숫자 이름으로 header 대조를 건너뛰어, scope를 본 tool과 다른 tool이 실행된다 |
| 숨긴 tool은 scope를 보지 않고 넘기고, 없는 tool과 같은 오류로 답한다 | step-up이 시작되거나, 응답 차이로 숨긴 tool이 있다는 것이 알려진다 |
| 풀 수 없는 `Mcp-Name`은 filter가 `400`으로 끝낸다 | SDK가 `500`으로 답한다 |
| 본문 상한은 transport가 `413`으로 지킨다 | 아주 큰 본문 하나로 서버 메모리를 채운다 |

## 14.9 옛 client는 붙지 못한다

2025-11-25 client도 이 MCP Server의 discovery는 그대로 따라간다.
token 없는 첫 요청은 형식과 상관없이 Spring Security가 `401`로 답하고, authorization 단계는 MCP 메시지의 버전을 읽지 않기 때문이다([9장](09-versions.md#98-official이-따르는-기준)).
그래서 옛 client는 token까지 받은 뒤, token을 붙인 첫 MCP 요청에서 실패한다.

2025-11-25 client는 `Mcp-Method`를 모르고, 명세대로라면 `MCP-Protocol-Version`도 `initialize`를 마친 뒤에야 붙인다.
이 서버는 `Mcp-Method`부터 보므로, 버전 header가 있든 없든 답은 같다.

```http
POST /mcp HTTP/1.1
Authorization: Bearer eyJraWQiOiI5YWQzNWE3...
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":8,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"modern-shop-agent","version":"0.0.1"}}}
```

```http
HTTP/1.1 400
Content-Type: application/json;charset=UTF-8

{"jsonrpc":"2.0","id":8,"error":{"code":-32020,"message":"Missing required header: Mcp-Method"}}
```

같은 `initialize`를 2026-07-28 header와 `_meta`를 붙여 보내도 받아 주지 않는다.

```http
HTTP/1.1 404
Content-Type: application/json;charset=UTF-8

{"jsonrpc":"2.0","id":8,"error":{"code":-32601,"message":"Method not found: initialize"}}
```

이 서버는 `initialize`라는 method를 아예 모른다.
2026-07-28은 구현하지 않은 method에 `404`와 `-32601`로 답하게 한다.
JSON-RPC 오류 본문이 있으므로, client는 이 `404`를 옛 HTTP+SSE 서버가 주는 `404`와 구별할 수 있다.

[9장](09-versions.md#97-initialize와-session의-제거-2026-07-28)의 표대로, 2026-07-28만 받는 서버에 옛 client는 붙지 못한다.
dual-era client는 2026-07-28 요청을 먼저 보내고 이 서버에서는 새 형식에 머물지만, 옛 client에게는 새 형식으로 넘어갈 방법이 없다.
그래서 이 practice는 agent의 MCP client를 바꾼다(14.11).

명세는 2026-07-28만 받는 서버가 `initialize`에 돌려주는 오류에 자기가 받는 버전을 적기를 권한다.
옛 client의 사용자가 볼 수 있는 단서가 그 메시지 하나뿐일 수 있기 때문이다.
SDK 2.2.0-M1의 두 응답은 버전을 적지 않으므로, 옛 client의 사용자는 `Missing required header: Mcp-Method`만 보게 된다.

## 14.10 서버 코드에서 보기

클래스는 `practice/mcp-2026-07-28/shop-mcp-server/src/main/java/dev/starryeye/modern/mcpserver/` 아래에 있다.

**`ModernMcpConfig`: SDK 서버를 servlet으로 등록한다**

Spring AI는 아직 2026-07-28 서버를 감싸지 않는다.
그래서 SDK의 `McpServer`와 servlet transport `HttpServletMcpTransport`를 직접 만들어 `/mcp`에 servlet으로 등록한다.

```java
@Bean
public McpServer mcpServer(ShopToolRepository repository) {
    return McpServer.builder()
            .serverInfo(Implementation.builder("modern-shop-mcp-server", "0.0.1").build())  // 결과마다 _meta로 간다
            .feature(ToolsFeature.ofSync(repository))           // tools/list·tools/call을 넘길 곳
            .build();                                           // server/discover는 SDK가 직접 답한다
}

@Bean
public ServletRegistrationBean<HttpServletMcpTransport> mcpServlet(McpServer server, McpLimitProperties limits) {
    HttpServletMcpTransport transport = HttpServletMcpTransport.builder(server)
            .endpoint(ENDPOINT)                                 // "/mcp"
            .contextExtractor(McpCaller::context)               // token의 사용자와 Mcp-Name을 넘긴다
            .maxRequestSize(limits.maxRequestBodyBytes())       // 넘으면 413
            .build();
    ServletRegistrationBean<HttpServletMcpTransport> registration = new ServletRegistrationBean<>(transport, ENDPOINT);
    registration.setAsyncSupported(true);
    /* 이름과 load-on-startup 설정 */
    return registration;
}
```

이 servlet은 Spring MVC의 `DispatcherServlet`을 거치지 않지만, servlet filter인 Spring Security는 그 앞에서 그대로 돈다.
filter의 순서(`McpTransportSecurityFilter` → Spring Security → `ToolScopeFilter`)는 `McpTransportConfig`가 정한다.
`McpCaller.context`는 요청마다 검증한 token의 `sub`·`client_id`와, 푼 `Mcp-Name`을 transport context에 넣는다.
tool 저장소는 `ctx.transportContext()`로 이 값을 꺼낸다.

**`ShopTool`과 `ShopTools`: tool 정의를 data로 둔다**

SDK의 2026-07-28 서버는 annotation으로 tool을 찾지 않는다.
그래서 `ShopTools`가 tool 7개의 이름·설명·입력 schema·필요한 scope·handler를 `ShopTool` record로 적는다.
13장의 `@McpTool`과 `@RequiredScope`가 하던 일이다.
값은 13장의 tool과 같다.
scope 표(`ToolScopeRegistry`)도 같은 목록에서 만드므로, 서버가 내놓는 tool이 scope 표에서 빠질 수 없다.

**`ShopToolRepository`: 목록, 숨김, 실행할 이름**

```java
public ToolsPage list(McpRequestContext ctx, String cursor) {
    String subject = McpCaller.subject(ctx.transportContext()).orElse(null);
    List<Tool> visible = /* 역할로 받을 수 없는 scope의 tool을 뺀 목록. 순서는 정의한 순서 */;
    return new ToolsPage(visible, null, this.listTtl.toMillis(), CacheScope.PRIVATE);  // ttlMs 60000, private
}

public Tool find(McpRequestContext ctx, String name) {
    ShopTool tool = visibleTool(ctx, name);
    return (tool != null) ? tool.tool() : null;                 // 숨긴 tool은 null → SDK가 Unknown tool
}

public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
    String checked = McpCaller.mcpName(ctx.transportContext()); // filter가 본 Mcp-Name
    String primitiveName = ctx.primitiveName();                 // 본문의 name. 문자열이 아니면 null
    if (primitiveName == null || !primitiveName.equals(checked) || !primitiveName.equals(request.name())) {
        throw unknownTool(request.name());                      // 없는 tool과 같은 -32602
    }
    /* 숨긴 tool이면 unknownTool. 아니면 handler에 사용자와 arguments를 넘긴다 */
}
```

SDK는 입력 검증보다 `find`를 먼저 부른다.
그래서 인자가 틀린 호출에도 숨긴 tool과 없는 tool이 같은 답을 받는다.

**`ToolScopeFilter`: header만 읽는다**

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    String mcpName = request.getHeader("Mcp-Name");
    String tool = McpCaller.decodeMcpName(mcpName);
    if (mcpName != null && tool == null) {
        /* 400, -32020, "Mcp-Name header has a malformed Base64 value" */
        return;
    }
    JwtAuthenticationToken token = /* 인증이 JwtAuthenticationToken이면 그것, 아니면 null */;
    String subject = (token != null) ? token.getToken().getSubject() : null;
    Set<String> granted = /* token의 scope. token이 null이면 빈 집합 */;
    boolean toolCall = "tools/call".equals(request.getHeader("Mcp-Method"));
    if (!toolCall) {
        tool = null;                                            // tools/call이 아니면 기본 scope만
    }
    List<String> missing = required(subject, tool).stream()    // 기본 scope, 숨기지 않은 tool의 scope
            .filter(scope -> !granted.contains(scope)).toList();
    if (!missing.isEmpty()) {
        insufficientScope(response, token, tool, missing, granted);  // 403, scope="모자란 scope 모두"
        return;
    }
    /* tools/call이면 사용자마다 호출 수를 세고, 상한을 넘으면 429와 Retry-After */
    chain.doFilter(request, response);
}
```

10장의 filter가 하던 본문 읽기, charset 맞추기, 형식 검사가 모두 빠졌다.
13장 practice의 `McpProtocolVersionFilter`는 2025-11-25까지의 버전만 받아서, 남겨 두면 2026-07-28 요청을 모두 막는다.
그래서 이 practice에는 없고, 버전 검사는 SDK가 한다.
없어진 다른 클래스와 그 이유는 [practice README의 mcp-cimd와 다른 점](../mcp-2026-07-28/README.md#mcp-cimd와-다른-점)에 있다.

## 14.11 agent 코드에서 보기

클래스는 `practice/mcp-2026-07-28/shop-agent/src/main/java/dev/starryeye/modern/agent/` 아래에 있다.

**`ModernMcpClient`: 요청 하나에 `POST` 하나**

```java
private HttpRequest request(String accessToken, String method, String name, JsonRpc.JSONRPCRequest body) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(this.endpoint)
            .timeout(this.requestTimeout)                       // 20초
            .header("Content-Type", APPLICATION_JSON)
            .header("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM)
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)   // "2026-07-28"
            .header("Mcp-Method", method)
            .POST(HttpRequest.BodyPublishers.ofByteArray(write(body)));  // params._meta에 META
    if (name != null) {
        builder.header("Mcp-Name", mcpName(name));              // tools/call만. header에 그대로 못 쓰는 이름은 Base64 표기
    }
    if (accessToken != null) {
        builder.header("Authorization", "Bearer " + accessToken);
    }
    return builder.build();
}
```

`META`는 `_meta`의 세 값(`protocolVersion` `2026-07-28`, `clientCapabilities` `{}`, `clientInfo` `modern-shop-agent` `0.0.1`)이다.
JSON 타입은 SDK의 `McpSchema`·`JsonRpc`를 쓰고, HTTP 요청과 응답 해석만 이 클래스가 한다.
redirect를 따라가는 `HttpClient`는 받지 않는다.
따라가면 access token이 든 요청이 MCP Server가 고른 다른 주소로 가기 때문이다.
응답이 SSE stream이면 `data:` 줄을 모아 같은 `id`의 응답을 찾고, keep-alive 주석과 다른 `id`의 메시지는 건너뛴다.

응답은 HTTP 상태와 JSON-RPC `code`로 나눈다.

| 응답 | 예외 | 그 뒤 |
|---|---|---|
| `403`과 `error="insufficient_scope"` | `StepUpRequiredException`(challenge의 scope) | 채팅까지 올라가 consent 카드가 된다 |
| `429` | `ToolRateLimitedException`(`Retry-After`) | 모델에게 tool 오류 문장으로 간다 |
| `-32602`이고 message가 `Unknown tool: `로 시작 | `UnknownToolException` | 그 token의 목록을 버리고, 모델에게 tool 오류 문장으로 간다 |
| `-32020`·`-32021`·`-32022` | `McpProtocolMismatchException` | client가 요청을 잘못 만든 것이라 다시 보내도 결과가 같다. 로그에 WARN을 남기고, 모델에게 tool 오류 문장으로 간다 |
| 응답 없음(연결 거절, 시간 초과) | `McpTransportException` | 모델에게 tool 오류 문장으로 간다 |
| `resultType`이 `complete`가 아님 | `ModernMcpException` | 이 agent는 서버가 입력을 되묻는 흐름을 다루지 않는다 |

`-32602`는 인자가 틀렸을 때도 쓰이므로 message까지 보고 모르는 tool을 가른다.

**`UserToolCatalog`와 `ModernToolCallback`: 사용자를 tool마다 묶는다**

```java
public List<ToolCallback> callbacks(Authentication user) {
    String token = this.accessToken.apply(user);                // 만료됐으면 여기서 refresh된다
    discoverOnce(token);                                        // 처음 한 번 server/discover
    /* 만료된 항목을 치우고, 이 token의 목록이 cache에 있으면 쓰고, 없으면 tools/list로 받는다 */
    return tools.stream()
            .<ToolCallback>map(tool -> new ModernToolCallback(tool, this.client,
                    () -> this.accessToken.apply(user),         // 부를 때마다 이 사용자의 지금 token
                    () -> forget(token)))                       // 모르는 tool이면 이 token의 목록을 버린다
            .toList();
}
```

`ChatController`는 요청 thread에서 login한 사용자(`Authentication`)를 꺼내 이 메서드에 넘긴다.
tool 호출은 reactor의 thread에서 돌지만, callback이 사용자를 들고 있으므로 thread-local인 `SecurityContextHolder`에서 사용자를 찾지 않는다.
token을 꺼내는 `OAuth2AuthorizedClientManager`도 SecurityContext 없이, 넘겨받은 사용자로 authorized client를 찾는다.
13장의 agent는 reactor thread로 옮겨 온 SecurityContext에서 사용자를 읽어 MCP 요청에 token을 붙였다.
이 agent에는 그럴 일이 없으므로, SecurityContext를 reactor thread로 옮기는 `spring.reactor.context-propagation: auto` 설정을 두지 않는다.

`ModernToolCallback`은 SDK의 `Tool`로 Spring AI의 `ToolDefinition`을 만들고, 호출을 `ModernMcpClient.callTool`에 넘긴다.
결과 content의 text를 이어 붙여 돌려주고, tool이 실패한 결과(`isError`)도 같은 방식으로 돌려준다.
`StepUpRequiredException`은 그대로 던진다.
Spring AI는 `ToolExecutionException`만 잡으므로, 이 예외는 tool loop를 지나 채팅 응답까지 올라가 consent 카드가 된다.
그 밖의 MCP 오류는 `ToolExecutionException`으로 바꿔, 모델이 오류 문장을 tool 결과로 받고 사용자에게 설명하게 한다.

**`McpAuthorizationDiscovery`: probe도 2026-07-28 형식**

token 없는 첫 요청의 본문과 header만 1단계의 `server/discover`로 바뀌었다.
`401`의 `resource_metadata` → PRM → Authorization Server Metadata 순서와 검사는 13장과 같다.

**두 bean에만 기댄다**

`ToolCatalogConfig`는 `ModernMcpClient`와 `UserToolCatalog` 두 bean을 만들고, 다른 클래스는 이 둘에만 기댄다.
SDK에 2026-07-28 client가 생기면 이 두 클래스만 바꾸면 된다.
13장 agent에서 MCP 요청에 token을 붙이고 step-up 예외를 올리던 클래스들과 Spring AI의 MCP client starter는 이 practice에 없다([practice README](../mcp-2026-07-28/README.md#mcp-cimd와-다른-점)).

## 14.12 다루지 않는 것

- MRTR(`input_required`): 서버가 client에게 입력을 되묻는 2026-07-28의 방식이다.
  agent는 client capability를 선언하지 않는다.
- `subscriptions/listen`: 목록 변경 같은 알림을 받는 stream이다.
  서버는 `listChanged: false`를 알린다.
- `x-mcp-header`(`Mcp-Param-*`): tool 인자를 header로 옮기는 방식이다.
  이 practice의 tool 정의에는 없고, agent도 처리하지 않는다.
  명세는 tool 정의에 이 표시가 있으면 client가 그 header를 붙이게 하므로, 이 확장을 쓰는 MCP Server에 붙으려면 agent가 이 표시를 읽어 header를 붙여야 한다.
- tasks와 그 밖의 확장.
- 2025-11-25와 2026-07-28을 함께 받는 dual-era 서버.
- 실제 제품을 이 MCP Server에 붙이는 것: Claude Code(CLI)처럼 사용자 기기에서 도는 client를 붙이려면 Authorization Server가 공인 인증서를 믿고 `localhost` redirect의 포트를 무시해 비교해야 한다.

## 14.13 직접 해 보기

```bash
# 저장소 최상위 폴더에서. 처음 실행하면 세 서버가 모두 뜨기까지 2~3분 걸린다
practice/mcp-2026-07-28/run.sh

# 1단계: token 없는 server/discover. 401과 resource_metadata, scope="products:read"가 온다
curl -i -X POST http://localhost:8181/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: server/discover' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"curl","version":"1.0"}}}}'
```

**token이 필요한 요청**: 캡처 스크립트로 본다.
스크립트는 처음 보는 client의 consent 화면을 지나야 하므로, 서버를 다시 띄운 직후에 돌린다.

```bash
# 저장소 최상위 폴더에서. 출력의 JWT는 앞 20자, refresh token과 code는 앞 12자만 남는다
practice/mcp-2026-07-28/stop.sh
practice/mcp-2026-07-28/run.sh
docs/superpowers/captures/mcp-2026-07-28-walkthrough.sh > /tmp/modern-walkthrough.txt
```

2\~6단계의 `server/discover`, `tools/list`, `tools/call`, `403`, 쓰기 token의 `updateStock`이 한 번에 기록된다.
header와 본문이 다른 요청, 숫자 tool 이름, 옛 형식과 2026-07-28 형식의 `initialize`도 같은 출력에 있다.
같은 방법으로 받은 기록이 [2026-07-28 캡처](../../docs/superpowers/captures/2026-10-18-modern-walkthrough.txt)에 있다.

**browser**: `http://localhost:8180`을 열고(`127.0.0.1`이 아니다), 아래 순서대로 하며 `practice/mcp-2026-07-28/logs/`의 로그를 본다.

| 할 일 | 볼 것 |
|---|---|
| `user`/`password`로 login하고 `products:read`를 체크해 제출 | consent 화면은 13장과 같다. 제목은 `권한 요청: Shop Agent (ChatGPT형)`이다 |
| `노트북 재고 있어?` 보내기 | 재고를 알려 주는 답이 온다 |
| `p1 재고를 10개로 바꿔 줘` 보내기 | `updateStock을(를) 하려면 products:write 권한이 더 필요합니다.`로 시작하는 카드와 "권한 허용" 버튼이 뜬다. `shop-mcp-server.log`의 `scope 부족` 줄에 `모자란 scope=products:write`가 있다 |
| "권한 허용"을 누르고 `products:write`를 체크해 제출 | consent 화면의 새 항목은 `products:write` 하나이고 `products:read`는 "이미 허락한 권한" 아래에 있다. 재고가 10개로 바뀌고, 로그에 `updateStock 호출 (productId=p1, quantity=10, 사용자=user)`가 있다 |
| `practice/mcp-2026-07-28/stop.sh` 뒤 `practice/mcp-2026-07-28/run.sh claude`, 같은 순서 | step-up의 consent 화면에 `products:read`와 `products:write`가 모두 새 항목으로 나온다. 둘 다 체크해 제출하면 재고가 바뀐다 |

해 볼 것과 기대 결과의 전체 목록은 [practice README의 직접 확인할 것](../mcp-2026-07-28/README.md#직접-확인할-것)에 있다.

## 14.14 정리

- 2026-07-28 요청은 혼자 선다. client는 요청마다 `_meta`에 버전·capability·client 정보를 넣고 `MCP-Protocol-Version`·`Mcp-Method`·`Mcp-Name` header를 붙이며, 서버 정보는 결과의 `_meta`로 받는다.
- 서버가 받는 버전은 `server/discover`로 물을 수 있다. agent는 처음 한 번 묻고, `2026-07-28`이 없으면 tool을 부르기 전에 멈춘다.
- scope filter는 `Mcp-Name` header만 보고, header와 본문이 같은지는 SDK가 본다. 그래서 검사하는 쪽과 실행하는 쪽이 본문을 따로 읽는 일이 없다.
- SDK 2.2.0-M1은 문자열이 아닌 tool 이름의 대조를 건너뛴다. 그래서 실행하는 쪽은 SDK가 본문에서 그대로 꺼낸 이름이 `Mcp-Name`과 같을 때만 실행한다.
- header 덕분에 filter는 처음부터 tool 이름을 알고, 모자란 scope를 한 challenge에 모아 알린다.
- 목록의 cache 기간과 범위는 MCP Server가 `ttlMs`·`cacheScope`로 정한다. `private` 목록은 access token마다 따로 두고, step-up으로 token이 바뀌면 다시 받는다.
- 2026-07-28만 받는 서버에 옛 client는 붙지 못한다. 옛 `initialize`는 `400`, 2026-07-28 형식의 `initialize`는 `404`이고, 그래서 agent의 MCP client를 바꾼다.

## 14.15 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| client는 모든 POST 요청에 `MCP-Protocol-Version` header를 넣고, 값은 `_meta`의 `io.modelcontextprotocol/protocolVersion`과 같다. 다르면 서버는 `400`과 `HeaderMismatch`로 거절한다 | [MCP 2026-07-28 Streamable HTTP — Request Metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#request-metadata) | MUST |
| 모든 요청에 `Mcp-Method`를, `tools/call`·`resources/read`·`prompts/get`에는 `Mcp-Name`을 붙인다 | [Streamable HTTP — Standard Request Headers](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#standard-request-headers) | REQUIRED |
| `Mcp-Name`에 ASCII 밖의 글자나 제어 문자, 앞뒤 공백이 있거나 `=?base64?…?=` 모양이면 UTF-8의 Base64를 `=?base64?…?=`로 적는다. 서버는 풀어서 본문과 비교한다 | [Streamable HTTP — Value Encoding](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#value-encoding) | MUST |
| client는 tool 정의의 인자에 `x-mcp-header`가 있으면 그 값을 `Mcp-Param-{Name}` header로 붙인다 | [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http), [Tools — x-mcp-header](https://modelcontextprotocol.io/specification/2026-07-28/server/tools#x-mcp-header) | MUST |
| 본문을 처리하는 서버는 header 값이 본문과 다르거나, 필요한 header가 없거나 잘못되었으면 `400`과 `-32020`(`HeaderMismatch`)으로 거절한다 | [Streamable HTTP — Server Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#server-validation) | MUST |
| header로 정책을 정하는 중개자는 `MCP-Protocol-Version`이 header와 본문을 맞춰 보는 버전인지 확인하고, 아니면 요청을 거절한다 | [Streamable HTTP — Server Validation](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#server-validation) | SHOULD |
| 서버는 요청한 버전을 구현하지 않으면 `400`과 `UnsupportedProtocolVersionError`에 지원 버전을 담아 답하고, 구현하지 않은 method에는 `404`와 `-32601`로 답한다 | [Streamable HTTP — Request Metadata](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#request-metadata), [Versioning — Protocol Version Negotiation](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#protocol-version-negotiation) | MUST |
| 서버는 `server/discover`를 구현한다. client는 다른 요청 전에 불러 지원 버전을 알아도 되고, 부르지 않아도 된다 | [Versioning — Protocol Version Negotiation](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#protocol-version-negotiation), [Discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover) | MUST, MAY |
| 서버는 `_meta`에 `serverInfo`를 넣는다. client는 이 값으로 동작을 바꾸거나 보안 판단을 하지 않는다 | [Discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover) | SHOULD, SHOULD NOT |
| 2026-07-28만 받는 서버는 `initialize`에 돌려주는 오류에 지원 버전을 적는다. 옛 client의 HTTP 요청은 필요한 header가 없어 `400`으로 거절되고, 옛 client에게는 새 버전으로 넘어갈 방법이 없다 | [Versioning — Backward Compatibility with Initialization-Based Versions](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#backward-compatibility-with-initialization-based-versions) | SHOULD |
| client는 요청마다 새 `POST`를 보내고 `Accept`에 `application/json`과 `text/event-stream`을 넣으며, 서버의 JSON 응답과 SSE 응답을 모두 처리한다 | [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | MUST |
| `Authorization` header는 client가 서버로 보내는 모든 HTTP 요청에 넣는다 | [MCP 2026-07-28 Authorization — Token Requirements](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#token-requirements) | MUST |
| scope가 모자란 요청에 서버는 `403`과 `error="insufficient_scope"`·`scope`·`resource_metadata`로 답한다. 이번 동작에 필요한 scope는 한 challenge에 모두 넣고, 이미 허락된 scope는 넣지 않아도 된다 | [Authorization — Runtime Insufficient Scope Errors](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#runtime-insufficient-scope-errors), [Scope Challenge Handling](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#scope-challenge-handling) | SHOULD |
| 다시 authorization을 받을 때 client는 이전에 요청한 scope와 challenge의 scope를 합친다 | [Authorization — Step-Up Authorization Flow](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#step-up-authorization-flow), [Scope Selection Strategy](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#scope-selection-strategy) | SHOULD |
| 서버는 `tools/list` 같은 결과에 `ttlMs`와 `cacheScope`를 넣는다. `ttlMs`는 0 이상이다 | [MCP 2026-07-28 Caching — Cacheable Results](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#cacheable-results), [Time-to-Live (TTL) Field](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#time-to-live-ttl-field) | MUST |
| `"private"` 결과는 같은 authorization context에서 다시 쓸 수 있지만, 다른 authorization context와 cache를 나누면 안 된다 | [Caching — Cache Scope Field](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#cache-scope-field) | MAY, MUST NOT |
| 서버는 tool마다 알맞은 접근 제어를 하고, 무단 접근을 막는 데 `cacheScope`에만 기대지 않는다 | [Caching — Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#security-considerations) | MUST, MUST NOT |

[← 13장](13-cimd.md) · [목차](README.md) · [부록: API 레퍼런스 →](reference-api.md)
