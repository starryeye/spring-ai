# 12. tool 목록과 권한 — 받을 수 없는 tool은 숨기고, 받을 수 있는 tool은 step-up한다

## 12.1 권한별 tool 목록의 필요성

10장과 11장의 MCP Server는 누구에게나 같은 tool 목록을 준다.
조회 token으로도 `tools/list`에 `updateStock`이(11장에서는 `checkout`도) 보이고, scope는 tool을 부를 때 검사한다.
그런데 실제 서비스에는 허락을 아무리 받아도 할 수 없는 일이 있다.
가게의 손님은 재고를 바꿀 수 없고, 손님이 consent 화면에서 무엇을 허락하든 이 사실은 그대로다.

**할 수 없는 일을 하는 tool을 보여 주면**

손님에게도 `updateStock`이 보이면, 손님이 `p1 재고를 10개로 바꿔 줘`라고 할 때 모델은 이 tool을 부른다.
MCP Server는 `403`으로 `products:write`를 요구하고, client는 손님에게 consent 화면을 띄운다.
서버가 scope만 검사하면 손님은 consent 한 번으로 재고를 바꾸고, 역할까지 검사해 거절하면 받아도 쓸 수 없는 scope를 묻는 consent 화면만 본다.
어느 쪽이든 목록은 그 사용자가 알 필요 없는 기능의 이름과 설명을 보여 준다.

**지금 token의 scope로 거르면 step-up 입구가 사라진다**

쓸 수 없는 tool을 숨기는 가장 쉬운 기준은 지금 token의 scope다.
2026-07-28 server/tools도 `tools/list`가 연결에 따라 달라지면 안 되지만 요청에 붙은 authorization에 따라서는 달라도 된다고 하면서, token의 scope가 허락하는 tool만 주는 예를 든다.
그러나 이 기준은 [10장](10-scope-and-step-up.md)의 step-up과 충돌한다.
점원 `user`의 첫 token에는 `products:read`만 있으므로, 이 기준이면 점원에게도 `updateStock`이 보이지 않는다.
모델은 목록에 없는 tool을 모르므로 부르지 않고, 그런 기능이 없다고 답한다.
`403`이 오지 않으니 step-up도 시작되지 않고, 점원은 재고를 바꿀 길이 없다.
scope로 거르는 방식은 필요한 scope를 처음부터 모두 받는 client에서만 제대로 동작한다.
그런데 처음부터 모두 받으면 10장에서 본 최소 권한의 이점을 잃는다.

**두 질문을 나눈다**

그래서 이 장의 MCP Server는 tool마다 두 가지를 따로 묻는다.

| 질문 | 아니면 | 답을 아는 곳 |
|---|---|---|
| 이 사용자가 이 tool의 scope를 언젠가 받을 수 있는가 | 목록에서 숨긴다. 불러도 없는 tool과 같은 오류로 답한다 | MCP Server의 역할 표 |
| 지금 token에 그 scope가 있는가 | 목록에 보여 주고, 부를 때 `403`으로 step-up한다 | token의 `scope` |

**권한과 scope**

