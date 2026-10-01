# 11. stateless와 handle — session 없이 상태를 다룬다

## 11.1 stateless의 필요성

[1장](01-mcp-basics.md)에서 본 session은 서버가 `initialize` 응답의 `Mcp-Session-Id`로 주고, client가 이후 요청마다 돌려보내는 값이다.
2025-11-25에서 session ID를 줄지는 서버가 정한다.
session ID를 주는 서버는 이 값으로 그 session에 둔 상태를 찾는다.
장바구니처럼 tool 호출 사이에 남아야 하는 상태도 session에 두기 쉽다.

**공유 MCP client에서는 session의 상태가 사용자끼리 섞인다**

official과 authz의 agent는 MCP client 하나를 모든 사용자가 같이 써서, 어느 사용자의 요청이든 같은 session으로 간다([6장 session과 사용자](06-mcp-call-and-validation.md#67-session과-사용자)).
그래서 장바구니를 session에 두면 `user`가 담은 상품이 `user2`에게도 보인다.
앱을 끌 때 보내는 `DELETE`에는 붙일 사용자 token도 없다([준수표](reference-compliance.md) 36번).

**session이 있으면 서버를 여러 대로 늘리기 어렵다**

session의 상태는 그 session을 연 서버의 메모리에 있어서, 같은 session의 다음 요청도 그 서버로 가야 한다.
그래서 요청을 고르게 나눠 주는 보통의 load balancer를 그대로 쓰기 어렵고, 그 서버가 내려가면 상태도 함께 사라진다.

**2026-07-28에는 session이 없다**

MCP 2026-07-28에서는 protocol 수준의 session과 `Mcp-Session-Id`가 없어졌다([9장](09-versions.md)).
호출 사이의 상태가 필요한 서버는 그 상태를 가리키는 이름(handle)을 만들어 tool 결과로 주고, 다음 호출의 tool 인자로 돌려받는다.
서버는 요청마다 token으로 사용자를 알아내고, 받은 handle이 그 사용자의 것인지 확인한다.

이 장의 practice `mcp-stateless-handle`은 이 방식을 2025-11-25 형식 위에서 보여 준다.
handle을 다음 호출로 넘기는 것은 웹 agent에서는 모델이고, 사용자 기기의 앱에서는 코드다.
MCP Server에는 장바구니 tool 네 개가 더 있다.

| tool | 하는 일 | scope |
|---|---|---|
| `createBasket()` | 새 장바구니를 만들고 handle(`basketId`)을 돌려준다 | `products:read` |
| `addItem(basketId, productId, quantity)` | 상품을 담는다. 같은 상품을 다시 담으면 수량이 더해지고, 한 상품은 99개까지 담을 수 있다 | `products:read` |
| `getBasket(basketId)` | 담은 상품, 합계, 만료 시각을 보여 준다 | `products:read` |
| `checkout(basketId)` | 재고를 줄이고 주문 번호를 돌려준다. 장바구니는 닫힌다 | `orders:write` |

`checkout`만 새 scope `orders:write`를 요구하므로, 결제 직전에 [10장](10-scope-and-step-up.md)의 step-up이 일어난다.

## 11.2 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant M as MCP Server
    participant A as Authorization Server
    Note over C,M: 모든 요청에 token만 있고 session header는 없다
    C->>M: initialize + token (products:read)
    M-->>C: 200 (Mcp-Session-Id 없음)
    C->>M: tools/call createBasket
    Note over M: key user:bsk_…로 저장
    M-->>C: basketId bsk_…
    C->>M: tools/call addItem(bsk_…, p4, 1)
    M-->>C: 장바구니 내용
    C->>M: tools/call checkout(bsk_…)
    M-->>C: 403 insufficient_scope, scope=orders:write
    C->>A: step-up (orders:write를 더한 scope)
    A-->>C: 새 access token
    C->>M: tools/call checkout(bsk_…) + 새 token
    M-->>C: orderId ord-1001, 장바구니를 닫는다
```

[다이어그램 그림으로 보기](diagrams/11-stateless-and-handle-1.png)

| 단계 | 볼 값 |
|---|---|
| 1단계 (1)(2): session 없이 token만으로 부른다 | `initialize` 응답에 `Mcp-Session-Id`가 없다. GET은 `405`, DELETE는 `404`다 |
| 2단계 (3)\~(6): handle을 만들고 넘긴다 | `createBasket` 결과의 `basketId`와 다음 호출의 인자. 서버는 장바구니를 key `user:bsk_…`로 둔다 |
| 3단계 (7)\~(12): handle을 사용자에게 묶고 주문한다 | step-up 뒤의 주문 번호. 다른 사용자의 호출과 두 번째 주문은 그림에 없고 11.5에서 본다 |

(9)(10)은 10장의 step-up을 줄여 그렸다.

아래 예시는 `practice/mcp-stateless-handle`을 실제로 띄워 받은 값이다.
Authorization Server는 `http://localhost:9040`, MCP Server는 `http://localhost:8151/mcp`, agent는 `http://localhost:8150`이다.
요청은 curl이 agent의 client `stateless-shop-agent`로 받은 token을 붙여 보냈고, 처음 token의 scope는 `products:read openid`다.

## 11.3 1단계: session 없는 서버의 요청과 응답

session을 주지 않는 서버는 요청이 어느 연결에서 왔는지 기억하지 않고, 요청 하나하나를 그 요청에 붙은 token만으로 처리한다.
MCP Server에서는 `application.yml`의 `spring.ai.mcp.server.protocol: STATELESS`가 이 방식을 고른다(11.9).

`products:read` token을 붙여 `initialize`를 보내면 다음 응답이 온다(캐시·보안용 header는 뺐다).

```http
HTTP/1.1 200
Content-Type: application/json
Content-Length: 282

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","serverInfo":{"name":"stateless-shop-mcp-server","version":"0.0.1"},"...":"그 밖의 field는 생략"}}
```

10장의 authz에서는 이 응답에 `Mcp-Session-Id` header가 있었다.
여기에는 없으므로, client는 이후 요청에 session header를 넣지 않는다.
`tools/call`의 응답도 `application/json` 본문 하나다.

**GET과 DELETE**

session이 있는 서버에서 GET `/mcp`는 서버가 client에게 먼저 메시지를 보내는 SSE stream을 열고, DELETE `/mcp`는 session을 끝낸다([1장](01-mcp-basics.md)).
같은 token으로 GET(`Accept: text/event-stream`)과 DELETE를 보내면 다음 상태 코드가 온다.

```text
HTTP/1.1 405
HTTP/1.1 404
```

GET의 `405`는 "이 endpoint는 SSE stream을 주지 않는다"는 뜻이다.
stateless 서버는 연결을 기억하지 않으므로, 먼저 보낼 메시지를 어느 client에게 보내야 할지 알 방법이 없다.
MCP Java SDK의 client는 `initialize` 응답을 받은 뒤 GET으로 이 stream을 열어 보고, `405`가 오면 요청과 응답만으로 돈다.

MCP Java SDK의 client는 session ID를 받지 않았으면 닫을 때 DELETE를 보내지 않는다.
그래서 공유 client가 앱을 끌 때 token 없는 DELETE를 보내는 일(준수표 36번)도 생기지 않는다.
명세는 session 종료를 허용하지 않는 서버가 `405`로 답할 수 있다고 하지만, Spring AI의 stateless transport는 DELETE 경로를 두지 않으므로 `404`다.

**요청 형식은 2025-11-25 그대로다**

session을 주지 않는 서버도 2025-11-25를 따르므로, client는 여전히 `initialize`를 먼저 보내고 요청마다 `MCP-Protocol-Version` header를 넣는다.
2026-07-28은 `initialize`도 없애고 요청마다 `_meta`에 버전을 넣게 하지만, 이 practice의 SDK는 그 형식을 모른다(11.11).

## 11.4 2단계: handle을 만들고 넘긴다

session이 없으면 서버는 "이 client의 장바구니"를 찾을 곳이 없다.
그래서 장바구니를 만들 때 그 이름(handle)을 client에게 준다.
상태는 서버가 가지고, client는 이름만 들고 다닌다.

```http
POST /mcp HTTP/1.1
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-11-25
Authorization: Bearer eyJraWQiOiIwYTIwZGRi...

{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"createBasket","arguments":{}}}
```

요청에 `Mcp-Session-Id`는 없고, 누가 부르는지는 `Authorization`의 token이 알려 준다.

```json
{"jsonrpc": "2.0", "id": 7, "result": {
  "content": [{"type": "text", "text": "장바구니 bsk_hXq50IBUX_9-Wyt_6m15yA를 만들었습니다. 2026-09-30T20:51:51.928045Z에 만료됩니다."}],
  "isError": false,
  "structuredContent": {"basketId": "bsk_hXq50IBUX_9-Wyt_6m15yA", "expiresAt": "2026-09-30T20:51:51.928045Z"}}}
```

handle은 결과의 두 곳에 있다.
만료 시각은 UTC이고, 장바구니를 만든 때부터 30분 뒤다.

| field | 담긴 것 | 읽는 쪽 |
|---|---|---|
| `content`의 `text` | handle과 만료 시각을 적은 문장 | 모델 |
| `structuredContent` | 같은 값을 담은 JSON object | 코드. `local-client`는 여기서 `basketId`를 꺼낸다(11.8) |

웹 agent에서 Spring AI는 tool 결과의 `content`를 JSON 문자열로 바꿔 모델에게 주고, `structuredContent`는 넘기지 않는다.
그래서 handle이 `text`에도 있어야 모델이 본다.
명세는 옛 client를 위해 같은 내용을 JSON 문자열로 `text`에도 넣기를 권하지만, 이 practice의 `text`는 같은 값을 담은 문장이다.
SEP-2567의 예시도 `text`에 `Created basket bsk_a1b2c3` 같은 문장을 쓴다.

**handle을 다음 호출의 인자로 넘긴다**

모델이나 코드는 받은 handle을 다음 tool 호출의 인자에 그대로 넣는다.

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"addItem","arguments":{"basketId":"bsk_hXq50IBUX_9-Wyt_6m15yA","productId":"p4","quantity":1}}}
```

응답의 `text`에는 담은 상품 `- [p4] 인체공학 마우스 × 1 = 59,000원`과 합계가 적혀 있고, 같은 handle로 부른 `getBasket`도 같은 문장을 준다.
어느 장바구니인지는 session이 아니라 인자의 `basketId`가 정한다.

**handle은 protocol의 기능이 아니다**

SEP-2567은 handle을 protocol이 아니라 tool을 설계하는 방법으로 설명하고, session이 빠진 자리를 채우는 방법으로 권한다.
이 practice의 handle은 `bsk_` 뒤에 무작위 22자를 붙인 불투명한 값이다.
`cart_user42_2026-03-11`처럼 안의 구조가 보이는 값은 client가 쪼개 읽거나, 모델이 비슷한 값을 지어내기 쉽다.

**수명은 tool 설명에 적는다**

handle은 연결이 끊겨도 남으므로, "연결이 닫힐 때까지"라는 수명은 더 이상 맞지 않는다.
모델은 tool 설명을 보고 장바구니를 만들지 정하고, 서버 문서에만 적은 규칙은 보지 못한다.
그래서 `createBasket`의 설명에 `장바구니는 만든 뒤 30분이 지나면 만료되고, 한 사용자는 열린 장바구니를 5개까지 가진다.`를 적는다.
설명의 마지막 문장 `이미 쓰던 장바구니가 있으면 새로 만들지 말고 그 ID를 이어 쓴다.`는 모델이 turn마다 장바구니를 새로 만들지 않게 한다.

## 11.5 3단계: handle을 사용자에게 묶는다

handle은 tool 결과에 담겨 대화 기록에 남는다.
대화 기록은 복사, 화면 공유, 다른 agent에게 넘기는 prompt를 거쳐 다른 사람에게 갈 수 있다.
그래서 MCP Server는 handle을 가졌다는 것만으로 장바구니를 쓰게 하지 않고, 요청한 사용자의 장바구니인지 확인한다.

**`<sub>:<handle>` key**

`BasketStore`는 장바구니를 `<sub>:<handle>` key로 저장하고 찾는다.
tool 메서드는 transport의 `contextExtractor`가 검증한 JWT에서 꺼내 둔 `sub`를 `McpCaller.from`으로 읽는다(11.9).
client가 보낸 값이 아니므로, 다른 사용자의 `sub`를 흉내 낼 방법이 없다.

**다른 사용자가 handle을 쓰면**

이번에는 같은 client로 login한 `user2`의 token을 붙여 `user`의 handle로 `getBasket`을 부른다.

```json
{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"장바구니 bsk_hXq50IBUX_9-Wyt_6m15yA를 찾을 수 없습니다. createBasket으로 새 장바구니를 만드세요."}],"isError":true}}
```

서버는 `user2:bsk_hXq50IBUX_9-Wyt_6m15yA`로 찾고, 그런 key는 없다.
token은 유효하고 scope도 충분하므로 `401`이나 `403`이 아니다.
요청은 tool까지 가서 실행되었고, 거절은 HTTP `200` 안의 `isError: true`인 tool 결과로 온다.
명세는 이런 오류를 JSON-RPC 오류가 아니라 tool 결과로 알리게 한다.
모델은 이 문장을 읽고 새 장바구니를 만들어 이어 갈 수 있다.
웹 agent에서는 Spring AI의 `SyncMcpToolCallback`이 이 결과를 예외로 바꾸고, 모델은 이 문장이 든 예외 메시지 `Error calling tool: [TextContent[…]]`를 tool 결과로 받는다.

**handle은 추측할 수 없어야 한다**

다른 사용자가 쓰는 것은 `<sub>:<handle>` key가 막지만, handle 자체도 추측할 수 없게 만든다.
`SecureRandom`의 128bit 무작위 값을 base64url 22자로 적고, 앞에 `bsk_`를 붙인다.
순서대로 매긴 번호라면 남의 handle을 짐작할 수 있고, 사용자 확인에 빈틈이 하나만 생겨도 곧바로 남의 장바구니를 쓰게 된다.
SEP-2567은 인증이 없는 서버라면 handle이 곧 bearer token이 되므로 128bit 이상의 무작위 값을 쓰라고 권한다.

**장바구니를 쓸 수 없는 경우**

오류 문장은 모델이 다음에 할 일을 정할 수 있게 쓴다.
SEP-2567도 만료된 handle에 "invalid argument" 대신 만료되었다고 알려, 모델이 `create_*` tool을 다시 불러 이어 가게 하라고 권한다.

| 경우 | tool 결과의 문장 |
|---|---|
| 모르는 handle, 다른 사용자의 handle | `장바구니 bsk_…를 찾을 수 없습니다. createBasket으로 새 장바구니를 만드세요.` |
| 본인 handle, 만든 뒤 30분이 지남 | `장바구니 bsk_…는 만료되었습니다(만든 뒤 30분). createBasket으로 새 장바구니를 만드세요.` |
| 본인 handle, 이미 주문함 | `장바구니 bsk_…는 이미 주문했습니다(주문 번호 ord-…).` |
| 열린 장바구니가 이미 5개인 사용자의 `createBasket` | `열린 장바구니는 5개까지 만들 수 있습니다. 쓰던 장바구니를 이어 쓰세요.` |

만료되거나 주문한 장바구니는 30분 더 남겨 두었다가 지운다.
그 30분 동안은 만료와 주문 완료를 알려 주고, 지운 뒤에는 찾을 수 없다고 답한다.
빈 장바구니의 `checkout`과 재고 부족도 같은 방식의 tool 결과로 알린다([practice README](../mcp-stateless-handle/README.md#handle과-소유권)).

**다른 사용자는 handle이 있는지도 알 수 없다**

오류를 나누면 남의 handle이 있는지 오류 문장으로 알아낼 수 있을 것처럼 보인다.
그러나 다른 사용자의 요청은 그 사용자의 `sub`로 key를 만들어 찾으므로, 남의 handle은 언제나 찾을 수 없다는 결과다.
만료와 주문 완료는 key를 찾은 본인에게만 가므로, 다른 사용자는 그 handle이 있는지 알 수 없다.

**주문은 한 번만 된다**

같은 장바구니를 두 번 주문하면 재고가 두 번 줄고 주문도 둘이 된다.
step-up 뒤 새 token으로 보낸 `checkout`은 `주문 ord-1001를 접수했습니다.`와 `"structuredContent":{"orderId":"ord-1001"}`를 돌려준다.
같은 handle로 `checkout`을 한 번 더 보내면 다음 결과가 온다.

```json
{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"장바구니 bsk_hXq50IBUX_9-Wyt_6m15yA는 이미 주문했습니다(주문 번호 ord-1001)."}],"isError":true}}
```

`BasketStore#checkout`은 장바구니 찾기, 재고 줄이기, 닫기를 `synchronized` 메서드 하나 안에서 한다(11.9).
그래서 같은 handle의 `checkout` 두 개가 동시에 와도 하나씩 돌고, 두 번째 호출은 첫 호출이 닫은 장바구니를 보고 "이미 주문"으로 끝난다.
step-up 전의 `checkout`은 filter에서 `403`으로 끝나 tool까지 가지 않으므로, consent 뒤 다시 보낸 `checkout`이 첫 주문이다.

