# 1. MCP 기초 — LLM 앱은 외부 tool을 어떻게 부르나

## 1.1 MCP의 필요성

LLM은 학습한 내용으로만 답한다.
오늘의 재고나 사용자의 파일처럼 바깥에 있는 정보는 모른다.
그래서 LLM 앱은 외부 기능을 불러 그 결과를 LLM에게 건넨다. 이런 외부 기능을 tool이라고 한다.

문제는 앱과 tool을 잇는 방법이 앱마다 다르다는 점이다.
앱이 셋, tool이 넷이면 연결 코드를 열두 번 따로 짜야 한다.
MCP(Model Context Protocol)는 이 연결 방법을 하나로 정한 규약이다.
tool을 MCP Server로 한 번 만들어 두면 MCP를 지원하는 어느 앱에서든 쓸 수 있다.

**host·client·server**

MCP에는 역할이 셋 있다.

```mermaid
flowchart LR
    U[사용자] --> A
    subgraph H[host: LLM 앱]
        A[앱 코드]
        C1[MCP client 1]
        C2[MCP client 2]
        A --> C1
        A --> C2
    end
    A <-->|질문, tool 정의| L[LLM]
    C1 <-->|Streamable HTTP| S1[MCP Server: shop-mcp-server]
    C2 <-->|stdio| S2[MCP Server: 로컬 파일 server]
```

[다이어그램 그림으로 보기](diagrams/01-mcp-basics-1.png)

| 역할 | 하는 일 | official practice에서 |
|---|---|---|
| host | 사용자가 쓰는 LLM 앱이다. LLM과 이야기하고, 붙일 MCP Server마다 MCP client를 만든다 | `shop-agent` |
| MCP client | host 안에서 MCP Server 하나와 연결을 맺고 메시지를 주고받는다 | `shop-agent` 안의 Spring AI MCP client |
| MCP Server | tool 같은 기능을 내놓는 프로그램이다. 같은 기기에서 돌 수도 있고 원격에 있을 수도 있다 | `shop-mcp-server` |

Claude Desktop이나 Cursor 같은 앱이 host다.
MCP client와 MCP Server는 1:1로 짝을 짓는다. server가 셋이면 client도 셋이다.
LLM을 부르는 것은 host다. MCP Server는 LLM과 직접 이야기하지 않는다.

**server가 내놓는 기능 세 가지**

| 기능 | 뜻 | 쓸지 정하는 쪽 |
|---|---|---|
| tool | LLM이 불러 실행하는 함수. 예: 재고 조회 | LLM |
| resource | 읽을 수 있는 데이터. 예: 파일 내용 | host 앱 |
| prompt | 미리 만들어 둔 메시지 틀 | 사용자 |

이 practice는 tool만 쓴다.
official의 `shop-mcp-server`는 `getStock`과 `searchProducts` 두 tool을 내놓는다.

MCP 메시지는 transport라는 통로를 따라 오간다.
같은 기기 안이면 표준 입출력(stdio)을, 네트워크 너머면 HTTP(Streamable HTTP)를 쓴다.
official은 Streamable HTTP를 쓰고, MCP Server 주소는 `http://localhost:8111/mcp`다.
두 transport의 차이는 1.11에서 다룬다.

## 1.2 메시지 형식: JSON-RPC 2.0

MCP는 메시지 모양을 새로 만들지 않고 JSON-RPC 2.0을 그대로 쓴다.
JSON-RPC는 "이 method를 이 parameter로 실행해 달라"를 JSON으로 적는 작은 규약이다.
메시지는 세 종류다.

```text
요청          {"jsonrpc":"2.0","id":2,"method":"tools/list"}
응답          {"jsonrpc":"2.0","id":2,"result":{"tools":[...]}}
notification  {"jsonrpc":"2.0","method":"notifications/initialized"}
```

| 종류 | 들어 있는 field | 답이 오나 |
|---|---|---|
| 요청(request) | `jsonrpc`, `id`, `method`, `params` | 온다. 같은 `id`를 단 응답이 돌아온다 |
| 응답(response) | `jsonrpc`, `id`, 그리고 `result`와 `error` 중 하나 | 응답에는 답하지 않는다 |
| notification | `jsonrpc`, `method`, `params`. `id`가 없다 | 오지 않는다 |

**`id`의 역할**

client는 앞 요청의 응답을 기다리지 않고 다음 요청을 보낼 수 있다.
그러면 응답이 보낸 순서대로 오지 않을 수 있다.
그래서 요청마다 `id`를 붙이고, 응답에는 그 요청의 `id`를 그대로 적는다.
client는 이 값으로 응답을 요청과 짝짓는다.

MCP는 JSON-RPC보다 조금 엄격하다.
요청의 `id`는 `null`일 수 없고, 한 session(1.9) 안에서 같은 값을 다시 쓰지 않는다.
여러 메시지를 배열 하나로 묶어 보내는 JSON-RPC batch도 쓰지 않는다.

notification은 알리기만 하고 답을 바라지 않는 메시지다.
`id`가 없으니 짝지을 응답도 없다.

**실패 응답**