이 장에서 권한은 사용자가 이 서비스에서 원래 할 수 있는 일이다.
점원은 재고를 바꿀 수 있고 손님은 바꿀 수 없다는 정보는 서비스의 사용자 데이터에 있으므로, MCP Server가 안다.
scope는 사용자가 이 client에게 맡긴 범위이고, token에 들어 있다([10장 최소 권한의 필요성](10-scope-and-step-up.md#101-최소-권한의-필요성)).
client가 사용자 대신 실제로 할 수 있는 일은 권한과 scope가 겹치는 부분이다.
권한 밖의 tool은 숨기고, 권한 안이지만 scope 밖의 tool은 보여 준 뒤 step-up한다.
이 practice의 목록은 요청에 붙은 token의 `sub`만으로 정해지므로, 연결이 아니라 authorization에 따라 달라진다는 명세의 조건에 맞는다.
쓸 수 없는 tool을 숨길지, 보여 주고 부를 때 거절할지는 명세가 정하지 않았다.

이 장의 practice `mcp-tool-visibility`는 11장의 `mcp-stateless-handle`에 사용자 역할을 더한다.
`user`는 점원이라 tool 7개를 모두 보고, `user2`는 손님이라 `updateStock`을 뺀 6개를 본다.
web agent는 이렇게 사용자마다 다른 목록을 access token별로 cache한다(12.7).

## 12.2 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber
    participant U as MCP client (user, 점원)
    participant V as MCP client (user2, 손님)
    participant M as MCP Server
    participant A as Authorization Server
    Note over U,V: scope는 둘 다 openid products:read
    U->>M: tools/list + user의 token
    Note over M: sub=user → STAFF
    M-->>U: tool 7개
    V->>M: tools/list + user2의 token
    Note over M: sub=user2 → CUSTOMER
    M-->>V: tool 6개 (updateStock 없음)
    V->>M: tools/call updateStock
    M-->>V: 200, error -32602 Unknown tool
    U->>M: tools/call updateStock
    M-->>U: 403 insufficient_scope, scope=products:write
    U->>A: step-up (products:write를 더한 scope)
    A-->>U: 새 access token
    U->>M: tools/call updateStock + 새 token
    M-->>U: 200 재고 변경
```

[다이어그램 그림으로 보기](diagrams/12-tool-visibility-1.png)

| 단계 | 볼 값 |
|---|---|
| 1단계 (1)\~(4): 역할로 거른 `tools/list` | 점원은 7개, 손님은 `updateStock`을 뺀 6개를 같은 순서로 받는다 |
| 2단계 (5)(6): 숨긴 tool을 부른다 | HTTP `200` 안의 JSON-RPC 오류 `-32602`다. 정말 없는 tool의 응답과 같고, `WWW-Authenticate`가 없다 |
| 3단계 (7)\~(12): 받을 수 있는 tool은 step-up한다 | `403`과 `scope="products:write"`. 그 뒤는 10장과 같다 |

(9)(10)은 10장의 step-up을 줄여 그렸다.
그림은 사용자마다 client를 나눠 그렸지만, web agent에서는 MCP client 하나를 모든 사용자가 같이 쓰고 요청마다 그 사용자의 token을 붙인다.

아래 예시는 `practice/mcp-tool-visibility`를 실제로 띄워 받은 값이다.
Authorization Server는 `http://localhost:9050`, MCP Server는 `http://localhost:8161/mcp`, agent는 `http://localhost:8160`이다.
요청은 curl이 agent의 client `visibility-shop-agent`로 받은 token을 붙여 보냈고, 두 사용자 모두 처음 token의 scope는 `products:read openid`다.

## 12.3 1단계: 역할로 거른 `tools/list`

점원과 손님은 같은 client로 같은 scope를 받았으므로, 두 access token은 `scope`(`["products:read","openid"]`)와 `client_id`(`visibility-shop-agent`)가 같고, `sub`만 `user`와 `user2`로 다르다.
각 token을 붙여 같은 `tools/list`를 보낸다.

```http
POST /mcp HTTP/1.1
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-11-25
Authorization: Bearer eyJraWQiOiJmNzc5MDY0...

{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

두 요청 모두 `200`을 받지만, 본문의 크기는 `user`가 `Content-Length: 5010`, `user2`가 `Content-Length: 4269`로 다르다.
응답의 `result.tools`에서 tool 이름만 순서대로 뽑으면 다음과 같다(첫 줄이 `user`, 둘째 줄이 `user2`).

```text
names(7개): getStock,searchProducts,updateStock,addItem,checkout,createBasket,getBasket
names(6개): getStock,searchProducts,addItem,checkout,createBasket,getBasket
```

MCP Server는 `tools/list`마다 `tools/list — 사용자=user2, 역할=CUSTOMER, 보인 tool=6/7`처럼 사용자, 역할, 보인 tool 수를 로그에 남긴다.

**역할 표와 받을 수 있는 scope**

MCP Server는 token의 `sub`로 역할을 찾는다.
역할 표에는 `user` → 점원(`STAFF`) 한 줄만 있다.
표에 없는 사용자와 `sub`가 없는 token은 손님(`CUSTOMER`)으로 보아, 권한을 모르는 사용자에게 넓은 권한을 주지 않는다.

| 역할 | 계정 | 받을 수 있는 scope |
|---|---|---|
| 점원(`STAFF`) | `user` | `products:read`, `products:write`, `orders:write` |
| 손님(`CUSTOMER`) | `user2`, 표에 없는 모든 사용자 | `products:read`, `orders:write` |

tool마다 필요한 scope(10장의 `@RequiredScope`)를 이 표와 맞춰 보면, 손님에게는 `updateStock`만 숨겨진다.
숨길 tool의 표를 따로 두지 않고, tool마다 적어 둔 scope를 그대로 쓴다.

| tool | 필요한 scope | 점원 `user` | 손님 `user2` |
|---|---|---|---|
| `searchProducts`, `getStock`, `createBasket`, `addItem`, `getBasket` | `products:read` | 보인다 | 보인다 |
| `checkout` | `orders:write` | 보인다. 처음 부르면 step-up한다 | 보인다. 처음 부르면 step-up한다 |
| `updateStock` | `products:write` | 보인다. 처음 부르면 step-up한다 | 숨긴다. 부르면 "모르는 tool" 오류다 |

**두 목록의 순서가 같은 이유**

손님의 목록은 점원의 목록에서 `updateStock`만 뺀 것이고, 같은 token으로 한 번 더 받은 목록도 순서가 같다.
MCP Server는 원래 목록에서 숨길 tool만 빼고 순서는 건드리지 않는다.
2026-07-28은 tool 집합이 바뀌지 않았으면 늘 같은 순서로 돌려주라고 권한다.
그래야 client가 목록을 믿고 cache할 수 있고, tool을 모델 context에 넣을 때 prompt cache도 잘 맞는다는 것이다.
prompt cache는 앞부분이 같은 prompt의 계산을 다시 쓰는 기능이라, tool 정의의 순서만 바뀌어도 다른 prompt가 된다.
원래 목록의 순서는 Spring AI가 `@McpTool` 메서드를 등록한 순서로, bean 안에서는 메서드 이름 순이고 bean끼리는 만들어진 순서다.
한 서버 안에서는 늘 같지만, 실행 환경이 다르면 달라질 수 있다.

## 12.4 2단계: 숨긴 tool을 부르면

목록에서 뺐다고 그 tool을 부를 수 없게 되는 것은 아니다.
client는 이름만 알면 `tools/call`을 보낼 수 있고, 그 이름은 낡은 cache, 다른 사용자의 목록, 모델이 지어낸 이름, prompt injection에서 올 수 있다.
그래서 MCP Server는 부를 때도 다시 막는다.

손님 `user2`의 조회 token으로 다음 `tools/call`을 보낸다.

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"updateStock","arguments":{"productId":"p1","quantity":10}}}
```

응답은 다음과 같다(cache·보안용 header는 뺐다).

```http
HTTP/1.1 200
Content-Type: application/json
Content-Length: 129

{"jsonrpc":"2.0","id":7,"error":{"code":-32602,"message":"Unknown tool: invalid_tool_name","data":"Tool not found: updateStock"}}
```

같은 token으로 `updateStock`과 길이가 같은, 서버에 없는 이름 `updateStack`도 불러 두 응답을 비교하면 다음과 같다.

| 비교 | 숨긴 `updateStock` | 없는 `updateStack` |
|---|---|---|
| 상태와 `Content-Length` | `200`, `129` | `200`, `129` |
| `WWW-Authenticate` | 없다 | 없다 |
| `error`의 `code`와 `message` | `-32602`, `Unknown tool: invalid_tool_name` | 같다 |
| `error`의 `data` | `Tool not found: updateStock` | `Tool not found: updateStack` |

`data`에는 요청한 이름이 들어가서, 길이가 같은 이름과 비교해야 이름 말고 다른 점이 없는지 볼 수 있다.
`message`는 MCP Java SDK가 2025-11-25 명세의 오류 예시 문장을 그대로 쓰는 고정 문자열이다.

**같아야 하는 이유**

숨긴 tool에 `403`이나 다른 문장으로 답하면, 손님은 그 응답만 보고 `updateStock`이 있다는 것을 안다.
이름을 바꿔 가며 불러 보면 숨긴 tool의 이름을 하나씩 알아낼 수 있고, 관리자용 tool의 이름은 공격할 곳을 찾는 사람에게 쓸모 있는 정보다.
응답이 없는 tool과 같으면, 손님은 숨긴 tool과 처음부터 없는 tool을 구별하지 못한다.
명세는 없는 tool을 tool 결과가 아니라 JSON-RPC 오류(protocol error)로 다루므로, 이 응답은 명세의 형식 그대로다.

**`products:write`가 있어도 같다**

Authorization Server는 역할을 모른다.
그래서 client가 `user2`의 authorization request에 `products:write`를 넣으면, Authorization Server는 consent 화면에 그 체크박스를 보여 주고 허락받은 대로 발급한다.
curl로 이렇게 받은 token의 `scope`는 `["openid","products:read","products:write"]`다.
그래도 이 token으로 받은 목록은 6개이고, `updateStock(p1, 10)`은 같은 "모르는 tool" 오류이며, 호출 전후의 `getStock(p1)` 결과는 모두 재고 7개다.
MCP Server는 token의 scope가 아니라 역할로 숨길지를 정하기 때문이다.
Security Best Practices도 token에 적힌 scope만 믿고 서버 쪽 권한 판단을 하지 않는 것을 흔한 실수로 꼽는다.
MCP Server가 손님에게 `products:write`를 요구하는 `403`을 보내지 않으므로, web agent와 `local-client`가 이 scope를 요청할 일은 없다.

## 12.5 3단계: 받을 수 있는 tool은 그대로 step-up

점원은 `products:write`를 받을 수 있으므로, `updateStock`을 숨기지 않고 10장 그대로 step-up하게 한다.
점원 `user`의 조회 token으로 12.4와 같은 `tools/call`을 보내면 다음 응답이 온다.

```http
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", scope="products:write", resource_metadata="http://localhost:8161/.well-known/oauth-protected-resource/mcp"
```

MCP Server의 로그에는 `scope 부족 — 사용자=user, client_id=visibility-shop-agent, tool=updateStock, 필요한 scope=products:write, 가진 scope=[openid, products:read]`가 남는다.
그 뒤의 흐름은 [10장 web agent의 consent 카드](10-scope-and-step-up.md#106-web-agent-대화-안-consent-카드)와 같다.
browser에서 점원이 "권한 허용"을 누르면 consent 화면의 새 체크박스는 `products:write` 하나다.
허락하면 질문이 다시 가고, MCP Server에는 `updateStock 호출 (productId=p1, quantity=10, 사용자=user)`가 찍힌다.

**tool 설명의 마지막 문장**

`updateStock`과 `checkout`의 설명은 `처음 부르면 사용자에게 재고 변경 권한(products:write)을 묻는다.`와 `처음 부르면 사용자에게 주문 권한(orders:write)을 묻는다.`로 끝난다.
모델은 token에 어떤 scope가 있는지 모르고, 이 문장으로 scope가 아직 없어도 이 tool을 불러도 된다는 것을 안다.
모델이 scope가 모자랄까 걱정해 이 tool을 피하면 `403`이 오지 않아, 12.1에서 본 것처럼 step-up이 시작되지 않는다.

**검사 순서**

`ToolScopeFilter`는 `tools/call`을 세 단계로 본다.

1. 기본 scope `products:read`가 있는지 본다. 없으면 tool과 상관없이 `403`과 `scope="products:read"`다.
2. 보이는 tool인지 본다. 숨긴 tool이면 tool의 scope를 보지 않고 transport로 넘기고, transport가 "모르는 tool"로 답한다.
3. tool의 scope가 있는지 본다. 없으면 `403 insufficient_scope`와 모자란 scope다.

기본 scope는 tool 이름을 보기 전에 검사하므로, 기본 scope가 없는 token은 숨긴 tool을 부르든 없는 tool을 부르든 같은 `403`을 받는다.

**거꾸로면 생기는 일**

2와 3의 순서가 바뀌면, 손님의 조회 token으로 부른 `updateStock`은 먼저 scope 검사에서 `403`과 `scope="products:write"`를 받는다.

1. 손님은 `updateStock`이 있다는 것을 안다. 없는 tool에는 `403`이 오지 않기 때문이다.
2. client는 step-up을 시작하고, 손님에게 `products:write`의 consent 화면을 띄운다.
3. Authorization Server는 역할을 모르므로, 손님이 허락하면 `products:write`가 든 token을 준다.
4. 새 token으로 다시 부르면 그제야 역할 검사에서 "모르는 tool"이 된다.

손님은 받아도 쓸 수 없는 scope를 허락하고도 아무것도 하지 못하고, token에는 쓸모없는 넓은 scope가 남는다.
그래서 보이는 tool인지를 scope보다 먼저 본다.

## 12.6 권한을 판단하는 곳

이 practice에서 역할 표는 MCP Server에 있다.
token은 누구인지(`sub`)만 알려 주고, 그 사람이 점원인지는 MCP Server가 자기 표에서 찾는다.
가게의 권한은 가게 서비스의 데이터이고, MCP Server는 그 서비스의 일부다.
학습용이라 표는 `McpTransportConfig`의 코드에 있고, 실제 서비스라면 사용자 저장소에 있다.

다른 방법은 Authorization Server가 역할을 보고 scope를 줄이는 것이다.

| 비교 | MCP Server의 역할 표(이 practice) | Authorization Server가 역할로 scope를 줄인다 |
|---|---|---|
| 손님이 `products:write`를 요청하면 | consent와 발급은 그대로 된다. MCP Server가 역할로 거절한다 | Authorization Server가 그 scope를 빼고 발급한다(10장의 일부 허락과 같은 결과) |
| 목록에서 숨길 tool | MCP Server가 역할 표로 바로 안다 | token에는 지금 scope만 있다. 언젠가 받을 수 있는지 알려면 역할을 claim으로 넣거나 Authorization Server에 따로 묻는다 |
| 역할이 바뀌면 | MCP Server는 다음 요청부터 바뀐 역할로 판단한다 | 이미 발급한 token은 만료될 때까지 옛 scope를 가진다 |

두 방법을 함께 쓰면 손님은 쓸 수 없는 scope를 받지도 못하고, MCP Server도 역할로 한 번 더 막는다(12.4).

**GitHub의 두 연결 방식**

[10장](10-scope-and-step-up.md#101-최소-권한의-필요성)에서 본 GitHub의 원격 MCP Server는 연결 방식에 따라 tool 목록을 다르게 다룬다.
classic PAT로 연결하면 scope가 처음부터 정해져 있어서, 쓸 수 없는 tool을 목록에서 숨긴다.
OAuth로 연결하면 tool이 아직 허락받지 않은 scope를 필요로 할 때 그 자리에서 허락을 받는다([GitHub changelog](https://github.blog/changelog/2026-01-28-github-mcp-server-new-projects-tools-oauth-scope-filtering-and-new-features/)).
이 장의 기준으로 보면, PAT는 scope를 더 받을 수 없으니 숨기고 OAuth는 더 받을 수 있으니 보여 주는 것이다.

**client 등록으로 정해지는 경우**

Authorization Server는 client 등록에 없는 scope를 `invalid_scope`로 거절하므로, 언젠가 받을 수 있는 scope는 client 등록으로도 정해진다([10장 1단계](10-scope-and-step-up.md#103-1단계-먼저-조회-scope만-받는다)).
그래서 등록 scope가 `products:read`뿐인 읽기 전용 client는 점원이 써도 `products:write`를 받을 수 없고, MCP Server는 token의 `client_id`를 보고 쓰기 tool을 숨길 수 있다.
이 practice의 두 client는 세 scope가 모두 등록되어 있어서, 이 경우는 코드로 다루지 않는다.

## 12.7 web agent: 사용자별 tool 목록 cache

**목록 하나를 같이 쓰면 한 사용자의 목록이 다른 사용자에게 간다**

11장까지의 agent는 Spring AI 자동 구성의 tool provider를 `ChatClient`의 기본 tool로 넣는다([1장 official 코드에서 보기](01-mcp-basics.md#112-official-코드에서-보기)).
이 provider는 처음 불릴 때 `tools/list`를 보내고, 목록이 바뀌었다는 알림이 올 때까지 그 목록을 모든 사용자에게 쓴다.
그래서 MCP Server가 사용자마다 다른 목록을 주면, 기동 뒤 처음 질문한 사람의 목록이 모두에게 간다.
`user2`가 먼저 물으면 점원의 모델도 `updateStock`을 몰라서 step-up을 할 수 없다.
`user`가 먼저 물으면 손님의 모델도 `updateStock`을 보고, 숨긴 tool의 이름과 설명이 손님의 대화에 들어간다.
그래서 이 agent는 `spring.ai.mcp.client.toolcallback.enabled: false`로 자동 provider를 끄고, `UserToolCatalog`가 목록을 access token별로 둔다.
MCP client는 여전히 하나이고, 목록을 받는 `tools/list`에는 질문한 사용자의 token이 붙는다(12.11).

**2026-07-28 Caching의 규칙**

2026-07-28은 `tools/list` 같은 목록 결과를 client가 cache하는 규칙을 정했고, 서버는 결과에 두 field를 넣는다.

| field | 뜻 | client가 할 일 |
|---|---|---|
| `ttlMs` | 결과를 새것으로 봐도 되는 시간(ms)이다. HTTP의 `Cache-Control: max-age`와 비슷하다 | 받은 뒤 이 시간 안에는 다시 받지 않는다. 지나면 다음에 필요할 때 다시 받는다 |
| `cacheScope` | `"public"`은 모든 사용자에게 같은 결과, `"private"`은 사용자마다 다른 결과다 | `"private"` 결과는 같은 authorization context에서만 다시 쓴다. access token이 다르면 cache도 따로 둔다 |

명세의 규칙 가운데 이 장과 관계있는 것은 셋이다.

- 사용자마다 거른 목록에는 `"private"`이 맞다.
- `ttlMs`가 없는 결과는 client가 자기 기준이나 목록 변경 알림으로 다룬다.
- tool 호출에 "없는 method"나 "잘못된 parameter" 같은 뜻밖의 오류가 오면, TTL 전이라도 다시 받아도 된다.

**이 practice가 따르는 것**

이 practice의 MCP Server는 2025-11-25라서 `ttlMs`와 `cacheScope`를 보내지 않는다.
`ttlMs`가 없으면 client는 자기 기준에 기댈 수 있으므로, agent는 TTL을 5분으로 스스로 정한다(2026-07-28의 예시 값 `ttlMs: 300000`과 같다).
그리고 두 규칙을 미리 따라, 사용자마다 거른 목록을 `"private"`으로 보아 다른 access token과 나누지 않고, "모르는 tool" 오류가 오면 TTL 전이라도 그 token의 목록을 버린다.

질문이 오면 `UserToolCatalog`는 그 사용자의 access token 값의 SHA-256 hex로 key를 만들고 목록을 찾는다.
목록이 없거나 5분이 지났으면 그 token으로 `tools/list`를 보내 받은 목록을 5분 동안 두고, 있으면 `tools/list` 없이 그 목록을 쓴다.
만료된 항목은 목록을 꺼낼 때 지우고, 만료 전에 미리 다시 받지는 않는다.
그래서 목록을 다시 받는 경우는 셋이다.

1. 5분이 지났다.
2. token이 바뀌었다. step-up이나 refresh를 하거나 다시 login하면 새 token을 받으므로 key가 바뀐다.
3. tool 호출이 "모르는 tool" 오류로 끝났다(12.8).

access token은 299초 동안 유효하고, agent는 만료 1분쯤 전부터 token을 refresh한다.
refresh로 token이 바뀌면 key도 바뀌므로, 한 key는 token을 받은 뒤 4분쯤까지만 쓰인다.
그래서 5분 안에 다시 물어도 cache를 쓰지 않고 목록을 새로 받는 일이 흔하다.
요청 순서는 [practice README의 사용자별 목록 cache](../mcp-tool-visibility/README.md#사용자별-목록-cache) 그림에 있다.

**key를 token으로 잡는 이유**

이 서버의 목록은 역할로만 달라지므로, 사용자(`sub`)를 key로 잡아도 결과는 같다.
그래도 token을 key로 잡는 것은, 명세가 access token이 다르면 cache를 따로 두라고 하기 때문이다.
scope로 목록을 거르는 서버라면 step-up 전후의 목록이 달라서, 사용자 key로는 낡은 목록을 쓰게 된다.
token key는 그런 서버에도 맞고, step-up으로 token이 바뀌면 저절로 목록을 다시 받는다.
key를 token 값 대신 SHA-256 hex로 두는 것은, cache를 로그에 찍거나 heap dump를 볼 때 token이 그대로 보이지 않게 하려는 것이다.

**이 practice의 한계**

- agent는 SSE stream을 시작하기 전에 요청 thread에서 목록을 꺼낸다. 그래서 목록을 새로 받다가 실패하면 화면에는 `오류: HTTP 500`만 나오고, 원인은 `logs/shop-agent.log`에 있다.
- 목록이 바뀌었다는 알림은 받지 않는다. stateless MCP Server는 GET stream에 `405`로 답하므로([11장 1단계](11-stateless-and-handle.md#113-1단계-session-없는-서버의-요청과-응답)), agent는 TTL이 지났을 때, token이 바뀌었을 때, "모르는 tool" 오류가 왔을 때만 다시 받는다.

## 12.8 web agent: 목록에 없는 tool을 모델이 부를 때

모델은 그 질문에 넣은 목록의 tool을 부른다.
그래도 이 사용자에게 지금 보이는 목록에 없는 tool이 불리는 경우가 둘 있다.

**낡은 목록의 tool: "모르는 tool" 오류를 모델에게 돌려준다**

agent는 목록을 한동안 그대로 쓰므로, 그사이 MCP Server의 목록이 바뀌면 서버가 더는 보여 주지 않는 tool을 들고 있게 된다.
`McpTransportConfig`의 역할 표에서 `user`를 빼고 MCP Server만 다시 띄우면, agent에 남은 점원의 목록에는 `updateStock`이 그대로 있다.
모델이 이 tool을 부르면 MCP Server는 12.4의 "모르는 tool" 오류로 답한다.

Spring AI 2.0.1의 `SyncMcpToolCallback`은 이 JSON-RPC 오류(`McpError`)를 감싸지 않고 그대로 던진다.
tool loop는 `ToolExecutionException`만 잡으므로, 그대로 두면 채팅 stream이 오류로 끝난다.
그래서 agent는 목록의 callback마다 `UnknownToolAwareToolCallback`으로 감싼다.
이 callback은 "모르는 tool" 오류를 받으면 그 token의 목록을 버리고, 오류를 `ToolExecutionException`으로 바꿔 던진다.
그러면 모델은 `Unknown tool: invalid_tool_name`을 tool 결과로 받고 turn은 이어진다.
그 turn은 받아 둔 목록을 계속 쓰고, 다음 질문에서 목록을 새로 받는다.
2025-11-25 server/tools는 client가 이런 protocol error를 모델에게 넘겨도 된다고 하고, 2026-07-28 Caching은 이때 TTL 전에 목록을 다시 받아도 된다고 한다.

**목록에 아예 없는 이름: turn을 되돌리고 안내한다**

모델은 받은 목록에 없는 이름으로도 tool 호출을 만들 수 있다.
손님의 목록에는 `updateStock`이 없지만, 손님이 재고를 바꿔 달라고 하면 모델이 이 이름을 지어낼 수 있다.
이때 Spring AI는 MCP 요청을 보내기 전에 `No ToolCallback found for tool name: updateStock`이라는 `IllegalStateException`으로 stream을 끝내고, MCP Server는 이 호출을 보지 못한다.
그대로 두면 화면에는 오류만 남고, 대화 기억에는 결과 없는 tool 호출이 남는다([11장 web agent의 대화 기억](11-stateless-and-handle.md#117-web-agent-tool-결과까지-기억하는-대화)).
그래서 `ChatEvents`는 이 오류를 원인 사슬에서 찾아, step-up 때처럼 그 turn을 대화 기억에서 되돌린다.
그리고 `tool-unavailable` event(`{"tool":"updateStock"}`)를 보내고, 화면은 `이 계정에서는 updateStock을(를) 쓸 수 없습니다.`를 보여 준다.
다음 질문은 그 turn이 시작되기 전의 기억에서 이어진다.

**browser로 본 것**

손님으로 `p1 재고를 10개로 바꿔 줘`를 보낸 browser 확인에서는, 모델이 보이는 tool인 `getStock`만 불렀다.
답은 `재고 수량 변경은 현재 제공된 도구로는 지원되지 않습니다. 상품 p1의 재고가 7개인 상태에서 추가 구매하시려면 장바구니에 담아 주문해 주세요.`였다.
consent 카드는 뜨지 않았고, 모델이 `updateStock`을 부르지 않았으므로 `tool-unavailable` 안내도 나오지 않았다.
모델이 목록에 없는 tool을 부를지는 모델에 달려 있어서, 두 경로는 매번 볼 수 있는 것이 아니다.
낡은 목록의 경로는 점원이 질문한 뒤 agent가 그 token의 목록을 아직 쓰는 동안(12.7), 위처럼 역할 표에서 `user`를 빼고 MCP Server만 다시 띄우면 볼 수 있다.
MCP Server만 다시 띄우는 명령은 12.13에 있다.

## 12.9 사용자 기기의 앱

`local-client`는 연결한 뒤 받은 tool 목록을 `tools:` 한 줄로 찍는다.
목록에 `updateStock`이 없으면 그 tool을 일부러 한 번 불러, 숨긴 tool의 호출이 어떻게 끝나는지 보여 준다.
아래는 browser 대신 curl이 login과 consent를 한 `docs/superpowers/captures/visibility-local-client-run.sh user2`의 출력이다.
discovery와 login(`[1]`\~`[4]`), 장바구니 줄, step-up의 주소와 `[3]`·`[4]` 줄은 뺐다.

```text
[5] MCP 호출
    initialize: protocolVersion=2025-11-25, server=visibility-shop-mcp-server
    tools: getStock, searchProducts, addItem, checkout, createBasket, getBasket
    updateStock(목록에 없음): JSON-RPC 오류 -32602 Unknown tool: invalid_tool_name (Tool not found: updateStock)
    getStock(p1): 상품 p1 (게이밍 노트북 15인치) 의 현재 재고는 7개입니다.
    403 insufficient_scope — 필요한 scope: orders:write
[6] step-up: products:read orders:write로 다시 authorization을 받는다
[7] 새 token으로 같은 요청을 새로 보낸다
    checkout: 주문을 접수했습니다. 주문 번호는 ord-1002입니다.
    tools: getStock, searchProducts, addItem, checkout, createBasket, getBasket
```

`user`로 돌리면 두 `tools:` 줄에 `updateStock`이 든 7개가 찍히고, `updateStock(목록에 없음)` 줄은 없다.

| 줄 | 볼 곳 |
|---|---|
| `updateStock(목록에 없음)` | HTTP `200` 안의 JSON-RPC 오류라서, MCP Java SDK는 `McpError`를 던진다. `403`이 아니므로 바로 뒤에 `[6]` step-up이 없다 |
| `403`부터 `[7]`까지 | step-up은 `checkout`의 `orders:write` 한 번뿐이다. 손님도 받을 수 있는 scope라서 `checkout`은 숨기지 않았다 |
| 마지막 `tools:` | step-up으로 token이 바뀌어 목록을 다시 받았다. 역할로만 거르므로 처음 목록과 같다 |

주문 번호가 `ord-1002`인 것은 같은 서버에서 `user`로 먼저 돌린 실행이 `ord-1001`을 받았기 때문이다.
목록은 요청의 authorization에 따라 달라도 되므로, `local-client`는 token이 바뀌면 옛 목록이 맞는다고 가정하지 않고 다시 받는다.

## 12.10 서버 코드에서 보기

클래스는 `practice/mcp-tool-visibility/shop-mcp-server/src/main/java/dev/starryeye/visibility/mcpserver/` 아래에 있다.

**`ToolVisibility`: 숨길 tool**

```java
public boolean hidden(String subject, String tool) {
    return this.registry.all().containsKey(tool)
            && !grantable(roleOf(subject)).contains(this.registry.scopeFor(tool));
}
```

`ToolVisibility`는 역할마다 받을 수 있는 scope(12.3의 표)를 `GRANTABLE`로 두고, `roleOf`는 표에 없거나 `null`인 사용자를 손님으로 본다.
`hidden`은 등록된 tool만 본다.
그 tool의 scope(10장의 `ToolScopeRegistry`가 `@RequiredScope`에서 모은 값)를 이 역할이 받을 수 없으면 숨긴다.
등록되지 않은 이름은 숨긴 tool이 아니고, SDK가 "모르는 tool"로 답한다.
`visible`은 SDK가 준 목록에서 `hidden`인 tool만 빼고 순서는 그대로 둔다.
역할 표는 `McpTransportConfig`가 `new ToolVisibility(registry, Map.of("user", ToolVisibility.Role.STAFF))`로 만든다.

**`ToolVisibilityTransport`: SDK handler를 감싼다**

MCP Java SDK와 Spring AI에는 사용자마다 `tools/list` 결과를 거르는 hook이 없다.
Spring AI의 stateless transport `WebMvcStatelessServerTransport`는 `final`이라 상속할 수도 없다.
그래서 같은 interface `McpStatelessServerTransport`를 구현한 transport로 원래 transport를 감싼다.
SDK server가 `setMcpHandler`로 넘기는 요청 handler를 다시 감싸 원래 transport에 넘기고, 감싼 handler는 다음 일을 한다.

```java
public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
        McpSchema.JSONRPCRequest request) {
    String subject = McpCaller.subject(context).orElse(null);
    if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
        String name = toolName(request.params());
        if (name != null && visibility.hidden(subject, name)) {
            /* 숨긴 tool 호출 로그 */
            return Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
                    new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS,
                            UNKNOWN_TOOL_MESSAGE, "Tool not found: " + name)));
        }
    }
    Mono<McpSchema.JSONRPCResponse> response = this.sdk.handleRequest(context, request);
    if (!McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
        return response;
    }
    return response.map(r -> {
        if (!(r.result() instanceof McpSchema.ListToolsResult list)) {
            return r;
        }
        List<McpSchema.Tool> visible = visibility.visible(subject, list.tools());
        /* tools/list 로그를 남기고, visible에 list의 nextCursor와 meta를 더한 새 결과로 답한다 */
    });
}
```

`subject`는 11장의 `contextExtractor`가 검증한 JWT에서 꺼내 둔 `sub`다.
token에 `sub`가 없으면 `null`이고, `ToolVisibility`는 이 요청을 손님으로 본다.
`UNKNOWN_TOOL_MESSAGE`는 SDK 2.0.1이 없는 tool에 쓰는 `Unknown tool: invalid_tool_name`이고, SDK를 올리면 다시 확인할 값이다.

숨긴 tool의 호출을 tool마다 감싸 막지 않는 데는 이유가 있다.
SDK는 이름으로 tool을 찾은 뒤에 인자를 검증하고, 찾지 못한 이름에만 "모르는 tool"로 답한다.
tool을 감싸 막으면 인자가 틀린 숨긴 tool 호출은 인자 오류를 받아, 없는 tool과 응답이 달라진다.
그래서 SDK가 tool을 찾기 전에 handler에서 답한다.
`toolName`도 SDK와 같은 JSON mapper(`McpJsonDefaults.getMapper()`)로 `params`를 읽는다.
더 너그러운 mapper라면, SDK가 다른 오류로 답할 요청에 숨긴 tool만 "모르는 tool"로 답해 차이가 생긴다.

`McpTransportConfig`는 감싼 transport를 `@Primary` bean으로 둔다.
Spring AI의 자동 구성에서 SDK server는 transport를 `McpStatelessServerTransport` interface 타입으로 받으므로, `@Primary`인 이 bean이 server에 간다.
HTTP 경로를 만드는 router는 구체 타입 `WebMvcStatelessServerTransport`로 받으므로, 요청은 원래 transport로 들어와 감싼 handler를 거친다.

**`ToolScopeFilter`: 보이는 tool인지 먼저**

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    /* token과 granted(가진 scope)를 읽고, 기본 scope가 없으면 403 scope="products:read" */
    HttpServletRequest forwarded = request;
    if (HttpMethod.POST.matches(request.getMethod())) {
        /* 본문을 읽어 message를 만든다(10장) */
        String tool = toolName(message);
        // 숨긴 tool은 scope를 늘려도 쓸 수 없으니 step-up을 시작하면 안 된다.
        // 그래서 scope를 보지 않고 transport로 넘겨, 정말 없는 tool과 같은 "모르는 tool" 응답을 받게 한다.
        boolean hidden = tool != null && this.visibility.hidden(token.getToken().getSubject(), tool);
        String missing = (tool != null && !hidden && !granted.contains(this.registry.scopeFor(tool)))
                ? this.registry.scopeFor(tool) : null;
        if (missing != null) {
            insufficientScope(request, response, token, tool, missing, granted);
            return;
        }
    }
    chain.doFilter(forwarded, response);
}
```

filter는 숨긴 tool에 직접 답하지 않고 transport로 넘긴다.
그래야 숨긴 tool의 요청이 정말 없는 tool의 요청과 같은 뒤 단계를 지난다.
`MCP-Protocol-Version` 검사, `Accept` 검사, JSON-RPC 읽기, `id` 되돌려 주기, Spring Security가 붙이는 header가 모두 같아진다.
filter가 이 일을 따로 흉내 내면, 잘못된 요청 같은 드문 경우에 응답이 달라질 수 있다.

## 12.11 client 코드에서 보기

agent의 클래스는 `practice/mcp-tool-visibility/shop-agent/src/main/java/dev/starryeye/visibility/agent/` 아래에, `local-client`의 클래스는 `practice/mcp-tool-visibility/local-client/src/main/java/dev/starryeye/visibility/localclient/`에 있다.

**agent: 설정과 `UserToolCatalog`**

`application.yml`의 `spring.ai.mcp.client.toolcallback.enabled: false`는 자동 구성의 tool provider를 끈다.
MCP client bean(`mcpSyncClients`)은 그대로 남는다.
`ChatClientConfig`는 기본 tool을 넣지 않고, `ToolCatalogConfig`는 이 MCP client로 `UserToolCatalog` bean을 만든다.
cache key의 access token은 MCP 요청에 token을 붙이는 customizer와 같은 `OAuth2AuthorizedClientManager.authorize(...)`로 얻는다.
token이 만료되었으면 이 호출이 refresh하므로, key의 token과 실제로 붙는 token이 같다.

```java
public List<ToolCallback> callbacks(Authentication user) {
    String key = key(this.accessToken.apply(user));
    Instant now = this.clock.instant();
    this.entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
    Entry cached = this.entries.get(key);
    if (cached != null) {
        /* cache에서 꺼낸다는 로그 */
        return cached.callbacks();
    }
    List<ToolCallback> callbacks = this.loader.load(() -> invalidate(key));
    this.entries.put(key, new Entry(callbacks, now.plus(TTL)));
    /* 새로 받았다는 로그 */
    return callbacks;
}
```

`loader`는 `client.listTools()`로 받은 tool마다 `SyncMcpToolCallback`을 만들고 `UnknownToolAwareToolCallback`으로 감싼다.
callback의 이름은 `prefixedToolName(tool.name())`으로 서버가 준 이름과 같게 둔다.
`loader`에 넘기는 `() -> invalidate(key)`는 목록을 받을 때의 key를 기억한다.
그래서 "모르는 tool" 오류가 tool loop의 다른 thread에서 와도, 그 목록을 받은 token의 항목을 정확히 버린다.

**agent: `UnknownToolAwareToolCallback`**

`call`은 감싼 `SyncMcpToolCallback`이 던진 `McpError` 가운데 "모르는 tool"만 잡는다.
잡으면 `onUnknownTool`로 목록을 버리고 `new ToolExecutionException(getToolDefinition(), ex)`를 던지며, 다른 `McpError`는 그대로 던진다.
`-32602`는 인자 오류에도 쓰이므로, `isUnknownTool`은 `code`와 함께 `message`가 `Unknown tool: invalid_tool_name`인지 본다.
`ToolExecutionException`은 10장의 `StepUpToolExecutionExceptionProcessor`를 지나 Spring AI의 기본 처리로 가고, 모델은 예외 메시지를 tool 결과로 받는다.

**agent: `ChatController`와 `ChatEvents`**

```java
@PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session,
        Authentication authentication) {
    /* conversationId와 turn을 시작하기 전의 기억 before(11장) */
    // 이 사용자의 tool 목록이다. 요청 thread에서 꺼내야 목록을 새로 받을 때 이 사용자의 token이 붙는다.
    List<ToolCallback> tools = this.toolCatalog.callbacks(authentication);
    Flux<String> content = this.chatClient.prompt()
            .user(message)
            .tools(tools.toArray())
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
            .stream()
            .content();
    return ChatEvents.of(content, StepUpState.of(session), () -> restore(conversationId, before));
}
```

`.tools(...)`로 넣은 callback이 이 질문에서 모델이 볼 수 있는 tool의 전부다.
`McpSyncClient.listTools()`는 부른 thread에서 요청을 보내므로, 요청 thread의 SecurityContext로 그 사용자의 token이 붙는다.

`ChatEvents.of`는 채팅 stream이 오류로 끝나면 step-up 오류(10장)를 먼저 찾고, 다음으로 목록에 없는 tool의 오류를 찾는다.
두 경우 모두 `ChatController`가 넘긴 `restore`로 끊긴 turn을 대화 기억에서 되돌린 뒤, 각각 `step-up`과 `tool-unavailable` event를 보낸다.
`unavailableTool`은 원인 사슬에서 메시지가 `No ToolCallback found for tool name: `로 시작하는 `IllegalStateException`을 찾고, 뒤의 tool 이름을 꺼낸다.
Spring AI 2.0.1에는 이 경우를 위한 예외 타입이 따로 없어서 메시지로 알아보므로, Spring AI를 올리면 다시 확인한다.

**local-client: `McpCalls.ToolList`**

`ToolList#printIfTokenChanged`는 목록을 받은 뒤의 token을 기억해 두고, 지금 token이 그와 다를 때만 `tools/list`를 다시 보내 `tools:` 줄을 찍는다.
`McpCalls#run`은 `initialize` 직후와 `checkout` 뒤에 이 메서드를 부른다.
처음 받은 목록에 `updateStock`이 없으면 `callHidden`이 그 tool을 부르고, SDK가 던진 `McpError`를 그 자리에서 잡아 `updateStock(목록에 없음)` 줄을 찍는다.

## 12.12 다루지 않는 것

- 2026-07-28의 `ttlMs`·`cacheScope` field와 목록 변경 알림: 서버가 준 값으로 TTL을 정하고, `subscriptions/listen`으로 받은 알림으로 cache를 무효로 하는 일은 SDK가 2026-07-28을 지원한 뒤의 practice에서 다룬다.
- tool별 scope를 client에게 알리는 표준: tool 정의에 필요한 scope를 적는 SEP-1488과 ChatGPT의 `securitySchemes`는 [10장 다루지 않는 것](10-scope-and-step-up.md#1011-다루지-않는-것)에서 본 대로 아직 표준이 아니다.
- 역할의 영속 저장과 변경: 역할 표는 `McpTransportConfig`의 코드에 있고, 실행 중에 역할을 바꾸는 화면도 없다.
- `resources`·`prompts` 목록: 이 practice는 tool 목록만 거른다. 사용자마다 다른 resource나 prompt 목록도 같은 기준으로 거르고 `"private"`으로 cache한다.

## 12.13 직접 해 보기

```bash
# 저장소 최상위 폴더에서
practice/mcp-tool-visibility/run.sh
```

**web agent**: 보통 browser 창으로 `http://localhost:8160`을 열고 `user2`/`password`로 login한 뒤, consent 화면에서 `products:read`를 체크한다.
점원 `user`는 시크릿 창에서 login한다.
보통 창들은 cookie를 함께 쓰고 agent 화면에는 logout이 없어서, 서버를 다시 띄우지 않고는 보통 창에서 다른 계정으로 login할 수 없기 때문이다.
아래 순서대로 보내고, `practice/mcp-tool-visibility/logs/`의 `shop-mcp-server.log`와 `shop-agent.log`를 본다.

| 계정과 질문 | 볼 것 |
|---|---|
| `user2`: `p1 재고를 10개로 바꿔 줘` | consent 카드 없이 재고를 바꿀 수 없다는 답이 온다. MCP Server 로그에 `tools/list — 사용자=user2, 역할=CUSTOMER, 보인 tool=6/7`이 찍힌다 |
| `user2`: 답을 받자마자 `노트북 재고 있어?` | agent 로그에 `tool 목록을 cache에서 꺼낸다 (사용자=user2, 6개)`가 찍히고, MCP Server에는 새 `tools/list`가 없다 |
| `user`: `p1 재고를 10개로 바꿔 줘` | `products:write`를 요청하는 consent 카드가 뜬다. MCP Server 로그에 `보인 tool=7/7`과 `scope 부족` 줄이 찍힌다 |
| `user`: "권한 허용" 뒤 `products:write`를 체크 | 질문이 다시 가고 재고가 바뀐다. agent 로그에 `tool 목록을 새로 받았다 (사용자=user, 7개)`가 한 번 더 찍힌다 |

해 볼 것과 기대 결과의 전체 목록은 [practice README의 직접 확인할 것](../mcp-tool-visibility/README.md#직접-확인할-것)에 있다.

**낡은 목록**: 12.8의 "모르는 tool" 경로는 위 표의 마지막 줄 직후, agent가 점원의 새 token으로 받은 목록을 아직 쓰는 동안 본다.
그 목록은 token을 받은 뒤 4분쯤까지만 쓰이므로, 다음을 바로 이어서 한다.
`shop-mcp-server`의 `McpTransportConfig`에서 역할 표 `Map.of("user", ToolVisibility.Role.STAFF)`를 `Map.of()`로 바꾸고, MCP Server만 다시 띄운다.
`run.sh`는 포트가 이미 쓰이고 있는 서버를 건너뛰므로, 나머지 두 서버는 그대로 두고 MCP Server만 새로 띄운다.

```bash
# 저장소 최상위 폴더에서
kill $(lsof -ti tcp:8161 -sTCP:LISTEN)
practice/mcp-tool-visibility/run.sh
```

`run.sh`가 `[건너뜀] shop-mcp-server`를 찍으면 옛 MCP Server가 아직 내려가는 중이므로, 몇 초 뒤 `run.sh`를 다시 실행한다.
그 뒤 점원이 재고를 다시 바꿔 달라고 해서 모델이 `updateStock`을 부르면, MCP Server 로그에 `숨긴 tool 호출 — 사용자=user, 역할=CUSTOMER, tool=updateStock`이 찍힌다.
agent 로그에는 `모르는 tool 오류로 이 token의 tool 목록을 버린다`가 찍히고, 답은 끊기지 않고 이어진다.
확인한 뒤에는 역할 표를 되돌리고, 같은 명령으로 MCP Server를 다시 띄운다.

**token이 필요한 요청**: 캡처 스크립트 `docs/superpowers/captures/mcp-visibility-walkthrough.sh`로 본다.
두 사용자의 `tools/list`, 숨긴 tool과 없는 tool의 응답 비교, 점원의 `403`, `products:write`를 받은 손님의 호출이 한 번에 기록된다.
consent 화면이 나오고 재고가 처음 값이어야 하므로, 서버를 다시 띄운 직후에 돌린다.

```bash
# 저장소 최상위 폴더에서. 출력의 JWT는 앞 20자만 남는다
practice/mcp-tool-visibility/stop.sh
practice/mcp-tool-visibility/run.sh
docs/superpowers/captures/mcp-visibility-walkthrough.sh > /tmp/visibility-walkthrough.txt
```

스크립트는 두 사용자의 consent를 남기므로, 그 뒤에 web agent를 처음부터 해 보려면 서버를 다시 띄운다.

**local-client**: `JAVA_HOME`을 Java 21로 맞춘 뒤 `practice/mcp-tool-visibility/local-client`에서 `./gradlew run`을 실행한다([practice README의 실행](../mcp-tool-visibility/README.md#실행)).
처음 열린 browser에서 `user`나 `user2`로 login하고, step-up의 consent 화면에서는 `products:read`와 `orders:write`를 모두 체크한다.
browser 대신 curl로 두 계정을 차례로 돌리려면 저장소 최상위 폴더에서 다음을 실행한다.

```bash
# ./gradlew run을 부르므로 JAVA_HOME이 Java 21을 가리켜야 한다
docs/superpowers/captures/visibility-local-client-run.sh user > /tmp/visibility-local-client-user.txt
docs/superpowers/captures/visibility-local-client-run.sh user2 > /tmp/visibility-local-client-user2.txt
```

## 12.14 정리

- 모델은 목록에 있는 tool만 부르므로, 지금 token의 scope로 목록을 거르면 step-up이 시작될 `403`도 사라진다.
- 그래서 "이 사용자가 언젠가 받을 수 있는가"로 숨길지를 정하고, "지금 token에 있는가"는 부를 때 `403`으로 묻는다. 권한은 MCP Server가 알고, scope는 token에 있다.
- 숨긴 tool의 호출에는 정말 없는 tool과 똑같은 JSON-RPC 오류로 답해, 그런 tool이 있다는 사실도 알리지 않는다. 검사는 보이는 tool인지를 tool의 scope보다 먼저 본다.
- 목록이 사용자마다 다르면 client는 access token별로 cache한다. 2025-11-25 서버는 `ttlMs`를 주지 않으므로 client가 TTL을 정하고, token이 바뀌거나 "모르는 tool" 오류가 오면 다시 받는다.
- web agent는 낡은 목록의 "모르는 tool" 오류를 모델에게 돌려주고, 목록에 없는 이름의 호출은 turn을 되돌려 안내한다.

## 12.15 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| `tools/list`에는 요청한 client가 지금 쓸 수 있는 tool로 답한다. 목록은 연결에 따라, 또는 다른 요청의 부수 효과로 달라지면 안 되지만, 요청의 authorization(예: 허락된 scope)에 따라서는 달라도 된다 | [MCP 2026-07-28 Tools — Capabilities](https://modelcontextprotocol.io/specification/2026-07-28/server/tools#capabilities) | MUST, MUST NOT, MAY |
| 서버는 tool 집합이 같으면 늘 같은 순서로 돌려준다. 그래야 client가 목록을 cache하고, prompt cache도 잘 맞는다 | [MCP 2026-07-28 Tools — Capabilities](https://modelcontextprotocol.io/specification/2026-07-28/server/tools#capabilities) | SHOULD |
| 서버는 `tools/list` 같은 결과에 `ttlMs`와 `cacheScope`를 넣는다. `ttlMs`는 0 이상이다 | [MCP 2026-07-28 Caching — Cacheable Results](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#cacheable-results), [Time-to-Live (TTL) Field](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#time-to-live-ttl-field) | MUST |
| `"private"` 결과는 같은 authorization context에서 다시 쓸 수 있지만, 다른 authorization context와 cache를 나누면 안 된다(access token이 다르면 cache도 다르다). 사용자마다 거른 목록에는 `"private"`이 맞다 | [MCP 2026-07-28 Caching — Cache Scope Field](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#cache-scope-field) | MAY, MUST NOT |
| `ttlMs`가 없으면 client는 0(바로 낡음)으로 보고 자기 기준이나 알림에 기댄다. 낡은 결과는 다음에 필요할 때 다시 받는다 | [MCP 2026-07-28 Caching — Time-to-Live (TTL) Field](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#time-to-live-ttl-field), [Freshness Calculation](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#freshness-calculation) | SHOULD |
| client는 TTL을 polling 주기로 쓰지 않는다. tool 호출에 없는 method나 잘못된 parameter 같은 뜻밖의 오류가 오면 TTL 전이라도 다시 받을 수 있다 | [MCP 2026-07-28 Caching — Freshness Calculation](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#freshness-calculation) | SHOULD NOT, MAY |
| 서버는 tool마다 알맞은 접근 제어를 하고, 무단 접근을 막는 데 `cacheScope`에만 기대지 않는다 | [MCP 2026-07-28 Caching — Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/caching#security-considerations) | MUST, MUST NOT |
| 없는 tool은 tool 결과가 아니라 JSON-RPC 오류(protocol error)로 알린다. 예는 `-32602`와 `Unknown tool: invalid_tool_name`이다. client는 protocol error를 모델에게 넘길 수 있다 | [MCP 2025-11-25 Tools — Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling) | —(서버), MAY(client) |
| scope가 모자란 요청에 서버는 `403`, `error="insufficient_scope"`, 필요한 `scope`, `resource_metadata`로 답한다. 사용자를 대신하는 client는 step-up을 한다 | [MCP 2025-11-25 Authorization — Scope Challenge Handling](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-challenge-handling) | SHOULD |
| 최소 scope로 시작해 더 넓은 scope가 필요한 작업을 처음 할 때 늘린다. token에 적힌 scope만 믿고 서버 쪽 권한 판단을 하지 않는 것은 흔한 실수다 | [MCP Security Best Practices — Scope Minimization](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#scope-minimization) | — |

[← 11장](11-stateless-and-handle.md) · [목차](README.md) · [13장 →](13-cimd.md)