## 11.6 signed handle과 session을 쓰지 않는 이유

이 practice의 handle은 이름일 뿐이고, 장바구니의 내용은 서버 메모리에 있다.
서버 저장소 없이 다루려면 내용과 사용자를 handle 안에 넣고 서버가 signature를 붙이는 signed handle을 쓸 수 있다.
client는 호출마다 이 handle을 보내고, 서버는 signature를 확인해 내용을 믿는다.
2025-11-25라면 장바구니를 session에 두는 방법도 있다.

| 비교 | 불투명 handle과 서버 저장소(이 practice) | signed handle | session |
|---|---|---|---|
| 서버 저장소 | 필요하다 | 필요 없어 보인다 | session마다 필요하다 |
| 두 번 주문 막기 | 저장소의 장바구니를 닫는다 | handle은 그대로 남는다. 주문한 handle을 서버에 적어 두지 않으면 막지 못한다 | session의 상태를 닫는다 |
| 만료 전에 쓰지 못하게 하기 | 저장소에서 지우면 된다 | 서버 기록 없이는 할 수 없다 | session을 끝내면 된다 |
| 크기 | `bsk_`와 22자 | 담은 상품이 늘수록 커지고, 모델의 context를 차지한다 | client는 session ID만 보낸다 |
| 모델에게 보이는 것 | 뜻 없는 이름 | 암호화하지 않으면 담은 내용이 그대로 보인다 | 모델은 상태를 보지 못한다 |
| 사용자 확인 | key `<sub>:<handle>` | handle 안의 사용자와 token의 `sub`를 비교한다 | session을 사용자에 묶는다. 공유 client에서는 사용자끼리 섞인다 |
| 2026-07-28 | 쓸 수 있다 | 쓸 수 있다 | session이 없다 |
| 서버 여러 대 | Redis 같은 공유 저장소가 필요하다 | 모든 서버가 signature key를 나눠 가진다 | 같은 서버로 보내거나 session 저장소를 나눠 쓴다 |