실패하면 `result` 대신 `error`가 온다.
아래는 official MCP Server가 모르는 버전 header를 받았을 때의 응답 본문이다(1.10).

```json
{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported MCP-Protocol-Version: 1999-01-01"}}
```

`code`는 JSON-RPC가 정한 정수이고, `-32600`은 "잘못된 요청"이라는 뜻이다.
`id`가 `null`인 것은 server가 본문을 읽기 전에 거절해서 요청의 `id`를 모르기 때문이다.

## 1.3 연결의 한 생애: 시퀀스 다이어그램

MCP 연결은 초기화, 사용, 종료 세 구간으로 나뉜다.
초기화에서 서로 무엇을 할 수 있는지 맞추고, 사용 구간에서 tool을 부르고, 종료에서 session을 정리한다.

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant M as MCP Server
    Note over C,M: 초기화
    C->>M: POST /mcp initialize
    M-->>C: 200 result + Mcp-Session-Id
    C->>M: POST /mcp notifications/initialized
    M-->>C: 202 (본문 없음)
    Note over C,M: 사용
    C->>M: POST /mcp tools/list
    M-->>C: 200 SSE - tools 목록
    C->>M: POST /mcp tools/call getStock
    M-->>C: 200 SSE - tool 결과
    Note over C,M: 종료
    C->>M: DELETE /mcp
    M-->>C: 200 (session 끝)
```

[다이어그램 그림으로 보기](diagrams/01-mcp-basics-2.png)

| 단계 | 메시지 | 얻는 것 |
|---|---|---|
| 1단계 (1)(2): 서로 소개하기 | `initialize` | 이 연결에서 쓸 버전과 capability, session ID |
| 2단계 (3)(4): 준비 끝 알리기 | `notifications/initialized` | 이제부터 일반 요청을 보낼 수 있다 |
| 3단계 (5)(6): tool 목록 받기 | `tools/list` | tool 이름, 설명, 받는 인자 |
| 4단계 (7)(8): tool 부르기 | `tools/call` | tool 실행 결과 |
| 5단계 (9)(10): session 끝내기 | `DELETE` | server가 session을 정리한다 |

2단계부터는 모든 요청에 두 header를 보낸다. 1단계에서 받은 `Mcp-Session-Id`와 협상한 `MCP-Protocol-Version`이다.

이 장은 MCP 2025-11-25 버전을 따른다. official의 MCP Java SDK가 협상하는 버전이 이것이다.
2026-07-28 버전에는 `initialize`와 session이 없다. 달라진 점은 9장에서 다룬다.

아래 예시는 official practice를 실제로 띄워 받은 응답이다.
official의 MCP Server는 token이 없는 요청을 `401`로 거절한다. 그래서 모든 요청에 `Authorization` header가 있다.
token을 받는 과정은 3~5장, 이 header를 검사하는 과정은 6장에서 다룬다. 이 장에서는 이 header를 건너뛰고 읽어도 된다.

## 1.4 1단계: `initialize`로 서로를 소개한다

client와 server는 처음에 서로를 모른다.
어느 MCP 버전으로 이야기할지, 상대가 어떤 기능을 지원하는지부터 맞춰야 한다.
그래서 첫 요청은 언제나 `initialize`다.

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Content-Type: application/json
Accept: application/json, text/event-stream

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": { "name": "walkthrough", "version": "1.0.0" }
  }
}
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
    "capabilities": {
      "completions": {},
      "logging": {},
      "prompts": { "listChanged": true },
      "resources": { "subscribe": false, "listChanged": true },
      "tools": { "listChanged": true }
    },
    "serverInfo": { "name": "official-shop-mcp-server", "version": "0.0.1" }
  }
}
```

| field | 뜻 | 이 응답에서 |
|---|---|---|
| `protocolVersion` | 이 연결에서 쓸 MCP 버전. 날짜 모양이다 | client가 요청한 `2025-11-25`를 server도 지원해서 그대로 돌려준다 |
| `capabilities` | server가 지원하는 선택 기능 목록 | `tools`가 있으니 tool을 부를 수 있다. `listChanged: true`는 tool 목록이 바뀌면 알려 준다는 뜻이다 |
| `serverInfo` | server의 이름과 버전 | `application.yml`의 `spring.ai.mcp.server.name`·`version` 값이다 |
| `Mcp-Session-Id` (응답 header) | 이 연결을 가리키는 session ID | 1.9에서 다룬다 |

**버전 맞추기**

client는 자기가 지원하는 가장 새 버전을 보낸다.
server가 그 버전을 지원하면 같은 값을 돌려준다.
지원하지 않으면 자기가 지원하는 다른 버전을 돌려준다.
client는 돌려받은 버전을 모르면 연결을 끊는다.

**capability 맞추기**

capability는 이 연결에서 쓸 수 있는 선택 기능의 목록이다.
client도 요청의 `capabilities`로 자기 기능을 알린다. 이 예시의 client는 빈 객체를 보내 지원하는 기능이 없다고 알렸다.
양쪽은 이후 상대가 선언한 기능만 쓴다.
official은 tool만 만들었지만 응답에는 `prompts`·`resources`·`completions`도 있다. Spring AI의 기본 설정이 이 기능들도 선언하기 때문이다.

