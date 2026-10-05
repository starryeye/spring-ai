# MCP tool 목록과 권한 practice (`mcp-tool-visibility`) — 설계

## 목표

MCP Server가 **사용자가 원래 할 수 없는 tool은 목록에서 숨기고**, 할 수 있지만 아직 허락하지 않은 tool은 **보여 준 뒤 부를 때 step-up**하게 하는 practice와 학습 문서를 만든다.
그리고 사용자마다 달라진 tool 목록을 client가 표준 방식(2026-07-28 Caching 규칙)으로 cache하게 한다.
독자는 안내서 1\~11장을 읽은 사람이다. 다 보고 나면 권한과 scope를 나눠 tool 목록을 설계하고, 사용자별 목록을 섞이지 않게 cache할 줄 알게 된다.

최종 목표(2026-09-28 사용자 결정)로 가는 로드맵의 세 번째 단계다.
로드맵: scope와 step-up(완료, PR #6) → stateless와 handle(완료, PR #11) → **tool 목록과 권한** → CIMD → SDK 2.2(MCP 2026-07-28)로 올리기.

**출발점이 된 질문(2026-10-05 사용자):** 지금 token의 scope로 목록을 거르면, 모델은 scope가 없는 tool을 보지 못해 부르지 않는다.
그러면 `403`이 나지 않아 step-up의 입구가 사라진다.
그래서 "지금 token에 있는가"가 아니라 "이 사용자가 언젠가 받을 수 있는가"로 숨길지를 정한다.

## 결정

| # | 항목 | 결정 | 누가 |
|---|---|---|---|
| A | 숨기는 기준 | 사용자 권한(역할)이다. 권한은 MCP Server가 자기 역할 표로 판단한다. token은 누구인지(`sub`)만 알려 준다 | 사용자 |
| B | Authorization Server | 바꾸지 않는다. 손님에게도 `products:write` consent가 가능하지만, 서버가 그 scope로 challenge를 보내지 않으므로 실제로 묻는 일은 없다. 받더라도 서버가 역할로 거절한다 | 사용자 |
| C | agent의 tool 목록 cache | 2026-07-28 Caching 규칙을 client에서 미리 따른다. access token별 cache(`"private"`), client가 정한 TTL 5분, "모르는 tool" 오류와 token 변경에 다시 받기 | 사용자 |
| D | 시작점과 버전 | `mcp-stateless-handle`을 복사한다. Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.1 그대로다(2026-10-05 확인: SDK 2.2 미출시, Spring AI 2.1.0-M1은 새 MCP 형식 없음) | Claude |
| E | 표준 기준 | 요청 형식은 2025-11-25 그대로다. 목록을 거르는 규칙은 2026-07-28 server/tools(authorization에 따라 달라져도 되지만 연결마다 달라지면 안 됨, 순서는 늘 같게)를, cache는 2026-07-28 Caching을 따른다 | Claude |
| F | 숨긴 tool을 부르면 | "모르는 tool" JSON-RPC 오류로 답한다. 응답은 SDK가 정말 없는 tool에 주는 오류와 똑같이 맞춰, 숨긴 tool이 있다는 사실을 알리지 않는다 | Claude |
| G | tool 호출 검사 순서 | 보이는 tool인가 → token에 scope가 있는가. 거꾸로면 손님이 받을 수 없는 scope로 step-up을 시작한다 | Claude |

## 1. 개념 (학습 문서가 설명할 것)

**숨기기와 step-up은 부딪친다**
- 모델은 `tools/list`에 있는 tool만 부른다. scope가 없는 tool을 숨기면 모델은 그 tool을 모르고, 사용자가 부탁해도 "그런 기능이 없다"고 답한다.
- 그러면 MCP Server가 `403 insufficient_scope`를 보낼 일이 없어서 step-up이 시작되지 않는다.
- 그래서 두 질문을 나눈다. "이 사용자가 언젠가 받을 수 있는가"가 아니오면 숨긴다. "지금 token에 있는가"가 아니오면 보여 주고 부를 때 step-up한다.

**권한과 scope**
- 권한은 사용자가 가게에서 원래 할 수 있는 일이다. 점원은 재고를 바꿀 수 있고, 손님은 바꿀 수 없다. MCP Server가 자기 데이터로 안다.
- scope는 사용자가 이 client에게 맡긴 범위다. token에 들어 있다(10장).
- 실제로 할 수 있는 일은 둘이 겹치는 부분이다. 권한 밖은 숨기고, 권한 안이지만 scope 밖은 step-up한다.

**실제 사례**
- GitHub 원격 MCP Server: classic PAT는 scope가 처음부터 정해져 늘릴 수 없으므로 쓸 수 없는 tool을 숨긴다. OAuth는 늘릴 수 있으므로 보여 주고 부를 때 scope를 요청한다(10장에 이미 인용).
- 2026-07-28 server/tools: 목록은 요청의 authorization에 따라 달라져도 된다(MAY). 연결마다나 다른 요청의 부수 효과로 달라지면 안 된다(MUST NOT). 순서는 늘 같게 한다(SHOULD). 숨길지 호출 때 막을지는 정하지 않았다.

**사용자별 목록의 cache**
- 사용자마다 목록이 다르면, 모든 사용자가 같이 쓰는 client가 목록 하나를 cache할 때 다른 사용자의 목록이 새어 나간다.
- 2026-07-28 Caching: 서버는 `tools/list` 결과에 `ttlMs`와 `cacheScope`를 반드시 넣는다. 사용자마다 거른 목록은 `"private"`이고, 다른 access token이면 다른 cache를 써야 한다(MUST NOT 공유). TTL 안이면 다시 받지 않고, 지나면 다음에 필요할 때 다시 받는다. TTL을 polling 주기로 쓰지 않는다. 목록 변경 알림은 즉시 무효화이고, "모르는 tool" 같은 오류가 나면 TTL 전이라도 다시 받아도 된다.
- 2025-11-25에는 이 field가 없다. `ttlMs`가 없는 옛 서버에 대해 client는 0(늘 stale)으로 보고 자기 판단을 더할 수 있다. 이 practice의 agent는 TTL 5분을 스스로 정하고, 2026-07-28의 다른 규칙은 그대로 따른다. SDK 2.2로 올리면 TTL을 서버의 `ttlMs`로 바꾸기만 하면 된다.
- stateless 서버는 목록 변경 알림을 보낼 GET stream이 `405`라서, 이 practice에서는 TTL과 오류로만 다시 받는다.

## 2. 구성

| module | 포트 | package |
|---|---|---|
| auth-server | 9050 | `dev.starryeye.visibility.authserver` |
| shop-mcp-server | 8161 (`/mcp`) | `dev.starryeye.visibility.mcpserver` |
| shop-agent | 8160 | `dev.starryeye.visibility.agent` |
| local-client | (loopback redirect `http://127.0.0.1:8123/callback`) | `dev.starryeye.visibility.localclient` |

- `client_id`는 `visibility-shop-agent`(confidential, secret `visibility-shop-agent-secret`)와 `local-mcp-client`(public)다. cookie는 `VISIBILITYAUTHSESSIONID`, `VISIBILITYAGENTSESSIONID`다.
- 계정은 `user`/`password`(점원), `user2`/`password`(손님)다.
- scope는 그대로다: `products:read`(기본), `products:write`(`updateStock`), `orders:write`(`checkout`). PRM의 `scopes_supported`와 `401`의 `scope`는 `products:read`만이다.

## 3. 역할과 보이는 tool

| 역할 | 계정 | 받을 수 있는 scope |
|---|---|---|
| 점원(`STAFF`) | `user` | `products:read`, `products:write`, `orders:write` |
| 손님(`CUSTOMER`) | `user2`, 표에 없는 모든 사용자 | `products:read`, `orders:write` |

- 어떤 tool이 보이는지는 그 tool의 `@RequiredScope`를 이 역할이 받을 수 있는지로 정한다. 새 표를 따로 만들지 않고 tool별 scope 정보를 그대로 쓴다.
- 결과: 손님에게는 `updateStock`만 숨겨진다.

| tool | 점원 | 손님 |
|---|---|---|
| `searchProducts`, `getStock`, `createBasket`, `addItem`, `getBasket` | 보임 | 보임 |
| `checkout` | 보임, 부르면 step-up(`orders:write`) | 보임, 부르면 step-up(`orders:write`) |
| `updateStock` | 보임, 부르면 step-up(`products:write`) | 숨김, 부르면 "모르는 tool" |

## 4. 흐름

### 4.1 tool 목록
1. client가 사용자 token으로 `tools/list`를 보낸다.
2. MCP Server는 token의 `sub`로 역할을 찾고, 그 역할이 받을 수 있는 scope로 tool을 거른다. 순서는 SDK가 준 순서 그대로다(bean마다 메서드 이름 순, bean은 생성 순). 한 서버 안에서는 늘 같지만 실행 환경마다 다를 수 있어서, 테스트는 절대 순서가 아니라 반복 요청의 순서와 상대 순서를 비교한다.
3. 같은 사용자는 step-up 전후로 같은 목록을 받는다(역할로만 달라진다).

### 4.2 tool 호출
1. 보이는 tool인가. 아니면 "모르는 tool" JSON-RPC 오류(HTTP 200, `WWW-Authenticate` 없음)다. 정말 없는 이름일 때와 응답이 같다.
2. token에 그 tool의 scope가 있는가. 아니면 지금처럼 `403 insufficient_scope`와 모자란 scope다.
3. 둘 다 통과하면 tool이 돈다.

### 4.3 agent의 목록 cache
1. 질문이 오면 `ChatController`가 현재 사용자의 access token을 key로 보관소에서 목록을 찾는다.
2. 없거나 TTL(5분)이 지났으면 그 token으로 `tools/list`를 받아 둔다. 만료 항목은 꺼낼 때 지운다.
3. 꺼낸 목록을 그 질문에만 넣는다. 앱 시작 때 고정한 목록은 쓰지 않는다.
4. tool 호출이 "모르는 tool" 오류로 끝나면 그 token의 항목을 버린다. 같은 turn에서 바로 다시 받지는 않는다. 모델에게는 그 오류 문장이 tool 결과로 가고 turn은 이어진다.
5. step-up이나 token 갱신으로 token이 바뀌면 key가 달라져 다시 받는다.
6. 모델이 이 질문의 목록에 없는 tool을 부르면 Spring AI가 MCP 요청 없이 `IllegalStateException("No ToolCallback found for tool name: …")`으로 stream을 끝낸다. agent는 이것을 받아 그 turn을 대화 기억에서 되돌리고, 화면에 "이 계정에서 쓸 수 없는 tool"이라는 안내 event를 보낸다.

## 5. module별 변경 (`mcp-stateless-handle` 대비)

### auth-server
- 포트·issuer·client 이름·cookie만 바꾼다. 역할을 모른다(결정 B).

### shop-mcp-server
- 사용자 역할 표(`sub` → 역할)와 역할별 받을 수 있는 scope. 표에 없는 사용자는 손님이다.
- 보이는 tool을 계산하는 곳(가칭 `ToolVisibility`): 역할 + `ToolScopeRegistry`의 tool별 scope.
- `tools/list` 결과를 역할로 거르는 곳: SDK·Spring AI에 사용자별로 목록을 거르는 hook이 없으므로, transport와 server 사이에서 결과를 거른다. 정확한 연결 방법은 구현 첫 task에서 SDK 2.0.1 코드로 정한다.
- `ToolScopeFilter`의 `tools/call` 검사에 "보이는 tool인가"를 scope 검사보다 먼저 더한다(결정 F, G).
- `updateStock`과 `checkout`의 tool 설명에 "처음 부르면 사용자에게 권한을 묻는다"를 더한다. 모델이 권한 때문에 그 tool을 피하지 않게 하려는 것이다.
- 로그: `tools/list` 응답마다 사용자·역할·보인 tool 수를 남긴다.

### shop-agent (웹 agent)
- 자동 구성의 tool 목록 provider를 끄고, access token별 tool 목록 보관소(가칭 `UserToolCatalog`)를 둔다. key는 token 값의 hash, TTL 5분(`Clock` 주입), 만료 항목은 꺼낼 때 지운다.
- `ChatController`가 질문마다 현재 사용자의 목록을 꺼내 넣는다.
- "모르는 tool" 오류를 받으면 그 token의 항목을 버린다. Spring AI 2.0.1의 `SyncMcpToolCallback`은 `McpError`를 감싸지 않고 그대로 던져서 tool 실행 예외 처리(`ToolExecutionExceptionProcessor`)까지 오지 않는다. 그래서 목록의 callback마다 감싸는 callback을 두어, "모르는 tool"이면 항목을 버리고 `ToolExecutionException`으로 바꿔 모델에게 문장으로 돌려준다.
- 목록에 없는 tool을 모델이 부를 때의 `IllegalStateException`은 `ChatEvents`가 받아 turn을 되돌리고 `tool-unavailable` event를 보낸다(4.3의 6).
- 로그: 목록을 새로 받을 때와 cache에서 꺼낼 때를 구분해 남긴다.
- 대화 기억, step-up 되돌리기, "새 대화"는 그대로다.

### local-client (사용자 기기의 앱)
- 연결 직후 받은 tool 목록을 한 줄로 출력한다. step-up으로 token이 바뀌면 다시 받아 한 번 더 출력한다.
- 손님으로 login했으면 숨겨진 `updateStock`을 코드에서 한 번 불러, "모르는 tool" 오류가 오고 `403`·step-up이 없다는 것을 보여 준다.

## 6. 학습 문서

- practice README: "`mcp-stateless-handle`과 다른 점" 중심. 실행, 코드 지도, 직접 확인할 것.
- 안내서 12장 "tool 목록과 권한": 필요성(숨기면 step-up 입구가 사라진다) → 시퀀스 다이어그램 → 역할로 거른 `tools/list`의 실제 요청·응답(점원·손님) → 숨긴 tool 호출과 정말 없는 tool 호출 비교 → 검사 순서 → client의 목록 cache(2025-11-25의 client TTL과 2026-07-28의 `ttlMs`·`cacheScope`) → 실제 사례(GitHub) → 코드 → 다루지 않는 것 → 직접 해 보기 → 정리 → 명세 근거.
- 함께 고칠 곳: 10장(scope와 step-up)과 11장 nav에서 12장으로 잇기, 준수표 "`mcp-tool-visibility`에서 달라지는 행", 부록 `reference-api.md`의 `tools/list` 행, 안내서 목차, 저장소 README, stateless README "더 읽을 것", 문서 스킬의 장 목록과 "예시 값" 예외에 12장.
- 캡처: curl walkthrough(점원·손님의 `tools/list`, 손님의 `updateStock`과 없는 tool 호출 비교, 점원의 `updateStock` `403`, 같은 사용자의 목록 순서 반복 확인), local-client 실행(점원, 손님).
- 스킬 `writing-practice-docs` 규칙을 따른다. 제품 예시는 OpenAI·Anthropic 제품만 든다(MCP Server 사례인 GitHub는 10장처럼 쓴다).

## 7. 테스트

- MCP Server
  - 역할별 보이는 tool, 표에 없는 사용자는 손님.
  - 실제 filter chain과 transport: 두 사용자 JWT의 `tools/list`가 다르고 순서가 같다, 같은 요청을 두 번 보내도 순서가 같다.
  - 손님의 `updateStock`과 같은 길이의 없는 tool(`updateStack`)의 응답이 같다(상태, header, 이름만 바꾼 본문). `data`와 `Content-Length`에 이름이 들어가므로 길이가 같은 이름과 비교한다.
  - 손님의 `updateStock`은 `403`이 아니다(검사 순서). 점원의 조회 token `updateStock`은 `403 insufficient_scope`다.
- agent
  - token마다 목록이 따로다. 다른 token으로 목록이 새지 않는다.
  - 5분 안에는 다시 받지 않고, 지나면 다시 받는다(`Clock`).
  - token이 바뀌면 다시 받는다.
  - "모르는 tool" 오류 뒤 그 token의 항목이 없어지고, 모델은 그 오류 문장을 tool 결과로 받는다.
  - 목록에 없는 tool을 모델이 부르면 turn이 되돌려지고 `tool-unavailable` event가 간다.
  - `ChatController`가 질문마다 그 사용자의 목록을 넣는다.
- local-client: 가짜 MCP Server가 사용자마다 다른 목록을 줄 때의 출력, 숨긴 tool 호출 오류 처리, step-up 뒤 목록 다시 받기.
- browser 확인(controller): 손님의 "p1 재고를 10개로 바꿔 줘"에 consent 카드 없이 할 수 없다는 답, 점원은 consent 카드, agent 로그의 cache 사용과 step-up 뒤 다시 받기.

## 8. 구현 전에 확인한 것 (2026-10-05, SDK 2.0.1·Spring AI 2.0.1 bytecode와 실행으로 확인)

- `tools/list` 거르는 자리: `WebMvcStatelessServerTransport`는 `final`이라 상속할 수 없다. `McpStatelessServerTransport`를 구현한 감싸는 transport를 `@Primary` bean으로 두고, `setMcpHandler`에서 SDK handler를 감싼다. 자동 구성의 server는 interface 타입으로 받아 감싼 쪽을, router는 구체 타입으로 받아 원래 bean을 쓴다. tool spec 단위로 감싸면 SDK의 입력 검증이 먼저 돌아 숨긴 tool이 드러나므로 쓰지 않는다.
- 없는 tool의 `tools/call` 응답: HTTP 200, `application/json`, `WWW-Authenticate` 없음, 본문 `{"jsonrpc":"2.0","id":…,"error":{"code":-32602,"message":"Unknown tool: invalid_tool_name","data":"Tool not found: <이름>"}}`. message는 고정 문자열이고 이름은 `data`에만 있다. 감싸는 handler가 숨긴 tool에 같은 값을 만든다.
- `ToolScopeFilter`: 기본 scope 검사 뒤, 숨긴 tool이면 tool별 scope 검사를 건너뛰고 transport로 넘긴다. 그래서 정말 없는 tool과 같은 뒤 단계를 지나 응답이 같다.
- agent: `spring.ai.mcp.client.toolcallback.enabled: false`로 자동 provider를 끈다. `List<McpSyncClient>` bean(`mcpSyncClients`)은 남는다. callback은 `SyncMcpToolCallback.builder().mcpClient(c).tool(t).prefixedToolName(t.name()).build()`로 만든다. 질문마다 `.tools(...)`로 넣은 callback만 tool loop가 쓴다.
- agent의 `listTools()`를 요청 thread에서 부르면 그 사용자의 token이 붙는다. cache key의 token은 token을 붙이는 customizer와 같은 `OAuth2AuthorizedClientManager.authorize(...)`로 얻는다(만료면 갱신된 같은 token).
- `@SpringBootTest`는 `main()`을 돌리지 않아 `Hooks.enableAutomaticContextPropagation()`이 꺼져 있다. tool loop thread의 token을 확인하는 테스트는 hook을 직접 켠다.
- local-client: `callTool`은 JSON-RPC 오류를 `McpError`로 던진다(step-up 없음). step-up 뒤 새 `listTools()` 요청은 새 token으로 간다.

## 다루지 않는 것

- Authorization Server가 역할을 보고 scope를 줄이는 방식(결정 B). 안내서에 한 줄로 적는다.
- client 등록 단위로 숨기는 변형(예: 읽기 전용 client).
- 2026-07-28의 실제 `ttlMs`·`cacheScope` field와 목록 변경 알림(`subscriptions/listen`). SDK 2.2 practice에서 한다.
- tool별 scope를 알리는 표준(SEP-1488, 열림)과 ChatGPT의 `securitySchemes`.
- 역할의 영속 저장과 실행 중 변경 UI.
- `resources`·`prompts` 목록 거르기.
- 기존 practice의 변경.