signed handle은 서버 저장소 없이도 될 것처럼 보이지만, 주문처럼 한 번만 해야 하는 일은 결국 서버 기록이 필요하다.
그래서 이 practice는 불투명 handle과 서버 저장소를 쓴다.

## 11.7 웹 agent: tool 결과까지 기억하는 대화

웹 agent에서 handle을 다음 tool 호출에 넘기는 것은 모델이다.
turn은 사용자 질문 하나와 그 답까지다.
사용자가 `휴대용 SSD도 두 개 담아 줘`라고 하면, 모델은 앞 turn의 `createBasket` 결과에서 `basketId`를 찾아 `addItem`에 넣어야 한다.
그러려면 다음 turn의 prompt에 앞 turn의 tool 결과가 들어 있어야 한다.

**기본 배치에서는 tool 결과가 기억에 남지 않는다**

Spring AI에서 tool loop는 `ToolCallingAdvisor`가 맡는다.
모델이 tool 호출로 답하면 tool을 실행하고, 결과를 붙여 모델을 다시 부르는 일을 답이 나올 때까지 되풀이한다.
advisor는 order가 작을수록 바깥에 있고, 대화 기억 advisor `MessageChatMemoryAdvisor`는 기본 order에서 `ToolCallingAdvisor`보다 바깥에 있다.
그래서 대화 기억은 tool loop가 시작하기 전의 질문과 loop가 끝난 뒤의 답만 본다.
tool 호출과 결과는 loop 안에서만 쓰이고, turn이 끝나면 사라진다.
다음 turn의 모델은 답에 handle이 적혀 있지 않으면 앞의 장바구니를 모른다.