이 응답은 `Content-Type: application/json`, 곧 JSON 하나다.
뒤의 `tools/list` 응답은 다른 형식으로 온다(1.6).

## 1.5 2단계: `notifications/initialized`로 준비를 알린다

server는 `initialize`에 답한 뒤에도 client가 그 응답을 다 처리했는지 모른다.
그래서 server는 이 notification을 받기 전에는 ping과 로그 메시지 말고는 client에게 요청을 보내지 않는다.
client는 "이제 일반 요청을 보내겠다"는 notification으로 초기화를 마친다.

```http
POST /mcp HTTP/1.1
Host: localhost:8111
Authorization: Bearer eyJraWQiOiJlZDY1ZWFl...
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: 7c324e98-8854-4b88-9243-7a97e94fd80a
MCP-Protocol-Version: 2025-11-25

{"jsonrpc":"2.0","method":"notifications/initialized"}
```

```http
HTTP/1.1 202
Content-Length: 0
```

notification에는 `id`가 없으므로 JSON-RPC 응답도 없다.
그래도 HTTP 요청에는 응답이 있어야 하므로, server는 본문 없는 `202 Accepted`로 받았다는 것만 알린다.

이 요청부터 header 두 개가 더 붙는다.
`Mcp-Session-Id`는 1단계 응답에서 받은 값이고(1.9), `MCP-Protocol-Version`은 협상한 버전이다(1.10).

## 1.6 3단계: `tools/list`로 tool 목록을 받는다

client는 server에 어떤 tool이 있는지 모른다.
tool의 이름과 설명, 받는 인자를 알아야 LLM에게 "이런 tool이 있다"고 알려 줄 수 있다.

header는 2단계와 같고 본문만 다르다.

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

```http
HTTP/1.1 200
Content-Type: text/event-stream

id:7c324e98-8854-4b88-9243-7a97e94fd80a
event:message
data:{"jsonrpc":"2.0","id":2,"result":{"tools":[ ... ]}}
```

이번 응답은 `Content-Type: text/event-stream`이다. 이 형식을 SSE(Server-Sent Events)라고 한다.
SSE는 server가 한 응답 안에서 메시지를 여러 번 나눠 보내는 방식이다.
메시지 하나는 `id:`·`event:`·`data:` 줄의 묶음이고, 빈 줄로 끝난다.
오래 걸리는 작업이라면 server는 진행 상황 notification을 먼저 보내고 마지막에 JSON-RPC 응답을 보낼 수 있다.
official의 tool은 바로 끝나므로 stream에는 응답 하나만 있다.

client는 요청을 보낼 때 JSON과 SSE 중 무엇이 올지 모른다.
그래서 `Accept`에 두 형식을 모두 적고, 둘 다 처리할 줄 알아야 한다.

`id:` 줄은 SSE event의 ID다. 끊긴 stream을 다시 이어 받을 때 쓰며, JSON-RPC의 `id`와는 다른 값이다.
official에서는 SDK가 이 자리에 session ID를 넣어 모든 event의 ID가 같다.
명세는 이 값이 session 안에서 겹치지 않기를 요구하므로, 이 부분은 명세와 다르다.

`data:` 줄의 JSON을 펼치면 다음과 같다.

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "getStock",
        "title": "getStock",
        "description": "상품 ID로 현재 재고 수량을 조회한다. 사용자가 특정 상품의 재고나 구매 가능 여부를 물을 때 사용한다. 상품 ID를 모르면 먼저 searchProducts 로 상품을 찾아야 한다.",
        "inputSchema": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "properties": {
            "productId": { "type": "string", "description": "상품 ID. 예: p1" }
          },
          "required": ["productId"]
        },
        "annotations": { "readOnlyHint": false, "destructiveHint": true, "...": "그 밖의 field는 생략" }
      },
      { "name": "searchProducts", "...": "그 밖의 field는 생략" }
    ]
  }
}
```

| field | 뜻 | 누가 읽나 |
|---|---|---|
| `name` | tool 이름. `tools/call`에서 이 이름으로 부른다 | MCP client |
| `description` | tool이 하는 일과 쓸 때 | LLM. 이 글을 읽고 tool을 고른다 |
| `inputSchema` | 받는 인자의 모양(JSON Schema) | LLM. 이 모양대로 인자를 만든다 |
| `annotations` | 읽기만 하는지 같은 tool 성격 힌트 | MCP client. 믿을 수 있는 server의 값만 참고한다 |

`description`은 사람보다 LLM이 읽는 글이다.
`getStock`의 설명은 상품 ID를 모르면 먼저 `searchProducts`로 찾으라고 안내한다.
LLM은 이런 안내를 보고 tool을 부를 순서를 정한다.

`annotations`는 official이 따로 정하지 않아 기본값이 나온다.
그래서 조회만 하는 `getStock`도 `destructiveHint: true`다.
server가 주는 힌트를 그대로 믿으면 안 되는 이유가 이것이다.

## 1.7 4단계: `tools/call`로 tool을 부른다

LLM이 tool과 인자를 고르면, MCP client가 그 호출을 server에 전한다.
header는 2단계와 같다.

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": { "name": "getStock", "arguments": { "productId": "p1" } }
}
```

```http
HTTP/1.1 200
Content-Type: text/event-stream

id:7c324e98-8854-4b88-9243-7a97e94fd80a
event:message
data:{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"상품 p1 (게이밍 노트북 15인치) 의 현재 재고는 7개입니다."}],"isError":false}}
```

| field | 뜻 |
|---|---|
| `params.name` | 부를 tool 이름. `tools/list`의 `name` 중 하나다 |
| `params.arguments` | tool에 넘길 인자. `inputSchema` 모양을 따른다 |
| `result.content` | 결과 목록. 여기서는 글(`text`) 하나이고, 그림 같은 다른 형식도 있다 |
| `result.isError` | tool이 실행 중에 실패했는지 여부 |

실패는 두 가지로 나뉜다.
없는 tool 이름이나 잘못된 요청 모양은 JSON-RPC `error`로 온다.
tool은 돌았지만 일이 실패한 경우는 `result`에 `isError: true`로 온다. 외부 API 오류나 잘못된 날짜 형식이 그런 예다.
두 번째 경우 client는 결과 글을 LLM에게 넘긴다. LLM은 그 글을 읽고 인자를 바꿔 다시 부를 수 있다.

## 1.8 5단계: `DELETE`로 session을 끝낸다

server는 session마다 협상한 버전과 상태를 기억한다.
client가 더 쓰지 않을 session을 알려 주면 server는 그 자원을 바로 정리할 수 있다.

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

MCP에는 종료용 JSON-RPC 메시지가 없다. 연결을 어떻게 끝낼지는 transport가 정한다.
HTTP에서는 연결을 닫으면 끝나고, session까지 확실히 끝내려면 이 `DELETE`를 보낸다.
server는 `DELETE`를 `405`로 거절할 수도 있다. official은 받아들인다.
MCP Java SDK client는 client를 닫을 때 이 `DELETE`를 보낸다.

## 1.9 session: `Mcp-Session-Id`

HTTP 요청은 하나하나가 따로 논다.
`tools/list`를 받은 server는 이 요청이 어느 `initialize` 뒤에 온 것인지 알 수 없다.
그래서 server는 `initialize` 응답에 session ID를 주고, client는 이후 요청마다 그 값을 돌려보낸다.
server는 이 값으로 그 연결에서 협상한 버전과 capability를 찾는다.

규칙은 네 가지다.

- server는 `initialize` 응답의 `Mcp-Session-Id` header로 session ID를 준다. session을 쓰지 않는 server는 주지 않는다.
- 받은 client는 이후 모든 요청(`POST`·`GET`·`DELETE`)에 같은 값을 넣는다.
- session ID가 있어야 하는 요청에 없으면 server는 `400`으로 답한다.
- 끝난 session의 ID가 오면 server는 `404`로 답한다. `404`를 받은 client는 session ID를 빼고 `initialize`부터 다시 시작한다.

명세는 이 header를 `MCP-Session-Id`로 적고, official 응답에는 `Mcp-Session-Id`로 온다.
HTTP header 이름은 대소문자를 가리지 않으므로 둘은 같은 header다.

session ID 없이 `tools/list`를 보내면 `400`이다.

```http
HTTP/1.1 400
Content-Type: application/json

{"jsonRpcError":{"code":-32601,"message":"Session ID missing"},"...":"그 밖의 field는 생략"}
```

이 본문은 SDK가 예외 객체를 그대로 JSON으로 만든 것이다. JSON-RPC 응답 모양이 아니고 stack trace까지 들어 있다.
그러니 client는 본문이 아니라 상태 코드로 판단한다.

`DELETE`로 끝낸 session으로 다시 `tools/list`를 보내면 `404`다.

```http
HTTP/1.1 404
Content-Type: application/json

{"jsonRpcError":{"code":-32603,"message":"Session not found: d04dcc79-10d6-4539-bcb0-9854a1e7fe7f"},"...":"그 밖의 field는 생략"}
```

server가 발급한 적 없는 session ID를 보내도 똑같이 `404`다.
MCP Java SDK client는 `404`를 받으면 그 session을 버리고 `initialize`부터 다시 한다.

**session ID는 인증이 아니다**

session ID는 어느 연결인지를 가리킬 뿐, 누가 보냈는지를 증명하지 않는다.
다른 사람이 이 값을 알아내 보내면 server는 원래 client와 구별하지 못한다.
그래서 official의 MCP Server는 session ID와 상관없이 요청마다 token을 검사한다. 이 검사는 6장에서 다룬다.

## 1.10 `MCP-Protocol-Version` header

버전은 `initialize`에서 한 번 협상한다.
그 뒤에도 server가 요청마다 어느 버전의 규칙으로 답할지 알 수 있게, client는 협상한 버전을 header로 매번 보낸다.