**두 설정을 함께 바꾼다**

agent는 두 설정으로 tool 호출과 결과까지 기억에 남긴다.

1. `ChatMemoryConfig`는 `ToolCallingAdvisor`의 내부 history를 끈다(`disableInternalConversationHistory()`).
2. `ChatClientConfig`는 `MessageChatMemoryAdvisor`를 `ToolCallingAdvisor`보다 큰 order(`MEMORY_ADVISOR_ORDER`)로 넣는다. 그래서 대화 기억 advisor가 tool loop 안에 들어간다.

`ChatClientConfig`의 system prompt도 앞선 tool 결과의 `basketId`를 이어 쓰고, 찾을 수 없음·만료·이미 주문이라는 결과가 오면 `createBasket`으로 새로 만들라고 적는다.

두 번째 turn `휴대용 SSD도 두 개 담아 줘`는 다음 순서로 돈다.

```mermaid
sequenceDiagram
    autonumber
    participant G as ChatController
    participant T as ToolCallingAdvisor
    participant H as 대화 기억 advisor
    participant L as LLM
    participant M as MCP Server
    G->>T: 질문 (conversation ID = sub)
    T->>H: System + 질문
    Note over H: 질문을 저장하고, 기억(앞 turn의 basketId 포함)을 붙인다
    H->>L: System + 기억 + 질문
    L-->>H: tool 호출 addItem(bsk_…, p9, 2)
    Note over H: tool 호출을 저장한다
    H-->>T: tool 호출
    T->>M: tools/call addItem
    M-->>T: tool 결과
    T->>H: System + tool 결과
    Note over H: tool 결과를 저장하고, 기억(질문, tool 호출)을 붙인다
    H->>L: System + 기억 + tool 결과
    L-->>H: 답
    Note over H: 답을 저장한다
    H-->>T: 답
    T-->>G: 답 (stream)
```

[다이어그램 그림으로 보기](diagrams/11-stateless-and-handle-2.png)

loop의 단계마다 대화 기억 advisor를 지나므로, 질문(2), tool 호출(4), tool 결과(8), 답(10)이 차례로 저장된다.
다음 turn의 모델은 (3)처럼 기억에서 이 tool 결과와 그 안의 `basketId`를 받는다.

두 설정이 함께 있어야 하는 이유는 (8)에 있다.
내부 history를 끈 `ToolCallingAdvisor`는 다음 단계에 system prompt와 마지막 메시지(tool 결과)만 넘긴다.
대화 기억 advisor가 loop 밖에 있으면, 그 단계의 모델은 질문도 앞 turn도 없이 tool 결과만 받는다.
대화 기억 advisor가 loop 안에 있어야 (9)에서 질문과 앞 turn을 다시 붙여 준다.
반대로 대화 기억 advisor만 안쪽에 두고 내부 history를 켜 두면, 한 turn의 history를 `ToolCallingAdvisor`와 대화 기억이 따로 가진다.
두 설정을 함께 두면 history는 대화 기억 한 곳에만 있고, 모델이 받는 prompt도 늘 대화 기억에서 만들어진다.

**대화 기억은 사용자마다 따로 둔다**

`ChatController`는 login한 사용자의 이름(`sub`)을 conversation ID로 넘긴다.
그래서 `user`와 `user2`의 대화와 그 안의 handle이 섞이지 않는다.
conversation ID를 넘기지 않으면 `MessageChatMemoryAdvisor`는 모두가 쓰는 기본 대화로 가지 않고 오류를 낸다.