- client는 `initialize` 뒤 모든 요청에 `MCP-Protocol-Version: 2025-11-25`처럼 협상한 버전을 넣는다.
- header가 없고 버전을 알아낼 다른 방법도 없으면, server는 `2025-03-26`으로 여긴다. 이 header가 생기기 바로 전 버전이다.
- 모르는 버전이나 형식이 틀린 값이 오면 server는 `400`으로 답한다.

없는 버전 `1999-01-01`을 보내 보면 다음 응답이 온다.

```http
HTTP/1.1 400
Content-Type: application/json;charset=UTF-8

{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Unsupported MCP-Protocol-Version: 1999-01-01"}}
```

official의 MCP Server는 Spring AI MCP 2.0.0의 Streamable HTTP transport를 쓴다.
이 transport는 이 header를 검사하지 않아서, 모르는 버전을 보내도 `200`으로 답한다.
그래서 official은 `McpProtocolVersionFilter`로 이 검사를 따로 한다. 이 filter의 코드는 6장에서 본다.
header가 없는 요청은 그대로 통과시킨다. 이 server는 버전에 따라 동작이 달라지지 않기 때문이다.

버전마다 무엇이 달라졌는지는 9장에서 다룬다.

## 1.11 transport: stdio와 Streamable HTTP

JSON-RPC는 메시지 모양만 정하고, 메시지를 어떤 통로로 나를지는 정하지 않는다.
그 통로가 transport다. MCP는 transport 두 가지를 정해 두었다.

**stdio: 같은 기기의 process**

client가 MCP Server를 자식 process로 띄운다.
client는 server의 표준 입력(stdin)에 메시지를 쓰고, server는 표준 출력(stdout)에 답을 쓴다.
메시지 하나는 한 줄이고, 메시지 안에는 줄바꿈이 없다.
stdout에는 MCP 메시지만 쓴다. 로그는 표준 오류(stderr)로 보낸다.
Claude Desktop 같은 앱에 로컬 파일 server를 붙이는 경우가 대표적이다.

**Streamable HTTP: 원격 server**

MCP Server는 따로 실행되는 process이고, 여러 client가 붙는다.
server는 MCP endpoint 하나를 연다. official에서는 `http://localhost:8111/mcp`다.

| 요청 | 하는 일 |
|---|---|
| `POST /mcp` | client가 메시지 하나를 보낸다. 요청에는 JSON이나 SSE로 답하고, notification에는 `202`로 답한다 |
| `GET /mcp` | client가 묻지 않아도 server가 보낼 메시지를 받을 SSE stream을 연다 |
| `DELETE /mcp` | session을 끝낸다 |

`GET` stream은 server가 먼저 할 말이 있을 때 쓴다.
예를 들어 tool 목록이 바뀌었다는 notification(`notifications/tools/list_changed`)이 이 stream으로 올 수 있다.
MCP Java SDK client는 session ID를 받자마자 이 stream을 연다.
official에 `GET /mcp`를 보내면 연결이 열린 채로 있고, 보낼 메시지가 생길 때까지 상태 줄도 오지 않는다.
stream을 내놓지 않는 server는 `GET`에 `405`로 답한다.

| | stdio | Streamable HTTP |
|---|---|---|
| server 위치 | client와 같은 기기 | 네트워크 너머 어디든 |
| server를 띄우는 쪽 | client가 자식 process로 띄운다 | server 운영자가 따로 띄운다 |
| 붙는 client | 띄운 client 하나 | 여럿 |
| server가 credentials를 받는 방법 | 환경 변수 | OAuth access token |
| official | 없음 | `shop-mcp-server` |

**OAuth는 HTTP transport에서만 쓴다**

HTTP server에는 주소를 아는 누구나 요청을 보낼 수 있다.
그래서 server는 요청을 누가 보냈고 누구를 대신하는지 알아야 한다. MCP는 이 일을 OAuth로 한다(2장).

stdio server는 사용자의 앱이 사용자의 기기에서 띄운다.
네트워크로 열려 있지 않아서, 띄운 client 말고는 말을 걸 수 없다.
그래도 stdio server가 외부 API를 부르려면 API key 같은 credentials가 필요하다.
이 값은 host가 server를 띄울 때 환경 변수로 넘긴다.
MCP 명세도 authorization 규칙은 HTTP transport에만 적용하고, stdio server는 환경에서 credentials를 가져오게 한다.

Spring AI MCP client라면 stdio server 설정은 이런 모양이다.
official에는 stdio server가 없으므로 이름과 값은 예시다.

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            files:                               # 예시 이름
              command: java                      # 띄울 process
              args: ["-jar", "files-mcp-server.jar"]
              env:
                FILES_API_KEY: ${FILES_API_KEY}  # server process의 환경 변수가 된다
```

Claude Desktop 같은 host의 설정 파일도 같은 세 항목(`command`·`args`·`env`)을 쓴다.

## 1.12 client와 server가 지키는 것

앞에서 본 규칙을 어기면 무슨 일이 생기는지 모았다.
괄호 속 상태 코드는 official이 돌려주는 값이다.

| 확인 | 어기면 생기는 일 |
|---|---|
| client는 `initialize`를 가장 먼저 보낸다 | server가 버전도 capability도 모르는 요청을 받는다. official은 session ID가 없다며 거절한다(`400`) |
| client는 돌려받은 `protocolVersion`을 지원하는지 본다 | 서로 다른 버전 규칙으로 메시지를 읽게 된다. 모르는 버전이면 연결을 끊는다 |
| 양쪽은 상대가 선언한 capability만 쓴다 | 상대가 모르는 기능을 불러 실패한다 |
| client는 `POST`의 `Accept`에 `application/json`과 `text/event-stream`을 모두 적는다 | server가 SSE로 답할 수 없다. official은 요청을 거절한다(`400`) |
| client는 `initialize` 뒤 요청마다 `Mcp-Session-Id`를 넣는다 | server가 요청을 session과 잇지 못한다(`400`) |
| client는 `404`를 받으면 새 `initialize`로 다시 시작한다 | 끝난 session으로 보내는 요청이 모두 실패한다 |
| client는 요청마다 협상한 `MCP-Protocol-Version`을 넣는다 | 모르는 값이면 거절된다(`400`). 빠뜨리면 server가 옛 버전 `2025-03-26`으로 여길 수 있다 |
| client는 한 session 안에서 요청 `id`를 다시 쓰지 않는다 | 응답이 어느 요청의 것인지 가릴 수 없다 |
| stdio server는 stdout에 MCP 메시지만 쓴다 | 로그 한 줄만 섞여도 client가 메시지를 읽지 못한다 |

## 1.13 official 코드에서 보기

**MCP Server: tool을 내놓는 쪽**

`shop-mcp-server`의 `ProductTools`는 bean의 메서드에 `@McpTool`을 붙여 tool을 만든다.
Spring AI는 이 annotation을 읽어 `tools/list` 응답을 만들고, `tools/call`이 오면 해당 메서드를 부른다.

```java
@Component
public class ProductTools {

    @McpTool(name = "getStock",                                     // tools/list의 name
            description = "상품 ID로 현재 재고 수량을 조회한다. ...")   // tools/list의 description
    public String getStock(
            @McpToolParam(description = "상품 ID. 예: p1", required = true)  // inputSchema의 productId
            String productId) {
        return productRepository.findById(productId) /* ... */;     // tools/call 결과의 text
    }

    @McpTool(name = "searchProducts", description = "판매 중인 상품을 검색한다. ...")
    public String searchProducts(
            @McpToolParam(description = /* ... */, required = false)  // inputSchema의 required에 없다
            String keyword) {
        /* ... */
    }
}
```

메서드 parameter가 `inputSchema`의 `properties`가 되고, 돌려준 `String`이 결과의 `text`가 된다.
`required = false`인 `keyword`는 `searchProducts`의 `required` 목록에 들어가지 않는다.

server 설정은 `application.yml`의 `spring.ai.mcp.server`에 있다.

```yaml
spring:
  ai:
    mcp:
      server:
        name: official-shop-mcp-server   # initialize 응답의 serverInfo.name
        version: 0.0.1                   # serverInfo.version
        protocol: STREAMABLE             # session을 쓰는 Streamable HTTP
        type: SYNC                       # tool 메서드가 값을 바로 돌려준다
```

- `protocol`은 transport를 고른다. `STATELESS`는 session 없는 Streamable HTTP이고, `SSE`는 2024-11-05 버전의 옛 HTTP+SSE transport다.
- endpoint 경로는 기본값 `/mcp`다. 바꾸려면 `spring.ai.mcp.server.streamable-http.mcp-endpoint`를 쓴다.
- `type: SYNC`에서는 tool 메서드가 `String` 같은 평범한 값을 돌려준다. `Mono<String>`을 돌려주면 오류 없이 tool 등록에서 빠진다.

**Agent: tool을 쓰는 쪽**

`shop-agent`가 host다. 이 앱 안의 Spring AI MCP client가 `shop-mcp-server`와 연결을 맺는다.

```yaml
spring:
  ai:
    mcp:
      client:
        type: SYNC
        initialized: false                  # 기동할 때 initialize 하지 않는다
        streamable-http:
          connections:
            shop:                           # 연결 이름
              url: "http://localhost:8111"  # endpoint는 기본값 /mcp