**step-up으로 끊긴 turn을 되돌린다**

`결제해 줘`에 모델이 `checkout`을 부르면 `403`이 오고, turn은 답 없이 consent 카드로 끝난다([10장](10-scope-and-step-up.md)).
이때 기억에는 그 turn의 질문과 결과 없는 `checkout` 호출이 들어 있다.
consent 뒤 browser가 같은 질문을 다시 보내면, 모델은 같은 질문 두 개와 결과 없는 tool 호출이 섞인 기억을 받게 된다.
그래서 `ChatController`는 turn을 시작하기 전의 기억을 복사해 둔다(11.10).
turn이 step-up으로 끝나면, `ChatEvents`가 consent 카드나 거절 안내 event를 만들기 직전에 그 복사본으로 기억을 되돌린다.
다시 보낸 질문은 끊기기 전의 기억에서 새 turn으로 시작하고, `checkout`을 불러 주문 번호 `ord-1001`로 두 상품을 주문했다고 답한다.

되돌리기는 turn 전체를 되돌리지만, 서버에서 이미 끝난 일은 되돌리지 않는다.
`p1 담고 결제해 줘`처럼 한 turn에서 `addItem`을 부른 뒤 `checkout`이 끊기면, 담은 상품은 서버에 남는다.
다시 보낸 질문이 `addItem`을 또 부르면 수량이 더해진다.
그래서 결제는 `결제해 줘`처럼 따로 한 turn으로 부탁한다.

**새 대화**

화면의 "새 대화" 버튼은 `POST /api/chat/reset`으로 그 사용자의 대화 기억을 지운다.
이어서 `장바구니 보여 줘`를 보내면 모델은 전에 받은 handle을 몰라 `createBasket`으로 새 장바구니를 만들고, 비어 있다고 답한다.
turn이 도는 중에 기억을 지우면 그 turn이 지운 뒤에도 기억에 계속 쓰므로, 화면은 turn이 끝날 때까지 이 버튼을 막아 둔다.

**이 practice의 한계**

SEP-2567은 대화를 줄일 때 handle이 든 tool 결과를 지키는 일을 client가 맡는다고 본다.
이 practice의 대화 기억에는 다음 한계가 있다.

- 기억은 최근 메시지 20개다. 넘치면 `MessageWindowChatMemory`는 오래된 메시지를 다음 사용자 질문 앞까지 한꺼번에 지운다. 그래서 한 turn에서 tool을 10번쯤 넘게 부르면 그 turn이 통째로 기억에서 빠질 수 있다.
- 마지막 답은 stream이 끝나야 저장된다. browser가 도중에 끊으면 기억에는 tool 호출과 결과만 남고 답은 없다.
- step-up이 아닌 오류나 tool 실행 중의 연결 끊김으로 turn이 끝나면 되돌리지 않는다. 그래서 결과 없는 tool 호출이 "새 대화"까지 기억에 남을 수 있다.
- 모델은 Ollama의 `qwen3:8b`이고, 결과 없는 tool 호출이 섞인 history도 받는다. Claude API는 tool 호출 바로 뒤에 그 결과가 오지 않는 history를 받지 않으므로([Claude API 문서](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)), provider를 바꾸면 이 경우를 따로 다뤄야 한다.
- 같은 사용자가 browser tab 두 개에서 채팅하면, 두 tab이 대화 하나를 lock 없이 같이 쓴다.