```

`connections` 아래 항목 하나가 MCP client 하나다. MCP Server를 더 붙이려면 항목을 더 적는다.
`initialized: false`는 초기화를 처음 쓰는 때로 미룬다.
기동하는 순간에는 대신 부를 사용자가 없고, MCP Server는 token 없는 요청에 `401`을 준다.
그래서 기동 때 `initialize`를 하면 실패한다. 첫 채팅 요청이 올 때 그 사용자의 token으로 초기화한다(6장).

MCP client가 받은 tool은 저절로 LLM에게 가지 않는다.
`ChatClientConfig`가 tool 목록을 `ChatClient`에 연결한다.

```java
@Bean
public ChatClient shopChatClient(ChatClient.Builder builder,
                                 ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
    ChatClient.Builder configured = builder.defaultSystem(SYSTEM_PROMPT);
    toolCallbackProvider.ifAvailable(configured::defaultTools);   // MCP tool을 LLM에 넘긴다
    return configured.build();
}
```

`ToolCallbackProvider`는 Spring AI의 MCP client 자동 구성이 만드는 bean이다. MCP client가 받은 `tools/list` 결과를 Spring AI의 tool 정의로 바꿔 담는다.
이 줄이 없어도 앱은 뜨고 답도 온다. 다만 LLM은 tool을 모르는 채 기억으로만 답한다.

사용자가 "p1 재고 알려줘"라고 물으면 이렇게 흘러간다.

1. `ChatClient`가 질문과 tool 정의를 LLM(Ollama의 `qwen3:8b`)에게 보낸다. tool 정의는 `tools/list`의 이름·설명·`inputSchema`다.
2. LLM은 `getStock`을 `{"productId": "p1"}`로 부르겠다고 답한다.
3. Spring AI가 MCP client로 `tools/call`을 보낸다.
4. Spring AI가 결과 글을 LLM에게 돌려주고, LLM이 그 글로 답을 만든다.

tool을 고르는 것은 LLM이고, 실제로 부르는 것은 host 안의 MCP client다.

## 1.14 직접 해 보기

official의 MCP Server는 token 없는 요청을 모두 거절한다.
그래서 이 장의 요청을 직접 보내려면 access token이 있어야 한다.
token을 받는 과정은 2~5장에서 다루므로, 그 장들을 읽은 뒤 해 보기를 권한다.

```bash
cd practice/mcp-security-authn-official
./run.sh
```

**browser로 보기**

browser로 `http://localhost:8110`에 들어가 `user`/`password`로 login하고 "p1 재고 알려줘"라고 묻는다.
MCP Server 로그(`practice/mcp-security-authn-official/logs/shop-mcp-server.log`)에 tool 호출이 남는다.

```text
... d.s.officialmcpserver.ProductTools : getStock 호출 (productId=p1, 사용자=user)
```

**curl로 보기**

`docs/superpowers/captures/mcp-authorization-walkthrough.sh`는 login과 token 발급을 대신 하고 MCP 요청을 차례로 보낸다.
출력의 7~10단계가 이 장의 1~4단계이고, 18단계가 5단계다.
14단계는 session ID가 없는 요청, 15단계는 모르는 버전 header를 보낸 요청이다.

```bash
# 저장소 최상위 폴더에서. 출력에는 token 원문이 남는다
docs/superpowers/captures/mcp-authorization-walkthrough.sh > /tmp/walkthrough.txt
```

token이 이미 있다면 한 단계씩 직접 보낼 수 있다.

```bash
MCP=http://localhost:8111/mcp
TOKEN='<5장에서 받은 access token>'

# 1단계: initialize. 응답 header의 Mcp-Session-Id를 기억한다
curl -i -X POST $MCP -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

SESSION='<위 응답의 Mcp-Session-Id>'
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'
   -H 'Accept: application/json, text/event-stream'
   -H "Mcp-Session-Id: $SESSION" -H 'MCP-Protocol-Version: 2025-11-25')

# 2단계: notifications/initialized → 202
curl -i -X POST $MCP "${H[@]}" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3단계: tools/list → SSE
curl -i -X POST $MCP "${H[@]}" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 4단계: tools/call
curl -i -X POST $MCP "${H[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getStock","arguments":{"productId":"p1"}}}'

# 5단계: session 종료 → 200. 그 뒤 3단계를 다시 보내면 404
curl -i -X DELETE $MCP -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" -H 'MCP-Protocol-Version: 2025-11-25'
```

`Mcp-Session-Id`를 빼거나 `MCP-Protocol-Version`을 `1999-01-01`로 바꿔 보내면 `400`을 볼 수 있다.

## 1.15 정리

- MCP는 LLM 앱(host)이 외부 tool을 부르는 표준 규약이다. host 안의 MCP client가 MCP Server 하나와 JSON-RPC 메시지를 주고받는다.
- 연결은 `initialize` → `notifications/initialized` → `tools/list`·`tools/call` → `DELETE` 순서로 흘러간다.
- Streamable HTTP에서는 `initialize` 뒤 요청마다 `Mcp-Session-Id`와 `MCP-Protocol-Version`을 보낸다. session ID가 없거나 버전이 틀리면 `400`, 끝난 session이면 `404`다.
- session ID는 연결을 가리킬 뿐 보낸 사람을 증명하지 않는다. 증명은 OAuth token이 맡는다(6장).
- OAuth는 HTTP transport에서만 쓴다. stdio server는 환경 변수로 credentials를 받는다.

## 1.16 명세 근거

| 내용 | 명세 | 요구 수준 |
|---|---|---|
| MCP 메시지는 JSON-RPC 2.0을 따른다 | [MCP 2025-11-25 Overview — Messages](https://modelcontextprotocol.io/specification/2025-11-25/basic#messages), [JSON-RPC 2.0](https://www.jsonrpc.org/specification) | MUST |
| 요청의 `id`는 문자열이나 정수이고 `null`이 아니며, 한 session 안에서 다시 쓰지 않는다 | [MCP 2025-11-25 Overview — Requests](https://modelcontextprotocol.io/specification/2025-11-25/basic#requests) | MUST, MUST NOT |
| 응답은 요청과 같은 `id`에 `result`나 `error`를 담는다 | [MCP 2025-11-25 Overview — Responses](https://modelcontextprotocol.io/specification/2025-11-25/basic#responses) | MUST |
| notification에는 `id`가 없고, 받는 쪽은 응답하지 않는다 | [MCP 2025-11-25 Overview — Notifications](https://modelcontextprotocol.io/specification/2025-11-25/basic#notifications) | MUST NOT |
| host는 server마다 client를 하나씩 만든다 | [MCP 2025-11-25 Architecture — Core Components](https://modelcontextprotocol.io/specification/2025-11-25/architecture#core-components) | — |
| 초기화가 첫 상호작용이고, server는 `protocolVersion`·`capabilities`·`serverInfo`로 답한다. 성공하면 client는 `notifications/initialized`를 보낸다 | [MCP 2025-11-25 Lifecycle — Initialization](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization) | MUST |
| server는 `notifications/initialized`를 받기 전에 ping·로그 말고는 요청을 보내지 않는다 | [MCP 2025-11-25 Lifecycle — Initialization](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization) | SHOULD NOT |
| server는 요청받은 버전을 지원하면 같은 버전으로, 아니면 지원하는 다른 버전으로 답한다. client는 돌려받은 버전을 모르면 연결을 끊는다 | [MCP 2025-11-25 Lifecycle — Version Negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#version-negotiation) | MUST, SHOULD |
| 양쪽은 협상한 버전과 capability만 쓴다 | [MCP 2025-11-25 Lifecycle — Operation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#operation) | MUST |
| HTTP에서는 연결을 닫아 종료를 알린다 | [MCP 2025-11-25 Lifecycle — Shutdown](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#shutdown) | — |
| tool을 내놓는 server는 `tools` capability를 선언한다 | [MCP 2025-11-25 Tools — Capabilities](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#capabilities) | MUST |
| 믿을 수 없는 server가 준 tool `annotations`는 믿지 않는다 | [MCP 2025-11-25 Tools — Tool](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#tool) | MUST |
| tool 실행 실패는 `isError: true` 결과로 오고, client는 그 결과를 LLM에게 넘긴다 | [MCP 2025-11-25 Tools — Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling) | SHOULD |
| stdio 메시지는 줄바꿈으로 나누고, server의 stdout에는 MCP 메시지만 쓴다 | [MCP 2025-11-25 Transports — stdio](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#stdio) | MUST NOT |
| 메시지마다 새 `POST`를 보내고, `Accept`에 `application/json`과 `text/event-stream`을 모두 적는다 | [MCP 2025-11-25 Transports — Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) | MUST |
| notification을 받아들이면 본문 없는 `202`로 답한다. 요청에는 JSON이나 SSE로 답하고, client는 둘 다 처리한다 | [MCP 2025-11-25 Transports — Sending Messages to the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#sending-messages-to-the-server) | MUST |
| client는 `GET`으로 SSE stream을 열 수 있고, server는 SSE나 `405`로 답한다 | [MCP 2025-11-25 Transports — Listening for Messages from the Server](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) | MAY, MUST |
| SSE event ID는 session 안의 모든 stream에서 겹치지 않는다 | [MCP 2025-11-25 Transports — Resumability and Redelivery](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#resumability-and-redelivery) | MUST |
| server는 `initialize` 응답에서 session ID를 줄 수 있고, 받은 client는 이후 모든 요청에 넣는다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MAY, MUST |
| session ID가 없는 요청(초기화 제외)에는 `400`으로 답한다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | SHOULD |
| 끝난 session에는 `404`로 답하고, `404`를 받은 client는 새로 `initialize` 한다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | MUST |
| 더 쓰지 않을 session은 `DELETE`로 끝내고, server는 이를 `405`로 거절할 수 있다 | [MCP 2025-11-25 Transports — Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management) | SHOULD, MAY |
| HTTP header 이름은 대소문자를 가리지 않는다 | [RFC 9110 §5.1](https://www.rfc-editor.org/rfc/rfc9110#section-5.1) | — |
| client는 초기화 뒤 모든 요청에 `MCP-Protocol-Version`을 넣는다 | [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) | MUST |
| header가 없고 알 방법도 없으면 server는 `2025-03-26`으로 여긴다 | [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) | SHOULD |
| 형식이 틀리거나 지원하지 않는 버전에는 `400`으로 답한다 | [MCP 2025-11-25 Transports — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header) | MUST |
| `MCP-Protocol-Version` header는 2025-06-18부터 쓴다 | [MCP 2025-06-18 Key Changes](https://modelcontextprotocol.io/specification/2025-06-18/changelog) | — |
| HTTP transport는 authorization 명세를 따르고, stdio transport는 그 대신 환경에서 credentials를 가져온다 | [MCP 2025-11-25 Authorization — Protocol Requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protocol-requirements), [MCP 2025-11-25 Overview — Auth](https://modelcontextprotocol.io/specification/2025-11-25/basic#auth) | SHOULD, SHOULD NOT |

[목차](README.md) · [2장 →](02-why-oauth.md)