ChatGPT도 tool 결과의 `content`와 `structuredContent`를 대화 기록에 남기고([OpenAI 문서](https://developers.openai.com/plugins/reference)), claude.ai도 tool 결과를 대화 기록에 저장한다([claude.ai 문서](https://claude.com/docs/connectors/building/mcp-apps/instance-supersession)).

## 11.8 사용자 기기의 앱: 코드가 handle을 들고 다닌다

사용자 기기의 앱 `local-client`는 대화 없이 정해 둔 순서로 tool을 부르므로, handle을 기억하는 것은 모델이 아니라 코드의 변수다.
`local-client`는 한 번 실행하는 동안 `createBasket`이 준 handle을 뒤의 모든 호출에 넘긴다.
아래는 browser 대신 curl이 login과 consent를 한 `docs/superpowers/captures/stateless-local-client-run.sh`의 출력이다.
10장과 같은 discovery와 login(`[1]`\~`[4]`), `[5]`의 `initialize`와 tool 목록은 뺐고, `addItem(p9, 2)`의 결과는 줄였다.

```text
[5] MCP 호출
    getStock(p1): 상품 p1 (게이밍 노트북 15인치) 의 현재 재고는 7개입니다.
    createBasket: 장바구니 bsk_lC9K7ohngy3NkrN2RnnenA를 만들었습니다. 2026-09-30T20:52:47.763027Z에 만료됩니다.
    addItem(p4, 1): 장바구니 bsk_lC9K7ohngy3NkrN2RnnenA:
- [p4] 인체공학 마우스 × 1 = 59,000원
합계 59,000원. 2026-09-30T20:52:47.763027Z에 만료됩니다.
    addItem(p9, 2): (생략)
    getBasket: 장바구니 bsk_lC9K7ohngy3NkrN2RnnenA:
- [p4] 인체공학 마우스 × 1 = 59,000원
- [p9] 휴대용 SSD 1TB × 2 = 278,000원
합계 337,000원. 2026-09-30T20:52:47.763027Z에 만료됩니다.
    getBasket(모르는 ID): [오류] 장바구니 bsk_AAAAAAAAAAAAAAAAAAAAAA를 찾을 수 없습니다. createBasket으로 새 장바구니를 만드세요.
    403 insufficient_scope — 필요한 scope: orders:write
[6] step-up: products:read orders:write로 다시 authorization을 받는다
    http://localhost:9040/oauth2/authorize?response_type=code&client_id=local-mcp-client&redirect_uri=http%3A%2F%2F127.0.0.1%3A60162%2Fcallback&scope=products%3Aread+orders%3Awrite&state=...&code_challenge=...&code_challenge_method=S256&resource=http%3A%2F%2Flocalhost%3A8151%2Fmcp
[3] callback으로 authorization code를 받았다: http://127.0.0.1:60162/callback
[4] client_secret 없이 access token을 받았다(299초 뒤 만료, scope: products:read orders:write)
[7] 새 token으로 같은 요청을 새로 보낸다
    checkout: 주문 ord-1001를 접수했습니다.
```

| 줄 | 볼 곳 |
|---|---|
| `createBasket`부터 `getBasket`까지 | 같은 handle로 두 상품을 담는다. handle은 만들 때마다 다르고, 주문 번호는 서버를 띄울 때마다 `ord-1001`부터 매긴다 |
| `getBasket(모르는 ID)` | 형식은 맞지만 만든 적 없는 handle이다. `[오류]`는 `isError: true`인 결과 앞에 앱이 붙이는 표시다 |
| `403`부터 `[7]`까지 | `checkout`에서 [10장](10-scope-and-step-up.md)과 같이 `orders:write`로 step-up하고, 같은 handle로 새 요청을 보낸다 |

`structuredContent`에서 handle을 꺼내는 코드는 `McpCalls#basketId`다.

```java
static String basketId(McpSchema.CallToolResult created) {
    if (created.structuredContent() instanceof Map<?, ?> content && content.get("basketId") instanceof String id) {
        return id;
    }
    throw new LocalClientException("createBasket이 basketId를 돌려주지 않았다");
}
```

코드는 `text`의 문장에서 handle을 잘라 내지 않고 `structuredContent`의 `basketId`를 읽으므로, 서버가 문장을 바꿔도 그대로 돈다.

## 11.9 서버 코드에서 보기

클래스는 `practice/mcp-stateless-handle/shop-mcp-server/src/main/java/dev/starryeye/stateless/mcpserver/` 아래에 있다.

**`McpTransportConfig`: stateless transport bean**

`application.yml`의 `spring.ai.mcp.server.protocol: STATELESS`로 Spring AI는 session 없는 transport `WebMvcStatelessServerTransport`를 쓴다.
자동 구성의 이 bean은 `@ConditionalOnMissingBean`이라서, `McpTransportConfig`는 `contextExtractor` 하나를 넣으려고 같은 bean을 직접 만든다.

```java
@Bean
public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
        @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
    return WebMvcStatelessServerTransport.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .messageEndpoint(properties.getMcpEndpoint())
            .contextExtractor(McpCaller::context)
            .build();
}
```

이 transport의 경로는 POST와 GET뿐이고, GET에는 `405`로 답한다.

tool이 사용자를 `McpTransportContext` 인자로 받으면 사용자를 쓴다는 것이 메서드 선언에 보이고, 테스트도 security context 없이 이 인자만 넘겨 tool을 부를 수 있다.
thread에 묶인 값에 기대지 않는다는 점도 있다.
지금의 stateless WebMVC sync 서버는 요청 thread에서 tool을 부르므로 `SecurityContextHolder`도 쓸 수 있고, authz에서 온 `ProductTools`는 로그에 남길 사용자만 거기서 읽는다.

**`McpCaller`: token의 사용자**

```java
public static McpTransportContext context(ServerRequest request) {
    return request.principal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .map(JwtAuthenticationToken.class::cast)
            .map(authentication -> McpTransportContext.create(Map.of(
                    SUBJECT, authentication.getToken().getSubject(),
                    CLIENT_ID, Objects.toString(authentication.getToken().getClaimAsString(CLIENT_ID), ""))))
            .orElse(McpTransportContext.EMPTY);
}
```

filter chain이 token을 먼저 검증하므로, 여기서 읽는 JWT는 이미 signature·`iss`·`aud`·만료를 통과한 값이다.
인증이 없으면 빈 context를 주고, tool이 부르는 `McpCaller.from`은 `sub`가 없으면 예외를 던진다.
`client_id`는 `createBasket 호출 (사용자=user, client_id=stateless-shop-agent)`처럼 로그에 남겨, 어느 client를 거친 호출인지 보이게 한다.

**`BasketStore#open`과 `checkout`: 찾는 순서와 한 번뿐인 주문**

```java
private Basket open(String subject, String handle) {
    removeEnded();
    /* handle이 bsk_ 뒤에 22자가 오는 형식이 아니면 BasketException.notFound(handle) */
    Basket basket = this.baskets.get(key(subject, handle));
    if (basket == null) {
        throw BasketException.notFound(handle);
    }
    if (basket.orderId != null) {
        throw BasketException.ordered(handle, basket.orderId);
    }
    /* 만료 시각이 지났으면 BasketException.expired(handle) */
    return basket;
}
```

`removeEnded`는 끝난 지 30분이 지난 장바구니를 지우고, `key`는 `subject + ":" + handle`을 만든다.
형식이 맞지 않는 값의 오류 문장에는 받은 값 대신 `(올바르지 않은 ID)`가 들어가서, 모델이 보낸 엉뚱한 값을 되풀이하지 않는다.
주문 여부를 만료보다 먼저 보므로, 주문한 장바구니는 만료 시각이 지나도 "이미 주문"으로 답한다.

같은 클래스의 `checkout`은 장바구니 찾기, 재고 줄이기, 닫기를 lock 하나 안에서 한다.

```java
public synchronized String checkout(String subject, String handle, Consumer<Map<String, Integer>> reserveStock) {
    Basket basket = open(subject, handle);
    if (basket.items.isEmpty()) {
        throw BasketException.empty(handle);
    }
    reserveStock.accept(Map.copyOf(basket.items));
    basket.orderId = "ord-" + (++this.orderSequence);
    basket.closedAt = this.clock.instant();
    return basket.orderId;
}
```

`reserveStock`(`ProductRepository#reserve`)은 모든 상품의 재고를 한꺼번에 줄이고, 하나라도 모자라면 아무것도 줄이지 않고 예외를 던진다.
주문 번호는 재고를 줄인 뒤에야 적으므로, 재고가 모자라 멈추면 장바구니는 열린 채로 남는다.

**`BasketTools#checkout`: tool 결과를 직접 만든다**

```java
@McpTool(name = "checkout", description = /* 주문한다. 사용자가 주문이나 결제를 분명히 요청할 때만 사용한다 */)
@RequiredScope("orders:write")
public CallToolResult checkout(McpTransportContext context, /* basketId 인자 */) {
    McpCaller caller = McpCaller.from(context);
    /* 호출 로그 */
    try {
        String orderId = this.baskets.checkout(caller.subject(), basketId, this.products::reserve);
        return CallToolResult.builder()
                .addTextContent("주문 %s를 접수했습니다.".formatted(orderId))
                .structuredContent(Map.of("orderId", orderId))
                .build();
    }
    catch (BasketException | IllegalStateException | IllegalArgumentException ex) {
        return error(ex.getMessage());
    }
}
```

`@RequiredScope("orders:write")`는 10장의 `ToolScopeFilter`가 읽으므로, 조회 token의 `checkout`은 이 메서드에 닿기 전에 `403`으로 끝난다.
Spring AI는 tool이 문자열을 돌려주면 text 하나인 결과로 만들므로, 이 tool은 `structuredContent`와 `isError`를 정하려고 `CallToolResult`를 직접 만든다.
장바구니 문제(`BasketException`), 재고 부족(`IllegalStateException`), 0 이하의 주문 수량(`IllegalArgumentException`)은 `error`가 `isError: true`인 결과로 바꾼다.

## 11.10 client 코드에서 보기

agent의 클래스는 `practice/mcp-stateless-handle/shop-agent/src/main/java/dev/starryeye/stateless/agent/` 아래에, `local-client`의 클래스는 `practice/mcp-stateless-handle/local-client/src/main/java/dev/starryeye/stateless/localclient/`에 있다.

**agent: `ChatMemoryConfig`**

```java
public static final int MEMORY_ADVISOR_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

@Bean
public ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager toolCallingManager) {
    return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .disableInternalConversationHistory();
}
```

`ChatClientConfig`는 `MessageChatMemoryAdvisor.builder(chatMemory).order(ChatMemoryConfig.MEMORY_ADVISOR_ORDER)`로 대화 기억 advisor를 넣는다.
`ChatMemory` bean은 `MessageWindowChatMemory`에 `maxMessages(20)`을 준다.

**agent: `ChatController`**

```java
@PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestBody String message, HttpSession session,
        Authentication authentication) {
    // 대화 기억은 로그인 사용자(sub)마다 따로 둔다.
    String conversationId = authentication.getName();
    // consent 뒤 browser가 같은 질문을 다시 보내므로, 그 turn은 끊기기 전의 기억에서 다시 시작해야 한다.
    // 그래서 turn을 시작하기 전 기억을 복사해 두고, step-up으로 끊기면 그대로 되돌린다.
    List<Message> before = List.copyOf(this.chatMemory.get(conversationId));
    Flux<String> content = this.chatClient.prompt()
            .user(message)
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
            .stream()
            .content();
    return ChatEvents.of(content, StepUpState.of(session), () -> restore(conversationId, before));
}
```

`restore`는 그 사용자의 기억을 지우고 `before`를 다시 넣는다.
`ChatEvents.of`는 채팅 stream이 `StepUpRequiredException`으로 끝날 때만 이 되돌리기를 부르고, 그다음에 consent 카드(`step-up`)나, 이미 거절한 scope면 거절 안내(`step-up-declined`) event를 만든다.

**local-client: `McpCalls`**

`McpCalls#run`은 `basketId(created)`(11.8)로 꺼낸 handle을 지역 변수에 두고, step-up 뒤 `callOnce`가 다시 부르는 호출까지 모두 같은 `basketId`를 넘긴다.

## 11.11 다루지 않는 것

- 2026-07-28의 요청 형식: `initialize` 대신 요청마다 `_meta`에 버전과 capability를 넣는 방식, `server/discover`, `Mcp-Name` header, GET을 대신하는 `subscriptions/listen`, SSE 재개(`Last-Event-ID`)가 빠진 것은 SDK가 2026-07-28을 지원한 뒤의 practice에서 다룬다.
- 영속 저장과 서버 여러 대: 장바구니와 대화 기억은 메모리에 있어서 다시 띄우면 사라지고, MCP Server는 한 대로만 돈다. 여러 대로 늘리려면 Redis 같은 공유 저장소에 `<sub>:<handle>` key로 두고, 한 번만 되는 주문도 그 저장소의 원자적 갱신으로 지킨다.
- scope별 tool 목록: 조회 token으로도 `tools/list`에 `checkout`이 보인다. token의 scope에 따라 다른 tool 목록을 주는 서버는 다음 practice에서 다룬다.
- 장바구니를 지우는 tool과 목록 tool: SEP-2567은 `destroy_*`·`list_*` tool을 두면 좋다고 하지만 꼭 두라고 하지는 않는다. 이 practice의 장바구니는 만료로만 사라진다.

## 11.12 직접 해 보기

```bash
# 저장소 최상위 폴더에서
practice/mcp-stateless-handle/run.sh
```

token 없이 부르면 `401`과 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8151/.well-known/oauth-protected-resource/mcp", scope="products:read"`가 온다.

```bash
curl -i -X POST http://localhost:8151/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

**웹 agent**: browser로 `http://localhost:8150`을 열고 `user`/`password`로 login한 뒤, consent 화면에서 `products:read`를 체크한다.
아래 질문을 차례로 보내고, `practice/mcp-stateless-handle/logs/shop-mcp-server.log`에서 tool 호출을 본다.

| 질문 | 볼 것 |
|---|---|
| `장바구니 만들고 인체공학 마우스 하나 담아 줘` | 로그에 `createBasket 호출`과 `addItem 호출 (사용자=user, productId=p4, quantity=1)`이 찍힌다 |
| `휴대용 SSD도 두 개 담아 줘` | `searchProducts 호출`과 `addItem 호출 (사용자=user, productId=p9, quantity=2)`이 찍히고, 새 `createBasket 호출`은 없다. 앞 turn의 handle을 기억에서 이어 쓴 것이다 |
| `결제해 줘` | 답 대신 `orders:write`를 요청하는 consent 카드가 뜬다. "권한 허용" 뒤 `orders:write`를 체크하면 질문이 다시 가고 주문 번호가 나온다 |
| "새 대화"를 누르고 `장바구니 보여 줘` | 모델이 옛 handle을 몰라 새 장바구니를 만들고, 비어 있다고 답한다 |

로컬 모델이라 기기에 따라 답 하나에 30\~100초가 걸린다.
해 볼 것과 기대 결과의 전체 목록은 [practice README의 직접 확인할 것](../mcp-stateless-handle/README.md#직접-확인할-것)에 있다.

**token이 필요한 요청**: 캡처 스크립트 `docs/superpowers/captures/mcp-stateless-walkthrough.sh`로 본다.
session header 없는 `initialize`, GET의 `405`와 DELETE의 `404`, handle 흐름, `user2`가 받는 결과, `checkout`의 step-up, 두 번째 `checkout`이 한 번에 기록된다.
consent 화면이 나오려면 저장된 consent가 없어야 하므로, 서버를 다시 띄운 직후에 돌린다.

```bash
# 저장소 최상위 폴더에서. 출력의 JWT는 앞 20자만 남는다
practice/mcp-stateless-handle/stop.sh
practice/mcp-stateless-handle/run.sh
docs/superpowers/captures/mcp-stateless-walkthrough.sh > /tmp/stateless-walkthrough.txt
```

스크립트는 `user`의 consent를 `orders:write`까지 남기므로, 그 뒤에 웹 agent를 처음부터 해 보려면 서버를 다시 띄운다.

**local-client**: `JAVA_HOME`을 Java 21로 맞춘 뒤 `practice/mcp-stateless-handle/local-client`에서 `./gradlew run`을 실행한다([practice README의 실행](../mcp-stateless-handle/README.md#실행)).
step-up의 consent 화면에서는 `products:read`와 `orders:write`를 모두 체크한다.

## 11.13 정리

- 2025-11-25의 서버는 session ID를 주지 않아도 된다. session이 없으면 요청마다 token만으로 사용자가 정해지므로, 공유 MCP client를 여러 사용자가 써도 섞일 상태가 없고 token 없는 종료 `DELETE`도 없다.
- 호출 사이의 상태는 서버가 만든 handle로 가리킨다. handle은 protocol의 기능이 아니라 tool 결과와 인자의 문자열이고, 수명은 tool 설명에 적는다.
- handle을 가졌다고 쓰게 하지 않는다. 서버는 검증한 token의 `sub`로 `<sub>:<handle>` key를 만들어 찾고, handle은 추측할 수 없는 무작위 값으로 만든다.
- 쓸 수 없는 장바구니는 모델이 읽고 이어 갈 수 있는 `isError: true` tool 결과로 알리고, 주문은 lock 안에서 장바구니를 닫아 한 번만 되게 한다.
- 웹 agent는 대화 기억을 tool loop 안에 두어 모델이 앞 turn의 handle을 다시 쓰게 하고, 사용자 기기의 앱은 코드가 `structuredContent`의 handle을 넘긴다.

## 11.14 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| 서버는 `initialize` 응답에서 session ID를 줄 수 있다. session ID를 받은 client는 이후 모든 요청에 넣는다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MAY, MUST |
| 더 쓰지 않는 session은 client가 `DELETE`로 끝낸다. session 종료를 허용하지 않는 서버는 `405`로 답할 수 있다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | SHOULD, MAY |
| GET에 서버는 `text/event-stream`으로 stream을 열거나 `405`로 답한다 | [MCP 2025-11-25 Transports — Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) | MUST |
| POST로 온 요청에 서버는 `text/event-stream`이나 `application/json` 하나로 답하고, client는 둘 다 처리한다 | [MCP 2025-11-25 Transports — Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) | MUST |
| authorization을 쓰는 서버는 모든 요청을 검증하고 session으로 인증하지 않는다. session ID는 안전한 무작위 값으로 만들고, `<user_id>:<session_id>`처럼 token에서 꺼낸 사용자에게 묶는다 | [MCP 2025-11-25 Security Best Practices — Session Hijacking](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices#session-hijacking) | MUST, MUST NOT, SHOULD |
| 2026-07-28은 protocol 수준의 session과 `Mcp-Session-Id`를 없애고, 호출 사이의 상태는 서버가 만든 handle을 tool 인자로 주고받아 다룬다 | [MCP 2026-07-28 Key Changes — Major changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog#major-changes) | — |
| handle은 protocol의 기능이 아니라 권하는 tool 설계다. handle은 불투명하게 만들고, 인증하는 서버는 호출마다 handle과 사용자를 함께 확인하며, 수명은 만드는 tool의 설명에 적고, 만료된 handle에는 만료를 알린다 | [SEP-2567 — Guidance for servers](https://modelcontextprotocol.io/seps/2567-sessionless-mcp#guidance-for-servers), [Security Implications](https://modelcontextprotocol.io/seps/2567-sessionless-mcp#security-implications) | 권고(규칙 아님) |
| authorization을 쓰는 서버는 모든 요청을 검증하고, handle을 가진 것만으로 인증하지 않는다. handle은 안전한 무작위 값으로 만들고, `<user_id>:<handle>`처럼 검증한 token의 사용자에게 묶어 다른 사용자가 보낸 handle은 거절한다 | [MCP 2026-07-28 Security Best Practices — State Handle Hijacking](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices#state-handle-hijacking) | MUST, MUST NOT, SHOULD |
| `structuredContent`를 주는 tool은 같은 내용을 JSON 문자열로 `text`에도 넣는다 | [MCP 2025-11-25 Tools — Structured Content](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#structured-content) | SHOULD |
| 입력 검증과 업무 규칙의 오류는 `isError: true`인 tool 결과로 알리고, client는 이 결과를 모델에게 넘긴다 | [MCP 2025-11-25 Tools — Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling) | —(서버), SHOULD(client) |

[← 10장](10-scope-and-step-up.md) · [목차](README.md) · [부록: API 레퍼런스 →](reference-api.md)
